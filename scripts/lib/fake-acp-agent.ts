#!/usr/bin/env bun
/**
 * A stand-in ACP agent for the AI mic tests (`VRCODE_ACP_CLAUDE='["bun", ".../fake-acp-agent.ts"]'`),
 * built with the same SDK as the real ones, so no model is called. It appends what it was asked to
 * `FAKE_ACP_RECORD` as JSON lines and answers a prompt by its text: `HANG` waits for
 * `session/cancel`, `FAIL` answers with ACP's "authentication required", `DIE` exits, anything
 * else streams a short canned turn that runs a command, asks for permission and acts in the
 * terminal through cmux.
 */
import { appendFileSync } from "node:fs";
import { Readable, Writable } from "node:stream";
import * as acp from "@agentclientprotocol/sdk/unstable";

const record = (entry: Record<string, unknown>) => {
  if (process.env.FAKE_ACP_RECORD) appendFileSync(process.env.FAKE_ACP_RECORD, `${JSON.stringify(entry)}\n`);
};
const cancels = new Map<string, () => void>();
let sessions = 0;

const update = (cx: acp.AgentContext, sessionId: string, value: Record<string, unknown>) =>
  cx.notify(acp.methods.client.session.update, { sessionId, update: value as any });

acp
  .agent({ name: "fake-acp-agent" })
  .onRequest(acp.methods.agent.initialize, () => ({
    protocolVersion: acp.PROTOCOL_VERSION,
    agentCapabilities: { sessionCapabilities: { systemPrompt: {} } },
    authMethods: [],
  }))
  .onRequest(acp.methods.agent.session.new, (ctx) => {
    const sessionId = `session-${++sessions}`;
    record({
      method: "session/new", sessionId, cwd: ctx.params.cwd, systemPrompt: (ctx.params as any).systemPrompt?.content?.[0]?.text ?? null,
      env: { CMUX_TUI_SOCKET: process.env.CMUX_TUI_SOCKET ?? null, CMUX_TUI_TERMINAL_ID: process.env.CMUX_TUI_TERMINAL_ID ?? null },
    });
    const select = (id: string, category: string, values: string[]) => ({
      id, name: id, category, type: "select", currentValue: values[0], options: values.map((value) => ({ value, name: value })),
    });
    return {
      sessionId,
      configOptions: [
        select("mode", "mode", ["default", "acceptEdits", "bypassPermissions"]),
        select("model", "model", ["default", "opus", "sonnet", "haiku"]),
        select("effort", "thought_level", ["low", "medium", "high"]),
      ] as any,
    };
  })
  .onRequest(acp.methods.agent.session.setConfigOption, (ctx) => {
    record({ method: "session/set_config_option", sessionId: ctx.params.sessionId, configId: ctx.params.configId, value: (ctx.params as any).value });
    return { configOptions: [] };
  })
  .onNotification(acp.methods.agent.session.cancel, (ctx) => {
    record({ method: "session/cancel", sessionId: ctx.params.sessionId });
    cancels.get(ctx.params.sessionId)?.();
  })
  .onRequest(acp.methods.agent.session.prompt, async (ctx) => {
    const { sessionId } = ctx.params;
    const text = ctx.params.prompt.map((block: any) => block.text ?? "").join("");
    record({ method: "session/prompt", sessionId, text });
    if (text.includes("HANG")) {
      await new Promise<void>((resolve) => cancels.set(sessionId, resolve));
      return { stopReason: "cancelled" };
    }
    if (text.includes("FAIL")) throw new acp.RequestError(-32000, "Authentication required");
    if (text.includes("DIE")) process.exit(3);
    const cx = ctx.client;
    await update(cx, sessionId, { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "Finding the repo. " } });
    await update(cx, sessionId, { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "Then cd there." } });
    await update(cx, sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Looking around." } });
    // Like Claude Code: a generic call first, the command in an update.
    await update(cx, sessionId, { sessionUpdate: "tool_call", toolCallId: "t1", title: "Terminal", kind: "execute", status: "pending", rawInput: {} });
    await update(cx, sessionId, { sessionUpdate: "tool_call_update", toolCallId: "t1", title: "ls ~/Developer", rawInput: { command: "ls ~/Developer" } });
    await update(cx, sessionId, { sessionUpdate: "tool_call_update", toolCallId: "t1", status: "completed" });
    const permission = await cx.request(acp.methods.client.session.requestPermission, {
      sessionId,
      toolCall: { toolCallId: "t2", title: "cmux terminal write", kind: "execute" },
      options: [
        { optionId: "reject", name: "Reject", kind: "reject_once" },
        { optionId: "once", name: "Allow once", kind: "allow_once" },
        { optionId: "always", name: "Always allow", kind: "allow_always" },
      ],
    });
    record({ method: "permission", outcome: permission.outcome });
    await update(cx, sessionId, {
      sessionUpdate: "tool_call", toolCallId: "t2", title: "cmux", kind: "execute", status: "in_progress",
      rawInput: { command: "cmux terminal current keys ctrl+u && cmux terminal current write --text 'cd ~/Developer/vrcode' && cmux terminal current keys enter && sleep 1 && cmux terminal current screen read" },
    });
    await update(cx, sessionId, { sessionUpdate: "tool_call_update", toolCallId: "t2", status: "completed" });
    await update(cx, sessionId, { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Moved your terminal to **~/Developer/vrcode**." } });
    return { stopReason: "end_turn" };
  })
  .connect(acp.ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin) as unknown as ReadableStream<Uint8Array>));
