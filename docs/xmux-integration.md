# Using xmux from vrcode (verified 2026-10-31)

Everything below was checked the night before the hackathon with a throwaway app outside the xmux
repo: composite build, debug and minified release APKs, `FakeBridge` on the test classpath, and the
new spatial API compiling from outside. On the Quest 3 (serial 2G0YC5ZG0L01ZS): xmux's own app
(minified release) paired over USB, showed a two-pane cmux screen over passthrough, and `hitTest`
returned the right pane, terminal and cell for rays at each pane, the header, the divider and past
the edge. Not checked on the headset: typing into a live cmux (cmux was not running; the bridge's
own integration tests cover input), hand/controller rays (gaze rays only).

## Gradle

vrcode lives at `ImproveLabs/vrcode`, xmux at `leonsilicon/xmux`, so the composite path is
`../../leonsilicon/xmux`. Copy xmux's wrapper (Gradle 8.13): `cp -R ../../leonsilicon/xmux/gradle/wrapper gradle/ && cp ../../leonsilicon/xmux/gradlew .`
Run `vp install` in the xmux checkout once (the terminal font comes from an npm package). JDK 17:
`export JAVA_HOME=~/.proto/tools/jdk/temurin-17.0.20+8/Contents/Home ANDROID_HOME=~/Library/Android/sdk`.

```kotlin
// settings.gradle.kts
pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories { google(); mavenCentral() }
}
rootProject.name = "vrcode"
includeBuild("../../leonsilicon/xmux")
include(":app")

// build.gradle.kts: the SAME AGP and Kotlin versions as xmux's gradle/libs.versions.toml
plugins {
  id("com.android.application") version "8.11.1" apply false
  id("org.jetbrains.kotlin.android") version "2.1.0" apply false
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// app/build.gradle.kts
android {
  compileSdk = 35
  defaultConfig { minSdk = 34; targetSdk = 34; ndk { abiFilters += "arm64-v8a" } }
  buildFeatures { compose = true }
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  packaging { resources.excludes.add("META-INF/LICENSE*") }
}
dependencies {
  implementation("dev.xmux:xmux-spatial:0.2.0-SNAPSHOT")       // any version; the checkout wins
  implementation("dev.xmux:xmux-connect-panel:0.2.0-SNAPSHOT") // optional: bridge picker + pairing UI
  implementation(platform("androidx.compose:compose-bom:2024.09.03"))
  implementation("androidx.compose.material3:material3")
  testImplementation(testFixtures("dev.xmux:xmux-client:0.2.0-SNAPSHOT")) // FakeBridge
}
```

`gradle.properties`: `android.useAndroidX=true`, `org.gradle.jvmargs=-Xmx4096m`.

Manifest: start from xmux's `apps/xmux-quest-app/src/main/AndroidManifest.xml` (INTERNET, hand
tracking permission and feature, `handtracking.version V2.0`, headtracking, passthrough, VR intent
category). vrcode adds `RECORD_AUDIO` for voice.

## Activity wiring

The activity forwards `registerFeatures`, `registerPanels`, `onSceneReady`, `onSceneTick` and
`dispatchKeyEvent` to `XmuxSpatial`; xmux's `apps/xmux-quest-app/.../MainActivity.kt` is a complete
example with the connect panel, pairing and a demo window. API for vrcode's input:

- `xmux.hitTest(origin, direction)`: pane and terminal under the pointer hand's ray (geometric
  intersection with each window's live pose and ISDK size; main thread; only xmux windows, so test
  vrcode's own panels first). `hit.terminal ?: xmux.focusedTerminal()?.id` is the voice target
  rule. A ray on a divider or the header has `pane == null`. Do not use `Scene.lineSegmentIntersect`
  for xmux windows: on the Quest it passed through a screen window and hit the panel behind it.
- `xmux.focus(hit.window, hit.pane)`: lock the target and keep hardware-keyboard routing in step.
- `session.terminal(id).write(text)` then `.keys(Key("enter"))`; Escape is `Key("escape")`.
- `xmux.follow(session, chrome = { screen -> WindowChrome(trailingSlot = { ... }) })` for the mic
  glyph; the slot reads Compose state, so flipping a `mutableStateOf` updates the header.

## Running it

VRcode's [background cmux controls](cmux-control.md) now cover the full pinned public operation
catalog through the Mac service, including pane/tab/workspace operations beyond xmux's input and
focus protocol. Open Actions on the Quest; these controls address the daemon directly without
focusing a Mac application. The [interaction inventory](cmux-interactions.md) records all native
keyboard/menu actions and the frontend/provider boundaries.

- The app's Connection settings now defaults to Automatic, using the secure Mac relay for
  both xmux and the vrcode service. It also offers explicit network, USB, Wi-Fi ADB and native
  Bluetooth carriers. See [connection setup](local-wireless.md) for pairing and platform setup.
- Wi-Fi ADB fallback: run `bun scripts/quest-link.ts wifi`, then choose Wi-Fi debugging on the
  Quest. Its dedicated endpoint is `127.0.0.1:27274`, forwarded to Mac relay port 7274. The older
  7272/7273 forwards remain available for legacy debug clients.
- Bridge from source: `cd ../../leonsilicon/xmux && vpr bridge:serve` (cmux session `main`, port
  7272); approve the headset with `cargo run -q -p xmux-bridged -- pair <code>`.
- Venue Wi-Fi that isolates clients: plug the Quest into the Mac, `adb reverse tcp:7272 tcp:7272`, and
  connect to `127.0.0.1:7272` (verified on an emulator; pairing survives the address change).
- No cmux at hand (or as a demo fallback): `xmux-bridge serve --replay
  packages/dev/improve/xmux/testing/fixtures/traces/split2panes.xmtrace --loop`.

## Window appearance (2026-11-01)

Settings → Appearance → Window style offers macOS traffic lights or Windows controls on the right.
The choice saves automatically and updates already mounted terminals through Compose state;
the terminal body, panel dimensions, loading state, header slots and control callbacks stay intact.

This uses the `WindowChrome.header` customization hook and public `WindowHeader` default renderer
added to the local xmux checkout. For an older xmux checkout, apply the corresponding patch once
from the vrcode root before building:

```sh
git -C ../../leonsilicon/xmux apply "$(pwd)/patches/xmux-custom-header.patch"
```

The settings and header renderers live in `settings-ui`, which can run its Compose instrumentation
tests without the Quest runtime. Existing control actions are preserved: a control with no callback
is decorative/disabled, just as the original traffic lights were.

Terminal color themes, text size, and cursor controls are also available on Appearance. They use
the additional `patches/xmux-terminal-appearance.patch` for render-only color overrides and local
font sizing. See [terminal appearance](terminal-appearance.md) for patch setup, bundled theme
sources, behavior, and verification.

## Notes for the day

- cmux was not running on the Mac on 2026-10-31; start it (session `main`) before `vpr bridge:serve`,
  or the bridge exits with "connecting to cmux session \"main\" (is cmux running?)".
- The bridge already has the Quest paired (state in `~/Library/Application Support/dev.xmux.bridge`),
  but pairing tokens live in each app's storage: the vrcode app pairs once on first connect.
- The pairing code shows on the headset; without wearing it, `vpx _quest-capture` in the xmux
  checkout takes a headset screenshot (it simulates wearing the headset).
- xmux's standalone app leaves its connect panel where the first window spawns, so the window covers
  it. vrcode should hide or move its own connect UI once `ConnectionState.Online`.
- Quest cold start to the first frame is about 15 s for a release build; the headset needs a lit
  room or Horizon OS will not start 6DoF apps. The battery was at 15% on the night before.
