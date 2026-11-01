package dev.improve.vrcode.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.improve.vrcode.speech.Kind
import dev.improve.vrcode.speech.MicCapture
import dev.improve.vrcode.speech.SpeechEvent
import dev.improve.vrcode.speech.SpeechLink
import dev.improve.vrcode.speech.joinText
import org.json.JSONObject

/**
 * One voice pane: the text of one or more dictations, staged until it is pasted or deleted. A
 * [Mode.Passthrough] pane (right fist) pastes what was said as is; a [Mode.Ai] pane (left fist)
 * pastes what the AI made of it for the target pane.
 */
class VoiceNote(val id: Int, val mode: Mode = Mode.Passthrough) {
  enum class Mode { Passthrough, Ai }

  enum class Phase { Listening, Finalizing, Thinking, Ready }

  var phase by mutableStateOf(Phase.Listening)
    internal set

  /** Text from finished dictations into this pane. */
  var committed by mutableStateOf("")
    internal set

  /** The dictation in progress: finalized words, then the recognizer's current guess. */
  var liveFinal by mutableStateOf("")
    internal set

  var liveVolatile by mutableStateOf("")
    internal set

  /** A problem to show on the pane (no Mac service, microphone unavailable, ...). */
  var notice by mutableStateOf<String?>(null)
    internal set

  /** False until the first dictation into this pane ends: until then the pane follows the fist. */
  var placed by mutableStateOf(false)
    internal set

  /** The utterance currently streaming into this pane. */
  internal var utterance = 0

  /** [Mode.Ai] panes: the AI's text for the transcript, once it answered. */
  var aiText by mutableStateOf<String?>(null)
    internal set

  /** The AI request in flight for this pane. */
  internal var aiRequest = 0

  /** What was said. */
  val text: String get() = joinText(joinText(committed, liveFinal), liveVolatile)

  /** What a paste types: what was said, or for an AI pane the AI's text (what was said if it failed). */
  val output: String get() = if (mode == Mode.Ai) aiText ?: text else text

  internal fun commitLive() {
    committed = text
    liveFinal = ""
    liveVolatile = ""
  }
}

/**
 * Voice mode: while the left fist is held the microphone streams to the Mac and the live
 * transcript shows in a voice pane. Every fist makes a new pane unless a pane was picked for
 * editing (a left pinch on it), in which case the dictation is added to that pane. Listening ends
 * only when the fist opens. Nothing reaches a terminal until a pane is pasted. Main thread only.
 */
