package dev.improve.vrcode.hands

import android.util.Log
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.ControllerType
import com.meta.spatial.toolkit.Microgestures
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform

/**
 * Per-frame left-hand signals for voice mode: the fist (dictate) and, once the hand is open again,
 * the index pinch (insert into the pane the left hand points at). The right hand is not read here:
 * its index pinches reach panels through the Interaction SDK, and [RightClickFeature] handles
 * its middle pinch separately.
 *
 * The fist is [FistShape]'s strict check on the hand's joints (all four fingers rolled up, thumb
 * wrapped), not the runtime's isFist, which also fires for a cupped hand. It goes through [FistGate]: it starts after [FIST_ON_MS] of continuous fist and ends only
 * after [FIST_OFF_MS] of a tracked, open hand. Silence never ends it, and neither does the hand
 * leaving the cameras' view. A pinch
 * counts only when it starts at least [PINCH_AFTER_FIST_MS] after any fist, so unrolling the hand
 * cannot insert by accident.
 */
class HandInput(private val activity: AppSystemActivity, private val predictedTimeNs: () -> Long) : AutoCloseable {
  /** The debounced left fist. */
  var leftFist = false
    private set

  /** True on the tick the left fist starts or ends. */
  var leftFistStarted = false
    private set

  var leftFistEnded = false
    private set

  /** The left hand's aim pose: the ray starts at `t` along `forward()`. */
  var leftPose: Pose? = null
    private set

  /**
   * Where the left fist holds a prop, in scene space ([FistGrip]): it follows the hand's position
   * and rotation. Held for [GRIP_HOLD_MS] through tracking gaps, then null. Falls back to the aim
   * pose when the joint tracker is unavailable.
   */
  var leftGrip: Pose? = null
    private set

  /**
   * Set by the caller before [tick]: ending voice mode now would lose nothing (nothing said yet), so
   * an open hand ends it after [QUICK_RELEASE_MS] instead of [FIST_OFF_MS].
   */
  var quickRelease = false

  /** True on the tick a deliberate left index pinch (or left trigger) goes down. */
  var leftPinched = false
    private set

  private val fistGate = FistGate(onMs = FIST_ON_MS, offMs = FIST_OFF_MS)
  private val joints = LeftHandJoints()
  private val alignment = SceneAlignment()
  private val mouthProbe = MouthProbe()
  private var leftGripMs = Long.MIN_VALUE / 2
  private var loggedAlignment = false
  private var lastShapeLogMs = 0L
  private var rawFist = false
  private var rawTracked = false
  private var lastFistMs = Long.MIN_VALUE / 2
  private var pinchDown = false
  private var loggedSetup = false

  fun tick(nowMs: Long) {
    leftFistStarted = false
    leftFistEnded = false
    leftPinched = false
    val body = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()?.tryGetLocalPlayerAvatarBody() ?: return
    val left = body.leftHand
    logSetupOnce(left)

    val controller = left.tryGetComponent<Controller>()
    val active = controller?.isActive == true
    val isHand = active && controller?.type == ControllerType.HAND
    leftPose = if (active) left.tryGetComponent<Transform>()?.transform else null
    // A strict fist from the hand's joints; the runtime's own isFist also fires for a cupped hand,
    // so it is only the fallback when the joint tracker cannot be created. The joints are read
    // whenever the runtime tracks the hand (the aim pose can drop out in a fist).
    val positions = joints.poll(activity.scene, predictedTimeNs())
    updateGrip(positions, nowMs)
    val tracked: Boolean
    val fistNow: Boolean
    if (positions != null) {
      val shape = FistShape.measure(positions)
      tracked = true
      fistNow = FistShape.isFist(shape, wasFist = rawFist)
      if (fistNow != rawFist || (shape.mean > 120f && nowMs - lastShapeLogMs > 1000L)) {
        lastShapeLogMs = nowMs
        Log.d(TAG, "left hand fist=$fistNow $shape")
      }
    } else if (!joints.available) {
      tracked = isHand
      fistNow = isHand && left.tryGetComponent<Microgestures>()?.isFist == true
    } else {
      tracked = false // hand or joints not tracked this frame: hold the current state
      fistNow = rawFist
    }
    if (fistNow != rawFist || tracked != rawTracked) {
      rawFist = fistNow
      rawTracked = tracked
      Log.d(TAG, "left hand tracked=$tracked fist=$fistNow")
    }
    mouthProbe.tick(positions, joints.headInLocal, rawFist, nowMs)
    val releaseMs = if (quickRelease) QUICK_RELEASE_MS else FIST_OFF_MS
    when (fistGate.update(tracked = tracked, fist = fistNow, nowMs = nowMs, releaseMs = releaseMs)) {
      FistGate.Change.Started -> {
        leftFistStarted = true
        Log.i(TAG, "left fist: voice mode on")
      }
      FistGate.Change.Ended -> {
        leftFistEnded = true
        Log.i(TAG, "left hand open: voice mode off")
      }
      FistGate.Change.None -> {}
    }
    leftFist = fistGate.held
    if (rawFist || leftFist) lastFistMs = nowMs

    val down = active && controller != null && (controller.buttonState and ButtonBits.ButtonTriggerL) != 0
    if (down && !pinchDown && !rawFist && nowMs - lastFistMs >= PINCH_AFTER_FIST_MS) {
      leftPinched = true
      Log.d(TAG, "left pinch (${controller?.type})")
    }
    pinchDown = down
  }

  override fun close() {
    joints.close()
    alignment.reset()
  }

  private fun updateGrip(positions: FloatArray?, nowMs: Long) {
    val headInLocal = joints.headInLocal
    val headInScene = activity.scene.getViewerPose()
    if (headInLocal != null && headInScene.q.norm() > 0.5f) {
      alignment.update(headInLocal, headInScene)
      if (!loggedAlignment) {
        loggedAlignment = true
        Log.i(TAG, "hand joints: scene from LOCAL = ${alignment.offset}")
      }
    }
    // Without joints or a known scene offset, the aim pose is the closest thing to the hand.
    val grip =
      positions?.let(FistGrip::pose)?.let(alignment::toScene)
        ?: leftPose?.takeIf { !joints.available || alignment.offset == null }
    if (grip != null) {
      leftGrip = grip
      leftGripMs = nowMs
    } else if (nowMs - leftGripMs > GRIP_HOLD_MS) {
      leftGrip = null
    }
  }

  private fun logSetupOnce(left: Entity) {
    val controller = left.tryGetComponent<Controller>()
    if (loggedSetup || controller?.isActive != true) return
    loggedSetup = true
    Log.i(TAG, "hands: left controller=${controller.type} microgestures=${left.tryGetComponent<Microgestures>() != null}")
  }

  private companion object {
    const val TAG = "vrcode"
    const val FIST_ON_MS = 120L
    const val FIST_OFF_MS = 400L
    const val QUICK_RELEASE_MS = 100L
    const val PINCH_AFTER_FIST_MS = 400L
    const val GRIP_HOLD_MS = 250L
  }
}
