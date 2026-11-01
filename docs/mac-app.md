# VRcode Mac (the Mac app)

`VRcode Mac.app` is vrcode's Mac side: a signed menu-bar app built with Tauri 2 (`mac-app/`).
It bundles and supervises two helpers, and ships the AI mic's agent runner beside them:

| Helper | From | Serves |
|---|---|---|
| `vrcode-mac` | the Swift service in `mac/` (release build) | speech on 7273, the TLS connection relay on 7274 (LAN, hotspots, Bluetooth, ADB), cmux control |
| `xmux-bridge` | the xmux checkout (`XMUX_DIR`, default `~/Developer/github/leonsilicon/xmux`) | the cmux session to the headset on 7272 |
| `vrcode-bun` + `vrcode-agent.js` | Bun itself (the one running the packaging script), and `mac/agent/vrcode-agent.ts` bundled with `bun build` into the app's Resources | started by `vrcode-mac` for the AI mic's agent mode: drives Claude Code, Codex or Gemini CLI over the Agent Client Protocol, and is the Bun that runs them ([protocol](vrcode-protocol.md)) |

Nothing else needs to run on the Mac: no separate xmux CLI or LaunchAgent, and no terminal.

Built and verified on 2026-11-01:

- **Signing.** Signed with Developer ID Application (M76P5G9HXK), hardened runtime and a secure
  timestamp. `codesign --verify --deep --strict` passes. Not notarized; see below.
- **Runtime behaviour** (isolated ports):
  - Both helpers start.
  - A killed bridge restarts after 1 s.
  - SIGTERM to `vrcode-mac` or to the app stops everything.
  - After a hard app crash, a relaunch stops only the helpers it had recorded.
  - The status window renders live data.

## Build, run, install

```sh
cargo install tauri-cli --version '^2' --locked   # once
bun run mac:run [service args]           # build, sign, package, open once
bun run mac:run --package-only           # build, sign, package (mac/.build/VRcode Mac.app)
bun run mac:install [service args]       # start at login; relaunch after a crash
bun run mac:uninstall                    # stop and remove the LaunchAgent and installed copy
```

`mac:run` and `mac:install` package the app the same way (`scripts/run-mac-service.ts`):

1. `swift build -c release` in `mac/`, copied to `mac-app/src-tauri/binaries/vrcode-mac-<triple>`.
2. `bun install --frozen-lockfile`, then `bun build --target=bun mac/agent/vrcode-agent.ts` into
   `mac-app/src-tauri/resources/vrcode-agent.js`, and Bun itself copied as `vrcode-bun-<triple>`.
3. `cargo build --release -p xmux-bridged --bin xmux-bridge` in the xmux checkout, copied as
   `xmux-bridge-<triple>`.
4. `cargo tauri build --bundles app`, signing the app and its sidecars with the hardened runtime.
   The identity is `APPLE_SIGNING_IDENTITY`, else the first "Developer ID Application" identity,
   else "Apple Development", else ad hoc. A stable identity matters: macOS privacy grants and the
   Keychain access list follow the signature, so with ad hoc signing every rebuild asks again.
5. The bundle is copied to `mac/.build/VRcode Mac.app` and the signature is verified.

`mac:install` copies the app to `~/Library/Application Support/dev.improve.vrcode/VRcode Mac.app`
and writes the LaunchAgent `dev.improve.vrcode.mac`:

- It has `RunAtLoad`, and `KeepAlive` with `SuccessfulExit = false`. launchd relaunches the app
  after a crash, but **Quit VRcode Mac** in the menu bar stays quit.
- To start it again: `launchctl kickstart gui/$(id -u)/dev.improve.vrcode.mac`.
- Its log is `~/Library/Logs/vrcode-mac.log`, which holds the app's and both helpers' output.
- Re-run `mac:install` after rebuilding.
- While the agent is loaded, `mac:run` refuses to open a second copy, because it would compete for
  the same ports.

The app is Developer ID signed but not notarized. Copies built on this Mac run as they are; a copy
downloaded on another Mac needs notarization (set `APPLE_ID`, `APPLE_PASSWORD` and `APPLE_TEAM_ID`,
or the App Store Connect API key variables, and Tauri notarizes during the build).

