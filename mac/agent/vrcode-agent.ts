#!/usr/bin/env bun
/**
 * vrcode-agent: the AI mic's agent runner. VRcode Mac's Swift service (AiAgent.swift) starts it
 * once and talks JSON lines over stdin and stdout; it drives the provider's own coding agent over
 * the Agent Client Protocol (https://agentclientprotocol.com) with the official SDK:
 * Claude Code through `@agentclientprotocol/claude-agent-acp`, Codex through
 * `@agentclientprotocol/codex-acp`, Gemini CLI through its own `--acp` mode. Each agent signs in
 * with its own login and keeps its own tools, so it can look through the user's files and run
 * commands; it acts in the user's terminal (the cmux terminal they were looking at) with the
 * cmux CLI, where they watch it work. Runs are full auto: the most permissive mode the agent
 * offers, and every permission request it still makes is approved.
 *
 * Agents start with `bun x --bun <package>@<version>` (the versions the ACP registry lists).
 * Compiled with `bun build --compile` this executable is also the Bun that runs them
 * (`BUN_BE_BUN=1`), so the Mac needs neither Bun nor Node installed. One agent process per
 * provider stays up; each terminal keeps one session for follow-up requests.
 *
 * stdin:  {"type":"run","id":1,"provider":"claude","model":"claude-sonnet-5-5","cwd":"/abs",
 *          "terminal":"term_…","cmux":["/path/cmux","--socket","/path.sock"],"prompt":"…"}
 *         {"type":"cancel","id":1}
 * stdout: {"id":1,"type":"progress","kind":"status"|"thinking"|"step","text":"…"}
 *         {"id":1,"type":"done","summary":"…"} | {"id":1,"type":"error","message":"…"}
 */
import { spawn, type ChildProcess } from "node:child_process";
import { homedir } from "node:os";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk/unstable";

export type Provider = "claude" | "openai" | "gemini";

export type RunRequest = {
  type: "run";
  id: number;
  provider: Provider;
  model: string;
  /** The working directory of what runs in the terminal; the session starts there. */
  cwd: string;
  /** cmux's id for the terminal the user was looking at, when there is one. */
  terminal?: string | null;
  /** The cmux CLI and its session arguments, e.g. `["/Users/me/.cmux/bin/cmux", "--socket", "…"]`. */
  cmux?: string[] | null;
  /** The pane's screen, cursor line and the transcript (AiService.prompt). */
  prompt: string;
};

export type Output =
  | { id: number; type: "progress"; kind: "status" | "thinking" | "step"; text: string }
  | { id: number; type: "done"; summary: string; cancelled?: boolean }
  | { id: number; type: "error"; message: string };

/** The ACP agent for each provider, with the version the ACP registry lists. */
export const AGENTS: Record<Provider, { name: string; package: string; args: string[]; signIn: string }> = {
  claude: { name: "Claude Code", package: "@agentclientprotocol/claude-agent-acp@0.99.1", args: [], signIn: "run `claude` in a terminal on the Mac and sign in" },
  openai: { name: "Codex", package: "@agentclientprotocol/codex-acp@2.5.0", args: [], signIn: "run `codex login` on the Mac" },
  gemini: { name: "Gemini CLI", package: "@google/gemini-cli@0.66.0", args: ["--acp"], signIn: "run `gemini` on the Mac and sign in, or set GEMINI_API_KEY" },
};

/** A run that goes on this long is stopped. */
export const RUN_LIMIT_MS = 20 * 60_000;
/** A terminal's session is reused for follow-up requests until it has been idle this long. */
export const SESSION_IDLE_MS = 30 * 60_000;

/** Modes that run without asking, most permissive first (Claude Code, Codex, Gemini CLI). */
const FULL_AUTO_MODES = ["bypassPermissions", "agent-full-access", "full-access", "yolo", "auto", "acceptEdits", "agent"];
/** Thinking effort: enough to look around, short enough for someone waiting in a headset. */
const EFFORT = "medium";

