package dev.improve.vrcode

import android.util.Log

/**
 * Keeps one failing feature from taking down the whole headset app. A failure that repeats every
 * frame is logged once with its stack trace, then only as a periodic count.
 */
internal class FailureLog(
  private val tag: String,
  private val log: (String, Throwable?) -> Unit = { message, error -> Log.e(tag, message, error) },
) {
  private val counts = LinkedHashMap<String, Int>()

  fun report(site: String, error: Throwable) {
    val key = "$site|${error.javaClass.name}|${error.message}"
    if (key !in counts && counts.size >= MAX_KEYS) counts.remove(counts.keys.first())
    val count = (counts[key] ?: 0) + 1
    counts[key] = count
    when {
      count == 1 -> log("$site failed; VRcode keeps running", error)
      count % REPEAT_INTERVAL == 0 -> log("$site failed $count times: ${error.javaClass.simpleName}: ${error.message}", null)
    }
  }

  private companion object {
    const val MAX_KEYS = 64
    /** About ten seconds of frames at 72-120 Hz. */
    const val REPEAT_INTERVAL = 900
  }
}
