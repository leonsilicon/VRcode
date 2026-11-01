package dev.improve.vrcode.pie

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** An item's icon: a drawn glyph, or short [glyph] text for keys that read best as letters. */
enum class PieIcon(val glyph: String? = null) {
  AddTerminal, Codex, Claude, BringForward, Keyboard, Enter, Escape, Tab, Interrupt, Paste, Clear,
  ArrowUp, ArrowRight, ArrowDown, ArrowLeft, ShiftTab, Backspace,
  CtrlD("^D"), CtrlZ("^Z"), CtrlL("^L"), CtrlR("^R"), PageUp("PgUp"), PageDown("PgDn"), Home("Home"), End("End"),
}

class PieItem(val label: String, val icon: PieIcon, val action: () -> Unit)

/** [count] items evenly around the pie, item 0 at the top: each one's direction in degrees, clockwise. */
fun pieEvenAngles(count: Int): List<Float> = List(count) { 360f * it / count }

/**
 * What the pie shows: its [title], its items and the one the hand currently points at.
 * [cancelFraction] is the dead zone's diameter as a fraction of the pie's side. [angles] is each
 * item's direction in degrees clockwise from the top; evenly spaced unless given.
 */
class PieState(
  val title: String,
  items: List<PieItem>,
  val cancelFraction: Float = 0.17f,
  angles: List<Float> = pieEvenAngles(items.size),
) {
  var items by mutableStateOf(items)
    private set
  var angles by mutableStateOf(angles)
    private set

  init {
    require(items.size == angles.size) { "one angle per item" }
  }

  /** Item [index]'s direction in degrees, clockwise from the top. */
  fun angleOf(index: Int): Float = angles.getOrElse(index) { 360f * index / items.size }

  /** Replaces the items (each at its [angles] entry), clearing any hover or choice. */
  fun setItems(items: List<PieItem>, angles: List<Float> = pieEvenAngles(items.size)) {
    require(items.size == angles.size) { "one angle per item" }
    highlighted = null
    chosen = null
    rayHovered = null
    rayRun = null
    progress = 0f
    this.items = items
    this.angles = angles
  }

  var highlighted by mutableStateOf<Int?>(null)
  /** The item that just ran, shown briefly before the pie hides. */
  var chosen by mutableStateOf<Int?>(null)
  /** Fill inside the hovered item's label pill, driven by the clock that launches its action. */
  var progress by mutableFloatStateOf(0f)
  /** The item the other hand's ray hovers (its icon, or the spoke out to its shown label), if any. */
  var rayHovered by mutableStateOf<Int?>(null)
  /** The shown label pill the other hand's ray hovers, if any: that runs its item at once. */
  var rayRun by mutableStateOf<Int?>(null)
}

/** Item icons' centres sit this fraction of the pie's side from its centre. */
const val PIE_ITEM_RADIUS = 0.16f

/** An item icon's diameter, which is also its label pill's height, as a fraction of the side. */
const val PIE_ITEM_HEIGHT = 0.072f

/** The label pill's width, as a fraction of the side. */
const val PIE_LABEL_WIDTH = 0.175f

/**
 * How far from the pie's centre, along any item's spoke, its label pill begins: as far past the
 * icon's centre as the icon's centre is from the pie's, so the label stands well clear of its icon.
 * Reaching it runs the item at once.
 */
const val PIE_LABEL_START = 2 * PIE_ITEM_RADIUS

/**
 * Each spoke, from the centre out: the item's icon and then, [PIE_LABEL_START] out, its label pill
 * (the action's name, filling over the one-second dwell). The pill is a horizontal stadium placed so
 * its nearest point along the spoke is [PIE_LABEL_START] out. Positions are fractions of the pie's
 * side from its centre, x right and y up.
 */
fun pieLabelCentre(index: Int, count: Int): Pair<Float, Float> = pieLabelCentre(evenAngle(index, count))

/** The label pill's centre on the spoke [degrees] clockwise from the top. */
fun pieLabelCentre(degrees: Float): Pair<Float, Float> {
  val (s, c) = spoke(degrees)
  val d = PIE_LABEL_START + pillHalfExtent(PIE_LABEL_WIDTH, PIE_ITEM_HEIGHT, s)
  return s * d to c * d
}

