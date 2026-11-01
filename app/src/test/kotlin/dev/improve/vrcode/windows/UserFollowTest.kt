package dev.improve.vrcode.windows

import com.meta.spatial.core.Vector3
import dev.improve.vrcode.voice.PaneFollow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFollowTest {
  private val frame = 1f / 90f
  private val standing = Vector3(0f, 1.6f, 0f)

  /** A walk at [speed] m/s along +x with a gait's sway (1 Hz, sideways) and bob (2 Hz, up and down). */
  private fun gait(t: Float, speed: Float = 1.4f, swayM: Float = 0.03f, bobM: Float = 0.025f) =
    standing + Vector3(speed * t, bobM * sin(2f * PI.toFloat() * 2f * t), swayM * sin(2f * PI.toFloat() * t))

  @Test fun startingToWalkAcceleratesInsteadOfLurching() {
    val follow = UserFollow()
    follow.update(standing, 0f)
    var previous = standing
    var previousStep = 0f
    var t = 0f
    repeat(270) {
      t += frame
      val anchor = follow.update(standing + Vector3(1.4f * t, 0f, 0f), frame)
      val step = (anchor - previous).length()
      // No frame moves more than 4 mm further than the one before: the speed ramps up.
      assertTrue("frame $it jumped from $previousStep to $step m", step - previousStep < 0.004f)
      previous = anchor
      previousStep = step
    }
    // The old ease jumps straight to 1.3 m/s (15 mm a frame) when the dead zone is crossed.
    val old = PaneFollow(deadZoneM = 0.4f, timeConstantS = 0.3f, snapM = 3f)
    old.update(standing, 0f)
    var oldPrevious = standing
    var oldJump = 0f
    var oldStep = 0f
    t = 0f
    repeat(270) {
      t += frame
      val anchor = old.update(standing + Vector3(1.4f * t, 0f, 0f), frame)
      val step = (anchor - oldPrevious).length()
      oldJump = maxOf(oldJump, step - oldStep)
      oldPrevious = anchor
      oldStep = step
    }
    assertTrue("the old ease lurched by only $oldJump m", oldJump > 0.01f)
  }

  @Test fun walkingBobAndSwayBarelyReachTheWindows() {
    val follow = UserFollow()
    follow.update(standing, 0f)
    val old = PaneFollow(deadZoneM = 0.4f, timeConstantS = 0.3f, snapM = 3f)
    old.update(standing, 0f)
    var t = 0f
    val empty = Float.MAX_VALUE to -Float.MAX_VALUE
    var bob = empty
    var sway = empty
    var oldBob = empty
    var oldSway = empty
    var trail = 0f
    fun Pair<Float, Float>.with(v: Float) = minOf(first, v) to maxOf(second, v)
    repeat(900) { i ->
      t += frame
      val head = gait(t)
      val anchor = follow.update(head, frame)
      val oldAnchor = old.update(head, frame)
      // Once the walk is steady (after 4 s).
      if (i >= 360) {
        bob = bob.with(anchor.y)
        sway = sway.with(anchor.z)
        oldBob = oldBob.with(oldAnchor.y)
        oldSway = oldSway.with(oldAnchor.z)
        trail = maxOf(trail, abs(head.x - anchor.x))
      }
    }
    val bobRange = bob.second - bob.first
    val swayRange = sway.second - sway.first
    // The head bobs 5 cm and sways 6 cm peak to peak.
    assertTrue("windows bob $bobRange m", bobRange < 0.005f)
    assertTrue("windows bob less than before: $bobRange vs ${oldBob.second - oldBob.first}", bobRange < (oldBob.second - oldBob.first) / 3f)
    // About half the sway still reaches them, no more than before.
    assertTrue("windows sway $swayRange m", swayRange < 0.035f)
    assertTrue("sway $swayRange vs ${oldSway.second - oldSway.first}", swayRange <= (oldSway.second - oldSway.first) * 1.02f)
    // Keeping up costs a trail close to before (0.49 m at 1.4 m/s, was 0.42 m).
    assertTrue("trails by $trail m", trail < 0.55f)
  }

  @Test fun standingUpStillRaisesTheWindows() {
    val follow = UserFollow()
    follow.update(Vector3(0f, 1.2f, 0f), 0f)
    var anchor = Vector3(0f, 1.2f, 0f)
    repeat(270) { anchor = follow.update(Vector3(0f, 1.7f, 0f), frame) }
    assertEquals(1.7f, anchor.y, 0.005f)
  }

  @Test fun aStalledOrRepeatedFrameNeitherFlingsNorFreezesIt() {
    val follow = UserFollow()
    follow.update(standing, 0f)
    val far = standing + Vector3(1f, 0f, 0f)
    // A zero-length frame (the same display time twice) leaves it where it was.
    assertEquals(standing, follow.update(far, 0f))
    // A one-second stall moves it at most one clamped step, never past the head.
    val after = follow.update(far, 1f)
    assertTrue(after.x in 0f..1f)
    repeat(270) { follow.update(far, frame) }
    assertEquals(0f, (follow.position!! - far).length(), 0.005f)
  }
}
