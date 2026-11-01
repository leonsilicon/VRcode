package dev.improve.vrcode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** IDs belong to the live bridge, never a locally stored terminal list. */
data class DockWindow(val id: Int, val title: String)

enum class DockOptionKind { Connection, Window, Keyboard, Arrange, Settings }

/**
 * One icon of the raised dock. A window always shows a running dot; [selected] brightens it for
 * the focused window, and gives the keyboard one while it is open. [hidden] is a window that is
 * minimized or behind a fullscreen one, drawn translucent like a hidden app in the macOS Dock.
 * [enabled] is the bridge's state for Connection (green online, amber offline); a disabled
 * window, or Arrange, is faded.
 */
data class DockOption(
  val label: String,
  val kind: DockOptionKind,
  val selected: Boolean = false,
  val enabled: Boolean = true,
  val hidden: Boolean = false,
)

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
 * The sheet's handle, like an iPhone bottom sheet's grabber: a short, faint, rounded bar. Its
 * spatial panel follows the user's pinch and is always the top edge of the sheet; when [raised] it
 * sits inside the sheet, above the options, and is drawn over it.
 * A hand near it makes it bulge ([bulge], 0..1) and shows a chevron: up to pull the dock up, down to
 * lower it. Movement belongs to the spatial panel so the drawing and hit target stay together.
 * Every touch goes to [onTouch]; a plain touch never toggles the dock.
 */
@Composable
fun DockBar(bulge: Float, raised: Boolean = false, barFraction: Float = 0.7f, onTouch: (DockTouch) -> Unit = {}) {
  val b by animateFloatAsState(bulge.coerceIn(0f, 1f), spring(dampingRatio = 0.6f, stiffness = 500f), label = "bulge")
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().testTag("vrcode-dock-bar").dockTouches(onTouch), contentAlignment = Alignment.Center) {
      val barHeight = 4.dp + 4.dp * b
      Box(
        Modifier.width(maxWidth * barFraction * (1f + 0.15f * b))
          .height(barHeight)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = (if (raised) 0.55f else 0.3f) + 0.4f * b)),
      )
      if (b > 0.05f) {
        Text(if (raised) "⌄" else "⌃", Modifier.offset(y = maxHeight * (if (raised) 0.24f else -0.32f)),
          color = Color.White.copy(alpha = 0.7f * b), fontSize = 12.sp, fontWeight = FontWeight.Bold)
      }
    }
  }
}

private val DockSheetShape = RoundedCornerShape(22.dp)

/** The shelf is never narrower than this fraction of the panel, so the grabber always sits on its top edge. */
private const val MIN_SHELF_FRACTION = 0.3f

/**
 * The dock's sheet: it moves like an iPhone bottom sheet and looks like the macOS Dock. The panel
 * is the size of the expanded sheet. The sheet's bottom edge stays at the panel's bottom and its
 * top edge, which carries the handle (drawn by [DockBar] on its own panel), sits [drop] (a fraction
 * of the panel's height) below the panel's top: 0 when expanded. The icons and their tooltip ride
 * with the top edge, so they rise into view out of the sheet's bottom as it is pulled up. [reveal]
 * fades the sheet in at the start of the swipe.
 *
 * Like the macOS Dock, the sheet is a translucent graphite shelf that hugs a row of square icons
 * ([DockTile]), with a separator at [separatorX] between the terminals and the tools, and a running
 * dot under each window. [xs] are the icons' centres and [slot] the distance between neighbours,
 * both fractions of the panel's width; every icon size follows [slot] ([DockShelf]), so icons shrink
 * rather than overlap when many windows are open. [rowY] (the icons' centres) and [labelY] are
 * fractions of the panel's height from the expanded sheet's top.
 *
 * Icons grow, bottom-anchored, under the fingertip at [hoverX] (a fraction of the width), or under
 * [highlighted] without one; the highlighted icon's name shows in a tooltip at [labelY], between
 * the row and the handle. Touching an icon runs it; every touch goes to [onTouch] as it happens.
 */
@Composable
fun DockBody(
  options: List<DockOption>,
  xs: List<Float>,
  slot: Float,
  highlighted: Int?,
  rowY: Float,
  labelY: Float,
  separatorX: Float? = null,
  hoverX: Float? = null,
  drop: Float = 0f,
  reveal: Float = 1f,
  onTouch: (DockTouch) -> Unit = {},
) {
  val centre = hoverX ?: highlighted?.let { xs.getOrNull(it) }
  // Where the magnification was last centred, so it shrinks back in place once the hand leaves.
  val anchor = remember { floatArrayOf(0.5f) }
  if (centre != null) anchor[0] = centre
  val magnify by animateFloatAsState(if (centre != null) 1f else 0f, tween(140), label = "magnify")
  fun scaleAt(x: Float) = 1f + (DockShelf.magnification((x - anchor[0]) / slot.coerceAtLeast(0.0001f)) - 1f) * magnify
  VrcodeTheme {
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = reveal.coerceIn(0f, 1f) }.testTag("vrcode-dock").dockTouches(onTouch)) {
      val w = maxWidth
      val h = maxHeight
      val top = h * drop.coerceIn(0f, 1f)
      val slotDp = w * slot
      val end = slotDp * DockShelf.END
      val left = minOf(w * (xs.firstOrNull() ?: 0.5f) - end, w * (0.5f - MIN_SHELF_FRACTION / 2f)).coerceAtLeast(0.dp)
      val right = maxOf(w * (xs.lastOrNull() ?: 0.5f) + end, w * (0.5f + MIN_SHELF_FRACTION / 2f)).coerceAtMost(w)
      // The visible shelf, clipping what rides inside it at its rounded edges.
      Box(
        Modifier.offset(x = left, y = top).size(right - left, h - top).clip(DockSheetShape)
          .background(DockShelfBrush).border(1.dp, DockShelfEdge, DockSheetShape)
          .testTag("vrcode-dock-sheet"),
      ) {
        // The expanded layout in panel coordinates, pinned to the sheet's top edge so it moves with the handle.
        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).offset(x = -left).requiredSize(w, h)) {
          val tile = slotDp * DockShelf.TILE
          val rowCentre = h * rowY
          val tileBottom = rowCentre + tile / 2
          separatorX?.let { x ->
            Box(Modifier.offset(x = w * x - 0.5.dp, y = rowCentre - tile * 0.42f).size(1.dp, tile * 0.84f).background(DockSeparatorColor))
          }
          for ((i, option) in options.withIndex()) {
            val x = xs.getOrElse(i) { 0.5f }
            val scale = scaleAt(x)
            val size = tile * scale
            Box(
              Modifier.offset(x = w * x - size / 2, y = tileBottom - size).zIndex(scale)
                .graphicsLayer { alpha = dockTileAlpha(option) }
                .clearAndSetSemantics { contentDescription = option.label; testTag = "dock-option-$i" },
            ) { DockTile(option, size) }
            dockRunningDot(option)?.let { color ->
              val dot = slotDp * DockShelf.DOT
              Box(Modifier.offset(x = w * x - dot / 2, y = tileBottom + slotDp * DockShelf.PAD / 2 - dot / 2).size(dot).background(color, CircleShape))
            }
          }
          val picked = highlighted?.takeIf { it in options.indices }
          if (picked != null) {
            DockTooltip(options[picked].label, centreX = w * xs.getOrElse(picked) { 0.5f }, centreY = h * labelY, minX = left, maxX = right)
          }
        }
      }
    }
  }
}
