package dev.improve.vrcode

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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

/** The style is read during composition so existing panels update without being remounted. */
@Composable
fun VrcodeWindowHeader(
  state: TerminalWindowState,
  style: WindowStyle,
  chrome: WindowChrome,
  onDrag: ((anchor: Offset, position: Offset) -> Unit)? = null,
) {
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
  Box(Modifier.fillMaxWidth().then(dragModifier).testTag("vrcode-title-bar")) {
    when (style) {
      WindowStyle.MacOS -> MacOSHeader(state, chrome)
      WindowStyle.Windows -> WindowsHeader(state, chrome)
    }
  }
}

/** Reserve room for actions so long titles cannot cover the mode toggle or settings. */
@Composable
private fun MacOSHeader(state: TerminalWindowState, chrome: WindowChrome) {
  Row(
    Modifier.fillMaxWidth().height(chrome.headerHeightDp.dp)
      .background(Color(chrome.headerColor)).padding(horizontal = 16.dp).testTag("xmux-header"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
      TrafficLight(0xFFFF5F57, "xmux-close", chrome.onClose)
      TrafficLight(0xFFFEBC2E, "xmux-minimize", chrome.onMinimize)
      TrafficLight(0xFF28C840, "xmux-zoom", chrome.onZoom)
      chrome.leadingSlot()
    }
    Text(
      state.title, Modifier.weight(1f).padding(horizontal = 12.dp).testTag("xmux-title"),
      color = Color(chrome.titleColor), fontSize = 15.sp, fontWeight = FontWeight.Medium,
      maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
    )
    chrome.trailingSlot()
    if (state.loading) {
      CircularProgressIndicator(
        Modifier.padding(start = 8.dp).size(16.dp).testTag("xmux-spinner"),
        color = Color(0xFFA0A0A0), strokeWidth = 2.dp,
      )
    }
  }
}

@Composable
private fun TrafficLight(color: Long, tag: String, onClick: (() -> Unit)?) {
  val base = Modifier.size(12.dp).clip(CircleShape).background(Color(color)).testTag(tag)
  Box(if (onClick == null) base else base.clickable(onClick = onClick))
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
private fun WindowsHeader(state: TerminalWindowState, chrome: WindowChrome) {
  Row(
    Modifier.fillMaxWidth().height(chrome.headerHeightDp.dp)
      .background(Color(chrome.headerColor)).testTag("vrcode-windows-header"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Spacer(Modifier.width(8.dp))
    chrome.leadingSlot()
    Text(
      state.title,
      Modifier.weight(1f).padding(horizontal = 12.dp).testTag("xmux-title"),
      color = Color(chrome.titleColor), fontSize = 15.sp, fontWeight = FontWeight.Medium,
      maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    chrome.trailingSlot()
    if (state.loading) {
      CircularProgressIndicator(
        Modifier.padding(horizontal = 12.dp).size(16.dp).testTag("xmux-spinner"),
        color = Color(0xFFA0A0A0), strokeWidth = 2.dp,
      )
    }
    WindowsControl("Minimize", "xmux-minimize", Color(chrome.titleColor), chrome.onMinimize)
    WindowsControl("Maximize", "xmux-zoom", Color(chrome.titleColor), chrome.onZoom)
    WindowsControl("Close", "xmux-close", Color(chrome.titleColor), chrome.onClose)
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
