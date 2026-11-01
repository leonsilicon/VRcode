package dev.improve.vrcode

import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Query
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.core.Vector4
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.runtime.BlendMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.DepthWrite
import com.meta.spatial.runtime.PointerEvent
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMaterialAttribute
import com.meta.spatial.runtime.SceneMaterialDataType
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SortOrder
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.AvatarAttachment
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.Transform
import dev.improve.vrcode.hands.HandAim

/**
 * Meta's laser for a ray that hits nothing, in the Interaction SDK's own shape
 * (`IsdkDefaultCursorSystem`, SDK 0.14). It starts [HAND_START_M] out from a tracked hand's aim
 * point, or at a controller's tip, and runs [MAX_LENGTH_M] along the ray. It stops [SURFACE_M]
 * short of a [surface] the SDK cannot hit, the way the SDK's laser stops short of a panel.
 */
internal class FreeLaserShape(val position: Vector3, val rotation: Quaternion, val scale: Vector3) {
  companion object {
    const val MAX_LENGTH_M = 0.25f
    const val HAND_START_M = 0.1f
    const val SURFACE_M = 0.05f
    /** The laser box spans -1 to 1 across, so this half-width makes it 1 mm wide. */
    const val HALF_WIDTH_M = 0.0005f

    fun at(aim: Pose, hand: Boolean, surface: Float? = null): FreeLaserShape? {
      val start = if (hand) HAND_START_M else 0f
      val length = if (surface == null) MAX_LENGTH_M else minOf(MAX_LENGTH_M, surface - start - SURFACE_M)
      if (!(length > 0f)) return null
      return FreeLaserShape(aim.t + aim.forward() * start, aim.q, Vector3(HALF_WIDTH_M, HALF_WIDTH_M, length))
    }
  }
}

/**
 * Keeps Meta's hand and controller laser on while [tick]'s `shown` holds. The SDK's cursor system
 * drops every pointer event without a hit, so its laser only appears over a panel. For each
 * pointer whose ray the SDK did not draw since the last tick, this draws the same mesh and shader
 * at full length. A hand shows none while the runtime reports its aim invalid or the palm facing
 * the headset (its system gesture), as the system's own pointer does. Pointers whose
 * [Controller.laserEnabled] is off (the live mic's hand) show none either. Main thread only.
 */
@OptIn(SpatialSDKExperimentalAPI::class)
internal class FreeLasers(
  private val activity: AppSystemActivity,
  private val predictedTimeNs: () -> Long,
  /** How far along a ray a surface the SDK cannot hit lies (the dock while it is not hittable), or null. */
  private val surface: (Vector3, Vector3) -> Float?,
) : AutoCloseable {
  private class Laser(val obj: SceneObject, val mesh: SceneMesh, val material: SceneMaterial) {
    var visible = true
    var pressed: Boolean? = null
  }

  private var isdk: IsdkSystem? = null
  /** Pointer entities the SDK reported a hit for, so drew its own laser, since the last tick. */
  private val sdkDrawn = HashSet<Long>()
  private val lasers = HashMap<Long, Laser>()
  private val aims = mapOf(true to HandAim(left = true), false to HandAim(left = false))

  // The SDK's cursor system draws for exactly these events: a hit, with the cursor not disabled.
  private val observer: (PointerEvent) -> Unit = { event ->
    if (event.hitInfo.distance != Float.MAX_VALUE && event.pointerType and CURSOR_DISABLED == 0) {
      sdkDrawn += event.source.id
    }
  }

  fun tick(shown: Boolean) {
    if (isdk == null) isdk = activity.systemManager.tryFindSystem<IsdkSystem>()?.also { it.registerObserver(observer) }
    val drawn = HashSet<Long>()
    if (shown) {
      Query.where { has(Controller.id, AvatarAttachment.id) }.filter { isLocal() }.eval().forEach { entity ->
        val (left, hand) = when (entity.getComponent<AvatarAttachment>().type) {
          "left_hand" -> true to true
          "right_hand" -> false to true
          "left_controller" -> true to false
          "right_controller" -> false to false
          else -> return@forEach // the eye-gaze input is a Controller too
        }
        val controller = entity.getComponent<Controller>()
        if (!controller.isActive || !controller.laserEnabled || entity.id in sdkDrawn) return@forEach
        val pressed = if (hand) {
          val frame = aims.getValue(left).poll(activity.scene, predictedTimeNs()) ?: return@forEach
          if (!frame.has(HandAim.AIM_VALID) || frame.has(HandAim.SYSTEM_GESTURE)) return@forEach
          frame.has(HandAim.INDEX_PINCHING)
        } else {
          controller.buttonState and (if (left) ButtonBits.ButtonTriggerL else ButtonBits.ButtonTriggerR) != 0
        }
        val aim = entity.tryGetComponent<Transform>()?.transform ?: return@forEach
        val shape = FreeLaserShape.at(aim, hand, surface(aim.t, aim.forward())) ?: return@forEach
        draw(entity.id, shape, pressed)
        drawn += entity.id
      }
    }
    sdkDrawn.clear()
    for ((id, laser) in lasers) if (id !in drawn && laser.visible) {
      laser.obj.setIsVisible(false)
      laser.visible = false
    }
  }

  private fun draw(id: Long, shape: FreeLaserShape, pressed: Boolean) {
    val laser = lasers.getOrPut(id) { create().also { Log.i(TAG, "free laser drawn for pointer $id past every panel") } }
    laser.obj.setPosition(shape.position)
    laser.obj.setRotationQuat(shape.rotation)
    laser.obj.setScale(shape.scale)
    if (laser.pressed != pressed) {
      laser.material.setAttribute("albedoFactor", if (pressed) PRESSED else NORMAL)
      laser.pressed = pressed
    }
    if (!laser.visible) {
      laser.obj.setIsVisible(true)
      laser.visible = true
    }
  }

  /** The SDK's laser: a box from its origin 1 m along +Z, in its translucent pointer-laser shader. */
  private fun create(): Laser {
    val material = SceneMaterial.custom(SHADER, arrayOf(SceneMaterialAttribute("albedoFactor", SceneMaterialDataType.Vector4)))
    material.setSortOrder(SortOrder.TRANSLUCENT)
    material.setBlendMode(BlendMode.TRANSLUCENT)
    material.setDepthWrite(DepthWrite.DISABLE)
    val mesh = SceneMesh.box(Vector3(-1f, -1f, 0f), Vector3(1f, 1f, 1f), material)
    val obj = SceneObject(activity.scene, mesh, "VrcodeFreeLaser${lasers.size}")
    obj.shouldFrustumCull(false)
    return Laser(obj, mesh, material)
  }

  override fun close() {
    isdk?.unregisterObserver(observer)
    isdk = null
    lasers.values.forEach { it.obj.destroy(); it.mesh.destroy() }
    lasers.clear()
    sdkDrawn.clear()
    aims.values.forEach { it.close() }
  }

  private companion object {
    const val TAG = "VrcodePointer"
    const val SHADER = "data/shaders/isdk/pointerlaser"
    /** `IsdkSystem.PointerTypeBits.CURSOR_DISABLED`, which the SDK keeps internal. */
    const val CURSOR_DISABLED = 0x10000
    /** The SDK's laser colours: white, and blue while pressed. */
    val NORMAL = Vector4(1f, 1f, 1f, 1f)
    val PRESSED = Vector4(0f, 0.12f, 0.47f, 1f)
  }
}
