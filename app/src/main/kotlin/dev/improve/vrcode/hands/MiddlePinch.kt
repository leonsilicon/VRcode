package dev.improve.vrcode.hands

/**
 * The thumb-to-middle pinch as Meta's runtime recognizes it (XR_FB_hand_tracking_aim): the
 * MIDDLE_PINCHING discrete signal starts a pinch, and its pinchStrengthMiddle keeps it held through
 * a single-frame dropout of that signal. No app thresholds on fingertip distance are used while
 * the runtime reports pinch data.
 *
 * OpenXR does not promise pinch data while the runtime's own palm-facing system gesture is active
 * ([HandAim.SYSTEM_GESTURE]), and that is exactly the pose the palm menus use. Only if the runtime
 * withholds it (no valid aim, or a system gesture with both strengths at zero) does this fall back
 * to the joints: the thumb and middle fingertips' skin surfaces touching, using each joint's
 * runtime-reported radius.
 */
object MiddlePinch {
  enum class Source { None, Runtime, Joints }

  /** Pinch strength that keeps an already-held pinch down when the discrete signal drops. */
  const val HOLD_STRENGTH = 0.5f
  /** Joint fallback: surface gap that starts a pinch, and the larger one that ends it. */
  const val TOUCH_SLACK_M = 0.006f
  const val RELEASE_SLACK_M = 0.02f
  /** Joint fallback: surface gap at which [progress] starts rising from 0. */
  const val PROGRESS_FAR_M = 0.05f

  /** Whether the runtime withheld its pinch data this frame. */
  fun withheld(f: HandAim.Frame): Boolean =
    !f.has(HandAim.AIM_VALID) || (f.has(HandAim.SYSTEM_GESTURE) && f.middleStrength <= 0f && f.indexStrength <= 0f)

  /** Where this frame's pinch state comes from. */
  fun source(f: HandAim.Frame): Source = if (withheld(f)) Source.Joints else Source.Runtime

  /** Whether the middle finger pinches the thumb, given whether it did on the previous frame. */
  fun down(f: HandAim.Frame, wasDown: Boolean): Boolean {
    if (withheld(f)) {
      return PalmShape.touching(f.joints, f.radii, PalmShape.MIDDLE_TIP, if (wasDown) RELEASE_SLACK_M else TOUCH_SLACK_M)
    }
    return f.has(HandAim.MIDDLE_PINCHING) || (wasDown && f.middleStrength >= HOLD_STRENGTH)
  }

  /** An index pinch is the stronger pinch: it is not a middle pinch with the index merely close. */
  fun indexDominant(f: HandAim.Frame): Boolean {
    if (withheld(f)) {
      return PalmShape.touching(f.joints, f.radii, PalmShape.INDEX_TIP, TOUCH_SLACK_M) &&
        PalmShape.thumbToIndexM(f.joints) < PalmShape.thumbToMiddleM(f.joints)
    }
    return f.has(HandAim.INDEX_PINCHING) && f.indexStrength >= f.middleStrength
  }

  /**
   * A held middle pinch has turned into an index pinch: the runtime reports the index pinching, no
   * longer the middle, and the index as the stronger. Both fingers reading as pinched (the index
   * sits next to the middle finger, so a middle pinch can saturate both strengths) is not one.
   */
  fun indexTakesOver(f: HandAim.Frame): Boolean {
    if (withheld(f)) return indexDominant(f)
    return f.has(HandAim.INDEX_PINCHING) && !f.has(HandAim.MIDDLE_PINCHING) && f.indexStrength > f.middleStrength
  }

  /** How far the middle finger is toward a pinch, 0 to 1: the runtime's pinchStrengthMiddle. */
  fun progress(f: HandAim.Frame): Float {
    if (!withheld(f)) return if (f.has(HandAim.MIDDLE_PINCHING)) 1f else f.middleStrength.coerceIn(0f, 1f)
    val gap = PalmShape.thumbToMiddleM(f.joints) - f.radii[PalmShape.THUMB_TIP] - f.radii[PalmShape.MIDDLE_TIP]
    return (1f - (gap - TOUCH_SLACK_M) / (PROGRESS_FAR_M - TOUCH_SLACK_M)).coerceIn(0f, 1f)
  }
}
