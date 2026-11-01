#!/usr/bin/env bun
/**
 * The dev session's `mac` process: builds the Swift service (debug) and the xmux bridge, stages
 * them as VRcode Mac's sidecars, and runs the menu-bar app with `cargo tauri dev`. Tauri rebuilds
 * and restarts the app when mac-app/ changes; this script does the same when the Swift service's
 * sources change. Its arguments go to vrcode-mac, as with the packaged app.
 */
import { existsSync, watch } from "node:fs";
import { chmod, mkdir, rename } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, join } from "node:path";
import { TAURI_DEV_PORT } from "../lib/dev-session";
import { repoRoot, run } from "../lib/process";

const tauriDir = join(repoRoot, "mac-app/src-tauri");
const macDir = join(repoRoot, "mac");
const xmuxDir = process.env.XMUX_DIR ?? join(homedir(), "Developer/github/leonsilicon/xmux");
export const binaries = join(tauriDir, "binaries");
/** `cargo tauri dev` copies the sidecars next to the debug app, which runs them from there. */
const debugDir = join(tauriDir, "target/debug");
const devHelpers = ["vrcode-mac", "xmux-bridge"].map((name) => join(debugDir, name));

export const usage = `Usage: bun scripts/dev/mac.ts [Mac service arguments]
Builds vrcode-mac (swift build) and xmux-bridge (from XMUX_DIR, default ${xmuxDir}), stages them in
mac-app/src-tauri/binaries/, then runs VRcode Mac with cargo tauri dev (its dev server on port ${TAURI_DEV_PORT}).
Rust and UI changes restart the app through Tauri; Swift changes in mac/ rebuild the service and
restart it here. The arguments go to vrcode-mac (e.g. --cmux-session NAME).`;

export async function hostTriple() {
  const triple = (await run(["rustc", "-vV"])).match(/^host: (.+)$/m)?.[1];
  if (!triple) throw new Error("rustc -vV did not report a host triple");
  return triple;
}

/** The staged sidecar Tauri copies into the dev app. */
export const sidecar = (name: string, triple: string) => join(binaries, `${name}-${triple}`);

/**
 * Replaces `destination` atomically, and only when its bytes differ: Tauri's build script reruns
 * (rebuilding the app) whenever a sidecar's modification time changes.
 */
export async function stage(source: string, destination: string): Promise<boolean> {
  const bytes = await Bun.file(source).bytes();
  const current = Bun.file(destination);
  if (await current.exists() && current.size === bytes.length) {
    const existing = await current.bytes();
    if (Buffer.from(existing.buffer, existing.byteOffset, existing.length).equals(Buffer.from(bytes.buffer, bytes.byteOffset, bytes.length))) return false;
  }
  await mkdir(dirname(destination), { recursive: true });
  const temporary = `${destination}.${process.pid}.tmp`;
  await Bun.write(temporary, bytes);
  await chmod(temporary, 0o755);
  await rename(temporary, destination);
  return true;
}

async function buildSwift(triple: string) {
  await run(["swift", "build", "--package-path", macDir], { timeout: 1_800_000, inherit: true });
  const bin = await run(["swift", "build", "--package-path", macDir, "--show-bin-path"], { timeout: 120_000 });
  return stage(join(bin, "vrcode-mac"), sidecar("vrcode-mac", triple));
}

async function buildBridge(triple: string) {
  if (!existsSync(join(xmuxDir, "Cargo.toml"))) throw new Error(`no xmux checkout at ${xmuxDir}; set XMUX_DIR`);
  await run(["cargo", "build", "--release", "-p", "xmux-bridged", "--bin", "xmux-bridge"], { cwd: xmuxDir, timeout: 3_600_000, inherit: true });
  return stage(join(xmuxDir, "target/release/xmux-bridge"), sidecar("xmux-bridge", triple));
}

/**
 * A debug vrcode-mac runs the agent runner from this checkout (`bun mac/agent/vrcode-agent.ts`), so
 * the bundled copy only has to exist for Tauri; the packaged app is the one that uses it.
 */
async function stageAgentRunner(triple: string) {
  await stage(process.execPath, sidecar("vrcode-bun", triple));
  const bundled = join(tauriDir, "resources/vrcode-agent.js");
  if (!existsSync(bundled)) {
    await run([process.execPath, "build", "--target=bun", join(macDir, "agent/vrcode-agent.ts"), "--outfile", bundled], { timeout: 300_000, inherit: true });
  }
}

/** Builds a sidecar, falling back to the copy already staged when the build fails. */
async function prepare(name: string, triple: string, build: () => Promise<boolean>) {
  try {
    await build();
  } catch (error) {
    if (!existsSync(sidecar(name, triple))) throw error;
    console.error(`[dev] ${name} did not build (${error instanceof Error ? error.message : error}); using the copy staged before.`);
  }
}

type Process = { pid: number; ppid: number; command: string };

