#!/usr/bin/env bun
/** Exercise the Kotlin TLS tunnel against an isolated native Mac relay and echo services. */
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import { findAdb, ipv4 } from "./quest-link";
import { RelayFixture } from "./lib/relay-fixture";
import { macAddressFor } from "./lib/network";
import { repoRoot, run } from "./lib/process";

export async function main(argv = Bun.argv.slice(2)) {
  const { values, positionals } = parseArgs({ args: argv, allowPositionals: true, options: {
    serial: { type: "string" }, host: { type: "string" }, adb: { type: "string" },
    binary: { type: "string", default: "mac/.build/out/Products/Debug/vrcode-mac" },
    apk: { type: "string", default: "connection/build/outputs/apk/androidTest/debug/connection-debug-androidTest.apk" },
    bluetooth: { type: "boolean" }, controller: { type: "boolean" }, "classic-address": { type: "string" }, help: { type: "boolean" },
  } });
  const usage = "Usage: bun scripts/check-relay-android.ts --serial SERIAL [--host IP] [--adb PATH] [--binary PATH] [--apk PATH] [--controller] [--bluetooth] [--classic-address MAC]";
  if (values.help) { console.log(usage); return; }
  if (!values.serial || positionals.length) throw new Error(usage);
  const adb = [findAdb(values.adb), "-s", values.serial];
  const fixture = await new RelayFixture().start(resolve(repoRoot, values.binary), values.bluetooth);
  try {
    let host = values.host;
    if (!host && values.serial.startsWith("emulator-")) host = "10.0.2.2";
    if (!host) {
      const routeTarget = values.serial.split(":")[0];
      if (!/^(\d+\.){3}\d+$/.test(routeTarget)) throw new Error("Pass --host with the Mac's reachable IPv4 address when selecting a USB device.");
      host = await macAddressFor(ipv4(routeTarget));
    }
    const configuration = { ...fixture.config, host, ...(values["classic-address"] ? { bluetooth: values["classic-address"] } : {}) };
    const encoded = "vrcode:" + Buffer.from(JSON.stringify(configuration)).toString("base64");
    await run([...adb, "install", "-r", "-t", "-g", resolve(repoRoot, values.apk)], { timeout: 60_000 });
    const classes = "dev.improve.vrcode.connection.RelayTunnelTest" + (values.controller ? ",dev.improve.vrcode.connection.ConnectionControllerTest" : "");
    const output = await run([...adb, "shell", "am", "instrument", "-w", "-r", "-e", "class", classes, "-e", "relay_config", encoded,
      "-e", "bluetooth_hardware", String(values.bluetooth ?? false), "dev.improve.vrcode.connection.test/androidx.test.runner.AndroidJUnitRunner"], { timeout: 180_000 });
    console.log(output);
    if (output.includes("FAILURES") || !output.includes("OK (")) throw new Error("Android relay tests failed");
  } catch (error) {
    console.error("Native relay log:\n" + await readFile(fixture.logPath, "utf8"));
    throw error;
  } finally { await fixture.stop(); }
}

if (import.meta.main) main().catch((error) => { console.error(`check-relay-android: ${error.message}`); process.exitCode = 1; });
