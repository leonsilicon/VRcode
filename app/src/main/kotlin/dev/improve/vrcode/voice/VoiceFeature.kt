package dev.improve.vrcode.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.FocusKeeper
import dev.improve.vrcode.VoiceProp
import dev.improve.vrcode.AiProvider
import dev.improve.vrcode.VoiceMic
import dev.improve.vrcode.dock.DockPlacement
import dev.improve.vrcode.hands.HandInput
import dev.improve.vrcode.hands.RightFistInput
import dev.improve.vrcode.speech.SpeechLink
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope

/**
 * Voice mode end to end, for any activity that hosts xmux. Two fists, two modes:
 * - right fist: plain dictation, typed exactly as said;
 * - left fist: speak to the AI, which turns what was said into the input for the focused pane (a
 *   shell command, or a prompt for a coding agent) using that pane's screen.
 * Like dictation on a Mac, voice goes to the focused pane ([focus] keeps one focused whenever a
 * terminal window is open): when the fist opens, the pane focused at that moment is the target, and
 * the text is typed there as soon as the Mac finalizes it (dictation) or the AI answers. Nothing
 * presses Enter unless that mic's Enter-after toggle is on (each mic has its own, remembered; both
 * start off, so AI text waits at the prompt to review).
 * The live voice pane floats just above the mic in the fist while it is closed; the other hand
 * presses its buttons: the Enter-after toggle in the corner nearest that hand, Cancel and Clear at
 * the far end. It hides when the fist opens,
 * unless the text could not be typed (no terminal, the AI failed): then it stays, staged, for a
 * left pinch on a terminal pane (or its Paste button) to paste it.
 * The host calls [onSceneReady], [onSceneTick] and [close], and [connectSpeech] with the Mac's
 * address; [predictedTimeNs] is the frame's predicted display time for OpenXR hand joints.
 */
class VoiceFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  scope: CoroutineScope,
  private val focus: FocusKeeper,
  private val overlayHitDistance: (Vector3, Vector3) -> Float? = { _, _ -> null },
  /** The dock's current (eased) pose, so the voice pane moves with it; null uses its target pose. */
  private val dockPose: () -> Pose? = { null },
  predictedTimeNs: () -> Long,
) {
  private val hands = HandInput(activity, predictedTimeNs)
  private val rightFist = RightFistInput(activity, predictedTimeNs)
  private val link: SpeechLink = SpeechLink(scope) { voice.onSpeechEvent(it) }
  val voice: VoiceController =
    VoiceController(link, aiContext = ::paneContext, deliver = ::deliver, aiProvider = { aiProvider.storageValue }) {
      activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

  /** The pane a voice pane types into: the one focused when its fist opened. */
  private class Target(val window: ScreenWindow, val terminal: TerminalId)

  /** Targets of voice panes still being finalized or answered (state, for the header badges). */
  private val targets = mutableStateMapOf<VoiceNote, Target>()

  /** The hand whose fist started the live dictation; only that hand opening ends it. */
  private enum class VoiceHand { Left, Right }

  /** State, so the live pane mirrors its buttons when the other fist takes over a pane. */
  private var voiceHand by mutableStateOf<VoiceHand?>(null)

  /** Each mic's Enter-after toggle survives restarts (and reinstalls). */
  private val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  init {
    voice.enterAfterDictation = prefs.getBoolean(PREF_ENTER_DICTATION, false)
    voice.enterAfterAi = prefs.getBoolean(PREF_ENTER_AI, false)
  }

  /** Eases the live pane after the mic in the fist (see [MicPanePlacement]). */
  private val paneFollow = PaneFollow()
  private var lastTickMs = 0L
  private var frameDtS = 0f

  /** A reusable voice pane panel; panels are pooled because each mount registers a new panel. */
  private class Slot(val entity: Entity) {
    var note by mutableStateOf<VoiceNote?>(null)
    var pose: Pose? = null
    var shown = false
      private set

    fun show(value: Boolean) {
      if (shown == value) return
      entity.setComponent(Visible(value))
      shown = value
      Log.i(TAG, "voice pane ${note?.id} ${if (value) "shown" else "hidden"}")
    }
  }

  private val slots = mutableListOf<Slot>()
  private var marker: Entity? = null
  private var markerShown = false
  private var propEntity: Entity? = null
  private var propShown = false
  /** The pane created or resumed by this fist; retained text from earlier fists stays hidden. */
  private var activeNote: VoiceNote? = null
  /** Debug builds: the prop floats in front of the head, to check the model renders. */
  private var propPreview = false
  /** Preview the mic being customized on its own hand without starting capture. */
  private var settingsPreview: VoiceMic? = null
  private var textProp = VoiceProp.Microphone
  private var aiProp = VoiceProp.NewsMicrophone
  private var selectedProp = VoiceProp.NewsMicrophone
  /** The AI mic's model provider; the news microphone's flag shows its logo. */
  private var aiProvider = AiProvider.Claude
  private var selectedAsset = VoiceProp.Microphone.assetPath(aiProvider)
  /** Prop models in the APK: a missing one would abort the app in the glTF loader. */
  private val bundledProps: Set<String> by lazy { activity.assets.list("voice-props")?.toSet().orEmpty() }

  /** [prop]'s model for the current provider, or the always-bundled classic microphone if it is missing. */
  private fun loadableAsset(prop: VoiceProp): String {
    val wanted = prop.assetPath(aiProvider)
    return if (wanted.substringAfterLast('/') in bundledProps) wanted else VoiceProp.Microphone.assetPath(aiProvider)
  }
  private var propMic: VoiceMic? = null
  private var debugMic = VoiceMic.Ai
  private var sceneReady = false

  fun connectSpeech(host: String, port: Int = DEFAULT_PORT) {
    if (link.address == "$host:$port") return
    Log.i(TAG, "speech service at $host:$port")
    link.connect(host, port)
  }

  fun disconnectSpeech() {
    voice.onSpeechEvent(dev.improve.vrcode.speech.SpeechEvent.Disconnected("Connection changed"))
    link.close()
  }

  fun onSceneReady() {
    sceneReady = true
    marker =
      xmux.mountPanel(MARKER_PX, MARKER_PX, Placement.InFrontOfHead) { MicMarker(voice) }.also {
        it.setComponent(Visible(false))
        it.setComponent(Hittable(MeshCollision.NoCollision))
      }
    selectedAsset = loadableAsset(selectedProp)
    propEntity =
      Entity.create(listOf(Mesh(Uri.parse(selectedAsset), MeshCollision.NoCollision), Transform(), Visible(false)))
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && !debugRegistered) {
      activity.registerReceiver(debugReceiver, IntentFilter(DEBUG_ACTION), Context.RECEIVER_EXPORTED)
      debugRegistered = true
    }
  }

  /**
   * Debug builds only: drives voice mode without hands, e.g.
   * `adb shell am broadcast -a dev.improve.vrcode.VOICE --es cmd start` (start, ai, stop, cancel,
   * clear, delete, paste; `enter` presses the Enter-after toggle, `--es mic ai` for the AI mic's;
   * `prop` toggles a preview of the voice prop in front of the head).
   */
  private val debugReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
          "start" -> startVoiceMode(VoiceHand.Right) // plain dictation
          "ai" -> startVoiceMode(VoiceHand.Left) // speak to the AI
          "stop" -> stopVoiceMode()
          "cancel" -> voice.cancelListening() // the pane's Cancel button
          "clear" -> voice.clearListening() // the pane's Clear button
          "delete" -> voice.selected?.let(voice::remove)
          "paste", "insert" -> voice.selected?.let(::pasteIntoFocused)
          "enter" -> toggleEnterAfter(if (intent.getStringExtra("mic") == "ai") VoiceNote.Mode.Ai else VoiceNote.Mode.Passthrough)
          "prop" -> {
            debugMic = if (intent.getStringExtra("mic") == "text") VoiceMic.Text else VoiceMic.Ai
            propPreview = !propPreview
          }
        }
        Log.i(TAG, "debug voice ${intent.getStringExtra("cmd")}: panes=${voice.notes.map { "${it.id}:${it.mode}:${it.phase}:\"${it.output}\"" }}")
      }
    }
  private var debugRegistered = false

  /** The preference also applies immediately if voice mode is already active. */
  fun setVoiceProps(text: VoiceProp, ai: VoiceProp, provider: AiProvider) {
    textProp = text
    aiProp = ai
    aiProvider = provider
    Log.i(TAG, "voice props: text=${text.storageValue} right hand; AI=${ai.storageValue} left hand; AI provider=${provider.storageValue}")
  }

  fun onSceneTick(allowGestures: Boolean = true, previewMic: VoiceMic? = null) {
    if (settingsPreview != previewMic) {
      settingsPreview = previewMic
      Log.i(TAG, "voice prop settings preview ${previewMic?.let { "${it.label} on ${it.hand} hand" } ?: "disabled"}")
    }
    hands.quickRelease = voice.quiet // nothing said yet: an opening hand ends voice mode at once
    rightFist.quickRelease = voice.quiet
    val now = SystemClock.uptimeMillis()
    frameDtS = if (lastTickMs == 0L) 0f else (now - lastTickMs) / 1000f
    lastTickMs = now
    hands.tick(now)
    rightFist.tick(now)
    if (allowGestures && voiceHand == null) {
      if (hands.leftFistStarted) startVoiceMode(VoiceHand.Left) else if (rightFist.started) startVoiceMode(VoiceHand.Right)
    }
    if ((hands.leftFistEnded && voiceHand == VoiceHand.Left) || (rightFist.ended && voiceHand == VoiceHand.Right)) {
      stopVoiceMode()
    }
    // Resolve the prop first: the pane and badge use its visibility in this same frame.
    placeProp()
    syncPanes()
    placeMarker()
    if (allowGestures && hands.leftPinched) onLeftPinch()
  }

  private fun startVoiceMode(hand: VoiceHand) {
    voiceHand = hand
    paneFollow.reset() // the new pane appears at this mic, not easing over from the last one
    voice.startListening(if (hand == VoiceHand.Left) VoiceNote.Mode.Ai else VoiceNote.Mode.Passthrough)
    activeNote = voice.selected
  }

  /** The fist opened: what was said goes to the pane focused right now, once it is ready. */
  private fun stopVoiceMode() {
    voiceHand = null
    voice.listening?.let { note -> focusedTarget()?.let { targets[note] = it } }
    voice.stopListening()
  }

  private fun focusedTarget(): Target? {
    val window = focus.focused ?: return null
    return window.focusedTerminal?.let { Target(window, it) }
  }

  /**
   * [target]'s terminal, or the focused one when no target was captured. A target whose window
   * closed meanwhile gives null, so the text stays staged rather than landing somewhere unexpected.
   */
  private fun handleFor(target: Target?): Pair<TerminalHandle, ScreenWindow?>? {
    if (target != null) {
      if (target.window !in xmux.windows) return null
      return target.window.session?.terminal(target.terminal)?.let { it to target.window }
    }
    val window = focus.focused
    return xmux.focusedTerminal()?.let { it to window }
  }

  /** The target pane's visible text (bottom [CONTEXT_LINES] rows) and title, for the AI. */
  private fun paneContext(note: VoiceNote): AiContext? {
    val (handle, window) = handleFor(targets[note]) ?: return null
    val grid = handle.grid.value
    val lines = (0 until grid.rows).map { grid.rowText(it) }.dropLastWhile { it.isBlank() }.takeLast(CONTEXT_LINES)
    return AiContext(lines.joinToString("\n"), window?.state?.title ?: "")
  }

  /**
   * Types a ready voice pane into its target (no review step, like dictation on a Mac), then Enter
   * if its mic's toggle is on. The AI mic's is off by default, so its command or prompt waits at the
   * prompt to be checked.
   */
  private fun deliver(note: VoiceNote): Boolean {
    val (handle, window) = handleFor(targets.remove(note)) ?: return false
    val text = voice.take(note) ?: return false
    type(handle, text, enter = voice.enterAfter(note.mode))
    Log.i(TAG, "voice: typed pane ${note.id} (${note.mode}, ${text.length} chars) into screen ${window?.screen?.value} \"${window?.state?.title}\"")
    return true
  }

  /** How [screen]'s header shows voice: focused, listening into it, or about to type there. */
  private fun targetOf(screen: ScreenId): VoiceTarget? {
    val focused = focus.focusedScreen == screen
    val live = voice.listening
    return when {
      focused && live != null -> if (live.mode == VoiceNote.Mode.Ai) VoiceTarget.TalkingToAi else VoiceTarget.Dictating
      targets.any { (note, target) -> target.window.screen == screen && note in voice.notes && note.phase != VoiceNote.Phase.Ready } ->
        VoiceTarget.Typing
      focused -> VoiceTarget.Focused
      else -> null
    }
  }

  /** For a terminal window's header: marks the focused window and what voice is doing there. */
  @Composable
  fun TargetBadge(screen: ScreenId) {
    VoiceTargetBadge(targetOf(screen)) { voice.level }
  }

  fun close() {
    if (debugRegistered) activity.unregisterReceiver(debugReceiver)
    debugRegistered = false
    voice.shutdown()
    link.close()
    hands.close()
    rightFist.close()
    propEntity?.destroy()
    propEntity = null
  }

  /**
   * Left index pinch: on a voice pane it picks that pane; on a terminal pane it pastes the selected
   * voice pane there. A pinch on a window header does nothing here (the header drags the window).
   */
  private fun onLeftPinch() {
    val ray = hands.leftPose ?: return
    val direction = ray.forward().normalize()
    val pane = nearestPane(ray.t, direction)
    val hit = xmux.hitTest(ray.t, direction)
    val overlay = overlayHitDistance(ray.t, direction)
    if (overlay != null && overlay <= minOf(pane?.second ?: Float.MAX_VALUE, hit?.point?.distanceTo(ray.t) ?: Float.MAX_VALUE)) return
    if (pane != null && (hit == null || pane.second <= (hit.point?.distanceTo(ray.t) ?: Float.MAX_VALUE))) {
      voice.select(pane.first)
      return
    }
    if (hit == null || hit.onHeader) return
    val terminal = hit.terminal ?: return
    val session = hit.window.session ?: return
    val note = voice.selected ?: return
    val text = voice.take(note) ?: return
    xmux.focus(hit.window, hit.pane)
    type(session.terminal(terminal), text, enter = voice.enterAfter(note.mode))
    Log.i(TAG, "voice: pasted pane ${note.id} (${text.length} chars) into terminal ${terminal.value}")
  }

  /** Pastes [note] into the focused terminal pane and removes it. */
  fun pasteIntoFocused(note: VoiceNote) {
    val handle = xmux.focusedTerminal() ?: return
    val text = voice.take(note) ?: return
    type(handle, text, enter = voice.enterAfter(note.mode))
    Log.i(TAG, "voice: pasted pane ${note.id} (${text.length} chars) into the focused terminal")
  }

  private fun type(handle: TerminalHandle, text: String, enter: Boolean) {
    handle.write(text)
    if (enter) handle.keys(Key("enter"))
  }

  /** The pane's Enter-after button: flips [mode]'s mic toggle and remembers it. */
  private fun toggleEnterAfter(mode: VoiceNote.Mode) {
    val on = !voice.enterAfter(mode)
    voice.setEnterAfter(mode, on)
    prefs.edit().putBoolean(if (mode == VoiceNote.Mode.Ai) PREF_ENTER_AI else PREF_ENTER_DICTATION, on).apply()
    Log.i(TAG, "voice: Enter after ${if (mode == VoiceNote.Mode.Ai) "AI" else "dictation"} ${if (on) "on" else "off"}")
  }

  /**
   * Whether the hand free to press [note]'s buttons is the left: the mic is in the right fist (or,
   * with no fist closed, the pane is a dictation, the right mic's).
   */
  private fun freeHandLeft(note: VoiceNote): Boolean =
    (voiceHand ?: if (note.mode == VoiceNote.Mode.Ai) VoiceHand.Left else VoiceHand.Right) == VoiceHand.Right

  /**
   * Only the current pane is visible, floating just above the mic in the fist (see [paneAnchor]):
   * while its fist is closed, and afterwards only if it could not be typed (it then carries a
   * notice). Finishing and the AI's thinking show on the target window's header instead.
   */
  private fun syncPanes() {
    if (!sceneReady) return
    targets.keys.filter { it !in voice.notes }.forEach { targets.remove(it) }
    for (slot in slots) {
      val note = slot.note ?: continue
      if (note !in voice.notes) {
        slot.show(false)
        slot.note = null
        slot.pose = null
      }
    }
    for (note in voice.notes) {
      if (slots.any { it.note == note }) continue
      val slot = slots.firstOrNull { it.note == null } ?: newSlot()
      slot.note = note
      slot.pose = null
    }
    val head = headPose()
    for (slot in slots) {
      val note = slot.note ?: continue
      val live = voiceHand != null || voice.listening == note || note.notice != null
      if (note != activeNote || !live || head == null) {
        slot.show(false)
        continue
      }
      val pose = paneAnchor(head)
      slot.pose = pose
      slot.entity.setComponent(Transform(pose))
      slot.show(true)
    }
  }

  /**
   * Where the live pane floats: above the mic in the fist holding it, eased by [paneFollow], so
   * the free hand can reach its buttons. When that hand is not tracked, or the fist opened and the
   * pane stayed (staged), it holds where it was while that is still in front of the eyes; without
   * such a spot it sits just above the dock.
   */
  private fun paneAnchor(head: Pose): Pose {
    val grip =
      when (voiceHand) {
        VoiceHand.Left -> hands.leftGrip
        VoiceHand.Right -> rightFist.grip
        null -> null
      }
    if (grip != null) {
      val paneHeightM = PANE_HEIGHT_PX * xmux.config.windowWidthM / xmux.spec.widthPx
      val centre = paneFollow.update(MicPanePlacement.target(grip, head.t, paneHeightM), frameDtS)
      return MicPanePlacement.pose(centre, head.t)
    }
    paneFollow.position?.takeIf { MicPanePlacement.inView(it, head) }?.let { return MicPanePlacement.pose(it, head.t) }
    paneFollow.reset()
    return aboveDock(head)
  }

  /** Centred just above the dock, in the dock's plane, so it moves with the view like the dock. */
  private fun aboveDock(head: Pose): Pose {
    val paneHeightM = PANE_HEIGHT_PX * xmux.config.windowWidthM / xmux.spec.widthPx
    val dock = dockPose() ?: DockPlacement.pose(head)
    return dock * Pose(Vector3(0f, DockPlacement.HEIGHT_M / 2f + ABOVE_DOCK_GAP_M + paneHeightM / 2f, 0f))
  }

  /** The focused window's title, read through state so the live pane follows focus changes. */
  private fun focusedTitle(): String? {
    val screen = focus.focusedScreen ?: return null
    return xmux.windows.firstOrNull { it.screen == screen }?.state?.title
  }

  private fun newSlot(): Slot {
    lateinit var slot: Slot
    val entity =
      xmux.mountPanel(PANE_WIDTH_PX, PANE_HEIGHT_PX, Placement.InFrontOfHead) {
        slot.note?.let {
          VoiceBubble(it, voice, target = focusedTitle(), freeHandLeft = freeHandLeft(it), onPasteFocused = ::pasteIntoFocused,
            onCancel = voice::cancelListening, onClear = voice::clearListening, onToggleEnter = { toggleEnterAfter(it.mode) })
        }
      }
    entity.setComponent(Visible(false))
    slot = Slot(entity)
    slots.add(slot)
    return slot
  }

  private fun placeMarker() {
    val entity = marker ?: return
    val hand = if (voiceHand == VoiceHand.Left) hands.leftGrip else rightFist.grip
    val show = propShown && voiceHand != null && voice.listening != null && hand != null
    if (show != markerShown) {
      entity.setComponent(Visible(show))
      markerShown = show
    }
    if (!show || hand == null) return
    val head = headPose() ?: return
    // Keep the listening badge beside the prop rather than covering its model.
    val position = hand.t + head.q.times(Vector3(if (voiceHand == VoiceHand.Left) -0.08f else 0.08f, 0.03f, 0f))
    entity.setComponent(Transform(Pose(position, facing(head, position))))
  }

  /**
   * The selected mic is in the fist that started voice mode for as long as it stays closed,
   * whether or not the microphone could start (a failure shows on the voice pane instead). It
   * takes the hand's grip pose every frame, so it moves and turns with the hand. Settings can
   * also hold it on that hand without a fist or starting capture; tracking loss hides it.
   */
  private fun placeProp() {
    val entity = propEntity ?: return
    val mic = settingsPreview ?: if (propPreview) debugMic else when (voiceHand) {
      VoiceHand.Left -> VoiceMic.Ai
      VoiceHand.Right -> VoiceMic.Text
      null -> null
    }
    val prop = if (mic == VoiceMic.Text) textProp else aiProp
    val asset = loadableAsset(prop)
    if (prop != selectedProp || asset != selectedAsset) {
      selectedProp = prop
      selectedAsset = asset
      if (asset != prop.assetPath(aiProvider)) Log.w(TAG, "voice prop ${prop.assetPath(aiProvider)} is not in the APK; showing $asset")
      entity.setComponent(Mesh(Uri.parse(asset), MeshCollision.NoCollision))
    }
    if (mic != propMic) {
      propMic = mic
      Log.i(TAG, "voice prop mode=${mic?.label} hand=${mic?.hand} asset=${prop.storageValue}")
    }
    val pose =
      if (propPreview && settingsPreview == null) {
        headPose()?.let { head -> Pose(head.t + head.forward().normalize() * PREVIEW_DISTANCE_M, head.q.removePitchAndRoll()) }
      } else {
        when (mic) {
          VoiceMic.Ai -> hands.leftGrip
          VoiceMic.Text -> rightFist.grip
          null -> null
        }
      }
    if (pose != null) entity.setComponent(Transform(pose))
    val show = pose != null
    if (show != propShown) {
      entity.setComponent(Visible(show))
      propShown = show
      Log.i(TAG, "voice prop ${selectedProp.storageValue} ${if (show) "shown at $pose" else "hidden"}")
    }
  }

  /** The nearest shown voice pane the ray crosses, with the distance along the ray. */
  private fun nearestPane(origin: Vector3, direction: Vector3): Pair<VoiceNote, Float>? {
    val metresPerPx = xmux.config.windowWidthM / xmux.spec.widthPx
    val halfW = PANE_WIDTH_PX * metresPerPx / 2f
    val halfH = PANE_HEIGHT_PX * metresPerPx / 2f
    var best: Pair<VoiceNote, Float>? = null
    for (slot in slots) {
      if (!slot.shown) continue
      val note = slot.note ?: continue
      val pose = slot.pose ?: continue
      val normal = pose.q.times(Vector3(0f, 0f, 1f))
      val denom = direction.dot(normal)
      if (abs(denom) < 1e-5f) continue
      val t = (pose.t - origin).dot(normal) / denom
      if (t < 0f) continue
      val local = origin + direction * t - pose.t
      val inside =
        abs(local.dot(pose.q.times(Vector3(1f, 0f, 0f)))) <= halfW && abs(local.dot(pose.q.times(Vector3(0f, 1f, 0f)))) <= halfH
      if (inside && (best == null || t < best.second)) best = note to t
    }
    return best
  }

  /** A yaw-only rotation that turns a panel at [position] to face the head. */
  private fun facing(head: Pose, position: Vector3): Quaternion {
    val away = position - head.t
    return Quaternion.lookRotationAroundY(Vector3(away.x, 0f, away.z))
  }

  private fun headPose(): Pose? {
    val head =
      Query.where { has(AvatarAttachment.id) }
        .filter { isLocal() and by(AvatarAttachment.typeData).isEqualTo("head") }
        .eval()
        .firstOrNull() ?: return null
    return head.tryGetComponent<Transform>()?.transform
  }

  companion object {
    const val DEFAULT_PORT = 7273
    private const val DEBUG_ACTION = "dev.improve.vrcode.VOICE"
    private const val TAG = "vrcode"
    private const val PANE_WIDTH_PX = 400
    private const val PANE_HEIGHT_PX = 230
    private const val MARKER_PX = 96
    private const val ABOVE_DOCK_GAP_M = 0.015f
    private const val CONTEXT_LINES = 60
    private const val PREVIEW_DISTANCE_M = 0.4f
    private const val PREFS = "voice"
    private const val PREF_ENTER_DICTATION = "enter_after_dictation"
    private const val PREF_ENTER_AI = "enter_after_ai"
  }
}
