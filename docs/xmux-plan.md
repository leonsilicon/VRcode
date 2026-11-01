# xmux plan

This is the implementation plan for xmux as a library-first, MIT-licensed project. It was split on
2026-10-30 from the research plan in the `xmux-playground` repository; everything about hand gestures,
voice input, radial menus and capture moved to vrcode's plan. Measurements and device recipes live in
`measurements.md`.

## Context

XMUX started as a comparison harness and became, in this session, a working Spatial SDK prototype (`quest/`): one or many cmux-style screens rendered as macOS-like floating windows over passthrough on the Quest 3, driven by a synthetic content source. The headset measurements are in `PLAN.md` (Status section) and `quest/README.md`.

The user now wants two products with a clean line between them:

- **xmux** (this plan): open source (MIT), library first. "cmux on VR": the cmux-tui daemon on the Mac is the only source of truth; xmux renders each cmux screen as a floating window over passthrough and forwards keyboard, pointer click/scroll, and keeps the default ISDK window move/resize. Embeddable in other Quest apps as a Maven dependency; also shipped as a basic standalone Meta Horizon Store app that is a pure cmux client (no local shell).
- **vrcode** (new, separate repo at `/Users/leonsilicon/Developer/github/ImproveLabs/vrcode`, currently empty): built on top of xmux; owns every custom hand gesture, microgesture, voice / speech-to-text, transcript card, radial hold menu, macros and screenshot capture. Nothing of that stays in xmux.

Decisions fixed by the user on 2026-10-30: pure cmux client (no on-device shell); MIT; a NEW clean public repo for xmux (`xmux-playground` stays as the research archive; `quest/` is the seed); input split = keyboard + mouse + default move/resize stay in xmux, everything else goes to vrcode.

Outcome of this plan: a repo layout, module/crate boundaries, a stable public Kotlin API that vrcode programs against, the Rust bridge contract, a test strategy that keeps a headset out of the loop for most of the work, packaging/publishing/CI, and the split of the existing `PLAN.md` into an xmux plan and a vrcode plan.

## Repo layout (new public repo `xmux`, MIT)

```
xmux/
  LICENSE  README.md  CONTRIBUTING.md
  docs/                    protocol.md, embedding.md, bridge.md, measurements.md
  packages/dev/improve/xmux/schema/xmux.proto   the single wire schema; Rust (prost + protox) and Kotlin (protobuf Gradle plugin) code is generated at build time
  Cargo.toml               Rust workspace (Mac side); members are the crates in packages/dev/improve/xmux/bridge/
    packages/dev/improve/xmux/bridge/proto   (xmux-proto)   generated protobuf types + 16-byte framing + LZ4; pure, no I/O; shared by bridge, tests, and any future bridge-side service
    packages/dev/improve/xmux/bridge/lib     (xmux-bridge)  library: cmux Rust SDK client -> screen/terminal state machine -> frame encoder; input decoder
    packages/dev/improve/xmux/bridge/daemon  (xmux-bridged) binary: TLS listener (tokio + rustls), Bonjour, pairing, config, logging; thin main() around xmux-bridge
    packages/dev/improve/xmux/bridge/view    (xmux-view)    dev tool: decodes a recorded frame stream into PNGs; the headset-less harness for bridge tests
  settings.gradle.kts      Gradle multi-module build (Kotlin); modules in packages/dev/improve/xmux/android/, found automatically
    xmux-core/             plain Android library, no Spatial SDK: CellGrid, row/run apply, TerminalFont, TerminalGridView (per-pane renderer), ScreenView (split-tree composition, dividers, tab strips, focus outline), key/mouse encoding
    xmux-proto/            protobuf messages generated at build time (protobuf-kotlin-lite)
    xmux-client/           plain library: TLS transport (OkHttp/JSSE), frame decode, LZ4, NsdManager discovery, pairing store, XmuxSession state + Flow events
    xmux-compose/          Compose chrome: TerminalWindow, WindowChrome with slots, traffic lights, spinner; no Spatial SDK
    xmux-spatial/          Spatial SDK 0.14 integration: XmuxSpatial, ScreenWindow entities, panel registration, layer/mesh tiering (12 layers max), ISDK move/resize, passthrough, hit testing
    preview/               phone/emulator app showing one screen in 2D from a recorded frame stream (today's quest/preview)
    app/                   the standalone "XMUX" store app: pairing screen + XmuxSpatial with defaults
    sample-embed/          minimal third-party-style app embedding xmux-spatial (what vrcode starts from); compiles against the published API only
  packages/                TypeScript tooling (Bun + Vite+), one package per script: Gradle runner, Quest install/stats/stress/capture, CI workflow sources
```

Dependency rules: `xmux-core` and `xmux-client` depend only on AndroidX, protobuf-kotlin-lite, lz4-java, OkHttp; `xmux-compose` adds Compose; only `xmux-spatial` imports `com.meta.spatial.*`. Each library publishes an AAR `dev.xmux:xmux-{proto,core,client,compose,spatial}`. vrcode depends on them by version in releases and through `includeBuild("../../leonsilicon/xmux")` during development. The bridge ships as a cargo crate plus a notarized universal macOS binary on GitHub Releases; the standalone app's pairing screen tells the user how to install it.

## Public Kotlin API (what vrcode programs against)

