/**
 * The AI mic's hearing end to end through the real Mac service: an AI-mic dictation's audio
 * (AUDIO_START `ai`) is kept, and the AI_REQUEST naming it (`utterances`) is answered from what a
 * model heard in the recording instead of the on-device transcript (mac/Sources/vrcode-mac/AiHearing.swift).
 * A fake Gemini API (VRCODE_GEMINI_API_BASE) does the hearing and the fake ACP agent
 * (scripts/lib/fake-acp-agent.ts) stands in for Claude Code, so no model is called. Opt in with
 * VRCODE_MAC_BINARY=/path/to/vrcode-mac (or VRCODE_CMUX_BINARY, which the cmux suite uses).
 */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { createConnection, createServer, type Socket } from "node:net";
import { join } from "node:path";
import { once } from "node:events";

const binary = process.env.VRCODE_MAC_BINARY ?? process.env.VRCODE_CMUX_BINARY;
const suite = binary ? describe : describe.skip;

const AUDIO_START = 3, AUDIO = 4, AUDIO_END = 5, AUDIO_CANCEL = 6, AI_REQUEST = 11, AI_RESULT = 12, ERROR = 8, AI_PROGRESS = 16;

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
    const data = Buffer.isBuffer(value) ? value : Buffer.from(value === undefined ? "" : JSON.stringify(value));
    const header = Buffer.alloc(16);
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

/** [count] 50 ms chunks of a 440 Hz tone, 16 kHz mono s16le, as MicCapture sends them. */
function tone(count: number): Buffer[] {
  return Array.from({ length: count }, (_, chunk) => {
    const samples = new Int16Array(800);
    for (let i = 0; i < samples.length; i++) samples[i] = Math.round(8000 * Math.sin((2 * Math.PI * 440 * (chunk * 800 + i)) / 16000));
    return Buffer.from(samples.buffer);
  });
}

