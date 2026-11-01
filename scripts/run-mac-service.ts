#!/usr/bin/env bun
import { mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { repoRoot, run } from "./lib/process";

export const LABEL = "dev.improve.vrcode.mac";
const home = homedir();
export const installedApp = join(home, "Library/Application Support/dev.improve.vrcode/VRcode Mac.app");
export const plistPath = join(home, "Library/LaunchAgents", `${LABEL}.plist`);
export const logPath = join(home, "Library/Logs/vrcode-mac.log");
/** The Tauri menu-bar app's own executable; it runs the bundled vrcode-mac and xmux-bridge. */
export const appExecutable = "Contents/MacOS/vrcode-mac-app";

const tauriDir = join(repoRoot, "mac-app/src-tauri");
const packagedApp = join(repoRoot, "mac/.build/VRcode Mac.app");
const xmuxDir = process.env.XMUX_DIR ?? join(home, "Developer/github/leonsilicon/xmux");

const usage = `Usage: bun scripts/run-mac-service.ts [--package-only | --install | --uninstall] [Mac service arguments]

Builds VRcode Mac.app: the signed Tauri menu-bar app (mac-app/) bundling the Swift service
(mac/, as vrcode-mac) and the xmux bridge (built from XMUX_DIR, default ${xmuxDir}).
The app passes its arguments to vrcode-mac and runs the bridge on --bridge-port (default 7272)
for --cmux-session (default main); --no-xmux-bridge leaves the bridge out.

  (default)       build, package and open the app once
  --package-only  build and package without launching (mac/.build/VRcode Mac.app)
  --install       run it at login and relaunch it if it crashes (per-user LaunchAgent ${LABEL}).
                  The arguments are saved; the app is copied out of mac/.build so rebuilds and
                  tests never replace a running binary. Quit from the menu bar stays quit.
                  Log: ${logPath}
  --uninstall     stop it and remove the LaunchAgent and the installed copy

Signing uses APPLE_SIGNING_IDENTITY, else the first "Developer ID Application" identity,
else "Apple Development", else ad hoc.`;

export async function main(args = Bun.argv.slice(2)) {
  if (args.includes("--help")) { console.log(usage); return; }
  const domain = `gui/${process.getuid?.() ?? 501}`;
  if (args.includes("--uninstall")) {
    await bootout(domain);
    await rm(plistPath, { force: true });
    await rm(installedApp, { recursive: true, force: true });
    console.log(`Removed ${LABEL}.`);
    return;
  }
  const serviceArgs = args.filter((arg) => arg !== "--install" && arg !== "--package-only");
  if (!args.includes("--install") && !args.includes("--package-only") && await agentLoaded(domain)) {
    throw new Error(`the installed VRcode Mac (${LABEL}) is running and holds the service ports. Run \`vpx _tauri\` to update it, or \`vpx _tauri --uninstall\` first.`);
  }
  const app = await packageApp();
  if (args.includes("--install")) await install(domain, app, serviceArgs);
  else if (!args.includes("--package-only")) await run(["open", "-g", "-n", app, "--args", ...serviceArgs]);
}

async function packageApp() {
  try { await run(["cargo", "tauri", "--version"]); }
  catch { throw new Error("the Tauri CLI is missing. Install it with: cargo install tauri-cli --version '^2' --locked"); }
  if (!existsSync(join(xmuxDir, "Cargo.toml"))) throw new Error(`no xmux checkout at ${xmuxDir}; set XMUX_DIR`);
  const triple = (await run(["rustc", "-vV"])).match(/^host: (.+)$/m)?.[1];
  if (!triple) throw new Error("rustc -vV did not report a host triple");
  const binaries = join(tauriDir, "binaries");
  await mkdir(binaries, { recursive: true });

  // The Swift service, release build, as the vrcode-mac sidecar.
  const mac = join(repoRoot, "mac");
  await run(["swift", "build", "-c", "release", "--package-path", mac], { timeout: 600_000, inherit: true });
  const swiftBin = await run(["swift", "build", "-c", "release", "--package-path", mac, "--show-bin-path"], { timeout: 60_000 });
  await run(["/bin/cp", join(swiftBin, "vrcode-mac"), join(binaries, `vrcode-mac-${triple}`)]);

  // The AI mic's agent runner: Bun itself as the vrcode-bun sidecar (it also runs the ACP agents
  // the runner starts, so the Mac needs no Bun or Node), and the runner bundled into one file that
  // the app carries in its Resources.
  await run([process.execPath, "install", "--frozen-lockfile"], { cwd: repoRoot, timeout: 300_000, inherit: true });
  await run([process.execPath, "build", "--target=bun", join(repoRoot, "mac/agent/vrcode-agent.ts"), "--outfile", join(tauriDir, "resources/vrcode-agent.js")],
    { cwd: repoRoot, timeout: 300_000, inherit: true });
  await run(["/bin/cp", process.execPath, join(binaries, `vrcode-bun-${triple}`)]);

  // The xmux bridge, from the xmux checkout, as the xmux-bridge sidecar.
  await run(["cargo", "build", "--release", "-p", "xmux-bridged", "--bin", "xmux-bridge"], { cwd: xmuxDir, timeout: 1_200_000, inherit: true });
  await run(["/bin/cp", join(xmuxDir, "target/release/xmux-bridge"), join(binaries, `xmux-bridge-${triple}`)]);

  // The menu-bar app; Tauri signs it and both sidecars with the hardened runtime, and notarizes
  // and staples it when notarization credentials are in the environment (see docs/mac-app.md).
  const identity = await buildIdentity();
  console.log(`Signing with: ${identity === undefined ? "the certificate in APPLE_CERTIFICATE" : identity === "-" ? "ad hoc (no identity found)" : identity}`);
  const config = identity === undefined ? [] : ["--config", JSON.stringify({ bundle: { macOS: { signingIdentity: identity } } })];
  await run(["cargo", "tauri", "build", "--bundles", "app", "--ci", ...config], { cwd: tauriDir, timeout: 3_600_000, inherit: true });

  const built = join(tauriDir, "target/release/bundle/macos/VRcode Mac.app");
  await rm(packagedApp, { recursive: true, force: true });
  await mkdir(dirname(packagedApp), { recursive: true });
  await run(["/usr/bin/ditto", built, packagedApp]);
  await run(["codesign", "--verify", "--deep", "--strict", packagedApp]);
  console.log(`Packaged ${packagedApp}`);
  return packagedApp;
}

/**
 * The identity to pass to Tauri. `undefined` in CI: with APPLE_CERTIFICATE (a base64 .p12) and no
 * APPLE_SIGNING_IDENTITY, Tauri imports the certificate into a temporary keychain and signs with
 * its identity. Passing one found locally would not match it.
 */
export async function buildIdentity(env = process.env) {
  if (env.APPLE_CERTIFICATE && !env.APPLE_SIGNING_IDENTITY) return undefined;
  return signingIdentity(env);
}

/** APPLE_SIGNING_IDENTITY, else the most trusted local code-signing identity. */
export async function signingIdentity(env = process.env) {
  if (env.APPLE_SIGNING_IDENTITY) return env.APPLE_SIGNING_IDENTITY;
  return pickIdentity(await run(["security", "find-identity", "-v", "-p", "codesigning"]).catch(() => ""));
}

/** Developer ID keeps privacy grants across rebuilds and machines; ad hoc ("-") loses them per build. */
export function pickIdentity(listing: string) {
  const names = [...listing.matchAll(/^\s*\d+\) [0-9A-F]{40} "([^"]+)"/gm)].map((match) => match[1]);
  return names.find((name) => name.startsWith("Developer ID Application:"))
    ?? names.find((name) => name.startsWith("Apple Development:"))
    ?? "-";
}

