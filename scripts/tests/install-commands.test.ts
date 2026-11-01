/** The checks `vpx _quest` and `vpx _tauri` make after installing (scripts/_quest.ts, scripts/_tauri.ts). */
import { describe, expect, test } from "bun:test";
import { crashLines, lastUpdateTime } from "../_quest";
import { runningPid, startupLines } from "../_tauri";

describe("vpx _quest", () => {
  test("reads the package's lastUpdateTime from dumpsys", () => {
    const dumpsys = "Packages:\n  Package [dev.improve.vrcode] (1a2b):\n    firstInstallTime=2026-10-30 18:02:11\n    lastUpdateTime=2026-11-01 16:40:02\n";
    expect(lastUpdateTime(dumpsys)).toBe("2026-11-01 16:40:02");
    expect(lastUpdateTime("Unable to find package: dev.improve.vrcode")).toBeUndefined();
  });

  test("finds only the app's own crash lines", () => {
    const log = [
      "11-01 16:41:02.100  9001  9001 E AndroidRuntime: FATAL EXCEPTION: main",
      "11-01 16:41:02.100  9001  9001 E AndroidRuntime: Process: dev.improve.vrcode, PID: 9001",
      "11-01 16:41:03.000  7000  7000 E AndroidRuntime: Process: com.oculus.vrshell, PID: 7000",
    ].join("\n");
    expect(crashLines(log)).toEqual(["11-01 16:41:02.100  9001  9001 E AndroidRuntime: Process: dev.improve.vrcode, PID: 9001"]);
    expect(crashLines("")).toEqual([]);
  });
});

describe("vpx _tauri", () => {
  test("reads the running pid from launchctl print", () => {
    expect(runningPid("gui/501/dev.improve.vrcode.mac = {\n\tactive count = 1\n\tstate = running\n\tpid = 22508\n}")).toBe("22508");
    expect(runningPid("gui/501/dev.improve.vrcode.mac = {\n\tstate = not running\n}")).toBeUndefined();
  });

  test("keeps the service's startup lines from the log", () => {
    const log = "[16:42:21] listening on port 7273\n[16:42:21] Bluetooth LE relay advertising\n[16:42:21] speech: ElevenLabs scribe_v2_realtime, the on-device recognizer as fallback\n[16:42:21] speech ready (en_US)\n";
    expect(startupLines(log)).toEqual([
      "[16:42:21] listening on port 7273",
      "[16:42:21] speech: ElevenLabs scribe_v2_realtime, the on-device recognizer as fallback",
      "[16:42:21] speech ready (en_US)",
    ]);
  });
});
