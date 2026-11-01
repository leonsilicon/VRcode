# vrcode plan

vrcode is a Meta Quest app for working in a cmux session from VR. The MacBook is only the compute
and state behind it and may be in clamshell mode (lid closed, still running), so everything the
user sees, hears, says and configures goes through the Quest. vrcode does five things:

1. **Terminals.** Every cmux screen appears as a floating window over passthrough, rendered by
   [xmux](https://github.com/ImproveLabs/xmux) ("cmux on VR"; local checkout at
   `../../leonsilicon/xmux`). cmux manages the workspaces, screens, panes, tabs and terminals.
2. **Mac display.** A window that shows a Mac display and takes pointer and keyboard input, so
   ordinary Mac apps stay usable from the headset. With the lid closed it is the Mac's only screen.
3. **Gestures and voice.** Hand gestures and microgestures act on the xmux terminals, and speech to
   text types into them through a staged transcript, nothing reaching a terminal before Send.
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
| vrcode app (Spatial SDK and `xmux-spatial`) | Quest | window placement and the saved layout, gestures, transcript card, Mac display panel, audio devices, all settings |

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
| Lock the target | `xmux.focus(window, pane)`, which also routes the hardware keyboard and focuses the pane in cmux |
| Type, press keys, click | `session.terminal(id).write(text)`, `.keys(Key("enter"))`, `.keys(Key("escape"))`, `.mouse(MouseEvent)`; key names follow cmux, modifiers go in `Key(name, modifiers)` |
| Move focus between panes | `session.focusDirection(screen, Direction.Left / Right / Up / Down)`; also `focusPane(id)` and `focusTab(id)` |
| Move focus between windows | cycle through `xmux.windows` with `xmux.focus(window)`; `xmux.focused` is the window keyboard input goes to |
| Place windows and read them back | `xmux.mount(session, screen, Placement.Fixed(pose), chrome)`, `unmount(window)`, `ScreenWindow.pose()`, `setPose(pose)`, `entity`. `panelSize` is the size at spawn; the live size after a user resize is the entity's `IsdkPanelDimensions` |
| Stable screen identity | `session.topology.value.screens[i].cmuxId`; the numeric `ScreenId` is assigned by each bridge run |
| Mic glyph on the target window | `WindowChrome(trailingSlot = { ... })` passed to `mount` or `follow`; slots are composables, so flipping a `mutableStateOf` updates the header |
| Transcript card, HUD, radial menus | `xmux.mountPanel(widthPx, heightPx, placement) { ... }` returns the panel `Entity` |
| Connection UI | `session.state` (`ConnectionState`: `Connecting`, `Pairing(code)`, `Online`, `Stalled`, `Reconnecting`, `Closed`); `ConnectPanel` from `xmux-connect-panel` |

Notes:

- `xmux.registerFeatures()` returns `VRFeature(inputSystemType = INTERACTION_SDK,
  handMicrogestureLocomotion = false)` and `ComposeFeature()`. Locomotion off is what vrcode wants
  (thumb swipes must not move the user). Check on the headset that `MicrogesturesSystem` still
  reports taps and swipes with that setting. If vrcode needs a different `VRFeature`, it registers
  its own and skips xmux's, which xmux's embedding guide allows.
- `xmux.registerPanels()` is empty (xmux registers panels as windows mount), so vrcode returns its own
  registrations, such as the Mac display's video panel, next to it.
- Do not use `Scene.lineSegmentIntersect` for xmux windows: on the Quest 3 it passed through a
  screen window and hit the panel behind it.

### Gaps to file against xmux

- **Pane, tab and workspace operations beyond focus.** The wire protocol carries only input, resize
  and focus (`FocusDirection`, `FocusPane`, `FocusTab`). The radial menu's split, close, new-tab and
  workspace items need a new xmux request, or vrcode's Mac service calling cmux directly.
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
and clipboard and screenshot delivery. On request from the Quest it starts or restarts cmux and
`xmux-bridge`, whose status shows on the HUD. The headset reaches it over a separate connection, and
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
- TLS and pairing for its port. xmux-bridge's cover only its own port. Over USB (`adb reverse`)
  this can wait.

## Mac display

A window in VR that shows a Mac display live, so the browser, editor and other Mac apps (cmux's own
window included) stay usable from the headset. It lives inside vrcode because Horizon OS does not
show other apps' panels next to an immersive app.

| Mode | When | What is shown |
|---|---|---|
| Mirror | lid open | the built-in display as it is |
| Virtual | lid closed, or chosen on the Quest | a virtual display the service creates (`CGVirtualDisplay`, a private API that tools such as BetterDisplay and DeskPad use; an HDMI dummy plug is the fallback). Resolution, HiDPI scale and refresh rate are set from the Quest. With the lid closed it is the Mac's only display |

