# Project Environment

Repository scripts and their tests must use Bun/TypeScript exclusively; never run or add Python,
including embedded Blender scripts. See `AGENTS.md` and `scripts/README.md`. Run `bun test` and
`bun run assets:validate`; native Swift/Gradle toolchains remain in use for application builds.

Inspected with the `argent-environment-inspector` on 2026-11-01. Its initial result (before the
Quest app was added during the settings task):

```json
{
  "is_react_native": false,
  "is_native_android": false,
  "is_native_ios": false,
  "project_type": "Planned native Android Meta Quest application plus existing Swift macOS service",
  "target_platform": "Native Android / Meta Spatial SDK",
  "xmux_checkout": "../../leonsilicon/xmux",
  "gradle": "8.13",
  "agp": "8.11.1",
  "kotlin": "2.1.0",
  "spatial_sdk": "0.14.0",
  "JAVA_HOME": "/Users/leonsilicon/.proto/tools/jdk/temurin-17.0.20+8/Contents/Home",
  "ANDROID_HOME": "/Users/leonsilicon/Library/Android/sdk",
  "quest_serial": "2G0YC5ZG0L01ZS",
  "argent_available": true
}
```

The native Android Quest app now exists in `app/`. `settings-ui/` contains its Compose settings and
preferences without a dependency on the headset runtime, so UI tests can run on a regular Android
emulator. The Mac service remains in `mac/`. No React Native or Metro setup is needed.

Build with the toolchain above: `./gradlew :app:assembleDebug`. Run unit tests with
`./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest`. See
`docs/settings.md` for settings behavior and device verification.

The Mac side ships as `VRcode Mac.app`, a Developer ID-signed Tauri 2 menu-bar app in `mac-app/`. It
bundles `vrcode-mac` (from `mac/`) and `xmux-bridge` (from the xmux checkout). Build it with
`bun run mac:run --package-only` (needs `cargo install tauri-cli --version '^2' --locked`) and
install it at login with `bun run mac:install`. Rust unit tests: `cargo test` in
`mac-app/src-tauri`. See `docs/mac-app.md`.

Current environment inspector result (2026-11-01, floating dock task):

```json
{
  "is_react_native": false,
  "is_native_ios": false,
  "is_native_android": true,
  "project_type": "Native Kotlin/Compose Meta Quest app using Meta Spatial SDK, with Swift macOS companion service",
  "platforms": ["Meta Quest 3/3S (Android arm64)", "macOS companion", "Android emulator for isolated Compose UI tests"],
  "package_manager": "Bun 1.4.4",
  "build": "./gradlew :app:assembleDebug",
  "unit_tests": "./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest",
  "compose_ui_tests": "ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest",
  "application_id": "dev.improve.vrcode",
  "xmux_checkout": "../../leonsilicon/xmux",
  "argent_available": true
}
```

The bottom viewport-following dock uses the existing xmux panels and cmux screen identities.
See `docs/dock.md` for its controls, research, placement and verification.

## Crash triage

- Quest: `adb -s <serial> logcat -b crash -d` and `adb -s <serial> shell dumpsys dropbox --print
  data_app_crash` keep app crash stacks after the main log rotates (debug hand logs fill it in
  seconds). `DisplayStreamer` aborts in `app_process` are Android Studio's screen-sharing agent,
  not VRcode.
- Mac: `~/Library/Logs/DiagnosticReports/vrcode-mac-*.ips`. Privacy keys live in `mac/Info.plist`,
  embedded via `-sectcreate` in `mac/Package.swift`; a missing usage string is a TCC SIGABRT.
- `MainActivity` runs each feature's setup and tick through `guarded(...)`, and its coroutine scope
  has an exception handler; both report to `FailureLog` (logcat tag `Vrcode`, "failed; VRcode keeps
  running"). Search for that before assuming a feature is silently idle.
- Never `removeComponent` an ISDK component (e.g. `IsdkGrabbable`) from a panel; set it disabled.
  See `docs/dock.md`.
