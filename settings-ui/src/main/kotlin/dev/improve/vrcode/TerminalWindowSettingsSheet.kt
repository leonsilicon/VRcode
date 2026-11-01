package dev.improve.vrcode

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun WindowSettingsButton(tint: Color = Color(0xFFA9C7FF), onClick: () -> Unit) {
  IconButton(onClick, Modifier.size(48.dp).testTag("vrcode-open-window-settings")) {
    Icon(Icons.Default.Settings, contentDescription = "Open window settings", tint = tint)
  }
}

/** Lives inside the terminal body. The close row stays visible while the options scroll. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalWindowSettingsSheet(
  title: String,
  overrides: TerminalWindowSettings,
  defaults: AppSettings,
  mode: WindowMode,
  onChange: (TerminalWindowSettings) -> Unit,
  onMode: (WindowMode) -> Unit,
  onReset: () -> Unit,
  onClose: () -> Unit,
) {
  val current = overrides.resolve(defaults)
  var textSize by remember(current.terminalFontSize) { mutableFloatStateOf(current.terminalFontSize) }
  VrcodeTheme {
    Box(Modifier.fillMaxSize().testTag("window-settings-overlay")) {
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f))
        .testTag("window-settings-scrim").clickable(
          interactionSource = remember { MutableInteractionSource() }, indication = null,
          onClickLabel = "Close window settings", onClick = onClose))
      Surface(
        Modifier.align(Alignment.TopCenter).fillMaxWidth().heightIn(max = 680.dp)
          .testTag("window-settings-sheet").semantics { paneTitle = "Window settings" },
        shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 12.dp,
      ) {
        Column {
          Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
              Text("Window settings", style = MaterialTheme.typography.titleLarge)
              Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClose, Modifier.size(48.dp).testTag("window-settings-close")) {
              Icon(Icons.Default.Close, contentDescription = "Close window settings")
            }
          }
          HorizontalDivider()
          Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().testTag("window-settings-options")
              .verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text("Changes apply only to this terminal.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Window behavior", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              for (option in WindowMode.entries) {
                FilterChip(mode == option, { onMode(option) }, label = { Text(option.label) },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("window-settings-mode-${option.storageValue}"))
              }
            }
            Text(mode.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Window style", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              for (style in WindowStyle.entries) {
                FilterChip(current.windowStyle == style, { onChange(overrides.copy(style = style)) }, label = { Text(style.label) },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("window-settings-style-${style.storageValue}"))
              }
            }
            HorizontalDivider()
            Text("Color theme", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              for (theme in TerminalTheme.entries) {
                FilterChip(current.terminalTheme == theme, { onChange(overrides.copy(theme = theme)) },
                  label = { Text(theme.label) },
                  leadingIcon = { Box(Modifier.size(14.dp).background(Color(theme.background), RoundedCornerShape(4.dp))) },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("window-settings-theme-${theme.storageValue}"))
              }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text("Text size", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
              Text("${textSize.roundToInt()} px", Modifier.testTag("window-settings-font-value"), color = MaterialTheme.colorScheme.primary)
            }
            Slider(textSize, { textSize = it.roundToInt().toFloat() },
              onValueChangeFinished = { onChange(overrides.copy(fontSize = textSize)) },
              valueRange = AppSettings.MIN_FONT_SIZE..AppSettings.MAX_FONT_SIZE,
              steps = (AppSettings.MAX_FONT_SIZE - AppSettings.MIN_FONT_SIZE).toInt() - 1,
              modifier = Modifier.testTag("window-settings-font-size").semantics { contentDescription = "Window text size" })
            Text("Larger text resizes this window while keeping its rows and columns.",
              style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Cursor shape", style = MaterialTheme.typography.titleMedium)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              for (cursor in TerminalCursor.entries) {
                FilterChip(current.terminalCursor == cursor, { onChange(overrides.copy(cursor = cursor)) },
                  label = { Text(cursor.label) },
                  modifier = Modifier.heightIn(min = 48.dp).testTag("window-settings-cursor-${cursor.storageValue}"))
              }
            }
            TextButton(onReset, Modifier.heightIn(min = 48.dp).testTag("window-settings-defaults")) { Text("Use app defaults") }
          }
        }
      }
    }
  }
}
