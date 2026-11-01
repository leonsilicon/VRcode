/** Real daemon tests, with no cmux frontend. Opt in with VRCODE_CMUX_BINARY=/path/to/vrcode-mac. */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdir, mkdtemp, realpath, rm } from "node:fs/promises";
import { createConnection, createServer, type Socket } from "node:net";
import { join } from "node:path";
import { once } from "node:events";
import { networkInterfaces } from "node:os";

const binary = process.env.VRCODE_CMUX_BINARY;
const suite = binary ? describe : describe.skip;

async function freePort() {
  const server = createServer(); server.listen(0, "127.0.0.1"); await once(server, "listening");
  const port = (server.address() as { port: number }).port;
  await new Promise<void>((resolve) => server.close(() => resolve())); return port;
}

class Control {
  private buffer = Buffer.alloc(0);
  private responses = new Map<string, (value: any) => void>();
  events: any[] = [];
  constructor(readonly socket: Socket) {
    socket.on("data", (data) => {
      this.buffer = Buffer.concat([this.buffer, data]);
      while (this.buffer.length >= 16 && this.buffer.length >= 16 + this.buffer.readUInt32LE(0)) {
        const length = this.buffer.readUInt32LE(0), kind = this.buffer[4];
        const value = JSON.parse(this.buffer.subarray(16, 16 + length).toString());
        this.buffer = this.buffer.subarray(16 + length);
        if (kind === 10 && value.type === "response") { this.responses.get(value.id)?.(value); this.responses.delete(value.id); }
        else this.events.push({ kind, ...value });
      }
    });
  }
  send(kind: number, value: unknown) {
    const data = Buffer.from(JSON.stringify(value)), header = Buffer.alloc(16);
    header.writeUInt32LE(data.length); header[4] = kind; header.writeUInt16LE(1, 6);
    this.socket.write(Buffer.concat([header, data]));
  }
  async request(operation: string, params: Record<string, unknown> = {}, key?: string) {
    return this.begin(operation, params, key).response;
  }
  begin(operation: string, params: Record<string, unknown> = {}, key?: string) {
    const id = crypto.randomUUID();
    let timer: ReturnType<typeof setTimeout>;
    const result = new Promise<any>((resolve, reject) => {
      timer = setTimeout(() => { this.responses.delete(id); reject(new Error(`No response for ${operation}`)); }, 12_000);
      this.responses.set(id, (value) => { clearTimeout(timer); resolve(value); });
    });
    this.send(9, { protocol: "cmux.protocol/2", type: "request", id, operation, params, ...(key ? { idempotency_key: key } : {}) });
    return { id, response: result, discard: () => { clearTimeout(timer); this.responses.delete(id); } };
  }
  async ok(operation: string, params: Record<string, unknown> = {}, mutation = false, key = crypto.randomUUID()) {
    const reply = await this.request(operation, params, mutation ? key : undefined);
    if (!reply.ok) throw new Error(`${operation}: ${JSON.stringify(reply.error)}`);
    return reply.result;
  }
}

