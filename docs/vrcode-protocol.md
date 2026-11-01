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
| 11 | `AI_REQUEST` | headset -> Mac | `{"transcript":"...","screen":"...","title":"...","provider":"claude"}`: what the user said to the AI (left fist), the focused pane's visible text and title, and the model provider chosen in Settings (`claude`, `openai` or `gemini`; missing means `claude`). `id` is unique per connection (shared with utterance ids) |
| 12 | `AI_RESULT` | Mac -> headset | `{"text":"...","provider":"...","model":"..."}`: the exact text to stage for that pane (a shell command, or a prompt for a coding agent), same `id`. A failure is an `ERROR` with that `id` |

One utterance is in flight per connection: a new `AUDIO_START` cancels the previous one.

AI requests run beside the audio path, so a slow answer never delays audio frames. The Mac calls the chosen provider at low effort: Claude (Messages API, `claude-opus-5-5`, server-side refusal fallback), ChatGPT (OpenAI Responses API, `gpt-6.1-sol`) or Gemini (`generateContent`, `gemini-3.8-flash`). Each provider's key comes from the Mac Keychain (service `dev.improve.vrcode`, account `anthropic-api-key`, `openai-api-key` or `gemini-api-key`), else `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `GEMINI_API_KEY`, else `~/.config/vrcode/<account>`; keys never reach the headset, and the result is staged on a voice pane for review, never typed directly.

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