function processes(): Process[] {
  const listing = Bun.spawnSync(["ps", "-axo", "pid=,ppid=,comm="], { stdout: "pipe", stderr: "ignore" }).stdout.toString();
  return listing.split("\n").flatMap((line) => {
    const match = line.trim().match(/^(\d+)\s+(\d+)\s+(.+)$/);
    return match ? [{ pid: Number(match[1]), ppid: Number(match[2]), command: match[3] }] : [];
  });
}

/**
 * The dev app (a descendant of `cargo tauri dev`, which starts it by a relative path) and its
 * helpers, which Tauri's sidecar lookup starts by absolute path and which outlive a killed app.
 */
function devProcesses(tauri: Tauri, { app = true, helpers = true } = {}): number[] {
  const all = processes();
  const tree = new Set([tauri.pid]);
  for (let grew = true; grew;) {
    grew = false;
    for (const entry of all) if (tree.has(entry.ppid) && !tree.has(entry.pid)) { tree.add(entry.pid); grew = true; }
  }
  return all.filter((entry) =>
    (app && tree.has(entry.pid) && basename(entry.command) === "vrcode-mac-app")
    || (helpers && devHelpers.includes(entry.command))).map((entry) => entry.pid);
}

async function stopped(find: () => number[], timeout: number) {
  const deadline = Date.now() + timeout;
  while (find().length) {
    if (Date.now() > deadline) return false;
    await Bun.sleep(100);
  }
  return true;
}

function signal(pids: number[], name: NodeJS.Signals) {
  for (const pid of pids) {
    try { process.kill(pid, name); } catch { /* already gone */ }
  }
}

type Tauri = ReturnType<typeof Bun.spawn>;

function startTauri(args: string[]): Tauri {
  console.log("[dev] Starting VRcode Mac (cargo tauri dev)");
  return Bun.spawn(["cargo", "tauri", "dev", "--port", String(TAURI_DEV_PORT), ...(args.length ? ["--", "--", ...args] : [])], {
    cwd: tauriDir, stdin: "inherit", stdout: "inherit", stderr: "inherit",
  });
}

/**
 * Stops the dev app before `cargo tauri dev`: SIGTERM lets the app stop its helpers, while Tauri's
 * own shutdown kills the app outright and leaves them holding the service ports.
 */
async function stopTauri(tauri: Tauri) {
  signal(devProcesses(tauri, { helpers: false }), "SIGTERM");
  if (!await stopped(() => devProcesses(tauri), 6_000)) {
    signal(devProcesses(tauri, { app: false }), "SIGTERM");
    if (!await stopped(() => devProcesses(tauri), 3_000)) signal(devProcesses(tauri), "SIGKILL");
  }
  if (tauri.exitCode === null) {
    tauri.kill("SIGINT");
    const timer = setTimeout(() => tauri.kill("SIGKILL"), 5_000);
    await tauri.exited;
    clearTimeout(timer);
  }
}

export async function main(args = Bun.argv.slice(2)) {
  if (args.includes("--help") || args.includes("-h")) { console.log(usage); return; }
  const triple = await hostTriple();
  await prepare("xmux-bridge", triple, () => buildBridge(triple));
  await stageAgentRunner(triple);
  await prepare("vrcode-mac", triple, () => buildSwift(triple));

  let tauri = startTauri(args);
  let restart: Promise<void> | undefined;
  let stopping = false;

  // Swift changes: rebuild, then restart the app only if the service binary changed.
  let building = false, pending = false;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const rebuild = async () => {
    if (building) { pending = true; return; }
    building = true;
    console.log("[dev] mac/ changed; rebuilding vrcode-mac");
    let changed = false;
    try {
      changed = await buildSwift(triple);
    } catch (error) {
      console.error(`[dev] vrcode-mac did not build (${error instanceof Error ? error.message : error}); the running service is unchanged.`);
    }
    building = false;
    if (changed && !stopping) {
      restart = (async () => { await stopTauri(tauri); tauri = startTauri(args); })();
      await restart;
      restart = undefined;
    }
    if (pending) { pending = false; void rebuild(); }
  };
  const watcher = watch(macDir, { recursive: true }, (_event, file) => {
    if (!file || !/^(?:Sources\/.+\.swift|Package\.swift|Info\.plist)$/.test(String(file))) return;
    clearTimeout(timer);
    timer = setTimeout(() => void rebuild(), 750);
  });

  const shutdown = async () => {
    if (stopping) return;
    stopping = true;
    watcher.close();
    clearTimeout(timer);
    await restart;
    await stopTauri(tauri);
    process.exit(0);
  };
  for (const name of ["SIGINT", "SIGTERM", "SIGHUP"] as const) process.on(name, () => void shutdown());

  for (;;) {
    const current = tauri;
    const code = await current.exited;
    if (stopping) return;
    if (restart) { await restart; continue; }
    if (current !== tauri) continue;
    watcher.close();
    console.log(`[dev] VRcode Mac exited (${code}). Press r to restart it.`);
    process.exitCode = code ?? 1;
    return;
  }
}

if (import.meta.main) main().catch((error) => { console.error(`[dev] mac: ${error.message}`); process.exitCode = 1; });
