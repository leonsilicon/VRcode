/**
 * ElevenLabs speech-to-text end to end through the real Mac service: with an ElevenLabs key, an
 * utterance (AUDIO_START … AUDIO_END) streams to Scribe v2 Realtime and its partial and committed
 * transcripts come back as TRANSCRIPT frames (mac/Sources/vrcode-mac/ElevenLabsTranscription.swift).
 * A fake Realtime WebSocket (VRCODE_ELEVENLABS_API_BASE) stands in for ElevenLabs, so nothing is
 * billed. `--control-only` leaves the on-device recognizer out, so a failure is not taken over.
 * Opt in with VRCODE_MAC_BINARY=/path/to/vrcode-mac (or VRCODE_CMUX_BINARY, which the cmux suite uses).
 */
import { afterAll, beforeAll, beforeEach, describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { createConnection, createServer, type Socket } from "node:net";
import { join } from "node:path";
import { once } from "node:events";
import type { ServerWebSocket } from "bun";

const binary = process.env.VRCODE_MAC_BINARY ?? process.env.VRCODE_CMUX_BINARY;
const suite = binary ? describe : describe.skip;

const WELCOME = 2, AUDIO_START = 3, AUDIO = 4, AUDIO_END = 5, AUDIO_CANCEL = 6, TRANSCRIPT = 7, ERROR = 8;

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

type Chunk = { message_type: string; audio_base_64: string; commit: boolean; sample_rate: number; previous_text?: string };
type Session = { url: URL; key: string | null; chunks: Chunk[]; closed: boolean };

suite("ElevenLabs speech-to-text", () => {
  let directory: string, service: ReturnType<typeof Bun.spawn>, link: Link;
  let scribe: ReturnType<typeof Bun.serve<{ session: Session }>>;
  const sessions: Session[] = [];
  /** How the fake answers: transcribe, or fail the session with quota_exceeded. */
  let mode: "transcribe" | "quota" = "transcribe";
  const audioOf = (session: Session) => Buffer.concat(session.chunks.map((chunk) => Buffer.from(chunk.audio_base_64, "base64")));

  beforeAll(async () => {
    directory = await mkdtemp("/tmp/vrcode-elevenlabs-");
    scribe = Bun.serve<{ session: Session }>({
      port: 0, hostname: "127.0.0.1",
      fetch(request, server) {
        const url = new URL(request.url);
        if (url.pathname !== "/v1/speech-to-text/realtime") return new Response("not found", { status: 404 });
        const session: Session = { url, key: request.headers.get("xi-api-key"), chunks: [], closed: false };
        return server.upgrade(request, { data: { session } }) ? undefined : new Response("upgrade failed", { status: 400 });
      },
      websocket: {
        open(ws) {
          sessions.push(ws.data.session);
          ws.send(JSON.stringify({ message_type: "session_started", session_id: `fake-${sessions.length}`, config: {} }));
        },
        message(ws: ServerWebSocket<{ session: Session }>, message) {
          const session = ws.data.session, chunk = JSON.parse(String(message)) as Chunk;
          session.chunks.push(chunk);
          if (mode === "quota") {
            ws.send(JSON.stringify({ message_type: "quota_exceeded", error: "You have exceeded your quota" }));
            ws.close();
            return;
          }
          // A partial once half a second is in, then the committed text when the Mac commits.
          const seconds = audioOf(session).length / 32_000;
          if (!chunk.commit && seconds >= 0.5) ws.send(JSON.stringify({ message_type: "partial_transcript", text: "open cloud code" }));
          if (chunk.commit) ws.send(JSON.stringify({ message_type: "committed_transcript", text: "Open Claude Code in vrcode." }));
        },
        close(ws) { ws.data.session.closed = true; },
      },
    });
    const port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--port", String(port), "--link-port", String(await freePort()),
      "--link-directory", join(directory, "link")], {
      env: { ...process.env, ELEVENLABS_API_KEY: "test-elevenlabs-key", VRCODE_ELEVENLABS_API_BASE: `ws://127.0.0.1:${scribe.port}` },
      stdout: "ignore", stderr: "ignore",
    });
    for (let i = 0; i < 80 && !link; i++) {
      try {
        const client = createConnection({ host: "127.0.0.1", port });
        await once(client, "connect"); link = new Link(client);
      } catch { await Bun.sleep(100); }
    }
    if (!link) throw new Error("VRcode Mac service did not start");
    link.send(1, 0, { client: "vrcode-elevenlabs-test", protocol: 1 });
    await link.until(() => link.frames.some((f) => f.kind === WELCOME));
  }, 20_000);

  afterAll(async () => {
    link?.socket.destroy(); service?.kill(); scribe?.stop(true);
    if (directory) await rm(directory, { recursive: true, force: true });
  });

  beforeEach(() => { mode = "transcribe"; });

  /** Streams [chunks] for utterance [id] a little faster than real time, as the headset would. */
  const dictate = async (id: number, chunks: Buffer[]) => {
    link.send(AUDIO_START, id, { sampleRate: 16000, channels: 1, encoding: "pcm_s16le" });
    for (const chunk of chunks) { link.send(AUDIO, id, chunk); await Bun.sleep(10); }
  };
  const transcripts = (id: number) => link.of(id).filter((f) => f.kind === TRANSCRIPT).map((f) => f.body);

  test("speech is ready without the on-device model when there is a key", () => {
    expect(link.frames.find((f) => f.kind === WELCOME)?.body.ready).toBe(true);
  });

  test("an utterance streams to Scribe and its partial and committed text come back as TRANSCRIPT", async () => {
    const chunks = tone(30); // 1.5 s
    await dictate(81, chunks);
    link.send(AUDIO_END, 81);
    await link.until(() => transcripts(81).some((t) => t.done));

    const updates = transcripts(81);
    expect(updates.some((t) => !t.done && t.final === "" && t.volatile === "open cloud code")).toBe(true);
    expect(updates.at(-1)).toEqual({ final: "Open Claude Code in vrcode.", volatile: "", done: true });
    expect(updates.filter((t) => t.done)).toHaveLength(1);

    const session = sessions.at(-1)!;
    expect(session.key).toBe("test-elevenlabs-key");
    const query = session.url.searchParams;
    expect(query.get("model_id")).toBe("scribe_v2_realtime");
    expect(query.get("audio_format")).toBe("pcm_16000");
    expect(query.get("commit_strategy")).toBe("manual");
    expect(query.get("language_code")).toBe("en");
    expect(query.get("no_verbatim")).toBe("true");
    const keyterms = query.getAll("keyterms");
    expect(keyterms).toContain("Claude Code");
    expect(keyterms).toContain("cmux");
    expect(keyterms.length).toBeLessThanOrEqual(50);
    expect(keyterms.every((term) => term.length <= 20)).toBe(true);

    // All of the audio, in order, in chunks of 0.1–1 s; context only on the first, commit only on the last.
    expect(audioOf(session).equals(Buffer.concat(chunks))).toBe(true);
    expect(session.chunks.every((chunk) => chunk.message_type === "input_audio_chunk" && chunk.sample_rate === 16000)).toBe(true);
    expect(session.chunks[0].previous_text).toBeTruthy();
    expect(session.chunks.slice(1).some((chunk) => "previous_text" in chunk)).toBe(false);
    expect(session.chunks.map((chunk) => chunk.commit)).toEqual([...session.chunks.slice(1).map(() => false), true]);
    for (const chunk of session.chunks.slice(0, -1)) {
      const size = Buffer.from(chunk.audio_base_64, "base64").length;
      expect(size).toBeGreaterThanOrEqual(3_200);
      expect(size).toBeLessThanOrEqual(32_000);
    }
    await link.until(() => session.closed); // one session per utterance
  });

  test("an utterance with no audio is done at once, without a session commit", async () => {
    const before = sessions.length;
    link.send(AUDIO_START, 83, { sampleRate: 16000, channels: 1, encoding: "pcm_s16le" });
    link.send(AUDIO_END, 83);
    await link.until(() => transcripts(83).some((t) => t.done));
    expect(transcripts(83).at(-1)).toEqual({ final: "", volatile: "", done: true });
    expect(sessions.slice(before).flatMap((session) => session.chunks)).toHaveLength(0);
  });

  test("a cancelled utterance closes its session and reports nothing more", async () => {
    await dictate(85, tone(12));
    link.send(AUDIO_CANCEL, 85);
    await link.until(() => sessions.at(-1)!.closed);
    await Bun.sleep(300);
    expect(transcripts(85).some((t) => t.done)).toBe(false);
    expect(sessions.at(-1)!.chunks.some((chunk) => chunk.commit)).toBe(false);
  });

  test("an ElevenLabs error with no on-device recognizer to take over is an ERROR for the utterance", async () => {
    mode = "quota";
    await dictate(87, tone(6));
    await link.until(() => link.of(87).some((f) => f.kind === ERROR));
    const message = link.of(87).find((f) => f.kind === ERROR)!.body.message as string;
    expect(message).toContain("ElevenLabs");
    expect(message).toContain("quota_exceeded");
    link.send(AUDIO_END, 87);
    await Bun.sleep(300);
    expect(transcripts(87).some((t) => t.done)).toBe(false);
  });
});
