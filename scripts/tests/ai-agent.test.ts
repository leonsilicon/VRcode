/**
 * The AI mic's thinking mode end to end through the real Mac service, with a fake `claude` CLI in
 * place of Claude Code (VRCODE_CLAUDE_BIN), so no model is called. Opt in with
 * VRCODE_MAC_BINARY=/path/to/vrcode-mac (or VRCODE_CMUX_BINARY, which the cmux suite uses).
 */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { chmod, mkdtemp, rm } from "node:fs/promises";
import { createConnection, createServer, type Socket } from "node:net";
import { join } from "node:path";
import { once } from "node:events";

const binary = process.env.VRCODE_MAC_BINARY ?? process.env.VRCODE_CMUX_BINARY;
const suite = binary ? describe : describe.skip;

const AI_REQUEST = 11, AI_RESULT = 12, ERROR = 8, AI_PROGRESS = 16, AI_CANCEL = 17;

async function freePort() {
  const server = createServer(); server.listen(0, "127.0.0.1"); await once(server, "listening");
  const port = (server.address() as { port: number }).port;
  await new Promise<void>((resolve) => server.close(() => resolve())); return port;
}

/** Stream-json lines shaped like Claude Code 2.1's `-p --output-format stream-json --include-partial-messages`. */
const CANNED = [
  { type: "system", subtype: "init", tools: ["Bash", "Glob", "Grep", "Read"] },
  { type: "stream_event", parent_tool_use_id: null, event: { type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "They want the tests. " } } },
  { type: "stream_event", parent_tool_use_id: null, event: { type: "content_block_delta", index: 0, delta: { type: "thinking_delta", thinking: "Checking package.json." } } },
  { type: "assistant", message: { content: [{ type: "tool_use", id: "toolu_1", name: "Read", input: { file_path: "/repo/project/package.json" } }] } },
  { type: "assistant", message: { content: [{ type: "tool_use", id: "toolu_2", name: "Bash", input: { command: "git status --short" } }] } },
  { type: "assistant", message: { content: [{ type: "tool_use", id: "toolu_3", name: "StructuredOutput", input: {} }] } },
  {
    type: "result", subtype: "success", is_error: false,
    structured_output: { summary: "Runs the Bun tests, as package.json says", keys: [{ text: "bun test", key: null }, { text: null, key: "Return" }] },
  },
];

const FAKE_CLAUDE = `#!/usr/bin/env bun
if (process.env.FAKE_EARLY_EXIT && require("node:fs").existsSync(process.env.FAKE_EARLY_EXIT)) { console.error("claude: unsupported option"); process.exit(2); }
const input = await Bun.stdin.text();
await Bun.write(process.env.FAKE_RECORD, JSON.stringify({ argv: process.argv.slice(2), input, cwd: process.cwd() }));
if (input.includes("HANG")) { await Bun.write(process.env.FAKE_RECORD + ".hang", "started"); await new Promise(() => {}); }
if (input.includes("FAIL")) { console.error("Not logged in. Run claude login."); process.exit(1); }
for (const line of ${JSON.stringify(CANNED)}) { console.log(JSON.stringify(line)); await Bun.sleep(5); }
`;

class Link {
  private buffer = Buffer.alloc(0);
  frames: { kind: number; id: number; body: any }[] = [];
  constructor(readonly socket: Socket) {
    socket.on("data", (data) => {
      this.buffer = Buffer.concat([this.buffer, data]);
      while (this.buffer.length >= 16 && this.buffer.length >= 16 + this.buffer.readUInt32LE(0)) {
        const length = this.buffer.readUInt32LE(0), kind = this.buffer[4], id = this.buffer.readUInt32LE(8);
        const text = this.buffer.subarray(16, 16 + length).toString();
        this.buffer = this.buffer.subarray(16 + length);
        this.frames.push({ kind, id, body: text ? JSON.parse(text) : {} });
      }
    });
  }
  send(kind: number, id: number, value?: unknown) {
    const data = Buffer.from(value === undefined ? "" : JSON.stringify(value)), header = Buffer.alloc(16);
    header.writeUInt32LE(data.length); header[4] = kind; header.writeUInt16LE(1, 6); header.writeUInt32LE(id, 8);
    this.socket.write(Buffer.concat([header, data]));
  }
  async until(test: () => boolean, ms = 10_000) {
    for (let waited = 0; !test(); waited += 20) {
      if (waited > ms) throw new Error("timed out waiting for the Mac service");
      await Bun.sleep(20);
    }
  }
  of(id: number) { return this.frames.filter((f) => f.id === id); }
}

