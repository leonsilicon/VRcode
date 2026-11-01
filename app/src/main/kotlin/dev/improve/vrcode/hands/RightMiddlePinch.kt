package dev.improve.vrcode.hands

import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene

/** Reads XR_FB_hand_tracking_aim's runtime-recognized middle pinch for XR_HAND_RIGHT_EXT only. */
@OptIn(SpatialSDKExperimentalAPI::class)
class RightMiddlePinch : AutoCloseable {
  private var handle = 0L
  private var session = 0L

  /** Null means tracking/aim data is unavailable. Index pinches and OS gestures are blocked. */
  fun poll(scene: Scene, predictedTimeNs: Long): MiddlePinchGate.Signal? {
    val currentSession = scene.getOpenXrSessionHandle()
    if (currentSession == 0L) {
      close()
      return null
    }
    if (session != currentSession) {
      close()
      session = currentSession
      handle = create(scene.getOpenXrInstanceHandle(), currentSession, scene.getOpenXrGetInstanceProcAddrHandle())
    }
    if (handle == 0L || predictedTimeNs <= 0L) return null
    return when (poll(handle, predictedTimeNs)) {
      0 -> MiddlePinchGate.Signal.Released
      1 -> MiddlePinchGate.Signal.Pinched
      2 -> MiddlePinchGate.Signal.Blocked
      else -> null
    }
  }

  override fun close() {
    if (handle != 0L) destroy(handle)
    handle = 0L
    session = 0L
  }

  private external fun create(instance: Long, session: Long, getProcAddr: Long): Long
  private external fun poll(handle: Long, time: Long): Int
  private external fun destroy(handle: Long)

  private companion object {
    init { System.loadLibrary("vrcode-right-pinch") }
  }
}
