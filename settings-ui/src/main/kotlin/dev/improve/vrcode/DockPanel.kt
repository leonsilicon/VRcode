package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** IDs belong to the live bridge, never a locally stored terminal list. */
data class DockWindow(val id: Int, val title: String)

enum class DockOptionKind { Connection, Window, Keyboard, Arrange, Settings }

/** One option of the raised dock. */
data class DockOption(val label: String, val kind: DockOptionKind, val selected: Boolean = false, val enabled: Boolean = true)

private val DockAccent = Color(0xFF4C8DFF)

/**
 * The collapsed dock, like an iPhone mini's home bar: a short, faint, rounded bar. A hand near it
 * makes it bulge ([bulge], 0..1) and shows an up-chevron; swiping up lifts it ([swipe], 0..1)
 * until the dock rises. It draws nothing else and takes no clicks: the app tracks the fingertip.
 */
@Composable
fun DockBar(bulge: Float, swipe: Float, barFraction: Float = 0.7f) {
  val b by animateFloatAsState(bulge.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 500f), label = "bulge")
  val s by animateFloatAsState(swipe.coerceIn(0f, 1f), spring(stiffness = 700f), label = "swipe")
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vrcode-dock-bar"), contentAlignment = Alignment.Center) {
      val barHeight = 4.dp + 4.dp * b
      Box(
        Modifier.offset(y = -(maxHeight * 0.3f) * s)
          .width(maxWidth * barFraction * (1f + 0.15f * b))
          .height(barHeight)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = 0.3f + 0.5f * maxOf(b, s))),
      )
      if (b > 0.05f) {
        Text("⌃", Modifier.offset(y = -(maxHeight * 0.32f) - (maxHeight * 0.2f) * s), color = Color.White.copy(alpha = 0.7f * b),
          fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/**
 * The raised dock: a row of options at [rowY] (fractions of the panel's height from its top) with
 * the bar at its top centre ([grabberY]) as a grabber to swipe it back down. [xs] are the options'
 * centres as fractions of the panel's width. While an option is pressed ([pressing]) it follows the
 * finger ([fingerX]) with a knob, lifts with the flick ([lift], 0..1) and shows "flick to open".
 */
@Composable
fun DockBody(
  options: List<DockOption>,
  xs: List<Float>,
  highlighted: Int?,
  pressing: Boolean,
  fingerX: Float,
  lift: Float,
  grabberBulge: Float,
  grabberSwipe: Float,
  rowY: Float,
  grabberY: Float,
  bodyTop: Float,
  barFraction: Float,
  hint: String?,
) {
  val l by animateFloatAsState(lift.coerceIn(0f, 1f), spring(stiffness = 800f), label = "lift")
  val g by animateFloatAsState(grabberBulge.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 500f), label = "grab")
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vrcode-dock")) {
      val w = maxWidth
      val h = maxHeight
      // The body behind the row and grabber.
      Box(
        Modifier.offset(y = h * bodyTop).fillMaxWidth().height(h * (1f - bodyTop)).clip(RoundedCornerShape(22.dp))
          .background(Color(0xE61A202B)).border(BorderStroke(1.dp, Color(0x30FFFFFF)), RoundedCornerShape(22.dp)),
      )
      // Grabber: the bar, now at the top centre; swipe down on it to lower the dock.
      Box(
        Modifier.offset(x = w * (0.5f - barFraction * (1f + 0.15f * g) / 2f), y = h * grabberY - 2.dp + h * 0.08f * grabberSwipe)
          .width(w * barFraction * (1f + 0.15f * g)).height(4.dp + 3.dp * g).clip(CircleShape)
          .background(Color.White.copy(alpha = 0.35f + 0.45f * maxOf(g, grabberSwipe))),
      )
      val icon = (h * 0.24f).coerceAtMost(w * 0.11f)
      for ((i, option) in options.withIndex()) {
        val x = xs.getOrElse(i) { 0.5f }
        val picked = i == highlighted
        val scale = if (picked) 1.25f else 1f
        val rise = if (picked && pressing) h * 0.22f * l else 0.dp
        val size = icon * scale
        Box(
          Modifier.offset(x = w * x - size / 2, y = h * rowY - size / 2 - rise).size(size).clip(CircleShape)
            .background(if (picked) DockAccent else if (option.selected) Color(0xFF2C4263) else Color(0x33FFFFFF))
            .testTag("dock-option-$i"),
          contentAlignment = Alignment.Center,
        ) {
          val glyph = Modifier.size(size * 0.55f)
          when (option.kind) {
            DockOptionKind.Window -> TerminalGlyph(glyph)
            DockOptionKind.Keyboard -> KeyboardGlyph(glyph)
            DockOptionKind.Arrange -> Icon(Icons.Default.Refresh, contentDescription = null, modifier = glyph, tint = Color.White)
            DockOptionKind.Settings -> Icon(Icons.Default.Settings, contentDescription = null, modifier = glyph, tint = Color.White)
            DockOptionKind.Connection ->
              Box(Modifier.size(size * 0.3f).clip(CircleShape).background(if (option.enabled) Color(0xFF8CDCB0) else Color(0xFFE8B86D)))
          }
        }
        if (picked) {
          // The label and the flick cue in a pill above the body (clear of the grabber), over the
          // picked option, rising a little with the flick. The world shows behind it in the headset.
          Box(
            Modifier.offset(
              x = (w * x - w * 0.15f).coerceIn(0.dp, w * 0.7f),
              y = (h * bodyTop - h * 0.2f - h * 0.08f * l).coerceAtLeast(0.dp),
            ).width(w * 0.3f),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              if (pressing) "↑ ${option.label}" else option.label,
              Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE61A202B)).padding(horizontal = 8.dp, vertical = 2.dp),
              color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
              overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
          }
        }
      }
      if (pressing) {
        // The knob under the finger, on the row.
        Box(Modifier.offset(x = w * fingerX - 4.dp, y = h * rowY + icon / 2 + 3.dp).size(8.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.8f)))
      }
      if (hint != null) {
        // At the top of the panel, above where an option's label shows.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
          Text(hint, Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xCC1A202B)).padding(horizontal = 8.dp, vertical = 2.dp),
            color = Color(0xFFB9C5D8), fontSize = 10.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
      }
    }
  }
}




