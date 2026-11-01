# Repository tooling

Use Bun 1.4.4 or newer for all repository-owned scripts and tests. Run `bun install` once so the
`vpx _dev`, `vpx _tauri` and `vpx _quest` commands (this folder's package.json `bin`) are linked; otherwise the
scripts use Bun and its built-in compatibility modules. The upstream `gradlew` / `gradlew.bat`
launchers remain unchanged and invoke the JVM for native builds.

| Task | Command |
| --- | --- |
| Run the whole dev stack in mprocs: VRcode Mac under `cargo tauri dev` and the Quest app ([below](#development-session)) | `vpx _dev [--force]` (or `bun run dev`) |
| Quest USB / Wi-Fi setup | `bun run quest-link --help` |
| Build, sign and run VRcode Mac (Tauri menu-bar app with vrcode-mac and xmux-bridge; needs `cargo install tauri-cli --version '^2' --locked`) | `bun run mac:run --bluetooth` |
| Package VRcode Mac without launching | `bun run mac:run --package-only` |
| Build and install VRcode Mac at login (restarts it) / remove it | `vpx _tauri [args]` / `vpx _tauri --uninstall` |
| Build, install and relaunch the Quest app on the connected headset (restarts it) | `vpx _quest [--serial SERIAL]` |
| Publish VRcode Mac to GitHub Releases | push a `mac-v<version>` tag, or run the **Release VRcode Mac** workflow ([docs](../docs/mac-app.md#releases)) |
| Script policy and unit tests | `bun run test` |
| Native TLS relay integration tests | `VRCODE_RELAY_BINARY="$PWD/mac/.build/debug/vrcode-mac" bun test scripts/tests/relay-integration.test.ts` |
| Background cmux integration tests | `VRCODE_CMUX_BINARY="$PWD/mac/.build/debug/vrcode-mac" bun test scripts/tests/cmux-integration.test.ts` |
| Kotlin / Swift integration on Android | `bun run check:relay:android --serial emulator-5554` |
| Regenerate the cmux action catalog | `bun run cmux:sync /path/to/cmux` |
| Check generated catalog freshness | `bun run cmux:sync /path/to/cmux --check` |
| Validate all delivered GLBs | `bun run assets:validate` |
| Render saved asset projects | `bun run assets:preview` |

`bun test` also checks the scripting policy. Native relay tests require the built Mac binary;
Android checks require a device, Android Platform Tools and the compiled instrumentation APK.
Bluetooth checks require a packaged Mac app and the relevant OS permissions and bonds. These
checks create isolated test credentials and stop only the service instance they start.

The asset renderer uses the standard Blender CLI, disables script auto-execution, and writes to
`artifacts/asset-previews/`. Editable `.blend` files are the asset sources; edit and export them
through Blender's UI. Asset validation prints current coverage as JSON and leaves the historical
Blender re-import reports intact.

## Development session

`vpx _dev` (`_dev.ts`) starts two processes in [mprocs](https://github.com/pvolok/mprocs)
(`dev/mprocs.yaml`). Press r on a process to restart it and q to quit:

| Process | Does |
| --- | --- |
| `mac` (`dev/mac.ts`) | Builds `vrcode-mac` (`swift build`, debug) and `xmux-bridge` (release, from `XMUX_DIR`), stages them in `mac-app/src-tauri/binaries/`, and runs VRcode Mac with `cargo tauri dev` on dev-server port 7271. Tauri restarts the app on Rust changes and serves `mac-app/ui/` with live reload. A change under `mac/Sources` rebuilds the service and restarts the app; if a build fails, the running copy stays. The service runs the AI agent runner from `mac/agent/vrcode-agent.ts` directly. |
| `quest` (`dev/quest.ts`) | Waits for a Quest: its USB connection if it has one, else Wi-Fi, or `--serial` / `ANDROID_SERIAL`. Then it sets up the adb tunnels (`quest-link usb` or `wifi`), reinstalls and relaunches the app (`vpx _quest`), and copies the Mac connection key (`quest-link pair`). Last, it follows the app's logcat by uid, so restarts stay in view. Restarting it reinstalls, which restarts VRcode on the headset. |

The session uses ports 7270 (mprocs control server), 7271 (Tauri dev server) and 7272–7274
(bridge, speech, relay). If any of them is taken, it refuses to start:

- `vpx _dev --force` quits an earlier session through mprocs, or stops whatever else holds the ports.
- VRcode Mac restarts a helper that is killed on its own, so `--force` stops the app itself.
- If that was the installed LaunchAgent copy, it is started again when the session ends.

Unlike the packaged app, the dev app is an unsigned debug build outside an app bundle:

- Bluetooth carriers are off, so connect the Quest over ADB or LAN.
- macOS attributes privacy grants to the terminal running the session.
- A Keychain-stored provider key may prompt before the dev service can read it.

`bun scripts/dev/mac.ts [service args]` runs the Mac side alone; its arguments go to `vrcode-mac`.