suite("AI mic thinking mode", () => {
  let directory: string, record: string, service: ReturnType<typeof Bun.spawn>, link: Link;

  beforeAll(async () => {
    directory = await mkdtemp("/tmp/vrcode-ai-");
    record = join(directory, "record.json");
    const fake = join(directory, "claude");
    await Bun.write(fake, FAKE_CLAUDE);
    await chmod(fake, 0o755);
    const port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--port", String(port), "--link-port", String(await freePort()),
      "--link-directory", join(directory, "link")], {
      env: { ...process.env, VRCODE_CLAUDE_BIN: fake, FAKE_RECORD: record, FAKE_EARLY_EXIT: record + ".early" }, stdout: "ignore", stderr: "ignore",
    });
    for (let i = 0; i < 80 && !link; i++) {
      try {
        const client = createConnection({ host: "127.0.0.1", port });
        await once(client, "connect"); link = new Link(client);
      } catch { await Bun.sleep(100); }
    }
    if (!link) throw new Error("VRcode Mac service did not start");
    link.send(1, 0, { client: "vrcode-ai-test", protocol: 1 });
    await link.until(() => link.frames.some((f) => f.kind === 2));
  }, 20_000);

  afterAll(async () => {
    link?.socket.destroy(); service?.kill();
    if (directory) await rm(directory, { recursive: true, force: true });
  });

  const request = (transcript: string) => ({
    transcript, agent: true, provider: "claude", model: "claude-sonnet-5-5",
    screen: "user@mac project % ", title: "zsh", cursor: { row: 0, col: 19, visible: true },
  });

  test("streams status, thinking and tool steps, then answers with keystrokes and a summary", async () => {
    link.send(AI_REQUEST, 41, request("run the tests"));
    await link.until(() => link.of(41).some((f) => f.kind === AI_RESULT || f.kind === ERROR));
    const frames = link.of(41);
    expect(frames.find((f) => f.kind === ERROR)).toBeUndefined();
    const progress = frames.filter((f) => f.kind === AI_PROGRESS).map((f) => f.body);
    expect(progress[0]).toEqual({ kind: "status", text: "Claude Code in ~" });
    expect(progress.filter((p) => p.kind === "thinking").map((p) => p.text).join("")).toBe("They want the tests. Checking package.json.");
    // Paths shorten to their last two parts; the answer's own tool call is not a step.
    expect(progress.filter((p) => p.kind === "step").map((p) => p.text)).toEqual(["Read project/package.json", "$ git status --short"]);
    const result = frames.find((f) => f.kind === AI_RESULT)!.body;
    expect(result.keys).toEqual([{ text: "bun test" }, { key: "enter" }]);
    expect(result.summary).toBe("Runs the Bun tests, as package.json says");
    expect(result.text).toBe("bun test");

    const { argv, input, cwd } = JSON.parse(await Bun.file(record).text());
    expect(cwd).toBe(process.env.HOME); // no cmux terminal named: the home folder
    expect(input).toContain("<transcript>run the tests</transcript>");
    expect(input).toContain("<cursor_line>user@mac project % ▮</cursor_line>");
    // Read-only: user settings ignored, only read tools plus Bash, prompts denied.
    for (const flag of ["--restricted", "--safe-mode", "--strict-mcp-config", "--no-session-persistence"]) expect(argv).toContain(flag);
    expect(argv[argv.indexOf("--tools") + 1]).toBe("Read,Grep,Glob,Bash");
    expect(argv[argv.indexOf("--permission-prompts") + 1]).toBe("none");
    expect(argv[argv.indexOf("--model") + 1]).toBe("claude-sonnet-5-5");
    expect(JSON.parse(argv[argv.indexOf("--settings") + 1])).toEqual({ showThinkingSummaries: true });
  });

  test("a CLI failure comes back as an ERROR with its message", async () => {
    link.send(AI_REQUEST, 42, request("FAIL please"));
    await link.until(() => link.of(42).some((f) => f.kind === AI_RESULT || f.kind === ERROR));
    const error = link.of(42).find((f) => f.kind === ERROR);
    expect(error?.body.message).toBe("Claude Code failed: Not logged in. Run claude login.");
  });

  test("a CLI that exits without reading its prompt fails the request, not the service", async () => {
    await Bun.write(record + ".early", "1");
    // Far more than a pipe buffer, so writing it to an exited CLI would SIGPIPE an unguarded service.
    link.send(AI_REQUEST, 45, { ...request("run the tests"), screen: "x".repeat(512 * 1024) });
    await link.until(() => link.of(45).some((f) => f.kind === AI_RESULT || f.kind === ERROR));
    expect(link.of(45).find((f) => f.kind === ERROR)?.body.message).toBe("Claude Code failed: claude: unsupported option");
    await rm(record + ".early");
    link.send(AI_REQUEST, 46, request("run the tests"));
    await link.until(() => link.of(46).some((f) => f.kind === AI_RESULT));
  });

  test("AI_CANCEL stops a running agent and nothing more is sent for it", async () => {
    await rm(record + ".hang", { force: true });
    link.send(AI_REQUEST, 43, request("HANG forever"));
    await link.until(() => Bun.file(record + ".hang").size > 0);
    link.send(AI_CANCEL, 43);
    await Bun.sleep(500);
    expect(link.of(43).filter((f) => f.kind !== AI_PROGRESS)).toEqual([]);
    // The fake CLI was terminated, so the service still answers the next request.
    link.send(AI_REQUEST, 44, request("run the tests"));
    await link.until(() => link.of(44).some((f) => f.kind === AI_RESULT));
    expect(link.of(43).filter((f) => f.kind !== AI_PROGRESS)).toEqual([]);
  });
});
