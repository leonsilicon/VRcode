/**
 * The AI mic's agent runner (mac/agent/vrcode-agent.ts): its helpers, and whole runs over the
 * Agent Client Protocol against a fake agent built with the same SDK (scripts/lib/fake-acp-agent.ts),
 * so no model is called.
 */
import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { mkdtemp, rm } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import {
  AgentRunner, describe as describeError, instructions, pickFullAuto, pickModel, stepText, summarize, type Output, type RunRequest,
} from "../../mac/agent/vrcode-agent";

describe("helpers", () => {
  test("pickModel maps vrcode's model ids onto what the agent offers", () => {
    const claude = ["default", "opus", "fable", "sonnet", "haiku", "claude-sonnet-5", "claude-opus-4-8"];
    expect(pickModel("claude-sonnet-5-5", claude)).toBe("sonnet");
    expect(pickModel("claude-fable-5-1", claude)).toBe("fable");
    expect(pickModel("gpt-6.1-sol", ["gpt-6.1-sol", "gpt-6-astra"])).toBe("gpt-6.1-sol");
    expect(pickModel("gemini-4-argon", ["auto"])).toBeUndefined();
  });

  test("pickFullAuto takes the most permissive mode offered", () => {
    expect(pickFullAuto(["default", "acceptEdits", "plan", "auto", "bypassPermissions"])).toBe("bypassPermissions");
    expect(pickFullAuto(["read-only", "workspace-write", "agent", "agent-full-access"])).toBe("agent-full-access");
    expect(pickFullAuto(["default", "autoEdit", "yolo"])).toBe("yolo");
    expect(pickFullAuto(["plan"])).toBeUndefined();
  });

  test("stepText shows commands, and cmux calls as what they do in the user's terminal", () => {
    expect(stepText({ title: "Terminal", rawInput: { command: "git status\n--short" } })).toBe("$ git status --short");
    expect(stepText({ title: "Read package.json", kind: "read" })).toBe("Read package.json");
    expect(stepText({ rawInput: { command: "cmux terminal keys ctrl+u && cmux terminal write --text 'cd ~/x' && cmux terminal keys enter && sleep 1 && cmux terminal screen read" } }))
      .toBe("Presses: ctrl+u · Types: cd ~/x · Presses: enter · Reads your terminal");
    expect(stepText({ rawInput: { command: `T=term_1; /Users/me/.cmux/bin/cmux --session main terminal $T write --text "npm test" && cmux terminal "$T" screen wait --pattern done` } }))
      .toBe("Types: npm test · Waits for your terminal");
    // A command typed into the terminal stays whole, && and all.
    expect(stepText({ rawInput: { command: "cmux terminal current write --text 'cd ~/vrcode && ls mac; pwd' && cmux terminal current keys enter" } }))
      .toBe("Types: cd ~/vrcode && ls mac; pwd · Presses: enter");
    expect(stepText({ rawInput: { command: "x".repeat(300) } })).toHaveLength(160);
  });

  test("summarize flattens the final answer for the panel", () => {
    expect(summarize("  Moved your terminal to **~/vrcode**.\n\nDone. ")).toBe("Moved your terminal to ~/vrcode. Done.");
  });

  test("errors name the agent and say how to sign in when that is the problem", () => {
    expect(describeError(Object.assign(new Error("Authentication required"), { code: -32000 }), "openai"))
      .toBe("Codex needs signing in on the Mac: Authentication required (run `codex login` on the Mac)");
    expect(describeError(new Error("exited: boom"), "claude")).toBe("Claude Code: exited: boom");
  });

  test("instructions tell the agent to find things out and to act in the terminal through cmux", () => {
    const text = instructions(true);
    expect(text).toContain("instead of guessing");
    for (const command of ["cmux terminal current screen read", "cmux terminal current write --text", "cmux terminal current keys enter"]) expect(text).toContain(command);
    expect(instructions(false)).not.toContain("cmux");
  });
});