private fun evenAngle(index: Int, count: Int): Float = 360f * index / count

/** A spoke [degrees] clockwise from the top: sin and cos of its angle. */
private fun spoke(degrees: Float): Pair<Float, Float> {
  val angle = Math.toRadians(degrees.toDouble())
  return sin(angle).toFloat() to cos(angle).toFloat()
}

/** Half a horizontal stadium's ([width] by [height]) extent along a direction whose x part is [s]. */
private fun pillHalfExtent(width: Float, height: Float, s: Float): Float = (width - height) / 2 * abs(s) + height / 2

/** What the other hand's ray is on: [item], and whether that is its label (which runs it at once). */
data class PieRayTarget(val item: Int, val onLabel: Boolean)

/**
 * What a ray at ([x], [y]) is on, or null. Positions are fractions of the pie's side from its
 * centre, x right and y up; [angles] places each item and [highlighted] is the item whose label
 * shows. Any item's icon counts. The shown label runs its item, and the stretch of spoke from the
 * icon out to it keeps that item hovered, so the ray can cross the gap between them.
 */
fun pieRayTarget(x: Float, y: Float, angles: List<Float>, highlighted: Int?): PieRayTarget? {
  val r = PIE_ITEM_HEIGHT / 2
  for ((i, degrees) in angles.withIndex()) {
    val (s, c) = spoke(degrees)
    if (hypot(x - s * PIE_ITEM_RADIUS, y - c * PIE_ITEM_RADIUS) <= r) return PieRayTarget(i, onLabel = false)
  }
  val i = highlighted?.takeIf { it in angles.indices } ?: return null
  val (lx, ly) = pieLabelCentre(angles[i])
  val half = (PIE_LABEL_WIDTH - PIE_ITEM_HEIGHT) / 2
  if (hypot(x - x.coerceIn(lx - half, lx + half), y - ly) <= r) return PieRayTarget(i, onLabel = true)
  val (s, c) = spoke(angles[i])
  val along = x * s + y * c
  val across = abs(x * c - y * s)
  return PieRayTarget(i, onLabel = false).takeIf { across <= r && along in PIE_ITEM_RADIUS..PIE_LABEL_START + r }
}

private val Item = Color(0xE6202227)
private val Edge = Color(0x40FFFFFF)
private val Picked = Color(0xFF4C8DFF)
private val Raised = Color(0xF02C3648)

/**
 * A pie of items around the pinch point: item 0 at the top, the rest clockwise, matching
 * [dev.improve.vrcode.hands.RadialMenu]. Each item is a circular icon. Hovering one reveals its
 * label pill well out on its spoke ([pieLabelCentre]): it fills over the one-second dwell that runs
 * the item, and reaching it runs the item at once. The icon itself never fills. The app runs items
 * from the hand, so nothing here has a click handler. The other hand's ray can hover icons and
 * labels too ([pieRayTarget]). The centre circle names the menu.
 */
