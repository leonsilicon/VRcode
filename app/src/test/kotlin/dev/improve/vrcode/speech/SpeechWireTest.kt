package dev.improve.vrcode.speech

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechWireTest {
  @Test
  fun headerMatchesTheDocumentedLayout() {
    val bytes = SpeechWire.encode(Kind.AUDIO, id = 0x01020304, sequence = 7, body = byteArrayOf(9, 8, 7))
    assertArrayEquals(
      byteArrayOf(
        3, 0, 0, 0, // body_len
        4, // kind AUDIO
        0, // flags
        1, 0, // version
        4, 3, 2, 1, // id
        7, 0, 0, 0, // sequence
        9, 8, 7,
      ),
      bytes,
    )
  }

  @Test
  fun framesRoundTripBackToBack() {
    val stream = SpeechWire.encode(Kind.WELCOME, 0, 1, "{}".toByteArray()) + SpeechWire.encode(Kind.AUDIO_END, 5, 2)
    val input = DataInputStream(ByteArrayInputStream(stream))
    val first = SpeechWire.read(input)
    assertEquals(Kind.WELCOME, first.kind)
    assertEquals("{}", String(first.body))
    val second = SpeechWire.read(input)
    assertEquals(Kind.AUDIO_END, second.kind)
    assertEquals(5, second.id)
    assertEquals(2, second.sequence)
    assertEquals(0, second.body.size)
    assertThrows(EOFException::class.java) { SpeechWire.read(input) }
  }

  @Test
  fun rejectsAnotherVersion() {
    val bytes = SpeechWire.encode(Kind.HELLO, 0, 1)
    bytes[6] = 2
    assertThrows(IOException::class.java) { SpeechWire.read(DataInputStream(ByteArrayInputStream(bytes))) }
  }

  @Test
  fun joinsSegmentsWithOneSpace() {
    assertEquals("run git status", joinText("run git", " status "))
    assertEquals("status", joinText("", "status"))
    assertEquals("run", joinText(" run ", "  "))
  }
}
