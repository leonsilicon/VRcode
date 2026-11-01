package dev.improve.vrcode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * The raised dock's proportions, after the macOS Dock: square tiles in slots of equal width, on a
 * shelf that hugs them. Lengths are in slots, the distance between neighbouring icons' centres, so
 * the whole dock shrinks with its icons when many windows are open.
 */
object DockShelf {
  /** A tile's width and height. */
  const val TILE = 0.8f
  /** The shelf's margin around a tile: above, below (where the running dot sits) and at the ends. */
  const val PAD = 0.18f
  /** The shelf's height: a tile with its margins above and below. */
  const val HEIGHT = TILE + 2 * PAD
  /** From an end icon's centre to the shelf's end. */
  const val END = TILE / 2 + PAD
  /** The gap the separator takes between the terminals and the tools. */
  const val SEPARATOR = 0.5f
  /** The running dot's diameter, centred in the margin below its tile. */
  const val DOT = 0.07f
  /**
   * The icon under the finger grows by this much, bottom-anchored; neighbours within [REACH] grow
   * less. Small enough that a magnified full-size tile stays below the label line in the sheet.
   */
  const val MAGNIFY = 1.25f
  const val REACH = 1.5f

  /**
   * How much an icon [slots] from the finger grows: [MAGNIFY] directly under it, easing to none
   * at [REACH]. The icons stay put, unlike macOS, so a touch always lands where an icon is drawn;
   * the falloff is narrow enough that neighbours never overlap by more than a hair.
   */
  fun magnification(slots: Float): Float {
    val d = abs(slots) / REACH
    if (d >= 1f) return 1f
    return 1f + (MAGNIFY - 1f) * cos(d * PI / 2).toFloat()
  }

  /**
   * A window title shortened to fit on its tile: the part before a " — " style separator, the last
   * component of a path, and only the number of a default "Terminal N" title.
   */
  fun shortTitle(title: String): String {
    val trimmed = title.trim()
    val head = trimmed.split(" — ", " – ", " - ", " | ").first().trim().ifEmpty { trimmed }
    val leaf = head.trimEnd('/').substringAfterLast('/').ifEmpty { head }
    return DEFAULT_TITLE.matchEntire(leaf)?.groupValues?.get(1) ?: leaf
  }

  private val DEFAULT_TITLE = Regex("""Terminal (\d+)""")
}

/** The shelf behind the icons: translucent graphite with a hairline edge, like the macOS Dock in dark mode. */
internal val DockShelfBrush = Brush.verticalGradient(listOf(Color(0xD83E3E44), Color(0xD8242428)))
internal val DockShelfEdge = Color(0x40FFFFFF)
internal val DockSeparatorColor = Color(0x59FFFFFF)

/** A disabled tool or window is faded; a hidden window is translucent, like a hidden macOS app. */
internal fun dockTileAlpha(option: DockOption): Float = when {
  option.kind != DockOptionKind.Connection && !option.enabled -> 0.4f
  option.hidden -> 0.5f
  else -> 1f
}

/** Every window is running; the focused one, and an open keyboard, get the bright dot. */
internal fun dockRunningDot(option: DockOption): Color? = when (option.kind) {
  DockOptionKind.Window -> if (option.selected) Color(0xF2FFFFFF) else Color(0x8CFFFFFF)
  DockOptionKind.Keyboard -> if (option.selected) Color(0xF2FFFFFF) else null
  else -> null
}

private fun tileGradient(option: DockOption): List<Color> = when (option.kind) {
  DockOptionKind.Window -> listOf(Color(0xFF46464B), Color(0xFF1A1A1D))
  DockOptionKind.Connection ->
    if (option.enabled) listOf(Color(0xFF5DE07C), Color(0xFF1E9C44)) else listOf(Color(0xFFFFC14F), Color(0xFFE07A00))
  DockOptionKind.Keyboard -> listOf(Color(0xFFFAFAFC), Color(0xFFC4C5CB))
  DockOptionKind.Arrange -> listOf(Color(0xFF7D7BFF), Color(0xFF3A36B0))
  DockOptionKind.Settings -> listOf(Color(0xFFB0B0B6), Color(0xFF5E5E65))
}

/**
 * A macOS-style app icon, [size] square: a squircle with a top-lit gradient, a hairline edge and a
 * soft shadow. A window's tile is Terminal.app's, with its short title underneath the prompt.
 */
@Composable
internal fun DockTile(option: DockOption, size: Dp) {
  Box(
    Modifier.size(size).shadow(size * 0.06f, DockSquircle)
      .background(Brush.verticalGradient(tileGradient(option)), DockSquircle)
      .border(1.dp, Color.White.copy(alpha = 0.16f), DockSquircle),
    contentAlignment = Alignment.Center,
  ) {
    when (option.kind) {
      DockOptionKind.Window -> {
        PromptGlyph(Modifier.matchParentSize())
        if (size >= 26.dp) {
          val fontSize = with(LocalDensity.current) { (size * 0.17f).toSp() }
          Text(
            DockShelf.shortTitle(option.label),
            Modifier.align(Alignment.BottomStart).padding(start = size * 0.14f, end = size * 0.1f, bottom = size * 0.1f),
            color = Color(0xCCFFFFFF), fontSize = fontSize, lineHeight = fontSize, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
          )
        }
      }
      DockOptionKind.Connection -> WirelessGlyph(Modifier.size(size * 0.62f))
      DockOptionKind.Keyboard -> DockKeyboardGlyph(Modifier.size(size * 0.66f))
      DockOptionKind.Arrange -> ArrangeGlyph(Modifier.size(size * 0.58f))
      DockOptionKind.Settings ->
        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(size * 0.66f), tint = Color(0xFFF4F4F6))
    }
  }
}

