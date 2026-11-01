# Window layout in cmux

Where each terminal window is, whether it follows the head, whether it is minimized, and which one
is fullscreen are all cmux state. The Quest stores none of it: every frame it draws the layout cmux
holds, and it turns the user's moves into layout changes. Quitting, reopening or reinstalling vrcode
puts every window back. A layout written by anything else on the Mac is shown live.

Code: `app/src/main/kotlin/dev/improve/vrcode/windows/` (`SceneLayout` is the document and its
operations, `LayoutSync` the cmux reads and writes, `TerminalWindows` the per-frame rendering).

## Where it lives

One cmux [frontend projection](https://github.com/manaflow-ai/cmux/blob/main/cmux-tui/spec/native-frontend.md#persistent-swift-frontend-boundary)
per headset scene, in the session the bridge mirrors (`main`):

| Field | Value |
|---|---|
| `frontend_projection` | `projection_` + 32 hex digits of SHA-256 over the frontend and window ids (fixed per headset) |
| `frontend_id` | `dev.improve.vrcode` |
| `window_id` | `quest-scene-` + 16 hex digits of SHA-256 over the device's `ANDROID_ID`; the same after a reinstall signed with the same key |
| `generation` | a new UUID per app launch |

The ids are derived, never stored, so nothing about the layout is kept on the Quest. Both operations
travel on their own cmux control connection through the vrcode Mac service, which already allows
`frontend_projection.get`/`.put` and `session.events`. No cmux or Mac service change is needed.

## Document

```json
{
  "schema_version": 1,
  "space": "local_floor",
  "fullscreen_screen_id": "screen_5bbdc6a90d00cd0d55f23bac150bff20",
  "windows": {
    "screen_5bbdc6a90d00cd0d55f23bac150bff20": {
      "mode": "fixed",
      "position_m": [0.94, 1.56, 0.82],
      "orientation_xyzw": [0, 0.373, 0, 0.928],
      "minimized": false,
      "scale": 1.25
    }
  }
}
```

- Keys under `windows` are cmux screen ids. xmux's `Screen.cmuxId` is the same id.
- A `fixed` window's pose is in the scene's `LOCAL_FLOOR` space. A `follow` window's
  `position_m` is an offset from the user's position along the `LOCAL_FLOOR` axes, and its
  `orientation_xyzw` is in `LOCAL_FLOOR`: it moves with the user but not with head rotation. The
  user's position is the head's, smoothed (`SceneLayout.userFollow`, `UserFollow`: 40 cm dead
  zone, then a critically damped spring with a 0.35 s smoothing time across the floor and 0.6 s in
  height, stepped by the predicted display time; snaps on a tracking jump over 3 m). The spring
  starts and stops without a jump in speed, and the slower height keeps a walking gait's bob
  (about 2 Hz, 5 cm) to a few millimetres on the windows. Walking steadily, the anchor trails the
  head by about 0.49 m at 1.4 m/s.
- `fullscreen_screen_id` is optional. While it is set, that window is drawn head-locked to fill 74°
  by 58°, and every other window is hidden. Its own pose stays the one it returns to.
- A window's pixel size is not stored: it follows its cmux terminal grid, which dragging an edge
  changes. `scale` is the uniform enlargement from the corner handles on top of that, text
  included (0.2 to 10). It is omitted when it is 1.
- Unknown fields, at the top level or in a window, are written back unchanged. Another frontend or a
  later vrcode can add fields without this build dropping them.
- A document with another `schema_version` or `space` is not overwritten. vrcode shows its default
  arc layout and logs that the placements came from another version.

New terminals launched from the left-hand quick actions open directly along the current head
direction at the configured window distance, facing the wearer, including when looking up or down.
This also applies when Codex or Claude Code needs to create a terminal because the focused one is
busy. Placement uses the tracked head when the new window is ready, after the creation request,
and is saved once; turning afterward does not drag the terminal with the gaze. Creating a terminal
leaves fullscreen so the new window is visible. Existing windows keep their placements; restored
screens and screens created elsewhere still use their saved placement or the default arc.

## When it is read and written

- **Connect:** `frontend_projection.get`, then `session.events` for live changes to this
  projection. Windows wait up to 3 s for the first read before mounting, so they appear in place
  rather than jump. A screen without an entry gets the next free arc slot, and that slot is
  written back.
- **User changes:** title-bar drags, ISDK grabs (detected as a pose the app did not set), corner
  scaling (a scale the app did not set), the pose an edge resize leaves, Arrange,
  dock recalls, minimize, fullscreen, and the Follow/Fixed toggle. Each change updates the local copy
  at once and is written once changes stop for 0.5 s, so a drag is one write rather than one per
  frame. `onPause` writes immediately.
- **Concurrency:** every write sends `expected_projection_revision`. When cmux rejects one (another
  writer was first; cmux reports `operation.failed` with "projection revision conflict"), vrcode
  rereads, keeps its own unsaved windows on top of cmux's copy, and writes again. Stream deltas older
  than the last revision seen, including vrcode's own echoes, are ignored.
- **Closed screens:** placements of screens the bridge no longer lists are dropped on the next
  write.

## Inspecting and editing from the Mac

```sh
# The layout, with its id and revision
cmux --session main --json raw operation session.snapshot \
  --params-json '{"machine":"current","session":"current"}' | jq '.frontend_projections'

# Hide a window on the headset (use the current projection_revision as the expected revision)
cmux --session main --json raw operation frontend_projection.put --mutation --idempotency-key "$(uuidgen)" \
  --params-json '{"machine":"current","session":"current","frontend_projection":"projection_…",
    "frontend_id":"dev.improve.vrcode","window_id":"quest-scene-…","generation":"mac",
    "expected_projection_revision":"3","projection":{…, "windows":{"screen_…":{…,"minimized":true}}}}'
```

The headset logs each step under the `VrcodeWindow` tag: `cmux layout: N windows (revision R)`,
`mounted screen … at its cmux placement`, and `saved N window placements to cmux (revision R)`.

## Title-bar dragging

A pinch/trigger must start on the title bar. The header's Compose gesture only authorizes the
drag; `TitleBarDepthDrag` then follows that hand's current ray once per scene frame. Lateral
movement and push/pull share `TitleBarRayDrag` and one ray/sphere solve. The grabbed panel point
and scale are captured once from the live ray, and stay fixed until physical release. A restarted
header gesture or the other hand cannot replace the holder. Each hand can drag its own window.
Modal UI, suspension, or tracking loss cancels the drag; a cancelled held press must release first.

Applying delayed panel-local pointer coordinates to the latest moving panel pose caused feedback:
a unit-test reproduction with three-frame-old hits put a stationary hand's grab point 0.90 m off
its ray after 60 frames. The new movement path never uses those hits or the panel's next pose as
input. `TitleBarDragTest` covers the delayed-hit reproduction, stationary rays, off-head rays,
wide/rotated/scaled panels, lateral movement after push/pull, and invalid tracking samples;
`TitleBarDragGateTest` covers release, gesture restarts, two-hand ownership, and cancellation.

Startup logs `title-bar drag tracking: hand ray per scene frame`; held drags log `ray drag: screen
… held by the … hand at …` and `released at …` under `VrcodeWindow`. Physical pinch feel requires
a wearer check on Quest: sweep sideways and vertically, stop while still pinching, push/pull,
release outside the window, and repeat with each hand and a wide window.

Validation on 2026-11-01: all 287 app/settings unit tests passed (29 title-bar tests); the debug
build was installed and restarted on the connected Quest 3. Logs confirmed the new tracking path,
physical drags and persisted placements, with no app crash errors observed. Wearer feedback after
trying the installed fix: "Smooth now".

## Still open

- **Recentering:** `LOCAL_FLOOR` moves when the user recenters, and fixed windows move with it.
  A persisted spatial anchor would be a new `space` value in this document.