@Composable
fun PieMenu(state: PieState) {
  // Where the other hand's ray is, as fractions of the side from the centre (y up). One handler for
  // the whole pie, so moving straight from an icon across the gap to its label is never missed, as
  // it was with a hoverable per element (Compose sent no enter to the next sibling).
  val ray = remember { mutableStateOf<Offset?>(null) }
  val target = ray.value?.let { pieRayTarget(it.x, it.y, state.angles, state.highlighted) }
  LaunchedEffect(target) {
    state.rayHovered = target?.takeUnless { it.onLabel }?.item
    state.rayRun = target?.takeIf { it.onLabel }?.item
  }
  BoxWithConstraints(
    Modifier.fillMaxSize().pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull() ?: continue
          val gone = event.type == PointerEventType.Exit || (change.type == PointerType.Touch && !change.pressed)
          val px = minOf(size.width, size.height).toFloat()
          ray.value = if (gone) null else Offset((change.position.x - size.width / 2f) / px, (size.height / 2f - change.position.y) / px)
        }
      }
    },
  ) {
    val side = minOf(maxWidth, maxHeight)
    val radius = side * PIE_ITEM_RADIUS
    val iconD = side * PIE_ITEM_HEIGHT
    val font = with(LocalDensity.current) { (iconD * 0.27f).toSp() }
    Box(
      Modifier.align(Alignment.Center).size(side * state.cancelFraction).clip(CircleShape)
        .background(Color(0x66202227)).border(1.dp, Edge, CircleShape),
      contentAlignment = Alignment.Center,
    ) {
      BasicText(state.title, style = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = font * 0.8f, textAlign = TextAlign.Center))
    }
    val chosen = state.chosen
    for ((i, item) in state.items.withIndex()) {
      val degrees = state.angleOf(i)
      val angle = Math.toRadians(degrees.toDouble())
      val picked = state.highlighted == i
      val dim = if (chosen == null || chosen == i) 1f else 0.35f
      Box(
        Modifier.align(Alignment.Center)
          .offset(radius * sin(angle).toFloat(), -radius * cos(angle).toFloat())
          .size(iconD)
          .alpha(dim)
          .clip(CircleShape)
          .background(if (picked) Raised else Item)
          .border(if (picked) 2.dp else 1.dp, if (picked) Picked else Edge, CircleShape)
          .semantics { contentDescription = item.label },
        contentAlignment = Alignment.Center,
      ) {
        PieIconImage(item.icon, Modifier.size(iconD * 0.5f))
      }
      // The hovered item's label fades in, out along its spoke.
      val shown by animateFloatAsState(if (picked) 1f else 0f, tween(140), label = "quick action label")
      if (shown > 0.01f) {
        val shape = RoundedCornerShape(50)
        val (lx, ly) = pieLabelCentre(degrees)
        Box(
          Modifier.align(Alignment.Center)
            .offset(side * lx, -side * ly)
            .size(side * PIE_LABEL_WIDTH, iconD)
            .alpha(shown * dim)
            .clip(shape)
            .background(Raised)
            .border(if (chosen == i) 3.dp else 1.dp, if (chosen == i) Color.White else Picked, shape)
            .testTag(pieLabelTag(i))
            .semantics(mergeDescendants = true) {
              progressBarRangeInfo = ProgressBarRangeInfo(state.progress, 0f..1f)
            },
          contentAlignment = Alignment.Center,
        ) {
          // Fill from the end nearest the icon.
          PillFill(state.progress, fromRight = lx < -1e-3f, Modifier.matchParentSize())
          BasicText(
            item.label,
            modifier = Modifier.fillMaxWidth().padding(horizontal = iconD * 0.2f),
            maxLines = 1,
            softWrap = false,
            style = TextStyle(color = Color.White, fontSize = font, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
          )
        }
      }
    }
  }
}

/** Test tag of item [index]'s label pill. */
fun pieLabelTag(index: Int) = "pie-label-$index"

/** [progress] of the pill filled from its left end, or from its right end when [fromRight]. */
@Composable
private fun PillFill(progress: Float, fromRight: Boolean, modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width * progress.coerceIn(0f, 1f)
    drawRect(Picked.copy(alpha = 0.8f), topLeft = Offset(if (fromRight) size.width - w else 0f, 0f), size = Size(w, size.height))
  }
}

/** [icon] in white, as the pie draws it; Settings' quick inputs editor shows the same icons. */
@Composable
fun PieIconImage(icon: PieIcon, modifier: Modifier) {
  val glyph = icon.glyph
  if (glyph == null) QuickActionIcon(icon, modifier) else GlyphIcon(glyph, modifier)
}

