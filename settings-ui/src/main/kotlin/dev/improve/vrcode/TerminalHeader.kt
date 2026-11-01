package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowChrome

/**
 * The frame outline for a terminal window: a thin accent ring on the focused window (where voice and
 * keys go), the plain hairline everywhere else.
 */
fun windowOutline(focused: Boolean, theme: TerminalTheme): BorderStroke =
  if (focused) BorderStroke(FOCUSED_OUTLINE_DP.dp, Color(theme.accent)) else WindowChrome.DefaultOutline

const val FOCUSED_OUTLINE_DP = 3

/** Title text of an unfocused window, as a fraction of the focused title's opacity. */
const val INACTIVE_TITLE_ALPHA = 0.5f

/** Header slot content (toolbar buttons) and the spinner of an unfocused window. */
const val INACTIVE_TOOLBAR_ALPHA = 0.5f

/**
 * The style is read during composition so existing panels update without being remounted.
 *
 * An unfocused window looks like an inactive Mac window: gray traffic lights that regain their color
 * while hovered, a dimmed title and toolbar, and a title bar flattened toward the body color. Windows
 * style dims its title and controls the same way. [badge] is status that must stay readable on any
 * window (where voice is about to type, for instance), so it is never dimmed.
 *
 * [onActivate] runs when the title bar is pressed outside a button that takes the press, which
 * brings a background window forward the way clicking a Mac title bar does.
 *
 * A spinner at the top right, level with the traffic lights, shows while the window is attaching
 * ([TerminalWindowState.loading]) or while the link to the Mac lags ([lagging]), so slow keys and
 * output have a visible cause. It is never dimmed, and its place is always reserved, so the header's
 * buttons do not move under the ray when it appears.
 */
@Composable
fun VrcodeWindowHeader(
  state: TerminalWindowState,
  style: WindowStyle,
  chrome: WindowChrome,
  onDrag: ((anchor: Offset, position: Offset) -> Unit)? = null,
  fullscreen: Boolean = false,
  hoverState: TerminalHeaderHover? = null,
  focused: Boolean = true,
  badge: @Composable () -> Unit = {},
  onActivate: (() -> Unit)? = null,
  lagging: Boolean = false,
) {
  // Observes the press without consuming it, so the drag below still sees it.
  val activateModifier = if (onActivate == null) Modifier else Modifier.pointerInput(onActivate) {
    awaitEachGesture {
      awaitFirstDown()
      onActivate()
    }
  }
  val dragModifier = if (onDrag == null) Modifier else Modifier.pointerInput(onDrag) {
    awaitEachGesture {
      // Child buttons consume their down event first; a press on them never starts a move.
      val down = awaitFirstDown()
      val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
      if (start != null) {
        onDrag(down.position, start.position)
        drag(start.id) { change ->
          change.consume()
          onDrag(down.position, change.position)
        }
      }
    }
  }
  Box(Modifier.fillMaxWidth().then(activateModifier).then(dragModifier).testTag("vrcode-title-bar")) {
    when (style) {
      WindowStyle.MacOS -> MacOSHeader(state, chrome, fullscreen, hoverState, focused, badge, lagging)
      WindowStyle.Windows -> WindowsHeader(state, chrome, fullscreen, focused, badge, lagging)
    }
  }
}

/** An inactive Mac title bar loses its contrast with the window body. */
private fun headerColor(chrome: WindowChrome, focused: Boolean): Color =
  if (focused) Color(chrome.headerColor) else lerp(Color(chrome.headerColor), Color(chrome.bodyColor), 0.5f)

private fun titleColor(chrome: WindowChrome, focused: Boolean): Color =
  Color(chrome.titleColor).let { if (focused) it else it.copy(alpha = it.alpha * INACTIVE_TITLE_ALPHA) }

/** Diameter of the header's status spinner; its place is reserved whether or not it shows. */
const val STATUS_SPINNER_DP = 22

/** Attaching or a lagging link: the spinner at the header's right end. */
@Composable
private fun StatusSpinner(state: TerminalWindowState, lagging: Boolean, chrome: WindowChrome, modifier: Modifier) {
  Box(modifier.size(STATUS_SPINNER_DP.dp).testTag("vrcode-status-slot"), contentAlignment = Alignment.Center) {
    if (state.loading || lagging) {
      val label = if (state.loading) "Connecting" else "Connection is slow"
      CircularProgressIndicator(
        Modifier.fillMaxSize().testTag("xmux-spinner").semantics { contentDescription = label },
        color = Color(chrome.titleColor).copy(alpha = 0.85f), strokeWidth = 2.5.dp,
      )
    }
  }
}

