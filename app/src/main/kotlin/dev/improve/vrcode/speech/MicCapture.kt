package dev.improve.vrcode.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlin.math.sqrt

/**
 * 16 kHz mono 16-bit microphone capture in 50 ms chunks. [onChunk] runs on the capture thread with
 * the PCM bytes and an RMS level in 0..1. [onSilenced] runs on the main thread whenever the system
 * starts or stops feeding this capture silence (`AudioRecordingConfiguration.isClientSilenced`: the
 * microphone privacy toggle, or another app taking the microphone over); it is not called for a
 * capture that is never silenced.
 */
class MicCapture(
  private val onSilenced: (silenced: Boolean) -> Unit = {},
  private val onChunk: (pcm: ByteArray, level: Float) -> Unit,
) {
  private var record: AudioRecord? = null
  private var thread: Thread? = null
  private val main = Handler(Looper.getMainLooper())

  @Volatile private var running = false

  /** What [onSilenced] last reported for the running capture. */
  private var silenced = false

  private val recordingCallback =
    object : AudioManager.AudioRecordingCallback() {
      // Registered on the record itself, so the list holds only its own configuration (empty once it stops).
      override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
        if (running) report(configs.any { it.isClientSilenced })
      }
    }

  /** Starts capturing; returns an error message instead when the microphone cannot be opened. */
  @SuppressLint("MissingPermission") // the activity checks RECORD_AUDIO before voice mode starts
  fun start(): String? {
    if (running) return null
    val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuffer <= 0) return "microphone unavailable"
    val r =
      try {
        AudioRecord(
          MediaRecorder.AudioSource.VOICE_RECOGNITION,
          SAMPLE_RATE,
          AudioFormat.CHANNEL_IN_MONO,
          AudioFormat.ENCODING_PCM_16BIT,
          maxOf(minBuffer, CHUNK_BYTES * 4),
        )
      } catch (e: RuntimeException) {
        return "microphone unavailable: ${e.message}"
      }
    if (r.state != AudioRecord.STATE_INITIALIZED) {
      r.release()
      return "microphone unavailable"
    }
    silenced = false
    r.registerAudioRecordingCallback({ main.post(it) }, recordingCallback)
    r.startRecording()
    record = r
    running = true
    // The callback reports later changes; the policy may already have silenced the capture at start.
    if (r.activeRecordingConfiguration?.isClientSilenced == true) report(true)
    thread =
      Thread({
        val buffer = ByteArray(CHUNK_BYTES)
        // An uncaught exception on this raw thread would terminate the whole headset app.
        try {
          while (running) {
            val n = r.read(buffer, 0, buffer.size)
            if (n > 0) runCatching { onChunk(buffer.copyOf(n), rms(buffer, n)) }
              .onFailure { android.util.Log.e("vrcode-mic", "Dropped a microphone chunk", it) }
          }
        } catch (error: Exception) {
          // read() throws once the recorder is released under a slow join in stop().
          if (running) android.util.Log.e("vrcode-mic", "Microphone capture stopped", error)
        }
      }, "vrcode-mic").apply { start() }
    return null
  }

  fun stop() {
    if (!running) return
    running = false
    thread?.join(500)
    thread = null
    record?.let {
      it.unregisterAudioRecordingCallback(recordingCallback)
      runCatching { it.stop() }
      it.release()
    }
    record = null
    silenced = false
  }

  /** Main thread: tells [onSilenced] about a change. */
  private fun report(value: Boolean) {
    if (value == silenced) return
    silenced = value
    onSilenced(value)
  }

  private fun rms(pcm: ByteArray, length: Int): Float {
    var sum = 0.0
    var i = 0
    while (i + 1 < length) {
      val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toDouble()
      sum += s * s
      i += 2
    }
    val samples = length / 2
    if (samples == 0) return 0f
    return (sqrt(sum / samples) / 8000.0).toFloat().coerceIn(0f, 1f)
  }

  companion object {
    const val SAMPLE_RATE = 16000
    const val CHUNK_BYTES = SAMPLE_RATE / 20 * 2 // 50 ms
  }
}
