package dev.improve.vrcode.windows

import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.dock.DockPlacement
import dev.improve.vrcode.voice.ThinkingPlacement
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pooled, nonmodal info boxes in the targeted terminal's plane, following its pose and size. An
 * action with no window to target (none is shown, or VRcode isn't connected to the Mac) shows in one
 * box ahead of the user instead, placed where they were heading when it appeared, so its failure is
 * never silent. Creation moves its notice from there to the new window once that mounts.
 */
internal class TerminalInfoPanels(private val xmux: XmuxSpatial) {
  val notices = TerminalActionNotices<ScreenWindow>()
  private class Slot(val entity: Entity, val ahead: Boolean) {
    var window by mutableStateOf<ScreenWindow?>(null)
    var shown = false
  }
  private val slots = mutableListOf<Slot>()
  /** The box for notices without a window; mounted the first time one appears. */
  private var ahead: Slot? = null
  private val heightM get() = HEIGHT_PX * xmux.config.windowWidthM / xmux.spec.widthPx

  fun start(window: ScreenWindow?, title: String, message: String) = notices.start(window, title, message).also {
    Log.i(TAG, "${it.title}: running on screen ${window?.screen?.value}: $message")
  }

  fun finish(notice: TerminalActionNotice<ScreenWindow>, message: String, failed: Boolean = false) {
    notices.finish(notice, message, failed, SystemClock.uptimeMillis())
    Log.i(TAG, "${notice.title}: ${notice.state} on screen ${notice.target?.screen?.value}: $message")
  }

  /** Space reserved above a window, so its AI thinking panel can stack above this info box. */
  fun occupiedHeight(window: ScreenWindow): Float =
    if (notices.entries.any { it.target == window }) heightM + ThinkingPlacement.GAP_M else 0f

  fun tick(visible: Boolean) {
    notices.prune(xmux.windows, SystemClock.uptimeMillis())
    val windows = notices.entries.mapNotNull { it.target }.distinct()
    for (slot in slots) if (slot.window !in windows) slot.window = null
    for (window in windows) if (slots.none { it.window == window }) {
      (slots.firstOrNull { it.window == null } ?: newSlot()).window = window
      Log.i(TAG, "info box above screen ${window.screen?.value}")
    }
    for (slot in slots) {
      val window = slot.window
      val pose = window?.takeIf { visible && it.visible }?.pose()
      if (pose != null) slot.entity.setComponent(Transform(ThinkingPlacement.pose(pose, window.heightM, heightM, above = true)))
      val show = pose != null
      if (slot.shown != show) { slot.entity.setComponent(Visible(show)); slot.shown = show }
    }
    tickAhead(visible)
  }

  private fun tickAhead(visible: Boolean) {
    val show = visible && notices.entries.any { it.target == null }
    val slot = ahead ?: (if (show) newSlot(ahead = true).also { ahead = it } else return)
    if (slot.shown == show) return
    // Placed once per showing, so it stays put (and readable) while the user looks around.
    if (show) headPose()?.let { slot.entity.setComponent(Transform(aheadPose(it))) }
    slot.entity.setComponent(Visible(show))
    slot.shown = show
    Log.i(TAG, if (show) "info box ahead of the user (no target window)" else "info box ahead of the user hidden")
  }

  private fun headPose(): Pose? = Query.where { has(AvatarAttachment.id) }
    .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }.eval()
    .firstOrNull()?.tryGetComponent<Transform>()?.transform?.takeIf(WindowLayout::isUsableHeadPose)

  fun clear() { notices.entries.clear() }

  fun close() {
    clear()
    (slots + listOfNotNull(ahead)).forEach { it.entity.destroy() }
    slots.clear()
    ahead = null
  }

  private fun newSlot(ahead: Boolean = false): Slot {
    lateinit var slot: Slot
    val entity = xmux.mountPanel(WIDTH_PX, HEIGHT_PX, Placement.InFrontOfHead) {
      if (slot.ahead) TerminalInfoPanel(notices.entries.filter { it.target == null }) { notices.entries.remove(it) }
      else slot.window?.let { window ->
        TerminalInfoPanel(notices.entries.filter { it.target == window }) { notices.entries.remove(it) }
      }
    }
    entity.setComponent(Visible(false))
    // Leave ISDK's components installed; the window's pose takes precedence over grabbing.
    return Slot(entity, ahead).also { slot = it; if (!ahead) slots.add(it) }
  }

  private companion object {
    const val TAG = "VrcodeTerminalInfo"
    const val WIDTH_PX = 560
    const val HEIGHT_PX = 200
  }
}

/** How far ahead of the eyes, and how far below them, a notice without a window sits. */
internal const val AHEAD_DISTANCE_M = 1.0f
internal const val AHEAD_DROP_M = 0.1f

/**
 * Level with the user's heading, facing them: palm menu actions are picked while looking down at the
 * hand, so the head's tilt must not put the box on the floor. Nearer than new windows open, so a
 * terminal that mounts behind it never hides it.
 */
internal fun aheadPose(head: Pose): Pose {
  val yaw = Math.toRadians(DockPlacement.yawDegrees(head).toDouble())
  val offset = Vector3(sin(yaw).toFloat() * AHEAD_DISTANCE_M, -AHEAD_DROP_M, cos(yaw).toFloat() * AHEAD_DISTANCE_M)
  return Pose(head.t + offset, Quaternion.lookRotation(Vector3(offset.x, 0f, offset.z)))
}

@Composable
private fun TerminalInfoPanel(notices: List<TerminalActionNotice<ScreenWindow>>, dismiss: (TerminalActionNotice<ScreenWindow>) -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  Column(Modifier.fillMaxSize().clip(shape).background(Color(0xEB15161B)).border(1.dp, Color(0x40FFFFFF), shape)
    .padding(horizontal = 14.dp, vertical = 10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    for (notice in notices) {
      val running = notice.state == TerminalActionNotice.State.Running
      val accent = when (notice.state) {
        TerminalActionNotice.State.Running -> Color(0xFF8A5CF6)
        TerminalActionNotice.State.Done -> Color(0xFF22A45D)
        TerminalActionNotice.State.Failed -> Color(0xFFFF6B6B)
      }
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          if (running) CircularProgressIndicator(Modifier.size(10.dp), color = accent, strokeWidth = 2.dp)
          else androidx.compose.foundation.layout.Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
          BasicText(notice.title + if (running) "…" else "", Modifier.weight(1f),
            style = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold))
          if (!running) BasicText("Dismiss", style = TextStyle(color = Color(0xFFB9BDC6), fontSize = 12.sp),
            modifier = Modifier.semantics { role = Role.Button; contentDescription = "Dismiss ${notice.title}" }
              .clickable { dismiss(notice) }.padding(6.dp))
        }
        BasicText(notice.message, style = TextStyle(color = if (notice.state == TerminalActionNotice.State.Failed) accent else Color(0xFFDCE3F0), fontSize = 14.sp))
      }
    }
  }
}