For UI work, `cargo tauri dev` in `mac-app/src-tauri` runs a debug build; build the two helpers into
`binaries/` first (`bun run mac:run --package-only` does).

## Arguments

The app's arguments go to `vrcode-mac` unchanged (`--bluetooth`, `--port`, `--link-port`,
`--cmux-session`, …). The bridge's are derived from them:

| Service argument | Bridge argument |
|---|---|
| `--bridge-port P` (default 7272) | `serve --port P` |
| `--cmux-session S` (default `main`) | `--session S` |
| `--cmux-socket PATH` | `--socket PATH` (instead of `--session`) |

The bridge doesn't run with `--link-only` (relay only; the relay tests use this with a stand-in
bridge), in the one-shot modes (`--link-info`, `--transcribe`, `--help`), or with the app-only flag
`--no-xmux-bridge`. It uses xmux's default state directory
(`~/Library/Application Support/dev.improve.xmux.bridge`, or `XMUX_BRIDGE_STATE`), so an existing
bridge certificate and paired headsets carry over.

## Supervision

- **Crash or non-zero exit.** The helper restarts with backoff: 1 s, doubling to 30 s, reset after
  a minute of uptime.
- **Port held by something else.** If a helper fails because its port is taken, the log says so,
  for example another xmux bridge LaunchAgent:
  `launchctl bootout gui/$(id -u)/dev.improve.xmux.bridge`.
- **Deliberate stop of `vrcode-mac`** (SIGTERM, SIGINT or SIGHUP, or a clean exit). The whole app
  quits with status 0, so stopping the service stops the app, as before. The relay tests stop a
  test copy this way. A deliberate stop of the bridge just restarts it.
- **SIGTERM to the app** (logout, `launchctl bootout`). Both helpers stop: SIGTERM, then SIGKILL
  after 3 s.
- **Helpers left behind.** Each helper's pid is recorded in
  `$TMPDIR/dev.improve.vrcode.mac/<helper>-<hash of its path>.pid`. If the app itself dies hard,
  the next launch stops only that recorded process, and only if it is still this bundle's helper
  with launchd as its parent. Unrelated processes are never touched.
- **Log echo.** Helper output is echoed verbatim to the app's stdout and stderr, so the LaunchAgent
  log and `open --stderr` capture it.

## Menu bar and window

The menu-bar item (a headset visor) has:

- one status line per helper;
- **Approve Headset…**, enabled while a headset waits to pair, with the waiting count next to the
  icon;
- **Open VRcode Mac…**, **Restart Services** and **Quit VRcode Mac**.

Opening the app again (Finder, Spotlight, `open`) also shows the window. Its sidebar has **Status**
(below) and then the **Quest settings** pages.

### Quest settings

The window shows the Quest's own settings, with the same pages, tabs, sections, labels and controls
as the headset's settings panel, and changes them from the Mac:

- **Schema.** The Quest describes its settings page (`SettingsSync.kt`), so a new theme, mic or
  provider appears here without a Mac release.
- **Connection.** The app is one more client of `vrcode-mac` on 127.0.0.1:`--port` (default 7273).
  It sends `HELLO {"client":"vrcode-mac-app"}`, mirrors every `SETTINGS`, sends `SETTINGS_SET` and
  `SETTINGS_ACTION`, and reconnects when the service restarts. See "Settings mirroring" in
  [the protocol](vrcode-protocol.md) and `mac-app/src-tauri/src/settings.rs`.
- **Rendering.** Each item type gets its own control:
  - toggle: a switch;
  - choice: option cards, with colour swatches for themes;
  - slider: shown as value × `scale` with `unit`;
  - presets: chips, selected when every value matches;
  - reset and action: buttons;
  - note: text;
  - api_key: the Keychain field for the provider selected on the Quest.

  `hiddenWhen` hides an item, and unknown item types are skipped.
- **While the Quest is away.** The page shows its last-known settings and says so. A change is kept
  as `pending`, marked "Applies when the Quest reconnects", and delivered on reconnect. Actions,
  such as Arrange windows here, need the Quest connected.
