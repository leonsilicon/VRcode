import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { randomBytes } from "node:crypto";
import { frame, RelayFixture, type RelayConnection } from "../lib/relay-fixture";

describe.skipIf(!process.env.VRCODE_RELAY_BINARY)("real native TLS relay (set VRCODE_RELAY_BINARY to enable)", () => {
  const fixture = new RelayFixture();
  beforeAll(async () => { await fixture.start(process.env.VRCODE_RELAY_BINARY!, process.env.VRCODE_RELAY_BLUETOOTH === "1"); }, 30_000);
  afterAll(async () => { await fixture.stop(); });

  async function connected(check: (connection: RelayConnection) => Promise<void>, authenticate = true) {
    const connection = await fixture.connect(authenticate);
    try { await check(connection); } finally { connection.socket.destroy(); }
  }

  test("both services multiplex and preserve binary data", async () => connected(async (connection) => {
    connection.send(1, 1, Buffer.from([1]));
    connection.send(1, 2, Buffer.from([2]));
    const opened = [await connection.packet(), await connection.packet()];
    expect(opened.map(([kind, channel]) => [kind, channel]).sort((a, b) => a[1] - b[1])).toEqual([[4, 1], [4, 2]]);
    const payloads = [Buffer.alloc(0), randomBytes(16384), randomBytes(8192)];
    connection.send(2, 1, payloads[1]);
    connection.send(2, 2, payloads[2]);
    const actual = [Buffer.alloc(0), Buffer.alloc(0), Buffer.alloc(0)];
    while (actual[1].length < payloads[1].length || actual[2].length < payloads[2].length) {
      const [kind, channel, data] = await connection.packet();
      expect(kind).toBe(2);
      expect([1, 2]).toContain(channel);
      actual[channel] = Buffer.concat([actual[channel], data]);
    }
    expect(actual).toEqual(payloads);
  }));
  test("fragmented authentication and headers", async () => connected(async (connection) => {
    for (const value of Buffer.from(fixture.config.token, "base64")) connection.socket.write(Buffer.from([value]));
    expect((await connection.reader.read(4)).toString()).toBe("VRL1");
    for (const value of frame(1, 7, Buffer.from([1]))) connection.socket.write(Buffer.from([value]));
    expect(await connection.packet()).toEqual([4, 7, Buffer.alloc(0)]);
  }, false));
  test("wrong token is rejected", async () => connected(async (connection) => {
    connection.socket.write(Buffer.alloc(32));
    expect(await connection.reader.read(1, true)).toEqual(Buffer.alloc(0));
  }, false));
  test("unknown service cannot open arbitrary ports", async () => connected(async (connection) => {
    connection.send(1, 1, Buffer.from([3]));
    expect(await connection.reader.read(1, true)).toEqual(Buffer.alloc(0));
  }));
  test("oversized frames are rejected before their body arrives", async () => connected(async (connection) => {
    const header = frame(2, 1);
    header.writeUInt32LE(32769, 5);
    connection.socket.write(header);
    expect(await connection.reader.read(1, true)).toEqual(Buffer.alloc(0));
  }));
  test("closing one channel preserves the other service", async () => connected(async (connection) => {
    connection.send(1, 1, Buffer.from([1]));
    expect(await connection.packet()).toEqual([4, 1, Buffer.alloc(0)]);
    connection.send(3, 1);
    connection.send(1, 2, Buffer.from([2]));
    expect(await connection.packet()).toEqual([4, 2, Buffer.alloc(0)]);
    const payload = Buffer.from("speech remains connected");
    connection.send(2, 2, payload);
    expect(await connection.packet()).toEqual([2, 2, payload]);
  }));
});
