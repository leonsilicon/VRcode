package dev.improve.vrcode.speech

import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Frame kinds of the vrcode service protocol (docs/vrcode-protocol.md). */
object Kind {
  const val HELLO = 1
  const val WELCOME = 2
  const val AUDIO_START = 3
  const val AUDIO = 4
  const val AUDIO_END = 5
  const val AUDIO_CANCEL = 6
  const val TRANSCRIPT = 7
  const val ERROR = 8
  const val CMUX_REQUEST = 9
  const val CMUX_EVENT = 10
  /** Headset -> Mac: {transcript, screen, title}; the Mac answers with AI_RESULT or ERROR (same id). */
  const val AI_REQUEST = 11
  /** Mac -> headset: {text, model}, the text to stage for the pane. */
  const val AI_RESULT = 12
}

class Frame(val kind: Int, val id: Int, val sequence: Int, val body: ByteArray)

/** xmux's 16-byte little-endian header layout with vrcode's own kinds and version. */
object SpeechWire {
  const val HEADER_LENGTH = 16
  const val VERSION = 1
  const val MAX_BODY = 4 * 1024 * 1024

  fun encode(kind: Int, id: Int, sequence: Int, body: ByteArray = ByteArray(0)): ByteArray {
    val out = ByteBuffer.allocate(HEADER_LENGTH + body.size).order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(body.size)
    out.put(kind.toByte())
    out.put(0)
    out.putShort(VERSION.toShort())
    out.putInt(id)
    out.putInt(sequence)
    out.put(body)
    return out.array()
  }

  /** Reads one frame; throws on end of stream, a foreign version or an oversized body. */
  fun read(input: DataInputStream): Frame {
    val header = ByteArray(HEADER_LENGTH)
    input.readFully(header)
    val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    val length = h.getInt(0)
    val kind = header[4].toInt() and 0xFF
    val version = h.getShort(6).toInt() and 0xFFFF
    if (version != VERSION) throw IOException("unsupported protocol version $version")
    if (length < 0 || length > MAX_BODY) throw IOException("frame body of $length bytes")
    val body = ByteArray(length)
    input.readFully(body)
    return Frame(kind, h.getInt(8), h.getInt(12), body)
  }
}

/** Joins two transcript segments with one space between words. */
fun joinText(a: String, b: String): String {
  val tail = b.trim()
  if (tail.isEmpty()) return a.trim()
  val head = a.trim()
  return if (head.isEmpty()) tail else "$head $tail"
}
