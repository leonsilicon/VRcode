package dev.improve.vrcode.hands

/**
 * One finger's pinch to the thumb as Meta's runtime recognizes it (XR_FB_hand_tracking_aim): the
 * finger's discrete PINCHING signal starts a pinch, and its pinch strength keeps it held through a
 * single-frame dropout of that signal. No app thresholds on fingertip distance are used while the
 * runtime reports pinch data.
 *
 * On-device logs only show PINCHING at full strength (1.0), which takes a firm press: with a light
 * touch the hint (this strength) can sit almost full without a pinch starting. A finger with a
 * [startStrength] also starts a pinch once its strength reaches that, provided it is the stronger
 * finger and the other is not flagged.
 *
 * OpenXR does not promise pinch data while the runtime's own palm-facing system gesture is active
 * ([HandAim.SYSTEM_GESTURE]), and that is exactly the pose the palm menus use. Only if the runtime
 * withholds it (no valid aim, or a system gesture with both strengths at zero) does this fall back
 * to the joints: the thumb and fingertip skin surfaces touching, using each joint's
 * runtime-reported radius.
 *
 * The index and middle fingers sit side by side, so one finger's pinch can make the runtime report
 * the other's too. [otherDominant] and [otherTakesOver] tell the two apart. When both report the
 * same strength, the index wins: a middle pinch never opens on such a frame and an index pinch does.
 */
sealed class FingerPinch(
  /** The finger, for logs: "middle" or "index". */
  val name: String,
  private val tip: Int,
  /** This finger's PINCHING bit in [HandAim.Frame.flags]. */
  val flag: Int,
  private val otherTip: Int,
  private val otherFlag: Int,
  private val winsTies: Boolean,
  /** Runtime strength that starts a pinch without the PINCHING signal, or null to need the signal. */
  val startStrength: Float? = null,
) {
  enum class Source { None, Runtime, Joints }

  /** This finger's runtime pinch strength, 0 to 1 (1 = it touches the thumb). */
  protected abstract fun strength(f: HandAim.Frame): Float

  /** The other finger's runtime pinch strength. */
  protected abstract fun otherStrength(f: HandAim.Frame): Float

  /** Whether the runtime withheld its pinch data this frame. */
  fun withheld(f: HandAim.Frame): Boolean =
    !f.has(HandAim.AIM_VALID) || (f.has(HandAim.SYSTEM_GESTURE) && f.middleStrength <= 0f && f.indexStrength <= 0f)

  /** Where this frame's pinch state comes from. */
  fun source(f: HandAim.Frame): Source = if (withheld(f)) Source.Joints else Source.Runtime

  /** Whether this finger pinches the thumb, given whether it did on the previous frame. */
  fun down(f: HandAim.Frame, wasDown: Boolean): Boolean {
    if (withheld(f)) {
      return PalmShape.touching(f.joints, f.radii, tip, if (wasDown) RELEASE_SLACK_M else TOUCH_SLACK_M)
    }
    if (f.has(flag) || (wasDown && strength(f) >= HOLD_STRENGTH)) return true
    val start = startStrength ?: return false
    return strength(f) >= start && !f.has(otherFlag) && strongerThanOther(f)
  }

  /** This finger reads the stronger, with ties going to the index (see the class comment). */
  private fun strongerThanOther(f: HandAim.Frame): Boolean =
    if (winsTies) strength(f) >= otherStrength(f) else strength(f) > otherStrength(f)

  /** The other finger's pinch is the stronger: this is not our pinch with the other finger merely close. */
  fun otherDominant(f: HandAim.Frame): Boolean {
    if (withheld(f)) {
      return PalmShape.touching(f.joints, f.radii, otherTip, TOUCH_SLACK_M) &&
        PalmShape.thumbToTipM(f.joints, otherTip) < PalmShape.thumbToTipM(f.joints, tip)
    }
    if (!f.has(otherFlag)) return false
    return if (winsTies) otherStrength(f) > strength(f) else otherStrength(f) >= strength(f)
  }

  /**
   * A held pinch has turned into the other finger's: the runtime reports the other finger pinching,
   * no longer this one, and the other as the stronger. Both fingers reading as pinched is not one.
   */
  fun otherTakesOver(f: HandAim.Frame): Boolean {
    if (withheld(f)) return otherDominant(f)
    return f.has(otherFlag) && !f.has(flag) && otherStrength(f) > strength(f)
  }

  /**
   * How far this finger is toward a pinch, 0 to 1: the runtime's pinch strength, scaled so 1 is
   * where a pinch starts ([startStrength], or the PINCHING signal).
   */
  fun progress(f: HandAim.Frame): Float {
    if (!withheld(f)) return if (f.has(flag)) 1f else (strength(f) / (startStrength ?: 1f)).coerceIn(0f, 1f)
    val gap = PalmShape.thumbToTipM(f.joints, tip) - f.radii[PalmShape.THUMB_TIP] - f.radii[tip]
    return (1f - (gap - TOUCH_SLACK_M) / (PROGRESS_FAR_M - TOUCH_SLACK_M)).coerceIn(0f, 1f)
  }

  /** Midway between the thumb tip and this fingertip, in the joints' space. */
  fun point(joints: FloatArray): FloatArray = PalmShape.pinchPoint(joints, tip)

  /** Where a hint for this finger sits: [aboveM] beyond its tip and [outM] off the palm. */
  fun hintPoint(joints: FloatArray, palm: FloatArray, aboveM: Float, outM: Float): FloatArray =
    PalmShape.aboveTip(joints, palm, tip, aboveM, outM)

  companion object {
    /** Pinch strength that keeps an already-held pinch down when the discrete signal drops. */
    const val HOLD_STRENGTH = 0.5f
    /**
     * [MiddlePinch] starts at this runtime strength. A wearer (2026-11-01) saw the hint almost full
     * while touching without the menu opening; once-a-second headset logs of a relaxed palm-facing
     * hand read 0-0.2. Near misses are logged with their peak strength for tuning.
     */
    const val MIDDLE_START_STRENGTH = 0.75f
    /** Joint fallback: surface gap that starts a pinch, and the larger one that ends it. */
    const val TOUCH_SLACK_M = 0.006f
    const val RELEASE_SLACK_M = 0.02f
    /** Joint fallback: surface gap at which [progress] starts rising from 0. */
    const val PROGRESS_FAR_M = 0.05f
  }
}

