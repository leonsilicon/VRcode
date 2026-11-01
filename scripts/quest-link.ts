#!/usr/bin/env bun
/** Connect VRcode's localhost services to a Quest over USB or local Wi-Fi. */
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import { createServer, isIP, type Socket } from "node:net";
import { parseArgs } from "node:util";
import { listenerStatus, macAddressFor } from "./lib/network";
import { repoRoot, run } from "./lib/process";

export class LinkError extends Error {}

export class Device {
  physicalSerial = "";
  constructor(public serial: string, public state: string, public detail: string) {}
  get usb() { return this.detail.includes("usb:"); }
  get quest() { return this.detail.includes("model:Quest"); }
}

export function parseDevices(output: string): Device[] {
  return output.split(/\r?\n/).flatMap((line) => {
    const match = line.trim().match(/^(\S+)\s+(device|offline|unauthorized)(?:\s+(.*))?$/);
    return match ? [new Device(match[1], match[2], match[3] ?? "")] : [];
  });
}

export function selectDevice(devices: Device[], serial?: string, usb = false): Device {
  let candidates = devices.filter((d) => serial ? d.serial === serial : d.quest && d.state === "device");
  if (usb) candidates = candidates.filter((d) => d.usb);
  else if (!serial) {
    const wireless = candidates.filter((d) => !d.usb);
    const identities = new Set(candidates.map((d) => d.physicalSerial));
    if (wireless.length && candidates.length > 1 && identities.size === 1 && !identities.has("")) candidates = wireless;
  }
  if (candidates.length !== 1) {
    const available = devices.map((d) => `${d.serial} (${d.state})`).join(", ") || "none";
    throw new LinkError(`Select one authorized Quest with --serial. Devices: ${available}`);
  }
  const device = candidates[0];
  if (device.state !== "device") throw new LinkError(`${device.serial} is ${device.state}; accept debugging on the headset first.`);
  return device;
}

export function ipv4(value: string): string {
  if (isIP(value) !== 4) throw new LinkError("expected the Quest's IPv4 address");
  const [a, b] = value.split(".").map(Number);
  if (a === 127 || a === 0 || a >= 224 || (a === 169 && b === 254)) throw new LinkError("expected the Quest's routable Wi-Fi address");
  return value;
}

export function wifiAddress(output: string): string {
  for (const match of output.matchAll(/\binet (\d+\.\d+\.\d+\.\d+)\/\d+/g)) {
    try { return ipv4(match[1]); } catch { /* Skip non-routable addresses. */ }
  }
  throw new LinkError("Quest has no IPv4 address on wlan0. Connect it to Wi-Fi first.");
}

export function reverseRules(output: string): Record<string, string> {
  return Object.fromEntries(output.split(/\r?\n/).flatMap((line) => {
    const parts = line.trim().split(/\s+/);
    return parts.length === 3 ? [[parts[1], parts[2]]] : [];
  }));
}

type AdbOptions = { serial?: string; timeout?: number };
export interface AdbRunner { run(args: string[], options?: AdbOptions): Promise<string> }

export class Adb implements AdbRunner {
  constructor(public executable: string) {}
  async run(args: string[], { serial, timeout = 10_000 }: AdbOptions = {}) {
    try { return await run([this.executable, ...(serial ? ["-s", serial] : []), ...args], { timeout }); }
    catch (error) { throw new LinkError(`adb ${args.join(" ")}: ${error instanceof Error ? error.message : error}`); }
  }
  async devices(identify = false) {
    const devices = parseDevices(await this.run(["devices", "-l"]));
    const ready = devices.filter((d) => d.quest && d.state === "device");
    if (identify && ready.length > 1 && ready.some((d) => !d.usb)) {
      for (const device of ready) device.physicalSerial = await this.run(["shell", "getprop", "ro.serialno"], { serial: device.serial });
    }
    return devices;
  }
}

