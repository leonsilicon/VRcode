# Floating dock

The dock works like an iPhone bottom sheet. At rest it is a short (7 cm), faint pill near the
bottom of the view. A hand reaching it makes it bulge. Pinch the bar and swipe up: the bar follows
the pinch throughout the gesture and is always the sheet's top edge, like a bottom sheet's grabber.
The sheet grows up out of the bar's resting place beneath it. Expanded, the bar sits inside the top
of the sheet, above the options, and can always be pinched and swiped back down. Touching an option
runs it and collapses the dock.

The sheet moves like an iPhone bottom sheet but looks like the macOS Dock. It is a translucent
graphite shelf that hugs a row of square app icons. The user's running terminal windows sit beside
Connection, then a separator, then the tools. Each window has a running dot. Icons magnify under the
fingertip, and the name of the icon a hand is near shows in a tooltip.

## Placement

The bar sits 45 cm from the eyes, within arm's reach so it can be touched, and 44 degrees below
where the head points. The 44 degrees is clamped to 15 to 80 degrees below the horizon. Like the
home bar at the bottom of a phone screen, it stays low in the view rather than at a fixed height in
the room. That is just above the bottom of the Quest 3's view and below where a hand pinches or
grabs to drag a window, so dragging does not catch the bar by accident. It faces the eyes.

It never locks to the head. `DockFollow` leaves it in place while the head turns less than 14
degrees, nods less than 10 degrees down or 4 degrees up, or moves less than 10 cm. The upward
allowance is small because looking up lowers the bar in the view, which would soon push it off the
bottom edge. Past that, yaw, pitch and eye position each ease toward the new target with a 0.45 s
time constant. A small glance stays still and a large look around catches up smoothly, so the
motion is never "trippy". The bar appears in place without animating, and snaps back after a
recenter or teleport. The sheet's swipe does not reposition its placement frame; terminal windows
keep their own Fixed/Follow modes.

While swiping the sheet, its placement frame stays still so head follow never pulls the handle
out of the pinch. The handle travels 11.5 cm upward in the dock's plane. Its centre is always 2 cm
below the sheet's top edge. The sheet's bottom edge stays 1 cm below where the bar rests, so the
sheet grows out of that spot like a sheet rising from the bottom of a phone screen. The options ride
up with the top edge and come into view out of the sheet's bottom edge. The sheet fades in over the
first 20% of the swipe, so it never pops in around the resting bar. At rest only the pill shows.

The bar is always rendered while VRcode is resumed and the head is tracked. That includes while
Settings or the cmux context menu is open, so the user can always see that VRcode loaded and has a
way back to Settings and Arrange.

## Gestures

Pinches come from tracked thumb/index joints and the runtime's index-pinch signal, or a
controller trigger and ray. A pinch must start on the visible handle; one held elsewhere and
swept onto it never grabs it. Option touches come from the Spatial SDK's Interaction SDK, the
same direct touch that types on the floating keyboard. `DockPanel.kt` reports each touch
(`DockTouch`: down, move, up). `DockGesture` uses the collapsed dock's frame: X right, Y up,
and the dock's plane at Z = 0.

| Step | What the user does | What happens |
|---|---|---|
| Hover | a fingertip comes within 6 cm of the bar, or a pointer aims at it | the bar brightens, widens and thickens, and shows a "⌃" cue |
| Raise | pinch the bar and swipe up, directly or along a ray | the bar follows the held pinch one-to-one, including direction reversals, and the sheet grows beneath it |
| Release | let go of the handle | the sheet settles to the nearest end over 180 ms; opening requires an upward swipe, past halfway; taps and sideways sweeps do not open it |
| Choose | bring a fingertip within 6 cm in front of an option, or aim a pointer at it | the icons under the fingertip magnify, like the macOS Dock, and the nearest one's name shows in a tooltip inside the sheet, above the icons and below the handle |
| Activate | touch the option (or pinch on it with a ray) | it runs on contact and the dock lowers; no slide, flick or release is needed |
| Lower | pinch the expanded handle at the top of the sheet (its cue is "⌄") and swipe down | the sheet follows the pinch down and settles closed past halfway; it stays expanded when left alone |

