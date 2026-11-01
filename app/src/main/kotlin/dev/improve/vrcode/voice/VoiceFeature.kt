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
import androidx.compose.runtime.getValue
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
import dev.improve.vrcode.VoiceProp
import dev.improve.vrcode.dock.DockPlacement
import dev.improve.vrcode.hands.HandInput
import dev.improve.vrcode.hands.RightFistInput
import dev.improve.vrcode.speech.SpeechLink
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope

/**
 * Voice mode end to end, for any activity that hosts xmux. Two fists, two modes:
 * - right fist: plain dictation, pasted exactly as said;
 * - left fist: speak to the AI, which turns what was said into the input for the focused pane (a
 *   shell command, or a prompt for a coding agent) using that pane's screen.
 * The live voice pane sits just above the dock, which follows the view; a dictation pane hides when
 * the fist opens, an AI pane stays until it is pasted or deleted so its answer can be reviewed.
 * Text stays staged for a left pinch on a terminal pane to paste it.
 * The host calls [onSceneReady], [onSceneTick] and [close], and [connectSpeech] with the Mac's
 * address; [predictedTimeNs] is the frame's predicted display time for OpenXR hand joints.
 */
class VoiceFeature(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  scope: CoroutineScope,
  private val overlayHitDistance: (Vector3, Vector3) -> Float? = { _, _ -> null },
  /** The dock's current (eased) pose, so the voice pane moves with it; null uses its target pose. */
  private val dockPose: () -> Pose? = { null },
  predictedTimeNs: () -> Long,
) {
  private val hands = HandInput(activity, predictedTimeNs)
  private val rightFist = RightFistInput(activity, predictedTimeNs)
  private val link: SpeechLink = SpeechLink(scope) { voice.onSpeechEvent(it) }
  val voice: VoiceController =
    VoiceController(link, aiContext = ::paneContext) {
      activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

  /** The hand whose fist started the live dictation; only that hand opening ends it. */
  private enum class VoiceHand { Left, Right }

  private var voiceHand: VoiceHand? = null

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
  /** Settings preview uses the tracked left hand, independently of the dictation gesture. */
  private var settingsPreview = false
  private var selectedProp = VoiceProp.Microphone
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
    propEntity =
      Entity.create(listOf(Mesh(Uri.parse(selectedProp.assetPath), MeshCollision.NoCollision), Transform(), Visible(false)))
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && !debugRegistered) {
      activity.registerReceiver(debugReceiver, IntentFilter(DEBUG_ACTION), Context.RECEIVER_EXPORTED)
      debugRegistered = true
    }
  }

  /**
   * Debug builds only: drives voice mode without hands, e.g.
   * `adb shell am broadcast -a dev.improve.vrcode.VOICE --es cmd start` (start, stop, delete, paste;
   * `prop` toggles a preview of the voice prop in front of the head).
   */
  private val debugReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        when (intent.getStringExtra("cmd")) {
          "start" -> startVoiceMode(VoiceHand.Right) // plain dictation
          "ai" -> startVoiceMode(VoiceHand.Left) // speak to the AI
          "stop" -> stopVoiceMode()
          "delete", "clear" -> voice.selected?.let(voice::remove)
          "paste", "insert" -> voice.selected?.let(::pasteIntoFocused)
          "prop" -> propPreview = !propPreview
        }
        Log.i(TAG, "debug voice ${intent.getStringExtra("cmd")}: panes=${voice.notes.map { "${it.id}:${it.mode}:${it.phase}:\"${it.output}\"" }}")
      }
    }
  private var debugRegistered = false

  /** The preference also applies immediately if voice mode is already active. */
  fun setVoiceProp(prop: VoiceProp) {
    if (selectedProp == prop) return
    selectedProp = prop
    propEntity?.setComponent(Mesh(Uri.parse(prop.assetPath), MeshCollision.NoCollision))
    Log.i(TAG, "voice prop: ${prop.storageValue}")
  }

  fun onSceneTick(allowGestures: Boolean = true, previewOnLeftHand: Boolean = false) {
    if (settingsPreview != previewOnLeftHand) {
      settingsPreview = previewOnLeftHand
      Log.i(TAG, "voice prop left-hand settings preview ${if (settingsPreview) "enabled" else "disabled"}")
    }
    hands.quickRelease = voice.quiet // nothing said yet: an opening hand ends voice mode at once
    rightFist.quickRelease = voice.quiet
    val now = SystemClock.uptimeMillis()
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
    voice.startListening(if (hand == VoiceHand.Left) VoiceNote.Mode.Ai else VoiceNote.Mode.Passthrough)
    activeNote = voice.selected
  }

  private fun stopVoiceMode() {
    voiceHand = null
    voice.stopListening()
  }

  /** The focused pane's visible text (bottom [CONTEXT_LINES] rows) and title, for the AI. */
  private fun paneContext(): AiContext? {
    val window = xmux.focused ?: return null
    val grid = xmux.focusedTerminal()?.grid?.value ?: return null
    val lines = (0 until grid.rows).map { grid.rowText(it) }.dropLastWhile { it.isBlank() }.takeLast(CONTEXT_LINES)
    return AiContext(lines.joinToString("\n"), window.state.title)
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
    type(session.terminal(terminal), text)
    Log.i(TAG, "voice: pasted pane ${note.id} (${text.length} chars) into terminal ${terminal.value}")
  }

  /** Pastes [note] into the focused terminal pane and removes it. */
  fun pasteIntoFocused(note: VoiceNote) {
    val handle = xmux.focusedTerminal() ?: return
    val text = voice.take(note) ?: return
    type(handle, text)
    Log.i(TAG, "voice: pasted pane ${note.id} (${text.length} chars) into the focused terminal")
  }

  private fun type(handle: TerminalHandle, text: String) {
    handle.write(text)
    if (voice.enterAfterPaste) handle.keys(Key("enter"))
  }

  /**
   * Only the current pane is visible, just above the dock (which follows the view): while its fist
   * is closed, while the Mac finishes or the AI thinks, and for an AI pane until it is pasted.
   */
  private fun syncPanes() {
    if (!sceneReady) return
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
      val live =
        voiceHand != null || voice.listening == note ||
          note.phase == VoiceNote.Phase.Finalizing || note.phase == VoiceNote.Phase.Thinking || note.mode == VoiceNote.Mode.Ai
      if (note != activeNote || !live || head == null) {
        slot.show(false)
        continue
      }
      val pose = aboveDock(head)
      slot.pose = pose
      slot.entity.setComponent(Transform(pose))
      slot.show(true)
    }
  }

  /** Centred just above the dock, in the dock's plane, so it moves with the view like the dock. */
  private fun aboveDock(head: Pose): Pose {
    val paneHeightM = PANE_HEIGHT_PX * xmux.config.windowWidthM / xmux.spec.widthPx
    val dock = dockPose() ?: DockPlacement.pose(head)
    return dock * Pose(Vector3(0f, DockPlacement.HEIGHT_M / 2f + ABOVE_DOCK_GAP_M + paneHeightM / 2f, 0f))
  }

  private fun newSlot(): Slot {
    lateinit var slot: Slot
    val entity =
      xmux.mountPanel(PANE_WIDTH_PX, PANE_HEIGHT_PX, Placement.InFrontOfHead) {
        slot.note?.let { VoiceBubble(it, voice, onPasteFocused = ::pasteIntoFocused) }
      }
    entity.setComponent(Visible(false))
    slot = Slot(entity)
    slots.add(slot)
    return slot
  }

  private fun placeMarker() {
    val entity = marker ?: return
    val hand = hands.leftGrip
    val show = propShown && voiceHand == VoiceHand.Left && voice.listening != null && hand != null
    if (show != markerShown) {
      entity.setComponent(Visible(show))
      markerShown = show
    }
    if (!show || hand == null) return
    val head = headPose() ?: return
    // Keep the listening badge beside the prop rather than covering its model.
    val position = hand.t + head.q.times(Vector3(-0.08f, 0.03f, 0f))
    entity.setComponent(Transform(Pose(position, facing(head, position))))
  }

  /**
   * The prop is in the left fist for as long as the fist that started voice mode stays closed,
   * whether or not the microphone could start (a failure shows on the voice pane instead). It
   * takes the hand's grip pose every frame, so it moves and turns with the hand. Settings can
   * also hold it on that hand without a fist or starting capture; tracking loss hides it.
   */
  private fun placeProp() {
    val entity = propEntity ?: return
    val pose =
      if (settingsPreview) {
        hands.leftGrip
      } else if (propPreview) {
        headPose()?.let { head -> Pose(head.t + head.forward().normalize() * PREVIEW_DISTANCE_M, head.q.removePitchAndRoll()) }
      } else {
        hands.leftGrip?.takeIf { voiceHand == VoiceHand.Left }
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
    private const val PANE_HEIGHT_PX = 190
    private const val MARKER_PX = 96
    private const val ABOVE_DOCK_GAP_M = 0.015f
    private const val CONTEXT_LINES = 60
    private const val PREVIEW_DISTANCE_M = 0.4f
  }
}
