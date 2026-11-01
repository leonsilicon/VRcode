package dev.improve.vrcode.hands

import android.util.Log
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import kotlin.math.acos

/**
 * Debug probe for a "speak into the fist" voice gate: logs how close the fist's microphone grille
 * is to the user's mouth and how directly the fist points at it, so the gate's distances come from
 * measurements, and so we can see whether hand tracking holds with the fist at the mouth.
 *
 * The mouth is a fixed offset from the head (OpenXR VIEW origin, between the eyes; +X right, +Y up,
 * -Z forward). The grille end comes from [FistGrip]: its +Y runs along the knuckle row out of the
 * thumb-and-index opening. Head and joints are both in the tracker's LOCAL space, so they compare
 * directly. This is the pattern Meta's Interaction SDK uses for head-relative zones (an `HmdOffset`
 * target with a `ColliderContainsHandJointActiveState` that has separate entry and exit volumes) and
 * that Ultraleap's `HandPoseDetector` uses for its proximity and "towards object" rules.
 *
 * Each line also carries Meta's Interaction SDK finger features in its units, from joint positions
 * (unsigned, where the SDK uses signed hinge angles): flexion = 180 + knuckle bend, curl = 180 +
 * the mean of the middle and end joint bends. The SDK's "all fingers closed" fist needs every
 * finger at curl > 245 and flexion > 240 to enter (exit below 225 and 230).
 *
 * `adb logcat -s vrcode-mouth`
 */
class MouthProbe {
  class Reading(
    /** The grille in head space, metres: +X right, +Y up, -Z forward. */
    val grilleInHead: Vector3,
    /** Grille to mouth, metres. */
    val mouthM: Float,
    /** Degrees between the fist's grille direction and the direction from the fist to the mouth. */
    val aimDegrees: Float,
  )

  private var lastLogMs = Long.MIN_VALUE / 2
  private var wasTracked = false
  private var last: Reading? = null

  /** [joints] null while the hand is untracked; [head] is the head pose in the joints' space. */
  fun tick(joints: FloatArray?, head: Pose?, fist: Boolean, nowMs: Long) {
    if (head == null) return
    val reading = joints?.let(FistGrip::pose)?.let { measure(it, head) }
    val tracked = reading != null
    if (tracked != wasTracked) {
      wasTracked = tracked
      lastLogMs = nowMs
      // On loss, the last reading says where the fist was when tracking dropped.
      Log.i(TAG, "tracked=$tracked fist=$fist ${(reading ?: last)?.let(::describe).orEmpty()}")
    } else if (reading != null && joints != null && reading.mouthM < LOG_WITHIN_M && nowMs - lastLogMs >= LOG_EVERY_MS) {
      lastLogMs = nowMs
      Log.d(TAG, "fist=$fist ${describe(reading)} ${FistShape.measure(joints)} isdk ${isdkFeatures(joints)}")
    }
    if (reading != null) last = reading
  }

  companion object {
    const val TAG = "vrcode-mouth"

    /** The lips relative to the VIEW origin: 8 cm below the eyes and 3 cm in front of them. */
    val MOUTH_IN_HEAD = Vector3(0f, -0.08f, -0.03f)

    /** From the fist's hollow ([FistGrip] origin) to where a held microphone's grille would be. */
    const val GRILLE_M = 0.05f

    private const val LOG_WITHIN_M = 0.45f
    private const val LOG_EVERY_MS = 200L

    fun measure(grip: Pose, head: Pose): Reading {
      val axis = grip.q.times(Vector3(0f, 1f, 0f))
      val grille = grip.t + axis * GRILLE_M
      val mouth = head.t + head.q.times(MOUTH_IN_HEAD)
      val grilleInHead = head.q.inverse().times(grille - head.t)
      return Reading(grilleInHead, grille.distanceTo(mouth), degreesBetween(axis, mouth - grip.t))
    }

    fun describe(r: Reading): String {
      val g = r.grilleInHead
      return "mouth=${cm(r.mouthM)}cm aim=${r.aimDegrees.toInt()}deg grille(head)=${cm(g.x)},${cm(g.y)},${cm(g.z)}cm"
    }

    /** Per finger (index..little), Meta Interaction SDK flexion and curl values; see the class doc. */
    fun isdkFeatures(joints: FloatArray): String {
      val flex = FistShape.FINGERS.map { 180 + FistShape.curlDegrees(joints, intArrayOf(it[0], it[1], it[2])).toInt() }
      val curl = FistShape.FINGERS.map { 180 + (FistShape.curlDegrees(joints, intArrayOf(it[1], it[2], it[3], it[4])) / 2f).toInt() }
      return "flex=${flex.joinToString(",")} curl=${curl.joinToString(",")}"
    }

    private fun degreesBetween(a: Vector3, b: Vector3): Float {
      val la = a.length()
      val lb = b.length()
      if (la < 1e-6f || lb < 1e-6f) return 180f
      return Math.toDegrees(acos((a.dot(b) / (la * lb)).coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    private fun cm(m: Float) = (m * 100f).toInt()
  }
}
