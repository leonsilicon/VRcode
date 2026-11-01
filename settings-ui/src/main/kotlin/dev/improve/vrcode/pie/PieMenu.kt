package dev.improve.vrcode.pie

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class PieIcon { Terminal, Codex, Claude, Keyboard, Enter, Escape, Interrupt, Paste, Clear }

class PieItem(val label: String, val icon: PieIcon, val action: () -> Unit)

/**
 * What the pie shows: its [title], its items and the one the hand currently points at.
 * [cancelFraction] is the dead zone's diameter as a fraction of the pie's side.
 */
class PieState(val title: String, val items: List<PieItem>, val cancelFraction: Float = 0.27f) {
  var highlighted by mutableStateOf<Int?>(null)
  /** The item that just ran, shown briefly before the pie hides. */
  var chosen by mutableStateOf<Int?>(null)
  /** Fill inside the hovered pill, driven by the same clock that launches its action. */
  var progress by mutableFloatStateOf(0f)
  var rayHovered by mutableStateOf<Int?>(null)
}

/** Item centres sit this fraction of the pie's side from its centre. */
const val PIE_ITEM_RADIUS = 0.36f

private val Item = Color(0xE6202227)
private val Edge = Color(0x40FFFFFF)
private val Picked = Color(0xFF4C8DFF)

/**
 * A pie of items around the pinch point: item 0 at the top, the rest clockwise, matching
 * [dev.improve.vrcode.hands.RadialMenu]. Icons expand into labeled pills on hover; the fill shows
 * the one-second dwell while pinching. The other hand's ray can hover too. No click/release handler
 * can bypass the dwell. The centre circle names the menu.
 */
@Composable
fun PieMenu(state: PieState) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = minOf(maxWidth, maxHeight)
    val radius = side * PIE_ITEM_RADIUS
    val pillW = side * 0.36f
    val chipH = side * 0.14f
    val font = with(LocalDensity.current) { (chipH * 0.25f).toSp() }
    Box(
      Modifier.align(Alignment.Center).size(side * state.cancelFraction).clip(CircleShape)
        .background(Color(0x66202227)).border(1.dp, Edge, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      BasicText(state.title, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = font * 0.8f, textAlign = TextAlign.Center))
    }
    val count = state.items.size
    for ((i, item) in state.items.withIndex()) {
      val angle = 2 * Math.PI * i / count
      val x = radius * sin(angle).toFloat()
      val y = -radius * cos(angle).toFloat()
      val picked = state.highlighted == i
      val chosen = state.chosen
      val expansion by animateFloatAsState(if (picked) 1f else 0f, tween(160), label = "quick action expansion")
      val interaction = remember { MutableInteractionSource() }
      val rayHovered by interaction.collectIsHoveredAsState()
      LaunchedEffect(rayHovered) {
        if (rayHovered) state.rayHovered = i else if (state.rayHovered == i) state.rayHovered = null
      }
      val shape = RoundedCornerShape(50)
      Box(
        Modifier.align(Alignment.Center)
          .offset(x, y)
          .size(chipH + (pillW - chipH) * expansion, chipH)
          .alpha(if (chosen == null || chosen == i) 1f else 0.35f)
          .clip(shape)
          .background(if (picked) Color(0xF02C3648) else Item)
          .border(if (chosen == i) 3.dp else 1.dp, if (chosen == i) Color.White else if (picked) Picked else Edge, shape)
          .hoverable(interaction)
          .semantics(mergeDescendants = true) {
            contentDescription = item.label
            if (picked) progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
          },
        contentAlignment = Alignment.Center,
      ) {
        if (picked) {
          Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(state.progress).background(Picked.copy(alpha = 0.8f)))
        }
        Row(
          Modifier.fillMaxSize().padding(horizontal = chipH * 0.2f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.Center,
        ) {
          QuickActionIcon(item.icon, Modifier.size(chipH * 0.5f))
          if (expansion > 0.01f) {
            Spacer(Modifier.size(chipH * 0.1f * expansion))
            BasicText(
              item.label,
              modifier = Modifier.weight(expansion).alpha(expansion),
              maxLines = 1,
              softWrap = false,
              style = TextStyle(color = Color.White, fontSize = font, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
            )
          }
        }
      }
    }
  }
}

