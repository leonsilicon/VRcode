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
import dev.improve.vrcode.QuickInput
import dev.improve.vrcode.QuickInputRing
import dev.improve.vrcode.cmux.CmuxFeature
import dev.improve.vrcode.hands.FingerPinch
import dev.improve.vrcode.hands.HandAim
import dev.improve.vrcode.hands.HandMenuGesture
import dev.improve.vrcode.hands.IndexPinch
import dev.improve.vrcode.hands.MenuDwell
import dev.improve.vrcode.hands.MiddlePinch
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
 * (index still up). Move onto an icon: its label pill appears well out on its spoke, as far past the
 * icon as the icon is from the pinch. Hold the pinch on the icon for one second (the label fills)
 * to run it, or move on out to the label to run it at once. Reaching an icon alone runs nothing.
 * Releasing anywhere, the other finger's pinch, a fist or tracking loss cancels.
 *
 * - Left: Add terminal, in a new cmux window ([CmuxFeature.newTerminal]); Codex and Claude Code, in
 *   the focused terminal when its shell waits at an empty prompt, else in a new cmux window
 *   ([CmuxFeature.runCommand]); and Bring forward, which moves the focused window to straight in front of the user ([bringForward]).
 * - Right: Keyboard, then Enter, Esc and Ctrl-C for the focused pane, and pasting or clearing voice
 *   panes.
 * - Left **index** pinch (the runtime's app menu gesture, which used to toggle Settings): quick
 *   inputs for the focused pane, each in the direction Settings puts it ([quickInputs], eight
 *   compass directions, any of them empty). By default Esc sits top-left, Ctrl-C top-right, Tab
 *   bottom-left and Enter bottom-right. With every direction empty the pinch opens nothing. The
 *   runtime draws its own menu glyph for this pose, so it gets no hint.
 *
 * Only one menu is open at a time, and none opens while voice is listening. While the right palm
 * faces the user the right-click (palm-away middle pinch) stands down; see [busy].
 */
class HandMenusFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val voiceFeature: VoiceFeature,
  private val cmux: CmuxFeature,
  /** Moves the focused window to straight in front of the user. */
  private val bringForward: () -> Unit,
  /** The quick inputs ring as Settings has it now; read every frame, so changes apply at once. */
  private val quickInputs: () -> QuickInputRing,
  private val predictedTimeNs: () -> Long,
) {
  private val keyboard by lazy { KeyboardFeature(xmux, voiceFeature.voice) }

  private val leftHand = Hand(HandAim(left = true))
  private val rightHand = Hand(HandAim(left = false))

  private val left =
    HandMenu(
      leftHand,
      "palm menu",
      "New",
      listOf(
        PieItem("Add terminal", PieIcon.AddTerminal) { cmux.newTerminal() },
        PieItem("Codex", PieIcon.Codex) { cmux.runCommand("codex", name = "Codex") },
        PieItem("Claude Code", PieIcon.Claude) { cmux.runCommand("claude", name = "Claude Code") },
        PieItem("Bring forward", PieIcon.BringForward) { bringForward() },
      ),
    )

  /** The ring [quickRing] shows; replaced when Settings changes it. */
  private var shownQuickInputs = quickInputs()

  private val quickRing =
    HandMenu(
      leftHand,
      "quick inputs",
      "Input",
      quickItems(shownQuickInputs),
      finger = IndexPinch,
      hints = false,
      angles = quickAngles(shownQuickInputs),
    )

  private fun quickItems(ring: QuickInputRing): List<PieItem> =
    ring.placed.map { (_, input) -> PieItem(input.label, input.icon) { sendQuickInput(input) } }

  private fun quickAngles(ring: QuickInputRing): List<Float> = ring.placed.map { (slot, _) -> slot.degrees }

  private fun sendQuickInput(input: QuickInput) {
    val key = input.key ?: return keyboard.show(headPose())
    focusedKey(Key(key, if (input.ctrl) setOf(Modifier.Ctrl) else emptySet()))
  }

  private val right =
    HandMenu(
      rightHand,
      "palm menu",
      "Keys",
      listOf(
        PieItem("Keyboard", PieIcon.Keyboard) { toggleKeyboard() },
        PieItem("Enter", PieIcon.Enter) { focusedKey(Key("enter")) },
        PieItem("Esc", PieIcon.Escape) { focusedKey(Key("escape")) },
        PieItem("Ctrl-C", PieIcon.Interrupt) { focusedKey(Key("c", setOf(Modifier.Ctrl))) },
        PieItem("Paste voice", PieIcon.Paste) { voiceFeature.voice.selected?.let(voiceFeature::pasteIntoFocused) },
        PieItem("Clear voice", PieIcon.Clear) { voiceFeature.voice.notes.toList().forEach(voiceFeature.voice::remove) },
      ),
    )

  /** The right palm faces the user or its menu is open: palm-away right-click should stand down. */
  val busy: Boolean get() = right.gesture.open || right.gesture.palmFacing

  /** The right hand's joints from this frame (OpenXR VIEW space, XR_EXT_hand_tracking order). */
  val rightJoints: FloatArray? get() = rightHand.frame?.joints

  /** Dock and pie use one keyboard, including its live toggle state. */
  val keyboardShown: Boolean get() = keyboard.shown

  fun toggleKeyboard() = keyboard.toggle(headPose())

  private val menus = listOf(left, quickRing, right)

  fun onSceneReady() {
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    menus.forEach { it.mount(metresPerPx) }
    keyboard.onSceneReady()
    Log.i(TAG, "Palm quick actions ready: icon, then its label pill ${(LABEL_START_M * 100).roundToInt()}cm out, dwell=${MenuDwell.DURATION_MS}ms on the icon or reach the label, pinch release cancels; left index pinch = quick inputs $shownQuickInputs")
  }

  fun onSceneTick(enabled: Boolean) {
    keyboard.onSceneTick()
    val ring = quickInputs()
    if (ring != shownQuickInputs) {
      shownQuickInputs = ring
      quickRing.setItems(quickItems(ring), quickAngles(ring))
      Log.i(TAG, "left quick inputs changed: $ring")
    }
    val head = headPose()
    // Dictation owns the hands while it listens (either hand's fist).
    val blocked =
      when {
        !enabled -> "app paused, or Settings / Terminal actions open"
        head == null -> "no head pose"
        voiceFeature.voice.listening != null -> "voice listening"
        else -> null
      }
    val now = SystemClock.uptimeMillis()
    for (hand in listOf(leftHand, rightHand)) if (blocked == null && head != null) hand.poll(now) else hand.clear()
    for (menu in menus) menu.tick(head, blocked, others = menus - menu)
  }

  /** Releases the native trackers; call from the activity's teardown. */
  fun release() {
    menus.forEach { it.release() }
    leftHand.release()
    rightHand.release()
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

  /** One hand's tracker, polled once a frame for every menu on that hand. */
  private inner class Hand(private val aim: HandAim) {
    val side = if (aim.left) "left" else "right"
    /** This frame's state, or null when untracked or while the menus are blocked. */
    var frame: HandAim.Frame? = null
      private set
    private var lastTrackedMs = Long.MIN_VALUE / 2
    private var wasTracked = false

    fun poll(now: Long) {
      frame = aim.poll(activity.scene, predictedTimeNs())
      if (frame != null) lastTrackedMs = now
      val tracked = now - lastTrackedMs <= HandMenuGesture.TRACKING_GAP_MS
      if (tracked != wasTracked) Log.i(TAG, "$side hand ${if (tracked) "tracked" else "not tracked"} (palm menus)")
      wasTracked = tracked
    }

    fun clear() {
      frame = null
    }

    fun release() = aim.close()
  }

  /**
   * One ring on [hand], opened by a palm-facing pinch of [finger]: its gesture, selection and panels.
   * [name] labels its log lines; [hints] shows the fill-as-you-pinch hint above the fingertip.
   * [angles] places each item, in degrees clockwise from the top; evenly spaced unless given.
   */
  private inner class HandMenu(
    private val hand: Hand,
    name: String,
    title: String,
    private var items: List<PieItem>,
    private val finger: FingerPinch = MiddlePinch,
    private val hints: Boolean = true,
    angles: List<Float> = pieEvenAngles(items.size),
  ) {
    val gesture = HandMenuGesture(finger)
    private val side = hand.side
    private val label = "$side $name"
    private var selection = selectionFor(angles)
    private val dwell = MenuDwell()
    private val state = PieState(title, items, cancelFraction = 2f * DEAD_ZONE_M / SIZE_M, angles = angles)

    // An empty ring keeps a one-item selection it never opens (see the Opened event).
    private fun selectionFor(angles: List<Float>) =
      PieSelection(RadialMenu(angles.size.coerceAtLeast(1), deadZoneM = DEAD_ZONE_M, reachM = REACH_M, degrees = angles.ifEmpty { null }))

    /** Replaces the items, each at its [angles] entry, closing the ring if it is open. */
    fun setItems(items: List<PieItem>, angles: List<Float>) {
      if (gesture.open) Log.i(TAG, "$label closed: its items changed ${selectionTrace()}")
      gesture.cancel()
      close()
      this.items = items
      selection = selectionFor(angles)
      state.setItems(items, angles)
    }
    private val hintState = PieHintState()
    private var panel: Entity? = null
    private var hintPanel: Entity? = null
    private var hintPosition: Vector3? = null
    private var lastLogMs = 0L
    /** When the chosen item's confirmation hides the ring, or 0 while none is showing. */
    private var hideAtMs = 0L
    private var traceFlags = -1
    private var wasPinching = false
    /** The fullest the hint got since the fingers last parted, while no pinch started. */
    private var approachPeak = 0f

    fun mount(metresPerPx: Float) {
      val px = (SIZE_M / metresPerPx).roundToInt()
      panel =
        xmux.mountPanel(px, px, Placement.InFrontOfHead) {
          PieMenu(state)
        }.also { it.setComponent(Visible(false)) }
      if (!hints) return
      val hintPx = (HINT_SIZE_M / metresPerPx).roundToInt()
      hintPanel =
        xmux.mountPanel(hintPx, hintPx, Placement.InFrontOfHead) { PieHint(hintState) }.also {
          it.setComponent(Visible(false))
          it.setComponent(Hittable(MeshCollision.NoCollision))
        }
    }

    /** [blocked] says why the palm menus are off this frame, or null while they are allowed. */
    fun tick(head: Pose?, blocked: String?, others: List<HandMenu>) {
      if (blocked != null || head == null) {
        // The only close without a gesture event: say why, so on-device logs explain every close.
        if (gesture.open) Log.i(TAG, "$label closed: ${blocked ?: "no head pose"} ${selectionTrace()}")
        gesture.cancel()
        close()
        showHint(null, null)
        return
      }
      val frame = hand.frame
      val now = SystemClock.uptimeMillis()
      if (hideAtMs != 0L && now >= hideAtMs) close()
      val event = gesture.update(frame, now)
      log(frame)
      logIgnoredPinch(frame, event)
      logNearMiss(frame)
      val point = gesture.point?.let { viewToScene(head, it) }
      val otherOpen = others.any { it.gesture.open }
      val hintShown = hints && gesture.hint && !otherOpen && hideAtMs == 0L
      showHint(gesture.hintPoint?.takeIf { hintShown }?.let { viewToScene(head, it) }, head)
      when (event) {
        HandMenuGesture.Event.Opened -> when {
          otherOpen || point == null -> gesture.cancel()
          items.isEmpty() -> {
            Log.i(TAG, "$label: nothing to open, every direction is empty (Settings → Customization → Quick inputs)")
            gesture.cancel()
          }
          else -> open(point, head)
        }
        HandMenuGesture.Event.Released -> {
          Log.i(TAG, "$label: released (cancelled) ${selectionTrace()}")
          close()
        }
        HandMenuGesture.Event.Cancelled -> {
          Log.i(TAG, "$label cancelled: ${gesture.cancelReason} ${selectionTrace()}")
          close()
        }
        HandMenuGesture.Event.None -> {
          if (gesture.open) trace(frame)
          if (gesture.open) {
            // Never finish a dwell using a stale point or during the release debounce window. Those
            // frames (a dropped frame, or the runtime's pinch flickering while the hand moves) pause
            // the dwell rather than restart it; a real release or tracking loss closes the menu.
            val canHover = frame != null && gesture.pinchReading && point != null
            if (canHover) selection.update(point!!)
            val rayRun = state.rayRun
            val rayItem = rayRun ?: state.rayHovered
            val item = rayItem ?: selection.reached
            // On the hovered item's label pill (the pinch moved out past its start along the spoke, or
            // the ray is on it): run it now. Short of it (on the icon) the second counts.
            val onLabel = canHover && item != null &&
              (rayRun == item || (rayItem == null && selection.along(item) >= LABEL_START_M))
            val completed = if (onLabel) dwell.finish(item!!) ?: dwell.update(item, now, confirmed = canHover)
              else dwell.update(item, now, confirmed = canHover)
            state.highlighted = dwell.hovered
            state.progress = dwell.progress
            completed?.let { reached ->
              Log.i(TAG, "$label: ${if (onLabel) "reached label" else "dwell complete"} ${selectionTrace()}")
              gesture.cancel() // the pinch is still held: the next menu needs a fresh one
              confirm(reached)
              run(reached)
            }
          }
        }
      }
    }

    fun release() {
      close()
      showHint(null, null)
    }

    private fun open(at: Vector3, head: Pose) {
      hideAtMs = 0L
      traceFlags = -1
      selection.open(at, head)
      dwell.reset()
      state.highlighted = null
      state.chosen = null
      state.rayHovered = null
      state.rayRun = null
      state.progress = 0f
      // Just behind the pinch point, so the hand stays in front of the ring.
      val position = at + selection.forward * BEHIND_HAND_M
      panel?.setComponent(Transform(Pose(position, Quaternion.lookRotation(position - head.t))))
      panel?.setComponent(Visible(true))
      // flags show whether the runtime's PINCHING signal or the strength alone started it.
      val f = hand.frame
      Log.i(
        TAG,
        "$label opened (${gesture.source} ${finger.name} pinch, palm ${gesture.facingDegrees.roundToInt()}°, " +
          "flags=${f?.flags} middle=${f?.middleStrength?.let { "%.2f".format(it) }} index=${f?.indexStrength?.let { "%.2f".format(it) }})",
      )
    }

    private fun close() {
      val confirming = hideAtMs != 0L
      hideAtMs = 0L
      if (selection.center == null && !confirming) return
      selection.close()
      dwell.reset()
      state.highlighted = null
      state.chosen = null
      state.rayHovered = null
      state.rayRun = null
      state.progress = 0f
      panel?.setComponent(Visible(false))
    }

    /**
     * Keeps the ring up briefly with [index] marked as chosen, so running an item reads as a
     * selection rather than the menu dropping back to its hint.
     */
    private fun confirm(index: Int) {
      selection.close()
      state.highlighted = index
      state.chosen = index
      state.progress = 1f
      hideAtMs = SystemClock.uptimeMillis() + CONFIRM_MS
    }

    private fun selectionTrace(): String =
      "(moved ${"%.1f".format(selection.distanceM * 100)}cm, item=${selection.highlighted?.let { items[it].label }})"

    /** While the ring is open: each change in the runtime's pinch flags, to diagnose closes. */
    private fun trace(frame: HandAim.Frame?) {
      if (frame == null || frame.flags == traceFlags) return
      traceFlags = frame.flags
      Log.d(
        TAG,
        "$label held: flags=${frame.flags} middle=${"%.2f".format(frame.middleStrength)} " +
          "index=${"%.2f".format(frame.indexStrength)} ${selectionTrace()}",
      )
    }

    /** The runtime started a pinch of [finger] with the palm facing, but no menu opened: say why. */
    private fun logIgnoredPinch(frame: HandAim.Frame?, event: HandMenuGesture.Event) {
      val pinching = frame?.has(finger.flag) == true
      val rose = pinching && !wasPinching
      wasPinching = pinching
      if (!rose || frame == null || event == HandMenuGesture.Event.Opened || gesture.open || !gesture.palmFacing) return
      Log.d(
        TAG,
        "$side ${finger.name} pinch did not open the $label: palm ${gesture.facingDegrees.roundToInt()}° " +
          "flags=${frame.flags} middle=${"%.2f".format(frame.middleStrength)} index=${"%.2f".format(frame.indexStrength)} " +
          "fist=${gesture.fist} stillHeld=${gesture.down}",
      )
    }

    /**
     * A palm-facing pinch that came close but never started (the hint filled most of the way, then
     * the fingers parted): log how close, to tune [FingerPinch.MIDDLE_START_STRENGTH].
     */
    private fun logNearMiss(frame: HandAim.Frame?) {
      if (frame == null) return
      if (gesture.open || gesture.down) {
        approachPeak = 0f
        return
      }
      if (gesture.hint) approachPeak = maxOf(approachPeak, gesture.progress)
      if (gesture.progress > NEAR_MISS_RESET) return
      if (approachPeak >= NEAR_MISS) {
        Log.i(
          TAG,
          "$label: ${finger.name} pinch near miss, hint peaked at ${(approachPeak * 100).roundToInt()}% " +
            "(source=${gesture.source}, starts at ${finger.startStrength ?: "the PINCHING signal"})",
        )
      }
      approachPeak = 0f
    }

    private fun run(index: Int) {
      val item = items.getOrNull(index) ?: return
      Log.i(TAG, "$label: ${item.label}")
      item.action()
    }

    /** Shows the fingertip hint at [at] (scene space), or hides it when null. */
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
        "$label: ${finger.name} hand facing=${gesture.facingDegrees.roundToInt()}° flags=${frame.flags} " +
          "middle=${"%.2f".format(frame.middleStrength)} index=${"%.2f".format(frame.indexStrength)} " +
          "source=${gesture.source} hint=${gesture.hint} open=${gesture.open}",
      )
    }
  }

  private companion object {
    const val TAG = "vrcode"
    /**
     * The menu panel's side. Icons sit [PIE_ITEM_RADIUS] of it out (6.7 cm); label pills begin
     * [PIE_LABEL_START] of it out (13.4 cm) in every direction.
     */
    const val SIZE_M = 0.42f
    /** Matches the item ring drawn by [PieMenu]: the hand is "over" an item about 3/4 of the way out. */
    const val REACH_M = SIZE_M * PIE_ITEM_RADIUS * 0.75f
    /** How far out along the hovered item's spoke the hand reaches its label pill, which runs it at once. */
    const val LABEL_START_M = SIZE_M * PIE_LABEL_START
    const val DEAD_ZONE_M = 0.035f
    const val BEHIND_HAND_M = 0.04f
    /** About the size of the runtime's own menu glyph, and a fingertip's width. */
    const val HINT_SIZE_M = 0.016f
    const val HINT_SMOOTHING = 0.6f
    /** How long a chosen item stays marked before the ring hides. */
    const val CONFIRM_MS = 250L
    /** A hint that fills this far and then empties back below [NEAR_MISS_RESET] is a near miss. */
    const val NEAR_MISS = 0.6f
    const val NEAR_MISS_RESET = 0.3f
  }
}
