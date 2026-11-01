#!/usr/bin/env bun
/**
 * `vpx _dev` (or `bun run dev`): VRcode's development stack in mprocs (scripts/dev/mprocs.yaml):
 *   mac    VRcode Mac under `cargo tauri dev`, with the Swift service and xmux bridge it runs
 *   quest  the Quest app: adb tunnels, install and relaunch, connection key, and its log
 * Other arguments go to mprocs. --force first quits a running dev session, or a VRcode Mac holding
 * its ports; an installed VRcode Mac stopped that way is started again when the session ends.
 */
import { existsSync } from "node:fs";
import { join } from "node:path";
import { DEV_SERVER_PORT, prepareDevSession, restartInstalledApp, SERVICE_PORTS, TAURI_DEV_PORT, type DevSession } from "./lib/dev-session";
import { repoRoot, run } from "./lib/process";

const usage = `Usage: vpx _dev [--force] [mprocs options]   (or: bun run dev)
Runs VRcode's development stack in mprocs:
  mac    VRcode Mac under cargo tauri dev, with vrcode-mac (debug) and xmux-bridge; Rust, UI and
         Swift changes restart it
  quest  the Quest app: adb tunnels, :app:installDebug and relaunch, connection key, then its log
In mprocs, r restarts the selected process and q quits.
  --force  quit a running dev session first, or a VRcode Mac holding its ports (an installed one
           is started again when this session ends)
Ports: ${DEV_SERVER_PORT} mprocs control, ${TAURI_DEV_PORT} Tauri dev server, ${SERVICE_PORTS.join("/")} bridge/speech/relay.
`;

const args = process.argv.slice(2);
const separator = args.indexOf("--");
const launcherArgs = separator < 0 ? args : args.slice(0, separator);
// Help and version must remain read-only, even when combined with --force.
const informational = launcherArgs.some((arg) => ["--help", "-h", "--version", "-V"].includes(arg));
const force = launcherArgs.includes("--force");
const mprocsArgs = [
  ...launcherArgs.filter((arg) => arg !== "--force"),
  ...(separator < 0 ? [] : args.slice(separator)),
];

if (!existsSync(join(repoRoot, "node_modules/mprocs"))) {
  await run([process.execPath, "install", "--frozen-lockfile"], { cwd: repoRoot, timeout: 300_000, inherit: true });
}
const { default: mprocsBin } = (await import("mprocs")) as { default: string };

let session: DevSession | undefined;
if (launcherArgs.includes("--help") || launcherArgs.includes("-h")) console.log(usage);
if (!informational) {
  try {
    session = await prepareDevSession({
      force,
      mprocsBin,
      serverPort: DEV_SERVER_PORT,
      servicePorts: [TAURI_DEV_PORT, ...SERVICE_PORTS],
    });
  } catch (error) {
    console.error(`[dev] ${error instanceof Error ? error.message : error}`);
    process.exit(1);
  }
}

const mprocs = Bun.spawn(
  [
    mprocsBin,
    "--config",
    join(import.meta.dir, "dev/mprocs.yaml"),
    "--server",
    `127.0.0.1:${DEV_SERVER_PORT}`,
    ...mprocsArgs,
  ],
  {
    cwd: repoRoot,
    stderr: "inherit",
    stdin: "inherit",
    stdout: "inherit",
  },
);

process.exitCode = await mprocs.exited;
if (session?.stoppedInstalledApp) restartInstalledApp();
