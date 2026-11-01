/** Quest ↔ VRcode Mac settings mirroring through the real Mac service (kinds 13–15). Opt in with VRCODE_RELAY_BINARY. */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createConnection, createServer, type Socket } from "node:net";
import { once } from "node:events";
import { tmpdir } from "node:os";
import { join } from "node:path";

const binary = process.env.VRCODE_RELAY_BINARY;
const suite = binary ? describe : describe.skip;
const SETTINGS = 13, SETTINGS_SET = 14, SETTINGS_ACTION = 15, ERROR = 8;

async function freePort() {
  const server = createServer(); server.listen(0, "127.0.0.1"); await once(server, "listening");
  const port = (server.address() as { port: number }).port;
  await new Promise<void>((resolve) => server.close(() => resolve())); return port;
}

class Peer {
  private buffer = Buffer.alloc(0);
  private waiters: { kind: number; resolve: (value: any) => void }[] = [];
  frames: { kind: number; body: any }[] = [];
  private constructor(readonly socket: Socket) {
    socket.on("data", (data) => {
      this.buffer = Buffer.concat([this.buffer, data]);
      while (this.buffer.length >= 16 && this.buffer.length >= 16 + this.buffer.readUInt32LE(0)) {
        const length = this.buffer.readUInt32LE(0), kind = this.buffer[4];
        const body = length ? JSON.parse(this.buffer.subarray(16, 16 + length).toString()) : {};
        this.buffer = this.buffer.subarray(16 + length);
        const waiter = this.waiters.findIndex((w) => w.kind === kind);
        if (waiter >= 0) this.waiters.splice(waiter, 1)[0].resolve(body); else this.frames.push({ kind, body });
      }
    });
  }
  static async open(port: number, client: string) {
    const socket = createConnection(port, "127.0.0.1"); await once(socket, "connect");
    const peer = new Peer(socket); peer.send(1, { client, protocol: 1 });
    expect((await peer.next(2)).settings).toBe(true);
    return peer;
  }
  send(kind: number, value: unknown) {
    const data = Buffer.from(JSON.stringify(value)), header = Buffer.alloc(16);
    header.writeUInt32LE(data.length); header[4] = kind; header.writeUInt16LE(1, 6);
    this.socket.write(Buffer.concat([header, data]));
  }
  next(kind: number, timeout = 5000): Promise<any> {
    const queued = this.frames.findIndex((f) => f.kind === kind);
    if (queued >= 0) return Promise.resolve(this.frames.splice(queued, 1)[0].body);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`no frame of kind ${kind}`)), timeout);
      this.waiters.push({ kind, resolve: (v) => { clearTimeout(timer); resolve(v); } });
    });
  }
  async close() { this.socket.end(); await once(this.socket, "close"); }
}

suite("settings mirroring", () => {
  let directory: string, port: number, service: ReturnType<typeof Bun.spawn>;
  const schema = { version: 1, pages: [{ id: "workspace", title: "Workspace", sections: [] }] };

  beforeAll(async () => {
    directory = await mkdtemp(join(tmpdir(), "vrcode-settings-"));
    port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--port", String(port), "--link-port", String(await freePort()),
      "--bridge-port", String(await freePort()), "--link-directory", join(directory, "link")],
      { env: { ...process.env, VRCODE_SETTINGS_FILE: join(directory, "quest-settings.json") }, stdout: "ignore", stderr: "pipe" });
    for (let i = 0; i < 100; i++) {
      try { const s = createConnection(port, "127.0.0.1"); await once(s, "connect"); s.destroy(); return; } catch { await Bun.sleep(100); }
    }
    throw new Error("service did not start");
  });
  afterAll(async () => { service?.kill(); await service?.exited; await rm(directory, { recursive: true, force: true }); });

  test("the Mac app sees, changes and queues Quest settings", async () => {
    const app = await Peer.open(port, "vrcode-mac-app");
    expect(await app.next(SETTINGS)).toEqual({ connected: false, pending: {} });

    const quest = await Peer.open(port, "vrcode");
    quest.send(SETTINGS, { device: "Quest 3", schema, values: { passthrough: true, terminal_font_size: 25 } });
    const live = await app.next(SETTINGS);
    expect(live).toMatchObject({ connected: true, device: "Quest 3", schema, values: { terminal_font_size: 25 }, pending: {} });

    // A connected Quest gets the edit at once and confirms with fresh SETTINGS.
    app.send(SETTINGS_SET, { values: { terminal_font_size: 30 } });
    expect(await quest.next(SETTINGS_SET)).toEqual({ values: { terminal_font_size: 30 } });
    quest.send(SETTINGS, { device: "Quest 3", schema, values: { passthrough: true, terminal_font_size: 30 } });
    expect((await app.next(SETTINGS)).values.terminal_font_size).toBe(30);
    app.send(SETTINGS_ACTION, { action: "arrange_windows" });
    expect(await quest.next(SETTINGS_ACTION)).toEqual({ action: "arrange_windows" });

    // The headset cannot drive another headset's settings.
    quest.send(SETTINGS_SET, { values: { passthrough: false } });
    expect((await quest.next(ERROR)).message).toContain("Only VRcode Mac");

    // Offline: the last values stay visible, edits wait, actions are refused.
    await quest.close();
    expect(await app.next(SETTINGS)).toMatchObject({ connected: false, values: { terminal_font_size: 30 } });
    app.send(SETTINGS_SET, { values: { passthrough: false } });
    expect((await app.next(SETTINGS)).pending).toEqual({ passthrough: false });
    app.send(SETTINGS_ACTION, { action: "arrange_windows" });
    expect((await app.next(ERROR)).message).toContain("Connect the Quest");
    const saved = JSON.parse(await readFile(join(directory, "quest-settings.json"), "utf8"));
    expect(saved).toMatchObject({ pending: { passthrough: false }, state: { device: "Quest 3" } });

    // On reconnect the Quest receives the queued edit after its first SETTINGS.
    const back = await Peer.open(port, "vrcode");
    back.send(SETTINGS, { device: "Quest 3", schema, values: { passthrough: true, terminal_font_size: 30 } });
    expect(await back.next(SETTINGS_SET)).toEqual({ values: { passthrough: false } });
    expect(await app.next(SETTINGS)).toMatchObject({ connected: true, pending: {} });
    await Promise.all([back.close(), app.close()]);
  });
});
