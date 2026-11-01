#!/usr/bin/env bun
import { cp, mkdir } from "node:fs/promises";
import { join } from "node:path";
import { repoRoot, run } from "./lib/process";

/** A background app bundle gives macOS Bluetooth permission a stable application identity. */
export async function main(args = Bun.argv.slice(2)) {
  if (args.includes("--help")) {
    console.log("Usage: bun scripts/run-mac-service.ts [--package-only] [Mac service arguments]");
    return;
  }
  const source = join(repoRoot, "mac");
  await run(["swift", "build", "--package-path", source], { timeout: 300_000, inherit: true });
  const bin = await run(["swift", "build", "--package-path", source, "--show-bin-path"], { timeout: 60_000 });
  const app = join(source, ".build/VRcode Mac.app");
  await mkdir(join(app, "Contents/MacOS"), { recursive: true });
  await cp(join(bin, "vrcode-mac"), join(app, "Contents/MacOS/vrcode-mac"));
  await cp(join(source, "Info.plist"), join(app, "Contents/Info.plist"));
  await run(["codesign", "--force", "--sign", "-", "--identifier", "dev.improve.vrcode.mac", app]);
  if (!args.includes("--package-only")) await run(["open", "-g", "-n", app, "--args", ...args]);
}

if (import.meta.main) main().catch((error) => { console.error(`run-mac-service: ${error.message}`); process.exitCode = 1; });
