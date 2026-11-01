package dev.improve.vrcode.windows

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One invocation keeps its own target and result, even while focus or another action changes. */
internal class TerminalActionNotice<T>(target: T?, val title: String, message: String) {
  enum class State { Running, Done, Failed }

  var target by mutableStateOf(target)
  var message by mutableStateOf(message)
  var state by mutableStateOf(State.Running)
    private set
  private var expiresAt: Long? = null

  fun finish(message: String, failed: Boolean, now: Long) {
    this.message = message
    state = if (failed) State.Failed else State.Done
    expiresAt = now + if (failed) 10_000L else 0L
  }

  fun expired(now: Long) = expiresAt?.let { now >= it } == true
}

internal class TerminalActionNotices<T> {
  val entries = mutableStateListOf<TerminalActionNotice<T>>()

  fun start(target: T?, title: String, message: String): TerminalActionNotice<T> {
    entries.removeAll { it.target == target && it.state != TerminalActionNotice.State.Running }
    return TerminalActionNotice(target, title, message).also(entries::add)
  }

  /** Completion is evident in the terminal; only failures need a result notice. */
  fun finish(notice: TerminalActionNotice<T>, message: String, failed: Boolean, now: Long) {
    notice.finish(message, failed, now)
    if (!failed) entries.remove(notice)
  }

  fun prune(targets: Collection<T>, now: Long) {
    entries.removeAll { it.expired(now) || (it.target != null && it.target !in targets) }
  }
}
