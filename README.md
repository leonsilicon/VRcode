# VRcode

A mixed reality coding workspace for Meta Quest. Put live Mac terminals around your room, arrange them with your hands, and speak to your coding tools.

Your Mac runs the terminal processes and AI agents. Your Quest displays the workspace over passthrough and handles hand input, voice, and settings.

## What you can do

- **Work in real terminals.** Open floating terminal windows backed by cmux on your Mac, with panes, tabs, workspaces, and hardware keyboard input.
- **Arrange your workspace.** Move windows with a left-hand pinch, resize terminal rows and columns from their edges, scale them from their corners, and restore saved placements.
- **Dictate with your right hand.** Close your fist to speak, watch the transcript above a virtual microphone, and open your hand to type it into the terminal.
- **Ask AI with your left hand.** Speak an instruction with the target terminal as context. Choose Claude, OpenAI, or Gemini; thinking mode can run a coding agent in the terminal's project and show its progress.
- **Use palm menus and the dock.** Create terminals, launch Codex or Claude Code, send common keys, open a keyboard, recall windows, and change settings.
- **Manage the Mac from its companion app.** VRcode Mac supervises the services, approves headset pairing, stores provider keys in Keychain, and mirrors Quest settings.

This is an early development project, currently targeting Quest 3 and Apple silicon Macs. Native Mac display streaming, Mac system audio in the headset, and persistent room anchors are planned; see [the project plan](PLAN.md).

## How it fits together