export async function installMappings(adb: AdbRunner, serial: string, wanted: Record<string, string>) {
  const existing = reverseRules(await adb.run(["reverse", "--list"], { serial }));
  for (const [endpoint, destination] of Object.entries(wanted)) {
    if (endpoint in existing && existing[endpoint] !== destination) throw new LinkError(`${endpoint} already forwards to ${existing[endpoint]}; leaving it unchanged.`);
  }
  const added: string[] = [];
  try {
    for (const [endpoint, destination] of Object.entries(wanted)) {
      if (!(endpoint in existing)) {
        await adb.run(["reverse", "--no-rebind", endpoint, destination], { serial });
        added.push(endpoint);
      }
    }
    const actual = reverseRules(await adb.run(["reverse", "--list"], { serial }));
    if (Object.entries(wanted).some(([endpoint, destination]) => actual[endpoint] !== destination)) throw new LinkError("Quest did not retain the reverse mappings; run the command again.");
  } catch (error) {
    for (const endpoint of added) {
      try { await adb.run(["reverse", "--remove", endpoint], { serial }); } catch { /* Preserve the original failure. */ }
    }
    throw error;
  }
}

export async function installReverse(adb: AdbRunner, serial: string, ports: number[]) {
  await installMappings(adb, serial, Object.fromEntries(ports.map((port) => [`tcp:${port}`, `tcp:${port}`])));
}

export async function connectWifi(adb: AdbRunner, host: string, port: number, expectedSerial?: string, timeout = 20_000) {
  const endpoint = `${host}:${port}`;
  const deadline = performance.now() + timeout;
  let last = "";
  while (performance.now() < deadline) {
    try {
      last = await adb.run(["connect", endpoint], { timeout: 5000 });
      if (await adb.run(["get-state"], { serial: endpoint, timeout: 3000 }) === "device") {
        if (expectedSerial && await adb.run(["shell", "getprop", "ro.serialno"], { serial: endpoint }) !== expectedSerial) throw new LinkError("Wi-Fi device identity differs from the USB Quest. Aborting.");
        return endpoint;
      }
    } catch (error) {
      if (error instanceof Error && error.message.includes("identity differs")) throw error;
      last = String(error);
    }
    await Bun.sleep(Math.max(0, Math.min(500, deadline - performance.now())));
  }
  throw new LinkError(`Cannot reach ${endpoint}: ${last}. Keep the Quest awake, authorize this Mac, and put both devices on a network allowing peer traffic (a private router or hotspot if needed). USB is still available; do not unplug until Wi-Fi succeeds.`);
}

async function printLink(serial: string, ports: number[], remote?: number) {
  console.log(`Transport ready: ${serial}`);
  const statuses = await Promise.all(ports.map(async (port) => await listenerStatus(port) ? "accepting TCP connections" : "not listening; start the Mac service"));
  for (const [i, name] of ["legacy xmux bridge", "legacy VRcode speech"].entries()) console.log(`  ${name}: Quest 127.0.0.1:${ports[i]} -> Mac 127.0.0.1:${ports[i]} (${statuses[i]})`);
  console.log(`  secure app relay: ${remote ? `Quest 127.0.0.1:${remote} -> ` : ""}Mac 127.0.0.1:${ports[2]} (${statuses[2]})`);
  console.log("The tunnels are ready even if a service is not running yet. xmux pairing is still required.");
}

