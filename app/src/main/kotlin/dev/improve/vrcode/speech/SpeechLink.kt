package dev.improve.vrcode.speech

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.improve.vrcode.voice.Keystroke
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

sealed interface SpeechEvent {
  data class Connected(val ready: Boolean, val locale: String) : SpeechEvent

  data class Disconnected(val reason: String) : SpeechEvent

  data class Transcript(val id: Int, val final: String, val volatile: String, val done: Boolean) : SpeechEvent

  data class Failure(val id: Int, val message: String) : SpeechEvent

  /** The AI's keystrokes for an AI_REQUEST [id]: text and key presses, in order; [summary] says what they do (agent only). */
  data class AiResult(val id: Int, val keys: List<Keystroke>, val summary: String = "") : SpeechEvent

  /** What the agent for AI_REQUEST [id] is doing: a [kind] of "status", "thinking" (a chunk) or "step" (a tool call). */
  data class AiProgress(val id: Int, val kind: String, val text: String) : SpeechEvent
}

/**
 * The headset's connection to the vrcode Mac service. Reconnects with backoff until [close];
 * [onEvent] runs on the main thread. [send] never touches the network: it queues the frame for the
 * connection's writer coroutine, so it is safe from any thread including the main thread, and it
 * drops frames while disconnected or when the queue is full.
 */
class SpeechLink(
  private val scope: CoroutineScope,
  private val onFrame: (Frame) -> Unit = {},
  private val onEvent: (SpeechEvent) -> Unit,
) {
  private val main = Handler(Looper.getMainLooper())
  private val sendLock = Any()
  private var socket: Socket? = null
  private var outbox: Channel<ByteArray>? = null
  private var sequence = 0
  private var pendingControlBytes = 0
  private var job: Job? = null
  @Volatile private var generation = 0

  @Volatile
  var connected: Boolean = false
    private set

  var address: String? = null
    private set

  fun connect(host: String, port: Int) {
    close()
    address = "$host:$port"
    val current = generation
    job =
      scope.launch(Dispatchers.IO) {
        var backoff = 500L
        while (isActive) {
          var writer: Job? = null
          var attempt: Socket? = null
          try {
            val s = Socket()
            attempt = s
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 3000)
            ensureActive()
            val output = BufferedOutputStream(s.getOutputStream())
            val queue = Channel<ByteArray>(OUTBOX_CAPACITY)
            synchronized(sendLock) {
              if (current != generation) throw CancellationException("Connection replaced")
              socket = s
              outbox = queue
              sequence = 0
            }
            writer =
              launch {
                try {
                  for (frame in queue) {
                    output.write(frame)
                    output.flush()
                  }
                } catch (e: IOException) {
                  runCatching { s.close() } // the reader then fails and reconnects
                }
              }
            send(Kind.HELLO, 0, JSONObject().put("client", "vrcode").put("protocol", SpeechWire.VERSION))
            val input = DataInputStream(BufferedInputStream(s.getInputStream()))
            backoff = 500L
            while (isActive) dispatch(SpeechWire.read(input), current)
          } catch (e: IOException) {
            if (connected) post(SpeechEvent.Disconnected(e.message ?: "connection lost"), current)
            Log.i(TAG, "speech service $host:$port: ${e.message}")
          } finally {
            writer?.cancel()
            drop(attempt)
          }
          delay(backoff)
          backoff = (backoff * 2).coerceAtMost(5000L)
        }
      }
  }

  fun send(kind: Int, id: Int, json: JSONObject): Boolean = send(kind, id, json.toString().toByteArray())

  fun send(kind: Int, id: Int, body: ByteArray = ByteArray(0)): Boolean =
    synchronized(sendLock) {
      // Sequence numbers and queue order are assigned together, so frames leave in send order.
      val queue = outbox ?: return false
      queue.trySend(SpeechWire.encode(kind, id, ++sequence, body)).isSuccess
    }

  fun close() {
    generation++
    address = null
    job?.cancel()
    job = null
    drop()
  }

  private fun dispatch(frame: Frame, current: Int) {
    if (current != generation) return
    val controlBytes = if (frame.kind == Kind.CMUX_EVENT) frame.body.size else 0
    synchronized(sendLock) {
      if (pendingControlBytes + controlBytes > 8 * 1024 * 1024) throw IOException("Control consumer is too slow")
      pendingControlBytes += controlBytes
    }
    main.post {
      try { if (current == generation) onFrame(frame) }
      finally { synchronized(sendLock) { pendingControlBytes -= controlBytes } }
    }
    val json = runCatching { JSONObject(String(frame.body)) }.getOrNull() ?: JSONObject()
    when (frame.kind) {
      Kind.WELCOME -> {
        connected = true
        post(SpeechEvent.Connected(json.optBoolean("ready", true), json.optString("locale")), current)
      }
      Kind.TRANSCRIPT ->
        post(SpeechEvent.Transcript(frame.id, json.optString("final"), json.optString("volatile"), json.optBoolean("done")), current)
      Kind.ERROR -> post(SpeechEvent.Failure(frame.id, json.optString("message", "speech service error")), current)
      Kind.AI_RESULT -> post(SpeechEvent.AiResult(frame.id, Keystroke.fromAiResult(json), json.optString("summary")), current)
      Kind.AI_PROGRESS -> post(SpeechEvent.AiProgress(frame.id, json.optString("kind"), json.optString("text")), current)
    }
  }

  private fun drop(expected: Socket? = null) {
    synchronized(sendLock) {
      if (expected != null && socket !== expected) {
        runCatching { expected.close() }
        return
      }
      runCatching { socket?.close() }
      socket = null
      outbox?.close()
      outbox = null
    }
    connected = false
  }

  private fun post(event: SpeechEvent, current: Int = generation) {
    main.post { if (current == generation) onEvent(event) }
  }

  private companion object {
    const val TAG = "vrcode"
    /** About 12 s of 50 ms audio chunks; beyond that the link is stalled and frames are dropped. */
    const val OUTBOX_CAPACITY = 256
  }
}