/** The instructions every session gets (as an appended system prompt where the agent supports it). */
export function instructions(terminal: string | null | undefined, cmux: string[] | null | undefined): string {
  const pane = terminal && cmux?.length ? `${cmux.map(shellQuote).join(" ")} terminal ${terminal}` : null;
  const terminalPart = pane
    ? `Their terminal is a cmux terminal. Act in it with the cmux CLI, through your shell tool, where P is \`${pane}\`:
- see what it shows: P screen read
- type text (no Enter): P write --text '<text>'
- press keys: P keys enter (others: tab, escape, backspace, up, down, left, right, space, ctrl+c, ctrl+u, ctrl+d, y, q, 1, ...; several at once: P keys ctrl+u enter)
- wait for output: P screen wait --pattern '<regex>' --timeout-ms 15000
- see what runs in it and in which folder: P process show

Your own shell is separate from theirs: a cd or export in your shell changes nothing for them. What they want to happen in their terminal (go to a folder, run a command they want to watch, start a program, answer a prompt or menu, send a prompt to a coding agent running there), you do by typing into their terminal with cmux; then read the screen to check it worked, and fix it if it didn't. Use your own shell to look things up and for quick work they don't need to watch.

Before typing, look at the screen: at a shell prompt with something already typed, clear it first (ctrl+u); in a full-screen program or a coding agent, send what that program expects. Type a command that deletes files or data, force-pushes, or is otherwise hard to undo without pressing Enter, unless they clearly asked for exactly that; it then waits for them to check.`
    : "No terminal is attached to this request: do what they asked with your own tools.";
  return `You are the AI mic in vrcode. The user wears a VR headset and speaks to you instead of typing. Each message has what their terminal shows and a transcript of what they said; speech recognition mishears technical words, so correct them from the screen, their files and common developer vocabulary.

You run on their Mac, as them, with full access to their files and shell. Find things out with your tools instead of guessing: list folders, search for files and repositories (ls, find, mdfind, git), read a project's instructions and scripts. When they name a folder, project or file, find its real path before using it.

${terminalPart}

Be quick: they are waiting in the headset. End with one short plain sentence saying what you did; it shows on a small panel, so no markdown.`;
}

