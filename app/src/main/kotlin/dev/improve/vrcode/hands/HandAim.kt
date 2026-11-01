package dev.improve.vrcode.hands

import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.Scene

/**
 * One hand's joints and Meta's runtime aim state, read each frame (cpp/hand_aim.cpp). The Spatial
 * SDK exposes neither per-finger pinches nor hand joints, so this reads them through the public
 * OpenXR handles, like the other trackers here.
 */
@OptIn(SpatialSDKExperimentalAPI::class)
class HandAim(val left: Boolean) : AutoCloseable {
  /**
   * One tracked frame in OpenXR VIEW space (head at the origin, right-handed, -Z forward).
   *
   * @property joints XR_EXT_hand_tracking joint positions as x, y, z triples.
   * @property radii each joint's distance to the skin, in metres.
   * @property palm the palm joint's orientation (x, y, z, w); its +Y points out of the back of the
   *   hand for either hand.
   * @property flags [AIM_VALID], [INDEX_PINCHING], [MIDDLE_PINCHING], [SYSTEM_GESTURE], [MENU_PRESSED].
   * @property indexStrength the runtime's pinchStrengthIndex, 0 to 1.
   * @property middleStrength the runtime's pinchStrengthMiddle, 0 to 1 (1 = middle and thumb touch).
   */
  class Frame(
    val joints: FloatArray,
    val radii: FloatArray,
    val palm: FloatArray,
    val flags: Int,
    val indexStrength: Float,
    val middleStrength: Float,
  ) {
    fun has(flag: Int) = flags and flag != 0
  }

  private var handle = 0L
  private var session = 0L
  private val buffer = FloatArray(OUT_SIZE)

  /** The hand's state at [predictedTimeNs], or null when it is not tracked. */
  fun poll(scene: Scene, predictedTimeNs: Long): Frame? {
    val currentSession = scene.getOpenXrSessionHandle()
    if (currentSession == 0L || predictedTimeNs <= 0L) return null
    if (session != currentSession) {
      close()
      session = currentSession
      handle = create(scene.getOpenXrInstanceHandle(), currentSession, scene.getOpenXrGetInstanceProcAddrHandle(), left)
    }
    if (handle == 0L) return null
    val flags = poll(handle, predictedTimeNs, buffer)
    if (flags < 0) return null
    return Frame(
      joints = buffer.copyOfRange(0, RADII),
      radii = buffer.copyOfRange(RADII, PALM),
      palm = buffer.copyOfRange(PALM, STRENGTH),
      flags = flags,
      indexStrength = buffer[STRENGTH],
      middleStrength = buffer[STRENGTH + 1],
    )
  }

  override fun close() {
    if (handle != 0L) destroy(handle)
    handle = 0L
    session = 0L
  }

  private external fun create(instance: Long, session: Long, getProcAddr: Long, left: Boolean): Long

  private external fun poll(handle: Long, time: Long, out: FloatArray): Int

  private external fun destroy(handle: Long)

  companion object {
    const val AIM_VALID = 1
    const val INDEX_PINCHING = 2
    const val MIDDLE_PINCHING = 4
    const val SYSTEM_GESTURE = 8
    const val MENU_PRESSED = 16

    // Output layout, mirrored in cpp/hand_aim.cpp.
    private const val RADII = FistShape.JOINT_COUNT * 3
    private const val PALM = RADII + FistShape.JOINT_COUNT
    private const val STRENGTH = PALM + 4
    private const val OUT_SIZE = STRENGTH + 2

    init {
      System.loadLibrary("vrcode-hand-aim")
    }
  }
}
