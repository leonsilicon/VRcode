package dev.improve.vrcode

import androidx.compose.foundation.BorderStroke
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

/** The same panel is mounted in the headset and exercised on a regular Android emulator. */
@Composable
fun DockPanel(
  windows: List<DockWindow>,
  focusedWindow: Int?,
  connected: Boolean,
  keyboardShown: Boolean,
  onWindow: (Int) -> Unit,
  onKeyboard: () -> Unit,
  onArrange: () -> Unit,
  onSettings: () -> Unit,
  onConnection: () -> Unit,
) {
  VrcodeTheme {
    Surface(
      Modifier.fillMaxSize().padding(4.dp).testTag("vrcode-dock"),
      shape = RoundedCornerShape(28.dp), color = Color(0xF21A202B),
      contentColor = Color(0xFFE4EAF5), border = BorderStroke(1.dp, Color(0x40FFFFFF)),
    ) {
      Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
          Modifier.width(42.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onConnection).testTag("dock-connection")
            .semantics { contentDescription = if (connected) "Mac connected. Open connection settings" else "Connect to your Mac" },
          contentAlignment = Alignment.Center,
        ) {
          Box(Modifier.size(10.dp).clip(CircleShape).background(if (connected) Color(0xFF8CDCB0) else Color(0xFFE8B86D)))
        }
        DockDivider()
        Row(
          Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState()).selectableGroup().testTag("dock-windows"),
          horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
          if (windows.isEmpty()) {
            Text(if (connected) "No terminal windows" else "Connect your Mac", Modifier.padding(horizontal = 12.dp),
              fontSize = 13.sp, color = Color(0xFFB9C5D8), maxLines = 1)
          }
          for ((index, window) in windows.withIndex()) {
            val selected = window.id == focusedWindow
            val title = window.title.ifBlank { "Terminal ${index + 1}" }
            val tile = dockTile(selected).width(100.dp).testTag("dock-window-${window.id}")
              .semantics { contentDescription = "Show $title" }
              .selectable(selected, enabled = connected, role = Role.Tab, onClick = { onWindow(window.id) })
            DockTile(title, tile, selected = selected, enabled = connected) { TerminalGlyph() }
          }
        }
        DockDivider()
        DockTile("Keyboard", dockTile(keyboardShown).width(68.dp).testTag("dock-keyboard")
          .semantics { contentDescription = "Toggle floating keyboard" }
          .toggleable(keyboardShown, role = Role.Switch, onValueChange = { onKeyboard() }), selected = keyboardShown) { KeyboardGlyph() }
        DockTile("Arrange", dockTile(false).width(64.dp).testTag("dock-arrange")
          .semantics { contentDescription = "Arrange terminal windows in front of you" }
          .clickable(enabled = connected && windows.isNotEmpty(), role = Role.Button, onClick = onArrange),
          enabled = connected && windows.isNotEmpty()) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(24.dp)) }
        DockTile("Settings", dockTile(false).width(64.dp).testTag("dock-settings")
          .semantics { contentDescription = "Open VRcode settings" }
          .clickable(role = Role.Button, onClick = onSettings)) { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(24.dp)) }
      }
    }
  }
}

/**
 * The collapsed dock: a thin, faint line that is always rendered while VRcode runs, so the user can
 * tell it loaded without it drawing the eye. The whole (otherwise transparent) panel is the touch
 * target, so the line is easy to point at or press even though it is only a few millimetres tall.
 */
@Composable
fun DockPill(connected: Boolean, onExpand: () -> Unit) {
  VrcodeTheme {
    Box(
      Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onExpand).testTag("vrcode-dock-pill")
        .semantics { contentDescription = if (connected) "Show dock. Mac connected" else "Show dock. Mac not connected" },
      contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.fillMaxWidth(0.88f).height(5.dp).clip(CircleShape).background(Color(0x73FFFFFF)))
    }
  }
}

private fun dockTile(selected: Boolean): Modifier = Modifier.fillMaxHeight().clip(RoundedCornerShape(18.dp))
  .background(if (selected) Color(0xFF2C4263) else Color.Transparent)

@Composable
private fun DockTile(label: String, modifier: Modifier, selected: Boolean = false, enabled: Boolean = true, icon: @Composable () -> Unit) {
  Column(modifier.padding(horizontal = 5.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center) {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
    Text(label, Modifier.padding(top = 4.dp), fontSize = 11.sp, fontWeight = FontWeight.Medium,
      color = if (enabled) Color(0xFFE4EAF5) else Color(0xFF788497), maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.padding(top = 4.dp).size(4.dp).clip(CircleShape).background(if (selected) Color(0xFFA9C7FF) else Color.Transparent))
  }
}

@Composable
private fun DockDivider() = Box(Modifier.width(1.dp).height(36.dp).background(Color(0x30FFFFFF)))

@Composable
private fun TerminalGlyph() {
  Canvas(Modifier.size(24.dp)) {
    val tint = Color(0xFFA9C7FF)
    val stroke = size.width * 0.065f
    drawRoundRect(tint, Offset(stroke, stroke * 2), Size(size.width - stroke * 2, size.height - stroke * 4), CornerRadius(stroke * 2), style = Stroke(stroke))
    drawLine(tint, Offset(size.width * 0.24f, size.height * 0.36f), Offset(size.width * 0.4f, size.height * 0.5f), stroke)
    drawLine(tint, Offset(size.width * 0.4f, size.height * 0.5f), Offset(size.width * 0.24f, size.height * 0.64f), stroke)
    drawLine(tint, Offset(size.width * 0.51f, size.height * 0.64f), Offset(size.width * 0.73f, size.height * 0.64f), stroke)
  }
}

@Composable
private fun KeyboardGlyph() {
  Canvas(Modifier.size(24.dp)) {
    val tint = Color(0xFFE4EAF5)
    val stroke = size.width * 0.06f
    drawRoundRect(tint, Offset(stroke, size.height * 0.18f), Size(size.width - stroke * 2, size.height * 0.64f), CornerRadius(stroke * 2), style = Stroke(stroke))
    for (row in 0..1) for (col in 0..3) {
      drawCircle(tint, stroke * 0.65f, Offset(size.width * (0.22f + col * 0.185f), size.height * (0.36f + row * 0.16f)))
    }
    drawLine(tint, Offset(size.width * 0.3f, size.height * 0.68f), Offset(size.width * 0.7f, size.height * 0.68f), stroke)
  }
}
