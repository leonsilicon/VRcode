# vrcode plan

vrcode is a Meta Quest app for working in a cmux session from VR. The MacBook is only the compute
and state behind it and may be in clamshell mode (lid closed, still running), so everything the
user sees, hears, says and configures goes through the Quest. vrcode does five things:

1. **Terminals.** Every cmux screen appears as a floating window over passthrough, rendered by
   [xmux](https://github.com/ImproveLabs/xmux) ("cmux on VR"; local checkout at
   `../../leonsilicon/xmux`). cmux manages the workspaces, screens, panes, tabs and terminals.
2. **Mac display.** A window that shows a Mac display and takes pointer and keyboard input, so
   ordinary Mac apps stay usable from the headset. With the lid closed it is the Mac's only screen.
3. **Gestures, shortcuts and voice.** Hand gestures act on the xmux terminals; a
   palm-facing middle-finger pinch opens a configurable Shortcuts pie for creating windows. Speech
   to text types through a staged transcript, nothing reaching a terminal before Send.
4. **Audio from the Quest.** Microphones, speakers and their settings are chosen on the Quest, and
   Mac app audio plays in the headset.
5. **Saved layout.** Quitting and reopening vrcode puts every window back where it was.

Rules that hold across all of it:

- cmux on the Mac is the only source of truth for terminals: contents, layout, tabs and focus. vrcode
  stores where windows are in the room and its own settings, never terminal state.
- The Mac is compute and state only. vrcode works with the lid closed and no external display,
  keyboard, speakers or microphone. With the lid open the Mac works as usual, and xmux never resizes
  the Mac's terminal grids.
- Every setting, Mac-side ones included, is changed from the Quest in the vrcode menu (left palm
  pinch). The vrcode Mac service has no UI of its own.
- vrcode does not patch xmux. Anything xmux lacks is filed as an xmux issue first (open gaps are
  listed below). xmux's README leaves gestures, voice and macros to apps built on it.
- Every gesture has an on-panel equivalent, so a flaky gesture never blocks a feature.

| Piece | Runs on | Owns |
|---|---|---|
| cmux (`cmux-tui` daemon, session `main`) | Mac (lid open or closed) | all terminal state |
| `xmux-bridge` | Mac, port 7272 | mirrors the cmux session to the headset; input and focus back to cmux |
| vrcode Mac service | Mac, its own port | speech recognition, the Mac display (capture, virtual display), mouse and keyboard injection, Mac audio, clipboard, keeping the Mac awake, starting cmux and the bridge |
| vrcode app (Spatial SDK and `xmux-spatial`) | Quest | window placement and the saved layout, gestures, transcript dialog, Mac display panel, audio devices, all settings |

## How vrcode uses xmux

