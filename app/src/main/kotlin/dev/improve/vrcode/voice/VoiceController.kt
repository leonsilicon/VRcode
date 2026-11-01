package dev.improve.vrcode.voice

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.improve.vrcode.AiModel
import dev.improve.vrcode.AiThinking
import dev.improve.vrcode.speech.Kind
import dev.improve.vrcode.speech.MicCapture
import dev.improve.vrcode.speech.SpeechEvent
import dev.improve.vrcode.speech.SpeechLink
import dev.improve.vrcode.speech.joinText
import org.json.JSONArray
import org.json.JSONObject

/**
 * One voice pane: the text of a dictation, typed into the focused pane as soon as it is ready. A
 * [Mode.Passthrough] pane (right fist) types what was said as is; a [Mode.Ai] pane (left fist)
 * sends the keystrokes the AI made of it for the target pane, Enter included when the AI chose to
 * press it. A pane that could not be typed (no terminal, the AI failed, the Mac dropped) stays
 * staged with a notice until it is pasted or deleted.
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

  /**
   * [Mode.Ai] panes: the utterances whose audio holds what was said here, in order. The Mac keeps
   * their recordings (AUDIO_START `ai`) and the AI request names them, so a model hears what was
   * said (Claude Code, not the on-device recognizer's "claw code"). Typing into the pane clears
   * it, since the recordings no longer hold everything in it: then the AI gets the text.
   */
  internal val recorded = mutableListOf<Int>()

  /** [Mode.Ai] panes: the AI's keystrokes for the transcript (text and keys such as Enter), once it answered. */
  var aiKeys by mutableStateOf<List<Keystroke>?>(null)
    internal set

  /** The AI request in flight for this pane. */
  internal var aiRequest = 0

  /** When the Mac last said something about [aiRequest] (`SystemClock.uptimeMillis`). */
  internal var aiActiveAt = 0L

  /**
   * [Mode.Ai] panes asked with thinking on ([AiThinking.agent]): the Mac runs the provider's agent,
   * which works in the target terminal itself, and [trace], [aiStatus] and then [aiSummary] show
   * what it does on the thinking panel.
   */
  var agent by mutableStateOf(false)
    internal set

  /** The agent's run in order: its thinking (adjacent chunks joined) and each tool call. */
  val trace = mutableStateListOf<AiTrace>()

  /** One line about the run ("Claude Code in ~/project"). */
  var aiStatus by mutableStateOf("")
    internal set

  /** What the agent did, in a sentence, once it finished. */
  var aiSummary by mutableStateOf("")
    internal set

  /** Adds one AI_PROGRESS chunk to [trace]. */
  internal fun addProgress(kind: String, text: String) {
    when (kind) {
      "status" -> aiStatus = text
      "thinking" -> {
        val last = trace.lastOrNull()
        if (last is AiTrace.Thought) trace[trace.size - 1] = AiTrace.Thought((last.text + text).takeLast(MAX_THOUGHT))
        else if (text.isNotBlank()) trace.add(AiTrace.Thought(text.trimStart().takeLast(MAX_THOUGHT)))
      }
      "step" -> trace.add(AiTrace.Step(text))
      else -> return
    }
    while (trace.size > MAX_TRACE) trace.removeAt(0)
  }

  /** The Mac's model heard [heard] in the [recorded] audio: the pane shows that instead of the live transcript. */
  internal fun hear(heard: String) {
    committed = heard
    liveFinal = ""
    liveVolatile = ""
  }

  internal fun clearAgent() {
    trace.clear()
    aiStatus = ""
    aiSummary = ""
  }

  private companion object {
    const val MAX_TRACE = 40
    const val MAX_THOUGHT = 2000
  }

  /** What was said. */
  val text: String get() = joinText(joinText(committed, liveFinal), liveVolatile)

  /**
   * What a paste types: what was said, or for an AI pane the AI's keystrokes (what was said, with no
   * Enter, if it failed).
   */
  val output: List<Keystroke> get() = if (mode == Mode.Ai) aiKeys ?: Keystroke.text(text) else Keystroke.text(text)

  internal fun commitLive() {
    committed = text
    liveFinal = ""
    liveVolatile = ""
  }
}