class VoiceController(
  private val link: SpeechLink,
  /** The target pane's screen and title for AI panes, read when the AI is asked. */
  private val aiContext: () -> AiContext? = { null },
  private val micAllowed: () -> Boolean,
) {
  val notes = mutableStateListOf<VoiceNote>()

  /** The pane a paste takes: the newest one, or the one the user picked. */
  var selected by mutableStateOf<VoiceNote?>(null)
    private set

  /** The pane the next fist dictates into; null makes a new pane. */
  var editing by mutableStateOf<VoiceNote?>(null)
    private set

  var listening by mutableStateOf<VoiceNote?>(null)
    private set

  /** The live dictation's mode, null when not listening. */
  val listeningMode: VoiceNote.Mode? get() = listening?.mode

  var level by mutableFloatStateOf(0f)
    private set

  var serviceReady by mutableStateOf(false)
    private set

  /** Press Enter after pasting. */
  var enterAfterPaste by mutableStateOf(false)

  private val main = Handler(Looper.getMainLooper())
  private var nextNote = 0
  private var nextUtterance = 0

  @Volatile private var streamingId = 0

  // Microphone chunks of the current dictation loud enough to be speech, and its loudest chunk.
  // Written only by the capture thread while it runs, reset on the main thread before it starts.
  @Volatile private var loudChunks = 0
  @Volatile private var peakLevel = 0f
  private val mic =
    MicCapture { pcm, rms ->
      link.send(Kind.AUDIO, streamingId, pcm)
      if (rms >= SPEECH_LEVEL) loudChunks++
      if (rms > peakLevel) peakLevel = rms
      main.post { level = rms }
    }

  /** Listening, and nothing said yet in this dictation: ending it now would lose nothing. */
  val quiet: Boolean get() = listening?.let(::heardNothing) == true

  /**
   * Starts dictating into the pane picked for editing (which keeps its own mode), else into a new
   * pane of [mode].
   */
  fun startListening(mode: VoiceNote.Mode = VoiceNote.Mode.Passthrough) {
    if (listening != null) return
    // The Mac handles one utterance at a time; panes still finishing keep what they have.
    notes.filter { it.phase == VoiceNote.Phase.Finalizing }.forEach(::finish)
    val note = editing?.takeIf { it in notes } ?: newNote(mode)
    note.aiText = null // an AI pane being added to is asked again with the whole transcript
    editing = null
    selected = note
    note.notice = null
    val problem =
      when {
        !micAllowed() -> "Microphone permission is off for vrcode"
        !link.connected -> "Mac speech service not connected (${link.address ?: "no address"})"
        else -> null
      }
    if (problem != null) return fail(note, problem)
    val utterance = ++nextUtterance
    note.utterance = utterance
    streamingId = utterance
    loudChunks = 0
    peakLevel = 0f
    link.send(
      Kind.AUDIO_START,
      utterance,
      JSONObject().put("sampleRate", MicCapture.SAMPLE_RATE).put("channels", 1).put("encoding", "pcm_s16le"),
    )
    mic.start()?.let {
      link.send(Kind.AUDIO_CANCEL, utterance)
      return fail(note, it)
    }
    note.phase = VoiceNote.Phase.Listening
    listening = note
    Log.i(TAG, "voice: listening into pane ${note.id} (utterance $utterance)")
  }

  /**
   * The fist opened: stop the microphone and let the Mac finalize the text. When nothing was said,
   * end at once instead of waiting for the Mac: a new, empty pane disappears immediately; a pane
   * being added to keeps the text it already had.
   */
  fun stopListening() {
    val note = listening ?: return
    listening = null
    mic.stop() // joins the capture thread, so every chunk of this dictation is counted below
    level = 0f
    note.placed = true
    val peak = "%.3f".format(peakLevel)
    if (heardNothing(note)) {
      link.send(Kind.AUDIO_CANCEL, note.utterance)
      note.utterance = -1 // late transcripts are ignored
      Log.i(TAG, "voice: pane ${note.id} heard nothing (peak level $peak); ended at once")
      finish(note)
      return
    }
    Log.i(TAG, "voice: pane ${note.id} heard $loudChunks loud chunks (peak level $peak); finalizing")
    link.send(Kind.AUDIO_END, note.utterance)
    note.phase = VoiceNote.Phase.Finalizing
    val utterance = note.utterance
    // The Mac answers within a second; never leave a pane stuck if it does not.
    main.postDelayed({ if (note.phase == VoiceNote.Phase.Finalizing && note.utterance == utterance) finish(note) }, FINALIZE_TIMEOUT_MS)
  }

  fun onSpeechEvent(event: SpeechEvent) {
    when (event) {
      is SpeechEvent.Connected -> {
        serviceReady = event.ready
        Log.i(TAG, "speech service connected (locale ${event.locale}, ready ${event.ready})")
      }
      is SpeechEvent.AiResult -> {
        val note = notes.firstOrNull { it.aiRequest == event.id && it.phase == VoiceNote.Phase.Thinking } ?: return
        note.aiText = event.text
        note.phase = VoiceNote.Phase.Ready
        Log.i(TAG, "voice: AI answered for pane ${note.id} (${event.text.length} chars)")
      }
      is SpeechEvent.Disconnected -> {
        serviceReady = false
        for (note in notes.filter { it.phase == VoiceNote.Phase.Thinking }) aiFailed(note, "Mac service disconnected; the AI didn't answer")
        val note = listening ?: notes.firstOrNull { it.phase == VoiceNote.Phase.Finalizing } ?: return
        if (note == listening) {
          listening = null
          mic.stop()
        }
        note.notice = "Mac speech service disconnected"
        finish(note)
      }
      is SpeechEvent.Transcript -> {
        val note = notes.firstOrNull { it.utterance == event.id } ?: return
        if (note.phase == VoiceNote.Phase.Ready) return
        note.liveFinal = event.final
        note.liveVolatile = event.volatile
        if (event.done && note.phase == VoiceNote.Phase.Finalizing) finish(note)
      }
      is SpeechEvent.Failure -> {
        notes.firstOrNull { it.aiRequest == event.id && it.phase == VoiceNote.Phase.Thinking }?.let {
          aiFailed(it, event.message)
          return
        }
        val note = notes.firstOrNull { it.utterance == event.id } ?: listening ?: return
        note.notice = event.message
        if (note == listening) {
          listening = null
          mic.stop()
          note.placed = true
        }
        finish(note)
      }
    }
  }

  /** Picks [note] for pasting and for the next fist to add to. */
  fun select(note: VoiceNote) {
    if (note !in notes || note == listening) return
    selected = note
    editing = note
    Log.i(TAG, "voice: pane ${note.id} selected for editing")
  }

  /** Hands [note]'s text to the caller and removes the pane; null when there is nothing to paste. */
  fun take(note: VoiceNote? = selected): String? {
    if (note == null || note !in notes || note == listening || note.phase == VoiceNote.Phase.Thinking) return null
    val text = note.output
    if (text.isBlank()) return null
    remove(note)
    return text
  }

  fun remove(note: VoiceNote) {
    if (note == listening) {
      listening = null
      mic.stop()
      level = 0f
      link.send(Kind.AUDIO_CANCEL, note.utterance)
    }
    note.utterance = -1 // late transcripts are ignored
    notes.remove(note)
    if (editing == note) editing = null
    if (selected == note) selected = notes.lastOrNull { it != listening }
  }

  /** Keyboard edits to [note] (the palm keyboard types into the pane picked for editing). */
  fun typeInto(note: VoiceNote, text: String) {
    if (note !in notes || note == listening) return
    note.aiText?.let {
      note.aiText = it + text
      return
    }
    note.commitLive()
    note.committed += text
  }

  fun backspaceIn(note: VoiceNote) {
    if (note !in notes || note == listening) return
    note.aiText?.let {
      note.aiText = it.dropLast(1)
      return
    }
    note.commitLive()
    note.committed = note.committed.dropLast(1)
  }

  /** Return on the keyboard: done editing; typing goes back to the focused terminal. */
  fun stopEditing() {
    editing = null
  }

  fun shutdown() {
    mic.stop()
  }

  /** No words from the recognizer and too few loud microphone chunks to have been speech. */
  private fun heardNothing(note: VoiceNote): Boolean =
    note.liveFinal.isBlank() && note.liveVolatile.isBlank() && loudChunks < SPEECH_CHUNKS

  private fun newNote(mode: VoiceNote.Mode): VoiceNote {
    val note = VoiceNote(++nextNote, mode)
    notes.add(note)
    // Keep a handful of panes; the oldest unselected one goes first.
    while (notes.size > MAX_PANES) {
      val oldest = notes.firstOrNull { it != note && it != selected } ?: break
      remove(oldest)
    }
    return note
  }

  private fun fail(note: VoiceNote, message: String) {
    note.notice = message
    note.placed = true
    finish(note)
  }

  private fun finish(note: VoiceNote) {
    note.commitLive()
    if (note.mode == VoiceNote.Mode.Ai && note.text.isNotBlank() && note.notice == null && note in notes) {
      askAi(note)
      return
    }
    note.phase = VoiceNote.Phase.Ready
    if (note.text.isBlank()) {
      if (note.notice == null) {
        remove(note) // nothing was said
      } else {
        // Show the problem for a moment, then drop the empty pane.
        main.postDelayed({ if (note in notes && note.text.isBlank() && note != listening) remove(note) }, NOTICE_MS)
      }
    }
    Log.i(TAG, "voice: pane ${note.id} holds ${note.text.length} chars")
  }

  /** Sends [note]'s transcript and the target pane's screen to the Mac's AI; the pane shows Thinking. */
  private fun askAi(note: VoiceNote) {
    val id = ++nextUtterance // shares the utterance id space, so AI and audio frames never collide
    note.aiRequest = id
    note.aiText = null
    note.phase = VoiceNote.Phase.Thinking
    val context = aiContext()
    val sent =
      link.send(
        Kind.AI_REQUEST,
        id,
        JSONObject().put("transcript", note.text).put("screen", context?.screen ?: "").put("title", context?.title ?: ""),
      )
    if (!sent) return aiFailed(note, "Mac service not connected; the AI didn't answer")
    Log.i(TAG, "voice: asking the AI for pane ${note.id} (${context?.title ?: "no pane focused"})")
    main.postDelayed({ if (note.phase == VoiceNote.Phase.Thinking && note.aiRequest == id) aiFailed(note, "The AI took too long") }, AI_TIMEOUT_MS)
  }

  /** The AI didn't answer: the pane keeps what was said (a paste types it) and shows why. */
  private fun aiFailed(note: VoiceNote, message: String) {
    note.notice = message
    note.phase = VoiceNote.Phase.Ready
    Log.i(TAG, "voice: AI failed for pane ${note.id}: $message")
  }

  private companion object {
    const val TAG = "vrcode"
    const val FINALIZE_TIMEOUT_MS = 4000L
    const val AI_TIMEOUT_MS = 30_000L
    const val NOTICE_MS = 4000L
    const val MAX_PANES = 8

    // Two 50 ms chunks at or above this level (MicCapture's 0..1 RMS, about -43 dBFS) count as
    // having said something. An estimate, not yet measured on the Quest: stopListening logs each
    // dictation's peak level for tuning. Kept low on purpose: a false "said something" only keeps
    // the old wait for the Mac, a false "nothing" would drop words.
    const val SPEECH_LEVEL = 0.03f
    const val SPEECH_CHUNKS = 2
  }
}

/** What the AI sees of the target pane: its visible text and its window title. */
class AiContext(val screen: String, val title: String)
