#!/usr/bin/env bun
/**
 * `vpx _quest`: builds the debug Quest app, installs it on the connected headset, relaunches it and
 * confirms the new build is the one running (the package's lastUpdateTime changed, the app has a
 * process, and the crash log has nothing from it since the launch). This is AGENTS.md's reinstall
 * procedure in one command. The headset is the one authorized Quest that `adb devices -l` lists (its
 * USB connection when it has one), else --serial or ANDROID_SERIAL. Reinstalling restarts VRcode on
 * the headset.
 */
import { parseArgs } from "node:util";
import { join } from "node:path";
import { Adb, findAdb, selectDevice } from "./quest-link";
import { repoRoot } from "./lib/process";

export const PACKAGE = "dev.improve.vrcode";
const ACTIVITY = `${PACKAGE}/.MainActivity`;

export const help = `Usage: vpx _quest [--serial SERIAL] [--adb PATH]
Builds and installs :app:installDebug on the connected Quest, relaunches ${PACKAGE} and checks it started.
  --serial SERIAL   the headset's USB serial or Wi-Fi IP:port (default: the one connected Quest, or ANDROID_SERIAL)
  --adb PATH        path to adb (auto-detected)`;

/** The `lastUpdateTime` in `dumpsys package` output, if the package is installed. */
export function lastUpdateTime(dumpsys: string) {
  return dumpsys.match(/lastUpdateTime=(.+)/)?.[1].trim();
}

/** Crash-buffer lines about the app. */
export function crashLines(log: string) {
  return log.split("\n").filter((line) => line.includes(PACKAGE));
}

export async function main(argv = Bun.argv.slice(2)) {
  const { values } = parseArgs({ args: argv, options: { serial: { type: "string" }, adb: { type: "string" }, help: { type: "boolean" } } });
  if (values.help) { console.log(help); return; }
  const adb = new Adb(findAdb(values.adb));
  const devices = await adb.devices(true);
  const serial = values.serial ?? process.env.ANDROID_SERIAL;
  // USB is the faster way to push an APK; a headset only on Wi-Fi is used as it is.
  const usb = !serial && devices.some((d) => d.quest && d.usb && d.state === "device");
  const device = selectDevice(devices, serial, usb);
  const target = device.serial;
  const dumpsys = () => adb.run(["shell", "dumpsys", "package", PACKAGE], { serial: target }).catch(() => "");
  const before = lastUpdateTime(await dumpsys());
  console.log(`Installing VRcode on ${target} (${device.usb ? "USB" : "Wi-Fi"}); this restarts the app on the headset.`);

  const gradle = Bun.spawn([join(repoRoot, "gradlew"), ":app:installDebug"], {
    cwd: repoRoot, env: { ...process.env, ANDROID_SERIAL: target }, stdin: "ignore", stdout: "inherit", stderr: "inherit",
  });
  if (await gradle.exited !== 0) throw new Error(":app:installDebug failed; nothing new is on the headset");
  const after = lastUpdateTime(await dumpsys());
  if (!after || after === before) throw new Error(`the headset's ${PACKAGE} did not change (lastUpdateTime ${after ?? "missing"})`);

  const since = (await adb.run(["shell", "date", "+%s"], { serial: target })).trim();
  await adb.run(["shell", "am", "force-stop", PACKAGE], { serial: target });
  await adb.run(["shell", "am", "start", "-n", ACTIVITY], { serial: target });
  let pid = "";
  for (let waited = 0; !pid && waited < 15_000; waited += 500) {
    await Bun.sleep(500);
    pid = await adb.run(["shell", "pidof", PACKAGE], { serial: target }).catch(() => "");
  }
  if (!pid) throw new Error(`${PACKAGE} did not start within 15 s; see adb -s ${target} logcat`);
  await Bun.sleep(5_000); // a crash at startup shows by now
  const crashes = crashLines(await adb.run(["logcat", "-d", "-b", "crash", "-T", `${since}.000`], { serial: target }).catch(() => ""));
  const still = await adb.run(["shell", "pidof", PACKAGE], { serial: target }).catch(() => "");
  if (crashes.length || !still) {
    throw new Error(`${PACKAGE} crashed after the launch:\n${crashes.join("\n") || "(its process is gone)"}`);
  }
  console.log(`VRcode is running on ${target}: lastUpdateTime ${after}, pid ${still}.`);
  console.log(`Its log: adb -s ${target} logcat --pid=${still}`);
}

if (import.meta.main) main().catch((error) => { console.error(`_quest: ${error.message}`); process.exitCode = 1; });