/** Small vector glyphs keep every action recognizable before its label is revealed. */
@Composable
private fun QuickActionIcon(icon: PieIcon, modifier: Modifier) {
  Canvas(modifier) {
    scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
      val ink = Color.White
      val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
      fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(ink, Offset(x1, y1), Offset(x2, y2), strokeWidth = 1.8f, cap = StrokeCap.Round)
      fun box(x: Float, y: Float, w: Float, h: Float, radius: Float = 1.5f) =
        drawRoundRect(ink, Offset(x, y), Size(w, h), CornerRadius(radius), style = stroke)
      when (icon) {
        PieIcon.Terminal -> {
          box(2f, 4f, 20f, 16f)
          line(6f, 8f, 10f, 12f); line(10f, 12f, 6f, 16f); line(13f, 16f, 18f, 16f)
        }
        PieIcon.Codex -> {
          line(8f, 6f, 2f, 12f); line(2f, 12f, 8f, 18f)
          line(16f, 6f, 22f, 12f); line(22f, 12f, 16f, 18f); line(14f, 4f, 10f, 20f)
        }
        PieIcon.Claude -> {
          for (ray in 0 until 12) {
            val a = ray * Math.PI / 6
            val inner = if (ray % 2 == 0) 3f else 4f
            line(12f + cos(a).toFloat() * inner, 12f + sin(a).toFloat() * inner,
              12f + cos(a).toFloat() * 10f, 12f + sin(a).toFloat() * 10f)
          }
        }
        PieIcon.Keyboard -> {
          box(1f, 5f, 22f, 15f)
          for (row in 0..1) for (col in 0..4) drawCircle(ink, 0.8f, Offset(5f + col * 3.5f, 9f + row * 4f))
          line(7f, 17f, 17f, 17f)
        }
        PieIcon.Enter -> {
          line(20f, 4f, 20f, 14f); line(20f, 14f, 4f, 14f)
          line(4f, 14f, 9f, 9f); line(4f, 14f, 9f, 19f)
        }
        PieIcon.Escape -> {
          line(12f, 4f, 21f, 4f); line(21f, 4f, 21f, 21f); line(21f, 21f, 12f, 21f)
          line(3f, 12f, 16f, 12f); line(3f, 12f, 8f, 7f); line(3f, 12f, 8f, 17f)
        }
        PieIcon.Interrupt -> box(5f, 5f, 14f, 14f, 2f)
        PieIcon.Paste -> {
          box(5f, 5f, 14f, 17f); box(9f, 2f, 6f, 5f)
          line(9f, 12f, 15f, 12f); line(9f, 16f, 15f, 16f)
        }
        PieIcon.Clear -> {
          line(3f, 6f, 21f, 6f); box(9f, 2f, 6f, 4f)
          line(5f, 6f, 6f, 21f); line(6f, 21f, 18f, 21f); line(18f, 21f, 19f, 6f)
          line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
        }
      }
    }
  }
}

/** How far the thumb and middle finger are toward a pinch, 0 to 1. */
class PieHintState {
  var progress by mutableFloatStateOf(0f)
}

/**
 * The hint above either hand's middle fingertip while its palm faces the user, like the system's own
 * menu glyph: a ring whose centre fills as the thumb closes on the middle finger. Pinching opens
 * the palm menu.
 */
@Composable
fun PieHint(state: PieHintState) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Box(
      Modifier.fillMaxSize(0.92f)
        .clip(CircleShape)
        .background(Color(0x99202227))
        .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape),
    )
    // A full pinch fills the ring completely, so "almost full" always means "not quite pinched".
    val p = state.progress
    Box(Modifier.fillMaxSize(0.28f + 0.64f * p).clip(CircleShape).background(Picked.copy(alpha = 0.55f + 0.45f * p)))
  }
}