The bar's panel is 12 × 6 cm, larger than the 7 cm pill. Its drawing and hit target move together;
there is no fixed invisible handle left behind. Expanded, its grab zone ends above the row of
options, so a pinch closing on the top of an option never grabs the handle. A plain touch never toggles it. The held pinch
retains ownership even at the top, so the same pinch can reverse all the way back down. Options
are disabled during a swipe, while settling and for 400 ms after release, preventing the pulling
hand from running one accidentally. This guard never prevents another pinch on the handle.
A touch between options runs the highlighted one. A pinching ray on an option runs it through
the SDK's touch delivery, just as direct contact does.

The bulge, the magnification and the tooltip are visual only. They come from the index fingertips (`HandAim` joints, converted from
OpenXR VIEW space with `viewToScene`) and from pointer rays at least 25 cm away, so the near rays of
typing hands just below the dock do not light it up.

Before 2026-11-01 the app detected touches itself from the fingertip joints, without reflecting
VIEW space's Z axis. Every fingertip was placed about 40 cm behind the head, so touching the bar
did nothing and only a pinched ray could raise the dock.

## Options

The options are drawn as macOS Dock icons (`settings-ui/.../DockIcons.kt`). Each is a square tile with
Apple's squircle outline (a superellipse), a top-lit gradient, a hairline edge and a soft shadow.
They sit in one row of 6 cm slots, and each tile is 4.8 cm, four fifths of its slot. Like the macOS
Dock, a separator half a slot wide splits the row between the terminals and the tools. When there
are many windows, the slots shrink to keep the row within 42 cm, and the tiles, dots and separator
shrink with them, so icons never overlap. The shelf behind them hugs the row, with the same margin
at its ends as above and below a tile, but it is never narrower than 30% of the panel, so the
grabber always sits on its top edge.

Under a fingertip or pointer, the nearest icon grows to 1.25×, grown from its bottom edge, and its
neighbours within 1.5 slots grow less (`DockShelf.magnification`). The growth follows the finger
between icons (`DockGesture.pointedX`, in millimetre steps). Unlike macOS, the icons do not spread
apart, so a touch always lands where an icon is drawn. The falloff is narrow enough that magnified
neighbours never overlap. The tooltip is the macOS Dock's name label, kept within the shelf's ends.
In order:

- **Connection** (a Wi-Fi tile, where Finder sits on a Mac): opens Connection settings. Green means
  the terminal bridge is online; amber means it is unavailable. Keyboard, Arrange and Settings stay
  usable offline.
- **One icon per live cmux window**, drawn like Terminal.app's icon: a graphite tile with a `>_`
  prompt and the window's short title (`DockShelf.shortTitle`: the part before " — ", a path's last
  component, or just the number of a default "Terminal N" title). The short title is left out once
  the tiles shrink below about 2 cm. Every window has a running dot under it, and the focused
  window's dot is bright. A minimized window, or one behind a fullscreen window, is drawn
  translucent, like a hidden app in the macOS Dock. While the bridge is offline, windows are
  faded. Titles and removals come
  from xmux. Touching a window glides it (280 ms) to sit just above the dock, its bottom edge
  24 degrees above the bar, at the window's own distance and facing the eyes. This is how to recover
  a window that has gone out of view. The window is also focused in VRcode and in cmux. Its
  Fixed/Follow mode is preserved, and the dock never creates a second terminal or caches terminal
  state. Minimized windows remain in this strip; recalling one unhides it. Recalling any window
  leaves fullscreen, restoring the fullscreen window's previous placement and mode. Closing a
  screen through its header removes it from cmux and from this strip.