/** A key name as letters ("^D", "PgUp"), sized to [modifier]'s box and free to overhang it a little. */
@Composable
private fun GlyphIcon(text: String, modifier: Modifier) {
  BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
    val size = with(LocalDensity.current) { (maxHeight * if (text.length <= 2) 0.8f else 0.5f).toSp() }
    BasicText(
      text,
      // The item (or its Settings tile) already names the key; the glyph is decoration.
      Modifier.wrapContentSize(unbounded = true).clearAndSetSemantics {},
      maxLines = 1,
      softWrap = false,
      style = TextStyle(color = Color.White, fontSize = size, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
    )
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
        PieIcon.AddTerminal -> {
          // Leave the lower-right corner open so the plus stays distinct at icon size,
          // including over the hovered pill's progress fill.
          drawPath(Path().apply {
            moveTo(13f, 20f)
            lineTo(4f, 20f); quadraticTo(2f, 20f, 2f, 18f)
            lineTo(2f, 6f); quadraticTo(2f, 4f, 4f, 4f)
            lineTo(20f, 4f); quadraticTo(22f, 4f, 22f, 6f)
            lineTo(22f, 11f)
          }, ink, style = stroke)
          line(6f, 8f, 10f, 12f); line(10f, 12f, 6f, 16f); line(13f, 16f, 14f, 16f)
          line(16f, 18f, 22f, 18f); line(19f, 15f, 19f, 21f)
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
        PieIcon.BringForward -> {
          // A small window with arrows out of its corners: it comes toward you.
          box(8f, 8.5f, 8f, 7f)
          line(8f, 11f, 16f, 11f)
          for ((cx, cy, dx, dy) in listOf(
            floatArrayOf(6f, 6.5f, -1f, -1f), floatArrayOf(18f, 6.5f, 1f, -1f),
            floatArrayOf(6f, 17.5f, -1f, 1f), floatArrayOf(18f, 17.5f, 1f, 1f),
          )) {
            val tipX = cx + dx * 4f
            val tipY = cy + dy * 4f
            line(cx, cy, tipX, tipY)
            line(tipX, tipY, tipX - dx * 3f, tipY)
            line(tipX, tipY, tipX, tipY - dy * 3f)
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
        PieIcon.Tab -> {
          line(3f, 12f, 17f, 12f); line(17f, 12f, 12f, 7f); line(17f, 12f, 12f, 17f)
          line(21f, 5f, 21f, 19f)
        }
        PieIcon.ShiftTab -> {
          // Tab, mirrored: back to the previous stop.
          line(21f, 12f, 7f, 12f); line(7f, 12f, 12f, 7f); line(7f, 12f, 12f, 17f)
          line(3f, 5f, 3f, 19f)
        }
        PieIcon.Backspace -> {
          drawPath(Path().apply {
            moveTo(8f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(8f, 19f); lineTo(2f, 12f); close()
          }, ink, style = stroke)
          line(11f, 9f, 17f, 15f); line(17f, 9f, 11f, 15f)
        }
        PieIcon.ArrowUp, PieIcon.ArrowRight, PieIcon.ArrowDown, PieIcon.ArrowLeft -> {
          val turn = when (icon) {
            PieIcon.ArrowRight -> 90f
            PieIcon.ArrowDown -> 180f
            PieIcon.ArrowLeft -> 270f
            else -> 0f
          }
          rotate(turn, pivot = Offset(12f, 12f)) {
            line(12f, 20f, 12f, 4f); line(12f, 4f, 6f, 10f); line(12f, 4f, 18f, 10f)
          }
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
        // Drawn as text by GlyphIcon.
        PieIcon.CtrlD, PieIcon.CtrlZ, PieIcon.CtrlL, PieIcon.CtrlR, PieIcon.PageUp, PieIcon.PageDown,
        PieIcon.Home, PieIcon.End -> Unit
      }
    }
  }
}

/** How far the thumb and middle finger are toward a pinch, 0 to 1. */
class PieHintState {
  var progress by mutableFloatStateOf(0f)
}

/**
 * The hint on either hand's middle fingertip while its palm faces the user, sized like the system's
 * own menu glyph: a ring whose centre fills as the thumb closes on the middle finger. Pinching opens
 * the palm menu. Drawn in proportion to its panel, so the ring keeps its look at any hint size.
 */
@Composable
fun PieHint(state: PieHintState) {
  Canvas(Modifier.fillMaxSize()) {
    val r = size.minDimension / 2 * 0.92f
    val ring = r * 0.12f
    drawCircle(Color(0x99202227), radius = r)
    drawCircle(Color.White.copy(alpha = 0.85f), radius = r - ring / 2, style = Stroke(ring))
    // A full pinch fills the ring completely, so "almost full" always means "not quite pinched".
    val p = state.progress
    drawCircle(Picked.copy(alpha = 0.55f + 0.45f * p), radius = r * (0.3f + 0.7f * p))
  }
}