suite("AI mic hearing", () => {
  let directory: string, record: string, service: ReturnType<typeof Bun.spawn>, link: Link;
  let gemini: ReturnType<typeof Bun.serve>;
  const heardRequests: { path: string; key: string | null; body: any }[] = [];
  const transcriptions: { authorization: string | null; raw: Buffer; model: unknown; prompt: unknown; keywords: unknown[]; file: File }[] = [];

  beforeAll(async () => {
    directory = await mkdtemp("/tmp/vrcode-hearing-");
    record = join(directory, "record.jsonl");
    gemini = Bun.serve({
      port: 0, hostname: "127.0.0.1",
      async fetch(request) {
        if (new URL(request.url).pathname === "/v1/audio/transcriptions") {
          const raw = Buffer.from(await request.clone().arrayBuffer()), form = await request.formData();
          transcriptions.push({
            authorization: request.headers.get("authorization"), raw, model: form.get("model"), prompt: form.get("prompt"),
            keywords: form.getAll("keywords[]"), file: form.get("file") as File,
          });
          return Response.json({ text: " run the Codex tests " });
        }
        const body = await request.json();
        heardRequests.push({ path: new URL(request.url).pathname, key: request.headers.get("x-goog-api-key"), body });
        const screen = body.contents?.[0]?.parts?.[0]?.text ?? "";
        if (screen.includes("BROKEN")) return Response.json({ error: { message: "Internal error" } }, { status: 500 });
        // A thought summary first: only the answer is what was said.
        return Response.json({ candidates: [{ content: { parts: [{ text: "listening…", thought: true }, { text: "open Claude Code in vrcode\n" }] } }] });
      },
    });
    const fake = join(import.meta.dir, "../lib/fake-acp-agent.ts");
    const port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--port", String(port), "--link-port", String(await freePort()),
      "--link-directory", join(directory, "link")], {
      env: {
        ...process.env, FAKE_ACP_RECORD: record,
        VRCODE_ACP_CLAUDE: JSON.stringify([process.execPath, fake]), VRCODE_ACP_OPENAI: JSON.stringify([process.execPath, fake]),
        GEMINI_API_KEY: "test-gemini-key", VRCODE_GEMINI_API_BASE: `http://127.0.0.1:${gemini.port}`,
        OPENAI_API_KEY: "test-openai-key", VRCODE_OPENAI_API_BASE: `http://127.0.0.1:${gemini.port}`,
        // With an ElevenLabs key on this Mac, live transcription would stream the tones to ElevenLabs.
        VRCODE_ELEVENLABS_API_BASE: "ws://127.0.0.1:9",
      },
      stdout: "ignore", stderr: "ignore",
    });
    for (let i = 0; i < 80 && !link; i++) {
      try {
        const client = createConnection({ host: "127.0.0.1", port });
        await once(client, "connect"); link = new Link(client);
      } catch { await Bun.sleep(100); }
    }
    if (!link) throw new Error("VRcode Mac service did not start");
    link.send(1, 0, { client: "vrcode-hearing-test", protocol: 1 });
    await link.until(() => link.frames.some((f) => f.kind === 2));
  }, 20_000);

  afterAll(async () => {
    link?.socket.destroy(); service?.kill(); gemini?.stop(true);
    if (directory) await rm(directory, { recursive: true, force: true });
  });

  const prompts = async () => (await Bun.file(record).text()).trim().split("\n").map((line) => JSON.parse(line))
    .filter((entry) => entry.method === "session/prompt").map((entry) => entry.text as string);
  const request = (transcript: string, utterances: number[], screen = "user@mac vrcode % ") => ({
    transcript, utterances, agent: true, provider: "claude", model: "claude-sonnet-5-5",
    screen, title: "zsh", cursor: { row: 0, col: screen.length, visible: true },
  });
  const settled = (id: number) => link.until(() => link.of(id).some((f) => f.kind === AI_RESULT || f.kind === ERROR), 20_000);
  const dictate = (id: number, chunks: Buffer[]) => {
    link.send(AUDIO_START, id, { sampleRate: 16000, channels: 1, encoding: "pcm_s16le", ai: true });
    for (const chunk of chunks) link.send(AUDIO, id, chunk);
  };

  test("the AI works from what the model heard in the recording, and the pane is told", async () => {
    const chunks = tone(10);
    dictate(51, chunks);
    link.send(AUDIO_END, 51);
    link.send(AI_REQUEST, 52, request("open claw code in vr code", [51]));
    await settled(52);

    expect(heardRequests).toHaveLength(1);
    const heard = heardRequests[0];
    expect(heard.path).toBe("/v1beta/models/gemini-3.8-flash:generateContent"); // Claude can't hear: Gemini's default model does
    expect(heard.key).toBeTruthy();
    expect(heard.body.system_instruction.parts[0].text).toContain('Claude Code, not "claw code"');
    const [context, audio] = heard.body.contents[0].parts;
    expect(context.text).toContain('<pane title="zsh">\nuser@mac vrcode % \n</pane>');
    expect(context.text).toContain("<cursor_line>user@mac vrcode % ▮</cursor_line>");
    expect(audio.inline_data.mime_type).toBe("audio/wav");
    const wav = Buffer.from(audio.inline_data.data, "base64");
    const pcm = Buffer.concat(chunks);
    expect(wav.subarray(0, 4).toString()).toBe("RIFF");
    expect(wav.subarray(8, 16).toString()).toBe("WAVEfmt ");
    expect([wav.readUInt16LE(22), wav.readUInt32LE(24), wav.readUInt16LE(34)]).toEqual([1, 16000, 16]);
    expect(wav.readUInt32LE(40)).toBe(pcm.length);
    expect(wav.subarray(44).equals(pcm)).toBe(true);

    const progress = link.of(52).filter((f) => f.kind === AI_PROGRESS).map((f) => f.body);
    expect(progress[0]).toEqual({ kind: "heard", text: "open Claude Code in vrcode" });
    expect((await prompts()).at(-1)).toContain("<transcript>open Claude Code in vrcode</transcript>");
    expect(link.of(52).some((f) => f.kind === AI_RESULT)).toBe(true);
  });

  test("a pane added to is heard again from all of its recordings, in order", async () => {
    const first = tone(4), second = tone(6);
    dictate(53, first);
    link.send(AUDIO_END, 53);
    dictate(54, second);
    link.send(AUDIO_END, 54);
    link.send(AI_REQUEST, 55, request("open claw code and run the tests", [53, 54]));
    await settled(55);
    const wav = Buffer.from(heardRequests.at(-1)!.body.contents[0].parts[1].inline_data.data, "base64");
    expect(wav.subarray(44).equals(Buffer.concat([...first, ...second]))).toBe(true);
  });

  test("with ChatGPT chosen, OpenAI's transcription model hears it, with the screen and names to expect", async () => {
    const chunks = tone(6);
    dictate(57, chunks);
    link.send(AUDIO_END, 57);
    link.send(AI_REQUEST, 58, { ...request("run the codecs tests", [57], "user@mac vrcode % bun test"), provider: "openai", model: "gpt-6.1-sol" });
    await settled(58);
    expect(transcriptions).toHaveLength(1);
    const heard = transcriptions[0];
    expect(heard.authorization).toStartWith("Bearer ");
    expect(heard.model).toBe("gpt-transcribe");
    expect(heard.prompt).toContain("speaking to an AI coding assistant about this terminal (zsh)");
    expect(heard.prompt).toContain("user@mac vrcode % bun test");
    expect(heard.keywords).toContain("Claude Code");
    expect(heard.keywords).toContain("Gemini CLI");
    expect(heard.keywords).toContain("package.json");
    // Bun names the part's type after its filename, so check the header that was sent.
    expect(heard.raw.includes('name="file"; filename="speech.wav"\r\nContent-Type: audio/wav\r\n')).toBe(true);
    const wav = Buffer.from(await heard.file.arrayBuffer());
    expect(wav.subarray(44).equals(Buffer.concat(chunks))).toBe(true);
    expect(link.of(58).find((f) => f.kind === AI_PROGRESS)?.body).toEqual({ kind: "heard", text: "run the Codex tests" });
    expect((await prompts()).at(-1)).toContain("<transcript>run the Codex tests</transcript>");
  });

  test("a cancelled recording isn't heard: the AI gets the transcript", async () => {
    const before = heardRequests.length;
    dictate(61, tone(4));
    link.send(AUDIO_CANCEL, 61);
    link.send(AI_REQUEST, 62, request("typed into the pane instead", [61]));
    await settled(62);
    expect(heardRequests).toHaveLength(before);
    expect(link.of(62).some((f) => f.kind === AI_PROGRESS && f.body.kind === "heard")).toBe(false);
    expect((await prompts()).at(-1)).toContain("<transcript>typed into the pane instead</transcript>");
  });

  test("when hearing fails the AI still answers, from the on-device transcript", async () => {
    dictate(71, tone(4));
    link.send(AUDIO_END, 71);
    link.send(AI_REQUEST, 72, request("open claw code", [71], "BROKEN % "));
    await settled(72);
    expect(heardRequests.at(-1)!.body.contents[0].parts[0].text).toContain("BROKEN");
    expect(link.of(72).some((f) => f.kind === AI_PROGRESS && f.body.kind === "heard")).toBe(false);
    expect((await prompts()).at(-1)).toContain("<transcript>open claw code</transcript>");
    expect(link.of(72).some((f) => f.kind === AI_RESULT)).toBe(true);
  });
});
