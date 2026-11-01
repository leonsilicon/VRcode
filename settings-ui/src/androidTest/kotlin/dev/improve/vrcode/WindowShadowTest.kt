package dev.improve.vrcode

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class WindowShadowTest {
  @get:Rule val compose = createComposeRule()

  /** Like a Mac's key window, the focused window's shadow is darker and reaches further below it. */
  @Test fun focusedWindowCastsALargerDarkerShadowBelowIt() {
    val widthM = 1.0f
    val heightM = 0.6f
    val pxPerM = WindowShadowStyle.PX_PER_M
    val margin = WindowShadowStyle.MARGIN_M * pxPerM
    val w = ((widthM + 2 * WindowShadowStyle.MARGIN_M) * pxPerM).roundToInt()
    val h = ((heightM + 2 * WindowShadowStyle.MARGIN_M) * pxPerM).roundToInt()
    var focused by mutableStateOf(true)
    compose.setContent {
      CompositionLocalProvider(LocalDensity provides Density(1f)) {
        // White behind the transparent panel, so shadow shows as darkness.
        Box(Modifier.requiredSize(w.dp, h.dp).background(Color.White).testTag("shadow-panel")) {
          WindowShadow(widthM, heightM, cornerM = 0.012f) { focused }
        }
      }
    }
    fun shade(image: Bitmap, x: Float, y: Float) = 255 - android.graphics.Color.red(image.getPixel(x.toInt(), y.toInt()))
    val belowX = w / 2f
    val justBelow = margin + heightM * pxPerM + 0.02f * pxPerM
    val farBelow = margin + heightM * pxPerM + 0.06f * pxPerM

    val active = capture("window-shadow-focused.png")
    compose.runOnIdle { focused = false }
    compose.mainClock.advanceTimeBy(300)
    val inactive = capture("window-shadow-unfocused.png")

    assertTrue("focused shadow shows below the window", shade(active, belowX, justBelow) > 40)
    assertTrue("focused shadow is darker just below", shade(active, belowX, justBelow) > shade(inactive, belowX, justBelow) + 20)
    assertTrue("focused shadow reaches further", shade(active, belowX, farBelow) > shade(inactive, belowX, farBelow))
    assertTrue("inactive window still has a shadow", shade(inactive, belowX, margin + heightM * pxPerM + 0.004f * pxPerM) > 15)
    // The bottom corners (the test activity's action bar shades the top rows of any capture).
    assertTrue("the panel's corners stay clear", shade(active, 2f, h - 3f) < 4 && shade(active, w - 3f, h - 3f) < 4)
    // Light from above: the shadow falls below the window more than above it.
    assertTrue(shade(active, belowX, justBelow) > shade(active, belowX, margin - 0.02f * pxPerM))
  }

  private fun capture(name: String): Bitmap {
    val bitmap = compose.onNodeWithTag("shadow-panel").captureToImage().asAndroidBitmap()
    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
      ?: context.getExternalFilesDir(null)!!
    directory.mkdirs()
    File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return bitmap
  }
}
