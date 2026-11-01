package dev.improve.vrcode

import com.meta.spatial.runtime.Scene
import com.meta.spatial.core.SpatialSDKExperimentalAPI

/** Reads the runtime's left-hand MENU_PRESSED bit, including when ISDK suspends its pointer. */
@OptIn(SpatialSDKExperimentalAPI::class)
class LeftPalmMenu : AutoCloseable {
  private var handle = 0L
  private var session = 0L

  fun isPressed(scene: Scene, predictedTimeNs: Long): Boolean {
    val currentSession = scene.getOpenXrSessionHandle()
    if (currentSession == 0L || predictedTimeNs <= 0L) return false
    if (session != currentSession) {
      close()
      session = currentSession
      handle = create(scene.getOpenXrInstanceHandle(), currentSession, scene.getOpenXrGetInstanceProcAddrHandle())
    }
    return handle != 0L && poll(handle, predictedTimeNs)
  }

  override fun close() {
    if (handle != 0L) destroy(handle)
    handle = 0L
    session = 0L
  }

  private external fun create(instance: Long, session: Long, getProcAddr: Long): Long
  private external fun poll(handle: Long, time: Long): Boolean
  private external fun destroy(handle: Long)

  companion object {
    init { System.loadLibrary("vrcode-hand-menu") }
  }
}

