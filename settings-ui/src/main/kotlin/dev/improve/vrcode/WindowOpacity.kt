package dev.improve.vrcode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

const val WINDOW_OPACITY_NOTE = "Lower it to see your room through the windows. Text and app colors stay solid."

/** Slider stops between the ends of the 30–100% range, one per 5%. */
private val WINDOW_OPACITY_STEPS =
  ((AppSettings.MAX_WINDOW_OPACITY - AppSettings.MIN_WINDOW_OPACITY) / AppSettings.WINDOW_OPACITY_STEP).roundToInt() - 1

fun windowOpacityLabel(opacity: Float): String = "${(opacity * 100).roundToInt()}%"

/**
 * Window background opacity. It applies while dragging (it is cheap: no panel is rebuilt), and
 * [onChange] only hears a new 5% step, so a drag does not save the same value over and over.
 */
@Composable
fun WindowOpacityControl(opacity: Float, tag: String, titleStyle: TextStyle, onChange: (Float) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text("Window opacity", Modifier.weight(1f), style = titleStyle)
      Text(windowOpacityLabel(opacity), Modifier.testTag("$tag-value"), color = MaterialTheme.colorScheme.primary)
    }
    Slider(
      value = opacity,
      onValueChange = { value -> AppSettings.sanitizeWindowOpacity(value)?.takeIf { it != opacity }?.let(onChange) },
      valueRange = AppSettings.MIN_WINDOW_OPACITY..AppSettings.MAX_WINDOW_OPACITY,
      steps = WINDOW_OPACITY_STEPS,
      modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "Window opacity" },
    )
  }
}
