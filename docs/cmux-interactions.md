# cmux interaction coverage

Source: [cmux public API and UI inventory](https://github.com/manaflow-ai/cmux/tree/adc65e8aa59f2347b68cb8014c0980d634f19bc7/cmux-tui/spec), pinned to the same SDK revision as xmux. Regenerate with `bun scripts/sync-cmux-catalog.ts /path/to/cmux`.

All 127 public transported operations are carried over one persistent Unix-socket connection per Quest control connection. Reads, mutations, streams, cancellation, and connection-scoped viewer leases use the original protocol. Mutation idempotency keys survive transport unchanged; mutations are never automatically replayed after a disconnect.

The Quest Actions panel provides common controls and searchable forms for the full catalog. Selectors use explicit resource IDs from a live session snapshot. Logical cmux focus changes daemon state; it does not activate any macOS app or window.

Terminal actions show progress and failures in an info box above the targeted floating terminal.
This includes quick-action launches (Claude Code, Codex and new terminals), closing a terminal,
resizing its grid, clipboard actions and operations launched from Terminal actions. Running actions
and live streams stay visible until they finish; successful actions clear their notice immediately.
Errors stay for ten seconds, with Dismiss to close them sooner. The box follows the terminal's position, rotation and size,
hides with the terminal, and keeps its captured target when focus changes. Creation moves its
message from the source window to the new terminal once mounted. An action with no window to
target (none is shown, or VRcode isn't connected to the Mac) uses one box placed 1 m ahead of the
user at the moment it appears, level with their heading; logcat tag `VrcodeTerminalInfo` logs
`info box ahead of the user (no target window)`. Concurrent actions retain their
own messages. AI mic thinking stacks above an existing info box (or stays below if configured).
Failures never open Terminal actions automatically; that panel opens only when requested.

## Public operations

| Operation | Kind | Quest route |
| --- | --- | --- |
| `agent.list` | read | Actions → All actions → generated form |
| `agent.report` | mutation | Actions → All actions → generated form |
| `browser.activate` | mutation | Actions → All actions → generated form |
| `browser.attach` | stream_open | Actions → All actions → generated form |
| `browser.back` | mutation | Actions → All actions → generated form |
| `browser.close` | mutation | Actions → All actions → generated form |
| `browser.forward` | mutation | Actions → All actions → generated form |
| `browser.get` | read | Actions → All actions → generated form |
| `browser.input.key` | mutation | Actions → All actions → generated form |
| `browser.input.mouse` | mutation | Actions → All actions → generated form |
| `browser.input.text` | mutation | Actions → All actions → generated form |
| `browser.input.wheel` | mutation | Actions → All actions → generated form |
| `browser.list` | read | Actions → All actions → generated form |
| `browser.navigate` | mutation | Actions → All actions → generated form |
| `browser.reload` | mutation | Actions → All actions → generated form |
| `browser.viewer.release` | connection_control | Actions → All actions → generated form |
| `browser.viewer.resize` | connection_control | Actions → All actions → generated form |
| `client.cell_pixels.set` | connection_control | Actions → All actions → generated form |
| `client.detach` | connection_control | Actions → All actions → generated form |
| `client.get` | read | Actions → All actions → generated form |
| `client.list` | read | Actions → All actions → generated form |
| `client.metadata.update` | connection_control | Actions → All actions → generated form |
| `client.sizing.release` | connection_control | Actions → All actions → generated form |
| `client.sizing.set` | connection_control | Actions → All actions → generated form |
| `frontend_projection.get` | read | Actions → All actions → generated form |
| `frontend_projection.put` | mutation | Actions → All actions → generated form |
| `machine.get` | read | Actions → All actions → generated form |
| `machine.list` | read | Actions → All actions → generated form |
| `notification.ack` | mutation | Actions → All actions → generated form |
| `notification.clear` | mutation | Actions → All actions → generated form |
| `notification.create` | mutation | Actions → All actions → generated form |
| `notification.list` | read | Actions → All actions → generated form |
| `pairing_request.list` | read | Actions → All actions → generated form |
| `pairing_request.resolve` | mutation | Actions → All actions → generated form |
| `pane.close` | mutation | Actions → All actions → generated form |
| `pane.create` | mutation | Actions → All actions → generated form |
| `pane.focus` | mutation | Actions → All actions → generated form |
| `pane.focus_direction` | mutation | Actions → All actions → generated form |
| `pane.get` | read | Actions → All actions → generated form |
| `pane.list` | read | Actions → All actions → generated form |
| `pane.neighbor.get` | read | Actions → All actions → generated form |
| `pane.rename` | mutation | Actions → All actions → generated form |
| `pane.run` | mutation | Actions → All actions → generated form |
| `pane.split` | mutation | Actions → All actions → generated form |
| `pane.split_ratio.set` | mutation | Actions → All actions → generated form |
| `pane.swap` | mutation | Actions → All actions → generated form |
| `pane.viewport_width.set` | mutation | Actions → All actions → generated form |
| `pane.zoom` | mutation | Actions → All actions → generated form |
| `request.cancel` | connection_control | Actions → All actions → generated form |
| `screen.close` | mutation | Actions → All actions → generated form |
| `screen.create` | mutation | Actions → All actions → generated form |
| `screen.focus` | mutation | Actions → All actions → generated form |
| `screen.get` | read | Actions → All actions → generated form |
| `screen.layout.export` | read | Actions → All actions → generated form |
| `screen.layout.undo` | mutation | Actions → All actions → generated form |
| `screen.list` | read | Actions → All actions → generated form |
| `screen.rename` | mutation | Actions → All actions → generated form |
| `session.creation.resolve` | read | Actions → All actions → generated form |
| `session.events` | stream_open | Actions → All actions → generated form |
| `session.get` | read | Actions → All actions → generated form |
| `session.journal.append` | mutation | Actions → All actions → generated form |
| `session.journal.checkpoint.create` | mutation | Actions → All actions → generated form |
| `session.journal.checkpoint.list` | read | Actions → All actions → generated form |
| `session.journal.hook.list` | read | Actions → All actions → generated form |
| `session.journal.hook.put` | mutation | Actions → All actions → generated form |
| `session.journal.producer.list` | read | Actions → All actions → generated form |
| `session.journal.producer.put` | mutation | Actions → All actions → generated form |
| `session.journal.restore.preview` | read | Actions → All actions → generated form |
| `session.journal.segment.list` | read | Actions → All actions → generated form |
| `session.journal.segment.seal` | mutation | Actions → All actions → generated form |
| `session.journal.subscribe` | stream_open | Actions → All actions → generated form |
| `session.list` | read | Actions → All actions → generated form |
| `session.open` | mutation | Actions → All actions → generated form |
| `session.ping` | read | Actions → All actions → generated form |
| `session.reload_config` | mutation | Actions → All actions → generated form |
| `session.shutdown` | mutation | Actions → All actions → generated form |
| `session.snapshot` | read | Actions → All actions → generated form |
| `session.terminal_defaults.update` | mutation | Actions → All actions → generated form |
| `session.window.title.clear` | mutation | Actions → All actions → generated form |
| `session.window.title.set` | mutation | Actions → All actions → generated form |
| `sidebar_view.attach` | stream_open | Actions → All actions → generated form |
| `sidebar_view.ensure` | mutation | Actions → All actions → generated form |
| `sidebar_view.get` | read | Actions → All actions → generated form |
| `sidebar_view.input` | mutation | Actions → All actions → generated form |
| `sidebar_view.reload` | mutation | Actions → All actions → generated form |
| `sidebar_view.resize` | mutation | Actions → All actions → generated form |
| `stream.cancel` | connection_control | Actions → All actions → generated form |
| `tab.close` | mutation | Actions → All actions → generated form |
| `tab.create_browser` | mutation | Actions → All actions → generated form |
| `tab.create_terminal` | mutation | Actions → All actions → generated form |
| `tab.focus` | mutation | Actions → All actions → generated form |
| `tab.get` | read | Actions → All actions → generated form |
| `tab.list` | read | Actions → All actions → generated form |
| `tab.move` | mutation | Actions → All actions → generated form |
| `tab.rename` | mutation | Actions → All actions → generated form |
| `terminal.attach` | stream_open | Actions → All actions → generated form |
| `terminal.close` | mutation | Actions → All actions → generated form |
| `terminal.copy` | read | Actions → All actions → generated form |
| `terminal.get` | read | Actions → All actions → generated form |
| `terminal.history.clear` | mutation | Actions → All actions → generated form |
| `terminal.history.read` | read | Actions → All actions → generated form |
| `terminal.input.focus` | mutation | Actions → All actions → generated form |
| `terminal.input.keys` | mutation | Actions → All actions → generated form |
| `terminal.input.mouse` | mutation | Actions → All actions → generated form |
| `terminal.input.write` | mutation | Actions → All actions → generated form |
| `terminal.list` | read | Actions → All actions → generated form |
| `terminal.move` | mutation | Actions → All actions → generated form |
| `terminal.output_read` | read | Actions → All actions → generated form |
| `terminal.process.get` | read | Actions → All actions → generated form |
| `terminal.project` | mutation | Actions → All actions → generated form |
| `terminal.renderer_grant.create` | connection_control | Actions → All actions → generated form |
| `terminal.screen.read` | read | Actions → All actions → generated form |
| `terminal.state.read` | read | Actions → All actions → generated form |
| `terminal.viewer.release` | connection_control | Actions → All actions → generated form |
| `terminal.viewer.resize` | connection_control | Actions → All actions → generated form |
| `terminal.viewport.scroll` | mutation | Actions → All actions → generated form |
| `terminal.wait` | read | Actions → All actions → generated form |
| `terminal.wait_exit` | read | Actions → All actions → generated form |
| `workspace.close` | mutation | Actions → All actions → generated form |
| `workspace.create` | mutation | Actions → All actions → generated form |
| `workspace.focus` | mutation | Actions → All actions → generated form |
| `workspace.get` | read | Actions → All actions → generated form |
| `workspace.layout.apply` | mutation | Actions → All actions → generated form |
| `workspace.list` | read | Actions → All actions → generated form |
| `workspace.move` | mutation | Actions → All actions → generated form |
| `workspace.rename` | mutation | Actions → All actions → generated form |
| `workspace.run` | mutation | Actions → All actions → generated form |

## cmux frontend actions

These are the complete native TUI and context-menu inventories, including actions that belong to a frontend or an external provider rather than the session API.

| Surface | Action | Upstream route / ownership |
| --- | --- | --- |
| Keyboard | SendPrefix | composite: frontend prefix config + active surface + send-key |
| Keyboard | NewTab | direct: new-tab |
| Keyboard | NewBrowserTab | composite: frontend omnibar + new-browser-tab |
| Keyboard | NewPaneSmart | composite: list-workspaces + new-pane |
| Keyboard | NextTab | direct: select-tab delta:+1 |
| Keyboard | PrevTab | direct: select-tab delta:-1 |
| Keyboard | SelectTab | direct: select-tab index |
| Keyboard | SplitRight | direct: split dir:right |
| Keyboard | SplitDown | direct: split dir:down |
| Keyboard | CloseTab | direct: close-surface |
| Keyboard | ClosePane | direct: close-pane |
| Keyboard | RenameTab | composite: frontend prompt + rename-surface |
| Keyboard | RenameScreen | composite: frontend prompt + rename-screen |
| Keyboard | RenameWorkspace | composite: frontend prompt + rename-workspace |
| Keyboard | CloseScreen | direct: close-screen |
| Keyboard | PrevScreen | direct: select-screen delta:-1 |
| Keyboard | NextScreen | direct: select-screen delta:+1 |
| Keyboard | SelectScreen | direct: select-screen index |
| Keyboard | NewScreen | direct: new-screen |
| Keyboard | PrevWorkspace | direct: select-workspace delta:-1 |
| Keyboard | NextWorkspace | direct: select-workspace delta:+1 |
| Keyboard | NewWorkspace | composite: {"ownership_source":"active-workspace-session","session_owned":{"kind":"mux-command","operation":"new-workspace"},"provider_owned":{"kind":"machine-provider-request","operation":"create_workspace"},"unknown_ownership":"reject"} |
| Keyboard | CloseWorkspace | composite: {"ownership_source":"active-workspace-session","session_owned":{"kind":"mux-command","operation":"close-workspace"},"provider_owned":{"kind":"machine-provider-request","operation":"delete_workspace"},"unknown_ownership":"reject"} |
| Keyboard | ToggleSidebar | presentation-only: frontend action adapter |
| Keyboard | ToggleSidebarCompact | presentation-only: frontend action adapter |
| Keyboard | ProviderMenu | presentation-only: frontend machine provider menu |
| Keyboard | ToggleSidebarView | presentation-only: frontend action adapter |
| Keyboard | FocusSidebar | presentation-only: frontend action adapter |
| Keyboard | NewPaneRight | direct: new-pane-right |
| Keyboard | UndoLayout | direct: undo-layout |
| Keyboard | FocusLeft | composite: frontend geometry + focus-pane |
| Keyboard | FocusRight | composite: frontend geometry + focus-pane |
| Keyboard | FocusUp | composite: frontend geometry + focus-pane |
| Keyboard | FocusDown | composite: frontend geometry + focus-pane |
| Keyboard | FocusNextPane | composite: list-workspaces + focus-pane |
| Keyboard | SwapPanePrev | composite: list-workspaces + swap-pane |
| Keyboard | SwapPaneNext | composite: list-workspaces + swap-pane |
| Keyboard | ZoomPane | direct: zoom-pane |
| Keyboard | ResizeGrow | composite: list-workspaces + set-split-ratio |
| Keyboard | ResizeShrink | composite: list-workspaces + set-split-ratio |
| Keyboard | ScrollUp | presentation-only: frontend viewport adapter; scroll-surface for shared local viewport |
| Keyboard | ScrollDown | presentation-only: frontend viewport adapter; scroll-surface for shared local viewport |
| Keyboard | ClearHistory | direct: clear-history |
| Keyboard | BrowserBack | direct: browser-back |
| Keyboard | BrowserForward | direct: browser-forward |
| Keyboard | BrowserReload | direct: browser-reload |
| Keyboard | BrowserEditUrl | composite: frontend prompt + browser-navigate |
| Keyboard | ShowShortcuts | presentation-only: frontend shortcut overlay |
| Keyboard | Detach | presentation-only: close frontend transport |
| Keyboard | UserCommand | composite: frontend command config + run |
| Menu | RenameClientMachine | composite: frontend prompt + client-local machine catalog |
| Menu | RenameManagedMachine | external-protocol: machine-provider rename_machine |
| Menu | DeleteManagedMachine | external-protocol: machine-provider delete_machine |
| Menu | RestoreManagedMachine | external-protocol: machine-provider restore_machine |
| Menu | PurgeManagedMachine | external-protocol: machine-provider purge_machine |
| Menu | RenameWorkspace | composite: frontend prompt + rename-workspace |
| Menu | RenameManagedWorkspace | external-protocol: machine-provider rename_workspace plus provider mirror commit |
| Menu | CopyWorkspaceId | presentation-only: tree snapshot + frontend clipboard |
| Menu | CloseWorkspace | composite: workspace ownership: close-workspace or machine-provider delete_workspace |
| Menu | DeleteManagedWorkspace | external-protocol: machine-provider delete_workspace |
| Menu | RestoreManagedWorkspace | external-protocol: machine-provider restore_workspace |
| Menu | PurgeManagedWorkspace | external-protocol: machine-provider purge_workspace |
| Menu | RenameScreen | composite: frontend prompt + rename-screen |
| Menu | CloseScreen | direct: close-screen |
| Menu | BrowserBack | direct: browser-back |
| Menu | BrowserForward | direct: browser-forward |
| Menu | BrowserReload | direct: browser-reload |
| Menu | BrowserEditUrl | composite: frontend omnibar + browser-navigate |
| Menu | BrowserCopyUrl | presentation-only: browser state + frontend clipboard |
| Menu | BrowserActivate | direct: browser-activate |
| Menu | RenameTab | composite: frontend prompt + rename-surface |
| Menu | RenameSurface | composite: frontend prompt + rename-surface |
| Menu | CopyTabId | presentation-only: tree snapshot + frontend clipboard |
| Menu | CopyPaneId | presentation-only: tree snapshot + frontend clipboard |
| Menu | CopyStatusMessage | presentation-only: status snapshot + frontend clipboard |
| Menu | NewPaneSmart | composite: list-workspaces + new-pane |
| Menu | NewTab | direct: new-tab |
| Menu | NewBrowserTab | composite: frontend omnibar + new-browser-tab |
| Menu | SplitRight | direct: split dir:right |
| Menu | SplitDown | direct: split dir:down |
| Menu | CloseTab | direct: close-surface |
| Menu | ClosePane | direct: close-pane |
| Menu | TogglePaneZoom | direct: zoom-pane explicit mode |
| Menu | ToggleSidebar | presentation-only: frontend action adapter |
| Menu | ToggleSidebarCompact | presentation-only: frontend action adapter |
| Menu | FocusSidebar | presentation-only: frontend action adapter |
| Menu | ActivateSidebarProfile | presentation-only: frontend sidebar profile composition |
| Menu | SetSidebarViewVisible | presentation-only: frontend per-profile view visibility |
| Menu | ShowShortcuts | presentation-only: frontend shortcut overlay |
| Menu | SetClientSizing | direct: set-client-sizing |
| Menu | UseClientSize | direct: set-client-sizing exclusive:true |
| Menu | RestoreAllClientSizing | direct: set-client-sizing enabled:true without client |
| Menu | DisconnectClient | composite: self: close frontend transport; peer: detach-client |
| Menu | SetSizeMode | direct: set-size-policy |
| Menu | SetSizeCounts | direct: set-size-counts participant |
| Menu | DisconnectSizeParticipant | composite: self: close frontend transport; peer: detach-client participant |
| Menu | SelectProviderScope | external-protocol: machine-provider select_scope |
| Menu | InvokeProviderAction | external-protocol: machine-provider invoke_action |
| Menu | CreateMachineFrom | composite: native source picker + client-local machine catalog |
| Menu | ConnectMachineTarget | composite: SSH config picker + client-local machine connector |
| Menu | ConnectOtherMachine | composite: frontend prompt + client-local machine connector |
| Menu | RunConfigured | composite: frontend configured menu + the referenced action's own route |
| Menu | MoveTabToWorkspace | direct: move-tab-to-workspace |

## Frontend and external boundaries

Clipboard copy/paste and selectable/searchable terminal text run on the Quest; explicit Mac clipboard transfer uses background NSPasteboard access. The existing Quest settings own window presentation, movement, and text sizing. Disconnect closes the Quest connection. Browser control operations are transported, but xmux has no browser-frame renderer; this change does not claim a Quest browser view. Provider-managed machines/workspaces, local plugin installation, SSH-machine catalogs, native sidebar profiles, and native window presentation belong to separate provider or frontend APIs, not cmux.protocol/2; they are inventoried above rather than sent as fake terminal keystrokes. Terminal selection is not settable through the public API: use Quest's selectable text/history view instead of depending on a selection in a Mac frontend.