function shellQuote(word: string): string {
  return /^[\w@%+=:,./-]+$/.test(word) ? word : `'${word.replaceAll("'", `'\\''`)}'`;
}

/** The agent's model option for [requested] (an id from vrcode's settings), if it offers one. */
export function pickModel(requested: string, offered: string[]): string | undefined {
  if (offered.includes(requested)) return requested;
  // Claude Code names its models by family: "sonnet" for claude-sonnet-5-5.
  const tokens = requested.split("-");
  return offered.find((value) => !value.includes("-") && tokens.includes(value))
    ?? offered.find((value) => value !== "default" && (requested.startsWith(value) || value.startsWith(requested)));
}

/** The most permissive mode in [offered]. */
export function pickFullAuto(offered: string[]): string | undefined {
  return FULL_AUTO_MODES.find((mode) => offered.includes(mode));
}

type ConfigOption = { id: string; category?: string | null; currentValue?: unknown; options?: unknown[] };

/** Every value a select config option offers (flattening option groups). */
export function optionValues(option: ConfigOption): string[] {
  return (option.options ?? []).flatMap((entry: any) =>
    typeof entry?.value === "string" ? [entry.value] : Array.isArray(entry?.options) ? entry.options.map((inner: any) => inner.value).filter((v: unknown) => typeof v === "string") : []);
}

/**
 * One line for a tool call: its shell command (`$ git status`), what it does in the user's
 * terminal (`Types: cd ~/project`), or the agent's title for it (`Read package.json`).
 */
export function stepText(call: { title?: string | null; kind?: string | null; rawInput?: any }, terminal?: string | null): string {
  const raw = call.rawInput?.command ?? call.rawInput?.cmd;
  const command = typeof raw === "string" ? raw : Array.isArray(raw) ? raw.join(" ") : null;
  let line = command ? `$ ${command}` : call.title || call.kind || "Tool";
  if (command && terminal && command.includes(`terminal ${terminal} `)) line = paneAction(command.slice(command.indexOf(`terminal ${terminal} `) + `terminal ${terminal} `.length));
  const flat = line.replace(/\s+/g, " ").trim();
  return flat.length > 160 ? `${flat.slice(0, 159)}…` : flat;
}

/** A cmux terminal subcommand as what it does in the user's terminal. */
function paneAction(rest: string): string {
  const write = rest.match(/^write\s+--text[ =](['"])([\s\S]*?)\1/);
  if (write) return `Types: ${write[2]}`;
  if (rest.startsWith("write")) return `Types: ${rest.replace(/^write\s+--text[ =]?/, "")}`;
  if (rest.startsWith("keys ")) return `Presses: ${rest.slice(5).split(/[;&|]/)[0].trim()}`;
  if (rest.startsWith("screen read")) return "Reads your terminal";
  if (rest.startsWith("screen wait")) return "Waits for your terminal";
  if (rest.startsWith("process")) return "Checks what runs in your terminal";
  return `Terminal: ${rest}`;
}

/** The answer's last stretch of text (after its last tool call), as one sentence for the panel. */
export function summarize(text: string): string {
  const flat = text.replace(/[*_`#]+/g, "").replace(/\s+/g, " ").trim();
  return flat.length > 400 ? `${flat.slice(0, 399)}…` : flat;
}

type Emit = (output: Output) => void;

/** A run in progress, so a cancel can find its session. */
type Run = { id: number; thread: Thread; cancelled: boolean; timedOut: boolean; finished: Promise<void> };

/** One ACP session: one terminal's conversation with one provider's agent. */
type Thread = {
  key: string;
  host: Host;
  session: acp.ActiveSession;
  model?: string;
  lastUsed: number;
  current?: Run;
  closed: boolean;
  /** The instructions went in as a system prompt, or in an earlier turn. */
  instructed: boolean;
};

type Host = {
  provider: Provider;
  child: ChildProcess;
  connection: acp.ClientConnection;
  init: Promise<acp.InitializeResponse>;
  alive: boolean;
  /** The last lines the agent wrote to stderr, for an error when it exits. */
  stderr: string[];
};

/** The agent command for [provider]: `VRCODE_ACP_<PROVIDER>` (a JSON argv; tests use a fake agent), else `bun x --bun`. */
export function agentCommand(provider: Provider, env: Record<string, string | undefined> = process.env): string[] {
  const override = env[`VRCODE_ACP_${provider.toUpperCase()}`];
  if (override) return JSON.parse(override);
  const agent = AGENTS[provider];
  return [process.execPath, "x", "--bun", agent.package, ...agent.args];
}

export class AgentRunner {
  private hosts = new Map<Provider, Host>();
  private threads = new Map<string, Thread>();
  private runs = new Map<number, Run>();
  /** Runs cancelled before their session was ready. */
  private cancelledEarly = new Set<number>();

  constructor(private emit: Emit, private env: Record<string, string | undefined> = process.env) {}

  handle(message: { type: string; id: number } & Record<string, unknown>) {
    if (message.type === "run") void this.run(message as unknown as RunRequest);
    else if (message.type === "cancel") void this.cancel(message.id);
  }