| Component | Runs on | Role |
| --- | --- | --- |
| [cmux](https://github.com/manaflow-ai/cmux) | Mac | Owns terminal processes, panes, tabs, workspaces, and saved window layout. |
| [xmux](https://github.com/ImproveLabs/xmux) | Mac and Quest | Bridges the cmux session to the headset and renders spatial terminal windows. |
| VRcode Quest app | Quest | Adds hand gestures, dictation, AI input, window management, and settings using Kotlin, Meta Spatial SDK, Compose, and OpenXR. |
| `vrcode-mac` | Mac | Swift service for transcription, AI requests, background cmux control, settings synchronization, and the authenticated connection relay. |
| VRcode Mac | Mac | Tauri 2 menu-bar app that bundles and supervises the Swift service and `xmux-bridge`. |

The bridge uses port **7272**, the speech/control service uses **7273**, and the authenticated TLS relay uses **7274**. Application connections support local Wi-Fi, USB/ADB, and Bluetooth, with additional network options described in [connection setup](docs/local-wireless.md).

## Requirements

- A **Meta Quest 3** with developer mode enabled and USB debugging authorized for your Mac.
- An **Apple silicon Mac with macOS 27 or later** and Xcode 27 for the current Swift service and Mac app.
- **Bun 1.4.4 or later**, **Rust/Cargo**, and the **Tauri 2 CLI**.
- **JDK 17** and the Android SDK: platform **35**, Platform Tools (`adb`), NDK **27.2.12479018**, and CMake **3.22.1**.
- A local checkout of **xmux**, including its installed dependencies, and **cmux** available on the Mac. The service looks for `cmux` on `PATH` or in `~/.cmux/bin/cmux`; `CMUX_BIN` overrides it.
- For AI features, a provider key configured in VRcode Mac and the corresponding coding-agent CLI installed and signed in when using thinking mode.

## Build and run

### Prepare the checkouts

```sh
git clone https://github.com/leonsilicon/VRcode.git
git clone https://github.com/ImproveLabs/xmux.git
cd xmux
bun install
cd ../VRcode
bun install
cargo install tauri-cli --version '^2' --locked
```

Point both the Mac build and Gradle at your xmux checkout. Set these in the shell where you run the commands below:

```sh
export XMUX_DIR=/absolute/path/to/xmux
export ORG_GRADLE_PROJECT_xmuxDir="$XMUX_DIR"
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

Use the Rust toolchain pinned by xmux's `rust-toolchain.toml`. Without overrides, Gradle looks for xmux at `../../leonsilicon/xmux` relative to this repository, while the Mac scripts use `~/Developer/github/leonsilicon/xmux`.

### Start a development session

Connect and authorize the Quest over USB, then run:

```sh
bun run dev
```

This opens an mprocs session with two processes:

- **mac** builds the Swift service and xmux bridge, then runs VRcode Mac under `cargo tauri dev`. Rust, UI, and Swift changes rebuild or restart the relevant parts.
- **quest** sets up ADB tunnels, builds and installs the debug APK, relaunches VRcode, imports the Mac connection key, and follows the app's logs.

Press **r** to restart the selected process and **q** to quit. Restart the Quest process after app changes to install the new APK. Installation restarts the headset app.

The launcher requires ports **7270–7274** to be free. If an installed VRcode Mac or an earlier development session is using them, `bun run dev --force` stops the conflicting stack first and restores an installed Mac app when the session ends. The development Mac app is unsigned and runs with Bluetooth disabled; use USB/ADB or local Wi-Fi. See [development session details](scripts/README.md#development-session).

### Install for everyday use

```sh
bun scripts/_tauri.ts
bun scripts/_quest.ts
bun run quest-link pair
```

The first command builds and installs VRcode Mac as a per-user LaunchAgent, enables Bluetooth, and checks that its service is listening. The second installs and relaunches the Quest app, then checks that the package was updated and remains running. Both commands restart their respective apps.

`quest-link pair` imports the Mac's relay connection key into the debug Quest app. Leave **Settings → Connection** on **Automatic**. If xmux requests its separate terminal pairing, approve the headset's six-digit code in VRcode Mac. Allow the permissions requested by the Mac app and the headset, including microphone access for voice.

For a headset connected through Wi-Fi ADB, pass its serial explicitly:

```sh
bun scripts/_quest.ts --serial QUEST_IP:5555
bun run quest-link pair --serial QUEST_IP:5555
```

Run `bun run quest-link --help` for USB tunnels and Wi-Fi ADB setup. [Connection setup](docs/local-wireless.md) covers direct Wi-Fi, Bluetooth, manual keys, and platform limitations. [Mac app setup](docs/mac-app.md) covers signing, permissions, installation, logs, and releases.

## Checks

```sh
bun run test
bun run assets:validate
./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest :connection:testDebugUnitTest
```

The Bun suite checks repository scripting policy, tooling, agent behavior, and assets. Native service integration tests need explicitly configured Mac binaries and are skipped by default; see [the tooling guide](scripts/README.md). Asset validation checks delivered GLB structure and catalog requirements; hand fit and rendering still need headset verification.

After changing shipped Quest code or assets, reinstall with `bun scripts/_quest.ts` and check the feature's log output. After changing the Mac app or service, reinstall with `bun scripts/_tauri.ts`.

## Repository map

| Path | Contents |
| --- | --- |
| [`app/`](app/) | Quest spatial app, interaction features, and native hand-tracking bridge. |
| [`settings-ui/`](settings-ui/) | Compose settings, shared models, and settings synchronization schema. |
| [`connection/`](connection/) | Quest transport selection and authenticated relay client. |
| [`mac/`](mac/) | Swift service and the Bun AI agent runner. |
| [`mac-app/`](mac-app/) | Tauri menu-bar companion and settings UI. |
| [`scripts/`](scripts/) | Bun/TypeScript development, installation, validation, and test tooling. |
| [`assets/`](assets/) | Runtime models, editable asset sources, previews, and credits. |
| [`docs/`](docs/) | Architecture, setup, interaction behavior, and validation notes. |

## Further reading

- [Hand gestures and palm menus](docs/hand-input.md), [dock](docs/dock.md), and [settings](docs/settings.md).
- [Terminal controls](docs/cmux-control.md), [interaction inventory](docs/cmux-interactions.md), and [saved window layout](docs/window-layout.md).
- [Connection implementation and validation](docs/connection-validation.md) and [service protocol](docs/vrcode-protocol.md).
- [xmux integration](docs/xmux-integration.md) and [planned Mac display streaming](docs/mac-display-streaming.md).
- [Project plan](PLAN.md) and [project story](DEVPOST.md).

Asset sources and preparation are documented in [the microphone guide](assets/microphone/README.md); third-party model sources and license details are listed in [voice-prop credits](assets/voice-props/CREDITS.md).