- *The separator.*
- **Keyboard** (a silver tile, with a running dot while the keyboard is open): toggles the same keyboard as the right-hand pie and quick inputs. That is Meta's
  system keyboard when possible: `KeyboardFeature` focuses the focused terminal window's view
  (xmux's `ScreenView`, which takes typed text, backspace and keys through its Android input
  connection) and explicitly asks Android to show the soft input, so Meta's keyboard can type
  straight into the focused pane. With no terminal window, while a voice pane is being edited, or if
  Android declines (logged as `system keyboard for screen N: focused=… accepted=false`), VRcode's
  floating keyboard opens just above the dock instead. Android accepting the request is not enough:
  on the Quest 3 (Spatial SDK 0.14) Meta's keyboard turned down every show request for VRcode's
  panels (`ImeTracker … onFailed at PHASE_IME_ON_SHOW_SOFT_INPUT_TRUE`, with nothing reported to the
  app). So the window's IME insets must report it showing within a second (`system keyboard
  showing`); if they do not, the request is withdrawn, the floating keyboard opens, and the rest of
  the session goes straight to the floating keyboard (`system keyboard did not appear within …`). The
  option's selected state follows the pie, the floating keyboard's close button, and Meta's keyboard
  closing itself after it showed (`system keyboard closed`).
- **Arrange** (an indigo tile of windows, after Mission Control): runs the existing terminal
  arrangement action. Faded while offline or without windows.
- **Settings** (a gray gear tile, after System Settings): opens the existing panel and brings it
  back in front of the user, which also recovers a settings panel left out of view.

## Panels and hit testing

The dock uses two pooled panels, independent of terminal reconnects:

- **The bar.** A 12 × 6 cm panel, larger than the drawn pill so it is easier to touch. It is
  always shown and hittable, raised or not, so a pointer never clicks a terminal through it. It
  overlaps the top of the sheet and stands 3 mm in front of it, so rays meet the handle first.
- **The sheet.** A 48 × 14.5 cm panel, the size of the expanded sheet and fixed to the dock's
  anchor. It draws the sheet from its bottom edge up to the handle, so the drawn sheet grows inside
  the panel; the options and label are clipped at the sheet's rounded edges. Expanded, the sheet
  spans 1 cm below to 13.5 cm above the resting bar. The icons are centred 3 cm above it (a
  full-size tile spans 0.6 to 5.4 cm, and magnified it reaches 6.6 cm). The hovered option's label
  is centred 7.6 cm above, about 1.4 cm tall, so it clears a magnified icon below it and the
  handle's panel (its bottom at 8.5 cm) above it. The handle is 11.5 cm above. The sheet is drawn
  only as wide as its shelf, but the panel stays 48 cm wide.

The handle is hittable (`MeshCollision.LineTest`). The sheet only accepts option touches once
fully expanded and released; otherwise it is `MeshCollision.NoCollision`.
`DockFeature.hitDistance` also treats the visible dock as foreground geometrically: the bar, and
while raised the sheet from its bottom edge up to its current top. That uses the panel's full
48 cm width rather than the narrower shelf, matching the quad the SDK hits.

Both panels are compositor layers. By default the SDK draws overlapping layers by their distance
from the head, which would put the sheet over the handle (the sheet's centre is nearer the eyes than
the handle's). `DockFeature` raises the handle's layer to `zIndex` 1 once the SDK has created it,
through `PanelSceneObject.getLayer()`, logging "dock bar layer raised over the sheet". Every other
panel keeps the default 0, so the pill also draws over a window that comes in front of it.

There is no invisible full-size quad in front of terminals. Terminal cursors, right-click and
context-menu gestures, and left-pinch voice paste treat the visible dock as a foreground panel. They
do not act through it on a terminal behind it.

Other overlays stack against `DockFeature.currentPose()` (the eased collapsed anchor), falling back to
`DockPlacement.pose(head)`, with `DockPlacement.WIDTH_M` and `HEIGHT_M` (the expanded dock, up to the
top of the handle's panel, 14.5 cm above the anchor):

- Voice panes float above the mic in the fist instead (`voice/MicPanePlacement.kt`); only a pane
  with no mic position yet falls back to 1.5 cm above that footprint.
- The floating keyboard sits 2 cm above it.

**Never remove `IsdkGrabbable` from a panel.** The dock originally called
`removeComponent<IsdkGrabbable>()`. The SDK's panel handle system still read the component on its
next tick and aborted the app on every launch (`Failed to get component IsdkGrabbable ... wrong data
type found`, 2026-11-01). Both dock panels set `IsdkGrabbable(enabled = false)` instead.

## Code

| File | Owns |
|---|---|
| `app/.../dock/DockPlacement.kt` | the placement, the sheet's layout and how it grows with the handle, footprints, ray and distance math |
| `app/.../dock/DockFollow.kt` | the lazy yaw, pitch and position follow |
| `app/.../dock/DockGesture.kt` | `DockLayout` (option spacing, the separator, shrinking a full row), continuous pinch swipes and release settling, hover, option highlights, the fingertip's position for magnification, and touches |
| `app/.../dock/DockRecall.kt` | the window recall target and glide |
| `app/.../dock/DockFeature.kt` | mounts the panels, raises the handle's layer over the sheet, queues their touches for the scene tick, switches which panel is hittable, reads xmux state and fingertips (from `HandAim` joints), dispatches the options |
| `settings-ui/.../DockPanel.kt` | the platform-independent `DockBar` (the grabber) and `DockBody` (the sheet: the shelf, separator, icons, dots, magnification and tooltip) Compose UI, and `DockTouch` reporting |
| `settings-ui/.../DockIcons.kt` | the macOS Dock look: `DockShelf` (proportions, magnification, short titles), the squircle `DockTile` and its glyphs, the running dots and the tooltip |

`MainActivity` passes each hand's pointer ray (for the bulge), ticks the dock before the other
features, and applies the recall glide each frame.

## Existing projects checked

Searched on 2026-11-01 for "cmux dock", "cmux-dock", and floating/VR toolbar variants before
implementing this panel:

- [cmux Dock](https://github.com/manaflow-ai/cmux/blob/main/docs/dock.md) is a right sidebar for
  terminal/browser surfaces and split layouts. Its `.cmux/dock.json` seeds Mac-side controls;
  it does not provide a headset-following launcher.
- [SideRail's cmux host](https://github.com/KaxyotiK/siderail/blob/main/docs/CMUX.md) supplies a Git
  TUI inside that sidebar. It likewise has no spatial placement or Quest input integration.

Neither fits the requested spatial toolbar. The implementation uses the existing xmux spatial
panel API and vrcode actions, without adding a dock dependency or patching xmux.

## Verification

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :settings-ui:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.improve.vrcode.DockPanelTest
```

