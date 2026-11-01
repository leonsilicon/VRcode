package dev.improve.vrcode.hands

import kotlin.math.IEEErem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pie selection by hand displacement: [count] items around a circle, item 0 at the top and the rest
 * clockwise. Within [deadZoneM] of the start nothing is picked; at [reachM] the hand is over the
 * item and its hover begins. Holding there for a second runs it, and swiping on outward ([along])
 * runs it sooner ([MenuDwell]). Releasing the pinch cancels.
 *
 * Items are evenly spaced unless [degrees] gives each one's direction (clockwise from the top), as
 * the quick inputs ring does when some of its eight directions are empty. Then the hand picks the
 * nearest item, so each one's pick area reaches halfway to its neighbours.
 */
class RadialMenu(
  val count: Int,
  val deadZoneM: Float = 0.035f,
  val reachM: Float = 0.07f,
  degrees: List<Float>? = null,
) {
  init {
    require(count > 0) { "a radial menu needs at least one item" }
    require(reachM > deadZoneM) { "reach must be beyond the dead zone" }
    require(degrees == null || degrees.size == count) { "one direction per item" }
  }

  /** Each item's direction in radians, clockwise from the top, or null while evenly spaced. */
  private val angles: DoubleArray? = degrees?.map { Math.toRadians(it.toDouble()) }?.toDoubleArray()

  /** The item for a displacement of [rightM] and [upM] in the menu's plane, or null in the dead zone. */
  fun pick(rightM: Float, upM: Float): Int? {
    if (sqrt(rightM * rightM + upM * upM) < deadZoneM) return null
    val angle = atan2(rightM, upM).toDouble() // 0 at the top, growing clockwise
    val given = angles ?: run {
      val step = 2 * PI / count
      return ((angle / step).roundToInt() % count + count) % count
    }
    return given.indices.minBy { abs((angle - given[it]).IEEErem(2 * PI)) }
  }

  /** Whether a displacement of [rightM] and [upM] has reached the items' ring. */
  fun reached(rightM: Float, upM: Float): Boolean = sqrt(rightM * rightM + upM * upM) >= reachM

  /** How far a displacement of [rightM] and [upM] goes in [item]'s direction from the centre, in metres. */
  fun along(item: Int, rightM: Float, upM: Float): Float {
    val angle = angles?.get(item) ?: (2 * PI * item / count)
    return (rightM * sin(angle) + upM * cos(angle)).toFloat()
  }
}
