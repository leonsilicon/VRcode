/**
 * The AI mic's agent mode end to end through the real Mac service and its agent runner
 * (mac/agent/vrcode-agent.ts, run from the checkout in a development build), with a fake ACP agent
 * (scripts/lib/fake-acp-agent.ts) in place of Claude Code (VRCODE_ACP_CLAUDE), so no model is
 * called. Opt in with VRCODE_MAC_BINARY=/path/to/vrcode-mac (or VRCODE_CMUX_BINARY, which the
 * cmux suite uses).
 */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
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

suite("AI mic agent mode", () => {
  let directory: string, record: string, service: ReturnType<typeof Bun.spawn>, link: Link;

  beforeAll(async () => {
    directory = await mkdtemp("/tmp/vrcode-ai-");
    record = join(directory, "record.jsonl");
    const fake = join(import.meta.dir, "../lib/fake-acp-agent.ts");
    const port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--port", String(port), "--link-port", String(await freePort()),
      "--link-directory", join(directory, "link")], {
      env: { ...process.env, VRCODE_ACP_CLAUDE: JSON.stringify([process.execPath, fake]), FAKE_ACP_RECORD: record }, stdout: "ignore", stderr: "ignore",
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

  const records = async () => (await Bun.file(record).text()).trim().split("\n").map((line) => JSON.parse(line));
  const request = (transcript: string) => ({
    transcript, agent: true, provider: "claude", model: "claude-sonnet-5-5",
    screen: "user@mac project % ", title: "zsh", cursor: { row: 0, col: 19, visible: true },
  });
  const settled = (id: number) => link.until(() => link.of(id).some((f) => f.kind === AI_RESULT || f.kind === ERROR), 20_000);

  test("streams status, thinking and steps, then ends with the agent's summary and nothing to type", async () => {
    link.send(AI_REQUEST, 41, request("go to the vrcode repo"));
    await settled(41);
    const frames = link.of(41);
    expect(frames.find((f) => f.kind === ERROR)).toBeUndefined();
    const progress = frames.filter((f) => f.kind === AI_PROGRESS).map((f) => f.body);
    expect(progress.filter((p) => p.kind === "status").map((p) => p.text)).toEqual(["Starting Claude Code…", "Claude Code in ~"]);
    expect(progress.filter((p) => p.kind === "thinking").map((p) => p.text).join("")).toStartWith("Finding the repo. Then cd there.");
    expect(progress.filter((p) => p.kind === "step").map((p) => p.text))
      .toEqual(["$ ls ~/Developer", "Presses: ctrl+u · Types: cd ~/Developer/vrcode · Presses: enter · Reads your terminal"]);
    const result = frames.find((f) => f.kind === AI_RESULT)!.body;
    expect(result).toMatchObject({ keys: [], text: "", acted: true, summary: "Moved your terminal to ~/Developer/vrcode.", model: "claude-sonnet-5-5" });

    const log = await records();
    const created = log.find((entry) => entry.method === "session/new");
    expect(created.cwd).toBe(process.env.HOME); // no cmux terminal named: the home folder
    expect(created.env).toEqual({ CMUX_TUI_SOCKET: null, CMUX_TUI_TERMINAL_ID: null });
    const prompt = log.find((entry) => entry.method === "session/prompt").text;
    expect(prompt).toContain("<transcript>go to the vrcode repo</transcript>");
    expect(prompt).toContain("<cursor_line>user@mac project % ▮</cursor_line>");
    expect(log.find((entry) => entry.method === "permission").outcome).toEqual({ outcome: "selected", optionId: "always" });
  });

  test("an agent error comes back as an ERROR with its message", async () => {
    link.send(AI_REQUEST, 42, request("FAIL please"));
    await settled(42);
    expect(link.of(42).find((f) => f.kind === ERROR)?.body.message)
      .toBe("Claude Code needs signing in on the Mac: Authentication required (run `claude` in a terminal on the Mac and sign in)");
  });

  test("AI_CANCEL stops a running agent and nothing more is sent for it", async () => {
    link.send(AI_REQUEST, 43, request("HANG forever"));
    for (let i = 0; i < 200 && !(await Bun.file(record).text()).includes("HANG"); i++) await Bun.sleep(20);
    link.send(AI_CANCEL, 43);
    for (let i = 0; i < 100 && !(await Bun.file(record).text()).includes("session/cancel"); i++) await Bun.sleep(20);
    expect((await records()).some((entry) => entry.method === "session/cancel")).toBe(true);
    await Bun.sleep(300);
    expect(link.of(43).filter((f) => f.kind !== AI_PROGRESS)).toEqual([]);
    link.send(AI_REQUEST, 44, request("go to the vrcode repo"));
    await settled(44);
    expect(link.of(44).find((f) => f.kind === AI_RESULT)?.body.summary).toBe("Moved your terminal to ~/Developer/vrcode.");
    expect(link.of(43).filter((f) => f.kind !== AI_PROGRESS)).toEqual([]);
  });

  test("an agent that exits fails its request, not the service", async () => {
    link.send(AI_REQUEST, 45, request("DIE now"));
    await settled(45);
    expect(link.of(45).find((f) => f.kind === ERROR)?.body.message).toStartWith("Claude Code: exited");
    link.send(AI_REQUEST, 46, request("go to the vrcode repo"));
    await settled(46);
    expect(link.of(46).some((f) => f.kind === AI_RESULT)).toBe(true);
  });
});
