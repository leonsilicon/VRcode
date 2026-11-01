# Terminal appearance

Open **Settings → Appearance**, then scroll below Window style. All choices save automatically
and apply to existing terminals and newly opened panes that follow app defaults. Window style
remains independent of terminal colors. A terminal's own gear opens a scrollable **Window settings**
top sheet with overrides for only that terminal: style, theme, text size, cursor, and Fixed/Follow.
**Use app defaults** clears those overrides. They last for the mounted terminal and survive
closing the sheet and resizing the panel, but reset on reconnect.

- **Follow Mac** is the default and keeps the Mac's terminal colors.
- Eight offline presets: Catppuccin Mocha, Dracula, Nord, Tokyo Night, Gruvbox Dark,
  Solarized Dark, Catppuccin Latte, and Solarized Light. Cards show each preset's text,
  background, and cursor; the larger preview uses the production terminal renderer.
- **Text size:** 18–36 panel pixels, default 25, using bundled JetBrains Mono. Changing size
  expands or contracts the terminal panel at the existing pixel density, retaining its rows,
  columns, session, and position. It does not resize the remote Mac terminal.
  The preview updates while dragging; the saved size applies to terminal panels on release.
- **Cursor:** Follow app (default), Block, Bar, or Underline. Cursor visibility still follows
  the app, so a program hiding its cursor stays hidden.
- **Reset terminal appearance** resets these three choices while preserving window style,
  placement, connection, and voice settings. Workspace's Restore default settings resets all
  preferences.

The presets change the terminal's default foreground, background, and cursor colors, plus its
header, tabs, and pane focus outline. Explicit app colors are preserved. The bridge sends resolved
RGB colors, rather than ANSI palette indices, so these are default-color presets, not replacements
for all 16 ANSI colors. Applications such as editors with their own opaque color schemes retain
those schemes. No RGB matching heuristic or mutation of the streamed grid is used.

## Open source terminal research

Checked 2026-11-01:

| Terminal | Theme workflow | What VRcode adopts |
| --- | --- | --- |
| [Ghostty](https://ghostty.org/docs/features/theme) | Ships hundreds of built-in themes from iTerm2 Color Schemes; supports separate light and dark themes. | Recognizable, bundled presets with both light and dark choices. |
| [kitty](https://sw.kovidgoyal.net/kitty/kittens/themes/) | Its built-in themes picker previews themes interactively and applies colors to running terminals. | Preview before choosing and apply to open terminals. |
| [iTerm2](https://iterm2.com/documentation-preferences-profiles-colors.html) | Ships color presets and supports importing/exporting them. | Simple preset selection independent of window controls. |
| [Alacritty](https://github.com/alacritty/alacritty-theme) | Maintains a separate theme collection imported through configuration. | Curate and bundle a small collection, without requiring users to edit configuration files. |

The exact Ghostty-format source files and collection license are preserved in
[`assets/terminal-themes`](../assets/terminal-themes). `SOURCE` pins the upstream commit.
Only default foreground/background/cursor and the blue accent are used; VRcode does not claim to
install the full source palettes. The MIT notice also ships in the APK's `terminal-themes` assets.

## xmux integration

The local xmux checkout requires [`patches/xmux-terminal-appearance.patch`](../patches/xmux-terminal-appearance.patch).
It adds render-only `GridAppearance` overrides to `TerminalGridView` and `XmuxSpatial.setFontSize`.
Apply [`patches/xmux-window-settings.patch`](../patches/xmux-window-settings.patch) after the existing
header, appearance, and window-visibility patches. It adds a body overlay slot to `WindowChrome`,
per-window terminal input suspension, and `XmuxSpatial.setFontSize(window, sizeOrNull)` so changing
one terminal does not resize its neighbors. The overlay shares the terminal's panel, pose, and
viewport constraints; it does not mount a separate app-settings panel.
Use `git apply --unidiff-zero` for the window-settings patch: its additive font API declaration
uses minimal context to coexist with other extensions to the xmux API.
The patch includes the corresponding public API dumps. It is independent of the existing custom
header patch; both are required with an older checkout. From the VRcode root:

```sh
git -C ../../leonsilicon/xmux apply "$(pwd)/patches/xmux-custom-header.patch"
git -C ../../leonsilicon/xmux apply "$(pwd)/patches/xmux-terminal-appearance.patch"
```

These changes are already applied to the current local checkout. Do not apply them twice.

The app reapplies the current display overrides after each topology update, including newly
created views and panes. Renderer defaults remain separate from the bridge's grid metadata, so
remote updates cannot overwrite the user's theme and switching back to Follow Mac immediately
uses the latest remote defaults. Text-size changes recreate the panel views while retaining the
session bindings and poses. Header slots and control callbacks remain intact.

## Verification

Use the JDK and SDK paths in `MEMORY.md`:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :settings-ui:testDebugUnitTest
ANDROID_SERIAL=emulator-5554 ./gradlew :settings-ui:connectedDebugAndroidTest
```

The emulator needs at least a 1000×760 dp viewport; see [settings.md](settings.md). Appearance tests
exercise every theme, text size, cursor selection, preference reload after recreating UI state,
unknown preference migration, and independent reset. Pixel checks verify local defaults,
preservation of explicit foreground/background colors, inverse and invisible text, cursor shape,
remote default updates, and restoration of the latest Mac colors. The UI suite writes
`terminal-appearance-light.png` and `terminal-appearance-dark.png` alongside the other settings
screenshots in the connected test output. Headset text comfort and physical sizing still need
verification by a wearer.
