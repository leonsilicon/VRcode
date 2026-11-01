#!/usr/bin/env bun
/** Render saved asset projects through Blender's ordinary CLI, with script auto-execution disabled. */
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";
import { parseArgs } from "node:util";
import { repoRoot, run } from "./lib/process";

export async function main(argv = Bun.argv.slice(2)) {
  const { values, positionals } = parseArgs({ args: argv, allowPositionals: true, options: {
    blender: { type: "string" }, output: { type: "string", default: "artifacts/asset-previews" },
    asset: { type: "string", default: "all" }, help: { type: "boolean" },
  } });
  if (values.help) {
    console.log("Usage: bun scripts/render-assets.ts [--asset microphone|voice-props|all] [--blender PATH] [--output DIRECTORY]");
    return;
  }
  if (positionals.length || !["microphone", "voice-props", "all"].includes(values.asset)) throw new Error("--asset must be microphone, voice-props, or all");
  const executable = values.blender ?? Bun.which("blender") ?? "/Applications/Blender.app/Contents/MacOS/Blender";
  const output = resolve(repoRoot, values.output);
  await mkdir(output, { recursive: true });
  for (const asset of values.asset === "all" ? ["microphone", "voice-props"] : [values.asset]) {
    await run([executable, "--background", "--factory-startup", "--disable-autoexec", resolve(repoRoot, `assets/${asset}/${asset}.blend`),
      "--render-output", resolve(output, `${asset}-####`), "--render-format", "PNG", "--use-extension", "1", "--render-frame", "1"], { timeout: 300_000, inherit: true });
  }
  console.log(`Saved previews in ${output}`);
}

if (import.meta.main) main().catch((error) => { console.error(`render-assets: ${error.message}`); process.exitCode = 1; });
