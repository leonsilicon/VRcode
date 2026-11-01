import { createSocket } from "node:dgram";
import { createConnection, type Socket } from "node:net";

export async function macAddressFor(host: string): Promise<string> {
  const route = createSocket("udp4");
  try {
    return await new Promise((resolve, reject) => {
      route.once("error", reject);
      route.connect(9, host, () => resolve(route.address().address));
    });
  } finally {
    route.close();
  }
}

export async function listenerStatus(port: number): Promise<boolean> {
  return new Promise((resolve) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    const finish = (ready: boolean) => { socket.destroy(); resolve(ready); };
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
    socket.setTimeout(1000, () => finish(false));
  });
}

/** Buffered exact reads retain partial frames and reject promptly on EOF or socket errors. */
export class SocketReader {
  private buffer = Buffer.alloc(0);
  private ended = false;
  private error?: Error;
  private wake?: () => void;

  constructor(private socket: Socket) {
    socket.on("data", (chunk) => {
      this.buffer = Buffer.concat([this.buffer, chunk]);
      this.wake?.();
    });
    socket.on("error", (error) => { this.error = error; this.wake?.(); });
    socket.on("close", () => { this.ended = true; this.wake?.(); });
    socket.on("end", () => { this.ended = true; this.wake?.(); });
  }

  async read(count: number, allowEOF = false): Promise<Buffer> {
    const timer = setTimeout(() => this.socket.destroy(new Error("Socket read timed out")), 4000);
    try {
      while (this.buffer.length < count) {
        if (this.error) throw this.error;
        if (this.ended) {
          if (allowEOF && this.buffer.length === 0) return Buffer.alloc(0);
          throw new Error("Connection closed before the complete frame arrived");
        }
        await new Promise<void>((resolve) => { this.wake = resolve; });
      }
      const data = this.buffer.subarray(0, count);
      this.buffer = this.buffer.subarray(count);
      return data;
    } finally {
      clearTimeout(timer);
      this.wake = undefined;
    }
  }
}
