# Background cmux controls

The Mac runs the cmux daemon and VRcode service. It does not need a focused terminal window, an
attached cmux frontend, or a visible desktop. Quest controls address the selected pane/tab/terminal
by ID through the authenticated VRcode connection, while xmux continues streaming its contents.

Open **Actions** in a window header, **Settings → Terminal actions**, or point at a terminal and
make the right-hand middle-finger pinch. The menu captures that window and pane as its target.
New tabs, splits, run commands, rename, move, zoom, focus, scroll, layout undo, and close all use
background daemon operations. **All 127 actions** includes the complete pinned public catalog,
including inspections, notifications, agents, configuration, browser controls, and stream controls.
Use **Show optional fields** for advanced parameters. Live resources have a named picker; an ID
field is available for resource types absent from the current snapshot. Errors from cmux are shown
in the menu; unsupported daemon capabilities are never replaced with foreground keystrokes.

**Copy screen** and **Copy history** copy text to Quest and Mac clipboards. **Paste** reads the
Quest clipboard; **Paste from Mac** reads the Mac clipboard. Both write directly to the captured
terminal. **Select / find text** gives the Quest its own read-only text view and search/selection
controls. Selection and copy do not depend on a selection in a Mac frontend. The existing
pinch-drag selection also copies to the Quest clipboard. **Send right-click to terminal app**
forwards a right-button press/release to the cell where the menu was opened.

The menu consumes hardware-keyboard input while open, so typing names or search text does not
type into the terminal. Back/Escape closes it. Opening settings, disconnecting, or shutting down
the scene dismisses it. Mutations are sent once, with cmux idempotency keys; an interrupted request
reports an uncertain outcome. **Refresh** checks current state before repeating an operation.
Closing a tab/pane/screen/workspace removes views; **Terminal → Close** explicitly ends a process,
following cmux's resource lifecycle.

Pattern/process waits follow cmux's requested timeout and can be stopped with **Cancel action**.
Cancellation reconnects the control channel to guarantee cleanup of its pending waits and viewer
leases; it refreshes state without replaying the action. Stream controls can stop a single live
stream while leaving the control connection open.

The Mac service starts control before speech permissions or model downloads. Use
`bun run mac:run --control-only --cmux-session main` for terminal controls without speech. The
session name must match the xmux bridge. Existing paired LAN, USB, Wi-Fi ADB, and Bluetooth
connections carry control on the service tunnel; no extra port or pairing is needed.

The [complete interaction inventory](cmux-interactions.md) also records native cmux menu/keyboard
actions and external-provider boundaries. Browser commands are available, but xmux does not render
browser frames. Native sidebar profiles, machine-provider lifecycle, SSH-machine catalogs, and
local plugin installation belong to separate frontend/provider interfaces; they are not claimed as
implemented Quest interfaces.

## Verification

2026-11-01: Swift and Android debug builds, app/settings unit tests, all four menu instrumentation
tests, and the 29-test Bun suite (including real cmux and TLS relay integration) passed.

`CmuxActionsPanelTest` exercises all catalog entries, explicit targeting, exact rename strings,
search, forms, disabled offline actions, and Quest-owned text selection. Emulator captures:
[common actions](screenshots/cmux-actions-common.png), [split form](screenshots/cmux-actions-split.png).

```sh
swift build --package-path mac
VRCODE_CMUX_BINARY="$PWD/mac/.build/debug/vrcode-mac" bun test scripts/tests/cmux-integration.test.ts
./gradlew :app:assembleDebug :app:testDebugUnitTest :settings-ui:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.improve.vrcode.CmuxActionsPanelTest
```

The native integration suite uses an isolated real daemon with no terminal frontend attached. It
checks creation replay keys, layout and focus, text input, copy, scroll, mouse events, resource
closing, viewer resize/release, live stream cancellation, rejected requests, and a dropped mutation
reply that is not replayed. Physical hand recognition and in-headset menu comfort still require a
Quest wearer.
