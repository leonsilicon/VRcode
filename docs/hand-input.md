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

## Palm menus (both hands)

Turn either **palm toward yourself** and touch the **middle finger to the thumb**, keeping the index
finger up. A ring of items appears around the pinch, with a hint above the middle fingertip that
fills as the fingers close. While pinching, **move the hand onto an item** (about 7 cm) to run it at
once, or move toward one (past the 3.5 cm centre circle) and release. Releasing in the centre
circle cancels.

| Hand | Items (item 0 at the top, then clockwise) |
|---|---|
| Left | **Terminal**, **Codex**, **Claude Code**. Each opens a new cmux screen (a new floating window) and focuses it. Codex and Claude Code are started by typing `codex` / `claude` into that new login shell, so the shell remains when they exit. Nothing is sent to an existing terminal. If the Mac service is unavailable, the Terminal actions panel opens with the error. |
| Right | **Keyboard** (the same floating keyboard as the dock's), Enter, Esc, Ctrl-C (focused pane), Paste voice, Clear voice |

Only one ring is open at a time. Neither opens while voice is listening or while Settings or
Terminal actions are open. An index pinch that is stronger than the middle pinch, a fist, or
losing hand tracking for more than 200 ms cancels; the next ring needs a fresh pinch. Single
dropped tracking frames, which the runtime produces regularly, do not close the ring. A pinch must
start with the palm already facing you; turning a held palm-away pinch (the right-click) toward
you does not open a ring. While the right palm faces you, the right-click stands down.

**Detection uses the runtime's recognizers, not app heuristics.** No Kotlin, C++ or Spatial SDK
library recognizes "palm toward the user + middle pinch" for this stack. Meta's Interaction SDK
pose recognizers exist only for Unity and Unreal; the Spatial SDK exposes neither hand joints nor
per-finger pinches (only index pinch as a button, and `Microgestures`). Meta's open-source
[Ocean `HandGestures`](https://facebookresearch.github.io/ocean/doxygen/_hand_gestures_8h_source.html)
uses a fixed 2.5 cm fingertip distance and has no palm-facing check. So `cpp/hand_aim.cpp` reads
one `XR_EXT_hand_tracking` tracker per hand, in VIEW space, and passes on what the runtime reports:

- **Pinch** (`hands/MiddlePinch.kt`): Meta's `XR_FB_hand_tracking_aim` `MIDDLE_PINCHING` signal
  starts it. Its `pinchStrengthMiddle` keeps a held pinch down (≥ 0.5) through a dropout of that
  signal and fills the hint. An index pinch only cancels when `INDEX_PINCHING` is set and its
  strength is at least the middle's. Only if the runtime withholds pinch data (no valid aim, or a
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

Fixed in this pass: the right pie converted VIEW-space joints (OpenXR, −Z forward) into the
Spatial scene (+Z forward) without reflecting Z, so the ring and its hint were placed behind the
user's head. `viewToScene` (`hands/PieSelection.kt`) now reflects Z as `SceneAlignment` does. The
menu's right axis is `up × forward` in Spatial's left-handed space; `forward × up` pointed left.

`HandMenuGestureTest` covers the runtime pinch and hold, ignoring close fingertips when the runtime
says no pinch, palm-away versus palm-facing, the `SYSTEM_GESTURE` path, the joint fallback, index
dominance, fists, dropouts versus real tracking loss, and fresh-pinch rules. `PieSelectionTest`
covers clockwise picking, reach, the VIEW-to-scene conversion and the menu axes at several head
yaws. Selection accuracy, the left ring's cmux launch, and whether a palm-facing middle pinch
ever triggers OS behavior need a Quest wearer. Watch `adb logcat -s vrcode` for
`palm menu opened (Runtime pinch, palm N°)` and the once-a-second `left|right hand facing=…` lines.

## Pointer cursor and text selection

Point the **right hand** (or right controller) at a cmux window to see a white mouse arrow.
Its tip marks the aim ray's exact panel hit, including headers and tab strips. An **index pinch**
turns it blue. Keep pinching and move across terminal text to select it; a blue highlight follows
the drag in either direction and across lines. Release to keep the highlight and copy the text
to the **Quest clipboard**. Terminal Actions → Paste can insert it into a terminal.
A pinch released in the same cell remains a normal left click.

**Settings → Customization → Pointer cursor** offers Arrow, Dot, Crosshair, and No cursor.
Visible cursors can be scaled from 75% to 200%, with previews showing the chosen size. Arrow at
100% is the default for existing installs; unsupported saved styles fall back to it. Settings
save automatically and apply to the existing overlay. No cursor hides both the graphic and
pressed-state halo, while click/drag input and tracking-loss cancellation continue normally.
The terminal's text cursor remains a separate Appearance setting.
The emulator settings preview is saved in
[`screenshots/hand-pointer/settings.png`](screenshots/hand-pointer/settings.png).

The pointer and selection overlays are visual only and leave headers, tabs, and wheel input on
their existing SDK paths. Selection uses the Interaction SDK's Android down/move/up events,
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
clicks, and tab handling. `PointerCursorRenderingTest` covers the cursor overlay:

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