| Test | Checks |
|---|---|
| `DockGestureTest` | continuous swipe travel; held reversal at the top; release settling; direct and ray swipes down; immediate regrab during settling; multi-hand ownership; tap and sideways-sweep rejection; expansion persisting while idle; option guards and activation; the handle staying the sheet's top edge while the sheet's bottom stays put; the label clearing a magnified icon and the handle; the sheet's fade; a pinch on the top of the row not grabbing the expanded handle; the separator's gap and a full row shrinking; touches only over a full row's shrunken icons; the fingertip's position for magnification; VIEW-space conversion; recall |
| `DockShelfTest` | the magnification's falloff, neighbours never overlapping, the shelf's even margins, and short window titles |
| `DockPlacementTest` | the gaze-relative pose and its clamping, footprints, and bar and sheet ray hits during the swipe |
| `DockFollowTest` | the dead zones, easing, settling, following a large look down, wrap-around turns, snapping and stalled frames |
| `DockPanelTest` | the bar at rest, bulging with its "⌃" cue, and raised with its "⌄" cue; the expanded sheet with the bar over its top, at the headset's pixel scale: square tiles of four fifths of a slot, each named; the shelf hugging them; a hovered icon magnified most and its neighbour less, with its name; an end icon's name kept inside the shelf; half raised; sixteen icons shrunk without overlapping; that both panels report a touch on contact, before any release, at the right position. It saves captures to the test app's external `screenshots` directory, which `connectedDebugAndroidTest` deletes when it uninstalls the test app: to keep them, run `:settings-ui:installDebugAndroidTest`, then `adb shell am instrument -w -e class dev.improve.vrcode.DockPanelTest dev.improve.vrcode.settings.test/androidx.test.runner.AndroidJUnitRunner`, and pull `/sdcard/Android/data/dev.improve.vrcode.settings.test/files/screenshots` |