export async function probe(adb: Adb, serial: string, directHost?: string) {
  const payload = Buffer.from(crypto.getRandomValues(new Uint8Array(64 * 1024)));
  const peers = new Set<Socket>();
  let received = Buffer.alloc(0);
  const server = createServer((peer) => {
    peers.add(peer);
    peer.setTimeout(5000, () => peer.destroy());
    peer.on("error", () => {});
    peer.on("close", () => peers.delete(peer));
    peer.on("data", (chunk) => {
      received = Buffer.concat([received, chunk]);
      if (received.length >= payload.length) peer.end(received);
    });
  });
  let remote: string | undefined;
  let child: ReturnType<typeof Bun.spawn<"pipe", "pipe", "pipe">> | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(0, directHost ?? "127.0.0.1", resolve); });
    const port = (server.address() as { port: number }).port;
    remote = directHost ? String(port) : await adb.run(["reverse", "tcp:0", `tcp:${port}`], { serial });
    portNumber(remote);
    const started = performance.now();
    child = Bun.spawn([adb.executable, "-s", serial, "shell", "-T", "toybox", "nc", "-w", "3", "-W", "3", "-q", "2", directHost ?? "127.0.0.1", remote], { stdin: "pipe", stdout: "pipe", stderr: "pipe" });
    timer = setTimeout(() => child?.kill("SIGKILL"), 11_000);
    const errors = new Response(child.stderr).text();
    child.stdin.write(payload);
    await child.stdin.flush();
    const reader = child.stdout.getReader();
    const chunks: Uint8Array[] = [];
    let length = 0;
    while (length < payload.length) {
      const { done, value } = await reader.read();
      if (done) break;
      chunks.push(value);
      length += value.length;
    }
    // Android netcat needs stdin kept open until the reply has been drained.
    child.stdin.end();
    const [code, stderr] = await Promise.all([child.exited, errors]);
    const returned = Buffer.concat(chunks);
    if (code !== 0 || !returned.equals(payload) || !received.equals(payload)) throw new LinkError(`Binary tunnel probe failed (exit ${code}, Mac received ${received.length}, Quest returned ${returned.length}/${payload.length} bytes): ${stderr.trim()}`);
    console.log(`PASS: 64 KiB round trip through ${serial} and ${directHost ? `direct LAN ${directHost}` : "a reverse tunnel"} (${((performance.now() - started) / 1000).toFixed(2)}s including adb startup).`);
  } finally {
    if (timer) clearTimeout(timer);
    if (child) { child.kill(); await child.exited; }
    for (const peer of peers) peer.destroy();
    if (server.listening) await new Promise<void>((resolve) => server.close(() => resolve()));
    if (!directHost && remote && /^\d+$/.test(remote)) await adb.run(["reverse", "--remove", `tcp:${remote}`], { serial });
  }
}

export function findAdb(value?: string) {
  if (value) return value;
  const found = Bun.which("adb");
  if (found) return found;
  const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
  for (const candidate of [...(sdk ? [join(sdk, "platform-tools/adb")] : []), join(homedir(), "Library/Android/sdk/platform-tools/adb")]) if (existsSync(candidate)) return candidate;
  throw new LinkError("adb was not found. Install Android SDK Platform Tools or pass --adb PATH.");
}

async function provisionMac(adb: Adb, serial: string, binary: string, port: number) {
  const host = wifiAddress(await adb.run(["shell", "ip", "-o", "-4", "addr", "show", "wlan0"], { serial }));
  const address = await macAddressFor(host);
  let configuration: string;
  try { configuration = await run([binary, "--link-info", "--link-host", address, "--link-port", String(port)], { timeout: 15_000 }); }
  catch { throw new LinkError("Could not read the Mac connection key. Build the Mac service first."); }
  if (!configuration.startsWith("vrcode:")) throw new LinkError("Could not read the Mac connection key. Build the Mac service first.");
  await adb.run(["shell", "run-as", "dev.improve.vrcode", "mkdir", "-p", "files"], { serial });
  try { await run([adb.executable, "-s", serial, "shell", "run-as", "dev.improve.vrcode", "tee", "files/vrcode-pairing"], { input: Buffer.from(configuration) }); }
  catch { throw new LinkError("Could not provision the debug app. Install it first, or paste the Mac connection key in Settings."); }
  console.log("Mac connection key copied to the app's private storage. In Connection settings, choose Import from connected Mac.");
}

export function portNumber(value: string) {
  if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535) throw new LinkError("expected a TCP port from 1 to 65535");
  return Number(value);
}

export const help = `Usage: bun scripts/quest-link.ts <wifi|usb|status|lan|probe|pair|off> [options]
  --adb PATH           Path to adb (auto-detected)
  --serial SERIAL      Explicit USB serial or Wi-Fi IP:port
  --host IP            wifi: reconnect to this Quest IP without USB
  --direct             probe: test Quest -> Mac LAN reachability
  --adb-port PORT       Wireless ADB port (5555)
  --bridge-port PORT    Legacy bridge port (7272)
  --service-port PORT   Speech port (7273)
  --relay-port PORT     Secure relay port (7274)
  --mac-binary PATH     pair: built Mac service
  --help               Show this help`;