/**
 * The thumb-to-middle pinch: MIDDLE_PINCHING, or pinchStrengthMiddle at [MIDDLE_START_STRENGTH]. The palm
 * menus need the middle finger held against the thumb, where light touches often stop short of the
 * runtime's full-strength signal.
 */
object MiddlePinch : FingerPinch(
  name = "middle", tip = PalmShape.MIDDLE_TIP, flag = HandAim.MIDDLE_PINCHING,
  otherTip = PalmShape.INDEX_TIP, otherFlag = HandAim.INDEX_PINCHING, winsTies = false,
  startStrength = FingerPinch.MIDDLE_START_STRENGTH,
) {
  override fun strength(f: HandAim.Frame) = f.middleStrength
  override fun otherStrength(f: HandAim.Frame) = f.indexStrength
}

/**
 * The thumb-to-index pinch: INDEX_PINCHING and pinchStrengthIndex. With the left palm facing the
 * user this is also the runtime's app menu gesture ([HandAim.MENU_PRESSED], one frame); the pinch
 * signals themselves are what a held ring needs.
 */
object IndexPinch : FingerPinch(
  name = "index", tip = PalmShape.INDEX_TIP, flag = HandAim.INDEX_PINCHING,
  otherTip = PalmShape.MIDDLE_TIP, otherFlag = HandAim.MIDDLE_PINCHING, winsTies = true,
) {
  override fun strength(f: HandAim.Frame) = f.indexStrength
  override fun otherStrength(f: HandAim.Frame) = f.middleStrength
}
