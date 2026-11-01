# Quest settings

The Quest app is `dev.improve.vrcode`, built with `./gradlew :app:assembleDebug`. It consumes the
external xmux checkout with the compatibility patches documented in
[terminal appearance](terminal-appearance.md); override its location using `-PxmuxDir=/path/to/xmux`.
Use JDK 17 and set `ANDROID_HOME` to the installed Android SDK. CMake 3.22.1 and NDK 27.2.12479018
build the small hand-menu reader.

Face the left palm toward the headset and pinch the index finger and thumb to toggle Settings.
The runtime's `XR_HAND_TRACKING_AIM_MENU_PRESSED_BIT_FB` is read from a left-only OpenXR hand
tracker. The Spatial SDK left menu button and Android Menu/Start key are also accepted. All three
routes share an edge/debounce gate so one gesture cannot toggle twice. Ordinary pinches and the
right system-menu signal are not used. The native reader uses the SDK's existing OpenXR instance,
session, function loader, and predicted display time; it never creates a second XR session.

Settings opens in front of the user, slightly below eye level, and stays world-locked until moved
or dismissed. The next opening places it in front of the user again, and so does returning to the
app, a recenter, or putting the headset back on while it is open, so a panel placed from a stale or
off-head pose never stays out of view. Close, Escape/Back, or the same palm gesture dismisses it.
A gear in each terminal header and the dock both open the same window. The collapsed dock handle
(see `docs/dock.md`) stays visible below it. The panel entity and registration are reused on subsequent openings.

Point at a terminal's title bar, then hold a pinch or controller trigger and drag to reposition
the window. Release to leave it in place. Both macOS and Windows header styles support dragging;
the settings button, active window controls, and terminal body keep their own input behavior.
Dragging sweeps the window around the user: the grabbed point stays under the pointer at its
distance from the head, and the window turns upright to face the user (no roll), like windows
placed by Arrange. Dragged sideways, up or down, it never ends up edge-on or drifts nearer or
further away; an angled window turns to face the user when the drag starts. Movement uses the
panel's current physical scale, including windows resized with the SDK's corner handles.

Available options:

- Passthrough: applies immediately. While on, the app requests contextual boundaryless mode
  through `XR_META_boundary_visibility`, so the drawn play-area boundary does not interrupt MR.
  Turning passthrough off requests boundary restoration before hiding the room. Horizon OS
  decides whether suppression is allowed; requests refused during passthrough startup are retried.
- Window distance: 0.8–2.5 m, used when new terminal windows mount. Arrange windows here brings
  currently open terminals into an arc at that distance without disconnecting their sessions.
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
- Customization has a fixed subtab bar with Pointer and Microphone tabs. Each tab scrolls its own
  controls, and changing tabs starts at the top. The selected tab survives navigation to another
  settings page during the session.
- Customization → Microphone → Voice prop: preview and choose Classic microphone, News microphone, Megaphone,
  or Banana Phone, in that order. Saved boombox selections become News microphone. The choice
  applies immediately to the hand-held model during dictation and the live settings preview, saves immediately,
  and restores on launch. All four GLBs and their previews ship with the app, so selection works
  offline. Listening feedback and staged-transcript behavior are shared across looks.
  While the Microphone subtab is visible, the selected prop follows the left hand's grip position
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

Unit tests: `./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest`.
`PointerCursorSettingsTest` checks style/size selection, saving, reload, and restoring defaults;
`PointerCursorRenderingTest` checks every shape, scaling, hidden mode, and clicks passing through.
`WindowMotionTest` covers translation and all three head rotations, switching without a jump,
tracking becoming available, independent windows, and rebasing after a drag/arrange. A wearer
still needs to check perceived stability and comfort while walking and looking around on Quest.

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

The voice prop follows the left hand's tracked transform during left-fist dictation and while the
Microphone settings subtab is visible. Its initial
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
