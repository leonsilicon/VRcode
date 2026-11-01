package dev.improve.vrcode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun TerminalSizeControls(size: TerminalSize, onChange: (TerminalSize) -> Unit) {
  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("New terminal size", style = MaterialTheme.typography.titleLarge)
    Text("Width in columns and height in rows. Applies to terminals created in VRcode, including Codex and Claude Code.",
      color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.selectableGroup()) {
      for (presets in TerminalSizePreset.entries.chunked(2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          for (preset in presets) {
            FilterChip(
              selected = size == preset.size,
              onClick = { onChange(preset.size) },
              label = { Text("${preset.label} · ${preset.size.cols} × ${preset.size.rows}") },
              modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                .testTag("settings-terminal-size-${preset.name}").semantics { role = Role.RadioButton },
            )
          }
        }
      }
    }
    Text(if (TerminalSizePreset.entries.none { it.size == size }) "Custom · ${size.cols} × ${size.rows}" else "${size.cols} columns × ${size.rows} rows",
      Modifier.testTag("settings-terminal-size-value"), color = MaterialTheme.colorScheme.primary)
    DimensionControl("Width", "columns", size.cols, TerminalSize.MIN_COLS..TerminalSize.MAX_COLS, "settings-terminal-columns") {
      onChange(size.copy(cols = it))
    }
    DimensionControl("Height", "rows", size.rows, TerminalSize.MIN_ROWS..TerminalSize.MAX_ROWS, "settings-terminal-rows") {
      onChange(size.copy(rows = it))
    }
    Text("Standard (120 × 36) is the default. Open terminals keep their current size.",
      color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun DimensionControl(label: String, unit: String, value: Int, range: IntRange, tag: String, onChange: (Int) -> Unit) {
  Column {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(label, Modifier.weight(1f))
      Text("$value $unit", color = MaterialTheme.colorScheme.primary)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      OutlinedButton({ onChange(value - 1) }, enabled = value > range.first,
        modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp).testTag("$tag-decrease")
          .semantics { contentDescription = "Decrease terminal ${label.lowercase()}" }) { Text("−") }
      Slider(value.toFloat(), { onChange(it.roundToInt()) }, valueRange = range.first.toFloat()..range.last.toFloat(),
        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag(tag)
          .semantics { contentDescription = "Terminal ${label.lowercase()} in $unit" })
      OutlinedButton({ onChange(value + 1) }, enabled = value < range.last,
        modifier = Modifier.sizeIn(minWidth = 52.dp, minHeight = 52.dp).testTag("$tag-increase")
          .semantics { contentDescription = "Increase terminal ${label.lowercase()}" }) { Text("+") }
    }
  }
}
