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
      "minimized": false
    }
  }
}
```

- Keys under `windows` are cmux screen ids. xmux's `Screen.cmuxId` is the same id.
- A `fixed` window's pose is in the scene's `LOCAL_FLOOR` space. A `follow` window's pose is
  relative to the head.
- `fullscreen_screen_id` is optional. While it is set, that window is drawn head-locked to fill 74°
  by 58°, and every other window is hidden. Its own pose stays the one it returns to.
- Size is not stored: a window's size follows its cmux terminal grid.
- Unknown fields, at the top level or in a window, are written back unchanged. Another frontend or a
  later vrcode can add fields without this build dropping them.
- A document with another `schema_version` or `space` is not overwritten. vrcode shows its default
  arc layout and logs that the placements came from another version.

## When it is read and written

- **Connect:** `frontend_projection.get`, then `session.events` for live changes to this
  projection. Windows wait up to 3 s for the first read before mounting, so they appear in place
  rather than jump. A screen without an entry gets the next free arc slot, and that slot is
  written back.
- **User changes:** title-bar drags, ISDK grabs (detected as a pose the app did not set), Arrange,
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

## Still open

- **Recentering:** `LOCAL_FLOOR` moves when the user recenters, and fixed windows move with it.
  A persisted spatial anchor would be a new `space` value in this document.
