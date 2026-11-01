package dev.improve.vrcode

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.vrcode.pie.PieIcon
import dev.improve.vrcode.pie.PieItem
import dev.improve.vrcode.pie.PieMenu
import dev.improve.vrcode.pie.PieState
import dev.improve.xmux.cell.grid.Attr
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.connect.panel.ConnectStatus
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.GridAppearance
import dev.improve.xmux.terminal.grid.TerminalGridView
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import dev.improve.xmux.window.frame.WindowFrame
import java.io.File
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

/**
 * Renders the headset's real panels with staged content for the Devpost gallery: terminal windows,
 * Settings, the dock and the palm menus, each on a transparent background at 2-3x the headset's
 * pixel scale. Captures go to the test app's external `devpost` directory; DEVPOST.md's images
 * are composed from them. It asserts nothing.
 */
class DevpostShowcaseTest {
  @get:Rule val compose = createComposeRule()

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val out = File(context.getExternalFilesDir(null), "devpost").apply { mkdirs() }

  private class Shot { lateinit var layer: GraphicsLayer }

  /** Records [content] into a layer, so a capture is not clipped to the emulator's window. */
  @Composable
  private fun Captured(shot: Shot, content: @Composable () -> Unit) {
    val layer = rememberGraphicsLayer()
    shot.layer = layer
    // Unbounded, so the layer takes the content's own size rather than the emulator window's.
    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).drawWithContent {
      layer.record { this@drawWithContent.drawContent() }
      drawLayer(layer)
    }) { content() }
  }

  private fun save(shot: Shot, name: String) {
    compose.mainClock.advanceTimeBy(1500)
    compose.waitForIdle()
    val bitmap = runBlocking(Dispatchers.Main) { shot.layer.toImageBitmap() }.asAndroidBitmap()
    File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  private fun mount(density: Float, content: @Composable () -> Unit) {
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(density)) { content() }
    }
  }

  // ---- Terminal windows ---------------------------------------------------------------------

  @Test fun terminalWindows() {
    var current by mutableStateOf<Scene?>(null)
    val shot = Shot()
    mount(2f) { Captured(shot) { current?.let { scene -> key(scene) { TerminalWindow(scene) } } } }
    for ((name, scene) in listOf(
      "window-claude.png" to Scene("claude · ~/vrcode", TerminalTheme.TokyoNight, claudeSession(), focused = true),
      "window-tests.png" to Scene("tests", TerminalTheme.CatppuccinMocha, testRun(), focused = false),
      "window-git.png" to Scene("git", TerminalTheme.Dracula, gitLog(), focused = false),
      "window-codex.png" to Scene("codex", TerminalTheme.Nord, codexSession(), focused = false),
      "window-shell.png" to Scene("zsh · ~/vrcode", TerminalTheme.Mac, aiTypedShell(), focused = true),
      "window-claude-inactive.png" to Scene("claude · ~/vrcode", TerminalTheme.TokyoNight, claudeSession(), focused = false),
    )) {
      compose.runOnIdle { current = scene }
      save(shot, name)
    }
  }

  private class Scene(val title: String, val theme: TerminalTheme, val grid: CellGrid, val focused: Boolean)

  @Composable
  private fun TerminalWindow(scene: Scene, opacity: Float = 0.94f) {
    val ctx = LocalContext.current
    val font = remember { TerminalFont(ctx, 30f) }
    val theme = scene.theme
    val state = remember { TerminalWindowState(scene.title).apply { loading = false } }
    val chrome = WindowChrome(
      bodyColor = 0x00000000,
      onClose = {}, onMinimize = {}, onZoom = {},
      trailingSlot = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          TextButton(onClick = {}) { Text("Actions") }
          WindowModeButton(WindowMode.Follow, theme) {}
          WindowSettingsButton(tint = Color(theme.accent)) {}
        }
      },
      header = { s, c -> VrcodeWindowHeader(s, WindowStyle.MacOS, c.withTerminalTheme(theme, opacity), focused = scene.focused) },
      outline = { windowOutline(scene.focused, theme) },
    )
    val probe = remember { TerminalGridView(ctx, scene.grid, font) }
    val density = LocalDensity.current
    val width = with(density) { probe.contentWidth.toDp() }
    val height = with(density) { probe.contentHeight.toDp() } + chrome.headerHeightDp.dp
    WindowFrame(state, Modifier.requiredSize(width, height), chrome, body = { c ->
      TerminalGridView(c, scene.grid, font).apply {
        appearance = GridAppearance(theme.foreground.toInt(), theme.background.toInt(), theme.cursor.toInt())
        backgroundOpacity = opacity
        focused = scene.focused
      }
    })
  }

  /** Writes styled segments line by line into a [CellGrid]. */
  private class Screen(cols: Int, rows: Int, private val fg: Int) {
    val grid = CellGrid(cols, rows).apply { setCursorVisible(false) }
    private var row = 0
    fun line(vararg segments: Seg) {
      var col = 0
      for (s in segments) col = grid.write(row, col, s.text, s.fg.takeIf { it != 0 } ?: fg, s.bg, s.attrs)
      row++
    }
    fun text(text: String, color: Int = fg, attrs: Int = 0) = line(Seg(text, color, attrs = attrs))
    fun blank(n: Int = 1) { row += n }
    fun cursor(col: Int) { grid.setCursorVisible(true); grid.setCursor(row - 1, col) }
  }

  private class Seg(val text: String, val fg: Int = 0, val bg: Int = 0, val attrs: Int = 0)

  private fun c(hex: Long) = hex.toInt()

  private fun claudeSession(): CellGrid {
    val fg = c(0xFFC0CAF5); val dim = c(0xFF565F89); val orange = c(0xFFD97757); val green = c(0xFF9ECE6A)
    val red = c(0xFFF7768E); val blue = c(0xFF7AA2F7); val cyan = c(0xFF7DCFFF); val purple = c(0xFFBB9AF7)
    val addBg = c(0xFF1F3B2C); val delBg = c(0xFF4A2230)
    val w = 88
    val s = Screen(w, 31, fg)
    fun boxLine(content: List<Seg>, inner: Int = 56) {
      val len = content.sumOf { it.text.codePointCount(0, it.text.length) }
      s.line(Seg("│ ", orange), *content.toTypedArray(), Seg(" ".repeat((inner - len).coerceAtLeast(0)) + " │", orange))
    }
    s.text("╭" + "─".repeat(58) + "╮", orange)
    boxLine(listOf(Seg("✻ ", orange), Seg("Welcome to Claude Code!", fg, attrs = Attr.BOLD)))
    boxLine(listOf())
    boxLine(listOf(Seg("  /help for help, /status for your current setup", dim, attrs = Attr.ITALIC)))
    boxLine(listOf())
    boxLine(listOf(Seg("  cwd: ", dim), Seg("~/Developer/vrcode", fg)))
    s.text("╰" + "─".repeat(58) + "╯", orange)
    s.blank()
    s.line(Seg("> ", dim), Seg("make the dock magnify icons under my hand, like macOS", fg))
    s.blank()
    s.line(Seg("⏺ ", fg), Seg("I'll read the dock layout, then add the magnification curve.", fg))
    s.blank()
    s.line(Seg("⏺ ", green), Seg("Read", fg, attrs = Attr.BOLD), Seg("(settings-ui/src/main/kotlin/dev/improve/vrcode/DockPanel.kt)", fg))
    s.line(Seg("  ⎿  ", dim), Seg("Read 318 lines", dim))
    s.blank()
    s.line(Seg("⏺ ", green), Seg("Update", fg, attrs = Attr.BOLD), Seg("(DockPanel.kt)", fg))
    s.line(Seg("  ⎿  ", dim), Seg("Updated ", dim), Seg("DockPanel.kt", fg, attrs = Attr.BOLD), Seg(" with ", dim), Seg("6 additions", green), Seg(" and ", dim), Seg("2 removals", red))
    val diff = listOf(
      Triple("161", "-", "  val scale = 1f"),
      Triple("162", "-", "  DockIcon(option, scale)"),
      Triple("161", "+", "  val d = (x - anchor[0]) / slot"),
      Triple("162", "+", "  val scale = 1f + (DockShelf.magnification(d) - 1f) * magnify"),
      Triple("163", "+", "  DockIcon(option, scale, label = d.absoluteValue < 0.5f)"),
    )
    for ((n, sign, code) in diff) {
      val bg = if (sign == "+") addBg else delBg
      val color = if (sign == "+") green else red
      val body = "$sign $code"
      s.line(Seg("      $n ", dim), Seg(body.padEnd(w - 11), color, bg))
    }
    s.blank()
    s.line(Seg("⏺ ", green), Seg("Bash", fg, attrs = Attr.BOLD), Seg("(./gradlew :settings-ui:connectedDebugAndroidTest)", fg))
    s.line(Seg("  ⎿  ", dim), Seg("DockPanelTest ", fg), Seg("✔ 5 passed", green), Seg("  ·  BUILD SUCCESSFUL in 41s", dim))
    s.blank()
    s.line(Seg("✻ ", orange), Seg("Reinstalling on the Quest…", orange), Seg(" (18s · ↓ 3.1k tokens · esc to interrupt)", dim))
    s.blank()
    s.text("╭" + "─".repeat(w - 2) + "╮", dim)
    s.line(Seg("│ ", dim), Seg("> ", fg), Seg(" ".repeat(w - 6), fg), Seg(" │", dim))
    s.cursor(4)
    s.text("╰" + "─".repeat(w - 2) + "╯", dim)
    s.line(Seg("  ⏵⏵ ", purple), Seg("accept edits on", purple), Seg(" (shift+tab to cycle)", dim), Seg(" ".repeat(18)), Seg("◉ ", cyan), Seg("Opus 5.5", blue))
    return s.grid
  }

  private fun testRun(): CellGrid {
    val fg = c(0xFFCDD6F4); val dim = c(0xFF6C7086); val green = c(0xFFA6E3A1); val blue = c(0xFF89B4FA)
    val mauve = c(0xFFCBA6F7); val yellow = c(0xFFF9E2AF)
    val s = Screen(64, 24, fg)
    s.line(Seg("❯ ", mauve), Seg("bun test scripts/tests", fg, attrs = Attr.BOLD))
    s.line(Seg("bun test ", fg, attrs = Attr.BOLD), Seg("v1.4.4", dim))
    s.blank()
    val files = listOf(
      "relay-integration.test.ts" to listOf("both services multiplex and preserve binary data" to "3.12", "fragmented authentication and headers" to "1.08"),
      "quest-link.test.ts" to listOf("reads the package's lastUpdateTime from dumpsys" to "0.41", "finds only the app's own crash lines" to "0.22"),
      "ai-agent.test.ts" to listOf("an agent error comes back as an ERROR" to "12.7", "an agent that exits fails its request" to "9.83"),
      "assets.test.ts" to listOf("every delivered GLB meets the asset catalog" to "48.2", "GLB parser rejects truncation" to "0.67"),
    )
    for ((file, tests) in files) {
      s.line(Seg("scripts/tests/", dim), Seg(file, blue), Seg(":", dim))
      for ((name, ms) in tests) s.line(Seg("✓ ", green), Seg(name, fg), Seg(" [${ms}ms]", dim))
      s.blank()
    }
    s.line(Seg(" 80 pass", green, attrs = Attr.BOLD))
    s.line(Seg("   0 fail", dim))
    s.line(Seg(" 311 ", fg), Seg("expect() calls", dim))
    s.line(Seg("Ran 80 tests across 12 files. ", fg), Seg("[2.84s]", yellow))
    s.line(Seg("❯ ", mauve))
    s.cursor(2)
    return s.grid
  }

  private fun gitLog(): CellGrid {
    val fg = c(0xFFF8F8F2); val dim = c(0xFF6272A4); val yellow = c(0xFFF1FA8C); val green = c(0xFF50FA7B)
    val pink = c(0xFFFF79C6); val cyan = c(0xFF8BE9FD); val purple = c(0xFFBD93F9); val orange = c(0xFFFFB86C)
    val s = Screen(60, 22, fg)
    s.line(Seg("~/vrcode ", purple), Seg("main ", pink), Seg("❯ ", green), Seg("git log --graph --oneline", fg))
    val rows = listOf(
      Triple("* ", "1646f65", "Document VRcode, Quest interactions"),
      Triple("* ", "a0f5484", "Improve window controls, AI voice"),
      Triple("* ", "2f81a26", "Quest interactions, agent workflows"),
      Triple("*   ", "9c41d0e", "Merge branch 'palm-quick-inputs'"),
      Triple("|\\  ", "", ""),
      Triple("| * ", "3e01c7f", "Left-palm quick inputs, providers"),
      Triple("| * ", "7be20aa", "Dock: magnify icons under hand"),
      Triple("* | ", "5cccc1f", "Connection setup and Mac tooling"),
      Triple("|/  ", "", ""),
      Triple("* ", "d12bc89", "Add the Quest app and Mac service"),
    )
    val graphColors = listOf(pink, cyan)
    for ((g, sha, msg) in rows) {
      val segs = mutableListOf<Seg>()
      g.forEachIndexed { i, ch -> segs += Seg(ch.toString(), graphColors[(i / 2) % 2]) }
      if (sha.isNotEmpty()) { segs += Seg(sha, yellow); segs += Seg(" $msg", fg) }
      s.line(*segs.toTypedArray())
    }
    s.blank()
    s.line(Seg("~/vrcode ", purple), Seg("main ", pink), Seg("❯ ", green), Seg("git diff --stat", fg))
    s.line(Seg(" settings-ui/…/DockPanel.kt    | 8 ", fg), Seg("++++++", green), Seg("--", c(0xFFFF5555)))
    s.line(Seg(" settings-ui/…/DockShelf.kt    | 21 ", fg), Seg("+++++++++++", green))
    s.line(Seg(" app/…/dock/DockFeature.kt     | 4 ", fg), Seg("+++", green), Seg("-", c(0xFFFF5555)))
    s.line(Seg(" 3 files changed, ", fg), Seg("31 insertions(+)", green), Seg(", ", fg), Seg("3 deletions(-)", c(0xFFFF5555)))
    s.blank()
    s.line(Seg("~/vrcode ", purple), Seg("main ", pink), Seg("✚3 ", orange), Seg("❯ ", green))
    s.cursor(16)
    return s.grid
  }

  private fun codexSession(): CellGrid {
    val fg = c(0xFFD8DEE9); val dim = c(0xFF616E88); val blue = c(0xFF88C0D0); val green = c(0xFFA3BE8C); val purple = c(0xFFB48EAD)
    val s = Screen(54, 16, fg)
    s.line(Seg(">_ ", fg, attrs = Attr.BOLD), Seg("OpenAI Codex", fg, attrs = Attr.BOLD), Seg("  ~/xmux", dim))
    s.blank()
    s.line(Seg("user", purple, attrs = Attr.BOLD))
    s.text("why do resized windows reflow one frame late?")
    s.blank()
    s.line(Seg("• ", blue), Seg("Explored", fg, attrs = Attr.BOLD))
    s.line(Seg("  └ ", dim), Seg("Search ", blue), Seg("onGridResize in packages", fg))
    s.line(Seg("    ", dim), Seg("Read ", blue), Seg("TerminalGridView.kt, ScreenView.kt", fg))
    s.blank()
    s.line(Seg("codex", green, attrs = Attr.BOLD))
    s.text("The view measures before the new grid arrives, so")
    s.text("the first frame draws the old size. Requesting a")
    s.text("layout when the grid is swapped fixes it.")
    s.blank()
    s.line(Seg("▌ ", blue), Seg("Ask Codex to do anything", dim))
    s.line(Seg("  ⏎ send   ⌃J newline   ⌃C quit", dim))
    return s.grid
  }

  /** A shell where the AI mic just typed a command it read off the screen; Enter is left to the user. */
  private fun aiTypedShell(): CellGrid {
    val fg = c(0xFFD4D4D4); val dim = c(0xFF7F848E); val green = c(0xFF98C379); val blue = c(0xFF61AFEF)
    val magenta = c(0xFFC678DD); val yellow = c(0xFFE5C07B)
    val s = Screen(70, 14, fg)
    fun prompt(vararg cmd: Seg) = s.line(Seg("~/vrcode ", blue), Seg("main ", magenta), Seg("❯ ", green), *cmd)
    prompt(Seg("adb devices -l", fg))
    s.text("List of devices attached")
    s.line(Seg("2G0YC1ZF9K0123", yellow), Seg("   device usb:1-1 product:eureka model:", fg), Seg("Quest_3", green))
    s.line(Seg("emulator-5554", dim), Seg("    device product:sdk_gphone64_arm64", dim))
    s.blank()
    prompt(Seg("git status --short", fg))
    s.line(Seg(" M ", c(0xFFE06C75)), Seg("settings-ui/src/main/kotlin/dev/improve/vrcode/DockPanel.kt", fg))
    s.line(Seg(" M ", c(0xFFE06C75)), Seg("app/src/main/kotlin/dev/improve/vrcode/dock/DockFeature.kt", fg))
    s.blank()
    val typed = "bun scripts/_quest.ts --serial 2G0YC1ZF9K0123"
    prompt(Seg(typed, fg))
    s.cursor(16 + typed.length)
    return s.grid
  }

  // ---- Settings --------------------------------------------------------------------------------

  @Test fun settingsPanels() {
    var settings by mutableStateOf(AppSettings(aiProvider = AiProvider.Claude, terminalTheme = TerminalTheme.TokyoNight))
    var page by mutableStateOf(SettingsPage.Customization)
    var tab by mutableStateOf(CustomizationTab.AiMic)
    val shot = Shot()
    mount(2f) {
      Captured(shot) {
        Box(Modifier.requiredSize(1000.dp, 760.dp)) {
          SettingsPanel(settings, page, ConnectStatus.Idle, emptyList(),
            onPage = { page = it }, onChange = { settings = it },
            customizationTab = tab, onCustomizationTab = { tab = it },
            onArrange = {}, onConnect = {}, onDisconnect = {}, onClose = {})
        }
      }
    }
    save(shot, "settings-ai-mic.png")
    compose.onNodeWithTag("settings-ai-provider-${AiProvider.Claude.storageValue}").performScrollTo()
    save(shot, "settings-ai-provider.png")
    compose.onNodeWithTag("settings-ai-thinking-${AiThinking.Above.storageValue}").performScrollTo()
    save(shot, "settings-ai-thinking.png")
    compose.runOnIdle { tab = CustomizationTab.TextMic }
    save(shot, "settings-text-mic.png")
    compose.runOnIdle { tab = CustomizationTab.QuickInputs }
    save(shot, "settings-quick-inputs.png")
    compose.runOnIdle { page = SettingsPage.Appearance }
    save(shot, "settings-appearance.png")
    compose.runOnIdle { page = SettingsPage.Workspace }
    save(shot, "settings-workspace.png")
  }

  // ---- Dock ------------------------------------------------------------------------------------

  @Test fun dock() {
    val options = listOf(
      DockOption("Connected", DockOptionKind.Connection),
      DockOption("claude · ~/vrcode", DockOptionKind.Window, selected = true),
      DockOption("tests · ~/vrcode", DockOptionKind.Window),
      DockOption("git · ~/vrcode", DockOptionKind.Window),
      DockOption("codex · ~/xmux", DockOptionKind.Window, hidden = true),
      DockOption("Keyboard", DockOptionKind.Keyboard),
      DockOption("Arrange", DockOptionKind.Arrange),
      DockOption("Settings", DockOptionKind.Settings),
    )
    val separatorBefore = 5
    val spacing = min(0.06f, 0.42f / (options.size + DockShelf.SEPARATOR))
    val start = -(options.size - 1 + DockShelf.SEPARATOR) * spacing / 2f
    val xs = List(options.size) { i -> (start + (i + if (i >= separatorBefore) DockShelf.SEPARATOR else 0f) * spacing + 0.24f) / 0.48f }
    val slot = spacing / 0.48f
    val separatorX = (xs[separatorBefore - 1] + xs[separatorBefore]) / 2f
    var highlighted by mutableStateOf<Int?>(1)
    val shot = Shot()
    mount(3f) {
      Captured(shot) {
        Box(Modifier.requiredSize(576.dp, 174.dp)) {
          DockBody(options, xs, slot, highlighted, rowY = 0.724f, labelY = 0.407f, separatorX = separatorX, hoverX = highlighted?.let { xs[it] })
          Box(Modifier.offset(x = 216.dp, y = (-12).dp).requiredSize(144.dp, 72.dp)) { DockBar(0f, raised = true) }
        }
      }
    }
    save(shot, "dock-hover-claude.png")
    compose.runOnIdle { highlighted = 7 }
    save(shot, "dock-hover-settings.png")
  }

  // ---- Palm menus --------------------------------------------------------------------------------

  @Test fun palmMenus() {
    val newMenu = PieState("New", listOf(
      PieItem("Add terminal", PieIcon.AddTerminal) {}, PieItem("Close terminal", PieIcon.CloseTerminal) {},
      PieItem("Codex", PieIcon.Codex) {}, PieItem("Claude Code", PieIcon.Claude) {},
      PieItem("Bring forward", PieIcon.BringForward) {}, PieItem("Clear path", PieIcon.ClearPath) {},
    ))
    val keys = PieState("Keys", listOf(
      PieItem("Keyboard", PieIcon.Keyboard) {}, PieItem("Enter", PieIcon.Enter) {}, PieItem("Esc", PieIcon.Escape) {},
      PieItem("Ctrl-C", PieIcon.Interrupt) {}, PieItem("Paste voice", PieIcon.Paste) {}, PieItem("Clear voice", PieIcon.Clear) {},
    ))
    val ring = QuickInputRing.Default
    val quick = PieState("Input", ring.placed.map { (_, input) -> PieItem(input.label, input.icon) {} }, angles = ring.placed.map { (slot, _) -> slot.degrees })
    var current by mutableStateOf(newMenu)
    val shot = Shot()
    mount(2f) { Captured(shot) { key(current) { Box(Modifier.requiredSize(600.dp)) { PieMenu(current) } } } }
    for ((name, state, hover) in listOf(
      Triple("palm-new.png", newMenu, null), Triple("palm-new-claude.png", newMenu, 3),
      Triple("palm-keys.png", keys, null), Triple("palm-keys-paste.png", keys, 4),
      Triple("palm-quick.png", quick, null), Triple("palm-quick-ctrlc.png", quick, quick.items.indexOfFirst { it.label == "Ctrl-C" }),
    )) {
      compose.runOnIdle {
        current = state
        state.highlighted = hover
        state.progress = if (hover == null) 0f else 0.62f
      }
      save(shot, name)
    }
  }
}
