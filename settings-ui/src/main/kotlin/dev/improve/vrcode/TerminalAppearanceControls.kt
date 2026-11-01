package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.terminal.font.TerminalFont
import dev.improve.xmux.terminal.grid.TerminalGridView
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome
import kotlin.math.roundToInt

@Composable
fun TerminalAppearanceControls(settings: AppSettings, onChange: (AppSettings) -> Unit) {
  val theme = settings.terminalTheme
  var previewFontSize by remember(settings.terminalFontSize) { mutableFloatStateOf(settings.terminalFontSize) }
  Text("Terminal", style = MaterialTheme.typography.headlineSmall)
  Text("Make it yours. Changes apply to open and new terminals.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  TerminalAppearancePreview(settings.copy(terminalFontSize = previewFontSize))
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text("Color theme", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
    Text(theme.label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
  }
  Text("Themes set the default text, background and cursor. Apps keep their own colors.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (row in TerminalTheme.entries.chunked(3)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for (option in row) {
          ThemeOption(option, theme == option, Modifier.weight(1f)) { onChange(settings.copy(terminalTheme = option)) }
        }
      }
    }
  }
  Text("Presets from the open source iTerm2 Color Schemes collection, also used by Ghostty.",
    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
  HorizontalDivider()
  Text("Text & cursor", style = MaterialTheme.typography.titleLarge)
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    Text("${previewFontSize.roundToInt()} px", Modifier.testTag("settings-font-size-value"), color = MaterialTheme.colorScheme.primary)
  }
  Slider(
    value = previewFontSize,
    onValueChange = { previewFontSize = it.roundToInt().toFloat() },
    // Keep dragging responsive; only rebuild the spatial panels when the slider is released.
    onValueChangeFinished = { onChange(settings.copy(terminalFontSize = previewFontSize)) },
    valueRange = AppSettings.MIN_FONT_SIZE..AppSettings.MAX_FONT_SIZE,
    steps = (AppSettings.MAX_FONT_SIZE - AppSettings.MIN_FONT_SIZE).toInt() - 1,
    modifier = Modifier.testTag("settings-font-size").semantics { contentDescription = "Terminal text size" },
  )
  Text("JetBrains Mono. Larger text expands the window while keeping the same rows and columns.", color = MaterialTheme.colorScheme.onSurfaceVariant)
  Text("Cursor shape", style = MaterialTheme.typography.titleMedium)
  Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    for (cursor in TerminalCursor.entries) {
      FilterChip(
        selected = settings.terminalCursor == cursor,
        onClick = { onChange(settings.copy(terminalCursor = cursor)) },
        label = { Text(cursor.label) },
        modifier = Modifier.heightIn(min = 48.dp).testTag("settings-cursor-${cursor.storageValue}"),
      )
    }
  }
  TextButton(
    { onChange(settings.copy(terminalTheme = TerminalTheme.Mac, terminalFontSize = AppSettings.DEFAULT_FONT_SIZE, terminalCursor = TerminalCursor.Terminal)) },
    Modifier.heightIn(min = 48.dp).testTag("settings-terminal-defaults"),
  ) { Text("Reset terminal appearance") }
}

@Composable
private fun ThemeOption(theme: TerminalTheme, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
  Surface(
    modifier, shape = RoundedCornerShape(14.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      Modifier.testTag("settings-terminal-theme-${theme.storageValue}")
        .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(theme.background)).padding(12.dp).clearAndSetSemantics {},
        verticalAlignment = Alignment.CenterVertically) {
        Text("Aa", color = Color(theme.foreground), fontFamily = FontFamily.Monospace, fontSize = 24.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(8.dp, 20.dp).background(Color(theme.cursor)))
      }
      Text(theme.label, style = MaterialTheme.typography.titleSmall)
      Text(theme.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Uses the production glyph renderer, including cursor and local default-color resolution. */
@Composable
fun TerminalAppearancePreview(settings: AppSettings) {
  val context = LocalContext.current
  val font = remember(settings.terminalFontSize) { TerminalFont(context, settings.terminalFontSize) }
  val grid = remember {
    CellGrid(28, 5).apply {
      write(0, 0, "~/workspace")
      write(1, 0, "$ git status")
      write(2, 0, "On branch main")
      write(3, 0, "Your workspace is ready.")
      write(4, 0, "$ ")
      setCursor(4, 2)
    }
  }
  val state = remember { TerminalWindowState("Preview").apply { loading = false } }
  val opacity = settings.windowOpacity
  val chrome = remember(settings.terminalTheme, opacity) { WindowChrome().withTerminalTheme(settings.terminalTheme, opacity) }
  val shape = RoundedCornerShape(12.dp)
  // A stand-in for the room, which shows through a translucent window as it would in the headset.
  // A translucent grid paints its own background, so the frame stays clear rather than doubling it.
  Column(
    Modifier.fillMaxWidth().clip(shape).background(PREVIEW_ROOM)
      .background(if (opacity < 1f) Color.Transparent else Color(chrome.bodyColor)).testTag("settings-terminal-preview"),
  ) {
    Box(Modifier.clearAndSetSemantics {}) { VrcodeWindowHeader(state, settings.windowStyle, chrome) }
    key(font) {
      AndroidView(
        factory = { TerminalGridView(it, grid, font) },
        update = { it.appearance = settings.gridAppearance(); it.backgroundOpacity = opacity },
        modifier = Modifier.fillMaxWidth().height((font.cellHeight * grid.rows + 20f).dp).clearAndSetSemantics {},
      )
    }
  }
}

private val PREVIEW_ROOM = Brush.linearGradient(listOf(Color(0xFFE9C46A), Color(0xFF8AB6D6), Color(0xFF9CC5A1), Color(0xFFE76F51)))