```kotlin
// xmux-client
class XmuxClient(context: Context) {
  fun discover(): Flow<BridgeAdvert>                           // NsdManager, _xmux._tcp
  suspend fun connect(target: BridgeTarget, pairing: PairingStore): XmuxSession
}
interface XmuxSession {
  val state: StateFlow<ConnectionState>                        // Connecting, Paired, Online, Stalled(ms), Reconnecting, Closed
  val topology: StateFlow<Topology>                            // workspaces -> screens -> layout tree -> panes -> tabs -> terminalIds
  val events: Flow<SessionEvent>                               // ScreenAdded/Removed/Renamed, FocusChanged(paneId), AttachState(terminalId), Latency(rttMs)
  fun terminal(id: TerminalId): TerminalHandle                 // attaches on first use
  fun focusDirection(screen: ScreenId, dir: Direction)         // pane.focus_direction
  fun neighbor(pane: PaneId, dir: Direction): PaneId?          // pane.neighbor.get
  fun close()
}
interface TerminalHandle : AutoCloseable {
  val grid: StateFlow<CellGrid>; val cursor: StateFlow<Cursor>
  fun write(text: String); fun keys(vararg k: Key); fun mouse(e: MouseEvent)   // terminal.input.*
  fun resize(cols: Int, rows: Int)                             // terminal.viewer.resize on this lease, opt-in
}

// xmux-core / xmux-compose
class ScreenView(context) : View       // takes a ScreenModel (layout tree + TerminalHandles); draws panes, dividers, tabs, focus
@Composable fun TerminalWindow(screen: ScreenModel, chrome: WindowChrome)
class WindowChrome(title, leadingSlot: @Composable () -> Unit, trailingSlot: @Composable () -> Unit, onClose, onMinimize, onZoom)

// xmux-spatial
class XmuxSpatial(activity: AppSystemActivity, session: XmuxSession, config: SpatialConfig) {
  fun registerFeatures(): List<SpatialFeature>                 // VRFeature(ISDK) + ComposeFeature unless the host supplies them
  fun registerPanels(): List<PanelRegistration>                // host forwards from its own registerPanels()
  fun onSceneReady(); fun onSceneTick()                        // host forwards; passthrough on, spawn policy, tiering
  val windows: StateFlow<List<ScreenWindow>>
  fun mount(screen: ScreenId, placement: Placement = Placement.InFrontOfHead): ScreenWindow
  fun unmount(screen: ScreenId)
  fun hitTest(origin: Vector3, dir: Vector3): Hit?             // window -> pane -> terminalId + cell, for vrcode's rays
}
class ScreenWindow { val screen: ScreenId; val entity: Entity; val pose: StateFlow<Pose>; val grabState: StateFlow<GrabState>
  val sizeM: StateFlow<Vector2>; val tier: StateFlow<Tier>; val chrome: WindowChrome; fun focus(); fun setPose(p: Pose) }
```

`SpatialConfig` carries font px, cols/rows policy, max layers (12), tier distances, chrome colors, and `Placement` options (in front of head, arc, body-locked follow). vrcode's needs map one-to-one: ray-to-pane (`hitTest`), focus navigation (`focusDirection`), typing into a specific terminal (`terminal(id).write`), window pose stream (`ScreenWindow.pose`), header slots (`WindowChrome`), grab/resize reactions (`grabState`, `sizeM`). Nothing in xmux reads hand joints, microgestures, audio, or the camera.

## Mac bridge (Rust) contract

Wire schema (`packages/dev/improve/xmux/schema/xmux.proto`; protobuf was chosen over FlatBuffers on 2026-10-30 because both ecosystems have official, build-integrated generators with no hand-installed compiler, and our frames are small enough that varint decoding is noise next to rendering): `Run{text, fg, bg, attrs, underline, width_hint}`, `Row{row, runs}`; frames `hello/welcome`, `topology` (workspaces, screens with a `LayoutDocument` mirror and focused pane), `rows` (terminal id, generation, sequence, dirty rows, LZ4), `cursor`, `attach_state`, `input` (text/keys/mouse with idempotency key), `resize`, `focus_direction`, `checksum`, `ping/pong`, `stats`. 16-byte header: type, length, terminal/screen id, generation, sequence. No audio or caption frames: vrcode's bridge-side extras (speech, clipboard) are a second service on its own port that depends on `xmux-proto` for framing.

cmux mapping in `xmux-bridge`: topology from `session.events`; per visible terminal `terminal.attach(cols, rows, read_only=false)` -> `RenderSnapshot`/`RenderPatch` -> rows (`full_reset` bumps generation); input via `terminal.input.write/keys/mouse`; `client.sizing.set(enabled=false)` at connect so a headset never shrinks the Mac grid; `terminal.viewer.resize` only for opted-in windows; `terminal.viewer.release` on unmount/disconnect; `pane.focus_direction` and `pane.neighbor.get` pass through. Policy: batch per 16 ms, snapshot-on-gap by reopening the attach stream.

Transport in `xmux-bridged`: one TLS stream per headset (self-signed cert pinned after a six-digit pairing code shown by the app and typed on the Mac; token in EncryptedSharedPreferences), Bonjour `_xmux._tcp` with the cert fingerprint in TXT. No USB assumption.

## What carries over from `quest/`

- As-is into `xmux-core` (package `dev.xmux.core`): `CellGrid.kt`, `TerminalFont.kt`, `TerminalGridView.kt` (frame-wide glyph batching; becomes the per-pane renderer).
- Into `xmux-compose`: `ui/TerminalWindow.kt`, header refactored into `WindowChrome` with slots.
- Refactored: `app/MainActivity.kt` splits into `XmuxSpatial` (panel registration, spawn-in-front-of-head and arc placement, 12-layer cap with mesh fallback, ISDK components, passthrough) and `packages/dev/improve/xmux/android/app` (launcher activity + pairing screen). The stress extras (`count/active/render/fps`) are driven by `vpx _quest-stress` against `sample-embed`.
- `preview/PreviewActivity.kt` moves to `packages/dev/improve/xmux/android/preview`, fed by a recorded frame stream instead of `DemoScreen`.
- Dropped: `DemoScreen.kt` (survives only as a test fixture generating rows), `install-quest.sh` (replaced by `vpx _quest-install`), the `loading` timer (driven by `AttachState` events instead).
- New: `ScreenView`, `xmux-client`, topology model, hit testing, tiering, pairing UI.

