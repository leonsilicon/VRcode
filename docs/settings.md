# Quest settings

The Quest app is `dev.improve.vrcode`, built with `./gradlew :app:assembleDebug`. It consumes the
external xmux checkout with the compatibility patches documented in
[terminal appearance](terminal-appearance.md); override its location using `-PxmuxDir=/path/to/xmux`.
Use JDK 17 and set `ANDROID_HOME` to the installed Android SDK. CMake 3.22.1 and NDK 27.2.12479018
build the small OpenXR hand readers.

Open Settings with the dock's gear, or toggle it with the left controller's menu button. The
Spatial SDK left menu button and the Android Menu/Start key share an edge/debounce gate so one
press cannot toggle twice. With hand tracking, the left palm + index pinch (the runtime's app menu
gesture) no longer toggles Settings: it opens the
[quick inputs ring](hand-input.md#quick-inputs-left-index-pinch), and the menu-button press or menu
key the runtime also reports for it is ignored while the left hand is a tracked hand.

Settings opens in front of the user, slightly below eye level, and stays world-locked until moved
or dismissed. The next opening places it in front of the user again, and so does returning to the
app, a recenter, or putting the headset back on while it is open, so a panel placed from a stale or
off-head pose never stays out of view. Close, Escape/Back, or the controller's menu button dismisses it.
A gear in the dock opens app Settings. Each terminal header's gear opens **Window settings** as a
top sheet inside that terminal, above its live terminal screen. The sheet is capped at 680 dp on
tall screens and fills the available terminal viewport on shorter screens. Its options scroll
while its title and Close button stay visible. Close, Escape/Back, the same gear, or the dimmed
terminal below a shorter sheet dismisses it. The collapsed dock handle
(see `docs/dock.md`) stays visible below it. The panel entity and registration are reused on subsequent openings.

Point at a terminal's title bar, then hold a pinch or controller trigger and drag to reposition
the window. Release to leave it in place. Both macOS and Windows header styles support dragging;
the settings button, active window controls, and terminal body keep their own input behavior.
Dragging sweeps the window around the user: the grabbed point stays under the pointer at its
distance from the head, and the window turns upright to face the user (no roll), like windows
placed by Arrange. Dragged sideways, up or down, it never ends up edge-on or drifts nearer or
further away; an angled window turns to face the user when the drag starts. Movement uses the
panel's current physical scale, including windows resized with the SDK's corner handles.

Each terminal window has working close, minimize and maximize controls in both header styles:

- Close sends `screen.close` for that window's cmux screen ID. The live topology removes the
  window and its dock entry. It follows cmux's screen lifecycle; explicit `terminal.close` in
  Actions remains the operation for terminating a retained process. Errors appear in Actions,
  and repeated presses while a close request is pending do not send duplicate requests.
- Minimize hides the window while keeping its live session and dock entry. Hidden windows
  receive neither pointer hits nor keyboard/voice input. Recall it from the Quest dock to show
  and focus it again. Arrange leaves minimized windows hidden.
- Maximize centers a large fullscreen panel in the current view, following head position, yaw,
  pitch and roll. It fits within 74° horizontally and 58° vertically, including tall panels.
  Other terminal windows are temporarily hidden. Press the green/restore control again to
  restore the previous placement and Fixed/Follow mode. Minimize, dock recall, Arrange and the
  Fixed/Follow toggle also leave fullscreen; minimized windows stay hidden when it ends.

Fullscreen and minimized state last for the mounted window and reset on reconnect. Fullscreen
keeps the dock and recovery controls available. Mac traffic lights sit in a recessed capsule, with
64 × 48 dp targets and 28 dp colored circles carrying close/minimize/fullscreen icons. Hovering or
pressing expands the chosen circle into a 60 × 44 dp pill over 160 ms. The targets and title stay
in place throughout; the full pill is clickable. Mouse hover and the Quest hand/controller ray
both drive the expansion, including with No cursor selected. It collapses when the pointer leaves
or tracking is lost. Both styles expose Close, Minimize, Maximize / Restore window labels.

Window settings offers Fixed/Follow behavior, macOS/Windows title bars, color theme, text size,
and cursor shape for that terminal only. Unchanged options follow the app's current preferences;
**Use app defaults** clears this window's overrides and restores the default behavior. Overrides
survive closing and reopening the sheet and panel resizing, and last for the mounted window
(reconnecting or reopening the terminal starts with app defaults). Terminal selection, keyboard
input, and voice insertion pause while a window sheet is open. App settings remain available
through the dock or palm/menu gesture.

Available app options:

- Passthrough: applies immediately. While on, the app requests contextual boundaryless mode
  through `XR_META_boundary_visibility`, so the drawn play-area boundary does not interrupt MR.
  Turning passthrough off requests boundary restoration before hiding the room. Horizon OS
  decides whether suppression is allowed; requests refused during passthrough startup are retried.
- Window distance: 0.8–2.5 m, used when new terminal windows mount. Arrange windows here brings
  currently open terminals into an arc at that distance without disconnecting their sessions.
- Workspace → New terminal size: width in columns (40–200) and height in rows (12–72), with
  sliders and one-cell adjustment buttons. Standard, the default, is 120 × 36; presets also
  include Compact (80 × 24), Large (160 × 48), and Tall (100 × 48). Changes save immediately
  and apply to new terminals created by Add terminal, Codex, Claude Code, and Terminal actions.
  Explicit dimensions in Terminal actions take precedence. Existing terminals and terminals
  discovered on connection keep their size. The floating panel follows the terminal grid's
  aspect ratio and the chosen text size. Restore default settings restores 120 × 36.
  Screen/workspace creation is sized through the new terminal's attached viewer; logcat tag
  `VrcodeWindow` reports the exact terminal ID and requested size. Other attached cmux viewers
  can constrain the shared terminal grid according to cmux's viewer-size arbitration.
- New window behavior: Fixed (the default) stays at a position in the room; Follow keeps the
  window at the same offset in the viewport as the user walks, turns, looks up/down, or tilts their
  head. The choice saves immediately and applies only when a new screen window mounts. Existing
  windows retain their individual mode. Each terminal title bar has a Fixed / Follow toggle on
  the right, followed by Settings, in both macOS and Windows styles. Switching modes preserves
  the current pose; switching to Fixed leaves the window where it is. Title-bar dragging and
  Arrange windows here update a following window's viewport offset. Per-window modes last for
  that mounted window; reopening a screen or reconnecting uses the saved default.
- Connect on launch: reconnect to the last bridge on startup. Disabling it leaves the saved address
  available in the connection picker.
- Connection: network discovery, manual bridge address, pairing code, status and disconnect.
- Appearance: independent macOS/Windows window controls, eight bundled terminal color presets
  plus Follow Mac, 18–36 px JetBrains Mono text, and cursor shape. Changes apply to open and new
  terminals. The live preview uses the same renderer as the terminal. See
  [terminal appearance](terminal-appearance.md) for theme sources and app-color behavior.
- Customization has a fixed subtab bar with Pointer, Text mic and AI mic tabs. Each tab scrolls its own
  controls, and changing tabs starts at the top. The selected tab survives navigation to another
  settings page during the session.
- Customization → Text mic / AI mic → Mic appearance: independently preview and choose Classic microphone,
  News microphone, Megaphone, or Banana Phone, in that order. The right-hand Text mic defaults to
  Classic microphone and transcribes words as spoken; the left-hand AI mic defaults to News
  microphone, and uses AI before staging text to paste. The News microphone's cube shows the
  chosen AI provider's logo on whichever mic wears it.
- Customization → AI mic → AI provider: Claude (Anthropic, the default), ChatGPT (OpenAI) or
  Gemini (Google). The Mac sends left-fist requests to that provider, using the API key added in
  VRcode Mac's "AI provider keys" section (Keychain service `dev.improve.vrcode`, accounts
  `anthropic-api-key`, `openai-api-key`, `gemini-api-key`). The choice saves as `ai_provider` and
  goes out as the `provider` of each `AI_REQUEST`. Without that provider's key, the AI pane says so
  and a paste types what was said. The app loads `voice-props/news-microphone-<provider>.glb`.
  If a model is missing from the APK, it shows the classic microphone instead of failing to load.
  Old shared classic/default selections upgrade to these defaults; custom left-hand props survive
  on the AI mic, and saved boombox selections become News microphone. Each choice
  applies immediately to the hand-held model during dictation and the live settings preview, saves immediately,
  and restores on launch. All four GLBs and their previews ship with the app, so selection works
  offline. Listening feedback and staged-transcript behavior are shared across looks.
  While a mic subtab is visible, the selected prop follows that mic's hand's grip position
  and rotation even with an open hand. It needs no Mac connection, fist, or audio permission, and
  does not start recording. The preview ends on leaving that subtab, closing Settings, or pausing
  the app, and hides when hand tracking is lost (after the existing 250 ms tracking grace period).