describe("runs against an ACP agent", () => {
  let directory: string, record: string, runner: AgentRunner;
  const outputs: Output[] = [];
  let nextId = 100;

  beforeAll(async () => {
    directory = await mkdtemp(join(tmpdir(), "vrcode-agent-"));
    record = join(directory, "record.jsonl");
    const fake = join(import.meta.dir, "../lib/fake-acp-agent.ts");
    runner = new AgentRunner((output) => outputs.push(output), {
      ...process.env, FAKE_ACP_RECORD: record, VRCODE_ACP_CLAUDE: JSON.stringify([process.execPath, fake]),
    });
  });

  afterAll(async () => {
    runner?.close();
    if (directory) await rm(directory, { recursive: true, force: true });
  });

  const records = async () => (await Bun.file(record).text()).trim().split("\n").map((line) => JSON.parse(line));
  const request = (prompt: string, terminal: string | null = "term_test"): RunRequest => ({
    type: "run", id: ++nextId, provider: "claude", model: "claude-sonnet-5-5", cwd: directory, terminal,
    cmux: { binary: "/opt/cmux/bin/cmux", socket: "/tmp/cmux-test.sock" }, prompt: `<transcript>${prompt}</transcript>`,
  });
  /** Runs [req] to its end and returns its outputs. */
  const finish = async (req: RunRequest, during?: () => Promise<void>) => {
    const running = runner.run(req);
    if (during) await during();
    await running;
    return outputs.filter((output) => output.id === req.id);
  };

  test("a run starts the agent full auto and streams thinking and steps, then the summary", async () => {
    const out = await finish(request("go to the vrcode repo"));
    const progress = out.filter((o) => o.type === "progress") as Extract<Output, { type: "progress" }>[];
    expect(progress.filter((p) => p.kind === "status").map((p) => p.text)).toEqual(["Starting Claude Code…", `Claude Code in ${directory}`]);
    expect(progress.filter((p) => p.kind === "thinking").map((p) => p.text).join("")).toBe("Finding the repo. Then cd there.Looking around.Moved your terminal to **~/Developer/vrcode**.");
    // The generic "Terminal" call shows once its command is known.
    expect(progress.filter((p) => p.kind === "step").map((p) => p.text))
      .toEqual(["$ ls ~/Developer", "Presses: ctrl+u · Types: cd ~/Developer/vrcode · Presses: enter · Reads your terminal"]);
    expect(out.at(-1)).toEqual({ id: out[0].id, type: "done", summary: "Moved your terminal to ~/Developer/vrcode." });

    const log = await records();
    const created = log.find((entry) => entry.method === "session/new");
    expect(created.cwd).toBe(directory);
    expect(created.systemPrompt).toContain("cmux terminal current write --text");
    // The agent's shell acts in the user's terminal: cmux's own variables for it, and cmux on PATH.
    expect(created.env).toEqual({ CMUX_TUI_SOCKET: "/tmp/cmux-test.sock", CMUX_TUI_TERMINAL_ID: "term_test" });
    const config = Object.fromEntries(log.filter((entry) => entry.method === "session/set_config_option").map((entry) => [entry.configId, entry.value]));
    expect(config).toEqual({ mode: "bypassPermissions", effort: "medium", model: "sonnet" });
    expect(log.find((entry) => entry.method === "permission").outcome).toEqual({ outcome: "selected", optionId: "always" });
    const prompt = log.find((entry) => entry.method === "session/prompt").text;
    expect(prompt).toContain(`<working_directory>${directory}</working_directory>`);
    expect(prompt).toContain("<transcript>go to the vrcode repo</transcript>");
    expect(prompt).not.toContain("<instructions>"); // they went in as the system prompt
  });

  test("a follow-up to the same terminal continues its session", async () => {
    const out = await finish(request("now show the git status"));
    expect(out.filter((o) => o.type === "progress" && o.kind === "status").map((o: any) => o.text)).toEqual([`Claude Code in ${directory}`]);
    const log = await records();
    expect(log.filter((entry) => entry.method === "session/new")).toHaveLength(1);
    expect(log.filter((entry) => entry.method === "session/prompt").map((entry) => entry.sessionId)).toEqual(["session-1", "session-1"]);
  });

  test("another terminal gets its own agent, aimed at that terminal", async () => {
    await finish(request("hello", "term_other"));
    const created = (await records()).filter((entry) => entry.method === "session/new").at(-1);
    expect(created.env.CMUX_TUI_TERMINAL_ID).toBe("term_other");
  });

  test("cancel stops the run through session/cancel and ends it quietly", async () => {
    const req = request("HANG please");
    const out = await finish(req, async () => {
      for (let i = 0; i < 100 && !(await Bun.file(record).text()).includes("HANG"); i++) await Bun.sleep(20);
      await runner.cancel(req.id);
    });
    expect(out.at(-1)).toEqual({ id: req.id, type: "done", summary: "", cancelled: true });
    expect((await records()).some((entry) => entry.method === "session/cancel")).toBe(true);
  });

  test("a sign-in error says how to sign in", async () => {
    const out = await finish(request("FAIL"));
    expect(out.at(-1)).toEqual({
      id: out[0].id, type: "error",
      message: "Claude Code needs signing in on the Mac: Authentication required (run `claude` in a terminal on the Mac and sign in)",
    });
  });

  test("an agent that exits fails its run, and the next request starts a new one", async () => {
    const out = await finish(request("DIE now"));
    expect(out.at(-1)).toMatchObject({ type: "error" });
    expect((out.at(-1) as any).message).toStartWith("Claude Code: exited");
    const next = await finish(request("go to the vrcode repo"));
    expect(next.at(-1)).toMatchObject({ type: "done", summary: "Moved your terminal to ~/Developer/vrcode." });
    expect(next.some((o) => o.type === "progress" && o.text === "Starting Claude Code…")).toBe(true);
  });
});
