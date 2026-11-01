# Hand input

Point the **right hand** at a terminal cell and pinch the **middle finger to the thumb** to
right-click its pane. Keep the index finger unpinched. This opens the Quest's **Terminal actions**
menu for copy/paste, text/history, panes, tabs, screens, and workspaces. The window header's
**Actions** button and Settings → **Terminal actions** open the same controls. Holding the pinch
does not repeat. **Send right-click to terminal app** forwards a right-button press/release pair
to the original cell when a terminal application needs its own mouse event.

All these operations talk to the cmux daemon in the background through the paired Mac service.
They do not require a Mac window to be focused. See [the full interaction inventory](cmux-interactions.md)
and [the control protocol](vrcode-protocol.md#background-cmux-control).

Detection uses Meta's **OpenXR `XR_FB_hand_tracking_aim`** extension, through the existing
`org.khronos.openxr:openxr_loader_for_android:1.1.65` headers. The Quest runtime supplies
[`XR_HAND_TRACKING_AIM_MIDDLE_PINCHING_BIT_FB`](https://registry.khronos.org/OpenXR/specs/1.1/man/html/XrHandTrackingAimFlagBitsFB.html).
There is no app-defined fingertip-distance threshold or gesture classifier, and no new dependency.
The JNI bridge creates only an `XR_HAND_RIGHT_EXT` tracker. The Spatial SDK continues supplying
the right pointer's scene-space aim pose and handling normal index clicks.

Right-click is disabled in settings, while the app is paused, without an active tracked right
hand, during index pinches, and during runtime system gestures. After any interruption, release
the pinch before clicking again. Headers, tabs, dividers, padding, and empty space do not receive
terminal right-clicks. The left hand keeps its voice and menu gestures.

`MiddlePinchGateTest` covers held pinches, startup, tracking loss/reacquisition, cancellation, and
blocked index/system gestures. Build with `./gradlew :app:assembleDebug :app:testDebugUnitTest`.
Physical pinch recognition and targeting need a Quest wearer: test right-hand middle vs. index
pinches, a left-hand middle pinch, holding/releasing, tracking loss, and settings/app suspension
against a terminal app that handles right mouse events.

## Left pinch moves windows

The **left hand's index pinch** (palm away from you), or the left controller's trigger or X, only
**moves terminal windows**. Point anywhere on a window (its title bar, its terminal, even its
header buttons) or just past any edge or corner (the same widening band as the edge resize, about
3.4° past the window, all the way around), pinch and drag. The window moves like a title-bar drag:
the grabbed point stays under the pointer at its distance from your head, the window turns to face
you, and reaching out or drawing the hand back pushes or pulls it. Release to leave it there; the
move is saved to cmux. A pinch that closes within 0.3 s of the ray drifting just off the window
still grabs it, and a pinch that drops for less than 0.15 s keeps the drag.

The left pinch never does anything else to a window. It does not click, select text or press
header buttons (the panels only take the right trigger and A, `SpatialConfig.windowClickButtons`),
it never resizes from an edge (only the right hand does), and while the left ray is on a window
or its band, that window's corner handles are off, unless the right ray is on the same window
(then the right hand can still scale it). A panel in front of the window, such as the floating
keyboard, the dock or a voice pane, still takes the left pinch as a press. Moving stands down
while the left palm faces you (its palm menu and quick inputs), while its mic is live, and while
Settings, Terminal actions or window settings are open. A fullscreen window does not move.

Logcat shows `VrcodeWindow` lines `left-hand move: screen N grabbed at (412, 37) px of 1210 x 690,
1.52 m from the head` and `left-hand move: screen N released at 1.80 m` (`LeftHandMove`).

## Palm menus (both hands)

Turn either **palm toward yourself** and touch the **middle finger to the thumb**, keeping the index
finger up. A ring of items appears around the pinch. Before that, a small hint ring (about 1.6 cm, like the
system's menu glyph) sits on the middle fingertip's pad and fills as the fingers close. Actions are **circular icon buttons** about 7 cm out. While pinching,
**move the hand onto an icon** (about 5 cm): its **label pill**, with the action's name, appears
further out on that item's spoke (the line from the centre through the icon), as far past the icon
as the icon is from the centre. Reaching an icon runs nothing by itself. **Hold the pinch on the
icon (anywhere short of its label) for one full second** to run it; the label pill fills from its
icon end meanwhile (the icon itself never fills). Or **move on out to the label** to run it at
once: about 13 cm out, the same in every direction. A label only works once it is showing, so
jumping straight to that spot shows it first. Moving away or switching items resets the fill. A
dropped tracking frame or a brief flicker of the runtime's pinch reading pauses the clock. The
other hand's ray can hover an icon (clock) or a shown label (at once) too; the stretch of spoke
between them keeps the item hovered. **Releasing
the pinch anywhere dismisses the menu without launching**, because the runtime sometimes reports
a release that did not happen. Clicking cannot launch an item either. The chosen item
stays marked (white outline, other items dimmed) for a quarter
second before the ring hides. After an item runs, the hint stays hidden until you let go of the
pinch; a fully filled hint means the runtime reports a full middle pinch.

| Hand | Items (item 0 at the top, then clockwise) |
|---|---|
| Left | **Add terminal** (terminal with a plus), **Close terminal** (terminal with an ×, up and to the right of Add terminal), **Codex**, **Claude Code**, **Bring forward**, **Clear path** (a window at each side with arrows parting them; **Bring back**, arrows pointing in, once windows are aside). Add terminal opens a new cmux screen (a new floating window) and focuses it. Its fresh login shell starts in the focused terminal's current working directory (the foreground program's directory when available, otherwise its shell's). It also copies the focused window’s width, height and corner-handle scale. Without a focused terminal it uses the default terminal size; without a readable directory it uses the default directory. Codex and Claude Code type `codex` / `claude` into the focused terminal when its shell waits at an empty prompt: no program in the foreground (cmux `terminal.process.get`) and nothing typed after the prompt (read off the screen: the cursor follows a prompt character such as `%`, `#`, `>` or `❯`, with nothing after it except a right prompt). Otherwise, or when that cannot be checked, they open a new cmux screen in that same directory and type the command into its fresh login shell. Either way the shell remains when they exit. If the Mac service is unavailable, the Terminal actions panel opens with the error. **Close terminal** closes the focused window's cmux screen, with every pane and tab in it, exactly as the window's title bar × does: the info box above the window says "Closing the screen…", then the window and its dock entry go once cmux removes the screen. Only a shown, focused window is closed; with none focused (or the focused one minimized) it closes nothing and logs `close terminal: no focused window`, rather than pick another window. **Bring forward** (a small window with arrows out of its corners, on the ring's left side) glides the focused window from wherever it is to straight ahead of you at eye height, at Settings' window distance and facing you: where Arrange puts a single window. Only your head's heading counts, not its tilt, because you are looking down at your palm when you pick it. A Follow window keeps following at the new spot; a minimized one is shown; a fullscreen one is already in front and stays as it is. The move is saved to cmux like a drag. **Clear path** clears the way ahead when windows crowd it: the windows in front of you glide out to either side over about half a second, swinging around you like parting curtains. Those right of your heading go right, those left of it go left, each side together and just far enough that its nearest window's inner edge ends 50° from straight ahead. Each keeps its distance, height and order and still faces you; windows already clear, beside or behind you stay put, and a fullscreen window leaves fullscreen first. As with Bring forward, only your head's heading counts. The item then reads **Bring back**: picking it glides every window it moved that is still open back to where it was, including one you dragged meanwhile (a Follow window to its spot around where you are now). Arrange forgets the windows instead. The moves are saved to cmux like drags; where the windows were is kept only until the app closes. Logcat shows `VrcodeWindow` lines `clear path: 5 of 7 shown windows moved aside` and `bring back: 5 windows returned`. |
| Right | **Keyboard** (the same floating keyboard as the dock's), Enter, Esc, Ctrl-C (focused pane), Paste voice, Clear voice |

Ctrl-C uses the `^C` icon and sends one Ctrl+C key press. Claude Code uses that press to interrupt
work or clear the prompt, then requires a quick second press to exit. A fresh one-second menu dwell
misses that confirmation window: in Claude Code 2.1.316, two presses 1.1 seconds apart kept the
session running, while presses 100 ms apart exited cleanly (verified through cmux on 2026-11-01).
Use `/exit` followed by Enter when quitting Claude Code through the headset menus.

Only one ring is open at a time. Neither opens while voice is listening or while Settings or
Terminal actions are open. An index pinch taking over from the middle pinch, a fist, or
losing hand tracking for more than 200 ms cancels; the next ring needs a fresh pinch. Single
dropped tracking frames, which the runtime produces regularly, do not close the ring. Neither do
pinch readings that flicker while the hand moves: a held pinch ends (releases, or cancels for the
index/fist cases) only after 150 ms of readings saying so (a headset log showed the runtime taking 78 ms to report a pinch the joints had already seen). A missing tracking frame or an off pinch
reading pauses the dwell clock, so no action can launch during that grace period. A confirmed
release discards all progress. A pinch must
start with the palm already facing you; turning a held palm-away pinch (the right-click) toward
you does not open a ring. While the right palm faces you, the right-click stands down.

**The rings work while you walk.** A ring, its selection centre and the fingertip hint travel with
your body, not the room: they keep their offset from a neck point 7.5 cm below and 8 cm behind the
eyes (LibOVR's default neck model), so walking, stepping aside or bobbing while pinched carries the
ring along. Only moving the hand relative to your body picks an item. Turning or nodding the head
swings the eyes around that point without moving it, so looking around the ring still moves
nothing, and the ring keeps the orientation it opened with. Turning your whole body during a pinch
swings the hand across the ring like moving the arm would, because no torso heading is tracked.
Before this, the ring stayed where it opened in the room: walking moved the hand away from the
centre, which picked or ran items by accident and left the ring behind (`hands/PieSelection.kt`
`neckPoint`, `PieSelection.follow`).

**Detection uses the runtime's recognizers, not app heuristics.** No Kotlin, C++ or Spatial SDK
library recognizes "palm toward the user + middle pinch" for this stack. Meta's Interaction SDK
pose recognizers exist only for Unity and Unreal; the Spatial SDK exposes neither hand joints nor
per-finger pinches (only index pinch as a button, and `Microgestures`). Meta's open-source
[Ocean `HandGestures`](https://facebookresearch.github.io/ocean/doxygen/_hand_gestures_8h_source.html)
uses a fixed 2.5 cm fingertip distance and has no palm-facing check. So `cpp/hand_aim.cpp` reads
one `XR_EXT_hand_tracking` tracker per hand, in VIEW space, and passes on what the runtime reports:

- **Pinch** (`hands/FingerPinch.kt`, `MiddlePinch`): Meta's `XR_FB_hand_tracking_aim` `MIDDLE_PINCHING` signal
  starts it, and so does `pinchStrengthMiddle` reaching 0.75 (`MIDDLE_START_STRENGTH`) when the
  middle reads stronger than an unflagged index: on-device logs only show the signal at full
  strength (1.0), and a light touch could leave the hint almost full without opening the ring. The
  hint fills with the strength and is full exactly where a pinch starts. The strength also keeps a
  held pinch down (≥ 0.5) through a dropout of the signal. A hint that fills past 60% and empties
  without a pinch logs `middle pinch near miss, hint peaked at N%`, and `opened (…)` lines log the
  flags and strengths, so the threshold can be tuned from headset logs. The index pinch still needs
  `INDEX_PINCHING`. An index pinch blocks a new pinch when `INDEX_PINCHING` is set and
  its strength is at least the middle's. A held pinch only yields to the index once the runtime
  reports `INDEX_PINCHING` *without* `MIDDLE_PINCHING` and a stronger index: a middle pinch can
  saturate both strengths because the index sits beside it. Only if the runtime withholds pinch data (no valid aim, or a
  system gesture with both strengths at zero) do the fingertips' runtime-reported joint radii
  decide contact. A 2026-11-01 headset log showed strengths still reported during the right-palm
  system gesture (`flags=11 index=1.00`), so this fallback should rarely apply. The menu's log
  line (`source=Runtime|Joints`) records which path was used.
- **Palm facing** (`hands/PalmShape.kt`): the runtime's own palm-facing detection
  (`SYSTEM_GESTURE`), or the palm joint's normal within 55° of the direction to the head (MRTK's
  palm-up hand menu test). The normal is the palm joint's −Y axis, which the OpenXR spec defines
  as "perpendicular to palm surface and pointing towards the back of the hand" for +Y. The same
  code therefore serves both hands. The previous right-hand-only version built a normal from a
  cross product of finger joints, mirrored per hand.
- **Fist**: `FistShape` gates the ring, because a rolled fist also puts the middle finger on the
  thumb, and fists belong to voice.

**Why the ring seemed to "deactivate" (2026-11-01).** Moving onto an item ran it and closed the
ring, and because the pinch was still held the hint reappeared at once, drawn at 78% of its ring
at full strength: the success path looked like the menu dropping back to an almost filled circle.
While the ring was open, a single frame of `INDEX_PINCHING` at a strength equal to the middle's
(both at 1.0) cancelled it, and a single frame of `pinchStrengthMiddle` < 0.5 released it. Hand
motion is when the runtime's readings flicker. After an item ran, one such frame also cleared the
"release first" rule, so the still-held pinch could reopen the ring at the hand's new position.
`HandMenuGesture` now debounces the end of a held pinch (`RELEASE_GRACE_MS`), ignores
both-fingers readings, and keeps the hint hidden until release; the ring confirms the chosen item.

No library offers a better signal here. Spatial SDK 0.14's `libMetaSpatialSDKIsdk.so` does contain
Meta's Interaction SDK `FingerPinchGrabAPI`/`FingerPinchGrabRecognizer` (per-finger pinch with
hysteresis), but only as un-headered C++ symbols with no Kotlin or C API, so binding to them would
depend on private class layouts. `XR_EXT_hand_interaction` and `XR_META_hand_tracking_microgestures`
cover the index pinch and thumb microgestures only. The runtime's aim signals stay the detector;
only the app's use of them changed.

Every close is logged with its cause: `palm menu: dwell complete` or `reached label`,
`released (cancelled)`, or `palm menu cancelled: Fist|OtherFinger|TrackingLost`, each
with how far the hand moved. While the ring is open, `palm menu held: flags=… middle=… index=…`
records each change in the runtime's pinch flags, and `middle pinch did not open the palm menu`
explains a palm-facing middle pinch that was ignored.

Fixed in an earlier pass: the right pie converted VIEW-space joints (OpenXR, −Z forward) into the
Spatial scene (+Z forward) without reflecting Z, so the ring and its hint were placed behind the
user's head. `viewToScene` (`hands/PieSelection.kt`) now reflects Z as `SceneAlignment` does. The
menu's right axis is `up × forward` in Spatial's left-handed space; `forward × up` pointed left.

`HandMenuGestureTest` covers the runtime pinch and hold, ignoring close fingertips when the runtime
says no pinch, palm-away versus palm-facing, the `SYSTEM_GESTURE` path, the joint fallback, index
take-over (and both fingers reading as pinched), fists while open, flickering readings while the hand
moves, dropouts versus real tracking loss, and fresh-pinch rules including a flicker after an item ran. `PieSelectionTest`
covers clockwise picking, reach, the VIEW-to-scene conversion and the menu axes at several head
yaws, walking while pinched (the ring follows; the same walk without following reached an item),
and head turns about the neck leaving the centre in place. `MenuDwellTest` covers the one-second deadline, reaching the label (only for the item whose label
is showing, once, not across a reopen), flickers, switching/leaving items, release at the
deadline, reopening, and firing only once. `PieGeometryTest` checks that every label begins as far
past its icon as the icon is from the centre, the same distance out on every spoke, stays on the
panel, and clears the other icons for one to eight items, and what the other hand's ray is on
anywhere along a spoke (`pieRayTarget`). `PieMenuTest` renders the production Compose UI on the Android emulator. It checks
that icons stay circles, that hovering reveals the label that far out along the spoke, and that
the ray stays on the item across the gap and runs it on the shown label. It also checks that only the label carries the progress, that the fill runs from
the icon's end and never over the icon, the four-, six- and eight-item bounds, and that clicking
launches nothing. Selection accuracy, the left ring's cmux launch, and whether a palm-facing middle pinch
ever triggers OS behavior need a Quest wearer. Watch `adb logcat -s vrcode` for
`palm menu opened (Runtime pinch, palm N°)` and the once-a-second `left|right hand facing=…` lines.

Emulator captures: [icon circles](screenshots/palm-quick-actions/icons.png),
[half-filled hover pill](screenshots/palm-quick-actions/hover.png), and
[right-hand actions](screenshots/palm-quick-actions/keys.png).

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.improve.vrcode.pie.PieMenuTest
```

## Quick inputs (left index pinch)

Turn the **left palm toward yourself** and pinch the **index finger to the thumb**: the runtime's
app menu gesture, which used to toggle Settings. A ring of keys opens at the pinch, each where it
points. Move onto one and hold for a second, or move on out along its spoke, to send it to the
focused pane (picked as in the palm menus; releasing cancels). By default:

|  |  |  |
|---|---|---|
| **Esc** |  | **Ctrl-C** |
|  | *Input* |  |
| **Tab** |  | **Enter** |

The ring has eight compass directions, and **Settings → Customization → Quick inputs** chooses
what each one sends, or leaves it empty (see [settings](settings.md)): Esc, Ctrl-C, Tab, Enter,
Shift-Tab, Backspace, the four arrows, Ctrl-D, Ctrl-Z, Ctrl-L, Ctrl-R, Page Up, Page Down, Home,
End, or Keyboard (shows the floating keyboard). Keys stay on their compass directions, and empty
directions draw nothing and pick nothing: moving the hand picks the nearest placed key, so with
the four default keys each one owns a quarter of the ring. A full ring is the 8-way keypad, 45°
per key. Ctrl chords go out as the key with Ctrl (`c` + Ctrl for Ctrl-C), Shift-Tab as `backtab`.
Changes apply to the ring at once, including from VRcode Mac; with every direction empty the
pinch opens nothing and logs `left quick inputs: nothing to open`.

It is the palm menus' gesture driven by the index finger (`HandMenuGesture(IndexPinch)`), sharing the
left hand's tracker with the left palm menu, so the same opening, picking, release, fist and
tracking-loss rules apply. The pinch is the runtime's `INDEX_PINCHING` and `pinchStrengthIndex`,
with the same joint fallback as the middle pinch. When the runtime reports both fingers pinched at
equal strength, the index ring opens and the middle ring does not. A middle pinch taking over a held
index pinch cancels, and the reverse. There is no app hint: the runtime draws its own menu glyph
for this pose. Settings stays on the dock's gear and the controller's menu button; with hand
tracking, the runtime's `MENU_PRESSED` / menu-key echo of this pinch is ignored.

Logs use `left quick inputs` in place of `left palm menu`, for example
`left quick inputs opened (Runtime index pinch, palm 12°)`, and `left quick inputs changed: [Top
right=Ctrl-C, …]` when Settings changes the ring. `QuickInputsTest` checks that the default keys
sit on their diagonals and share the empty directions, that a full ring picks exactly like the
8-way keypad, and the keys each input sends; `QuickInputRingTest` (settings unit tests) covers
storage, swapping and Mac sync. `HandMenuGestureTest` covers the index pinch (opening, the joint
fallback, the system gesture, both fingers at once, take-over). On the emulator, the pie
package's `QuickInputRingTest` renders the default ring on its diagonals and every input in the
catalogue with its label pill inside the ring, and `QuickInputSettingsTest` edits,
swaps, empties, reloads and restores the ring in Settings.

## Pointer cursor, scrolling and text selection

Point the **right hand** (or right controller) at a cmux window to see a white mouse arrow.
Its tip marks the aim ray’s exact panel hit, including headers and tab strips. An **index pinch**
turns it blue. A pinch released in the same cell is a normal left click. What a pinch **dragged**
across a terminal does is **Settings → Customization → Pointer → Pinch and drag**:

- **Scroll** (the default). Keep pinching and move up or down: the text follows the hand, like a
  touchscreen, one row per row the pointer crosses (drag down to see earlier output). The rows go
  to cmux as wheel input (`terminal.input.mouse`, `kind: wheel`) at the cell the pinch started on,
  and keep counting past the pane’s edges. cmux only turns a wheel into a mouse report for an app
  that tracks the mouse, as Claude Code and Codex do, so they scroll their own conversation. A plain
  shell prompt tracks nothing, and a drag there changes nothing: cmux keeps a viewer’s scrollback
  in the frontend (`terminal.viewport.scroll` only moves its inspection viewport, not the attached
  stream xmux draws), and xmux does not render history yet. Drifting sideways out of the starting
  cell cancels the click without scrolling.
- **Select text.** Keep pinching and move across terminal text to select it; a blue highlight
  follows the drag in either direction and across lines. Release to keep the highlight and copy
  the text to the **Quest clipboard**. Terminal Actions → Paste can insert it into a terminal.

Meta's own laser (the thin white ray out from a tracked hand or controller) stays visible while
no mic is live, including when the ray points past every panel. The Interaction SDK only draws it
over a panel: its cursor system (`IsdkDefaultCursorSystem`, SDK 0.14) skips rays that hit nothing.
For those rays `FreeLasers` (`FreeLaser.kt`) draws the SDK's own laser: the same box mesh and
`data/shaders/isdk/pointerlaser` shader, white and blue while pinching. It is drawn at the SDK's
full length, 25 cm, starting 10 cm out from a hand or at a controller's tip. Over a panel the
SDK's laser and cursor take over unchanged, including their fade as a hand comes within touch range.
A hand shows no laser while its palm faces the headset (the runtime's system gesture, which the
palm menus use) or its aim is invalid. While a mic is live, the hand holding it shows no laser and
the free hand keeps only the SDK's laser over panels. `FreeLaserShapeTest` covers the geometry.

**Settings → Customization → Pointer cursor** offers Arrow, Dot, Crosshair, and No cursor.
Visible cursors can be scaled from 75% to 200%, with previews showing the chosen size. Arrow at
100% is the default for existing installs; unsupported saved styles fall back to it. Settings
save automatically and apply to the existing overlay. No cursor hides both the graphic and
pressed-state halo, while click/drag input and tracking-loss cancellation continue normally.
The terminal's text cursor remains a separate Appearance setting.
The emulator settings preview is saved in
[`screenshots/hand-pointer/settings.png`](screenshots/hand-pointer/settings.png).

The pointer and selection overlays are visual only and leave headers, tabs, and wheel input on
their existing SDK paths. Scrolling and selection both use the Interaction SDK's Android down/move/up events,
stays with the starting pane, and clamps at its visible edges. It includes complete wide glyphs,
combining sequences, and emoji without modifying the streamed cells. The current xmux protocol
does not expose soft-wrap metadata, so copying multiple displayed rows joins them with newlines.
Selection covers the visible grid; it does not auto-scroll into scrollback.

The remote `terminal.input.mouse` API only encodes PTY mouse reports; it does not perform the
Mac TUI's frontend selection. The app therefore handles selection locally and sends a mouse
press/release only for a completed click. Selection drags send no mouse reports to terminal
programs, including those that enable mouse tracking. No xmux checkout patch is needed.
Cancellation, tracking loss, settings opening, app pause, a tab switch, resize, or target removal
discard an unfinished selection without copying or clicking. Hardware-keyboard input or a new pinch clears the
previous highlight. Completed selection text remains in the Quest clipboard.

`TerminalTextSelectionTest` checks ranges and Unicode extraction. `TerminalSelectionInputTest`
drives the production Android input binding and renderer, checking highlighted pixels, copy on
release, quick/backward/multiline drags, pane boundaries, cancellation, target replacement,
clicks, and tab handling. Its scroll cases check the wheel rows sent from the starting cell
(including past the pane), that a scroll never clicks, copies or highlights, and that a pinch in
place still clicks. `PinchDrag` defaults and sync are in `AppSettingsTest` and `SettingsSyncTest`.
`PointerCursorRenderingTest` covers the cursor overlay:

```sh
./gradlew :app:testDebugUnitTest :settings-ui:testDebugUnitTest :app:assembleDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.improve.vrcode.TerminalSelectionInputTest,dev.improve.vrcode.PointerCursorRenderingTest
```

The cursor preview is saved in [`screenshots/hand-pointer/hover.png`](screenshots/hand-pointer/hover.png).
The selection test writes `selection-before.png`, `selection-held.png`, and
`selection-released.png` to its connected test output. Live Quest hand-ray alignment and physical
pinch recognition still need a wearer check. Include dragging backwards, across multiple lines,
past a pane edge, releasing outside the panel, and opening settings while holding a pinch.