| Stage | Choice |
|---|---|
| Capture (Mac) | ScreenCaptureKit, the chosen display with the cursor, up to 60 fps |
| Encode (Mac) | VideoToolbox HEVC (H.264 fallback), real-time low-latency mode, no B-frames, bitrate adapted to the link (USB with `adb reverse`, or Wi-Fi) |
| Transport | the vrcode service connection; video never shares xmux's stream |
| Decode and show (Quest) | `MediaCodec` hardware decoder into the `Surface` of a `VideoSurfacePanelRegistration` panel (a compositor layer, so text stays sharp); grabbable and resizable like the xmux windows |
| Pointer | the ray is tested against the panel before `xmux.hitTest`; the hit point maps to display coordinates and becomes `CGEvent` mouse moves, clicks, drags and scrolls on the Mac |
| Keyboard | a hardware keyboard paired with the Quest (a closed Mac's keyboard is unusable). While the panel has focus, its events go to the Mac as `CGEvent` keys, with Meta mapped to Command, instead of to `xmux.dispatchKeyEvent`. Check which keys Horizon OS keeps for itself |
| Gestures | right index pinch clicks, pinch-and-hold drags; Enter, Escape and voice keep targeting terminals |
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
  the transcript card's position, hand roles, audio choices and other settings.
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

Vocabulary set by the user on 2026-10-30 (from the original xmux plan, `docs/xmux-plan.md`).

Signals and where they come from:

| Signal | Source | Reliability |
|---|---|---|
| Index pinch | runtime (`XR_FB_hand_tracking_aim` INDEX_PINCHING; ISDK select/grab) | highest; Meta's primary click |
| Middle-finger pinch | runtime per-finger bit + strength (`XR_FB_hand_tracking_aim`), read through our own `xrCreateHandTrackerEXT` via the public OpenXR handles (Spatial SDK does not surface it) | good with an open hand; gate on "index not pinching" and "not fist", with hysteresis |
| Thumb tap / thumb swipes ("fist + thumb") | runtime (`MicrogesturesSystem`): the microgesture pose is a loose fist with the index extended as the thumb's touchpad | good; discrete D-pad events per hand |
| Fist | Spatial SDK `Microgestures.isFist` | good |
| Palm facing the headset + index pinch | runtime system gesture on both hands (SYSTEM_GESTURE flag while the pose is held; ISDK stands down). **Dominant hand** (right by default): opens the OS Universal Menu, consumed by the OS. **Non-dominant hand** (left by default): delivered to the app as a one-frame menu-button press (`MENU_PRESSED`), the hand equivalent of the left controller's menu button; exposed by Spatial SDK as the left menu button where mapped, otherwise read from our JNI hand tracker | left palm + index pinch is the sanctioned app menu gesture and is reliable; right palm + index pinch is the OS's; right palm + **middle** pinch is unreserved but adjacent to the OS gesture, so it needs gating and on-device testing |
| Thumbs up/down | not runtime-detected; no longer used (a MediaPipe landmark classifier remains an option if ever wanted) | - |

Mapping (hands have fixed roles, swappable in settings; "middle pinch" means the middle finger held
on the thumb with the index still up):

| Hand | Gesture | Action |
|---|---|---|
| Right (pointer) | index pinch | click / select (terminals and the Mac display); drag when the ray holds the top bar (ISDK grab handle); corners resize (xmux's window defaults) |
| Right | middle pinch hold + move (index stays up) | radial action menu at the hand: contextual pane actions (cmux pane/tab/workspace operations, i.e. right-click) when the ray is over a pane, otherwise the global set: undo, redo, keyboard, screenshot |
| Right | fist + thumb swipe L/R/F/B | L/R move focus between panes within the hovered screen (`session.focusDirection`); F/B move between windows (`xmux.focus` over `xmux.windows`) |
| Left (input) | index pinch, palm away | start/stop voice transcription into the target pane (pane under the right ray, else last clicked) |
| Left | fist + thumb tap | shutter: capture to the Mac clipboard (see Capture below) |
| Left | thumb swipe right | Enter to the target pane (sends the staged transcript when the card is open) |
| Left | thumb swipe left | Escape to the target pane (clears/closes the card when it is open) |
| Left | middle pinch hold + move (index stays up) | radial macro menu (e.g. Claude Code commands); items run as `TerminalHandle.write` / `keys` |
| Left | palm + **index** pinch (the app menu button, `MENU_PRESSED`) | open/close the vrcode menu (settings popup, see below); runtime-detected, no gating |

Open: the pane swipes cover left and right only, so panes above or below need the radial menu or a
click (`Direction.Up` / `Down` exist in xmux).

Radial hold menu (both hands, same code): on middle-pinch start (per-finger bit with strength
hysteresis, index unpinched) the menu's items appear as floating buttons around the pinch point,
world-locked at that moment; while the pinch is held, the hand's displacement from the start point
in the hand's local frame picks the item by direction once it exceeds a dead zone (~4 cm); the
picked item highlights; releasing the pinch commits it; releasing inside the dead zone cancels. No
palm orientation required, so it never enters the OS system-gesture pose; items are also
pointer-clickable for the pointer hand. Six to eight items per ring; a second ring on a longer move
if ever needed. Until xmux can split, close or create panes and tabs (see the gaps), the contextual
set is limited to focus and tab switching.

Rules: ignore ordinary app gestures while the runtime reports SYSTEM_GESTURE; treat `MENU_PRESSED`
(left palm pinch) as the vrcode menu toggle; require aim validity and tracking confidence.

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
| View | show/hide the transcript card and HUD, re-anchor the layout here, reset the layout |
| Mac and connection | bridge and pairing; status of cmux, `xmux-bridge` and the vrcode service, with restart buttons; keep-awake on/off |

**Capture** (left fist + thumb tap): a screenshot of the user's view including UI needs the Media
Projection API; a photo of the surroundings needs the Passthrough Camera API (Horizon OS v74+,
`CAMERA` + `HEADSET_CAMERA` permissions, 1280×960 or larger). Either image goes to the vrcode Mac
service and onto the Mac clipboard as an image (`NSPasteboard`; `pbcopy` handles only text). Open:
which source the shutter means.

## Voice input

Pinch-to-talk with a staged transcript:

| Stage | Choice |
|---|---|
| Target pane | the pane under the pointer hand's ray when the voice hand pinches (else the last clicked pane); locked with `xmux.focus` until send/discard; outlined on the window; re-targetable while the card is open |
| Capture (Quest) | `AudioRecord` at 16 kHz mono from the speech input chosen in Audio (default: the Quest's built-in mic), `RECORD_AUDIO`, honor `AudioManager.isMicrophoneMute()`. A mic on the Mac skips this and the transport |
| VAD | Silero VAD via ONNX Runtime for Android, or a WebRTC VAD port, on the headset (on the Mac for a Mac-side mic) |
| Transport | Opus via `MediaCodec` (`audio/opus`, API 29+) or Concentus, ~24 kbps, to the vrcode Mac service on its own connection, tagged with the target terminal's id |
| Recognition (Mac) | `SpeechAnalyzer` + `SpeechTranscriber`, on-device; alternatives whisper.cpp (Metal) or sherpa-onnx streaming models; volatile and finalized text go back to the headset's card |
| Recognition (Quest fallback) | sherpa-onnx or whisper.cpp on-device, only for a future Mac-less mode |
| Staging | **nothing is typed into the terminal while listening.** A body-locked transcript card (a Compose panel in front of the user, lazy-follow) shows volatile text in grey and finalized text in white, the target pane's name, and Send / Clear buttons; text is editable in place via the Horizon virtual keyboard |
| Confirm / reject | left thumb swipe right (Enter) or the Send button -> `session.terminal(target).write(text)` then `.keys(Key("enter"))`; left thumb swipe left (Escape) or the Clear button discards; listening stops on silence timeout, on send/clear, or on a second left pinch |
| Feedback | mic glyph in the target window's header (a `WindowChrome` slot); card shows listening / finalizing / sent states; audio never leaves the Mac/Quest pair |

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
- transcript card (Compose) (~120)
- vrcode menu: settings popup, header gear, live apply and sync to the Mac service (~200)
- radial hold menu mechanic + contextual/global/macro item sets (~250, shared by both hands)
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
| M2.5 Voice + gestures | `AudioRecord` + VAD + Opus; SpeechAnalyzer on Mac; left-pinch voice toggle with right-ray targeting; body-locked transcript card with Send/Clear; JNI hand tracker for middle pinch; radial hold menus (contextual, global, macro); Enter/Escape and pane swipes via `MicrogesturesSystem` | 1.5 wk | staged text never reaches a terminal before Send; swipe-right sends to the locked pane only; middle-pinch false-positive rate and radial selection accuracy measured over a 10-minute walking session (target: wrong-item rate under 5%); swipes move cmux focus and the Mac TUI follows |
| Saved layout | own follow loop keyed by `cmuxId`; anchored poses; store written on move end and `onPause` | tbd | quit and relaunch puts every window back within 2 cm, also after a recenter; a screen closed in cmux meanwhile stays gone and a new one gets a free slot; no terminal state is read from local storage |
| Mac display | ScreenCaptureKit + VideoToolbox stream; virtual display for the closed lid; `MediaCodec` video panel; ray clicks and keyboard as `CGEvent`; keeping the Mac awake | tbd | Mac text readable at 1 m; clicks land on the intended control; glass-to-glass latency measured (target under 100 ms over USB); with the lid closed the Mac stays awake for an hour and the virtual display is usable; with the lid open the Mac works as without vrcode |
| Audio | device pickers in the vrcode menu; Mac process tap streamed to the Quest; Mac-side mic option for speech | tbd | switching the speech mic or the output on the Quest takes effect without a restart; with the lid closed Mac app audio plays only in the headset; a Bluetooth device disconnecting mid-dictation falls back to the built-in mic without losing the staged text |