suite("background cmux control", () => {
  let directory: string, session: string, env: Record<string, string | undefined>, port: number;
  let daemon: ReturnType<typeof Bun.spawn>, service: ReturnType<typeof Bun.spawn>, control: Control;
  const cmux = process.env.CMUX_BIN ?? `${process.env.HOME}/.cmux/bin/cmux`;
  async function cli(args: string[]) {
    const child = Bun.spawn([cmux, "--session", session, ...args], { env, stdout: "pipe", stderr: "pipe" });
    const output = await new Response(child.stdout).text();
    const error = await new Response(child.stderr).text();
    if (await child.exited !== 0) throw new Error(error || output);
    return output;
  }

  beforeAll(async () => {
    directory = await mkdtemp("/tmp/vrcode-cmux-");
    session = `qa-${crypto.randomUUID().slice(0, 8)}`;
    env = { ...process.env, XDG_RUNTIME_DIR: directory, CMUX_TUI_STATE_DIR: join(directory, "state"),
      CMUX_TUI_CONFIG: join(directory, "config"), CMUX_TUI_SOCKET: undefined, CMUX_MUX_SOCKET: undefined };
    await Bun.write(join(directory, "config"), "");
    daemon = Bun.spawn([cmux, "server", "start", "--session", session], { env, stdout: "ignore", stderr: "ignore" });
    let socket: string | undefined;
    for (let i = 0; i < 60; i++) {
      try { socket = JSON.parse(await cli(["--json", "server", "status"])).socket; if (socket) break; } catch {}
      await Bun.sleep(100);
    }
    if (!socket) throw new Error("Isolated cmux daemon did not start");
    port = await freePort();
    service = Bun.spawn([binary!, "--control-only", "--cmux-socket", socket, "--port", String(port),
      "--link-port", String(await freePort()), "--link-directory", join(directory, "link")], { stdout: "ignore", stderr: "ignore" });
    for (let i = 0; i < 80; i++) {
      try {
        const client = createConnection({ host: "127.0.0.1", port });
        await once(client, "connect"); control = new Control(client); break;
      } catch { await Bun.sleep(100); }
    }
    if (!control) throw new Error("VRcode control service did not start");
    control.send(1, { client: "vrcode-cmux-test", protocol: 1 });
    for (let i = 0; i < 50 && !control.events.some((e) => e.kind === 2); i++) await Bun.sleep(20);
    expect(control.events.find((e) => e.kind === 2)?.cmux).toBe(true);
    expect(control.events.find((e) => e.kind === 2)?.ready).toBe(false); // works without speech permissions/models
  }, 20_000);

  afterAll(async () => {
    control?.socket.destroy(); service?.kill();
    if (session) await cli(["server", "stop", "--force"]).catch(() => {});
    daemon?.kill();
    if (directory) await rm(directory, { recursive: true, force: true });
  });

  test("layout, explicit targets, input, copy, and close work with no Mac frontend", async () => {
    const initial = await control.ok("session.snapshot");
    expect(initial.clients).toHaveLength(1);
    expect(initial.clients[0]).toMatchObject({ self: true, transport: "unix", attached_terminal_ids: [] });
    const key = crypto.randomUUID();
    const create = { name: "Quest background QA", initial_content: "empty" };
    const first = await control.ok("workspace.create", create, true, key);
    const duplicate = await control.ok("workspace.create", create, true, key);
    expect(duplicate.value).toEqual(first.value);
    expect(duplicate.replayed).toBe(true);
    let snapshot = await control.ok("session.snapshot");
    const workspace = snapshot.workspaces.find((w: any) => w.name === create.name).id;
    expect(snapshot.workspaces.filter((w: any) => w.name === create.name)).toHaveLength(1);
    await control.ok("workspace.run", { workspace, argv: ["/bin/sh"], name: "Quest shell", cols: 80, rows: 24 }, true);
    snapshot = await control.ok("session.snapshot");
    const terminal = snapshot.terminals[0].id, pane = snapshot.panes[0].id, screen = snapshot.screens[0].id, tab = snapshot.tabs[0].id;
    // Cancelled waits intentionally send no target response. More than 128 cycles also proves
    // the proxy releases its pending slots at the cancellation confirmation.
    for (let i = 0; i < 130; i++) {
      const waiting = control.begin("terminal.wait_exit", { terminal });
      const cancel = await control.ok("request.cancel", { request_id: waiting.id });
      expect(cancel.canceled).toBe(true);
      waiting.discard();
    }
    await control.ok("terminal.input.write", { terminal, text: "printf 'QUEST_BACKGROUND_INPUT\\n'\n" }, true);
    let copied = "";
    for (let i = 0; i < 40; i++) {
      copied = (await control.ok("terminal.copy", { terminal, mode: "scrollback" })).text;
      if (copied.includes("QUEST_BACKGROUND_INPUT")) break;
      await Bun.sleep(50);
    }
    expect(copied).toContain("QUEST_BACKGROUND_INPUT");
    const attachment = await control.ok("terminal.attach", { terminal, stream_id: `stream_${crypto.randomUUID().replaceAll("-", "")}` });
    expect(attachment.attachment_lease).toBeString();
    await control.ok("terminal.viewer.resize", { terminal, attachment_lease: attachment.attachment_lease, cols: 80, rows: 24 });
    await control.ok("terminal.viewer.release", { terminal, attachment_lease: attachment.attachment_lease });
    await control.ok("stream.cancel", { stream: attachment.stream_id });
    await control.ok("pane.split", { pane, direction: "right", cols: 80, rows: 24 }, true);
    await control.ok("tab.create_terminal", { pane, name: "Second tab", cols: 80, rows: 24 }, true);
    await control.ok("tab.rename", { tab, name: "Renamed from Quest" }, true);
    await control.ok("pane.zoom", { pane, enabled: true }, true);
    await control.ok("pane.zoom", { pane, enabled: false }, true);
    await control.ok("pane.focus", { pane }, true);
    await control.ok("tab.focus", { tab }, true);
    await control.ok("screen.rename", { screen, name: "Quest screen" }, true);
    await control.ok("workspace.rename", { workspace, name: "Quest workspace" }, true);
    await control.ok("terminal.viewport.scroll", { terminal, delta_rows: -5 }, true);
    await control.ok("terminal.input.mouse", { terminal, kind: "down", row: 1, column: 1, button: "right" }, true);
    await control.ok("terminal.input.mouse", { terminal, kind: "up", row: 1, column: 1, button: "right" }, true);
    snapshot = await control.ok("session.snapshot");
    expect(snapshot.tabs.some((t: any) => t.name === "Renamed from Quest")).toBe(true);
    expect(snapshot.clients).toHaveLength(1);
    expect(snapshot.clients[0]).toMatchObject({ self: true, transport: "unix", client_kind: null });
    expect(snapshot.clients[0].sizes.every((size: any) => !size.participating)).toBe(true);
    await control.ok("tab.close", { tab }, true);
    // A closed view keeps its process; explicit terminal.close owns process termination.
    expect((await control.ok("terminal.get", { terminal })).id).toBe(terminal);
    await control.ok("terminal.close", { terminal }, true);
    await control.ok("workspace.close", { workspace }, true);
  }, 20_000);

  test("window close removes the exact cmux screen while another screen is focused", async () => {
    const workspace = (await control.ok("workspace.create", { name: "Window controls QA", initial_content: "empty" }, true)).value.workspace_id;
    try {
      const first = (await control.ok("screen.create", { workspace, name: "Close this window" }, true)).value;
      const second = (await control.ok("screen.create", { workspace, name: "Keep this window" }, true)).value;
      await control.ok("pane.focus", { pane: second.pane_id }, true);
      await control.ok("screen.close", { screen: first.screen_id }, true);
      const snapshot = await control.ok("session.snapshot");
      expect(snapshot.screens.some((screen: any) => screen.id === first.screen_id)).toBe(false);
      expect(snapshot.screens.some((screen: any) => screen.id === second.screen_id)).toBe(true);
      expect(snapshot.panes.some((pane: any) => pane.id === second.pane_id)).toBe(true);
    } finally {
      await control.ok("workspace.close", { workspace }, true);
    }
  });

  test("a new floating terminal inherits the source's directory and grid before receiving input", async () => {
    const workspace = (await control.ok("workspace.create", { name: "Inherited directory QA", initial_content: "empty" }, true)).value.workspace_id;
    const path = join(directory, "项目 with spaces ' and $() literals");
    await mkdir(path);
    const expected = await realpath(path);
    let childWorkspace: string | undefined;
    try {
      const source = (await control.ok("workspace.run", { workspace, argv: ["/bin/sh"], cwd: directory, cols: 228, rows: 83 }, true)).value;
      const quoted = `'${path.replaceAll("'", "'\\''")}'`;
      await control.ok("terminal.input.write", { terminal: source.terminal_id, text: `cd ${quoted}\n` }, true);
      let process: any;
      for (let i = 0; i < 80; i++) {
        process = await control.ok("terminal.process.get", { terminal: source.terminal_id });
        if ((process.foreground_cwd || process.cwd) === expected) break;
        await Bun.sleep(50);
      }
      expect(process.foreground_cwd || process.cwd).toBe(expected);
      const sourceTerminal = await control.ok("terminal.get", { terminal: source.terminal_id });
      const cols = sourceTerminal.cols ?? sourceTerminal.size?.cols;
      const rows = sourceTerminal.rows ?? sourceTerminal.size?.rows;
      expect({ cols, rows }).toEqual({ cols: 228, rows: 83 });
      childWorkspace = (await control.ok("workspace.create", { initial_content: "empty" }, true)).value.workspace_id;
      const child = (await control.ok("tab.create_terminal", {
        workspace: childWorkspace, cwd: process.foreground_cwd || process.cwd, name: "Inherited directory", cols, rows,
      }, true)).value;
      expect(child.screen_id).not.toBe(source.screen_id);
      expect(child.terminal_id).not.toBe(source.terminal_id);
      expect(child.workspace_id).toBe(childWorkspace);
      expect(child.workspace_id).not.toBe(workspace);
      let childProcess: any;
      for (let i = 0; i < 80; i++) {
        childProcess = await control.ok("terminal.process.get", { terminal: child.terminal_id });
        if ((childProcess.foreground_cwd || childProcess.cwd) === expected) break;
        await Bun.sleep(50);
      }
      expect(childProcess.foreground_cwd || childProcess.cwd).toBe(expected);
      const terminal = await control.ok("terminal.get", { terminal: child.terminal_id });
      expect(terminal.cols ?? terminal.size?.cols).toBe(cols);
      expect(terminal.rows ?? terminal.size?.rows).toBe(rows);
    } finally {
      // Closing a screen can prune empty workspaces as well; tolerate only an already-gone one.
      for (const id of [childWorkspace, workspace]) {
        if (!id) continue;
        const reply = await control.request("workspace.close", { workspace: id }, crypto.randomUUID());
        if (!reply.ok && reply.error?.code !== "selector.not_found") throw new Error(JSON.stringify(reply.error));
      }
    }
  }, 15_000);

  test("the Quest pins a terminal's grid over other viewers and can hand it back", async () => {
    const workspace = (await control.ok("workspace.create", { name: "Grid size QA", initial_content: "empty" }, true)).value.workspace_id;
    const stream = `stream_${crypto.randomUUID().replaceAll("-", "")}`;
    try {
      const terminal = (await control.ok("screen.create", { workspace, name: "Resize from the Quest" }, true)).value.terminal_id;
      const grid = async () => {
        const t = await control.ok("terminal.get", { terminal });
        return { cols: t.cols ?? t.size?.cols, rows: t.rows ?? t.size?.rows };
      };
      const settle = async (cols: number, rows: number) => {
        for (let i = 0; i < 40 && JSON.stringify(await grid()) !== JSON.stringify({ cols, rows }); i++) await Bun.sleep(25);
        expect(await grid()).toEqual({ cols, rows });
      };
      // Another viewer (a Mac frontend, say) sizes the terminal to its own window.
      const lease = (await control.ok("terminal.attach", { terminal, stream_id: stream })).attachment_lease;
      await control.ok("terminal.viewer.resize", { terminal, attachment_lease: lease, cols: 60, rows: 20 });
      await settle(60, 20);

      const pinned = await control.ok("vrcode.terminal.size.set", { terminal, cols: 132, rows: 43 });
      expect(pinned).toMatchObject({ terminal, cols: 132, rows: 43, policy: "fixed" });
      await settle(132, 43);
      await control.ok("terminal.viewer.resize", { terminal, attachment_lease: lease, cols: 70, rows: 25 });
      await Bun.sleep(100);
      expect(await grid()).toEqual({ cols: 132, rows: 43 });

      const released = await control.ok("vrcode.terminal.size.set", { terminal });
      expect(released.policy).not.toBe("fixed");
      await settle(70, 25);

      for (const bad of [{ terminal, cols: 80 }, { terminal, cols: 0, rows: 24 }, { cols: 80, rows: 24 }]) {
        expect((await control.request("vrcode.terminal.size.set", bad)).error.code).toBe("vrcode.invalid_request");
      }
      const missing = await control.request("vrcode.terminal.size.set", { terminal: `term_${"0".repeat(32)}`, cols: 80, rows: 24 });
      expect(missing.error.message).toContain("no open terminal");
    } finally {
      await control.ok("stream.cancel", { stream }).catch(() => {});
      await control.ok("workspace.close", { workspace }, true);
    }
  });

  test("persistent streams can be cancelled while ordinary requests keep working", async () => {
    const stream = `stream_${crypto.randomUUID().replaceAll("-", "")}`;
    const result = await control.ok("session.events", { stream_id: stream });
    expect(result.stream_id).toBe(stream);
    await control.ok("session.get");
    await control.ok("stream.cancel", { stream });
    await control.ok("session.get");
    for (let i = 0; i < 30 && !control.events.some((e) => e.type === "stream_end" && e.stream_id === stream); i++) await Bun.sleep(20);
    expect(control.events.some((e) => e.type === "stream_end" && e.stream_id === stream)).toBe(true);
  });

  test("unknown actions and mutations without keys are rejected before dispatch", async () => {
    expect((await control.request("mac.activate_window")).error.code).toBe("vrcode.invalid_request");
    expect((await control.request("workspace.create", { initial_content: "empty" })).error.message).toContain("idempotency");
    const stale = await control.request("terminal.get", { terminal: `term_${"0".repeat(32)}` });
    expect(stale.ok).toBe(false);
    expect(stale.error.code).toBe("selector.not_found");
  });

  test("a lost mutation reply is reported and never replayed on the next connection", async () => {
    const path = join(directory, "failure.sock");
    let mutations = 0;
    const sockets = new Set<Socket>();
    const fake = createServer((socket) => {
      sockets.add(socket); socket.on("close", () => sockets.delete(socket));
      let buffer = "";
      socket.on("data", (data) => {
        buffer += data.toString();
        while (buffer.includes("\n")) {
          const index = buffer.indexOf("\n"), request = JSON.parse(buffer.slice(0, index)); buffer = buffer.slice(index + 1);
          if (request.operation === "workspace.create") { mutations++; socket.destroy(); }
          else socket.write(JSON.stringify({ protocol: "cmux.protocol/2", type: "response", id: request.id, ok: true, result: {} }) + "\n");
        }
      });
    });
    fake.listen(path); await once(fake, "listening");
    const testPort = await freePort();
    const failedService = Bun.spawn([binary!, "--control-only", "--any-interface", "--cmux-socket", path, "--port", String(testPort),
      "--link-port", String(await freePort()), "--link-directory", join(directory, "failure-link")], { stdout: "ignore", stderr: "ignore" });
    let failedControl: Control | undefined;
    try {
      for (let i = 0; i < 80; i++) {
        try { const socket = createConnection({ host: "127.0.0.1", port: testPort }); await once(socket, "connect"); failedControl = new Control(socket); break; }
        catch { await Bun.sleep(50); }
      }
      if (!failedControl) throw new Error("Failure-test service did not start");
      const lan = Object.values(networkInterfaces()).flat().find((address) => address?.family === "IPv4" && !address.internal)?.address;
      if (lan) {
        const socket = createConnection({ host: lan, port: testPort }); await once(socket, "connect");
        const untrusted = new Control(socket);
        try {
          const rejected = await untrusted.request("workspace.create", { initial_content: "empty" }, crypto.randomUUID());
          expect(rejected.error.code).toBe("vrcode.paired_relay_required");
          expect(mutations).toBe(0);
        } finally { socket.destroy(); }
      }
      const reply = await failedControl.request("workspace.create", { initial_content: "empty" }, crypto.randomUUID());
      expect(reply.ok).toBe(false);
      expect(reply.error.message).toContain("unknown");
      await failedControl.ok("session.snapshot");
      expect(mutations).toBe(1);
    } finally {
      failedControl?.socket.destroy(); failedService.kill();
      for (const socket of sockets) socket.destroy();
      await new Promise<void>((resolve) => fake.close(() => resolve()));
    }
  }, 10_000);
});

