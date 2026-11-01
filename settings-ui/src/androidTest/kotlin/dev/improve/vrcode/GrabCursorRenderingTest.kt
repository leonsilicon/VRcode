package dev.improve.vrcode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GrabCursorRenderingTest {
  private fun render(name: String, grabbing: Boolean, scale: Float = 4f, background: Int = 0xFF1E1E1E.toInt()): Bitmap {
    val side = (GrabCursor.EXTENT * scale).toInt() + 8
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(background)
    val cursor = GrabCursor()
    cursor.setBounds(0, 0, side, side)
    cursor.pointAt(side / 2f, side / 2f, scale, grabbing)
    cursor.draw(canvas)
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: File(context.getExternalFilesDir(null), "screenshots")
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
  }

  @Test fun openHandWhilePointingClosedBlueHandWhileGrabbing() {
    val open = render("grab-open.png", grabbing = false)
    val closed = render("grab-closed.png", grabbing = true)
    val centre = open.width / 2
    // The aim point is the middle of the hand, on its palm.
    assertEquals(GrabCursor.OPEN, open.getPixel(centre, centre))
    assertEquals(GrabCursor.GRABBING, closed.getPixel(centre, centre))
    // Nothing is drawn in the corners of its square.
    assertEquals(0xFF1E1E1E.toInt(), open.getPixel(1, 1))
    assertEquals(0xFF1E1E1E.toInt(), closed.getPixel(closed.width - 2, 1))
    // The open hand's fingers reach higher than the fist's knuckles.
    assertTrue(topOfHand(open) < topOfHand(closed))
    // A dark outline keeps the white hand visible on a white window.
    val onWhite = render("grab-open-on-white.png", grabbing = false, background = Color.WHITE)
    assertTrue((0 until onWhite.width).any { x -> (0 until onWhite.height).any { y -> onWhite.getPixel(x, y) == GrabCursor.INK } })
  }

  private fun topOfHand(bitmap: Bitmap): Int =
    (0 until bitmap.height).first { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) != 0xFF1E1E1E.toInt() } }
}