  async run(request: RunRequest) {
    const { id } = request;
    const agent = AGENTS[request.provider] ?? AGENTS.claude;
    const progress = (kind: "status" | "thinking" | "step", text: string) => this.emit({ id, type: "progress", kind, text });
    let run: Run | undefined;
    try {
      const fresh = !this.hosts.get(request.provider)?.alive;
      if (fresh) progress("status", `Starting ${agent.name}…`);
      const thread = await this.thread(request);
      // A new request to a terminal whose agent is still busy replaces that run.
      if (thread.current) {
        const previous = thread.current;
        await this.cancel(previous.id);
        await previous.finished;
      }
      if (this.cancelledEarly.delete(id)) return this.emit({ id, type: "done", summary: "", cancelled: true });
      let finish!: () => void;
      run = { id, thread, cancelled: false, timedOut: false, finished: new Promise<void>((resolve) => (finish = resolve)) };
      thread.current = run;
      this.runs.set(id, run);
      progress("status", `${agent.name} in ${abbreviate(request.cwd)}`);
      try {
        await this.turn(run, request, progress);
      } finally {
        thread.lastUsed = Date.now();
        if (thread.current === run) thread.current = undefined;
        this.runs.delete(id);
        finish();
      }
    } catch (error) {
      this.cancelledEarly.delete(id);
      this.emit({ id, type: "error", message: this.describe(error, request.provider) });
    }
  }

  async cancel(id: number) {
    const run = this.runs.get(id);
    if (!run) { this.cancelledEarly.add(id); return; }
    if (run.cancelled) return;
    run.cancelled = true;
    const { host, session } = run.thread;
    await host.connection.agent.notify(acp.methods.agent.session.cancel, { sessionId: session.sessionId }).catch(() => {});
  }

  /** Stops every agent (stdin closed: VRcode Mac quit). */
  close() {
    for (const host of this.hosts.values()) host.child.kill();
  }

  private async turn(run: Run, request: RunRequest, progress: (kind: "status" | "thinking" | "step", text: string) => void) {
    const { session } = run.thread;
    const limit = setTimeout(() => { run.timedOut = true; void this.cancel(run.id); }, RUN_LIMIT_MS);
    const calls = new Map<string, { title?: string | null; kind?: string | null; rawInput?: any; shown: boolean }>();
    let answer = "";
    const reply = session.prompt(this.message(run.thread, request));
    // The prompt request fails (the agent died, an auth error) without a stop message: end the turn.
    const failed = reply.then(() => new Promise<never>(() => {}));
    try {
      for (;;) {
        const message = await Promise.race([session.nextUpdate(), failed]);
        if (message.kind === "stop") {
          if (run.timedOut) throw new Error(`took longer than ${RUN_LIMIT_MS / 60_000} minutes and was stopped`);
          if (run.cancelled || message.stopReason === "cancelled") return this.emit({ id: run.id, type: "done", summary: "", cancelled: true });
          if (message.stopReason === "refusal") throw new Error("declined this request");
          if (message.stopReason === "max_tokens") throw new Error("ran out of output tokens");
          if (message.stopReason === "max_turn_requests") throw new Error("used up its turns without finishing");
          return this.emit({ id: run.id, type: "done", summary: summarize(answer) || "Done" });
        }
        const update: any = message.update;
        switch (update.sessionUpdate) {
          case "agent_thought_chunk":
          case "agent_message_chunk": {
            const text = update.content?.type === "text" ? update.content.text : "";
            if (!text) break;
            if (update.sessionUpdate === "agent_message_chunk") answer += text;
            if (!run.cancelled) progress("thinking", text);
            break;
          }
          case "tool_call":
          case "tool_call_update": {
            if (update.sessionUpdate === "tool_call") answer = "";
            const call = calls.get(update.toolCallId) ?? { shown: false };
            if (update.title) call.title = update.title;
            if (update.kind) call.kind = update.kind;
            if (update.rawInput && Object.keys(update.rawInput).length) call.rawInput = update.rawInput;
            calls.set(update.toolCallId, call);
            // Claude Code first reports a generic "Terminal" call and fills in the command next.
            const settled = call.rawInput || (update.status && update.status !== "pending");
            if (!call.shown && settled) {
              call.shown = true;
              if (!run.cancelled) progress("step", stepText(call, request.terminal));
            }
            break;
          }
        }
      }
    } finally {
      clearTimeout(limit);
    }
  }

