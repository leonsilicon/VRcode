package dev.improve.vrcode

import android.graphics.Bitmap
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.TextView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.improve.xmux.window.frame.TerminalWindowState
import dev.improve.xmux.window.frame.WindowFrame
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class PointerCursorRenderingTest {
  @get:Rule val compose = createComposeRule()

  @Test fun overlayShowsHoverAndHeldPinchWithoutTakingClicksFromTheTerminal() {
    lateinit var body: TextView
    lateinit var root: View
    var clicks = 0
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        Box(Modifier.requiredSize(480.dp, 220.dp).testTag("pointer-preview")) {
          WindowFrame(TerminalWindowState("main · cmux").apply { loading = false }, body = { context ->
            TextView(context).apply {
              text = "$ printf 'Hello from cmux'\nHello from cmux\nPinch and drag to select text\n$"
              setTextColor(0xFFD4D4D4.toInt())
              setBackgroundColor(0xFF1E1E1E.toInt())
              setTextSize(TypedValue.COMPLEX_UNIT_PX, 25f)
              typeface = Typeface.MONOSPACE
              minHeight = 160
              setPadding(12, 12, 12, 12)
              setOnClickListener { clicks++ }
              body = this
            }
          })
        }
      }
    }
    val cursor = PointerCursor()
    val position = IntArray(2)
    compose.runOnIdle {
      root = body.rootView
      body.getLocationInWindow(position)
      val rootPosition = IntArray(2)
      root.getLocationInWindow(rootPosition)
      position[0] -= rootPosition[0]
      position[1] -= rootPosition[1]
      cursor.setBounds(0, 0, root.width, root.height)
      cursor.pointAt(position[0] + 180f, position[1] + 90f, 1f, false)
      root.overlay.add(cursor)
    }
    fun capture(name: String): Bitmap {
      compose.waitForIdle()
      val bitmap = compose.onNodeWithTag("pointer-preview").captureToImage().asAndroidBitmap()
      val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
        ?: body.context.getExternalFilesDir(null)!!
      directory.mkdirs()
      File(directory, name).outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
      }
      return bitmap
    }
    // The body starts below the production header (56 px + 1 px separator).
    val hover = capture("pointer-hover.png")
    assertEquals(0xFFFFFFFF.toInt(), hover.getPixel(185, 57 + 102))
    compose.runOnIdle {
      cursor.pointAt(position[0] + 180f, position[1] + 90f, 1f, true)
    }
    val pressed = capture("pointer-pressed.png")
    assertEquals(0xFF7DC7FF.toInt(), pressed.getPixel(185, 57 + 102))
    compose.onNodeWithTag("xmux-grid").performTouchInput { click(Offset(185f, 102f)) }
    compose.runOnIdle { assertEquals(1, clicks); root.overlay.remove(cursor) }
    val hidden = capture("pointer-hidden.png")
    assertEquals(0xFF1E1E1E.toInt(), hidden.getPixel(185, 57 + 102))

    // Changing shape and size on the same mounted drawable keeps its hotspot at the aim point.
    compose.runOnIdle {
      root.overlay.add(cursor)
      cursor.style = PointerCursorStyle.Dot
      cursor.pointAt(position[0] + 180f, position[1] + 90f, 2f, false)
    }
    val dot = capture("pointer-dot.png")
    assertEquals(0xFFFFFFFF.toInt(), dot.getPixel(180, 57 + 90))
    assertEquals(0xFFFFFFFF.toInt(), dot.getPixel(186, 57 + 90))
    compose.runOnIdle { cursor.style = PointerCursorStyle.Crosshair }
    val crosshair = capture("pointer-crosshair.png")
    assertEquals(0xFFFFFFFF.toInt(), crosshair.getPixel(180, 57 + 90))
    assertEquals(0xFFFFFFFF.toInt(), crosshair.getPixel(195, 57 + 90))
    compose.runOnIdle {
      cursor.style = PointerCursorStyle.None
      cursor.pointAt(position[0] + 180f, position[1] + 90f, 2f, true)
    }
    val none = capture("pointer-none.png")
    assertTrue("No cursor must also hide the held-pinch halo", hidden.sameAs(none))
    compose.onNodeWithTag("xmux-grid").performTouchInput { click(Offset(180f, 90f)) }
    compose.runOnIdle { assertEquals(2, clicks); root.overlay.remove(cursor) }
  }
}