/**
 * The macOS Dock's name tooltip, centred on ([centreX], [centreY]) and kept between [minX] and
 * [maxX] (the shelf's ends), so the end icons' names are not clipped by the shelf.
 */
@Composable
internal fun DockTooltip(text: String, centreX: Dp, centreY: Dp, minX: Dp, maxX: Dp) {
  val shape = RoundedCornerShape(7.dp)
  Layout(
    content = {
      Text(
        text,
        Modifier.background(Color(0xE6545459), shape).border(1.dp, Color(0x40FFFFFF), shape).padding(horizontal = 8.dp, vertical = 2.dp),
        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
      )
    },
    modifier = Modifier.fillMaxSize().zIndex(10f),
  ) { measurables, constraints ->
    val lo = minX.roundToPx().coerceIn(0, constraints.maxWidth)
    val hi = maxX.roundToPx().coerceIn(lo, constraints.maxWidth)
    val label = measurables.single().measure(Constraints(maxWidth = ((hi - lo) * 0.8f).toInt().coerceAtLeast(0)))
    layout(constraints.maxWidth, constraints.maxHeight) {
      val x = (centreX.roundToPx() - label.width / 2).coerceIn(lo, (hi - label.width).coerceAtLeast(lo))
      label.place(x, (centreY.roundToPx() - label.height / 2).coerceAtLeast(0))
    }
  }
}

/**
 * Apple's icon outline is a squircle (a superellipse), not a rounded rectangle: its sides flow into
 * the corners without a visible join.
 */
internal val DockSquircle: Shape = GenericShape { size, _ ->
  val a = size.width / 2f
  val b = size.height / 2f
  for (i in 0..SQUIRCLE_STEPS) {
    val t = 2.0 * PI * i / SQUIRCLE_STEPS
    val c = cos(t)
    val s = sin(t)
    val x = a + a * (sign(c) * abs(c).pow(2.0 / SQUIRCLE_EXPONENT)).toFloat()
    val y = b + b * (sign(s) * abs(s).pow(2.0 / SQUIRCLE_EXPONENT)).toFloat()
    if (i == 0) moveTo(x, y) else lineTo(x, y)
  }
  close()
}

private const val SQUIRCLE_STEPS = 96
private const val SQUIRCLE_EXPONENT = 5.0

/** Terminal.app's "> _" prompt, in the tile's top-left. */
@Composable
private fun PromptGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val tint = Color(0xFFF2F2F2)
    val stroke = size.width * 0.07f
    drawLine(tint, Offset(size.width * 0.2f, size.height * 0.24f), Offset(size.width * 0.34f, size.height * 0.34f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(size.width * 0.34f, size.height * 0.34f), Offset(size.width * 0.2f, size.height * 0.44f), stroke, StrokeCap.Round)
    drawLine(tint, Offset(size.width * 0.42f, size.height * 0.44f), Offset(size.width * 0.6f, size.height * 0.44f), stroke, StrokeCap.Round)
  }
}

/** The connection: a dot under three arcs. */
@Composable
private fun WirelessGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val tint = Color.White
    val stroke = size.width * 0.085f
    val centre = Offset(size.width / 2f, size.height * 0.66f)
    for (radius in floatArrayOf(0.13f, 0.24f, 0.35f)) {
      val r = size.width * radius
      drawArc(tint, 225f, 90f, false, Offset(centre.x - r, centre.y - r), Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
    }
    drawCircle(tint, stroke * 0.75f, centre)
  }
}

/** Arrange, after Mission Control's icon: windows laid out side by side. */
@Composable
private fun ArrangeGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val tint = Color.White.copy(alpha = 0.95f)
    val corner = CornerRadius(size.width * 0.05f)
    drawRoundRect(tint, Offset(size.width * 0.04f, size.height * 0.12f), Size(size.width * 0.42f, size.height * 0.32f), corner)
    drawRoundRect(tint, Offset(size.width * 0.54f, size.height * 0.12f), Size(size.width * 0.42f, size.height * 0.32f), corner)
    drawRoundRect(tint, Offset(size.width * 0.2f, size.height * 0.54f), Size(size.width * 0.6f, size.height * 0.34f), corner)
  }
}

@Composable
private fun DockKeyboardGlyph(modifier: Modifier) {
  Canvas(modifier) {
    val tint = Color(0xFF3A3A3E)
    val stroke = size.width * 0.06f
    drawRoundRect(tint, Offset(stroke, size.height * 0.18f), Size(size.width - stroke * 2, size.height * 0.64f), CornerRadius(stroke * 2), style = Stroke(stroke))
    for (row in 0..1) for (col in 0..3) {
      drawCircle(tint, stroke * 0.65f, Offset(size.width * (0.22f + col * 0.185f), size.height * (0.36f + row * 0.16f)))
    }
    drawLine(tint, Offset(size.width * 0.3f, size.height * 0.68f), Offset(size.width * 0.7f, size.height * 0.68f), stroke)
  }
}
