package dev.improve.vrcode.hands

/**
 * One hand's palm menu gesture: with the palm toward the user, a pinch of [finger] to the thumb
 * opens the menu at the pinch point; moving the hand while pinched picks an item ([PieSelection]);
 * releasing ends it. Tracking loss (longer than [TRACKING_GAP_MS]: the runtime drops single frames),
 * the other finger's pinch taking over, or a fist cancels, and a cancelled pinch needs a fresh one.
 * Works for either hand. [MiddlePinch] (index still up) drives the palm menus on both hands;
 * [IndexPinch] drives the left hand's quick inputs.
 *
 * Palm-facing is the runtime's SYSTEM_GESTURE flag (its own palm-toward-the-headset detection) or
 * the palm joint's normal within [OPEN_FACING_DEGREES] of the head ([PalmShape]); it only gates the
 * start, so turning the hand during a selection does not close the menu. The pinch is the runtime's
 * ([FingerPinch]). Moving the hand makes the runtime's pinch readings flicker for a frame or two, so
 * a held pinch only ends after [RELEASE_GRACE_MS] of readings that say so.
 */
class HandMenuGesture(val finger: FingerPinch = MiddlePinch) {
  enum class Event { None, Opened, Released, Cancelled }

  /** Why the last [Event.Cancelled] happened, for the log. */
  enum class CancelReason { None, Fist, OtherFinger, TrackingLost }

  var open = false
    private set

  /** The palm faces the user (looser than the opening threshold): palm-away gestures stand down. */
  var palmFacing = false
    private set

  /** The pinch point in VIEW space for the current frame while tracked. */
  var point: FloatArray? = null
    private set

  /**
   * Show the hint on the pinching fingertip: the palm faces the user closely enough for a pinch
   * to open the menu, the menu is not open, and no pinch is still held from the last one.
   */
  var hint = false
    private set

  /** Where the hint goes, in VIEW space, while tracked. */
  var hintPoint: FloatArray? = null
    private set

  /** 0 with the thumb and [finger] apart, rising to 1 at a pinch. */
  var progress = 0f
    private set

  /** The last frame's measurements, for diagnostics. */
  var facingDegrees = 0f
    private set
  var source = FingerPinch.Source.None
    private set
  var fist = false
    private set
  var cancelReason = CancelReason.None
    private set

  /** Whether a pinch is held (debounced), including one still held after its menu closed. */
  var down = false
    private set

  /**
   * This frame's own pinch reading, before the release grace. While it is off the fingers may be
   * separating, which moves the pinch point, so the selection should hold still.
   */
  var pinchReading = false
    private set

  private var needsRelease = false
  private var upSinceMs = NOT_UP
  private var lastTrackedMs = Long.MIN_VALUE / 2

  /** Feeds one frame at [nowMs]; null means the hand is not tracked. */
  fun update(frame: HandAim.Frame?, nowMs: Long): Event {
    if (frame == null && nowMs - lastTrackedMs <= TRACKING_GAP_MS) return Event.None // hold through a brief dropout
    if (frame == null) {
      point = null
      hintPoint = null
      hint = false
      progress = 0f
      palmFacing = false
      source = FingerPinch.Source.None
      if (down) needsRelease = true
      down = false
      pinchReading = false
      upSinceMs = NOT_UP
      if (!open) return Event.None
      open = false
      needsRelease = true
      cancelReason = CancelReason.TrackingLost
      return Event.Cancelled
    }
    lastTrackedMs = nowMs
    val joints = frame.joints
    point = finger.point(joints)
    facingDegrees = PalmShape.facingDegrees(joints, frame.palm)
    source = finger.source(frame)
    val systemPose = frame.has(HandAim.SYSTEM_GESTURE)
    val canOpen = facingDegrees <= OPEN_FACING_DEGREES || systemPose
    palmFacing = facingDegrees <= KEEP_FACING_DEGREES || systemPose
    // A fist rolls the fingers onto the thumb too; it belongs to voice, not to the menu.
    fist = FistShape.isFist(joints, wasFist = fist)
    val otherPinch = finger.otherDominant(frame)
    val otherTakesOver = finger.otherTakesOver(frame)
    val wasDown = down
    // A new pinch must not be the other finger's; a held one only yields once the other takes over.
    val reading = !fist && finger.down(frame, wasDown) && !(if (wasDown) otherTakesOver else otherPinch)
    pinchReading = reading
    if (reading || !wasDown) upSinceMs = NOT_UP else if (upSinceMs == NOT_UP) upSinceMs = nowMs
    down = reading || (wasDown && nowMs - upSinceMs < RELEASE_GRACE_MS)
    progress = if (fist) 0f else finger.progress(frame)
    hintPoint = finger.hintPoint(joints, frame.palm, backM = HINT_BACK_M, outM = HINT_OUT_M)
    hint = canOpen && !otherPinch && !fist && !open && !needsRelease

    if (open) {
      if (down) return Event.None
      open = false
      cancelReason =
        when {
          fist -> CancelReason.Fist
          otherTakesOver -> CancelReason.OtherFinger
          else -> return Event.Released
        }
      needsRelease = true
      return Event.Cancelled
    }
    if (needsRelease) {
      if (!down) needsRelease = false
      return Event.None
    }
    if (down && !wasDown && canOpen) {
      open = true
      hint = false
      cancelReason = CancelReason.None
      return Event.Opened
    }
    return Event.None
  }

  /** Closes the menu without a release, e.g. after an item ran or the vrcode menu opened. */
  fun cancel() {
    if (open || down) needsRelease = true
    open = false
    hint = false
  }

  companion object {
    const val OPEN_FACING_DEGREES = 55f
    const val KEEP_FACING_DEGREES = 75f
    /** The hint's centre: on the fingertip's pad, back from the tip and just off the skin. */
    const val HINT_BACK_M = 0.01f
    const val HINT_OUT_M = 0.009f
    /** Untracked frames shorter than this keep the menu and pinch as they were. */
    const val TRACKING_GAP_MS = 200L
    /**
     * A held pinch ends only after its readings say so for this long. On-device (2026-11-01), the
     * runtime took up to 78 ms after a joint-fallback open to report the pinch; releasing only
     * cancels, so a longer grace costs little. Stays below [TRACKING_GAP_MS].
     */
    const val RELEASE_GRACE_MS = 150L
    private const val NOT_UP = Long.MIN_VALUE
  }
}
