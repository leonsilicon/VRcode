package dev.improve.vrcode.hands

import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity

/**
 * The right fist: plain dictation (paste what was said as is), the counterpart of the left fist's
 * AI mode. Same strict [FistShape] and [FistGate] as the left hand, from its own right-hand
 * [HandAim] tracker; losing tracking holds the state, and [quickRelease] ends it sooner when
 * nothing has been said yet.
 */
class RightFistInput(private val activity: AppSystemActivity, private val predictedTimeNs: () -> Long) : AutoCloseable {
  private val hand = HandAim(left = false)
  private val gate = FistGate(onMs = FIST_ON_MS, offMs = FIST_OFF_MS)
  private var rawFist = false
  /** Grip in scene space, held briefly through tracking gaps just like the left-hand prop. */
  var grip: Pose? = null
    private set
  private var gripMs = Long.MIN_VALUE / 2

  /** Nothing said yet in the live dictation: an opening hand may end it at once. */
  var quickRelease = false

  /** True on the tick the fist starts or ends. */
  var started = false
    private set

  var ended = false
    private set

  val held: Boolean get() = gate.held

  fun tick(nowMs: Long) {
    started = false
    ended = false
    val frame = hand.poll(activity.scene, predictedTimeNs())
    // HandAim locates joints in OpenXR VIEW space. Reflect its right-handed pose into
    // Spatial's left-handed coordinates, then move it with the current scene viewer pose.
    val trackedGrip = frame?.let { FistGrip.pose(it.joints) }?.let { pose ->
      val p = pose.t
      val q = pose.q
      activity.scene.getViewerPose() * Pose(Vector3(p.x, p.y, -p.z), Quaternion(q.w, -q.x, -q.y, q.z))
    }
    if (trackedGrip != null) {
      grip = trackedGrip
      gripMs = nowMs
    } else if (nowMs - gripMs > GRIP_HOLD_MS) {
      grip = null
    }
    if (frame != null) {
      val fist = FistShape.isFist(frame.joints, wasFist = rawFist)
      if (fist != rawFist) Log.d(TAG, "right fist raw=$fist")
      rawFist = fist
    }
    val releaseMs = if (quickRelease) QUICK_RELEASE_MS else FIST_OFF_MS
    when (gate.update(tracked = frame != null, fist = rawFist, nowMs = nowMs, releaseMs = releaseMs)) {
      FistGate.Change.Started -> {
        started = true
        Log.i(TAG, "right fist: dictation on")
      }
      FistGate.Change.Ended -> {
        ended = true
        Log.i(TAG, "right hand open: dictation off")
      }
      FistGate.Change.None -> {}
    }
  }

  override fun close() {
    hand.close()
  }

  private companion object {
    const val TAG = "vrcode"
    const val FIST_ON_MS = 120L
    const val FIST_OFF_MS = 400L
    const val QUICK_RELEASE_MS = 100L
    const val GRIP_HOLD_MS = 250L
  }
}