async function install(domain: string, app: string, serviceArgs: string[]) {
  await bootout(domain);
  await rm(installedApp, { recursive: true, force: true });
  await mkdir(dirname(installedApp), { recursive: true });
  await run(["/usr/bin/ditto", app, installedApp]);
  await mkdir(dirname(plistPath), { recursive: true });
  await mkdir(dirname(logPath), { recursive: true });
  await Bun.write(plistPath, launchAgentPlist(join(installedApp, appExecutable), serviceArgs, logPath));
  // launchd can still be tearing down the previous instance; bootstrap then reports EIO.
  for (let attempt = 1; ; attempt++) {
    try { await run(["launchctl", "bootstrap", domain, plistPath]); break; }
    catch (error) { if (attempt >= 10) throw error; await Bun.sleep(500); }
  }
  console.log(`Installed ${LABEL}: VRcode Mac runs at login and relaunches if it crashes.\n  args: ${serviceArgs.join(" ") || "(defaults: speech 7273, relay 7274, xmux bridge 7272)"}\n  log:  ${logPath}`);
}

async function agentLoaded(domain: string) {
  try { await run(["launchctl", "print", `${domain}/${LABEL}`]); return true; } catch { return false; }
}

async function bootout(domain: string) {
  try { await run(["launchctl", "bootout", `${domain}/${LABEL}`], { timeout: 20_000 }); } catch { /* not loaded */ }
}

const xml = (value: string) => value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/**
 * KeepAlive relaunches the app after a crash but not after a clean exit, so Quit in the menu
 * bar sticks. The app itself restarts its helpers when they fail.
 */
export function launchAgentPlist(executable: string, args: string[], log: string) {
  const program = [executable, ...args].map((arg) => `    <string>${xml(arg)}</string>`).join("\n");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${LABEL}</string>
  <key>ProgramArguments</key>
  <array>
${program}
  </array>
  <key>AssociatedBundleIdentifiers</key><array><string>${LABEL}</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>${xml(log)}</string>
  <key>StandardErrorPath</key><string>${xml(log)}</string>
</dict>
</plist>
`;
}

if (import.meta.main) main().catch((error) => { console.error(`run-mac-service: ${error.message}`); process.exitCode = 1; });