- Customization → Pointer → Pointer cursor: Arrow (default), Dot, Crosshair, or No cursor, with previews and
  a 75–200% size control for visible cursors. Changes save immediately and apply to the right-hand
  pointer. No cursor hides the graphic and pressed-state halo while pinch clicks and drag selection
  stay enabled. This preference is separate from the terminal's text cursor in Appearance.
- Restore default settings: restores the preferences above, retaining the last bridge address and
  xmux pairing credentials.

Preferences are app-private `SharedPreferences`, saved on every change and on pause. Hardware
keyboard input and voice insertion gestures do not reach terminals while Settings is open.
The first launch opens the Connection page; once the initial connection has screens, that setup
panel closes. Subsequent settings openings show Workspace.

The menu only exposes implemented app features. Future audio routing, Mac display, hand-role and
Mac service settings from PLAN.md still need their respective implementations.

## Verification

`TerminalWindowSettingsSheetTest` checks both header styles, independent settings on two mounted
terminals, reopening and resetting overrides, full-height scrolling in a 320 dp terminal, a capped
top sheet on a tall terminal, and dismissal without recreating the terminal body. Its screenshots:
[short terminal](screenshots/window-settings-short-top.png),
[short terminal scrolled](screenshots/window-settings-short-scrolled.png), and
[tall terminal](screenshots/window-settings-tall.png).

