/** Isolated real Swift relay and TCP echo services, shared by Bun and Android checks. */
import { createHash } from "node:crypto";
import { open, mkdtemp, readFile, rm, cp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createServer, type Server, type Socket } from "node:net";
import { connect as connectTLS, type TLSSocket } from "node:tls";
import { SocketReader, listenerStatus } from "./network";
import { repoRoot, run } from "./process";

export type RelayConfiguration = { host: string; port: number; fingerprint: string; token: string; name: string };

export function frame(kind: number, channel: number, body = Buffer.alloc(0)) {
  const header = Buffer.alloc(9);
  header.writeUInt8(kind, 0);
  header.writeUInt32LE(channel, 1);
  header.writeUInt32LE(body.length, 5);
  return Buffer.concat([header, body]);
}

export class RelayConnection {
  constructor(public socket: TLSSocket, public reader: SocketReader) {}
  send(kind: number, channel: number, body = Buffer.alloc(0)) { this.socket.write(frame(kind, channel, body)); }
  async packet(): Promise<[number, number, Buffer]> {
    const header = await this.reader.read(9);
    return [header.readUInt8(0), header.readUInt32LE(1), await this.reader.read(header.readUInt32LE(5))];
  }
}

export class RelayFixture {
  config!: RelayConfiguration;
  logPath = "";
  private directory = "";
  private servers: Server[] = [];
  private sockets = new Set<Socket>();
  private process?: ReturnType<typeof Bun.spawn>;
  private log?: Awaited<ReturnType<typeof open>>;
  private packaged = false;

  async start(binary: string, bluetooth = false) {
    try {
      this.directory = await mkdtemp(join(tmpdir(), "vrcode-relay-test-"));
      for (let i = 0; i < 2; i++) {
        const server = createServer((socket) => {
          this.sockets.add(socket);
          socket.on("error", () => {});
          socket.on("close", () => this.sockets.delete(socket));
          socket.on("data", (data) => socket.write(data));
        });
        this.servers.push(server);
        await new Promise<void>((resolve, reject) => { server.once("error", reject); server.listen(0, "127.0.0.1", resolve); });
      }
      const reserve = createServer();
      await new Promise<void>((resolve, reject) => { reserve.once("error", reject); reserve.listen(0, "127.0.0.1", resolve); });
      const port = (reserve.address() as { port: number }).port;
      await new Promise<void>((resolve) => reserve.close(() => resolve()));
      const args = ["--link-directory", this.directory, "--link-port", String(port)];
      const encoded = await run([binary, ...args, "--link-info", "--link-host", "127.0.0.1"], { timeout: 30_000 });
      if (!encoded.startsWith("vrcode:")) throw new Error("Relay returned an invalid connection key");
      this.config = JSON.parse(Buffer.from(encoded.slice(7), "base64").toString());
      this.logPath = join(this.directory, "relay.log");
      this.log = await open(this.logPath, "w");
      const ports = this.servers.map((server) => String((server.address() as { port: number }).port));
      const serve = ["--link-only", "--bridge-port", ports[0], "--port", ports[1]];
      this.packaged = bluetooth;
      const testApp = join(this.directory, "VRcode Connection Test.app");
      if (bluetooth) {
        // CoreBluetooth services are scoped by application identity. A second
        // production instance must not compete with a running user's service.
        await cp(join(repoRoot, "mac/.build/VRcode Mac.app"), testApp, { recursive: true });
        const plist = join(testApp, "Contents/Info.plist");
        await run(["/usr/bin/plutil", "-replace", "CFBundleIdentifier", "-string", "dev.improve.vrcode.mac.connection-test", plist]);
        await run(["/usr/bin/plutil", "-replace", "CFBundleName", "-string", "VRcode Connection Test", plist]);
        await run(["codesign", "--force", "--sign", "-", "--identifier", "dev.improve.vrcode.mac.connection-test", testApp]);
      }
      const command = bluetooth
        ? ["/usr/bin/open", "-g", "-n", "--stderr", this.logPath, testApp, "--args", ...args, ...serve, "--bluetooth", "--link-pid-file", join(this.directory, "pid")]
        : [binary, ...args, ...serve];
      this.process = Bun.spawn(command, { stdin: "ignore", stdout: "ignore", stderr: this.log.fd });
      const deadline = performance.now() + (bluetooth ? 60_000 : 5000);
      while (performance.now() < deadline) {
        const carriersReady = !bluetooth || await readFile(this.logPath, "utf8").then((log) =>
          log.includes("Bluetooth Classic relay registered") && log.includes("Bluetooth LE relay advertising"));
        if (carriersReady && await listenerStatus(port)) return this;
        await Bun.sleep(100);
      }
      throw new Error("Relay did not start: " + await readFile(this.logPath, "utf8"));
    } catch (error) {
      await this.stop();
      throw error;
    }
  }

  async connect(authenticate = true) {
    const socket = connectTLS({ host: "127.0.0.1", port: this.config.port, servername: "vrcode.local", rejectUnauthorized: false });
    this.sockets.add(socket);
    socket.on("close", () => this.sockets.delete(socket));
    const reader = new SocketReader(socket);
    try {
      await new Promise<void>((resolve, reject) => {
        const timer = setTimeout(() => socket.destroy(new Error("TLS connection timed out")), 4000);
        socket.once("error", (error) => { clearTimeout(timer); reject(error); });
        socket.once("secureConnect", () => { clearTimeout(timer); resolve(); });
      });
      const certificate = socket.getPeerCertificate().raw;
      if (!certificate || createHash("sha256").update(certificate).digest("hex") !== this.config.fingerprint) throw new Error("Relay TLS certificate fingerprint mismatch");
      if (authenticate) {
        socket.write(Buffer.from(this.config.token, "base64"));
        if (!(await reader.read(4)).equals(Buffer.from("VRL1"))) throw new Error("Relay authentication failed");
      }
      return new RelayConnection(socket, reader);
    } catch (error) { socket.destroy(); throw error; }
  }

  async stop() {
    if (this.packaged && this.directory) {
      try { process.kill(Number(await readFile(join(this.directory, "pid"), "utf8")), "SIGTERM"); }
      catch (error) { if (!["ENOENT", "ESRCH"].includes((error as NodeJS.ErrnoException).code ?? "")) throw error; }
    }
    if (this.process) {
      this.process.kill();
      const timer = setTimeout(() => this.process?.kill("SIGKILL"), 5000);
      try { await this.process.exited; } finally { clearTimeout(timer); }
    }
    for (const socket of this.sockets) socket.destroy();
    for (const server of this.servers) if (server.listening) await new Promise<void>((resolve) => server.close(() => resolve()));
    await this.log?.close();
    if (this.directory) await rm(this.directory, { recursive: true, force: true });
    this.process = undefined;
    this.log = undefined;
    this.directory = "";
    this.servers = [];
  }
}