## What moves out of PLAN.md into vrcode's plan (verbatim)

The whole "Gestures" subsection (signal table, per-hand mapping, radial hold menu, rules, Capture), the whole "Voice input" subsection, the Quest-client "Gestures" and "Voice" bullets, the bridge "Voice" line, the hand-rolled items gesture routing + dictation state machine, own hand tracker/JNI, transcript card, radial menu, capture pipeline, audio capture, and milestone M2.5. The body-locked "lazy follow" placement stays in xmux as a `Placement` option.

## Test strategy ("complete with tests", headset mostly out of the loop)

Principles: one fixture set consumed by the Rust bridge, the Kotlin core and the device smoke; the cmux daemon is a recorder, not a test dependency; the headset only answers what nothing else can; `DemoScreen` is a sample, never test data.

Fixtures `packages/dev/improve/xmux/testing/fixtures/traces/*.xmtrace`: JSON lines, one raw `cmux.protocol/2` item per line (`{t, stream: "events" | "attach:<terminalId>", item}`), plus `packages/dev/improve/xmux/testing/fixtures/expected/<name>.grid.json` (cols, rows, cells with fg/bg/attrs/text, cursor). Recorded once by `packages/dev/improve/xmux/bridge/lib/src/bin/record.rs` against a real daemon. Scenarios: `static80x24`, `scroll`, `styles`, `unicode`, `cursor`, `resize80to120`, `split2panes`, `tabs`, `full_reset`, `burst`.

Rust bridge:
- Unit (`cargo test`, `insta` snapshots, `proptest`): run -> cell conversion (wide lead/tail, graphemes, attrs, underline, default colors) in `cells.rs`; dirty-row coalescing invariants in `coalesce.rs`; protobuf + LZ4 round trips with pinned wire-byte goldens in `xmux-proto`'s `framing.rs` tests; reconnect/snapshot-on-gap, generation bump, lease release, `client.sizing.set(false)` once per connection, against a scripted `FakeCmux` in `tests/reconnect.rs`.
- Integration (`cargo test --features daemon`, one CI job on a macOS runner): start `npx cmux server start --session xmux-test`; attach -> first item is a snapshot; `input.write("echo hi\n")` shows up in a patch; `viewer.resize` changes metadata; a split shows up in `session.events` as a `split` node; release on disconnect. Each test owns a session name, 30 s timeout.
- Conformance: every trace replayed through the bridge, decoded, compared to `expected/*.grid.json`.

Kotlin core (`xmux-core`, `xmux-client`, JVM unit tests with JUnit5/kotlin.test):
- `CellGridTest`, `CellStyleTest` (inverse/dim/underline resolution lifted out of the view into a pure `CellStyle`), `RowApplierTest` (generation mismatch ignored, `full_reset`, clipping, gap -> `needsSnapshot`), `ConformanceTest` parameterized over every trace (grid == expected), `KeyEncoderTest` / `PointerEncoderTest` (Android KeyEvent -> `terminal.input.keys`/`write`; pointer -> `terminal.input.mouse` with cell hit test), `BridgeClientTest` / `ReconnectTest` on an okio `Buffer` fake socket.
- Robolectric (`@GraphicsMode(NATIVE)`, sdk 34): `TerminalGridViewDrawTest` asserts draw-call counts (upper bounds per trace, e.g. `styles` <= 60, `burst` <= 220), batch keys, cursor last, zero allocations in `onDraw` after warm-up.
- Instrumented (Gradle managed device `pixel6Api34`): `GoldenRenderTest` renders each conformance grid through a `TestHostActivity` at density 1 into a 740x557 bitmap and compares to `packages/dev/improve/xmux/testing/fixtures/golden/<trace>.png` (bundled font makes it deterministic); `-Pxmux.updateGoldens` regenerates.
- Compose (`compose-ui-test-junit4` on Robolectric): header has traffic lights, centered title, spinner iff attaching, slots render.
- `FakeBridge` ships in `xmux-client`'s `testFixtures` (vrcode uses it too): implements the same `BridgeConnection` interface as the TLS client, replays a trace at recorded or max speed, `advanceTo(t)`, records outbound input frames.

