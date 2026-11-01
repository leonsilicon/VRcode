package dev.improve.vrcode.voice

import dev.improve.xmux.cell.grid.CellGrid
import dev.improve.xmux.client.AttachStatus
import dev.improve.xmux.client.Cursor
import dev.improve.xmux.client.Key
import dev.improve.xmux.client.Modifier
import dev.improve.xmux.client.MouseEvent
import dev.improve.xmux.client.TerminalHandle
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.key.encoder.KeyInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class KeystrokeTest {
  @Test
  fun aiResultKeysBecomeStepsInOrder() {
    val json = JSONObject("""{"keys":[{"key":"ctrl+u"},{"text":"git status"},{"key":"enter"}],"text":"git status"}""")
    assertEquals(
      listOf(Keystroke.Press("ctrl+u"), Keystroke.Text("git status"), Keystroke.Enter),
      Keystroke.fromAiResult(json),
    )
  }

  @Test
  fun anOlderMacServiceSendsOnlyTextWithoutEnter() {
    assertEquals(listOf(Keystroke.Text("ls -la")), Keystroke.fromAiResult(JSONObject("""{"text":"ls -la"}""")))
    assertEquals(emptyList<Keystroke>(), Keystroke.fromAiResult(JSONObject("""{"text":"  "}""")))
  }

  @Test
  fun chordsSplitIntoModifiersAndTheKey() {
    assertEquals(Key("enter"), Keystroke.Enter.key)
    assertEquals(Key("c", setOf(Modifier.Ctrl)), Keystroke.Press("ctrl+c").key)
    assertEquals(Key("left", setOf(Modifier.Alt, Modifier.Shift)), Keystroke.Press("alt+shift+left").key)
    assertEquals(Key("-", setOf(Modifier.Ctrl)), Keystroke.Press("ctrl+-").key)
  }

  @Test
  fun keyPressesHaveShortLabels() {
    assertEquals("⏎", Keystroke.Enter.label)
    assertEquals("⌃C", Keystroke.Press("ctrl+c").label)
    assertEquals("Esc", Keystroke.Press("escape").label)
    assertEquals("⌥⇧←", Keystroke.Press("alt+shift+left").label)
    assertEquals("F5", Keystroke.Press("f5").label)
  }

  @Test
  fun keyboardEditsAppendTextAndBackspaceOverKeys() {
    val steps = listOf(Keystroke.Text("git sta"), Keystroke.Enter)
    assertEquals(listOf(Keystroke.Text("git sta")), Keystroke.backspace(steps))
    assertEquals(listOf(Keystroke.Text("git st")), Keystroke.backspace(Keystroke.backspace(steps)))
    assertEquals(listOf(Keystroke.Text("git status")), Keystroke.append(listOf(Keystroke.Text("git sta")), "tus"))
    assertEquals(listOf(Keystroke.Enter, Keystroke.Text("y")), Keystroke.append(listOf(Keystroke.Enter), "y"))
    assertEquals(emptyList<Keystroke>(), Keystroke.backspace(listOf(Keystroke.Text("x"))))
  }

  @Test
  fun sendWritesTextAndBatchesAdjacentKeysInOrder() {
    val handle = RecordingHandle()
    Keystroke.send(
      handle,
      listOf(Keystroke.Press("ctrl+c"), Keystroke.Press("ctrl+u"), Keystroke.Text("npm test"), Keystroke.Enter),
    )
    assertEquals(
      listOf("keys ctrl+c ctrl+u", "write npm test", "keys enter"),
      handle.calls,
    )
  }

  private class RecordingHandle : TerminalHandle {
    val calls = mutableListOf<String>()
    override val id = TerminalId(1)
    override val grid: StateFlow<CellGrid> = MutableStateFlow(CellGrid(1, 1))
    override val cursor: StateFlow<Cursor> = MutableStateFlow(Cursor(0, 0, true))
    override val attach: StateFlow<AttachStatus> = MutableStateFlow(AttachStatus.Attaching)

    override fun write(text: String) {
      calls += "write $text"
    }

    override fun keys(vararg keys: Key) {
      calls += "keys " + keys.joinToString(" ") { k -> (k.modifiers.map { it.name.lowercase() + "+" }.sorted() + k.name).joinToString("") }
    }

    override fun mouse(event: MouseEvent) = Unit

    override fun send(input: KeyInput) = Unit

    override fun resize(cols: Int, rows: Int) = Unit

    override fun close() = Unit
  }
}