Verified on 2026-11-01:
- `:app` compiled, and the app unit tests (119) and settings-ui tests passed.
- The original `DockPanelTest` cases passed on `emulator-5554`. Their captures showed the label
  covering the grabber, so the label was moved into a pill above the body. Those captures predate
  the bottom-sheet swipe interaction.

Bottom-sheet revision verified on 2026-11-01: app/settings unit tests (313 total) and debug APK
build passed. Installed and restarted on Quest 3 (`100.67.153.133:5555`); startup reported
`dock bottom sheet ready: 23 cm swipe, handle above options`. The Quest screenshot tool failed
with a closed response channel, so worn-headset visual and hand-tracking verification remains.

Attached-handle revision (the bar is the sheet's top edge) verified on 2026-11-01: the app unit
tests (290) passed, the debug APK built, and `DockPanelTest` (3) passed on `emulator-5554`. Its
captures show the grabber inside the top of one rounded sheet, the label between it and the row,
and, half raised, the icons rising out of the sheet's bottom edge. No headset was connected, so it
is not yet installed on the Quest.

macOS Dock revision (square icons on a shelf) verified on 2026-11-01: the app unit tests (304),
including `DockGestureTest` (37), and the settings-ui unit tests, including `DockShelfTest` (4),
passed; the debug APK built; and `DockPanelTest` (4) passed on `emulator-5554`. Its captures show:
- the squircle tiles, with Terminal.app-style windows labelled `zsh` and `claude`, and the hidden
  `claude` window translucent;
- the running dots, with the focused window's and the open keyboard's bright;
- the separator before Keyboard;
- a hovered icon magnified, with its tooltip between the row and the grabber;
- sixteen shrunken icons that do not overlap.

With many windows the small icons leave empty sheet above them, because the sheet's height is set by
the grabber's travel rather than shrinking like the macOS Dock. Installed on the Quest 3
(`2G0YC5ZG0L01ZS`, USB) at 15:48:20 and relaunched. With its proximity sensor briefly overridden,
startup reported `dock bottom sheet ready: 12 cm swipe, handle attached to the sheet's top,
macOS-style icons` and no crash. It has not yet been checked while worn.

Still needs a worn-headset check for the bottom-sheet revision:
- that pinching and swiping moves the handle and sheet with the hand, in both directions;
- that the expanded handle is reachable above the options and can always be grabbed again;
- that the pill draws over the sheet (the raised `zIndex`), not hidden behind it;
- that touching an option runs it, and that hovering shows the right label first;
- that the magnification follows the fingertip smoothly, and the tiles' short titles and the
  tooltip are legible at 45 cm;
- that typing hands never raise the dock.

Capture the headset with `adb shell am startservice -n com.oculus.metacam/.capture.CaptureService -a
TAKE_SCREENSHOT` while it is worn. Captures land in `/sdcard/Oculus/Screenshots`. `VrcodeDock` log
lines report "dock bottom sheet ready", "dock bar layer raised over the sheet", "dock bar pinched", "dock bar released at N%", "dock
raised", "dock option …" and "dock lowered".