/** Slot content in a row, dimmed while the window is unfocused. */
@Composable
private fun Toolbar(focused: Boolean, tag: String, content: @Composable () -> Unit) {
  Row(Modifier.alpha(if (focused) 1f else INACTIVE_TOOLBAR_ALPHA).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
    content()
  }
}

/** Reserve room for actions so long titles cannot cover the mode toggle or settings. */
@Composable
private fun MacOSHeader(state: TerminalWindowState, chrome: WindowChrome, fullscreen: Boolean, hoverState: TerminalHeaderHover?,
  focused: Boolean, badge: @Composable () -> Unit, lagging: Boolean) {
  // The inactive gray sits between the header and title colors, so it reads on light and dark themes.
  val inactive = if (focused) null else lerp(Color(chrome.headerColor), Color(chrome.titleColor), 0.32f)
  Row(
    Modifier.fillMaxWidth().height(chrome.headerHeightDp.dp)
      .background(headerColor(chrome, focused)).padding(horizontal = 16.dp).testTag("xmux-header"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    TrafficLights(chrome, fullscreen, hoverState, inactive)
    Toolbar(focused, "vrcode-leading-toolbar", chrome.leadingSlot)
    Text(
      state.title, Modifier.weight(1f).padding(horizontal = 12.dp).testTag("xmux-title"),
      color = titleColor(chrome, focused), fontSize = 15.sp, fontWeight = FontWeight.Medium,
      maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    )
    badge()
    Toolbar(focused, "vrcode-trailing-toolbar", chrome.trailingSlot)
    StatusSpinner(state, lagging, chrome, Modifier.padding(start = 10.dp))
  }
}

@Composable
private fun TrafficLights(chrome: WindowChrome, fullscreen: Boolean, hoverState: TerminalHeaderHover?, inactive: Color?) {
  val tag = "vrcode-traffic-lights"
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val expanded = hovered || hoverState?.hovered == tag
  val expansion by animateFloatAsState(if (expanded) 1f else 0f, tween(160), label = "Traffic lights expansion")
  DisposableEffect(hoverState, tag) { onDispose { hoverState?.remove(tag) } }
  // Reserve room for the expanded group so the title and other header actions stay put.
  Box(Modifier.size(200.dp, 52.dp), contentAlignment = Alignment.CenterStart) {
    Row(
      Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.25f))
        .border(1.dp, Color.White.copy(alpha = 0.09f), CircleShape)
        .testTag(tag).onGloballyPositioned { hoverState?.place(tag, it.boundsInRoot()) }
        .hoverable(interaction).padding(horizontal = 4.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      TrafficLight(0xFFFF5F57, "Close", "xmux-close", chrome.onClose, expansion, inactive)
      TrafficLight(0xFFFEBC2E, "Minimize", "xmux-minimize", chrome.onMinimize, expansion, inactive)
      TrafficLight(0xFF28C840, if (fullscreen) "Restore window" else "Maximize", "xmux-zoom", chrome.onZoom,
        expansion, inactive)
    }
  }
}

/** [inactive] is the gray an unfocused window shows until the group is hovered; null when focused. */
@Composable
private fun TrafficLight(color: Long, label: String, tag: String, onClick: (() -> Unit)?,
  expansion: Float, inactive: Color?) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val base = Modifier.width((40f + 24f * expansion).dp).height((32f + 16f * expansion).dp).testTag(tag).semantics {
    contentDescription = label
    if (onClick == null) disabled()
  }
  Box(
    if (onClick == null) base else base.clickable(
      interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    val fill = if (inactive == null) Color(color) else lerp(inactive, Color(color), expansion)
    // Mac lights are plain circles at rest, colored or gray; the glyphs appear while the group is hovered.
    val glyph = expansion
    Box(
      Modifier.size((28f + 32f * expansion).dp, (28f + 16f * expansion).dp)
        .clip(CircleShape).background(fill.copy(alpha = if (onClick == null) 0.4f else 1f))
        .border(1.dp, Color.White.copy(alpha = 0.12f + 0.2f * expansion), CircleShape)
        .testTag("$tag-shape"),
      contentAlignment = Alignment.Center,
    ) {
      TrafficLightIcon(tag, restore = label == "Restore window", Modifier.size((12f + 6f * expansion).dp),
        Color.Black.copy(alpha = glyph * if (onClick == null) 0.35f else if (pressed) 0.95f else 0.75f))
    }
  }
}

