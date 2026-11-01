#!/usr/bin/env bun
/** Prevent reintroducing other scripting runtimes into repository-owned tooling. */
import { existsSync } from "node:fs";
import { resolve } from "node:path";
import { repoRoot } from "./lib/process";

export async function checkScripts() {
  const result = Bun.spawnSync(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], { cwd: repoRoot });
  if (result.exitCode !== 0) throw new Error(result.stderr.toString());
  const errors: string[] = [];
  for (const path of new Set(result.stdout.toString().split("\0").filter(Boolean))) {
    if (!existsSync(resolve(repoRoot, path))) continue;
    if (path !== "gradlew.bat" && /\.(?:py[co]?|pyw|sh|bash|zsh|mjs|cjs|js|rb|pl|ps1|bat)$/.test(path)) errors.push(`${path}: repository scripts must be Bun TypeScript`);
    if (!/\.(?:ts|json|md|ya?ml|toml|kts?|swift)$/.test(path)) continue;
    const text = await Bun.file(resolve(repoRoot, path)).text();
    if (/^#!.*\b(?:python[\d.]*|node|(?:ba|z)?sh)\b/m.test(text)) errors.push(`${path}: use #!/usr/bin/env bun`);
    if (/["'`](?:python[\d.]*|pip3?|pipenv|poetry|uvx?|node|npm|npx|pnpm|yarn|tsx|ts-node)["'`]/m.test(text)) errors.push(`${path}: subprocesses must not use another scripting runtime`);
    if (/(?:^|[\s"'`])(?:python(?:\d+(?:\.\d+)*)?|pip3?|pipenv|poetry|uvx?)\s+(?:-[\w-]+|[\w./-]+\.py\b)/m.test(text)) errors.push(`${path}: forbidden interpreter invocation`);
    if (/--python(?:-expr|-exit-code)?(?:[\s"'`]|$)/m.test(text)) errors.push(`${path}: embedded interpreter flags are forbidden`);
    if (path.startsWith("scripts/") && path.endsWith(".ts") && text.includes("import.meta.main") && !text.startsWith("#!/usr/bin/env bun\n")) errors.push(`${path}: CLI entry points need a Bun shebang`);
  }
  const pkg = await Bun.file(resolve(repoRoot, "package.json")).json();
  for (const [name, command] of Object.entries(pkg.scripts)) {
    if (typeof command !== "string" || !command.startsWith("bun ")) errors.push(`package.json: ${name} must run with Bun`);
  }
  if (errors.length) throw new Error(errors.join("\n"));
  return "All repository-owned scripts use Bun. Upstream Gradle wrappers invoke the JVM.";
}

if (import.meta.main) checkScripts().then(console.log).catch((error) => { console.error(error.message); process.exitCode = 1; });
