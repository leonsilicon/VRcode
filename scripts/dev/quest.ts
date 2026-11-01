#!/usr/bin/env bun
/**
 * The dev session's `quest` process: waits for a Quest, tunnels the Mac's ports to it
 * (quest-link), installs and relaunches the debug app (`vpx _quest`), copies the Mac connection
 * key to it (quest-link pair), then follows the app's log. Restart it (r in mprocs) to reinstall.
 */
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { parseArgs } from "node:util";
import { main as install, PACKAGE } from "../_quest";
import { Adb, findAdb, LinkError, main as questLink, selectDevice } from "../quest-link";
import { appExecutable, installedApp } from "../run-mac-service";
import { repoRoot } from "../lib/process";
import { hostTriple, sidecar } from "./mac";

export const usage = `Usage: bun scripts/dev/quest.ts [--serial SERIAL]
Waits for a Quest (its USB connection when it has one; else --serial or ANDROID_SERIAL), sets up its
adb tunnels, installs and relaunches ${PACKAGE}, copies the Mac connection key, then follows its log.
Installing restarts VRcode on the headset.`;

const message = (error: unknown) => error instanceof Error ? error.message : String(error);

/** The Quest _quest installs to, polling until one is connected and authorized. */
async function waitForQuest(adb: Adb, serial?: string) {
  let last = "";
  for (;;) {
    let reason: string;
    try {
      const devices = await adb.devices(true);
      const usb = !serial && devices.some((d) => d.quest && d.usb && d.state === "device");
      if (serial || devices.some((d) => d.quest)) return selectDevice(devices, serial, usb);
      reason = "none is connected. Plug it in over USB, or reconnect Wi-Fi ADB with bun run quest-link wifi --host IP.";
    } catch (error) {
      if (!(error instanceof LinkError)) throw error;
      reason = error.message;
    }
    if (reason !== last) console.log(`[dev] Waiting for a Quest: ${reason}`);
    last = reason;
    await Bun.sleep(2_000);
  }
}

/** A vrcode-mac to read the connection key with: this session's, a test build's, or the installed app's. */
async function keyReader() {
  const candidates = [
    sidecar("vrcode-mac", await hostTriple().catch(() => "")),
    join(repoRoot, "mac/.build/debug/vrcode-mac"),
    join(installedApp, dirname(appExecutable), "vrcode-mac"),
  ];
  return candidates.find((path) => existsSync(path));
}

/**
 * Stops on SIGINT, SIGTERM or SIGHUP, even when started with SIGINT ignored. Under mprocs this
 * process leads its process group, which also holds the gradle install and logcat; stop those too.
 */
function stopOnSignals() {
  let stopping = false;
  const stop = () => {
    if (stopping) return;
    stopping = true;
    const group = Number(Bun.spawnSync(["ps", "-o", "pgid=", "-p", String(process.pid)], { stdout: "pipe" }).stdout.toString().trim());
    if (group === process.pid) {
      try { process.kill(-group, "SIGTERM"); } catch { /* nothing else in the group */ }
    }
    process.exit(130);
  };
  for (const name of ["SIGINT", "SIGTERM", "SIGHUP"] as const) process.on(name, stop);
}

export async function main(argv = Bun.argv.slice(2)) {
  const { values } = parseArgs({ args: argv, options: { serial: { type: "string" }, help: { type: "boolean" } } });
  if (values.help) { console.log(usage); return; }
  stopOnSignals();
  const adb = new Adb(findAdb());
  const device = await waitForQuest(adb, values.serial ?? process.env.ANDROID_SERIAL);
  const serial = device.serial;

  try {
    await questLink([device.usb ? "usb" : "wifi", "--serial", serial]);
  } catch (error) {
    console.warn(`[dev] The adb tunnels are not set up: ${message(error)}`);
  }

  const since = (await adb.run(["shell", "date", "+%s"], { serial })).trim();
  await install(["--serial", serial]);

  const reader = await keyReader();
  if (!reader) console.warn("[dev] No vrcode-mac is built yet to read the Mac connection key; restart this process once the mac process has built it.");
  else {
    try {
      await questLink(["pair", "--serial", serial, "--mac-binary", reader]);
    } catch (error) {
      console.warn(`[dev] The Mac connection key was not copied: ${message(error)}`);
    }
  }

  const uid = (await adb.run(["shell", "cmd", "package", "list", "packages", "-U", PACKAGE], { serial }))
    .match(new RegExp(`^package:${PACKAGE.replaceAll(".", "\\.")} uid:(\\d+)$`, "m"))?.[1];
  if (!uid) throw new Error(`could not read ${PACKAGE}'s uid for its log`);
  console.log(`[dev] Following ${PACKAGE}'s log (uid ${uid}), including restarts:`);
  const logcat = Bun.spawn([adb.executable, "-s", serial, "logcat", "-v", "color", `--uid=${uid}`, "-T", `${since}.000`], {
    stdin: "ignore", stdout: "inherit", stderr: "inherit",
  });
  process.on("exit", () => logcat.kill());
  const code = await logcat.exited;
  console.log(`[dev] The Quest's log ended (${code}); it may have disconnected. Press r to reinstall and follow it again.`);
}

if (import.meta.main) main().catch((error) => { console.error(`[dev] quest: ${message(error)}`); process.exitCode = 1; });