@Composable
private fun TrafficLightIcon(tag: String, restore: Boolean, modifier: Modifier, ink: Color) {
  Canvas(modifier) {
    val stroke = 1.8.dp.toPx()
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(ink,
      Offset(size.width * x1, size.height * y1), Offset(size.width * x2, size.height * y2), stroke, StrokeCap.Round)
    when (tag) {
      "xmux-close" -> { line(0.15f, 0.15f, 0.85f, 0.85f); line(0.15f, 0.85f, 0.85f, 0.15f) }
      "xmux-minimize" -> line(0.1f, 0.5f, 0.9f, 0.5f)
      else -> {
        if (restore) {
          drawRect(ink, Offset(size.width * 0.32f, size.height * 0.08f), size * 0.6f, style = Stroke(stroke))
          drawRect(ink, Offset(size.width * 0.08f, size.height * 0.32f), size * 0.6f, style = Stroke(stroke))
        } else {
          line(0.12f, 0.44f, 0.12f, 0.12f); line(0.12f, 0.12f, 0.44f, 0.12f)
          line(0.56f, 0.88f, 0.88f, 0.88f); line(0.88f, 0.88f, 0.88f, 0.56f)
          line(0.14f, 0.14f, 0.4f, 0.4f); line(0.6f, 0.6f, 0.86f, 0.86f)
        }
      }
    }
  }
}

/** The visible label is the current mode; the switch semantics expose whether Follow is on. */
@Composable
fun WindowModeButton(mode: WindowMode, theme: TerminalTheme = TerminalTheme.Mac, onToggle: () -> Unit) {
  val following = mode == WindowMode.Follow
  val tint = if (theme != TerminalTheme.Mac) Color(if (following) theme.accent else theme.foreground)
    else if (following) Color(0xFFA9C7FF) else Color(0xFFC8C8C8)
  Box(
    Modifier.height(40.dp).widthIn(min = 68.dp).clip(RoundedCornerShape(8.dp))
      .background(if (!following) Color.Transparent else if (theme == TerminalTheme.Mac) Color(0xFF203958) else Color(theme.raisedBackground))
      .border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
      .testTag("vrcode-window-mode")
      .semantics {
        contentDescription = "Follow viewport"
        stateDescription = if (following) "Following viewport" else "Fixed in room"
      }
      .toggleable(following, role = Role.Switch, onValueChange = { onToggle() })
      .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(mode.label, color = tint, fontSize = 14.sp, maxLines = 1)
  }
}

@Composable
private fun WindowsHeader(state: TerminalWindowState, chrome: WindowChrome, fullscreen: Boolean, focused: Boolean,
  badge: @Composable () -> Unit, lagging: Boolean) {
  val ink = titleColor(chrome, focused)
  Row(
    Modifier.fillMaxWidth().height(chrome.headerHeightDp.dp)
      .background(headerColor(chrome, focused)).testTag("vrcode-windows-header"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(Modifier.width(8.dp))
    Toolbar(focused, "vrcode-leading-toolbar", chrome.leadingSlot)
    Text(
      state.title,
      Modifier.weight(1f).padding(horizontal = 12.dp).testTag("xmux-title"),
      color = ink, fontSize = 15.sp, fontWeight = FontWeight.Medium,
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    badge()
    Toolbar(focused, "vrcode-trailing-toolbar", chrome.trailingSlot)
    StatusSpinner(state, lagging, chrome, Modifier.padding(horizontal = 10.dp))
    WindowsControl("Minimize", "xmux-minimize", ink, chrome.onMinimize)
    WindowsControl(if (fullscreen) "Restore window" else "Maximize", "xmux-zoom", ink, chrome.onZoom)
    WindowsControl("Close", "xmux-close", ink, chrome.onClose)
  }
}

@Composable
private fun WindowsControl(label: String, tag: String, color: Color, onClick: (() -> Unit)?) {
  val control = Modifier.width(46.dp).fillMaxHeight().testTag(tag).semantics {
    contentDescription = label
    if (onClick == null) disabled()
  }
  Box(
    if (onClick == null) control else control.clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(Modifier.size(12.dp)) {
      val stroke = 1.dp.toPx()
      val inset = stroke / 2
      val end = size.width - inset
      when (tag) {
        "xmux-minimize" -> drawLine(color, Offset(inset, size.height / 2), Offset(end, size.height / 2), stroke)
        "xmux-zoom" -> drawRect(color, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
        else -> {
          drawLine(color, Offset(inset, inset), Offset(end, end), stroke)
          drawLine(color, Offset(inset, end), Offset(end, inset), stroke)
        }
      }
    }
  }
}