@Composable
private fun TerminalGlyph(modifier: Modifier = Modifier.size(24.dp)) {
  Canvas(modifier) {
    val tint = Color(0xFFA9C7FF)
    val stroke = size.width * 0.065f
    drawRoundRect(tint, Offset(stroke, stroke * 2), Size(size.width - stroke * 2, size.height - stroke * 4), CornerRadius(stroke * 2), style = Stroke(stroke))
    drawLine(tint, Offset(size.width * 0.24f, size.height * 0.36f), Offset(size.width * 0.4f, size.height * 0.5f), stroke)
    drawLine(tint, Offset(size.width * 0.4f, size.height * 0.5f), Offset(size.width * 0.24f, size.height * 0.64f), stroke)
    drawLine(tint, Offset(size.width * 0.51f, size.height * 0.64f), Offset(size.width * 0.73f, size.height * 0.64f), stroke)
  }
}

@Composable
private fun KeyboardGlyph(modifier: Modifier = Modifier.size(24.dp)) {
  Canvas(modifier) {
    val tint = Color(0xFFE4EAF5)
    val stroke = size.width * 0.06f
    drawRoundRect(tint, Offset(stroke, size.height * 0.18f), Size(size.width - stroke * 2, size.height * 0.64f), CornerRadius(stroke * 2), style = Stroke(stroke))
    for (row in 0..1) for (col in 0..3) {
      drawCircle(tint, stroke * 0.65f, Offset(size.width * (0.22f + col * 0.185f), size.height * (0.36f + row * 0.16f)))
    }
    drawLine(tint, Offset(size.width * 0.3f, size.height * 0.68f), Offset(size.width * 0.7f, size.height * 0.68f), stroke)
  }
}