- **First run.** Before the Quest has connected once, the sidebar says its settings will appear
  then.
- **Live test.** `VRCODE_SETTINGS_PORT=7273 cargo test -- --ignored live` in `mac-app/src-tauri`
  checks the round trip against a running service and Quest.

### Status

| Section | What it shows and does |
|---|---|
| Services | state, pid, uptime, restarts, last exit and arguments of each helper; Restart per helper or all; whether the LaunchAgent is installed |
| Headset pairing | the served session and address; waiting headsets; enter the six-digit code the headset shows and Approve, or Reject; paired headsets with Unpair. It uses the bundled `xmux-bridge status --json`, `pair CODE`, `pair --reject` and `unpair ID`. When a headset starts waiting, the window opens on this section with the code field focused |
| Permissions | Screen & System Audio Recording and Accessibility (granted or not, **Allow…** asks macOS and opens the list), plus shortcuts to the Bluetooth and Local Network lists |
| AI provider keys | saves or removes the Keychain items `dev.improve.vrcode` / `anthropic-api-key`, `openai-api-key` and `gemini-api-key` that `AiService.swift` reads (Claude, ChatGPT, Gemini; the provider is picked on the Quest). Only the AI mic's Quick answer uses them: in agent mode (the default) the bundled `vrcode-agent` runner starts Claude Code, Codex or Gemini CLI over the Agent Client Protocol, and they use their own logins (`claude`, `codex login`, `gemini`), so sign in to the provider's CLI on this Mac ([protocol](vrcode-protocol.md)). In both modes a Gemini or OpenAI key also lets a model hear what was said to the AI mic in place of the on-device transcript (`AiHearing.swift`); Claude's API takes no audio |
| Log | both helpers' output and the app's own notes; the bridge's "run `xmux pair`" hint reads "approve it in VRcode Mac under Headset pairing" here |

The Quest stays the source of truth for its settings; the Mac shows them and requests changes.
Permissions, headset approval and the AI provider keys can only be handled on the Mac.

## Permissions and identity

The helpers are launched by the app, so macOS attributes their access to `VRcode Mac`
(`dev.improve.vrcode.mac`), and grants survive rebuilds signed with the same identity. Bundle
details:

- **Info.plist.** The bundle's Info.plist carries the privacy strings (Bluetooth, Local Network,
  `_vrcode._tcp`) and `LSUIElement`; the helper reads them because `Bundle.main` resolves to the
  app. Keep `mac-app/src-tauri/Info.plist` in step with `mac/Info.plist`, which the bare
  `swift run` binary embeds.
- **Screen Recording (macOS 26.1+).** Background executables no longer appear in the Screen
  Recording list; an app bundle does. On macOS 27, `CGRequestScreenCaptureAccess()` no longer
  prompts. **Allow…** still adds the app to the list and opens it.
- **AI provider keys.** Each Keychain item is created with an access list that trusts both the app and the
  bundled `vrcode-mac`. The helper therefore reads the key without a prompt, which matters with the
  lid closed. The service reads it on every request, so no restart is needed.

## Releases

`.github/workflows/mac-app-release.yml` builds the app on GitHub's `xcode-27` runner (macOS 27,
Xcode 27, a public preview label that actionlint doesn't know yet). It runs the same
`bun scripts/run-mac-service.ts --package-only` as a local build, with xmux checked out from
`ImproveLabs/xmux`. The app is signed with the Developer ID certificate, then notarized and stapled.

Before the release is published, the workflow checks:

- the signature and the hardened runtime on all three binaries;
- the stapled ticket;
- that Gatekeeper reports `Notarized Developer ID`;
- the Rust unit tests.

It then uploads `VRcode-Mac-<version>-macos-arm64.zip` and its `.sha256`. The release notes
record the VRcode and xmux commits.

To publish a version:

1. Set the same `version` in `mac-app/src-tauri/tauri.conf.json` and `mac-app/src-tauri/Cargo.toml`.
   Run `cargo check` in `mac-app/src-tauri` so `Cargo.lock` follows, then commit.
2. Push a tag: `git tag mac-v0.2.0 && git push origin mac-v0.2.0`. The workflow fails if the tag and
   the version differ. A version with a hyphen (`0.2.0-beta.1`) is published as a pre-release.

