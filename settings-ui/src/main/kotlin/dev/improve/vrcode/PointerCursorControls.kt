package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
fun PointerCursorControls(settings: AppSettings, onChange: (AppSettings) -> Unit) {
  Text("Pointer cursor", style = MaterialTheme.typography.headlineSmall)
  Text("Choose the cursor where your right hand points. Pinch to click and drag with any option.",
    color = MaterialTheme.colorScheme.onSurfaceVariant)
  Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    for (style in PointerCursorStyle.entries) {
      PointerOption(style, settings.pointerCursor == style, settings.pointerCursorSize, Modifier.weight(1f)) {
        onChange(settings.copy(pointerCursor = style))
      }
    }
  }
  if (settings.pointerCursor != PointerCursorStyle.None) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Pointer size", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
      Text("${(settings.pointerCursorSize * 100).roundToInt()}%", Modifier.testTag("settings-pointer-size-value"),
        color = MaterialTheme.colorScheme.primary)
    }
    Slider(
      value = settings.pointerCursorSize,
      onValueChange = { onChange(settings.copy(pointerCursorSize = it)) },
      valueRange = AppSettings.MIN_POINTER_SIZE..AppSettings.MAX_POINTER_SIZE,
      steps = 4,
      modifier = Modifier.testTag("settings-pointer-size").semantics { contentDescription = "Pointer size" },
    )
  }
}

@Composable
private fun PointerOption(style: PointerCursorStyle, selected: Boolean, sizeScale: Float, modifier: Modifier, onSelect: () -> Unit) {
  val cursor = remember(style) { PointerCursor().apply { this.style = style } }
  Surface(
    modifier, shape = RoundedCornerShape(14.dp),
    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
    border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
  ) {
    Column(
      Modifier.testTag("settings-pointer-${style.storageValue}")
        .selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(12.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(Modifier.fillMaxWidth().height(68.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (style == PointerCursorStyle.None) Text("Hidden", color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall)
        else Canvas(Modifier.fillMaxSize()) {
          val scale = density * sizeScale
          val arrow = style == PointerCursorStyle.Arrow
          cursor.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
          cursor.pointAt((size.width - if (arrow) 20f * scale else 0f) / 2,
            (size.height - if (arrow) 27f * scale else 0f) / 2, scale, false)
          drawIntoCanvas { cursor.draw(it.nativeCanvas) }
        }
      }
      RadioButton(selected, onClick = null)
      Text(style.label, style = MaterialTheme.typography.titleSmall)
    }
  }
}