  /** What the agent gets for this request: the instructions on a session's first turn when it can't take a system prompt. */
  private message(thread: Thread, request: RunRequest): string {
    const parts = [] as string[];
    if (!thread.instructed) parts.push(`<instructions>\n${instructions(request.terminal, request.cmux)}\n</instructions>`);
    thread.instructed = true;
    parts.push(`<working_directory>${request.cwd}</working_directory>`);
    if (request.terminal) parts.push(`<terminal id="${request.terminal}"/>`);
    parts.push(request.prompt);
    return parts.join("\n\n");
  }

  private async host(provider: Provider): Promise<Host> {
    const existing = this.hosts.get(provider);
    if (existing?.alive) return existing;
    const [command, ...args] = agentCommand(provider, this.env);
    const env: Record<string, string> = { BUN_BE_BUN: "1" };
    for (const [key, value] of Object.entries(this.env)) if (value !== undefined && key !== "BUN_BE_BUN") env[key] = value;
    // Started from inside a Claude Code session (during development), Claude Code would see itself nested.
    for (const key of ["CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT"]) delete env[key];
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"], cwd: homedir(), env });
    const stream = acp.ndJsonStream(Writable.toWeb(child.stdin!), Readable.toWeb(child.stdout!) as ReadableStream<Uint8Array>);
    const connection = acp
      .client({ name: "vrcode" })
      .onRequest(acp.methods.client.session.requestPermission, (ctx) => approve(ctx.params))
      .connect(stream);
    const host: Host = { provider, child, connection, alive: true, stderr: [], init: Promise.resolve(undefined as never) };
    child.stderr!.on("data", (data: Buffer) => {
      host.stderr.push(...data.toString().split("\n").filter((line) => line.trim()));
      host.stderr.splice(0, Math.max(0, host.stderr.length - 20));
    });
    const exited = new Promise<never>((_, reject) => {
      const fail = (error?: Error) => {
        if (!host.alive) return;
        host.alive = false;
        connection.close();
        for (const [key, thread] of this.threads) if (thread.host === host) { thread.closed = true; this.threads.delete(key); }
        reject(error ?? new Error(`exited${host.stderr.length ? `: ${host.stderr.at(-1)}` : ""}`));
      };
      child.on("error", fail);
      child.on("exit", () => fail());
    });
    exited.catch(() => {});
    host.init = Promise.race([
      connection.agent.request(acp.methods.agent.initialize, {
        protocolVersion: acp.PROTOCOL_VERSION,
        clientCapabilities: {},
        clientInfo: { name: "vrcode", title: "vrcode", version: "1" },
      }),
      exited,
    ]);
    this.hosts.set(provider, host);
    await host.init;
    return host;
  }

  /** The terminal's session with [request]'s provider: the one still in use, else a new one. */
  private async thread(request: RunRequest): Promise<Thread> {
    const key = `${request.provider}|${request.terminal ?? request.cwd}`;
    const existing = this.threads.get(key);
    if (existing && !existing.closed && existing.host.alive && Date.now() - existing.lastUsed < SESSION_IDLE_MS) {
      if (existing.model !== request.model) await this.configure(existing, request);
      return existing;
    }
    if (existing) this.retire(existing);
    const host = await this.host(request.provider);
    const init = await host.init;
    const systemPrompt = init.agentCapabilities?.sessionCapabilities?.systemPrompt
      ? { mode: "append", content: [{ type: "text", text: instructions(request.terminal, request.cmux) }] }
      : undefined;
    const session = await host.connection.agent.buildSession({ cwd: request.cwd, mcpServers: [], ...(systemPrompt ? { systemPrompt } : {}) }).start();
    const thread: Thread = { key, host, session, lastUsed: Date.now(), closed: false, instructed: !!systemPrompt };
    await this.configure(thread, request, true);
    this.threads.set(key, thread);
    return thread;
  }

