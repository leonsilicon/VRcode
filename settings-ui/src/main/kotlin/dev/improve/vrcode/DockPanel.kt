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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
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

enum class DockTouchPhase { Down, Move, Up }

/**
 * A touch on a dock panel, as the Interaction SDK delivers it: a fingertip touching the panel or a
 * pinching ray on it. [x] and [y] are fractions of the panel's width and height from its top-left
 * corner, outside 0..1 once a held touch moves off the panel.
 */
data class DockTouch(val phase: DockTouchPhase, val x: Float, val y: Float)

/**
 * Reports each touch as it happens: [DockTouchPhase.Down] on contact, without waiting for a release
 * or a click, then every move, then [DockTouchPhase.Up] on release or cancellation.
 */
@Composable
private fun Modifier.dockTouches(onTouch: (DockTouch) -> Unit): Modifier {
  val current by rememberUpdatedState(onTouch)
  return pointerInput(Unit) {
    awaitEachGesture {
      val down = awaitFirstDown(requireUnconsumed = false)
      fun report(phase: DockTouchPhase, at: Offset) =
        current(DockTouch(phase, at.x / size.width.coerceAtLeast(1), at.y / size.height.coerceAtLeast(1)))
      report(DockTouchPhase.Down, down.position)
      var last = down.position
      try {
        while (true) {
          val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
          last = change.position
          if (!change.pressed) break
          report(DockTouchPhase.Move, last)
        }
      } finally {
        report(DockTouchPhase.Up, last)
      }
    }
  }
}

/**
 * The dock's bar, like an iPhone mini's home bar: a short, faint, rounded bar. It stays in place
 * whether the dock is down or [raised] (it then sits underneath the options, a little brighter).
 * A hand near it makes it bulge ([bulge], 0..1) and shows a chevron: up to pull the dock up, down to
 * lower it. A pinch pulling it up lifts it ([lift], 0..1 of the pull that raises the dock), and it
 * springs back if let go early. Every touch goes to [onTouch] the moment it lands; while the dock
 * is down a touch does nothing, while raised it lowers the dock.
 */
@Composable
fun DockBar(bulge: Float, raised: Boolean = false, barFraction: Float = 0.7f, lift: Float = 0f, onTouch: (DockTouch) -> Unit = {}) {
  val b by animateFloatAsState(bulge.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 500f), label = "bulge")
  val l by animateFloatAsState(lift.coerceIn(0f, 1f), spring(dampingRatio = 0.7f, stiffness = 900f), label = "lift")
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vrcode-dock-bar").dockTouches(onTouch), contentAlignment = Alignment.Center) {
      val barHeight = 4.dp + 4.dp * b
      Box(
        Modifier.offset(y = -maxHeight * 0.25f * l)
          .width(maxWidth * barFraction * (1f + 0.15f * b))
          .height(barHeight)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = (if (raised) 0.55f else 0.3f) + 0.4f * b)),
      )
      if (b > 0.05f && l < 0.95f) {
        // Below the bar while raised, so it never sits on the options above; it fades as a pull lifts the bar into it.
        Text(if (raised) "⌄" else "⌃", Modifier.offset(y = maxHeight * (if (raised) 0.28f else -0.32f)),
          color = Color.White.copy(alpha = 0.7f * b * (1f - l)), fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

/**
 * The raised dock: a row of options at [rowY] (fractions of the panel's height from its top) on a
 * body from [bodyTop] down to the panel's bottom edge, above the bar (a separate panel that stays
 * where it is). [xs] are the options' centres as fractions of the panel's width. The option a hand
 * is near ([highlighted]) grows and shows its label above the body; touching an option runs it, and
 * every touch goes to [onTouch] as it happens.
 */
@Composable
fun DockBody(
  options: List<DockOption>,
  xs: List<Float>,
  highlighted: Int?,
  rowY: Float,
  bodyTop: Float,
  onTouch: (DockTouch) -> Unit = {},
) {
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vrcode-dock").dockTouches(onTouch)) {
      val w = maxWidth
      val h = maxHeight
      // The body behind the row.
      Box(
        Modifier.offset(y = h * bodyTop).fillMaxWidth().height(h * (1f - bodyTop)).clip(RoundedCornerShape(22.dp))
          .background(Color(0xE61A202B)).border(BorderStroke(1.dp, Color(0x30FFFFFF)), RoundedCornerShape(22.dp)),
      )
      val icon = (h * 0.27f).coerceAtMost(w * 0.11f)
      for ((i, option) in options.withIndex()) {
        val x = xs.getOrElse(i) { 0.5f }
        val picked = i == highlighted
        val size = icon * (if (picked) 1.2f else 1f)
        Box(
          Modifier.offset(x = w * x - size / 2, y = h * rowY - size / 2).size(size).clip(CircleShape)
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
          // The label in a pill above the body, over the option. The world shows behind it in the headset.
          Box(
            Modifier.offset(x = (w * x - w * 0.15f).coerceIn(0.dp, w * 0.7f), y = (h * bodyTop - h * 0.2f).coerceAtLeast(0.dp)).width(w * 0.3f),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              option.label,
              Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xE61A202B)).padding(horizontal = 8.dp, vertical = 2.dp),
              color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1,
              overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
          }
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
