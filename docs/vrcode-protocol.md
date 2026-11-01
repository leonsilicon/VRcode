# vrcode service protocol (v1)

The headset talks to the vrcode Mac service (`mac/`, port 7273) over one TCP connection, separate from
xmux's bridge connection. Frames use xmux's header layout with vrcode's own kinds and version, so
xmux's `Header::parse` is never fed vrcode frames.

The normal app now carries this stream inside the authenticated, pinned TLS
[connection relay](connection-relay.md), over the user's selected carrier. The raw speech listener
stays on Mac loopback. This framing itself has no authentication: legacy debug clients using the
7273 ADB forward still need a trusted network. See [connection setup](local-wireless.md).

## Frame

A 16-byte little-endian header, then `body_len` bytes of body:

| Offset | Size | Field |
|---|---|---|
| 0 | 4 | `body_len` |
| 4 | 1 | `kind` |
| 5 | 1 | `flags` (0) |
| 6 | 2 | `version` (1) |
| 8 | 4 | `id`: the utterance id for audio and transcript frames, else 0 |
| 12 | 4 | `sequence`: per-direction counter |

JSON bodies are UTF-8 objects. Unknown kinds are skipped; a version other than 1 closes the
connection.

## Kinds

| Kind | Name | Direction | Body |
|---|---|---|---|
| 1 | `HELLO` | headset -> Mac | `{"client":"vrcode","protocol":1}` |
| 2 | `WELCOME` | Mac -> headset | `{"service":"vrcode-mac","protocol":1,"locale":"en_US","ready":true}`; `ready` is false while speech assets download |
| 3 | `AUDIO_START` | headset -> Mac | `{"sampleRate":16000,"channels":1,"encoding":"pcm_s16le","locale":"en-US"}`; `locale` is optional |
| 4 | `AUDIO` | headset -> Mac | raw PCM in the format `AUDIO_START` named |
| 5 | `AUDIO_END` | headset -> Mac | empty; the Mac finalizes and sends a `TRANSCRIPT` with `done` |
| 6 | `AUDIO_CANCEL` | headset -> Mac | empty; the Mac drops the utterance and sends nothing more for it |
| 7 | `TRANSCRIPT` | Mac -> headset | `{"final":"...","volatile":"...","done":false}`: `final` is all finalized text so far, `volatile` the current guess after it |
| 8 | `ERROR` | Mac -> headset | `{"message":"..."}` |
| 9 | `CMUX_REQUEST` | headset -> Mac | One public `cmux.protocol/2` request envelope, including its own string request ID and mutation idempotency key |
| 10 | `CMUX_EVENT` | Mac -> headset | The cmux response, stream item, or stream end envelope unchanged; `connection_end` reports a lost local cmux socket |
| 11 | `AI_REQUEST` | headset -> Mac | `{"transcript":"...","screen":"...","title":"...","cursor":{"row":12,"col":30,"visible":true},"provider":"claude","model":"claude-sonnet-5-5","agent":true,"terminal":"term_…"}`: what the user said to the AI (left fist); everything on the target pane's screen when the AI is asked (one line per row from the top, blank rows below the text and cursor dropped, so `cursor.row` indexes the lines; `col` is in cells), its title, and its cursor (the Mac marks it with ▮ in a cursor line for the model, unless `visible` is false); and the provider and model chosen in Settings (`provider`: `claude`, `openai` or `gemini`, missing means `claude`; `model`: one of that provider's API model ids, missing or unknown means the provider's default). `id` is unique per connection (shared with utterance ids) |
| 12 | `AI_RESULT` | Mac -> headset | `{"keys":[{"key":"ctrl+u"},{"text":"git status"},{"key":"enter"}],"text":"git status","provider":"...","model":"..."}`: the keystrokes to send that pane, in order, same `id`. A `text` step is typed as is (it never contains a line break); a `key` step is one key press in cmux's chord syntax (`enter`, `tab`, `backtab`, `escape`, `backspace`, `delete`, `insert`, `space`, arrows, `home`, `end`, `pageup`, `pagedown`, `f1`-`f24`, or a letter, digit or punctuation key, after `ctrl+`, `alt+`, `shift+` in that order). The model decides whether to press Enter. The top-level `text` joins the text steps for headset builds that predate `keys`. A failure is an `ERROR` with that `id` |

