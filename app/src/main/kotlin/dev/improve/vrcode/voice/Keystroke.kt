package dev.improve.vrcode.voice

import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier
import dev.improve.xmux.client.TerminalHandle
import org.json.JSONArray
import org.json.JSONObject

/**
 * One step of what a voice pane types into a terminal, in order: literal text, or one key press.
 * The AI mic's answer is a list of these (the Mac's AiKeystroke), so it can press Enter, Ctrl-C,
 * arrows and the like itself; a dictation is one [Text] step, plus Enter if the text mic's toggle
 * is on.
 */
sealed interface Keystroke {
  data class Text(val text: String) : Keystroke

  /** A key chord in cmux's syntax: `enter`, `ctrl+c`, `alt+shift+left` (modifiers, then the key). */
  data class Press(val chord: String) : Keystroke {
    /** The xmux key press: the part after the last `+` is the key, the ones before it modifiers. */
    val key: Key
      get() {
        val parts = chord.split('+')
        val mods = parts.dropLast(1).mapNotNull { MODIFIERS[it] }.toSet()
        // `ctrl++` would split into empty parts; the Mac never sends it, but keep the key intact.
        val name = parts.last().ifEmpty { "+" }
        return Key(name, mods)
      }

    /** How a voice pane shows it: ⏎ for Enter, ⌃C for Ctrl-C, Esc, ↑ and so on. */
    val label: String
      get() {
        val parts = chord.split('+')
        val mods = parts.dropLast(1).joinToString("") { MOD_LABELS[it] ?: it }
        val name = parts.last()
        return mods + (KEY_LABELS[name] ?: if (name.length == 1) name.uppercase() else name.replaceFirstChar { it.uppercase() })
      }
  }

  companion object {
    val Enter = Press("enter")

    private val MODIFIERS = mapOf("ctrl" to Modifier.Ctrl, "alt" to Modifier.Alt, "shift" to Modifier.Shift)
    private val MOD_LABELS = mapOf("ctrl" to "⌃", "alt" to "⌥", "shift" to "⇧")
    private val KEY_LABELS =
      mapOf(
        "enter" to "⏎", "tab" to "⇥", "backtab" to "⇤", "escape" to "Esc", "backspace" to "⌫", "delete" to "⌦",
        "up" to "↑", "down" to "↓", "left" to "←", "right" to "→", "space" to "Space",
        "pageup" to "PgUp", "pagedown" to "PgDn",
      )

    /**
     * AI_RESULT's steps: its `keys` array of `{"text": ...}` and `{"key": ...}` objects, or, from a
     * Mac service that predates `keys`, its `text` as one step.
     */
    fun fromAiResult(json: JSONObject): List<Keystroke> {
      val keys = json.optJSONArray("keys") ?: return text(json.optString("text"))
      return fromJson(keys)
    }

    fun fromJson(array: JSONArray): List<Keystroke> =
      (0 until array.length()).mapNotNull { i ->
        val step = array.optJSONObject(i) ?: return@mapNotNull null
        when {
          step.has("key") -> step.optString("key").takeIf { it.isNotBlank() }?.let(::Press)
          else -> step.optString("text").takeIf { it.isNotEmpty() }?.let(::Text)
        }
      }

    /** [text] as steps: one [Text] step, or none when it is blank. */
    fun text(text: String): List<Keystroke> = if (text.isBlank()) emptyList() else listOf(Text(text))

    /** [steps] with [text] typed at the end (the palm keyboard editing a staged AI pane). */
    fun append(steps: List<Keystroke>, text: String): List<Keystroke> {
      if (text.isEmpty()) return steps
      val last = steps.lastOrNull()
      return if (last is Text) steps.dropLast(1) + Text(last.text + text) else steps + Text(text)
    }

    /** [steps] less their last character, or their last key press. */
    fun backspace(steps: List<Keystroke>): List<Keystroke> {
      val last = steps.lastOrNull() ?: return steps
      if (last !is Text || last.text.length <= 1) return steps.dropLast(1)
      return steps.dropLast(1) + Text(last.text.dropLast(1))
    }

    /** Sends [steps] to [handle] in order; adjacent key presses go out together. */
    fun send(handle: TerminalHandle, steps: List<Keystroke>) {
      val pending = mutableListOf<Key>()
      fun flush() {
        if (pending.isEmpty()) return
        handle.keys(*pending.toTypedArray())
        pending.clear()
      }
      for (step in steps) {
        when (step) {
          is Text -> {
            flush()
            handle.write(step.text)
          }
          is Press -> pending += step.key
        }
      }
      flush()
    }
  }
}
