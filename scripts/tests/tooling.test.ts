import { expect, test } from "bun:test";
import { chmod, mkdir, mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Adb, probe } from "../quest-link";
import { checkScripts } from "../check-scripts";
import { run } from "../lib/process";
import { outputs } from "../sync-cmux-catalog";

test("repository scripting policy holds", async () => {
  expect(await checkScripts()).toContain("All repository-owned scripts use Bun");
});

test("subprocess helper sends binary input and reports failures and deadlines", async () => {
  const payload = Buffer.from([0, 255, 1, 128]);
  expect(await run([process.execPath, "-e", "process.stdout.write(Buffer.from(await Bun.stdin.arrayBuffer()).toString('hex'))"], { input: payload })).toBe("00ff0180");
  await expect(run([process.execPath, "-e", "console.error('expected failure'); process.exit(3)"])).rejects.toThrow("expected failure");
  await expect(run([process.execPath, "-e", "await Bun.sleep(10000)"], { timeout: 30 })).rejects.toThrow("timed out");
});

test("binary probe drains the full reply and removes its temporary mapping", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vrcode-probe-test-"));
  try {
    const executable = join(directory, "adb.ts"), log = join(directory, "calls.jsonl");
    await Bun.write(executable, `#!/usr/bin/env bun
import { appendFileSync } from "node:fs";
import { createConnection } from "node:net";
let args = Bun.argv.slice(2);
appendFileSync(${JSON.stringify(log)}, JSON.stringify(args) + "\\n");
if (args[0] === "-s") args = args.slice(2);
if (args[0] === "reverse" && args[1] === "tcp:0") console.log(args[2].slice(4));
else if (args[0] === "shell") {
  const socket = createConnection({host: args.at(-2), port: Number(args.at(-1))});
  socket.on("error", () => process.exit(1));
  process.stdin.pipe(socket);
  socket.pipe(process.stdout);
  socket.on("end", () => { process.stdin.unpipe(socket); process.stdin.pause(); });
} else if (args[0] !== "reverse" || args[1] !== "--remove") process.exit(1);
`);
    await chmod(executable, 0o755);
    await probe(new Adb(executable), "quest");
    const calls = (await Bun.file(log).text()).trim().split("\n").map((line) => JSON.parse(line));
    expect(calls).toHaveLength(3);
    expect(calls[0].slice(0, 4)).toEqual(["-s", "quest", "reverse", "tcp:0"]);
    expect(calls[2]).toEqual(["-s", "quest", "reverse", "--remove", calls[0][4]]);
  } finally { await rm(directory, { recursive: true, force: true }); }
}, 15_000);

test("cmux generator preserves catalog metadata, operation classes and external routes", async () => {
  const directory = await mkdtemp(join(tmpdir(), "vrcode-catalog-test-"));
  try {
    await mkdir(join(directory, "cmux-tui/spec"), { recursive: true });
    const spec = { protocol: "cmux.protocol/2", operations: { "terminal.get": { class: "read", label: "café" }, "terminal.write": { class: "mutation" } }, types: { resource: "terminal" } };
    await Bun.write(join(directory, "cmux-tui/spec/resource-operations-v2.json"), JSON.stringify(spec));
    await Bun.write(join(directory, "cmux-tui/spec/inventory.json"), JSON.stringify({ tui_actions: [{ variant: "Focus", classification: "direct", route: "one|two" }], menu_actions: [{ variant: "Provider", classification: "external-protocol", route: { operation: "é" } }] }));
    await Bun.write(join(directory, "LICENSE"), "fixture license\n");
    const files = await outputs(directory);
    expect(JSON.parse(files["settings-ui/src/main/assets/cmux-catalog.json"])).toEqual({ ...spec, revision: "adc65e8aa59f2347b68cb8014c0980d634f19bc7" });
    expect(files["settings-ui/src/main/assets/cmux-catalog.json"]).toContain("caf\\u00e9");
    expect(files["mac/Sources/vrcode-mac/CmuxCatalog.swift"]).toContain('"terminal.write": "mutation"');
    expect(files["docs/cmux-interactions.md"]).toContain("All 2 public transported operations");
    expect(files["docs/cmux-interactions.md"]).toContain("direct: one/two");
    expect(files["docs/cmux-interactions.md"]).toContain('external-protocol: {"operation":"\\u00e9"}');
    expect(files["settings-ui/src/main/assets/cmux-catalog.LICENSE"]).toBe("fixture license\n");
  } finally { await rm(directory, { recursive: true, force: true }); }
});
