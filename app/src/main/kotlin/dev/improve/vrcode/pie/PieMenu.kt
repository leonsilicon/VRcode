package dev.improve.vrcode.pie

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

class PieItem(val label: String, val action: () -> Unit)

/**
 * What the pie shows: its [title], its items and the one the hand currently points at.
 * [cancelFraction] is the dead zone's diameter as a fraction of the pie's side.
 */
class PieState(val title: String, val items: List<PieItem>, val cancelFraction: Float = 0.27f) {
  var highlighted by mutableStateOf<Int?>(null)
}

/** Item centres sit this fraction of the pie's side from its centre. */
const val PIE_ITEM_RADIUS = 0.36f

private val Item = Color(0xE6202227)
private val Edge = Color(0x40FFFFFF)
private val Picked = Color(0xFF4C8DFF)

/**
 * A pie of items around the pinch point: item 0 at the top, the rest clockwise, matching
 * [dev.improve.vrcode.hands.RadialMenu]. The highlighted item is the one releasing the pinch picks,
 * and moving the hand onto it runs it at once. The centre circle is the cancel area and names the
 * menu. Items can also be clicked with the other hand's ray.
 */
@Composable
fun PieMenu(state: PieState, onClick: (Int) -> Unit) {
  BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = minOf(maxWidth, maxHeight)
    val radius = side * PIE_ITEM_RADIUS
    val chipW = side * 0.3f
    val chipH = side * 0.13f
    val font = with(LocalDensity.current) { (chipH * 0.36f).toSp() }
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
      val shape = RoundedCornerShape(50)
      Box(
        Modifier.align(Alignment.Center)
          .offset(x, y)
          .size(chipW, chipH)
          .clip(shape)
          .background(if (picked) Picked else Item)
          .border(1.dp, if (picked) Picked else Edge, shape)
          .clickable { onClick(i) }
          .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
      ) {
        BasicText(item.label, style = TextStyle(color = Color.White, fontSize = font, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
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
    val p = state.progress
    Box(Modifier.fillMaxSize(0.28f + 0.5f * p).clip(CircleShape).background(Picked.copy(alpha = 0.55f + 0.45f * p)))
  }
}