suite("a cmux daemon the service starts", () => {
  const cmux = process.env.CMUX_BIN ?? `${process.env.HOME}/.cmux/bin/cmux`;
  const harness = { NO_COLOR: "1", FORCE_COLOR: "0", CI: "1", PAGER: "cat", GIT_PAGER: "cat", GIT_EDITOR: "true",
    CLAUDECODE: "1", CLAUDE_CODE_ENTRYPOINT: "cli", CODEX_CI: "1" };

  test("keeps a coding agent's NO_COLOR and other tool-shell settings out of its terminals", async () => {
    const directory = await mkdtemp("/tmp/vrcode-cmux-env-");
    const session = `qa-${crypto.randomUUID().slice(0, 8)}`;
    // The service as an agent's tool shell would start it, against an isolated session it must start itself.
    const env = { ...process.env, ...harness, VRCODE_KEPT: "kept", XDG_RUNTIME_DIR: directory,
      CMUX_TUI_STATE_DIR: join(directory, "state"), CMUX_TUI_CONFIG: join(directory, "config"),
      CMUX_TUI_SOCKET: undefined, CMUX_MUX_SOCKET: undefined };
    await Bun.write(join(directory, "config"), "");
    const port = await freePort();
    const service = Bun.spawn([binary!, "--control-only", "--cmux-session", session, "--port", String(port),
      "--link-port", String(await freePort()), "--link-directory", join(directory, "link")], { env, stdout: "ignore", stderr: "ignore" });
    let control: Control | undefined;
    let workspace: string | undefined;
    try {
      for (let i = 0; i < 80 && !control; i++) {
        try { const socket = createConnection({ host: "127.0.0.1", port }); await once(socket, "connect"); control = new Control(socket); }
        catch { await Bun.sleep(100); }
      }
      if (!control) throw new Error("VRcode control service did not start");
      control.send(1, { client: "vrcode-cmux-env-test", protocol: 1 });
      workspace = (await control.ok("workspace.create", { initial_content: "empty" }, true)).value.workspace_id;
      // A terminal's process gets the daemon's environment, as the palm menu's Claude Code does.
      const terminal = (await control.ok("workspace.run", {
        workspace, argv: ["/bin/sh", "-c", "env; printf 'ENV_%s\\n' DONE; exec sleep 30"], cols: 200, rows: 100,
      }, true)).value.terminal_id;
      let text = "";
      for (let i = 0; i < 80 && !text.includes("ENV_DONE"); i++) {
        text = (await control.ok("terminal.copy", { terminal, mode: "scrollback" })).text;
        if (!text.includes("ENV_DONE")) await Bun.sleep(50);
      }
      expect(text).toContain("ENV_DONE");
      const names = text.split("\n").map((line) => line.split("=")[0].trim());
      expect(names).toContain("VRCODE_KEPT");
      expect(names.filter((name) => name in harness)).toEqual([]);
    } finally {
      if (control && workspace) await control.request("workspace.close", { workspace }, crypto.randomUUID()).catch(() => {});
      control?.socket.destroy(); service.kill();
      Bun.spawnSync([cmux, "--session", session, "server", "stop", "--force"], { env, stdout: "ignore", stderr: "ignore" });
      await rm(directory, { recursive: true, force: true });
    }
  }, 20_000);
});
