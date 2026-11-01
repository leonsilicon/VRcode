package dev.improve.vrcode.windows

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.QuickInput
import dev.improve.vrcode.dock.DockPlacement
import dev.improve.vrcode.pie.PieIconImage
import dev.improve.vrcode.voice.ThinkingPlacement
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier as KeyModifier
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The xmux key press [this] sends (Ctrl chords as the key with Ctrl); null for [QuickInput.Keyboard]. */
internal fun QuickInput.xmuxKey(): Key? = key?.let { Key(it, if (ctrl) setOf(KeyModifier.Ctrl) else emptySet()) }

/** What the key bar under the focused window holds, left to right, in groups. Pure, for tests. */
internal object KeyBarKeys {
  val groups: List<List<QuickInput>> = listOf(
    listOf(QuickInput.Escape, QuickInput.Tab, QuickInput.ShiftTab),
    listOf(QuickInput.Left, QuickInput.Up, QuickInput.Down, QuickInput.Right),
    listOf(QuickInput.Backspace, QuickInput.CtrlC, QuickInput.Enter),
    listOf(QuickInput.Keyboard),
  )

  val keys: List<QuickInput> get() = groups.flatten()

  private val REPEATING = setOf(QuickInput.Left, QuickInput.Up, QuickInput.Down, QuickInput.Right, QuickInput.Backspace)

  /** Held, these repeat like a keyboard's keys (sent on press). The rest send once, on release over the key. */
  fun repeats(input: QuickInput): Boolean = input in REPEATING

  /** The caption under a key's icon, where the icon alone could be read two ways; null for icon-only keys. */
  fun caption(input: QuickInput): String? = when (input) {
    QuickInput.Escape -> "Esc"
    QuickInput.Tab -> "Tab"
    QuickInput.ShiftTab -> "⇧Tab"
    QuickInput.Enter -> "Enter"
    else -> null
  }

  /** Held keys wait this long before they repeat, then repeat this often. */
  const val REPEAT_DELAY_MS = 400L
  const val REPEAT_INTERVAL_MS = 70L
}

/** Where the key bar sits: centred under its window, in its plane, facing the same way. Pure, for tests. */
internal object KeyBarPlacement {
  /** Beyond the bottom edge's resize band, so the edge still resizes from anywhere along it. */
  const val CLEARANCE_M = 0.006f

  /** How far below the window's bottom edge the bar's top sits, for a window [distanceM] from the eyes. */
  fun gapM(distanceM: Float): Float = EdgeBand.bandM(distanceM) + CLEARANCE_M

  /** The bar's centre pose, [barHeightM] tall, [gapM] under a window at [window] that is [windowHeightM] tall. */
  fun pose(window: Pose, windowHeightM: Float, barHeightM: Float, gapM: Float): Pose =
    ThinkingPlacement.pose(window, windowHeightM, barHeightM, above = false, offsetM = gapM - ThinkingPlacement.GAP_M)

  /** How much of the space under its window the bar takes, so a thinking panel below the window clears it. */
  fun occupiedM(barHeightM: Float, gapM: Float): Float = gapM + barHeightM
}

/**
 * A bar of keys under the focused terminal window (Esc, Tab, Shift-Tab, the arrows, Backspace,
 * Ctrl-C, Enter and Keyboard), so the keys a TUI or an AI agent asks for are one press away
 * without the palm rings or a keyboard. It follows the window every frame, sits just past the
 * window's bottom resize band, and sends to that window's focused pane; Keyboard calls
 * [onKeyboard]. One pooled panel, moved to whichever window has focus. Main thread only.
 */
internal class KeyBar(private val xmux: XmuxSpatial, private val onKeyboard: () -> Unit) {
  private var entity: Entity? = null
  private var window: ScreenWindow? = null
  private var pose: Pose? = null
  private var gapM = 0f
  private var shown = false
  private val metresPerPx: Float get() = xmux.config.windowWidthM / xmux.spec.widthPx
  private val widthPx: Int get() = (WIDTH_M / metresPerPx).roundToInt()
  private val heightPx: Int get() = (HEIGHT_M / metresPerPx).roundToInt()
  private val heightM: Float get() = heightPx * metresPerPx

  /** Puts the bar under [target] (the focused window), or hides it when null or not shown. Call every frame. */
  fun tick(target: ScreenWindow?, head: Pose?) {
    val windowPose = target?.takeIf { it.visible }?.pose()
    if (target == null || windowPose == null) {
      show(false)
      return
    }
    val panel = entity ?: mount()
    gapM = KeyBarPlacement.gapM(head?.let { (windowPose.t - it.t).length() } ?: DEFAULT_DISTANCE_M)
    val barPose = KeyBarPlacement.pose(windowPose, target.heightM, heightM, gapM)
    panel.setComponent(Transform(barPose))
    pose = barPose
    if (window != target) {
      window = target
      Log.i(TAG, "key bar under screen ${target.screen?.value} (\"${target.state.title}\")")
    }
    show(true)
  }

  /** How much space the bar takes under [target], or 0 when it is not there. */
  fun occupiedBelow(target: ScreenWindow): Float =
    if (shown && window == target) KeyBarPlacement.occupiedM(heightM, gapM) else 0f

  /** How far along the ray it meets the shown bar, if it does. */
  fun hitDistance(origin: Vector3, direction: Vector3): Float? {
    val at = pose?.takeIf { shown } ?: return null
    return DockPlacement.hitDistance(at, origin, direction, widthPx * metresPerPx, heightM)
  }

