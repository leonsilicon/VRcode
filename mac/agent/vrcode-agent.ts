#!/usr/bin/env bun
/**
 * vrcode-agent: the AI mic's agent runner. VRcode Mac's Swift service (AiAgent.swift) starts it
 * once and talks JSON lines over stdin and stdout; it drives the provider's own coding agent over
 * the Agent Client Protocol (https://agentclientprotocol.com) with the official SDK:
 * Claude Code through `@agentclientprotocol/claude-agent-acp`, Codex through
 * `@agentclientprotocol/codex-acp`, Gemini CLI through its own `--acp` mode. Each agent signs in
 * with its own login and keeps its own tools, so it can look through the user's files and run
 * commands, and it acts in the user's terminal (the cmux terminal they were looking at) with the
 * cmux CLI, where they watch it work. Runs are full auto: the most permissive mode the agent
 * offers, and every permission request it still makes is approved.
 *
 * Agents start with `bun x --bun <package>@<version>` (the versions the ACP registry lists), with
 * the Bun running this file: VRcode Mac ships Bun as its `vrcode-bun` sidecar and this file,
 * bundled, in its Resources, so the Mac needs neither Bun nor Node installed. Each terminal gets its own
 * agent process, started with `CMUX_TUI_SOCKET` and `CMUX_TUI_TERMINAL_ID` set the way cmux sets
 * them inside its own terminals, so `cmux terminal current …` from the agent's shell acts in the
 * user's terminal. The agent and its one session stay up for follow-up requests until idle.
 *
 * stdin:  {"type":"run","id":1,"provider":"claude","model":"claude-sonnet-5-5","cwd":"/abs",
 *          "terminal":"term_…","cmux":{"binary":"/path/cmux","socket":"/path.sock"},"prompt":"…"}
 *         {"type":"cancel","id":1}
 * stdout: {"id":1,"type":"progress","kind":"status"|"thinking"|"step","text":"…"}
 *         {"id":1,"type":"done","summary":"…"} | {"id":1,"type":"error","message":"…"}
 */
import { spawn, type ChildProcess } from "node:child_process";
import { homedir } from "node:os";
import { dirname } from "node:path";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk/unstable";

export type Provider = "claude" | "openai" | "gemini";

