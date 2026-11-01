package dev.improve.vrcode.hands

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pie selection by hand displacement: [count] items around a circle, item 0 at the top and the rest
 * clockwise. Within [deadZoneM] of the start nothing is picked (releasing there cancels); at
 * [reachM] the hand is over the item, which runs it without waiting for the release.
 */
class RadialMenu(val count: Int, val deadZoneM: Float = 0.035f, val reachM: Float = 0.07f) {
  init {
    require(count > 0) { "a radial menu needs at least one item" }
    require(reachM > deadZoneM) { "reach must be beyond the dead zone" }
  }

  /** The item for a displacement of [rightM] and [upM] in the menu's plane, or null in the dead zone. */
  fun pick(rightM: Float, upM: Float): Int? {
    if (sqrt(rightM * rightM + upM * upM) < deadZoneM) return null
    val angle = atan2(rightM, upM) // 0 at the top, growing clockwise
    val step = 2 * PI / count
    return ((angle / step).roundToInt() % count + count) % count
  }

  /** Whether a displacement of [rightM] and [upM] has reached the items' ring. */
  fun reached(rightM: Float, upM: Float): Boolean = sqrt(rightM * rightM + upM * upM) >= reachM
}
