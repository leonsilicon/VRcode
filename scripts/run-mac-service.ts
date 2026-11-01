#!/usr/bin/env bun
import { cp, mkdir, rm } from "node:fs/promises";
import { homedir } from "node:os";
import { dirname, join } from "node:path";
import { repoRoot, run } from "./lib/process";

export const LABEL = "dev.improve.vrcode.mac";
const home = homedir();
export const installedApp = join(home, "Library/Application Support/dev.improve.vrcode/VRcode Mac.app");
export const plistPath = join(home, "Library/LaunchAgents", `${LABEL}.plist`);
export const logPath = join(home, "Library/Logs/vrcode-mac.log");

const usage = `Usage: bun scripts/run-mac-service.ts [--package-only | --install | --uninstall] [Mac service arguments]

  (default)       build, package and open the background app once
  --package-only  build and package without launching
  --install       run it at login and restart it if it exits (per-user LaunchAgent ${LABEL}).
                  The service arguments are saved; the app is copied out of mac/.build so
                  rebuilds and tests never replace a running binary. Log: ${logPath}
  --uninstall     stop it and remove the LaunchAgent and the installed copy`;

/** A background app bundle gives macOS Bluetooth permission a stable application identity. */
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
  const app = await packageApp();
  const serviceArgs = args.filter((arg) => arg !== "--install" && arg !== "--package-only");
  if (args.includes("--install")) await install(domain, app, serviceArgs);
  else if (!args.includes("--package-only")) await run(["open", "-g", "-n", app, "--args", ...serviceArgs]);
}

async function packageApp() {
  const source = join(repoRoot, "mac");
  await run(["swift", "build", "--package-path", source], { timeout: 300_000, inherit: true });
  const bin = await run(["swift", "build", "--package-path", source, "--show-bin-path"], { timeout: 60_000 });
  const app = join(source, ".build/VRcode Mac.app");
  await mkdir(join(app, "Contents/MacOS"), { recursive: true });
  await cp(join(bin, "vrcode-mac"), join(app, "Contents/MacOS/vrcode-mac"));
  await cp(join(source, "Info.plist"), join(app, "Contents/Info.plist"));
  await run(["codesign", "--force", "--sign", "-", "--identifier", LABEL, app]);
  return app;
}

async function install(domain: string, app: string, serviceArgs: string[]) {
  await bootout(domain);
  await rm(installedApp, { recursive: true, force: true });
  await mkdir(dirname(installedApp), { recursive: true });
  await cp(app, installedApp, { recursive: true });
  await mkdir(dirname(plistPath), { recursive: true });
  await mkdir(dirname(logPath), { recursive: true });
  await Bun.write(plistPath, launchAgentPlist(join(installedApp, "Contents/MacOS/vrcode-mac"), serviceArgs, logPath));
  // launchd can still be tearing down the previous instance; bootstrap then reports EIO.
  for (let attempt = 1; ; attempt++) {
    try { await run(["launchctl", "bootstrap", domain, plistPath]); break; }
    catch (error) { if (attempt >= 10) throw error; await Bun.sleep(500); }
  }
  console.log(`Installed ${LABEL}: runs at login and restarts if it exits.\n  args: ${serviceArgs.join(" ") || "(relay + speech defaults)"}\n  log:  ${logPath}`);
}

async function bootout(domain: string) {
  try { await run(["launchctl", "bootout", `${domain}/${LABEL}`], { timeout: 20_000 }); } catch { /* not loaded */ }
}

const xml = (value: string) => value.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

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
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>${xml(log)}</string>
  <key>StandardErrorPath</key><string>${xml(log)}</string>
</dict>
</plist>
`;
}

if (import.meta.main) main().catch((error) => { console.error(`run-mac-service: ${error.message}`); process.exitCode = 1; });