export type RunRequest = {
  type: "run";
  id: number;
  provider: Provider;
  model: string;
  /** The working directory of what runs in the terminal; a new session starts there. */
  cwd: string;
  /** cmux's id for the terminal the user was looking at, when there is one. */
  terminal?: string | null;
  /** The cmux CLI and the socket of the session the terminal is in. */
  cmux?: { binary: string; socket: string } | null;
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
/** A terminal's agent keeps its session for follow-up requests until it has been idle this long. */
export const IDLE_MS = 30 * 60_000;

/** Modes that run without asking, most permissive first (Claude Code, Codex, Gemini CLI). */
const FULL_AUTO_MODES = ["bypassPermissions", "agent-full-access", "full-access", "yolo", "auto", "acceptEdits", "agent"];
/** Thinking effort: enough to look around, short enough for someone waiting in a headset. */
const EFFORT = "medium";

/** The instructions each session gets (an appended system prompt where the agent supports one). */
export function instructions(terminal: boolean): string {
  const terminalPart = terminal
    ? `Their terminal is a cmux terminal, and your shell is set up to act in it: \`cmux terminal current …\` targets it.
- see what it shows: cmux terminal current screen read
- type text, without Enter: cmux terminal current write --text '<text>'
- press keys: cmux terminal current keys enter (others: tab, escape, backspace, up, down, left, right, space, ctrl+c, ctrl+u, ctrl+d, y, q, 1, ...; several in order: cmux terminal current keys ctrl+u enter)
- wait for output: cmux terminal current screen wait --pattern '<regex>' --timeout-ms 15000
- see what runs in it and in which folder: cmux terminal current process show
These work as written (keep the word current), so there is no need to check cmux's help or list its terminals; screen read prints the visible screen in its text field, lines separated by \\n.
Chain them with && when you know what comes next, for example: cmux terminal current keys ctrl+u && cmux terminal current write --text 'git status' && cmux terminal current keys enter && sleep 1 && cmux terminal current screen read

Your own shell is separate from theirs: a cd or export in your shell changes nothing for them. What they want to happen in their terminal (go to a folder, run a command they want to watch, start a program, answer a prompt or menu, send a prompt to a coding agent running there), you do by typing into their terminal; then read the screen to check it worked, and fix it if it didn't. Use your own shell to look things up and for quick work they don't need to watch.

Before typing, look at the screen: at a shell prompt with something already typed, clear it first (ctrl+u); in a full-screen program or a coding agent, send what that program expects. Type a command that deletes files or data, force-pushes, or is otherwise hard to undo without pressing Enter, unless they clearly asked for exactly that; it then waits for them to check.`
    : "No terminal is attached to this request: do what they asked with your own tools.";
  return `You are the AI mic in vrcode. The user wears a VR headset and speaks to you instead of typing. Each message has what their terminal shows and a transcript of what they said; speech recognition mishears technical words, so correct them from the screen, their files and common developer vocabulary.

You run on their Mac, as them, with full access to their files and shell. Find things out with your tools instead of guessing: list folders, search for files and repositories (ls, find, mdfind, git), read a project's instructions and scripts. When they name a folder, project or file, find its real path before using it.

${terminalPart}

Be quick: they are waiting in the headset. End with one short plain sentence saying what you did; it shows on a small panel, so no markdown.`;
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

type ConfigOption = { id: string; category?: string | null; options?: unknown[] };

/** Every value a select config option offers (flattening option groups). */
export function optionValues(option: ConfigOption): string[] {
  return (option.options ?? []).flatMap((entry: any) =>
    typeof entry?.value === "string" ? [entry.value]
      : Array.isArray(entry?.options) ? entry.options.map((inner: any) => inner?.value).filter((value: unknown) => typeof value === "string")
      : []);
}

/**
 * One line for a tool call: its shell command (`$ git status`), what it does in the user's
 * terminal (`Types: cd ~/project · Presses: enter`), or the agent's title for it (`Read package.json`).
 */
export function stepText(call: { title?: string | null; kind?: string | null; rawInput?: any }): string {
  const raw = call.rawInput?.command ?? call.rawInput?.cmd;
  const command = typeof raw === "string" ? raw : Array.isArray(raw) ? raw.join(" ") : null;
  let line = command ? `$ ${command}` : call.title || call.kind || "Tool";
  if (command && PANE.test(command)) {
    line = shellParts(command)
      .filter((part) => part && !/^sleep\b/.test(part) && !/^\w+=\S*$/.test(part))
      .map((part) => {
        const match = part.match(PANE);
        return match ? paneAction(part.slice((match.index ?? 0) + match[0].length)) : `$ ${part}`;
      })
      .join(" · ");
  }
  const flat = line.replace(/\s+/g, " ").trim();
  return flat.length > 160 ? `${flat.slice(0, 159)}…` : flat;
}

/** [command]'s parts between `&&`, `||`, `;` and line breaks, leaving quoted text whole. */
function shellParts(command: string): string[] {
  const parts: string[] = [];
  let current = "", quote = "";
  for (let i = 0; i < command.length; i++) {
    const char = command[i];
    if (quote) {
      if (char === "\\" && quote === '"') { current += char + (command[++i] ?? ""); continue; }
      if (char === quote) quote = "";
      current += char;
    } else if (char === "'" || char === '"') {
      quote = char;
      current += char;
    } else if (char === ";" || char === "\n" || ((char === "&" || char === "|") && command[i + 1] === char)) {
      if (char !== ";" && char !== "\n") i++;
      parts.push(current.trim());
      current = "";
    } else {
      current += char;
    }
  }
  parts.push(current.trim());
  return parts.filter(Boolean);
}

/** `cmux [global options] terminal [current|term_…]`, the start of a command acting in the user's terminal. */
const PANE = /(?:^|[\s/])cmux(?:\s+--?[\w-]+(?:[ =](?!terminal\b)\S+)?)*\s+terminal\s+(?:(?:current|term_\w+|"?\$\{?\w+\}?"?)\s+)?/;

/** A cmux terminal subcommand as what it does in the user's terminal. */
function paneAction(rest: string): string {
  const write = rest.match(/^write\s+--text[ =](['"])([\s\S]*?)\1/);
  if (write) return `Types: ${write[2]}`;
  if (rest.startsWith("write")) return `Types: ${rest.replace(/^write\s+(?:--text[ =]?)?/, "")}`;
  if (rest.startsWith("keys ")) return `Presses: ${rest.slice(5).trim()}`;
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

/** The agent command for [provider]: `VRCODE_ACP_<PROVIDER>` (a JSON argv; tests use a fake agent), else `bun x --bun`. */
export function agentCommand(provider: Provider, env: Record<string, string | undefined> = process.env): string[] {
  const override = env[`VRCODE_ACP_${provider.toUpperCase()}`];
  if (override) return JSON.parse(override);
  const agent = AGENTS[provider];
  return [process.execPath, "x", "--bun", agent.package, ...agent.args];
}

type Emit = (output: Output) => void;
type Progress = (kind: "status" | "thinking" | "step", text: string) => void;

/** A run in progress, so a cancel can find its agent. */
type Run = { id: number; agent: Agent; cancelled: boolean; timedOut: boolean; finished: Promise<void> };

/** One agent process and its one session: one terminal's conversation with one provider. */
type Agent = {
  key: string;
  provider: Provider;
  child: ChildProcess;
  connection: acp.ClientConnection;
  alive: boolean;
  session?: acp.ActiveSession;
  model?: string;
  /** The instructions went in as a system prompt, or in an earlier turn. */
  instructed: boolean;
  lastUsed: number;
  current?: Run;
  /** The last lines the agent wrote to stderr, for an error when it exits. */
  stderr: string[];
  /** Rejects when the process exits. */
  exited: Promise<never>;
};

export class AgentRunner {
  private agents = new Map<string, Agent>();
  private runs = new Map<number, Run>();
  /** Runs cancelled before their agent was ready. */
  private cancelledEarly = new Set<number>();
  private sweeper = setInterval(() => this.sweep(), 60_000);

  constructor(private emit: Emit, private env: Record<string, string | undefined> = process.env) {
    this.sweeper.unref?.();
  }

  handle(message: { type: string; id: number } & Record<string, unknown>) {
    if (message.type === "run") void this.run(message as unknown as RunRequest);
    else if (message.type === "cancel") void this.cancel(message.id);
  }

  async run(request: RunRequest) {
    const { id } = request;
    const provider = AGENTS[request.provider] ? request.provider : "claude";
    const name = AGENTS[provider].name;
    const progress: Progress = (kind, text) => this.emit({ id, type: "progress", kind, text });
    let agent: Agent | undefined;
    try {
      agent = await this.agent({ ...request, provider }, progress);
      // A new request to a terminal whose agent is still busy replaces that run.
      if (agent.current) {
        const previous = agent.current;
        await this.cancel(previous.id);
        await previous.finished;
      }
      if (this.cancelledEarly.delete(id)) return this.emit({ id, type: "done", summary: "", cancelled: true });
      let finish!: () => void;
      const run: Run = { id, agent, cancelled: false, timedOut: false, finished: new Promise<void>((resolve) => (finish = resolve)) };
      agent.current = run;
      this.runs.set(id, run);
      progress("status", `${name} in ${abbreviate(request.cwd)}`);
      try {
        await this.turn(run, request, progress);
      } finally {
        agent.lastUsed = Date.now();
        if (agent.current === run) agent.current = undefined;
        this.runs.delete(id);
        finish();
      }
    } catch (error) {
      this.cancelledEarly.delete(id);
      // When the agent exited, its connection closes just before the process says why: wait for that.
      const gone = agent && (!agent.alive || agent.connection.signal.aborted);
      const reason = gone
        ? await Promise.race([agent!.exited.catch((exit: unknown) => exit), new Promise((resolve) => setTimeout(() => resolve(error), 1000))])
        : error;
      this.emit({ id, type: "error", message: describe(reason, provider) });
    }
  }

  async cancel(id: number) {
    const run = this.runs.get(id);
    if (!run) { this.cancelledEarly.add(id); return; }
    if (run.cancelled) return;
    run.cancelled = true;
    const { connection, session } = run.agent;
    if (session) await connection.agent.notify(acp.methods.agent.session.cancel, { sessionId: session.sessionId }).catch(() => {});
  }

  /** Stops every agent (stdin closed: VRcode Mac quit or restarted the runner). */
  close() {
    clearInterval(this.sweeper);
    for (const agent of this.agents.values()) agent.child.kill();
  }

  private async turn(run: Run, request: RunRequest, progress: Progress) {
    const session = run.agent.session!;
    const limit = setTimeout(() => { run.timedOut = true; void this.cancel(run.id); }, RUN_LIMIT_MS);
    const calls = new Map<string, { title?: string | null; kind?: string | null; rawInput?: any; shown: boolean }>();
    let answer = "";
    const reply = session.prompt(this.message(run.agent, request));
    // A prompt that fails (an auth error, the agent exited) never queues a stop message.
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
            // Claude Code first reports a generic "Terminal" call, then fills in the command.
            if (!call.shown && (call.rawInput || (update.status && update.status !== "pending"))) {
              call.shown = true;
              if (!run.cancelled) progress("step", stepText(call));
            }
            break;
          }
        }
      }
    } finally {
      clearTimeout(limit);
    }
  }

  /** What the agent gets for this request; the instructions go first when they could not go in as a system prompt. */
  private message(agent: Agent, request: RunRequest): string {
    const parts: string[] = [];
    if (!agent.instructed) parts.push(`<instructions>\n${instructions(!!request.terminal && !!request.cmux)}\n</instructions>`);
    agent.instructed = true;
    parts.push(`<working_directory>${request.cwd}</working_directory>`, request.prompt);
    return parts.join("\n\n");
  }

  /** The terminal's agent with a session ready for [request]: the one still in use, else a new one. */
  private async agent(request: RunRequest, progress: Progress): Promise<Agent> {
    const key = `${request.provider}|${request.terminal ?? ""}`;
    const existing = this.agents.get(key);
    if (existing?.alive && existing.session && Date.now() - existing.lastUsed < IDLE_MS) {
      if (existing.model !== request.model) await this.configure(existing, request, false);
      return existing;
    }
    if (existing) this.stop(existing);
    progress("status", `Starting ${AGENTS[request.provider].name}…`);
    const agent = this.start(key, request);
    try {
      const init = await Promise.race([
        agent.connection.agent.request(acp.methods.agent.initialize, {
          protocolVersion: acp.PROTOCOL_VERSION,
          clientCapabilities: {},
          clientInfo: { name: "vrcode", title: "vrcode", version: "1" },
        }),
        agent.exited,
      ]);
      const terminal = !!request.terminal && !!request.cmux;
      const systemPrompt = init.agentCapabilities?.sessionCapabilities?.systemPrompt
        ? { mode: "append", content: [{ type: "text" as const, text: instructions(terminal) }] }
        : undefined;
      agent.session = await Promise.race([
        agent.connection.agent.buildSession({ cwd: request.cwd, mcpServers: [], ...(systemPrompt ? { systemPrompt } : {}) }).start(),
        agent.exited,
      ]);
      agent.instructed = !!systemPrompt;
      await this.configure(agent, request, true);
      return agent;
    } catch (error) {
      this.stop(agent);
      throw error;
    }
  }

  private start(key: string, request: RunRequest): Agent {
    const [command, ...args] = agentCommand(request.provider, this.env);
    const env: Record<string, string> = {};
    for (const [name, value] of Object.entries(this.env)) if (value !== undefined) env[name] = value;
    // Started from inside a Claude Code session (during development), Claude Code would see itself nested.
    for (const name of ["CLAUDECODE", "CLAUDE_CODE_ENTRYPOINT", "CLAUDE_CODE_SSE_PORT"]) delete env[name];
    if (request.terminal && request.cmux) {
      // As inside the terminal itself: `cmux terminal …` acts in it.
      env.CMUX_TUI_SOCKET = request.cmux.socket;
      env.CMUX_TUI_TERMINAL_ID = request.terminal;
      env.PATH = [dirname(request.cmux.binary), env.PATH].filter(Boolean).join(":");
    }
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"], cwd: homedir(), env });
    const stream = acp.ndJsonStream(Writable.toWeb(child.stdin!), Readable.toWeb(child.stdout!) as unknown as ReadableStream<Uint8Array>);
    const connection = acp
      .client({ name: "vrcode" })
      .onRequest(acp.methods.client.session.requestPermission, (ctx) => approve(ctx.params))
      .connect(stream);
    const agent = { key, provider: request.provider, child, connection, alive: true, instructed: false, lastUsed: Date.now(), stderr: [] as string[] } as Agent;
    child.stderr!.on("data", (data: Buffer) => {
      agent.stderr.push(...data.toString().split("\n").filter((line) => line.trim()));
      agent.stderr.splice(0, Math.max(0, agent.stderr.length - 20));
    });
    agent.exited = new Promise<never>((_, reject) => {
      const exit = (error: Error) => {
        if (!agent.alive) return;
        agent.alive = false;
        connection.close();
        if (this.agents.get(key) === agent) this.agents.delete(key);
        reject(error);
      };
      child.on("error", exit);
      child.on("exit", (code, signal) => exit(new Error(`exited (${signal ?? `status ${code}`})${agent.stderr.length ? `: ${agent.stderr.at(-1)}` : ""}`)));
    });
    agent.exited.catch(() => {});
    this.agents.set(key, agent);
    return agent;
  }

  /** Full auto, [request]'s model and medium effort, as far as the agent offers them. */
  private async configure(agent: Agent, request: RunRequest, fresh: boolean) {
    const { connection } = agent;
    const session = agent.session!;
    const sessionId = session.sessionId;
    const response: any = session.newSessionResponse;
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
    agent.model = request.model;
  }

  private stop(agent: Agent) {
    if (this.agents.get(agent.key) === agent) this.agents.delete(agent.key);
    agent.session?.dispose();
    agent.child.kill();
  }

  /** Stops agents that have sat idle past [IDLE_MS]. */
  private sweep() {
    for (const agent of [...this.agents.values()]) if (!agent.current && Date.now() - agent.lastUsed > IDLE_MS) this.stop(agent);
  }
}

/** Full auto: allow, and keep allowing, whatever the agent asks for. */
function approve(params: acp.RequestPermissionRequest): acp.RequestPermissionResponse {
  const options = params.options;
  const pick = options.find((option) => option.kind === "allow_always") ?? options.find((option) => option.kind === "allow_once") ?? options[0];
  return pick ? { outcome: { outcome: "selected", optionId: pick.optionId } } : { outcome: { outcome: "cancelled" } };
}

/** An error as one line for the headset, naming the agent and, for a sign-in problem, how to fix it. */
export function describe(error: unknown, provider: Provider): string {
  const agent = AGENTS[provider] ?? AGENTS.claude;
  const message = String((error as any)?.message ?? error).trim();
  // -32000 is ACP's "authentication required".
  if ((error as any)?.code === -32000 || /not (?:signed|logged) in|authenticat|log ?in required|sign ?in required/i.test(message)) {
    return `${agent.name} needs signing in on the Mac: ${message} (${agent.signIn})`;
  }
  return `${agent.name}: ${message}`;
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
