import { describe, expect, test } from "bun:test";
import { connectWifi, installMappings, installReverse, LinkError, main, parseDevices, portNumber, reverseRules, selectDevice, wifiAddress, type AdbRunner } from "../quest-link";

const devicesOutput = `List of devices attached
QUEST1 device usb:2-1 product:eureka model:Quest_3
100.67.153.133:5555 device product:eureka model:Quest_3
PHONE1 device usb:2-2 model:Pixel_9
QUEST2 unauthorized usb:2-3
`;

class FakeAdb implements AdbRunner {
  rules: Record<string, string>;
  calls: { args: string[]; serial?: string }[] = [];
  constructor(rules = "", private failPort?: string) { this.rules = reverseRules(rules); }
  async run(args: string[], options: { serial?: string } = {}) {
    this.calls.push({ args, serial: options.serial });
    if (args[1] === "--list") return Object.entries(this.rules).map(([a, b]) => `local ${a} ${b}`).join("\n");
    if (args[1] === "--no-rebind") {
      if (args[2] === this.failPort) throw new LinkError("cannot bind");
      this.rules[args[2]] = args[3];
    } else if (args[1] === "--remove") delete this.rules[args[2]];
    return "";
  }
}

describe("Quest link", () => {
  test("auto selection prefers wireless for the same headset and ignores phones", () => {
    const devices = parseDevices(devicesOutput);
    devices[0].physicalSerial = devices[1].physicalSerial = "QUEST1";
    expect(selectDevice(devices).serial).toBe("100.67.153.133:5555");
    expect(selectDevice(devices, undefined, true).serial).toBe("QUEST1");
  });
  test("wireless preference requires verified matching identities", () => {
    const devices = parseDevices(devicesOutput);
    expect(() => selectDevice(devices)).toThrow(LinkError);
    devices[0].physicalSerial = "QUEST1";
    devices[1].physicalSerial = "ANOTHER_QUEST";
    expect(() => selectDevice(devices)).toThrow(LinkError);
  });
  test("multiple USB headsets require explicit selection", () => {
    expect(() => selectDevice(parseDevices(devicesOutput + "QUEST3 device usb:2-4 model:Quest_3S\n"), undefined, true)).toThrow(LinkError);
  });
  test("unauthorized explicit device is rejected", () => {
    expect(() => selectDevice(parseDevices(devicesOutput), "QUEST2")).toThrow("unauthorized");
  });
  test("venue CGNAT addresses are supported", () => {
    expect(wifiAddress("15: wlan0 inet 100.67.153.133/19 brd 100.67.159.255")).toBe("100.67.153.133");
    expect(() => wifiAddress("15: wlan0 inet6 fe80::1234/64")).toThrow(LinkError);
    expect(() => wifiAddress("15: wlan0 inet 999.1.2.3/24")).toThrow(LinkError);
  });
  test("conflicting reverse rule is never overwritten", async () => {
    const adb = new FakeAdb("local tcp:7273 tcp:9000");
    await expect(installReverse(adb, "quest", [7272, 7273])).rejects.toThrow("already forwards");
    expect(adb.rules).toEqual({ "tcp:7273": "tcp:9000" });
    expect(adb.calls).toHaveLength(1);
  });
  test("failure rolls back only newly created reverse rules", async () => {
    for (const existing of ["local tcp:8081 tcp:8081", "local tcp:7272 tcp:7272"]) {
      const adb = new FakeAdb(existing, "tcp:7273");
      await expect(installReverse(adb, "quest", [7272, 7273])).rejects.toThrow();
      expect(adb.rules).toEqual(reverseRules(existing));
    }
  });
  test("setup is idempotent and every ADB command is scoped", async () => {
    const adb = new FakeAdb();
    await installReverse(adb, "quest", [7272, 7273]);
    await installReverse(adb, "quest", [7272, 7273]);
    expect(adb.rules).toEqual({ "tcp:7272": "tcp:7272", "tcp:7273": "tcp:7273" });
    expect(adb.calls.filter((c) => c.args[1] === "--no-rebind")).toHaveLength(2);
    expect(adb.calls.every((c) => c.serial === "quest")).toBe(true);
  });
  test("connection checks actual transport readiness", async () => {
    const adb = { async run(args: string[]) {
      if (args[0] === "connect") return "failed to connect: Connection refused";
      throw new LinkError("device not found");
    } };
    await expect(connectWifi(adb, "192.168.1.2", 5555, undefined, 1)).rejects.toThrow("Cannot reach");
  });
  test("wireless identity must match USB", async () => {
    const adb = { async run(args: string[]) { return ({ connect: "connected", "get-state": "device", shell: "OTHER_QUEST" } as Record<string, string>)[args[0]]; } };
    await expect(connectWifi(adb, "192.168.1.2", 5555, "QUEST1")).rejects.toThrow("identity differs");
  });
  test("USB and Wi-Fi have distinct relay endpoints", async () => {
    const adb = new FakeAdb();
    await installMappings(adb, "usb-quest", { "tcp:17274": "tcp:7274" });
    await installMappings(adb, "wifi-quest", { "tcp:27274": "tcp:7274" });
    expect(adb.rules).toEqual({ "tcp:17274": "tcp:7274", "tcp:27274": "tcp:7274" });
  });
  test("ports reject malformed and out-of-range values", () => {
    expect(portNumber("65535")).toBe(65535);
    for (const value of ["0", "65536", "2.5", "7abc", "-1", ""]) expect(() => portNumber(value)).toThrow(LinkError);
  });
  test("invalid CLI combinations fail before contacting a device", async () => {
    for (const args of [["usb", "--host", "192.168.1.2"], ["wifi", "--direct"], ["wifi", "--host", "192.168.1.2", "--serial", "quest"], ["status", "--relay-port", "7273"], ["status", "--adb-port", "0"], ["wifi", "unexpected"]]) {
      await expect(main(args)).rejects.toThrow();
    }
  });
});
