#!/usr/bin/env bun
/**
 * `vpx _tauri`: builds VRcode Mac (the Tauri menu-bar app bundling the Swift service and the xmux
 * bridge, see run-mac-service.ts), installs it as the per-user LaunchAgent and restarts it, then
 * confirms the new build is the one running: the LaunchAgent has a live process and the service
 * logged that it is listening again. Mac service arguments may follow; --bluetooth is always on.
 * `vpx _tauri --uninstall` removes it. Restarting VRcode Mac briefly disconnects the headset's
 * terminals and speech.
 */
import { stat } from "node:fs/promises";
import { join } from "node:path";
import { LABEL, appExecutable, installedApp, logPath, main as macService } from "./run-mac-service";
import { run } from "./lib/process";

export const help = `Usage: vpx _tauri [Mac service arguments] | vpx _tauri --uninstall
Builds VRcode Mac, installs it as the LaunchAgent ${LABEL} (with --bluetooth) and checks the new build runs.
Log: ${logPath}`;

/** The pid in `launchctl print` output, if the job is running. */
export function runningPid(listing: string) {
  return listing.match(/^\s*pid = (\d+)/m)?.[1];
}

/** The service lines worth showing from the log written since the install. */
export function startupLines(log: string) {
  return log.split("\n").filter((line) => /listening on port|speech[: ]|fatal|failed/.test(line));
}

export async function main(argv = Bun.argv.slice(2)) {
  if (argv.includes("--help")) { console.log(help); return; }
  if (argv.includes("--uninstall")) { await macService(["--uninstall"]); return; }
  const domain = `gui/${process.getuid?.() ?? 501}`;
  const logStart = await stat(logPath).then((info) => info.size, () => 0);
  console.log("Building and installing VRcode Mac; installing restarts it, so the headset reconnects.");
  await macService(["--install", "--bluetooth", ...argv.filter((arg) => arg !== "--bluetooth" && arg !== "--install")]);

  let pid: string | undefined, lines: string[] = [];
  for (let waited = 0; waited < 60_000; waited += 1000) {
    await Bun.sleep(1000);
    pid = runningPid(await run(["launchctl", "print", `${domain}/${LABEL}`]).catch(() => ""));
    const log = await Bun.file(logPath).slice(logStart).text().catch(() => "");
    lines = startupLines(log);
    if (pid && lines.some((line) => line.includes("listening on port"))) break;
  }
  if (!pid) throw new Error(`${LABEL} is not running after the install; see ${logPath}`);
  if (!lines.some((line) => line.includes("listening on port"))) {
    throw new Error(`VRcode Mac (pid ${pid}) has not logged "listening on port" within 60 s; see ${logPath}`);
  }
  const built = (await stat(join(installedApp, appExecutable))).mtime;
  console.log(`VRcode Mac is running (pid ${pid}, app built ${built.toLocaleTimeString()}):`);
  for (const line of lines) console.log(`  ${line}`);
}

if (import.meta.main) main().catch((error) => { console.error(`_tauri: ${error.message}`); process.exitCode = 1; });
