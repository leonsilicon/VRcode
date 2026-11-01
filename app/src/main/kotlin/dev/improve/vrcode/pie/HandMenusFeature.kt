package dev.improve.vrcode.pie

import android.os.SystemClock
import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Query
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.cmux.CmuxFeature
import dev.improve.vrcode.hands.HandAim
import dev.improve.vrcode.hands.HandMenuGesture
import dev.improve.vrcode.hands.PieSelection
import dev.improve.vrcode.hands.RadialMenu
import dev.improve.vrcode.hands.viewToScene
import dev.improve.vrcode.keyboard.KeyboardFeature
import dev.improve.vrcode.voice.VoiceFeature
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.roundToInt

/**
 * Palm menus on both hands: turn a palm toward yourself and touch the middle finger to the thumb
 * (index still up). A ring of items appears around the pinch; move the hand onto an item to run it,
 * or toward it and release. Releasing in the centre, an index pinch, a fist or tracking loss
 * cancels.
 *
 * - Left: New terminal, Codex, Claude Code, each in a new cmux window ([CmuxFeature.newTerminal]).
 * - Right: Keyboard, then Enter, Esc and Ctrl-C for the focused pane, and pasting or clearing voice
 *   panes.
 *
 * Only one menu is open at a time, and neither opens while voice is listening. While the right palm
 * faces the user the right-click (palm-away middle pinch) stands down; see [busy].
 */
class HandMenusFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val voiceFeature: VoiceFeature,
  private val cmux: CmuxFeature,
  private val predictedTimeNs: () -> Long,
) {
  private val keyboard by lazy { KeyboardFeature(xmux, voiceFeature.voice) }

  private val left =
    HandMenu(
      HandAim(left = true),
      "New",
      listOf(
        PieItem("Terminal") { cmux.newTerminal() },
        PieItem("Codex") { cmux.newTerminal("codex", name = "Codex") },
        PieItem("Claude Code") { cmux.newTerminal("claude", name = "Claude Code") },
      ),
    )

  private val right =
    HandMenu(
      HandAim(left = false),
      "Keys",
      listOf(
        PieItem("Keyboard") { toggleKeyboard() },
        PieItem("Enter") { focusedKey(Key("enter")) },
        PieItem("Esc") { focusedKey(Key("escape")) },
        PieItem("Ctrl-C") { focusedKey(Key("c", setOf(Modifier.Ctrl))) },
        PieItem("Paste voice") { voiceFeature.voice.selected?.let(voiceFeature::pasteIntoFocused) },
        PieItem("Clear voice") { voiceFeature.voice.notes.toList().forEach(voiceFeature.voice::remove) },
      ),
    )

  /** The right palm faces the user or its menu is open: palm-away right-click should stand down. */
  val busy: Boolean get() = right.gesture.open || right.gesture.palmFacing

  /** The right hand's joints from this frame (OpenXR VIEW space, XR_EXT_hand_tracking order). */
  val rightJoints: FloatArray? get() = right.joints

  /** Dock and pie use one keyboard, including its live toggle state. */
  val keyboardShown: Boolean get() = keyboard.shown

  fun toggleKeyboard() = keyboard.toggle(headPose())

  fun onSceneReady() {
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    left.mount(metresPerPx)
    right.mount(metresPerPx)
    keyboard.onSceneReady()
  }

  fun onSceneTick(enabled: Boolean) {
    keyboard.onSceneTick()
    val head = headPose()
    // Dictation owns the hands while it listens (either hand's fist).
    val allowed = enabled && head != null && voiceFeature.voice.listening == null
    left.tick(head, allowed, other = right)
    right.tick(head, allowed, other = left)
  }

  /** Releases the native trackers; call from the activity's teardown. */
  fun release() {
    left.release()
    right.release()
  }

  private fun focusedKey(key: Key) {
    xmux.focusedTerminal()?.keys(key)
  }

  private fun headPose(): Pose? =
    Query.where { has(AvatarAttachment.id) }
      .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }
      .eval()
      .firstOrNull()
      ?.tryGetComponent<Transform>()
      ?.transform

  /** One hand's tracker, gesture, selection and panels. */
  private inner class HandMenu(private val aim: HandAim, title: String, private val items: List<PieItem>) {
    val gesture = HandMenuGesture()
    private val side = if (aim.left) "left" else "right"
    private val selection = PieSelection(RadialMenu(items.size, deadZoneM = DEAD_ZONE_M, reachM = REACH_M))
    private val state = PieState(title, items, cancelFraction = 2f * DEAD_ZONE_M / SIZE_M)
    private val hintState = PieHintState()
    private var panel: Entity? = null
    private var hintPanel: Entity? = null
    private var hintPosition: Vector3? = null
    private var lastFrame: HandAim.Frame? = null
    private var lastLogMs = 0L
    private var lastTrackedMs = Long.MIN_VALUE / 2
    private var wasTracked = false
    /** When the chosen item's confirmation hides the ring, or 0 while none is showing. */
    private var hideAtMs = 0L
    private var traceFlags = -1
    private var wasMiddlePinching = false

    val joints: FloatArray? get() = lastFrame?.joints

    fun mount(metresPerPx: Float) {
      val px = (SIZE_M / metresPerPx).roundToInt()
      panel =
        xmux.mountPanel(px, px, Placement.InFrontOfHead) {
          PieMenu(state) { i ->
            gesture.cancel()
            confirm(i)
            run(i)
          }
        }.also { it.setComponent(Visible(false)) }
      val hintPx = (HINT_SIZE_M / metresPerPx).roundToInt()
      hintPanel =
        xmux.mountPanel(hintPx, hintPx, Placement.InFrontOfHead) { PieHint(hintState) }.also {
          it.setComponent(Visible(false))
          it.setComponent(Hittable(MeshCollision.NoCollision))
        }
    }

    fun tick(head: Pose?, allowed: Boolean, other: HandMenu) {
      if (!allowed || head == null) {
        lastFrame = null
        gesture.cancel()
        close()
        showHint(null, null)
        return
      }
      val frame = aim.poll(activity.scene, predictedTimeNs())
      val now = SystemClock.uptimeMillis()
      if (frame != null) lastTrackedMs = now
      val tracked = now - lastTrackedMs <= HandMenuGesture.TRACKING_GAP_MS
      if (tracked != wasTracked) Log.i(TAG, "$side hand ${if (tracked) "tracked" else "not tracked"} (palm menu)")
      wasTracked = tracked
      lastFrame = frame
      if (hideAtMs != 0L && now >= hideAtMs) close()
      val event = gesture.update(frame, now)
      log(frame)
      logIgnoredPinch(frame, event)
      val point = gesture.point?.let { viewToScene(head, it) }
      val otherOpen = other.gesture.open
      val hintShown = gesture.hint && !otherOpen && hideAtMs == 0L
      showHint(gesture.hintPoint?.takeIf { hintShown }?.let { viewToScene(head, it) }, head)
      when (event) {
        HandMenuGesture.Event.Opened -> if (otherOpen || point == null) gesture.cancel() else open(point, head)
        HandMenuGesture.Event.Released -> {
          val picked = selection.highlighted
          if (picked != null) {
            Log.i(TAG, "$side palm menu: released toward an item ${selectionTrace()}")
            confirm(picked)
            run(picked)
          } else {
            Log.i(TAG, "$side palm menu: released in the centre (cancelled) ${selectionTrace()}")
            close()
          }
        }
        HandMenuGesture.Event.Cancelled -> {
          Log.i(TAG, "$side palm menu cancelled: ${gesture.cancelReason} ${selectionTrace()}")
          close()
        }
        HandMenuGesture.Event.None ->
          if (gesture.open && point != null) {
            selection.update(point)
            state.highlighted = selection.highlighted
            trace(frame)
            selection.reached?.let { reached ->
              Log.i(TAG, "$side palm menu: reached an item ${selectionTrace()}")
              gesture.cancel() // the pinch is still held: the next menu needs a fresh one
              confirm(reached)
              run(reached)
            }
          }
      }
    }

    fun release() {
      close()
      showHint(null, null)
      aim.close()
    }

    private fun open(at: Vector3, head: Pose) {
      hideAtMs = 0L
      traceFlags = -1
      selection.open(at, head)
      state.highlighted = null
      state.chosen = null
      // Just behind the pinch point, so the hand stays in front of the ring.
      val position = at + selection.forward * BEHIND_HAND_M
      panel?.setComponent(Transform(Pose(position, Quaternion.lookRotation(position - head.t))))
      panel?.setComponent(Visible(true))
      Log.i(TAG, "$side palm menu opened (${gesture.source} pinch, palm ${gesture.facingDegrees.roundToInt()}°)")
    }

    private fun close() {
      if (selection.center == null) return
      selection.close()
      state.highlighted = null
      panel?.setComponent(Visible(false))
    }

    private fun run(index: Int) {
      val item = items.getOrNull(index) ?: return
      Log.i(TAG, "$side palm menu: ${item.label}")
      item.action()
    }

    /** Shows the middle-finger hint at [at] (scene space), or hides it when null. */
    private fun showHint(at: Vector3?, head: Pose?) {
      val hint = hintPanel ?: return
      if (at == null || head == null) {
        if (hintPosition != null) hint.setComponent(Visible(false))
        hintPosition = null
        return
      }
      val previous = hintPosition
      val position = if (previous == null) at else previous.lerp(at, HINT_SMOOTHING)
      if (previous == null) hint.setComponent(Visible(true))
      hintPosition = position
      hintState.progress = gesture.progress
      hint.setComponent(Transform(Pose(position, Quaternion.lookRotation(position - head.t))))
    }

    /** Once a second while tracked: what the gesture sees, for on-device tuning. */
    private fun log(frame: HandAim.Frame?) {
      val now = SystemClock.uptimeMillis()
      if (frame == null || now - lastLogMs < 1000L) return
      lastLogMs = now
      Log.d(
        TAG,
        "$side hand facing=${gesture.facingDegrees.roundToInt()}° flags=${frame.flags} " +
          "middle=${"%.2f".format(frame.middleStrength)} index=${"%.2f".format(frame.indexStrength)} " +
          "source=${gesture.source} hint=${gesture.hint} open=${gesture.open}",
      )
    }
  }

  private companion object {
    const val TAG = "vrcode"
    const val SIZE_M = 0.26f
    /** Matches the item ring drawn by [PieMenu]: the hand is "over" an item about 3/4 of the way out. */
    const val REACH_M = SIZE_M * PIE_ITEM_RADIUS * 0.75f
    const val DEAD_ZONE_M = 0.035f
    const val BEHIND_HAND_M = 0.04f
    const val HINT_SIZE_M = 0.04f
    const val HINT_SMOOTHING = 0.6f
  }
}