  /** Full auto, [request]'s model and medium effort, as far as the agent offers them. */
  private async configure(thread: Thread, request: RunRequest, fresh = false) {
    const { connection } = thread.host;
    const sessionId = thread.session.sessionId;
    const response: any = thread.session.newSessionResponse;
    const options: ConfigOption[] = response.configOptions ?? [];
    const set = (configId: string, value: string) =>
      connection.agent.request(acp.methods.agent.session.setConfigOption, { sessionId, configId, value } as any).catch(() => {});
    if (fresh) {
      const modeOption = options.find((option) => option.category === "mode");
      const modes = modeOption ? optionValues(modeOption) : (response.modes?.availableModes ?? []).map((mode: any) => mode.id);
      const mode = pickFullAuto(modes);
      if (mode && modeOption) await set(modeOption.id, mode);
      else if (mode) await connection.agent.request(acp.methods.agent.session.setMode, { sessionId, modeId: mode }).catch(() => {});
      const effort = options.find((option) => option.category === "thought_level");
      if (effort && optionValues(effort).includes(EFFORT)) await set(effort.id, EFFORT);
    }
    const modelOption = options.find((option) => option.category === "model");
    const model = modelOption && pickModel(request.model, optionValues(modelOption));
    if (modelOption && model) await set(modelOption.id, model);
    thread.model = request.model;
  }

  private retire(thread: Thread) {
    thread.closed = true;
    this.threads.delete(thread.key);
    thread.session.dispose?.();
    void thread.host.connection.agent.request(acp.methods.agent.session.close, { sessionId: thread.session.sessionId }).catch(() => {});
  }

  /** An error as one line for the headset, naming the agent and, for a sign-in problem, how to fix it. */
  private describe(error: unknown, provider: Provider): string {
    const agent = AGENTS[provider] ?? AGENTS.claude;
    const message = (error as any)?.message ?? String(error);
    const code = (error as any)?.code;
    // -32000 is ACP's "authentication required".
    if (code === -32000 || /not (?:signed|logged) in|authenticat|log ?in required|sign ?in required/i.test(message)) {
      return `${agent.name} needs signing in on the Mac: ${message.trim()} (${agent.signIn})`;
    }
    return `${agent.name}: ${message.trim()}`;
  }
}

/** Full auto: allow, and keep allowing, whatever the agent asks for. */
function approve(params: acp.RequestPermissionRequest): acp.RequestPermissionResponse {
  const options = params.options;
  const pick = options.find((option) => option.kind === "allow_always") ?? options.find((option) => option.kind === "allow_once") ?? options[0];
  return pick ? { outcome: { outcome: "selected", optionId: pick.optionId } } : { outcome: { outcome: "cancelled" } };
}

function abbreviate(path: string): string {
  const home = homedir();
  return path === home ? "~" : path.startsWith(`${home}/`) ? `~${path.slice(home.length)}` : path;
}

if (import.meta.main) {
  const runner = new AgentRunner((output) => process.stdout.write(`${JSON.stringify(output)}\n`));
  let buffer = "";
  process.stdin.setEncoding("utf8");
  process.stdin.on("data", (chunk: string) => {
    buffer += chunk;
    for (let newline = buffer.indexOf("\n"); newline >= 0; newline = buffer.indexOf("\n")) {
      const line = buffer.slice(0, newline).trim();
      buffer = buffer.slice(newline + 1);
      if (!line) continue;
      try { runner.handle(JSON.parse(line)); } catch (error) { process.stderr.write(`vrcode-agent: bad request: ${error}\n`); }
    }
  });
  process.stdin.on("end", () => { runner.close(); process.exit(0); });
}