Spatial module (`xmux-spatial`): JVM tests only for placement math (`WindowLayout`: head pose + N -> poses; today's stale-metrics bug becomes a test: spacing comes from `PanelSpec`, never from a view). Everything else runs as a device smoke a `_quest-smoke` package under `packages/dev/improve/xmux/configs/quest/smoke`: refuses to run if `GuardianDialogActivity`/`ClearActivity` is on top; never sends fake proximity broadcasts; installs, launches with `--es scenario <trace> --ei count N` (the app replays through `FakeBridge` when `scenario` is set), waits for `XMUX: placed`, samples 20 s of `dumpsys gfxinfo` (after reset), app-pid `VrApi` lines, `XMUX` metric lines and `top`, captures through `com.oculus.metacam/.capture.CaptureService`, writes `results/smoke/<date>-<scenario>-N.json` + capture, compares to thresholds.

Thresholds (release build, 90 Hz, from today's measurements):

| Scenario | Pass if |
|---|---|
| 1 window, `burst` at 30 Hz | Stale <= 1/s, App <= 3 ms, HWUI p50 <= 8 ms, p99 <= 14 ms, draw calls <= 200 |
| 16 windows, 4 active | LCnt <= 16, Stale <= 2/s, HWUI p50 <= 10 ms, GPU <= 60% |
| 32 windows, 4 active | alive 60 s, Stale <= 5/s, GPU <= 85% (today 78%) |
| idle window (`static`) | zero `framesDrawnTotal` growth over 10 s |

Regression tracking: `results/smoke/index.csv` (date, commit, scenario, N, fps, stale, LCnt, app_ms, gpu, hwui_p50, hwui_p99, draw_calls, rss); `vpx _quest-smoke-report.py` prints the delta vs the previous commit and fails on > 20% p99 or stale-frame regression; the VrApi line parser has its own unit test.

## Packaging, publishing, CI (facts verified 2026-10-30 by the packaging pass)

Android libraries:
- Gradle project names equal artifact names (`xmux-proto`, `xmux-core`, `xmux-client`, `xmux-compose`, `xmux-spatial`) so vrcode's `includeBuild("../xmux")` substitutes `dev.xmux:<name>` with no manual `dependencySubstitution`. Both builds must share AGP/KGP versions.
- Publishing: Maven Central through the Central Portal (OSSRH is gone) with `com.vanniktech.maven.publish` 0.35.0 (matches Gradle 8.13 / AGP 8.11.1; 0.37 needs Gradle 9 / AGP 8.13, untested against Spatial SDK 0.14). `publishToMavenCentral(automaticRelease = true)`, `signAllPublications()`, POM with MIT license/scm/developers; credentials via `ORG_GRADLE_PROJECT_mavenCentral*` and `ORG_GRADLE_PROJECT_signingInMemoryKey*` secrets. Group id `dev.xmux` needs a DNS TXT verification on `xmux.dev`; fallback `io.github.leonsilicon` (auto-verified). Decide before the first publish.
- Scopes: `xmux-spatial` declares the Spatial SDK as `api` (SDK types are part of the ABI, pins one SDK version for vrcode); Compose is `api` in `xmux-compose`; `xmux-core`/`xmux-client` stay plain Android. Entry points in `xmux-spatial` are non-composable, so a host that never uses header slots needs neither the Compose compiler plugin nor `com.meta.spatial.plugin` (that plugin is only the Spatial Editor glXF exporter; the prototype builds without it).
- Consumer constraints to document: Spatial SDK AARs ship arm64-v8a only (`ndk { abiFilters += "arm64-v8a" }`), minSdk 34, Spatial SDK 0.14 pulls okhttp 4.9.1 / kotlinx-serialization 1.6.3 / compose-ui 1.7.5 at compile scope and bundles consumer ProGuard rules.
- API stability: `kotlin { explicitApi() }`, `@RequiresOptIn ExperimentalXmuxApi`, binary-compatibility-validator 0.18.2 at the root (`apiCheck` in CI, `apiDump` diffs reviewed in PRs; app/preview/sample ignored), Dokka 2.2.0 aggregated to GitHub Pages on release. SemVer pre-1.0: `0.MINOR.PATCH`, MINOR may break with a CHANGELOG entry; core/client/compose/spatial and the bridge share one tag, and the handshake carries `protocol_version` so mismatches fail cleanly.

Rust bridge:
- Cargo workspace at the repo root (crates in `packages/dev/improve/xmux/bridge/`) with `rust-toolchain.toml` 1.95.0, edition 2024, `rust-version = "1.91"` (cmux's own pin). cmux SDK is a git dependency (`cmux = { package = "cmux-sdk", git = "https://github.com/manaflow-ai/cmux", rev = "<pinned sha>" }`; crates.io has only a placeholder); commit `Cargo.lock`, bump with `cargo update -p cmux-sdk --precise`, cache `~/.cargo/git` in CI.
- Distribution: cargo-dist 0.33 (`axodotdev/cargo-dist`, alive again) with `targets = ["aarch64-apple-darwin", "x86_64-apple-darwin"]`, shell installer, GitHub attestations. 0.x ships ad-hoc signed binaries (curl/Homebrew downloads carry no quarantine); Developer ID signing + `notarytool` lands at 1.0 through a custom publish job. Hand-maintained tap `leonsilicon/homebrew-xmux` with a `service do ... end` block so `brew services start xmux-bridge` works; `xmux-bridge service install` writes a user LaunchAgent (`~/Library/LaunchAgents/dev.xmux.bridge.plist`, per-user because the cmux socket is per-user); `serve` is the foreground entry point.

GitHub Actions:
- `rust.yml` (paths `packages/dev/improve/xmux/bridge/**`, `Cargo.*`, `packages/dev/improve/xmux/schema/**`): `macos-latest` for fmt, clippy `-D warnings`, `cargo test`, then the daemon integration job (`actions/setup-node`, `npx cmux@<pinned> server start --session ci`, wait for the socket, `cargo test -p xmux-bridge --features daemon`, stop). `ubuntu-latest` only for `cargo check -p xmux-proto`.
- `android.yml`: `ubuntu-latest`, JDK 17, `gradle/actions/setup-gradle`: assemble release AARs + app, `testReleaseUnitTest`, `lint`, `apiCheck`; optional emulator job with `reactivecircus/android-emulator-runner@v2` (API 34, x86_64, KVM) running `:xmux-core:connectedDebugAndroidTest` only (Spatial code cannot run on x86_64).
- `release-android.yml` on `v*` tags: `publishToMavenCentral`, `dokkaGenerate` + Pages deploy. `release.yml`: dist-generated plan/build/host/announce plus the tap bump; `dist generate --check` in `rust.yml` keeps them in sync.

Horizon Store (standalone app): App Lab is gone, everything is the Meta Horizon Store with release channels (ALPHA/BETA/RC for testers, Production for review). Manifest: targetSdk 34 (mandatory), `android.hardware.vr.headtracking` required with `android:version="1"`, `com.oculus.feature.PASSTHROUGH` required, hand tracking permission + feature, `com.oculus.supportedDevices="quest3|quest3s"`, `com.oculus.intent.category.VR` launcher filter, `excludeFromRecents="true"`, not debuggable, `installLocation="auto"`, v2-signed release build with a real upload keystore from secrets, `isMinifyEnabled = true`, 64-bit only, `versionCode` from the tag.

## Milestones

| # | Scope | Gate |
|---|---|---|
| W0 (days) | New repo skeleton: license, Gradle multi-module with BCV/explicitApi/publishing wired, root Cargo workspace, `packages/dev/improve/xmux/schema/xmux.proto` with build-time Rust + Kotlin codegen, `packages/dev/improve/xmux/testing/fixtures/`, both CI workflows | CI green on the wired skeleton; schema round-trips in both languages |
| M0 bridge (1 wk) | cmux SDK glue, `cells.rs`/`coalesce.rs`/`session.rs` with unit tests, daemon integration job, recorder, the 10 traces + expected grids, `xmux-view` PNG dump | every trace replays to its expected grid; input echo and resize pass against the real daemon; Mac grid never resized by a viewer |
| M1 one live screen (1.5 wk) | `xmux-core` (CellGrid, CellStyle, RowApplier, ScreenView, encoders), `xmux-client` (TLS, decode, discovery, pairing store, FakeBridge testFixtures), `xmux-compose` chrome, `preview` fed by traces or the live bridge over Wi-Fi, `xmux-spatial` showing one screen as a layer over passthrough, a `_quest-smoke` package under `packages/dev/improve/xmux/configs/quest/smoke` | conformance parity (Kotlin grid == Rust expected for every trace); goldens stable across two CI runs; smoke thresholds pass for 1 window; a real cmux session with vim in a split renders and echoes typing end to end |
| M2 multi-screen (1.5 wk) | every screen in the session mounted, focus follows cmux, keyboard + pointer forwarded, opt-in grid resize, tiering keeps layers at 12, reconnect/stall recovery, `sample-embed` compiles against the published API only | 32 windows with 4 active: 90 fps, stale <= 5/s, GPU <= 85%; 200 ms stall recovers in one snapshot; 30 min soak on device |
| M3 standalone + release (1 wk) | pairing flow and settings in `app`, store manifest/signing/minify, Maven Central release of the four AARs, dist release of the bridge + tap, docs | vrcode consumes `dev.xmux:xmux-spatial` from Maven and reaches today's prototype parity without patching xmux; store release-channel build accepted |

## Splitting the current PLAN.md and setting up the two repos

1. New repo `leonsilicon/xmux` on GitHub, local path `/Users/leonsilicon/Developer/github/leonsilicon/xmux`; move this playground to `/Users/leonsilicon/Developer/github/leonsilicon/xmux-playground` to match its remote (one `mv`; this session's memory directory is keyed by the old path, so the memory index gets a pointer note). Seed the new repo with `quest/` (as listed above), `packages/dev/improve/xmux/assets/fonts`, and the libghostty facts only as a doc footnote.
2. New `docs/PLAN.md` in the xmux repo = current PLAN.md minus the vrcode sections, rewritten around the module layout, API, bridge contract, tests, packaging and milestones above (this file is the draft).
3. `/Users/leonsilicon/Developer/github/ImproveLabs/vrcode/PLAN.md` = the moved sections verbatim (Gestures, Voice input, Capture, M2.5, the hand-rolled items listed earlier) plus a header stating: depends on `dev.xmux:xmux-spatial` (composite build during development), uses `XmuxSpatial.hitTest`, `XmuxSession.terminal(id).write`, `focusDirection`, `ScreenWindow.pose`, `WindowChrome` slots, `FakeBridge` from `xmux-client` testFixtures; its bridge-side speech/clipboard service is a separate Rust crate depending on `xmux-proto`.
4. The playground's PLAN.md stays untouched as history, with one line at the top pointing to both new plans.

## Verification

- `cargo test` and `cargo test --features daemon` at the repo root (the latter with a local `npx cmux server start --session dev`).
- `./gradlew test apiCheck lint` at the repo root; `./gradlew :xmux-core:pixel6Api34DebugAndroidTest` for goldens.
- `preview` on the emulator with `--es scenario split2panes` (argent screenshot), then with the live bridge over Wi-Fi.
- `vpx _quest-stress` for 1/16/32 windows on the Quest (Wi-Fi adb at `10.10.90.253:5555` while on the cafe network), results in `results/smoke/`.
- End to end: cmux-tui on the Mac with vim in a split; the Quest shows it, typing from the headset's keyboard echoes in the TUI; `sample-embed` builds against the published artifacts only.

## Verified facts the plan relies on

**libghostty / Ghostty (2026-10-29)**
- Ghostty v1.4.0 (released 2026-10-27) requires Zig 0.17.0. libghostty-vt officially builds and is CI-tested for Android. Cross-compiled here with `zig build -Demit-lib-vt -Dtarget=aarch64-linux-android.29` and NDK 27.2 in 98 s, no patches: `.so` links bionic libc, 16 KB page aligned, 211 exported symbols. Smoke test on an arm64 Android emulator passed (DSR reply via write-PTY callback, CJK and ZWJ emoji cells, per-row dirty tracking, 8 MiB of colored scrollback parsed in 96 ms). Evidence: `results/ghostty-vt/`, binaries in `.local/ghostty-vt-android-arm64/`.
- The libghostty-vt C API is pre-1.0 with one more ABI break announced; libghostty 1.0 is planned before Ghostty 1.5 (March 2027).
- Ghostty's GPU renderer is not a library (desktop OpenGL 4.3 or Metal, coupled to the app runtime). Upstream declined an Android app port and is cool on GLES. Its cell shaders (~500 lines of MIT GLSL) are a design reference only.
- With cmux-tui as the host, libghostty-vt runs inside cmux; our Android build is a verified fallback for a future Mac-less mode only.

**cmux (manaflow-ai/cmux HEAD 6bb4499, 2026-10-29)**
- Two products: the macOS GUI app (Swift; local terminals hosted in-process; remote views via a private, account-bound mobile RPC; local socket exposes text only) and `cmux-tui`, a Rust mux/daemon with TUI, web frontend and a private native macOS frontend (cmux-lite).
- cmux-tui hierarchy: session -> workspaces -> screens -> split tree -> panes -> tabs -> terminals. A screen owns one binary split tree (`split` with direction+ratio, `leaf` pane with tabs, `stack`, `viewport`). Closing tabs/panes/screens detaches views; only `terminal.close` ends a terminal.
- Public API `cmux.protocol/2` over Unix socket (JSON lines) or WebSocket: `session.events` (typed snapshot + ordered deltas; screen snapshots carry their complete `LayoutDocument`), `terminal.attach(cols,rows,read_only)` -> `attachment_lease`, stream items `RenderSnapshot`/`RenderPatch` (rows of runs: text, resolved fg/bg, attrs, underline style, width hints; cursor; `full_reset`), `terminal.input.write/keys/mouse` (idempotent), `terminal.viewer.resize(lease,cols,rows)`, `terminal.viewer.release`, `client.sizing.set(enabled,exclusive)`, `pane.focus_direction{left,right,up,down}`, `pane.neighbor.get`.
- Official SDKs: C++20 (no third-party deps, `cmux::Client::connect()` discovers the socket), Rust, TypeScript, Python, Go, Java, Zig. The raw protocol v12 render mode used by the old repo adapter is private.
- Daemon available via npm (`npx cmux server start --session <name>`).

**Quest 3 (device queried 2026-10-29; Khronos OpenXR inventory 2026-10-08)**
- Android 14, OpenGL ES 3.2, Adreno Vulkan, 4 KB pages. Compositor layer cap 16.
- Runtime supports `XR_EXT_hand_interaction`, `XR_FB_hand_tracking_aim`, `XR_META_hand_tracking_microgestures`, `XR_META_virtual_keyboard`, `XR_FB_passthrough`, `XR_KHR_android_surface_swapchain`, `XR_EXT_hand_tracking_data_source`, `XR_EXT_palm_pose`. `XR_META_boundary_visibility` was not in the Quest 3 inventory entry (verify on device).

**Meta Spatial SDK (0.14.0, released 2026-09-11; checked 2026-10-30 against Meta's docs mirror, the samples repo and the Maven AAR)**
- Kotlin ECS framework on Android; Vulkan renderer underneath; custom GLSL shaders compiled with glslc. Panels host Android Views, Compose, Activities or Intents (`LayoutXMLPanelRegistration`, `ViewPanelRegistration`, `ComposeViewPanelRegistration`, `ActivityPanelRegistration`), or a raw `Surface` (`VideoSurfacePanelRegistration`, direct-to-surface into a compositor layer, no custom shaders; `ReadableVideoSurfacePanelRegistration` with post-processing).
- Panels render as compositor layers by default (`PanelRenderMode.Layer`, blend modes OPAQUE/MASKED/ALPHA_BLEND with feathering) or as meshes (`PanelRenderMode.Mesh`, not recommended for text). Quest supports 16 compositor layers per frame; the app manages which panels get layers. Known issues: readable media panels in mesh mode still create a layer (`layerConfig = null` workaround); panels above ~2064×2208 px crop; debug builds are slow.
- Interaction SDK is the default input system since 0.8: ray, pinch and direct touch from hands and controllers are "translated into Android touch events" for panel content; `IsdkGrabbable` (move/rotate/scale, movement types such as AxialBillboard) and `IsdkPanelResize` (Simple/Relayout/None, min/max, aspect) with `IsdkPanelGrabHandle` defining the grabbable region; pointer events via `InputListener.onPointerEvent` (select/scroll/grab semantics, handedness).
- `MicrogesturesSystem.addListener` delivers `MicrogestureBits` (per hand: swipe left/right/forward/backward, tap thumb). `AvatarBody` exposes head and hand entities; `Controller` components carry pinch as button state; low-level body tracking (84 joints incl. hands) is an experimental opt-in.
- Passthrough: `scene.enablePassthrough(true)` with the skybox hidden; passthrough LUTs; MRUK for scene understanding.
- Raw OpenXR access exists: `Scene.getOpenXrInstanceHandle()`, `getOpenXrSessionHandle()`, `getOpenXrGetInstanceProcAddrHandle()` are public, and `SpatialFeature`/`VrActivity.registerRequiredOpenXRExtensions()` adds extensions at instance creation (the body-tracking sample uses it). The guides do not document the handle getters. Limits: the SDK owns the frame loop, `xrEndFrame`, the layer list and the action sets, so an app can call extension functions through JNI but cannot submit its own composition layers outside the SDK's `SceneQuadLayer`/`SceneSwapchain` API or attach its own action set. The native library references, among others, `XR_EXT_hand_interaction`, `XR_FB_hand_tracking_aim`, `XR_META_hand_tracking_microgestures`, `XR_FB_passthrough`, `XR_META_passthrough_preferences`, `XR_META_environment_depth`, `XR_KHR_android_surface_swapchain`, `XR_FB_android_surface_swapchain_create`.
- Tooling: Android Studio on macOS (Otter 2025.2+ for the simulator workflow), Meta Spatial Simulator on Apple Silicon (macOS 12+), Data Model Inspector, OVRMetrics, MQDH layer counts, Horizon OS UI Set (Compose components). Hot reload applies to Spatial Editor compositions, not Kotlin.
- Prior evidence in this repo: `clients/spatial-kotlin` (View panels, dirty-row Canvas, ISDK grab/resize with AxialBillboard, passthrough, mesh/layer toggle, 72 Hz request) launched on the Quest with working hand move/resize; the owner reported steadier text in layer mode and flickering borders, which the docs attribute to blend/feathering settings.

**StereoKit (checked 2026-10-30; now the fallback stack)**
- Two lines: v0.3.11 (stable, released 2025-06-28; master untouched since a March 2026 workflow tweak; GLES/OpenGL/D3D11 renderer) and 0.4 (develop branch, last merge 2026-10-28; NuGet `0.4.0-preview.3902`, continuous prereleases; no stable 0.4 tag, no changelog or migration guide; preview docs at https://stereokit.net/preview/ generated from develop).
- 0.4 replaced the renderer with `sk_renderer` ("Vulkan 1.1 renderer targeting Android/Linux/Windows/macOS ... core goal is performance and compatibility on XR standalone headsets"; HLSL compiled by `skshaderc` to SPIR-V; glslang removed 2026-10-28). `openxr_platform.h` defines `XR_USE_GRAPHICS_API_VULKAN` unconditionally; no GL/GLES code remains; `backend_opengl_*`/`backend_d3d11_*` removed; `backend_vulkan_get_instance/physical_device/device/queue/queue_family_index`, `queue_lock/unlock`, `request`, `get_function`, `get_frame_fence_fd` added.
- 0.4 wraps its own OpenXR swapchain `VkImage`s with `tex_set_surface(tex, VkImage, tex_type_rendertarget | tex_type_attachment_internal, VkFormat, w, h, layers, 1)` (`openxr_view.cpp:526`, `texture.cpp` handles attachment layouts), and exposes `render_blit(to_rendertarget, material)`, `tex_create_rendertarget`, `render_to`, `tex_set_colors`, `shader_create_mem/file`, `material_set_texture`, `compute_*` and `compute_buffer_*`. So an app can draw into its own quad-layer swapchain images through StereoKit materials with no raw Vulkan.
- 0.4 API changes vs 0.3.11 (C headers): 78 functions removed, 290 added in `stereokit.h`; 31 removed, 17 added in `stereokit_ui.h`. Every hook this plan uses survives unchanged: `backend_openxr_composition_layer`, `ext_request/ext_enabled/get_function/get_session/get_space/get_instance`, `add_callback_pre_session_create`, `ui_handle_begin` (now with an `opt_ref_scale` parameter for two-hand scaling), `ui_window_begin`, `ui_button_round`, `ui_enable_far_interact`, `ui_set_element_visual`, `input_hand`, `tex_set_surface`, `render_set_clear_color`, `mesh_draw`, `ui_text`, `ui_input`. Removed: `render_enable_skytex` (use `render_set_skybox_visible(false)`), `solid_*` physics, old `time_*` names, `text_size*`.
- 0.4 adds a generalized interactor system (`interactor_create` with point/line types, poke/grip/pinch events, `interactor_get_focused`; default hand interactors = aim, pinch, poke), frame pacing, a VRAM allocator, runtime texture compression, `meta_environment_depth` (passthrough occlusion), spatial entities, haptics, a new text-input API, `XR_KHR_maintenance1`, and 16 KB page alignment for Android in CMake. It binds `XR_EXT_hand_interaction` (`input_profiles.cpp`). Passthrough is still not built in (same hook path as 0.3).
- Neither version exposes a public API to add OpenXR action sets or bindings (`oxri_register_profile` is internal), so binding the Meta microgesture paths needs a small source patch (we build from source) or a joint-based detector.

**Rust stack (checked 2026-10-30)**
- `openxr` 0.22.0 (Sept 2026; repo pushed 2026-10-23; 340 stars; Apache-2.0; ~900k downloads): "high-level, mostly-safe OpenXR bindings", generated from registry 1.1.58; static Khronos loader or linked loader (Meta's `libopenxr_loader.so` on Android); typed Vulkan/GLES session bindings; safe wrapper for `XR_FB_passthrough`; generated support for `XR_EXT_hand_interaction`, `XR_META_hand_tracking_microgestures`, `XR_KHR_android_surface_swapchain`, `XR_META_virtual_keyboard`, `XR_META_environment_depth`, `XR_FB_hand_tracking_aim`. Missing: `XR_META_boundary_visibility`.
- `stereokit-rust` 0.4.0-alpha.25 on crates.io (Aug 2026), alpha.26 in git (pushed 2026-10-29); MIT; single maintainer (~745 of ~755 commits), 100 commits since April 2026, 11 stars, ~11k downloads, 1 open / 2 closed issues. Builds StereoKit from a pinned develop submodule via `cmake-rs` (submodule at 2026-09-04, ~8 weeks behind develop HEAD); Android via `android-activity` 0.6 with a documented Quest workflow and July 2026 Quest demo logs; 2,498 public functions, 33k doc lines, 92 tests, only 8 `pub unsafe fn` (raw-pointer data setters, `Tex::set_native_surface`, hardware-buffer import, perception anchors). Wrappers exist for everything this plan uses: `BackendOpenXR::{instance,session,space,ext_enabled,get_function}`, `BackendOpenXR::add_composition_layer<T>(&mut T, sort_order)` (safe generic), `BackendVulkan::{instance,device,queue,queue_lock}`, `Renderer::blit`, `Interactor::create`, UI handles/windows/buttons, skybox visibility. Lag to watch: its `set_native_surface` still carries an `owned` parameter where the C header now takes `multisample`; it pins `openxr-sys` 0.13 while `openxr` 0.22 uses 0.14 (use matching versions or convert types).
- Pure-Rust XR without StereoKit was evaluated and rejected: `openxr` + `wgpu`/`ash` (interop example `philpax/wgpu-openxr-example`, updated 2026-07) or Bevy (`bevy_mod_openxr`/`bevy_mod_xr` 0.6.0, June 2026, `awtterpip/bevy_oxr` 423 stars) give an XR session and rendering but no hand-interaction/UI toolkit comparable to StereoKit's, which puts the hand-rolled interaction code back.
- Mac bridge crates: cmux's official Rust SDK lives in-repo (`cmux-tui/bindings/rust`, crate `cmux-sdk` 1.0.0 exporting `cmux`, Rust 1.88, blocking API); crates.io has only a `0.0.0-bootstrap` placeholder and an unrelated `cmux` crate, so depend on it by git at the pinned cmux commit. `core-text` 22.0 (servo, May 2026), `prost` 0.14 + `protox` 0.10 (pure-Rust protobuf compiler, no protoc), `lz4_flex` 0.11, `opus` 0.4 (Aug 2026), `tokio` 1.54, `rustls` 0.23, `voice_activity_detector` 0.2 (Silero), `whisper-rs` 0.16 / `sherpa-rs` 0.6 as recognizer alternatives, `objc2-speech` 0.3 (SFSpeechRecognizer only; `SpeechAnalyzer` is Swift-only and needs a small Swift shim). Headset audio: `oboe` 0.6.1 (2024, stable), `cpal` 0.18 as alternative.
- Undocumented detail the swapchain-wrapping path depends on: `tex_type_attachment_internal` (`1 << 29`) is private to StereoKit (`asset_types/texture.h`), not in the public enum. Passing the raw bit through `tex_set_surface` works today and is what StereoKit's own views do; propose a public flag upstream, and keep the Vulkan-copy fallback.

- Apple `SpeechAnalyzer` + `SpeechTranscriber` (macOS 26+; this Mac runs macOS 27): on-device, real-time, opt-in volatile results, non-overlapping finalized results, timeline-scheduled.

## Risks

- Meta Spatial SDK is 0.x: 0.13.1 forced AGP 8.11, 0.14 changed lifecycle ordering; the OpenXR handle getters are public but undocumented. Pin 0.14.0, keep SDK-touching code in a few files, read release notes per upgrade.
- The 16-layer cap is hard; tiering by render mode is mandatory past ~14 screen windows, and the mesh-mode bug needs its `layerConfig = null` workaround.
- The hardware Canvas redraws the whole panel per change; fine for sixteen panels, to be measured for a hundred. The direct-to-surface GLES renderer is the escape hatch, not the default.
- The earlier client's border flicker in layer mode is unexplained; M1 tests blend modes and feathering before any layout polish.
- The SDK owns the frame loop, layer list and action sets; anything needing a custom composition layer or custom action bindings means the StereoKit fallback.
- cmux moves fast (HEAD commit the day of this plan); pin a tagged release and stay on the public API; the raw v12 stream is private.
- Wi-Fi stalls are the user-visible failure; coalescing is cmux's, catch-up is reopen-on-gap, and the staleness marker is ours.
- Outdoor passthrough: camera exposure, tracking loss, boundary behavior; test before investing in layout polish.

## Status

| Date | Milestone | State |
|---|---|---|
| 2026-10-30 | M1 prototype: `quest/` (Spatial SDK 0.14 Kotlin app + `terminal` library + `preview` phone app). One window over passthrough: compositor-layer panel (ALPHA_BLEND, feathered), Compose header (traffic lights, title, spinner), `TerminalGridView` drawing an 80x24 grid with `Canvas.drawGlyphs` from a synthetic `DemoScreen` (torus, meters, log, typing prompt, status bar). ISDK edge/corner handles move and resize. Window spawns along the head's forward vector (Meta sample math). | Built and installed on the Quest 3 (`dev.xmux.quest`); runs with passthrough on and the panel view rendering at 27 fps, HWUI p50 6 ms / p99 11 ms per redrawn frame, 0 janky, ~40% of one core, 252 MB RSS. Visual check on the headset still pending: the headset was in the dark during the session (Horizon OS "Finding position in room"), so the two captures only show the tracking dialog. The emulator preview (`results/quest-proto/emulator-preview-20261030.png`) shows the window as rendered. |

Corrections learned from the prototype:
- HWUI ignores partial invalidation rectangles (API 21+): a changed row costs a full display-list re-record of the view, so "dirty rows invalidate only their rectangles" buys nothing. What matters is draw-call count: `TerminalGridView` batches glyphs frame-wide by (font, color, style), so a screen costs about as many `drawGlyphs` calls as it has distinct colors. Dirty-row tracking still gates whether a frame is drawn at all (unchanged screens cost zero).
- Quest screenshots over adb: `screencap` returns an empty file on Horizon OS and argent's screen-sharing agent aborts. The headset's own capture works: `am broadcast -a com.oculus.vrpowermanager.prox_close` (simulate worn) then `am startservice -n com.oculus.metacam/.capture.CaptureService -a TAKE_SCREENSHOT`, pull from `/sdcard/Oculus/Screenshots/`.
- Cold start of the release build to first frame was 15 s (`AppLoadTimeTelemetryCommon`); investigate before M2 (likely Spatial SDK asset/feature init, not our code).

## References