/**
 * Voice mode: while a fist is held the microphone streams to the Mac and the live transcript
 * shows in a voice pane. Every fist makes a new pane unless a pane was picked for editing (a left
 * pinch on it), in which case the dictation is added to that pane. Listening ends when the fist
 * opens (or Cancel on the pane); then, like dictation on a Mac, the text goes straight to the
 * focused pane through [deliver]: a dictation once the Mac finalizes it, an AI pane once the AI
 * answers. Main thread only.
 */
class VoiceController(
  private val link: SpeechLink,
  /** The target pane's screen and title for [note], an AI pane, read when the AI is asked. */
  private val aiContext: (note: VoiceNote) -> AiContext? = { null },
  /**
   * Types [note]'s ready output into its target pane (taking it with [take]); false when there is
   * no terminal to type into, which leaves the pane staged with a notice.
   */
  private val deliver: (note: VoiceNote) -> Boolean = { false },
  /** The AI mic's provider and model, chosen in Settings. */
  private val aiModel: () -> AiModel = { AiModel.ClaudeSonnet },
  /** Whether the AI mic thinks first (the Mac runs the provider's agent), chosen in Settings. */
  private val aiThinking: () -> AiThinking = { AiThinking.Off },
  /** Whether the Quest's microphone is muted for every app (`AudioManager.isMicrophoneMute`). */
  private val micMuted: () -> Boolean = { false },
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

  /**
   * Press Enter after typing a dictation: the right (text) mic's toggle on its pane. The AI mic has
   * none: the AI's keystrokes include Enter when it decides the command or prompt should be sent.
   */
  var enterAfterDictation by mutableStateOf(false)

  private val main = Handler(Looper.getMainLooper())
  private var nextNote = 0
  private var nextUtterance = 0

  @Volatile private var streamingId = 0

  // Microphone chunks of the current dictation loud enough to be speech, and its loudest chunk.
  // Written only by the capture thread while it runs, reset on the main thread before it starts.
  @Volatile private var loudChunks = 0
  @Volatile private var peakLevel = 0f
  private val mic =
    MicCapture(onSilenced = ::onMicSilenced) { pcm, rms ->
      link.send(Kind.AUDIO, streamingId, pcm)
      if (rms >= SPEECH_LEVEL) loudChunks++
      if (rms > peakLevel) peakLevel = rms
      main.post { level = rms }
    }

  /** Listening, and nothing said yet in this dictation: ending it now would lose nothing. */
  val quiet: Boolean get() = listening?.let(::heardNothing) == true

  /** Whether the system is feeding the running capture silence; reset when a dictation starts. */
  private var micSilenced = false

  /**
   * The system started or stopped silencing the microphone mid-dictation: the live pane says so at
   * once (a pane with a notice shows before any word), and listening goes on in case the user
   * unmutes. A fist that opens while silenced leaves what was heard staged, not typed: part of it
   * may be missing.
   */
  private fun onMicSilenced(silenced: Boolean) {
    micSilenced = silenced
    val note = listening ?: return
    note.notice = MicNotice.whileListening(note.notice, silenced)
    Log.i(TAG, "voice: microphone ${if (silenced) "silenced by the system" else "no longer silenced"} (pane ${note.id})")
  }

  /**
   * Starts dictating into the pane picked for editing (which keeps its own mode), else into a new
   * pane of [mode].
   */
  fun startListening(mode: VoiceNote.Mode = VoiceNote.Mode.Passthrough) {
    if (listening != null) return
    // The Mac handles one utterance at a time; panes still finishing keep what they have.
    notes.filter { it.phase == VoiceNote.Phase.Finalizing }.forEach(::finish)
    val note = editing?.takeIf { it in notes } ?: newNote(mode)
    cancelAi(note) // an agent still answering this pane is stopped
    note.aiKeys = null // an AI pane being added to is asked again with the whole transcript
    editing = null
    selected = note
    note.notice = null
    val problem = MicNotice.atStart(micAllowed(), micMuted(), link.connected, link.address)
    if (problem != null) {
      Log.i(TAG, "voice: pane ${note.id} can't listen: $problem")
      return fail(note, problem)
    }
    val utterance = ++nextUtterance
    note.utterance = utterance
    streamingId = utterance
    loudChunks = 0
    peakLevel = 0f
    micSilenced = false
    link.send(Kind.AUDIO_START, utterance, audioFormat(note.mode))
    mic.start()?.let {
      link.send(Kind.AUDIO_CANCEL, utterance)
      return fail(note, it)
    }
    if (note.mode == VoiceNote.Mode.Ai) note.recorded += utterance
    note.phase = VoiceNote.Phase.Listening
    listening = note
    note.notice = MicNotice.whileListening(note.notice, micSilenced) // silenced from the start
    Log.i(TAG, "voice: listening into pane ${note.id} (utterance $utterance${if (micSilenced) ", silenced by the system" else ""})")
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
      note.recorded.remove(note.utterance)
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

  /**
   * The fist holding [note]'s mic opened (after [stopListening]). A pane with nothing to paste that
   * only says why the mic could not listen (no Mac service, no permission, a Mac failure) goes with
   * the mic, instead of outliving it for [NOTICE_MS]. Panes still finishing, being answered, or
   * holding text are left alone.
   */
  fun release(note: VoiceNote) {
    if (note !in notes || note == listening || note.phase != VoiceNote.Phase.Ready || note.output.isNotEmpty()) return
    Log.i(TAG, "voice: pane ${note.id} only held a notice (${note.notice}); dismissed with the mic")
    remove(note)
  }

  /**
   * Cancel on the live pane: stop listening and drop what this dictation said, so nothing is
   * typed when the fist opens. A pane being added to keeps the text it already had, staged.
   */
  fun cancelListening() {
    val note = listening ?: return
    listening = null
    mic.stop()
    level = 0f
    link.send(Kind.AUDIO_CANCEL, note.utterance)
    note.recorded.remove(note.utterance)
    note.utterance = -1 // late transcripts are ignored
    note.liveFinal = ""
    note.liveVolatile = ""
    note.placed = true
    note.phase = VoiceNote.Phase.Ready
    if (note.text.isBlank()) remove(note)
    Log.i(TAG, "voice: pane ${note.id} cancelled while listening")
  }

  /**
   * Clear on the live pane: drop everything said so far and keep listening into the same pane.
   * The Mac transcribes one utterance at a time, so this starts a fresh one; chunks still tagged
   * with the old id are dropped there. The loud-chunk count is left alone (the capture thread
   * owns it while it runs): at worst a cleared dictation waits for the Mac instead of ending at once.
   */
  fun clearListening() {
    val note = listening ?: return
    link.send(Kind.AUDIO_CANCEL, note.utterance)
    val utterance = ++nextUtterance
    note.committed = ""
    note.liveFinal = ""
    note.liveVolatile = ""
    note.aiKeys = null
    note.notice = MicNotice.whileListening(null, micSilenced) // still silenced: Clear keeps saying so
    note.utterance = utterance
    note.recorded.clear()
    if (note.mode == VoiceNote.Mode.Ai) note.recorded += utterance
    link.send(Kind.AUDIO_START, utterance, audioFormat(note.mode))
    streamingId = utterance
    Log.i(TAG, "voice: pane ${note.id} cleared while listening (utterance $utterance)")
  }

  fun onSpeechEvent(event: SpeechEvent) {
    when (event) {
      is SpeechEvent.Connected -> {
        serviceReady = event.ready
        Log.i(TAG, "speech service connected (locale ${event.locale}, ready ${event.ready})")
      }
      is SpeechEvent.AiProgress -> {
        val note = notes.firstOrNull { it.aiRequest == event.id && it.phase == VoiceNote.Phase.Thinking } ?: return
        if (event.kind == "heard") note.hear(event.text) else note.addProgress(event.kind, event.text)
        note.aiActiveAt = SystemClock.uptimeMillis()
      }
      is SpeechEvent.AiResult -> {
        val note = notes.firstOrNull { it.aiRequest == event.id && it.phase == VoiceNote.Phase.Thinking } ?: return
        note.aiKeys = event.keys
        note.aiSummary = event.summary
        note.phase = VoiceNote.Phase.Ready
        if (event.acted && event.keys.isEmpty()) {
          // The agent already worked in the terminal: nothing is left to type. Its thinking panel
          // keeps showing the summary for a while; the voice pane goes.
          Log.i(TAG, "voice: agent finished for pane ${note.id}: ${event.summary.take(120)}")
          remove(note)
          return
        }
        val pressed = event.keys.filterIsInstance<Keystroke.Press>().map { it.chord }
        Log.i(TAG, "voice: AI answered for pane ${note.id} (${event.keys.size} steps; keys $pressed)")
        insert(note)
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

  /**
   * Debug builds: an AI pane holding [text] as if it had just been said, asked at once. [target]
   * runs first, so the host can record the pane's target before the AI is asked.
   */
  fun askAiWith(text: String, target: (VoiceNote) -> Unit = {}) {
    val note = newNote(VoiceNote.Mode.Ai)
    note.committed = text
    note.placed = true
    selected = note
    target(note)
    finish(note)
  }

  /** Picks [note] for pasting and for the next fist to add to. */
  fun select(note: VoiceNote) {
    if (note !in notes || note == listening) return
    selected = note
    editing = note
    Log.i(TAG, "voice: pane ${note.id} selected for editing")
  }

  /**
   * Hands [note]'s keystrokes to the caller and removes the pane; null when there is nothing to
   * paste. A dictation ends with Enter when the text mic's toggle is on; an AI pane's keystrokes are
   * sent as the AI gave them.
   */
  fun take(note: VoiceNote? = selected): List<Keystroke>? {
    if (note == null || note !in notes || note == listening || note.phase == VoiceNote.Phase.Thinking) return null
    val steps = note.output
    if (steps.isEmpty()) return null
    remove(note)
    return if (note.mode == VoiceNote.Mode.Passthrough && enterAfterDictation) steps + Keystroke.Enter else steps
  }

  /**
   * Stop on the thinking panel: the Mac stops the agent and nothing is typed. The pane keeps what
   * was said, staged, like a failed answer (a paste types it, without Enter).
   */
  fun stopAi(note: VoiceNote) {
    if (note.phase != VoiceNote.Phase.Thinking) return
    cancelAi(note)
    aiFailed(note, AI_STOPPED)
  }

  fun remove(note: VoiceNote) {
    cancelAi(note)
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
    note.aiKeys?.let {
      note.aiKeys = Keystroke.append(it, text)
      return
    }
    note.commitLive()
    note.committed += text
    note.recorded.clear() // the typing isn't in the recordings: the AI gets the text
  }

  fun backspaceIn(note: VoiceNote) {
    if (note !in notes || note == listening) return
    note.aiKeys?.let {
      note.aiKeys = Keystroke.backspace(it)
      return
    }
    note.commitLive()
    note.committed = note.committed.dropLast(1)
    note.recorded.clear()
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
    if (note.notice == null && note.text.isNotBlank() && note in notes) insert(note)
  }

  /** Types a ready pane into the focused pane; with no terminal to type into it stays staged and says so. */
  private fun insert(note: VoiceNote) {
    if (deliver(note) || note !in notes) return
    note.notice = NO_TARGET
    Log.i(TAG, "voice: pane ${note.id} staged: no terminal to type into")
  }

  /** AUDIO_START's body; an AI-mic dictation asks the Mac to keep its audio for the AI to hear. */
  private fun audioFormat(mode: VoiceNote.Mode): JSONObject =
    JSONObject().put("sampleRate", MicCapture.SAMPLE_RATE).put("channels", 1).put("encoding", "pcm_s16le")
      .apply { if (mode == VoiceNote.Mode.Ai) put("ai", true) }

  /** Sends [note]'s transcript and the target pane's screen and cursor to the Mac's AI; the pane shows Thinking. */
  private fun askAi(note: VoiceNote) {
    val id = ++nextUtterance // shares the utterance id space, so AI and audio frames never collide
    note.aiRequest = id
    note.aiKeys = null
    note.clearAgent()
    val context = aiContext(note)
    val model = aiModel()
    val agent = aiThinking().agent
    note.agent = agent
    note.phase = VoiceNote.Phase.Thinking
    val sent =
      link.send(
        Kind.AI_REQUEST,
        id,
        JSONObject().put("transcript", note.text).put("screen", context?.screen ?: "").put("title", context?.title ?: "")
          .apply { context?.cursor?.let { put("cursor", JSONObject().put("row", it.row).put("col", it.col).put("visible", it.visible)) } }
          .apply { context?.terminal?.let { put("terminal", it) } }
          .apply { if (note.recorded.isNotEmpty()) put("utterances", JSONArray(note.recorded)) }
          .put("provider", model.provider.storageValue).put("model", model.id).put("agent", agent),
      )
    if (!sent) return aiFailed(note, "Mac service not connected; the AI didn't answer")
    Log.i(TAG, "voice: asking ${model.id}${if (agent) " (agent)" else ""} for pane ${note.id} (${context?.title ?: "no pane focused"})")
    note.aiActiveAt = SystemClock.uptimeMillis()
    // The Mac limits an agent's run itself and says so; this only covers a Mac that went quiet. An
    // agent can work for minutes, so it counts from the agent's last progress.
    watchAi(note, id, if (agent) AGENT_IDLE_MS else AI_TIMEOUT_MS)
  }

  /** Fails [note]'s request [id] once nothing has come from the Mac for [limitMs]. */
  private fun watchAi(note: VoiceNote, id: Int, limitMs: Long) {
    main.postDelayed({
      if (note.phase != VoiceNote.Phase.Thinking || note.aiRequest != id) return@postDelayed
      val quietMs = SystemClock.uptimeMillis() - note.aiActiveAt
      if (quietMs >= limitMs) aiFailed(note, "The AI went quiet and was given up on") else watchAi(note, id, limitMs - quietMs)
    }, limitMs)
  }

  /** Tells the Mac to stop the agent still answering [note], if any; its late frames are ignored. */
  private fun cancelAi(note: VoiceNote) {
    if (note.phase != VoiceNote.Phase.Thinking || !note.agent) return
    link.send(Kind.AI_CANCEL, note.aiRequest)
    Log.i(TAG, "voice: stopped the agent for pane ${note.id} (request ${note.aiRequest})")
  }

  /** The AI didn't answer: the pane keeps what was said (a paste types it, without Enter) and shows why. */
  private fun aiFailed(note: VoiceNote, message: String) {
    note.notice = message
    note.phase = VoiceNote.Phase.Ready
    Log.i(TAG, "voice: AI failed for pane ${note.id}: $message")
  }

  private companion object {
    const val TAG = "vrcode"
    const val FINALIZE_TIMEOUT_MS = 4000L
    const val AI_TIMEOUT_MS = 30_000L
    const val AGENT_IDLE_MS = 5 * 60_000L
    const val NOTICE_MS = 4000L
    const val MAX_PANES = 8
    const val NO_TARGET = "No terminal to type into — pinch a pane to paste"

    // Two 50 ms chunks at or above this level (MicCapture's 0..1 RMS, about -43 dBFS) count as
    // having said something. An estimate, not yet measured on the Quest: stopListening logs each
    // dictation's peak level for tuning. Kept low on purpose: a false "said something" only keeps
    // the old wait for the Mac, a false "nothing" would drop words.
    const val SPEECH_LEVEL = 0.03f
    const val SPEECH_CHUNKS = 2
  }
}

/**
 * What the AI sees of the target pane: everything on its screen (one line per row from the top,
 * blank rows below the text and cursor dropped), its window title, and its cursor (row into
 * [screen]'s lines, column in cells).
 */
class AiContext(val screen: String, val title: String, val cursor: Cursor? = null, val terminal: String? = null) {
  class Cursor(val row: Int, val col: Int, val visible: Boolean)
}

/** One entry on the thinking panel: the agent's thinking, or one tool call it made. */
sealed interface AiTrace {
  data class Thought(val text: String) : AiTrace

  data class Step(val text: String) : AiTrace
}