  /** The ray reaches the bar before any terminal window, so window moves and edge resizes stand down. */
  fun blocksRay(origin: Vector3, direction: Vector3): Boolean {
    val distance = hitDistance(origin, direction) ?: return false
    val terminal = xmux.hitTest(origin, direction)?.point?.distanceTo(origin)
    return terminal == null || distance < terminal
  }

  fun close() {
    entity?.destroy()
    entity = null
    window = null
    pose = null
    shown = false
  }

  private fun show(value: Boolean) {
    if (shown == value) return
    entity?.setComponent(Visible(value))
    shown = value
    if (!value) {
      pose = null
      window?.let { Log.i(TAG, "key bar hidden (screen ${it.screen?.value})") }
      window = null
    }
  }

  private fun mount(): Entity {
    val panel = xmux.mountPanel(widthPx, heightPx, Placement.InFrontOfHead) { KeyBarPanel(::press) }
    // Keep its IsdkGrabbable, as the thinking panels do: ISDK's panel systems read it from every
    // panel. A grab does nothing lasting, since the bar is put back under its window every frame.
    panel.setComponent(Visible(false))
    entity = panel
    Log.i(TAG, "key bar ${widthPx}x$heightPx px: ${KeyBarKeys.keys.joinToString(" ") { it.label }}")
    return panel
  }

  private fun press(input: QuickInput, repeat: Boolean) {
    val key = input.xmuxKey()
    if (key == null) {
      Log.i(TAG, "key bar: ${input.label}")
      onKeyboard()
      return
    }
    val target = window ?: return
    val terminal = target.focusedTerminal
    val session = target.session
    if (terminal == null || session == null) {
      if (!repeat) Log.i(TAG, "key bar: ${input.label} not sent (screen ${target.screen?.value} has no focused pane)")
      return
    }
    session.terminal(terminal).keys(key)
    if (!repeat) Log.i(TAG, "key bar: ${input.label} to terminal ${terminal.value} on screen ${target.screen?.value}")
  }

  private companion object {
    const val TAG = "VrcodeKeyBar"
    /** Eleven keys about 3.7 cm square: an easy ray target at arm's length to a couple of metres. */
    const val WIDTH_M = 0.50f
    const val HEIGHT_M = 0.05f
    /** For the gap when the head is not tracked: the usual window distance. */
    const val DEFAULT_DISTANCE_M = 1.2f
  }
}

private val Face = Color(0xEB15161B)
private val Rim = Color(0x40FFFFFF)
private val KeyFace = Color(0xFF2A2C33)
private val KeyHover = Color(0xFF3A3D45)
private val KeyDown = Color(0xFF8A5CF6)
private val Caption = Color(0xFFB9BDC6)

@Composable
private fun KeyBarPanel(onKey: (QuickInput, Boolean) -> Unit) {
  val shape = RoundedCornerShape(12.dp)
  BoxWithConstraints(Modifier.fillMaxSize().clip(shape).background(Face).border(1.dp, Rim, shape)) {
    // Sized from the panel, whatever the terminal font makes a metre in pixels.
    val pad = maxHeight * 0.12f
    val gap = maxHeight * 0.08f
    Row(Modifier.fillMaxSize().padding(pad), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
      KeyBarKeys.groups.forEachIndexed { index, group ->
        if (index > 0) Box(Modifier.width(gap).fillMaxHeight(0.6f).background(Rim))
        for (input in group) BarKey(input, Modifier.weight(1f).fillMaxHeight(), onKey)
      }
    }
  }
}

/** One key: pressed state while held, hover from the ray, and repeat while held for [KeyBarKeys.repeats] keys. */
@Composable
private fun BarKey(input: QuickInput, modifier: Modifier, onKey: (QuickInput, Boolean) -> Unit) {
  val send by rememberUpdatedState(onKey)
  val repeats = KeyBarKeys.repeats(input)
  var pressed by remember { mutableStateOf(false) }
  val hover = remember { MutableInteractionSource() }
  val hovered by hover.collectIsHoveredAsState()
  val scope = rememberCoroutineScope()
  val shape = RoundedCornerShape(8.dp)
  BoxWithConstraints(
    modifier.clip(shape).background(if (pressed) KeyDown else if (hovered) KeyHover else KeyFace).hoverable(hover)
      .semantics { role = Role.Button; contentDescription = input.label; onClick { send(input, false); true } }
      .pointerInput(input) {
        awaitEachGesture {
          awaitFirstDown()
          pressed = true
          val repeating = if (!repeats) null else {
            send(input, false)
            scope.launch {
              delay(KeyBarKeys.REPEAT_DELAY_MS)
              while (true) {
                send(input, true)
                delay(KeyBarKeys.REPEAT_INTERVAL_MS)
              }
            }
          }
          val up = waitForUpOrCancellation()
          repeating?.cancel()
          pressed = false
          // A press dragged off the key does not send it, as with any button.
          if (!repeats && up != null) send(input, false)
        }
      },
    contentAlignment = Alignment.Center,
  ) {
    val caption = KeyBarKeys.caption(input)
    val iconSize = if (caption == null) minOf(maxWidth, maxHeight) * 0.55f else maxHeight * 0.42f
    val fontSize = with(LocalDensity.current) { (maxHeight * 0.24f).toSp() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
      PieIconImage(input.icon, Modifier.size(iconSize))
      if (caption != null) {
        BasicText(caption, maxLines = 1, softWrap = false,
          style = TextStyle(color = Caption, fontSize = fontSize, fontWeight = FontWeight.Medium))
      }
    }
  }
}