export async function main(argv = Bun.argv.slice(2)) {
  const { values, positionals } = parseArgs({ args: argv, allowPositionals: true, options: {
    adb: { type: "string" }, serial: { type: "string" }, host: { type: "string" }, direct: { type: "boolean" },
    "adb-port": { type: "string", default: "5555" }, "bridge-port": { type: "string", default: "7272" },
    "service-port": { type: "string", default: "7273" }, "relay-port": { type: "string", default: "7274" },
    "mac-binary": { type: "string", default: join(repoRoot, "mac/.build/out/Products/Debug/vrcode-mac") }, help: { type: "boolean" },
  } });
  if (values.help) { console.log(help); return; }
  const command = positionals[0];
  if (positionals.length !== 1 || !["wifi", "usb", "status", "lan", "probe", "pair", "off"].includes(command)) throw new LinkError(help);
  if (values.host && command !== "wifi") throw new LinkError("--host is only used with wifi");
  if (values.host && values.serial) throw new LinkError("use either --host or --serial");
  if (values.direct && command !== "probe") throw new LinkError("--direct is only used with probe");
  const ports = [values["bridge-port"], values["service-port"], values["relay-port"]].map(portNumber);
  const adbPort = portNumber(values["adb-port"]);
  if (new Set(ports).size !== 3) throw new LinkError("bridge, speech and relay services need different ports");
  const adb = new Adb(findAdb(values.adb));
  let serial: string;
  if (command === "wifi" && values.host) serial = await connectWifi(adb, ipv4(values.host), adbPort);
  else {
    const device = selectDevice(await adb.devices(!values.serial && command !== "usb"), values.serial, command === "usb");
    serial = device.serial;
    if (command === "wifi" && device.usb) {
      const host = wifiAddress(await adb.run(["shell", "ip", "-o", "-4", "addr", "show", "wlan0"], { serial }));
      const identity = await adb.run(["shell", "getprop", "ro.serialno"], { serial });
      if (!identity) throw new LinkError("Could not read the USB Quest's identity; leaving ADB unchanged.");
      console.log(`Enabling Wi-Fi ADB at ${host}:${adbPort}…`);
      await adb.run(["tcpip", String(adbPort)], { serial });
      serial = await connectWifi(adb, host, adbPort, identity);
    }
  }
  if (command === "wifi" || command === "usb") {
    const remote = command === "wifi" ? 27274 : 17274;
    await installMappings(adb, serial, { [`tcp:${ports[0]}`]: `tcp:${ports[0]}`, [`tcp:${ports[1]}`]: `tcp:${ports[1]}`, [`tcp:${remote}`]: `tcp:${ports[2]}` });
    await printLink(serial, ports, remote);
    if (command === "wifi") {
      console.log("You can unplug USB now. Choose Wi-Fi debugging or Automatic in Connection settings.");
      console.log("The app relay encrypts terminals and speech. Legacy raw ADB traffic still requires a trusted LAN.");
      console.log(`To disable wireless debugging: bun scripts/quest-link.ts off --serial ${serial}`);
    }
  } else if (command === "status") {
    await printLink(serial, ports);
    console.log("Reverse mappings:\n" + (await adb.run(["reverse", "--list"], { serial }) || "  none"));
  } else if (command === "lan") {
    const host = wifiAddress(await adb.run(["shell", "ip", "-o", "-4", "addr", "show", "wlan0"], { serial }));
    const address = await macAddressFor(host);
    console.log(`Quest Wi-Fi address: ${host}\nSecure app relay address: ${address}:${ports[2]}`);
    console.log("Import the Mac connection key in Settings, then choose Automatic or Local Wi-Fi.");
    console.log(`Legacy standalone xmux address: ${address}:${ports[0]}`);
    console.log("The speech server stays on loopback behind the secure relay. A firewall or network isolation can still block LAN access.");
  } else if (command === "pair") await provisionMac(adb, serial, values["mac-binary"], ports[2]);
  else if (command === "probe") {
    const host = values.direct ? wifiAddress(await adb.run(["shell", "ip", "-o", "-4", "addr", "show", "wlan0"], { serial })) : undefined;
    await probe(adb, serial, host ? await macAddressFor(host) : undefined);
  } else {
    await adb.run(["usb"], { serial });
    console.log(`Disabled Wi-Fi ADB on ${serial}. Reconnect USB to use debugging again.`);
  }
}

if (import.meta.main) main().catch((error) => { console.error(`quest-link: ${error.message}`); process.exitCode = 1; });
