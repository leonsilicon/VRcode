package dev.improve.vrcode.hands

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene

/**
 * The left hand's 26 OpenXR joint positions (XR_EXT_hand_tracking) and the head pose, both in
 * vrcode's own LOCAL space, read each frame (cpp/hand_joints.cpp). [SceneAlignment] maps LOCAL
 * poses into the Spatial SDK scene using [headInLocal].
 */
@OptIn(SpatialSDKExperimentalAPI::class)
class LeftHandJoints : AutoCloseable {
  private var handle = 0L
  private var session = 0L
  private val buffer = FloatArray(FistShape.JOINT_COUNT * 3)
  private val viewBuffer = FloatArray(7)

  /** The head pose in LOCAL space from the last [poll], or null when it was not valid. */
  var headInLocal: Pose? = null
    private set

  /** Joint positions as x, y, z triples in LOCAL space, or null when the hand is not tracked. */
  fun poll(scene: Scene, predictedTimeNs: Long): FloatArray? {
    headInLocal = null
    val currentSession = scene.getOpenXrSessionHandle()
    if (currentSession == 0L || predictedTimeNs <= 0L) return null
    if (session != currentSession) {
      close()
      session = currentSession
      handle = create(scene.getOpenXrInstanceHandle(), currentSession, scene.getOpenXrGetInstanceProcAddrHandle())
    }
    if (handle == 0L) return null
    val valid = poll(handle, predictedTimeNs, buffer, viewBuffer)
    if (valid and VIEW_VALID != 0) {
      val v = viewBuffer
      headInLocal = Pose(Vector3(v[0], v[1], v[2]), Quaternion(v[3], v[4], v[5], v[6]))
    }
    return if (valid and JOINTS_VALID != 0) buffer else null
  }

  /** False once creating the tracker failed for the current session (callers fall back). */
  val available: Boolean get() = session == 0L || handle != 0L

  override fun close() {
    if (handle != 0L) destroy(handle)
    handle = 0L
    session = 0L
  }

  private external fun create(instance: Long, session: Long, getProcAddr: Long): Long

  private external fun poll(handle: Long, time: Long, out: FloatArray, viewOut: FloatArray): Int

  private external fun destroy(handle: Long)

  private companion object {
    const val JOINTS_VALID = 1
    const val VIEW_VALID = 2

    init {
      System.loadLibrary("vrcode-hand-joints")
    }
  }
}