| 16 | `AI_PROGRESS` | Mac -> headset | `{"kind":"thinking","text":"..."}`, same `id` as an `AI_REQUEST` with `agent` true, while its agent runs: `status` (one line about the run: `Claude Code in ~/project`), `thinking` (a chunk of the model's thinking summary or of what it wrote between tool calls; chunks continue each other) or `step` (one tool call as one line: `$ git status`, `Read src/main.ts`). The run then ends with `AI_RESULT` or `ERROR` |
| 17 | `AI_CANCEL` | headset -> Mac | empty, same `id` as a running agent `AI_REQUEST`: the Mac stops its CLI and sends nothing more for it |
| 13 | `SETTINGS` | headset -> Mac -> VRcode Mac app | Headset: `{"device":"Quest 3","schema":{…},"values":{…}}`, its complete settings. The Mac adds `"connected":bool` and `"pending":{…}` before passing it to the app |
| 14 | `SETTINGS_SET` | VRcode Mac app -> Mac -> headset | `{"values":{"terminal_font_size":28}}`, a partial update |
| 15 | `SETTINGS_ACTION` | VRcode Mac app -> Mac -> headset | `{"action":"arrange_windows"}` |

One utterance is in flight per connection: a new `AUDIO_START` cancels the previous one.

## Settings mirroring

VRcode Mac shows the Quest's own settings and changes them. `WELCOME` adds `settings: true`. The
headset's settings connection (`SettingsLink`) then sends `SETTINGS`, and sends it again after every
change from either side. The Quest is the source of truth: values use its preference keys and
enum `storageValue`s, and `SettingsSync.kt` builds the schema from the same enums and page order
as the Settings panel. A new theme, mic or AI provider therefore appears in VRcode Mac
automatically.

VRcode Mac connects to the service port on loopback and says `HELLO {"client":"vrcode-mac-app"}`.
It then gets the latest `SETTINGS` at once and on every change. While the Quest is away it still
gets the last-known values, with `connected: false`; the Mac keeps them in
`~/Library/Application Support/dev.improve.vrcode/quest-settings.json`. Its `SETTINGS_SET` goes to
every connected headset, which applies the values like a panel change and answers with fresh
`SETTINGS`. With no headset connected, the values wait in `pending` and are sent after the
headset's next `SETTINGS`. `SETTINGS_ACTION` is never queued; offline it gets an `ERROR`. Only
`vrcode-mac-app` connections may set settings or run actions.

Schema: `{"version":1,"pages":[{"id","title","tabs"?:[{"id","title"}],"sections":[{"title"?,"detail"?,"tab"?,"items":[…]}]}]}`.
Item types:

| Type | Fields |
|---|---|
| `toggle` | `key`, `label`, `detail` |
| `choice` | `key`, `label`?, `options: [{value, label, detail?, colors?}]` |
| `slider` | `key`, `label`, `min`, `max`, `step`, `unit`, `decimals`, `scale`; shown as value × scale |
| `presets` | `options: [{label, values}]`, for example terminal size |
| `reset` | `label`, `values` (send as `SETTINGS_SET`) |
| `action` | `action`, `label` |
| `api_key` | `providerKey`: the Mac shows its Keychain key field for the provider selected there |
| `quick_inputs` | `key`, `slots: [{id, label}]` (eight directions clockwise from the top), `options: [{value, label, detail}]`. The value is eight option values in slot order, `""` where empty; the Mac shows a keypad of selects and, like the Quest, swaps an input that is already placed |
| `note` | `text` |

Any item may have `hiddenWhen: {key: value}`. Clients skip item types they do not know. API keys
stay on the Mac: VRcode Mac stores them in the Keychain and never sends them to the headset.

AI requests run beside the audio path, so a slow answer never delays audio frames.

**Thinking (agent) requests.** With `agent` true (Settings → AI mic → Thinking: above or below, the
default) the Mac runs the provider's coding-agent CLI headless instead of calling the API
(`AiAgent.swift`): Claude Code (`claude -p --output-format stream-json`), Codex (`codex exec
--json`) or Gemini CLI (`gemini -p --output-format stream-json`), each signed in with its own
login (no vrcode Keychain key), at medium effort with the request's `model`. It runs in the
working directory of the target terminal: `terminal` is its cmux id, and the Mac asks cmux
(`terminal.process.get`, `foreground_cwd`), falling back to the home folder. The agent may look
around first (project instructions and scripts, files, git state) but cannot change anything, and
that is enforced by each CLI rather than the prompt: Claude Code runs `--restricted` (user and
project settings and their allow rules are ignored) with only Read, Grep, Glob and Bash and
`--permission-prompts none`, so any Bash command that is not read-only is denied; Codex runs in its
`read-only` sandbox; Gemini CLI in its `plan` approval mode. Whatever should run is what it types
into the pane. Its answer is structured output, `{"summary":"...","keys":[{"text":"...","key":null},
{"text":null,"key":"enter"}]}`, checked like an API reply; `AI_RESULT` adds the `summary`. A run
stops after 120 s or 12 tool calls with an `ERROR`; `AI_CANCEL`, or the headset disconnecting,
stops it with no reply. Each CLI is found on PATH plus the usual install folders (`~/.local/bin`,
Homebrew, Bun, npm), since the LaunchAgent's PATH has none of them; `VRCODE_CLAUDE_BIN`,
`VRCODE_CODEX_BIN` and `VRCODE_GEMINI_BIN` override that (the integration test,
`scripts/tests/ai-agent.test.ts`, uses a fake `claude` this way). Requests without `agent` (and older
headsets) take the API path below.

**Quick (API) requests.** The Mac calls the chosen model at low effort: Claude (Messages API; `claude-haiku-5-5`, `claude-sonnet-5-5` (default), `claude-opus-5-5` or `claude-fable-5-1`; server-side refusal fallback on all but Haiku, which has none), ChatGPT (OpenAI Responses API; `gpt-6-luna`, `gpt-6.1-terra`, `gpt-6.1-sol` (default) or `gpt-6-astra`) or Gemini (`generateContent`; `gemini-3.5-flash-lite`, `gemini-3.8-flash` (default) or `gemini-4-argon`). `AiProvider.models` in `AiService.swift` and `AiModel` in settings-ui list the same ids; the Mac uses the provider's default for an id it doesn't offer, and `AI_RESULT`'s `model` names the one that answered. Each provider's key comes from the Mac Keychain (service `dev.improve.vrcode`, account `anthropic-api-key`, `openai-api-key` or `gemini-api-key`), else `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY`, else `~/.config/vrcode/<account>`; keys never reach the headset. The model replies with a JSON array of steps; the Mac checks every key against cmux's key names (fixing spellings like `Ctrl-C`, `esc`, `return`), merges adjacent text, turns a line break inside text into a space, and fails the request (an `ERROR`) rather than send a reply it cannot read or a key cmux cannot press. The headset types the steps into the pane focused when the fist opened: text with `TerminalHandle.write`, keys with `TerminalHandle.keys`, which the xmux bridge hands to cmux in order.

## Background cmux control

`WELCOME` adds `cmux: true` when background control is available. Speech readiness is independent:
control starts before microphone authorization or speech model preparation. `--control-only` runs
the Mac service without loading speech. The Quest opens its own control connection through service
2 of the existing pinned, authenticated relay; direct non-loopback service connections cannot use
cmux control, including when legacy audio is bound with `--any-interface`.

Every control connection owns one persistent Unix socket to the configured cmux session. This
preserves stream cancellation and connection-scoped viewer leases. `--cmux-session main` selects
the same session the xmux bridge mirrors; `--cmux-socket PATH` overrides discovery for tests or a
custom daemon. Otherwise `cmux server ensure` locates/starts that background daemon without
attaching a terminal frontend. All 127 pinned public operations are allowlisted. The service pins
machine/session routing, and forwards typed target IDs, parameters, responses, and idempotency keys.
It never activates a Mac application or dispatches a Mac keyboard shortcut. Mutations are sent once;
a lost response is an unknown outcome, and is never automatically repeated. Disconnecting closes
the Unix socket and releases its streams/leases. Requests/responses are bounded by the frame limit;
slow consumers exceeding the 8 MiB send backlog are disconnected.

Two local extensions use the same request/response envelopes: `vrcode.clipboard.read` returns
`{"text":"..."}`; `vrcode.clipboard.write` accepts `{"text":"..."}` and returns `{"copied":true}`.
These use `NSPasteboard` in the background. They never simulate Cmd-C or Cmd-V. The Quest's normal
paste reads its own clipboard and sends `terminal.input.write` to the captured terminal ID.

A third, `vrcode.terminal.size.set`, sets a terminal's grid from the Quest. With
`{"terminal":"term_…","cols":C,"rows":R}` (1-1000 each) it sets cmux's per-terminal size policy to
`fixed` C×R, so the grid holds while other viewers, such as a Mac frontend, report other sizes; they
crop or pan it. With only `{"terminal":"term_…"}` it clears the policy and the grid goes back to
cmux's attached viewers. It returns `{"terminal","cols","rows","policy"}` from cmux's new size
state. The size policy has no protocol/2 operation, so the service sends `list-workspaces` (to find
the terminal's surface) and `set-size-policy` as protocol/1 commands on a short-lived socket of
its own. Policies live in the cmux daemon's memory and reset when it restarts.

The complete public, keyboard, and context-menu inventory is in [cmux interactions](cmux-interactions.md).