Checked against xmux `0.2.0-SNAPSHOT` at `ea3e132` (2026-11-01). Setup steps are in
`docs/xmux-integration.md`, which was verified before the 0.2.0 renames (see "Changed since the
integration doc" below).

### Build

- Depend on `dev.improve.xmux:xmux-spatial`. It brings `xmux-client`, `xmux-proto`,
  `xmux-screen-view`, `xmux-terminal-window` and `xmux-window-frame`, and exposes Meta Spatial SDK
  0.14.0 as an `api` dependency (do not pin another Spatial SDK version). Add
  `dev.improve.xmux:xmux-connect-panel` for the bridge picker and pairing UI, and
  `testFixtures("dev.improve.xmux:xmux-client")` for `FakeBridge`.
- During development, build against the checkout: `includeBuild("../../leonsilicon/xmux")` in
  `settings.gradle.kts` replaces every `dev.improve.xmux:*` dependency, whatever its version. vrcode
  must use xmux's AGP (8.11.1) and Kotlin (2.1.0) versions.
- Every xmux module has its own Kotlin package, equal to its Android namespace. vrcode imports from
  `dev.improve.xmux.spatial` (`XmuxSpatial`, `ScreenWindow`, `WindowHit`, `Placement`),
  `dev.improve.xmux.client` (`XmuxSession`, `TerminalHandle`, `Screen`, `Key`, `Direction`,
  `ConnectionState`) and `dev.improve.xmux.window.frame` (`WindowChrome`).

### API vrcode programs against

| vrcode needs | xmux call |
|---|---|
| What the pointer ray is on | `xmux.hitTest(origin, direction)` (optional `maxDistanceM`, default 10 m) or `hitTest(hitInfo)` returns a `WindowHit`: `window`, `pane`, `terminal`, `row`/`col` (-1 off the grid), `onHeader`, `point`. Main thread only. It sees only xmux windows, so vrcode tests its own panels (Mac display, card, menus) first. A ray on a divider or the header has `pane == null` |
| Target pane for voice, Enter and Escape | `hit?.terminal ?: xmux.focusedTerminal()?.id` (the pane under the ray, else the last clicked one) |
| Focus the selected pane or window | `xmux.focus(window, pane)` (or `xmux.focus(window)` for a window), which also routes the hardware keyboard and focuses the pane in cmux |
| Type, press keys, click | `session.terminal(id).write(text)`, `.keys(Key("enter"))`, `.keys(Key("escape"))`, `.mouse(MouseEvent)`; key names follow cmux, modifiers go in `Key(name, modifiers)` |
| Place windows and read them back | `xmux.mount(session, screen, Placement.Fixed(pose), chrome)`, `unmount(window)`, `ScreenWindow.pose()`, `setPose(pose)`, `entity`. `panelSize` is the size at spawn; the live size after a user resize is the entity's `IsdkPanelDimensions` |
| Stable screen identity | `session.topology.value.screens[i].cmuxId`; the numeric `ScreenId` is assigned by each bridge run |
| Mic glyph on the target window | `WindowChrome(trailingSlot = { ... })` passed to `mount` or `follow`; slots are composables, so flipping a `mutableStateOf` updates the header |
| Transcript card, HUD, radial menus | `xmux.mountPanel(widthPx, heightPx, placement) { ... }` returns the panel `Entity` |
| Connection UI | `session.state` (`ConnectionState`: `Connecting`, `Pairing(code)`, `Online`, `Stalled`, `Reconnecting`, `Closed`); `ConnectPanel` from `xmux-connect-panel` |

Notes:

- `xmux.registerFeatures()` returns `VRFeature(inputSystemType = INTERACTION_SDK,
  handMicrogestureLocomotion = false)` and `ComposeFeature()`. Locomotion off is what vrcode wants
  (thumb swipes must not move the user). If vrcode needs a different `VRFeature`, it registers
  its own and skips xmux's, which xmux's embedding guide allows.
- `xmux.registerPanels()` is empty (xmux registers panels as windows mount), so vrcode returns its own
  registrations, such as the Mac display's video panel, next to it.
- Do not use `Scene.lineSegmentIntersect` for xmux windows: on the Quest 3 it passed through a
  screen window and hit the panel behind it.

### Gaps to file against xmux

- **Pane, tab and workspace operations beyond focus.** The wire protocol carries only input, resize
  and focus (`FocusDirection`, `FocusPane`, `FocusTab`). The radial menu's split, close, new-tab and
  workspace items, and Shortcuts' create-window action, need a new xmux request, or vrcode's Mac
  service calling cmux directly. Shortcuts needs the created screen and terminal identities back,
  so it can launch into that terminal and match the screen in bridge topology. Until that request
  exists, use the vrcode service route described under Shortcuts; do not patch xmux.
- **`follow` cannot restore a layout.** Its placement callback gets `(index, count)`, not the
  screen, so vrcode runs its own follow loop for the saved layout (see below). Ask for the `Screen`
  in the callback.
- **No way to set a window's size from code.** Restoring a saved size needs it.
- **No grab state.** vrcode cannot tell when ISDK is moving or resizing a window, so it detects
  "move ended" by polling poses.
- **No reusable framing.** `xmux-proto`'s `encode_frame`/`decode_frame` take only xmux's own `Frame`,
  and `Header::parse` rejects payload kinds above 19, so the vrcode service copies the 16-byte header
  layout, not the code.
- **Pairing is approved on the Mac.** `xmux-bridge pair <code>` has to run on the Mac. The vrcode
  service runs it for a headset it already trusts (see Clamshell mode below); a way for an
  already-paired client to approve a new one would remove that workaround.

### Changed since the integration doc

`docs/xmux-integration.md` was verified on 2026-10-31, before these xmux changes:

- The Maven group `dev.xmux` is now `dev.improve.xmux`. The composite build substitutes only the new
  group, so `dev.xmux:*` coordinates no longer resolve.
- The `xmux-core` and `xmux-compose` bundles are gone; depend on the modules listed above.
- The bridge keeps its certificate and pairings in `~/Library/Application
  Support/dev.improve.xmux.bridge`, and its LaunchAgent is labelled `dev.improve.xmux.bridge`. Run
  `xmux-bridge service uninstall` before upgrading an installed LaunchAgent and `service install`
  after. The upgraded bridge starts with a new certificate and no pairings, so every app pairs again
  (or set `XMUX_BRIDGE_STATE` to the old directory to keep them).
- xmux's standalone app installs as `dev.improve.xmux.quest`, next to the old `dev.xmux.quest` build
  rather than over it.

## vrcode Mac service

The vrcode Mac service runs next to `xmux-bridge` on its own port (the bridge uses 7272) and starts
at login as a LaunchAgent. It handles speech recognition, the Mac display and its input, Mac audio,
and clipboard and screenshot delivery. It also executes configured Shortcuts: create a cmux screen
and launch the chosen CLI in its new terminal. On request from the Quest it starts or restarts cmux
and `xmux-bridge`, whose status shows on the HUD. The headset reaches it over a separate connection, and
nothing vrcode-specific travels on xmux's stream. Its protocol uses xmux's 16-byte header layout
with its own payload kinds and version.

### Clamshell mode

- **One-time setup with the lid open** (or an external display attached): install the service and
  `xmux-bridge` LaunchAgents, grant the macOS permissions (Screen & System Audio Recording for the
  display and Mac audio, Accessibility for injected input), and pair the headset with both. After
  that the lid can stay closed. Later pairings: the service runs `xmux-bridge pair <code>` itself,
  with the code the headset shows, for a headset it already trusts.
- **Staying awake.** macOS sleeps when the lid closes unless an external display and power are
  connected, and power assertions do not prevent that. For headset-only use, either
  `sudo pmset -a disablesleep 1` while vrcode is in use, or an HDMI dummy plug plus power, which
  turns it into a standard clamshell setup. Check on this MacBook which is needed and whether a
  virtual display alone counts as an external display.
- **No screen.** With no display attached, the service creates a virtual display (see Mac display).
- **No built-in mic.** A closed MacBook disconnects its built-in microphone in hardware, so speech
  comes from a Quest-side mic or an external mic on the Mac (see Audio).
- **Locked Mac.** If the Mac locks, the user unlocks it through the Mac display panel. Check that
  injected keys reach the lock screen; if they do not, turn off the screen lock while vrcode is in
  use. FileVault's login after a reboot happens before macOS runs and cannot be reached from the
  headset.

Open:

- Language. SpeechAnalyzer is a Swift-only API, and ScreenCaptureKit, VideoToolbox, Core Audio taps
  and `CGEvent` are easiest from Swift, so the service may be simpler in Swift than in Rust (or in
  Rust with a Swift helper).
- Wireless authentication now lives in the pinned TLS connection relay on port 7274. The raw
  vrcode service stays on loopback; xmux retains its own independent certificate and pairing.

### Local wireless

Connection settings now defaults to Automatic, with explicit Wi-Fi, Mac sharing, phone hotspot,
Wi-Fi Direct, Bluetooth Classic, BLE, Bluetooth PAN, Wi-Fi ADB and USB choices. A common pinned TLS
relay on port 7274 protects both the xmux and vrcode service streams without exposing raw speech
on the LAN. The Mac provides native Classic/BLE carriers; the Quest provides matching adapters,
Bonjour discovery, authenticated local proxies and a Wi-Fi Direct group setup flow. Hotspots and
PAN use OS-managed networks and report platform prerequisites. USB and Wi-Fi ADB have separate
Quest-side relay ports so switching does not reuse the wrong carrier. See
[setup and platform limitations](docs/local-wireless.md) and [validation](docs/connection-validation.md).

## Mac display

A window in VR that shows a Mac display live, so the browser, editor and other Mac apps (cmux's own
window included) stay usable from the headset. It lives inside vrcode: no separate app runs beside
it on the Quest or the Mac (Horizon OS multitasking would also place such a window itself and give
input to one app at a time).

Decided 2026-11-01, with design, input mapping, build steps and the rejected stacks in
[Mac display streaming](docs/mac-display-streaming.md). It is a native pipeline in vrcode's own two
halves, copying proven code: Glass's macOS capture, encoder, virtual display, keep-awake and
`CGEvent` injection, and Moonlight's Qualcomm low-latency decoder setup. Its shape is scrcpy's: raw
H.264 over an ADB TCP socket into `MediaCodec`. Off-the-shelf stacks (Moonlight/Sunshine, Glass,
LiveKit/WebRTC, VNC) each need a separate app or server, or UDP, which `adb reverse` cannot carry.

| Mode | When | What is shown |
|---|---|---|
| Mirror | lid open | the built-in display as it is |
| Virtual | lid closed, or chosen on the Quest | a virtual display the service creates (`CGVirtualDisplay`, a private API that tools such as BetterDisplay and DeskPad use; an HDMI dummy plug is the fallback). Resolution, HiDPI scale and refresh rate are set from the Quest. With the lid closed it is the Mac's only display |

| Stage | Choice |
|---|---|
| Capture (Mac) | ScreenCaptureKit, the chosen display with the cursor, up to 60 fps |
| Encode (Mac) | VideoToolbox H.264 High with low-latency rate control, real time, no frame reordering, long GOP with keyframes on request (HEVC Main to try if text needs it); bitrate per link (USB with `adb reverse`, or Wi-Fi) |
| Transport | its own video socket on port 7274 (Mac → Quest, Annex-B access units in the xmux header layout); control and input on the vrcode connection (7273); video never shares xmux's stream |
| Decode and show (Quest) | `MediaCodec` `c2.qti.avc.decoder.low_latency` with `KEY_LOW_LATENCY` and Qualcomm's low-latency vendor keys, into the `Surface` of a flat `VideoSurfacePanelRegistration` panel (a compositor layer, so text stays sharp); grabbable and resizable like the xmux windows |
| Pointer | vrcode intersects the ray with the panel quad before `xmux.hitTest`; the UV maps to display coordinates and becomes `CGEvent` hover, clicks (double and triple via click state), right-clicks, drags and scrolls on the Mac |
| Keyboard | a hardware keyboard paired with the Quest (a closed Mac's keyboard is unusable). While the panel has focus, its events go to the Mac as `CGEvent` keys, with Meta mapped to Command, instead of to `xmux.dispatchKeyEvent`. Check which keys Horizon OS keeps for itself |
| Gestures | right index pinch clicks, pinch-and-hold drags; the palm-away right middle pinch (the existing right-click) right-clicks the Mac; right thumb swipes scroll (thumb swipes have no other app action); controller trigger, B and thumbstick do the same; on-panel right-click toggle and scroll strip as equivalents; Enter, Escape and voice keep targeting what has focus |
| Sound | Mac app audio plays in the headset (see Audio) |

Open: whether voice may also type into the focused Mac app. With the lid closed and no keyboard it
is the only way to enter text there besides the Horizon virtual keyboard.

## Audio

Every audio device and setting is chosen on the Quest. With the lid closed the Mac has no usable
microphone and only muffled speakers, so the Quest's devices are the defaults.

| Stream | Path | Choice |
|---|---|---|
| Speech-to-text input | selected mic -> Mac recognizer | any Quest input (`AudioManager.getDevices(GET_DEVICES_INPUTS)`, picked with `AudioRecord.setPreferredDevice`), or a mic attached to the Mac that the service reports, captured on the Mac directly |
| Mac app audio | Mac -> Quest | a Core Audio process tap on all processes (macOS 14.2+, `CATapDescription` with the muted-when-tapped behavior, so the Mac's speakers stay silent while the headset plays) -> Opus 48 kHz stereo -> `AudioTrack` on the Quest output chosen with `setPreferredDevice` (built-in speakers, USB-C or Bluetooth headphones) |
| Mic for Mac apps (later) | Quest -> Mac apps | a virtual input device on the Mac (an Audio Server plug-in) fed by the selected Quest mic, so calls and dictation in Mac apps work with the lid closed |

The audio and speech settings live in the vrcode menu (left palm pinch, see Gestures): speech input
device, output device, mic mute, Mac audio on/off and volume, speech language, VAD sensitivity and
silence timeout. The service reports Mac-side devices and applies Mac-side choices. Devices that connect or disconnect
(a Bluetooth headset, say) show up live; if the selected one goes away, vrcode falls back to the
Quest's built-in mic or speakers and says so on the HUD.

Open: which inputs Horizon OS exposes to apps (built-in array, USB-C, Bluetooth headset mics).
Android routes Bluetooth mics only in communication mode, which lowers playback quality while the
mic is open.

## Saved layout

Quitting and reopening vrcode puts every window back where it was. cmux stays the source of truth:
vrcode saves where windows are, never what they show.

- **Saved, per cmux screen:** pose, size (the entity's `IsdkPanelDimensions`) and whether it was
  hidden, keyed by the bridge's `session.sessionName` plus `Screen.cmuxId`. The numeric `ScreenId`
  is not a key: each bridge run assigns its own. Also saved: the Mac display panel's pose and size,
  the transcript card's position, hand roles, audio choices, the ordered Shortcuts configuration
  (including disabled items and custom CLI/model arguments), and other settings.
- **Not saved:** terminal contents, layout, tabs and focus. They come from cmux on every connect. A
  saved screen that cmux no longer has is not shown. A screen with no saved entry gets the next free
  arc slot.
- **Stable in the room:** xmux sets `LOCAL_FLOOR`, which moves when the user recenters, so poses are
  stored relative to a persisted spatial anchor that vrcode creates on first run (OpenXR
  `XR_FB_spatial_entity` storage, through whatever Spatial SDK 0.14 or MRUK exposes; check first).
  The boundary's `STAGE` space is the fallback if anchors are not reachable. If neither can be
  found (another room, a cleared boundary), the saved arrangement is placed as a group in front of
  the head, keeping the windows' relative positions.
- **Restoring:** vrcode mounts screens itself with `xmux.mount(session, screen,
  Placement.Fixed(pose), chrome)` instead of `follow`, and mounts new screens and unmounts removed
  ones on each tick (what `follow` does today, about 20 lines). `setPose` is a no-op before a window
  spawns, so mounting with `Placement.Fixed` is the only flicker-free way to restore. Sizes cannot be
  restored until xmux can set them (see the gaps above).
- **Writing:** after a window stops moving (poses polled, debounced) and in `onPause`, because
  Horizon OS can kill a backgrounded app without `onDestroy`. Storage is versioned JSON in
  app-private storage.

## Gestures

Vocabulary set by the user on 2026-10-30 (from the original xmux plan, `docs/xmux-plan.md`), with
palm-facing middle-pinch Shortcuts added on 2026-11-01.

Signals and where they come from:

| Signal | Source | Reliability |
|---|---|---|
| Index pinch | runtime (`XR_FB_hand_tracking_aim` INDEX_PINCHING; ISDK select/grab) | highest; Meta's primary click |
| Middle-finger pinch | runtime per-finger bit + strength (`XR_FB_hand_tracking_aim`), read through our own `xrCreateHandTrackerEXT` via the public OpenXR handles (Spatial SDK does not surface it) | good with an open hand; gate on "index not pinching" and "not fist", with hysteresis |
| Fist | Spatial SDK `Microgestures.isFist` | good |
| Palm facing the headset + index pinch | runtime system gesture on both hands (SYSTEM_GESTURE flag while the pose is held; ISDK stands down). **Dominant hand** (right by default): opens the OS Universal Menu, consumed by the OS. **Non-dominant hand** (left by default): delivered to the app as a one-frame menu-button press (`MENU_PRESSED`), the hand equivalent of the left controller's menu button; exposed by Spatial SDK as the left menu button where mapped, otherwise read from our JNI hand tracker | left palm + index pinch is the sanctioned app menu gesture and is reliable; right palm + index pinch is the OS's; right palm + **middle** pinch is unreserved but adjacent to the OS gesture, so it needs gating and on-device testing |
| Palm facing the headset + middle-finger pinch, index unpinched | app classifier combining the tracked palm orientation with the middle-pinch signal; separate from `MENU_PRESSED` | intended Shortcuts gesture on either hand; verify whether Horizon OS still supplies usable joints and pinch data in the palm-facing pose, and whether it asserts SYSTEM_GESTURE before a pinch (see Shortcuts validation below) |
| Thumbs up/down | not runtime-detected; no longer used (a MediaPipe landmark classifier remains an option if ever wanted) | - |

Mapping (hands have fixed roles, swappable in settings; "middle pinch" means the middle finger held
on the thumb with the index still up):

| Hand | Gesture | Action |
|---|---|---|
| Right (pointer) | index pinch | click / select (terminals and the Mac display); drag when the ray holds the top bar (ISDK grab handle); corners resize (xmux's window defaults) |
| Right | middle pinch hold + move, palm away (index stays up) | radial action menu at the hand: contextual pane actions (cmux pane/tab/workspace operations, i.e. right-click) when the ray is over a pane, otherwise the global set: undo, redo, keyboard, screenshot |
| Either | palm facing the headset + middle pinch hold + move (index stays up) | Shortcuts pie: **Create new window** with Claude Code, Codex or Terminal by default; user-configurable entries, independent of the hovered pane |
| Left (voice) | fist, held | **speak to the AI** (built 2026-11-01): the microphone prop appears in the fist; the voice pane shows above the dock while listening. When the hand opens, the Mac's AI (Claude Opus 5.5, low effort) turns what was said into the input for the focused pane, using its visible screen: a shell command, or a clear prompt for a coding agent. The AI pane stays above the dock, showing the AI's text over what was said, until it is pasted or deleted; nothing is typed until a paste |
| Right (voice) | fist, held | **plain dictation** (built 2026-11-01): same strict fist, from the right hand's own joint tracker; the pane shows above the dock while listening and hides when the hand opens, the transcript staged as said. One dictation at a time: only the hand that started it ends it |
| Left | index pinch, palm away, with text staged | insert the staged text into the pane the left hand points at (see Voice input); the left hand runs the whole voice flow |
| Either | palm toward the user + middle pinch hold + move (index stays up) | palm menus at the pinch point (built; see [hand input](docs/hand-input.md#palm-menus-both-hands)). **Left**: Terminal, Codex, Claude Code, each a new cmux screen. **Right**: Keyboard, Enter, Esc, Ctrl-C, Paste voice, Clear voice. Move onto an item (~7 cm) to run it, or toward it (past 3.5 cm) and release; releasing in the centre, a dominant index pinch, a fist or tracking loss over 200 ms cancels. Pinch = the runtime's MIDDLE_PINCHING and pinchStrengthMiddle; palm-facing = the runtime's SYSTEM_GESTURE or the palm joint's −Y within 55° of the head. The palm-away right middle pinch stays the right-click |
| (from the pie) | Keyboard | a floating iPhone-style keyboard (letters, 123, #+=, and an esc/tab/ctrl/arrows row), 30 cm wide at chest height and tilted toward the eyes; typed by fingertip touch (Interaction SDK direct touch) or ray pinch, grabbable to move, × to close. Keys go to the voice pane picked for editing (return ends editing), else to the focused terminal pane. Replaces the open-palm keyboard, dropped because typing over the other hand degrades hand tracking |
| Left | middle pinch hold + move, palm away (index stays up) | radial macro menu (e.g. Claude Code commands); items run as `TerminalHandle.write` / `keys` |
| Left | palm + **index** pinch (the app menu button, `MENU_PRESSED`) | open/close the vrcode menu (settings popup, see below); runtime-detected, no gating |

Pane and window selection is direct: look toward the desired pane or window, point and index-pinch
to focus it. cmux focus and hardware-keyboard routing follow that selection. Thumb taps and swipes
have no app action on either hand. The left fist is reserved for voice. Enter and Escape for staged
text live on the transcript dialog (Insert with Enter, Clear).

Radial hold menu (both hands, shared selection code): at middle-pinch start (per-finger bit with
strength hysteresis, index unpinched), classify the palm orientation and choose one menu:
palm-facing opens Shortcuts; palm-away opens that hand's contextual/global or macro menu. Use an
angular dead band to avoid ambiguous poses; latch the menu kind for the entire pinch so turning the
hand cannot switch menus mid-selection. Only one radial menu owns input at a time.

Items appear around the pinch point, world-locked at that moment. While held, displacement from
the start point in the captured menu frame picks an item once it exceeds a dead zone (~4 cm); the
picked item highlights; releasing commits it exactly once; releasing inside the dead zone cancels.
Freeze the frame at opening so wrist rotation cannot move the selection axes. Tracking loss,
an index pinch, an OS menu event or app suspension cancels without executing; require a fresh
pinch after cancellation. The on-panel version supports pointer clicks. Shortcuts starts with
three sectors; allow up to eight enabled entries in one ring, with additional entries accessible
in the Shortcuts pane. Until xmux can split, close or create panes and tabs (see the gaps), the
contextual set is limited to focus and tab switching.

Rules: ignore ordinary app gestures while the runtime reports SYSTEM_GESTURE; treat `MENU_PRESSED`
(left palm + index pinch) as the vrcode menu toggle; require valid tracking for all input. The
palm-facing Shortcuts gesture needs its own runtime validation before enabling it (see below);
do not bypass OS ownership to make it work.

### Shortcuts

Shortcuts is a configurable vrcode pane, with a pie presentation for fast hand selection. Its
default action group is **Create new window**. On either hand, face the palm toward the headset,
touch the middle finger to the thumb with the index unpinched, move toward an item while holding,
then release to launch. The center identifies Shortcuts / Create new window and the dead zone is
visibly a cancel area. Each sector has an icon and a text label:

| Default entry | Icon | New window content |
|---|---|---|
| Claude Code | Claude Code icon | a new cmux terminal running `claude` |
| Codex | Codex icon | a new cmux terminal running `codex` |
| Terminal | terminal glyph | a new cmux terminal running the user's default shell |

These are editable starter presets. Each selection creates a **separate cmux screen**, which xmux
renders as a new floating window, even when the ray is on an existing terminal. Place it in the
next free arc slot in front of the user and focus its terminal when it is ready. Shortcuts never
types a launch command into the previously focused terminal.

- **Access.** A Shortcuts button in window headers and a Shortcuts entry in the vrcode menu open
  the same pane without a gesture. Its launch buttons are pointer-clickable; Edit shortcuts opens
  its configuration. The pane remains usable with hand tracking unavailable or with controllers.
- **Configuration on the Quest.** Add, edit, remove, enable/disable and reorder entries; restore
  defaults explicitly. An entry has a stable id, label, icon, executable, argument list (including
  optional model/provider flags) and optional Mac working directory. An empty executable means a
  shell-only terminal. Users can replace Claude Code or Codex with another CLI, or keep several
  presets for different models. Offer bundled provider icons and a generic custom-tool glyph;
  always keep the text label. Show the command and directory in the editor before saving.
- **Persistence.** Store a versioned `shortcuts` list in the headset's app-private settings alongside
  the saved layout. Seed defaults only when the list has never been configured; an intentionally
  empty list stays empty and shows Add shortcut. Edits apply live; snapshot entries and their order
  when a pie opens so a settings update cannot change the item being selected. Keep sector positions
  stable when a CLI is unavailable, with an explanatory disabled state.
- **Launch route.** Add a create-window request/result to the vrcode service connection; this is
  planned protocol work, not an existing kind in `docs/vrcode-protocol.md`. The Mac service calls
  cmux to create a screen and terminal in the active workspace, then starts the configured command
  in that new terminal only. Pass the executable and arguments separately where cmux supports it;
  any shell-input fallback must quote each argument and directory. A Terminal entry skips the
  command step. Return stable cmux screen/terminal ids and match the incoming bridge topology
  before mounting/focusing; do not create a second local terminal or duplicate window.
- **Feedback.** Show a pending launch immediately. Correlate requests so one release or a retried
  request creates at most one screen and starts the command at most once. Report a missing CLI,
  invalid directory or disconnected service in the pane with an edit/retry action. If creation
  succeeded but command startup failed, retain the new shell and report the error there; retrying
  startup must reuse that screen. Never silently fall back to another provider.

Gesture validation is an implementation gate: OpenXR exposes middle-pinch bits/strength and
system/menu flags as [separate signals](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandTrackingAimFlagBitsFB.html),
but does not promise that this app gesture is available during a system-owned pose. Check on the
Quest whether palm-facing middle pinch is delivered without activating the OS menu, on both
hands. If SYSTEM_GESTURE suppresses it, keep the on-panel route available and resolve the gesture
before shipping; do not simply remove the system-gesture guard. Test palm-away versus palm-facing
routing, orientation jitter, index/middle overlap, release in the dead zone, tracking loss and
role swapping. A gesture must never open two menus or accidentally launch two windows.

### vrcode menu

The left palm + index pinch (`MENU_PRESSED`) opens vrcode's own menu: a settings popup for vrcode
itself, not for cmux. With controllers, the left menu button does the same. The right palm pinch
stays the OS's Universal Menu, which apps cannot override.

- It opens world-locked in front of the user at chest height. The right-hand ray works it like any
  panel, and the same gesture or a Close button dismisses it.
- On-panel equivalent: a gear button in every window's header (a `WindowChrome` leading slot), so
  the menu is reachable even when the HUD is hidden.
- Changes apply live and are stored with the saved layout. Mac-side settings are sent to the vrcode
  Mac service.

| Group | Settings |
|---|---|
| Audio | speech mic, output device, mic mute, Mac audio on/off and volume |
| Speech | language, VAD sensitivity, silence timeout |
| Mac display | mode (mirror or virtual), resolution, HiDPI scale, refresh rate, show/hide the panel |
| Hands | swap hand roles, radial menu dead zone |
| Shortcuts | open the Shortcuts pane; add/edit/remove, enable/disable and reorder launch entries; choose icons, CLI/model arguments and working directories; restore defaults |
| View | show/hide the transcript card and HUD, re-anchor the layout here, reset the layout |
| Mac and connection | bridge and pairing; status of cmux, `xmux-bridge` and the vrcode service, with restart buttons; keep-awake on/off |

**Capture** (the Screenshot item of the right hand's global radial menu; the left fist is reserved
for voice): a screenshot of the user's view including UI needs the Media
Projection API; a photo of the surroundings needs the Passthrough Camera API (Horizon OS v74+,
`CAMERA` + `HEADSET_CAMERA` permissions, 1280×960 or larger). Either image goes to the vrcode Mac
service and onto the Mac clipboard as an image (`NSPasteboard`; `pbcopy` handles only text). Open:
which source the shutter means.

## Voice input

Fist-to-talk with a staged transcript. A first version is built (2026-11-01): the Quest side in
`app/src/main/kotlin/dev/improve/vrcode/voice/`, `speech/` and `hands/`, the Mac side in `mac/`, the
wire format in `docs/vrcode-protocol.md`.

| Stage | Choice |
|---|---|
| Start / stop | either fist held is listening: the right for plain dictation, the left for the AI. The fist is strict: all four fingers rolled up (each finger's three joint bends sum to at least 150°, 185° on average, from XR_EXT_hand_tracking joints) and the thumb wrapped over them (within 5 cm of the index or middle finger); the runtime's `Microgestures.isFist` also fires for a cupped hand and is only a fallback when joints are unavailable. Hysteresis keeps a slightly loosening fist (mean above 140°) a fist. Debounced: 120 ms of fist to start, 400 ms of a tracked open hand to stop). **Only unrolling the fist ends listening**: there is no silence timeout, and losing hand tracking (a fist lowered out of the cameras' view) holds voice mode instead of ending it. Every fist makes a new voice pane |
| Microphone | the selected 3D voice prop appears in the fist (`assets/microphone/microphone.glb` by default); the adjacent listening badge pulses with the input level |
| Voice panes | only the current pane is shown, just above the dock (which follows the view), not on the hand: while its fist is closed, while the Mac finishes or the AI thinks, and for an AI pane until it is pasted or deleted. A dictation pane hides when the hand opens; its transcript stays staged. Panes carry a Dictation or AI badge; an AI pane shows Thinking, then the AI's text (what a paste types) above what was said. If the AI fails or has no key, the pane says why and a paste types what was said. Transcripts are retained (up to 8 notes; the oldest unselected one goes first) |
| Paste | with the hand open, point the left hand at a terminal pane and pinch (index, palm away): the selected staged transcript is typed there and removed (`TerminalHandle.write`, then `Key("enter")` if "Enter after" is on), and that pane is focused with `xmux.focus`. The right-hand pie's Paste voice action pastes into the focused terminal. A pinch counts only when it starts at least 400 ms after the fist, so unrolling the hand cannot insert. Hidden voice panels do not intercept the ray; a pinch on a window header pastes nothing (the header still drags the window) |
| Capture (Quest) | `AudioRecord` (`VOICE_RECOGNITION` source) at 16 kHz mono 16-bit in 50 ms chunks from the speech input chosen in Audio (default: the Quest's built-in mic), `RECORD_AUDIO`, honor `AudioManager.isMicrophoneMute()`. A mic on the Mac skips this and the transport |
| VAD | none: the fist bounds each utterance. If Silero VAD (ONNX Runtime) or a WebRTC VAD port is added later, it may only trim silence or save bandwidth; it never ends listening |
| Transport | today raw PCM (32 KB/s) on the vrcode service connection; Opus via `MediaCodec` (`audio/opus`) or Concentus, ~24 kbps, once Wi-Fi bandwidth matters |
| Recognition (Mac) | `SpeechAnalyzer` + `SpeechTranscriber` (volatile and fast results), on-device, with macOS 27's `AnalyzerInputConverter`; alternatives whisper.cpp (Metal) or sherpa-onnx streaming models. Finalized and volatile text go back to the dialog |
| Recognition (Quest fallback) | sherpa-onnx or whisper.cpp on-device, only for a future Mac-less mode |
| Staging | **nothing is typed into a terminal until an insert.** Clear discards the staged text |
| Feedback | the dialog's dot pulses with the level and its status line says listening / finishing / "pinch a pane to insert"; later a mic glyph in the target window's header (a `WindowChrome` slot); audio never leaves the Mac/Quest pair |

Speech facts (2026-10-30):

- Apple `SpeechAnalyzer` + `SpeechTranscriber` (macOS 26+; this Mac runs macOS 27): on-device,
  real-time, opt-in volatile results, non-overlapping finalized results, timeline-scheduled.
- Horizon OS gives apps the microphone (48 kHz mono default; the OS may mute the stream, check
  `AudioManager.isMicrophoneMute()`); no documented speech service (verify
  `SpeechRecognizer.isRecognitionAvailable()` on device).

## Hand-rolled items

Rough sizes in lines:

- gesture routing + dictation state machine (~150)
- own hand tracker for per-finger pinch and palm/system-gesture flags via the OpenXR handles, JNI
  (~120 C++, required for middle pinch)
- transcript dialog above the left hand (Compose) (~120)
- virtual microphone: `assets/microphone/microphone.glb` in the left fist, level-driven pulse (~80)
- vrcode menu: settings popup, header gear, live apply and sync to the Mac service (~200)
- radial hold menu mechanic + contextual/global/macro/Shortcuts item sets (~250, shared by both hands)
- Shortcuts pane and editor, default provider icons, versioned settings, create-window request and
  Mac-side cmux launch handling (size after the cmux route is verified)
- capture pipeline (Media Projection or Passthrough Camera -> vrcode service -> Mac clipboard) (~150)
- audio capture -> VAD -> Opus (~150)
- audio decode -> SpeechAnalyzer -> transcript back to the card (~150)
- Mac display, Mac side: ScreenCaptureKit -> VideoToolbox -> stream, plus `CGEvent` injection (~300)
- Mac display, virtual display and mode switching (~150)
- Mac display, Quest side: `MediaCodec` -> video panel, ray and keyboard routing (~200)
- audio: Mac process tap -> Opus -> Quest `AudioTrack`, device lists and switching on both sides,
  settings panel (~250)
- Mac service upkeep: LaunchAgent, keeping the Mac awake, starting cmux and the bridge, running
  `xmux-bridge pair` (~100)
- saved layout: own follow loop, anchor, store (~150)
- later: a virtual Mac microphone (Audio Server plug-in; a project of its own)

## Milestones

Every gate is also run with the MacBook lid closed and nothing attached to it but power.

| Milestone | Work | Time | Gate |
|---|---|---|---|
| M2.5 Voice + gestures | `AudioRecord` + VAD + Opus; SpeechAnalyzer on Mac; left-fist voice mode with the dialog above the hand and left-pinch insert; the 3D microphone in the fist; JNI hand tracker for middle pinch and palm orientation; shared radial hold menus (contextual, global, macro, Shortcuts) | 1.5 wk | staged text never reaches a terminal before an insert; a left pinch inserts into the pane under the left ray only, and never fires while the fist unrolls; left-fist false starts counted over a 10-minute session that includes typing and holding objects; middle-pinch false-positive rate and radial selection accuracy measured over a 10-minute walking session (target: wrong-item rate under 5%); selecting a pane updates cmux focus and the Mac TUI follows |
| Shortcuts | palm-facing middle-pinch pie and pointer-accessible pane; Claude Code/Codex/Terminal presets with icons; Quest-side editor and persistence; Mac-service create-window dispatch through cmux | tbd | each preset creates exactly one new floating cmux screen with the intended CLI/shell; a custom CLI/model preset and ordering survive relaunch; cancellation/tracking loss create nothing; retries never duplicate screens or command startup; no commands reach an existing terminal; palm-facing versus palm-away menus route correctly on both hands without opening the OS menu; missing tools and offline service show actionable errors |
| Saved layout | own follow loop keyed by `cmuxId`; anchored poses; store written on move end and `onPause` | tbd | quit and relaunch puts every window back within 2 cm, also after a recenter; a screen closed in cmux meanwhile stays gone and a new one gets a free slot; no terminal state is read from local storage |
| Mac display | ScreenCaptureKit + VideoToolbox stream; virtual display for the closed lid; `MediaCodec` video panel; ray clicks and keyboard as `CGEvent`; keeping the Mac awake | tbd | Mac text readable at 1 m; clicks land on the intended control; glass-to-glass latency measured (target under 100 ms over USB); with the lid closed the Mac stays awake for an hour and the virtual display is usable; with the lid open the Mac works as without vrcode |
| Audio | device pickers in the vrcode menu; Mac process tap streamed to the Quest; Mac-side mic option for speech | tbd | switching the speech mic or the output on the Quest takes effect without a restart; with the lid closed Mac app audio plays only in the headset; a Bluetooth device disconnecting mid-dictation falls back to the built-in mic without losing the staged text |