2026-11-01 window-settings verification: all three sheet checks and five header checks passed on
the Android emulator. App/settings/xmux-spatial unit tests, xmux API checks, Bun tests, and asset
validation passed. Installed on Quest 3 over Wi-Fi (`lastUpdateTime` 13:16:05) and relaunched; the
checked process log showed the bridge online and terminal controls mounted, with no app crash.
Argent exposed only Quest system chrome, so hand targeting of the sheet still needs a wearer check.

Unit tests: `./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest`.
`TerminalSizeSettingsTest` checks all four presets, independent custom width/height, one-cell
adjustments, saving, reload, bounds, and restoring the standard size. The production panel at
1000 × 760 dp is shown below.

![New terminal size settings](screenshots/settings-terminal-size.png)

`PointerCursorSettingsTest` checks style/size selection, saving, reload, and restoring defaults;
`PointerCursorRenderingTest` checks every shape, scaling, hidden mode, and clicks passing through.
`WindowMotionTest` covers translation and all three head rotations, switching without a jump,
tracking becoming available, independent windows, rebasing after a drag/arrange, minimizing,
fullscreen fit and restoring each previous mode. `TerminalHeaderTest` exercises both styles and
independent screen callbacks, including the fullscreen restore label. The isolated real-daemon
cmux integration test closes one screen while another is focused and verifies the survivor. A wearer
still needs to check perceived stability and comfort while walking and looking around on Quest.

2026-11-01 window-control verification: debug build, app/settings/xmux-spatial unit suites,
four header instrumentation tests and five real cmux integration tests passed. Bun tests and
asset validation passed. The APK was reinstalled on Quest 3 and relaunched (`lastUpdateTime`
12:59:02); no app crash appeared in the checked process log. Argent exposed only Quest system
chrome after launch, so this does not establish in-headset hand targeting or fullscreen comfort.

Compose UI tests: `ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest` on an Android emulator with a
viewport of at least 1000×760 dp (the actual VR panel dimensions). These exercise the production
panel, preference save/reload, navigation, arrangement callback, defaults and Close, and write
`settings-workspace.png`, `settings-connection.png`, and `settings-customization.png` under the test
app's external files. The customization test selects every prop, checks exclusive selection,
reloads the saved choice into recreated UI state, and verifies restoring defaults.
Window-mode tests also cover preference reload/default restoration and title-bar toggling in both
styles, with right-side actions staying clear of long titles and the terminal body staying mounted.
For a standard phone emulator, temporarily use `adb shell wm size 1200x1000` and
`adb shell wm density 160`, then reset both after testing.

On Quest, use Argent to launch `dev.improve.vrcode`; logcat tag `VrcodeMenu` reports successful
tracker creation. Actual palm-pinch recognition, comfortable placement and right-ray use require
a wearer. Menu-key injection only checks an equivalent input route. The Quest's immersive panels
may not appear in the Android accessibility tree; do not infer tap coordinates from screenshots.

The Text mic follows the right hand during right-fist dictation and in its settings subtab.
The AI mic follows the left hand during AI input and in its settings subtab. Initial
grip placement and mouth clearance still need wearer verification; the settings emulator test
checks the picker and persistence, and does not substitute for that headset check.

![Customization picker with News microphone selected](screenshots/settings-customization.png)

Boundary verification: logcat tag `VrcodeBoundary` reports accepted requests or the exact OpenXR
result when refused. With passthrough enabled, verify in-headset that crossing the existing
play-area edge does not interrupt the app. Disable passthrough inside the play area and verify
that the boundary is restored. Tracking loss and other OS safety interruptions remain possible.
The app declares `com.oculus.permission.BOUNDARY_VISIBILITY` and registers the extension on the
SDK-owned OpenXR instance. It does not use the unconditional `BOUNDARYLESS_APP` manifest feature,
because the app can turn passthrough off. See [Meta's contextual boundaryless guidance](https://developers.meta.com/vr/documentation/unity/unity-boundaryless/).

2026-11-01 device check: debug APK built and updated in place on Quest 3; the permission was
granted and HorizonStandalone logged the extension as available and enabled. Guardian refused
the request with `XR_ERROR_RUNTIME_FAILURE` (`-2`) while its system was `SUSPENDED` (headset off
head). Suppression/restoration still needs the wearer check above; API availability alone does
not prove that the boundary was suppressed.