Or run **Release VRcode Mac** from the Actions tab. It tags the chosen commit with `mac-v<version>`
and creates a **draft** release by default, so the build can be checked before publishing. It can
also bundle a different xmux branch, tag or commit (`xmux_ref`, default `main`). It refuses a
version that is already tagged or released.

### Secrets (GitHub repository secrets)

The values live in `leonsilicon/VRcode`'s repository secrets; nothing else stores them.
`.env.schema` (varlock) declares them, marks the sensitive ones, and documents each. The workflow
runs the build through `varlock run`, which validates them and redacts them from output. Local
builds need none of them.

**Current state (set 2026-11-01).** The repository has `APPLE_CERTIFICATE`,
`APPLE_CERTIFICATE_PASSWORD`, `APPLE_ID`, `APPLE_PASSWORD` and `APPLE_TEAM_ID`:

- **Certificate.** "Developer ID Application: Leon Si", valid until March 2031.
- **Notarization.** It uses the Apple ID set.
- **Source.** They were copied from the KeyChord organization's secrets, which Chord's releases
  use, by a one-off workflow on a temporary `KeyChord/Chord` branch. The workflow used a temporary
  token secret; the branch, the token and the run were deleted afterwards.

GitHub never returns a secret's value, so to refresh them, either repeat that copy or set new values
as below.

| Name | What |
|---|---|
| `APPLE_CERTIFICATE` | base64 of the **Developer ID Application** certificate and its private key, exported as `.p12` |
| `APPLE_CERTIFICATE_PASSWORD` | the `.p12` export password |
| `APPLE_API_KEY`, `APPLE_API_ISSUER`, `APPLE_API_PRIVATE_KEY` | notarization with an App Store Connect API key: key ID, issuer ID, and the contents of `AuthKey_<id>.p8` |
| or `APPLE_ID`, `APPLE_PASSWORD`, `APPLE_TEAM_ID` | notarization with an Apple ID: the address, an app-specific password, and the team (`M76P5G9HXK`) |
| variable `APPLE_SIGNING_IDENTITY` (optional) | pins the identity, e.g. `Developer ID Application: Leon Si (M76P5G9HXK)`; without it the certificate's own identity is used |

To create them from scratch:

1. **Export the certificate.** In Keychain Access → login → My Certificates, select
   "Developer ID Application: Leon Si (M76P5G9HXK)", choose File → Export Items… → `.p12` and set a
   password.
2. **Store the certificate and its password.** Use the prompting or stdin forms, so values don't
   land in shell history:

   ```sh
   base64 -i DeveloperID.p12 | gh secret set APPLE_CERTIFICATE --repo leonsilicon/VRcode
   gh secret set APPLE_CERTIFICATE_PASSWORD --repo leonsilicon/VRcode    # prompts
   ```

   Delete the `.p12` afterwards.
3. **Create a notarization key.** In App Store Connect → Users and Access → Integrations →
   App Store Connect API, create a team key with Developer access and download it once. Then:

   ```sh
   gh secret set APPLE_API_KEY --repo leonsilicon/VRcode                 # the key ID
   gh secret set APPLE_API_ISSUER --repo leonsilicon/VRcode              # the issuer ID
   gh secret set APPLE_API_PRIVATE_KEY --repo leonsilicon/VRcode < AuthKey_<id>.p8
   ```

The workflow names any missing secret and stops before building. A downloaded release isn't set to
start at login: the notes and the window point to System Settings → General → Login Items.

## Build notes

- **Proc-macro stripping.** `mac-app/src-tauri/Cargo.toml` sets
  `[profile.release.build-override] strip = false`. Stripped release proc-macro dylibs can get a
  LINKEDIT string table that is only 4-byte aligned. macOS 27's dyld refuses to load those
  ("mis-aligned LINKEDIT string pool"), and rustc then reports `can't find crate for tauri_macros`.
  Executables are unaffected.
- **No `.js` files.** Repository policy forbids committed `.js` files, so the window's script is
  inline in `mac-app/ui/index.html`. Tauri hashes it into the CSP at build time.
