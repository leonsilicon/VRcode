package dev.improve.vrcode.voice

/**
 * What a voice pane says when vrcode's microphone cannot hear the user. A pane with a notice shows
 * at once, before any word is transcribed (see [MicPanePlacement.shown]); without one, a muted mic
 * would leave a closed fist showing only the prop while it records silence.
 */
internal object MicNotice {
  const val NO_PERMISSION = "Microphone permission is off for vrcode"

  /** `AudioManager.isMicrophoneMute()`: the Quest's microphone is muted for every app. */
  const val MUTED = "The Quest's microphone is muted"

  /**
   * The system is feeding vrcode's capture silence (`AudioRecordingConfiguration.isClientSilenced`):
   * the microphone privacy toggle, or another app that took the microphone over.
   */
  const val SILENCED = "The Quest is silencing the microphone (muted, or another app is using it)"

  /** Why a fist cannot start listening, in the order the user would fix them; null when it can. */
  fun atStart(allowed: Boolean, muted: Boolean, connected: Boolean, address: String?): String? =
    when {
      !allowed -> NO_PERMISSION
      muted -> MUTED
      !connected -> "Mac speech service not connected (${address ?: "no address"})"
      else -> null
    }

  /**
   * The listening pane's notice once the system starts or stops silencing the capture. Listening
   * goes on, so the words come back if the user unmutes with the fist still closed; only the
   * [SILENCED] notice is ever set or cleared here.
   */
  fun whileListening(notice: String?, silenced: Boolean): String? =
    when {
      silenced -> SILENCED
      notice == SILENCED -> null
      else -> notice
    }
}
