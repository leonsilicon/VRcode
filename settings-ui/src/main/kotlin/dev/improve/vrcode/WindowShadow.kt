package dev.improve.vrcode

import android.graphics.BlurMaskFilter
import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.util.lerp

/**
 * How a window's drop shadow looks, in metres so it reads the same at any panel resolution. Like a
 * Mac, the focused (key) window casts a larger, darker shadow that falls further below it.
 */
object WindowShadowStyle {
  const val ACTIVE_BLUR_M = 0.05f
  const val ACTIVE_DROP_M = 0.02f
  const val ACTIVE_ALPHA = 0.55f
  const val INACTIVE_BLUR_M = 0.022f
  const val INACTIVE_DROP_M = 0.008f
  const val INACTIVE_ALPHA = 0.3f

  /** Room around the window inside the shadow panel; covers the active blur plus its drop. */
  const val MARGIN_M = ACTIVE_BLUR_M * 1.6f + ACTIVE_DROP_M

  /** Shadow panel resolution. The shadow is all blur, so it needs few pixels. */
  const val PX_PER_M = 320f
}

/**
 * The shadow behind a window of [windowWidthM] x [windowHeightM] with [cornerM] corners, drawn
 * centred in a panel that is [WindowShadowStyle.MARGIN_M] larger on every side. [focused] is read
 * during composition, so a focus change animates the shadow without recreating its panel.
 */
@Composable
fun WindowShadow(windowWidthM: Float, windowHeightM: Float, cornerM: Float, focused: () -> Boolean) {
  val active by animateFloatAsState(if (focused()) 1f else 0f, tween(200), label = "Window shadow")
  val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
  Canvas(Modifier.fillMaxSize().testTag("vrcode-window-shadow")) {
    val pxPerM = size.width / (windowWidthM + 2 * WindowShadowStyle.MARGIN_M)
    val blur = lerp(WindowShadowStyle.INACTIVE_BLUR_M, WindowShadowStyle.ACTIVE_BLUR_M, active) * pxPerM
    val drop = lerp(WindowShadowStyle.INACTIVE_DROP_M, WindowShadowStyle.ACTIVE_DROP_M, active) * pxPerM
    val alpha = lerp(WindowShadowStyle.INACTIVE_ALPHA, WindowShadowStyle.ACTIVE_ALPHA, active)
    val margin = WindowShadowStyle.MARGIN_M * pxPerM
    val corner = cornerM * pxPerM
    paint.color = android.graphics.Color.argb((alpha * 255).toInt(), 0, 0, 0)
    paint.maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    // Nothing under the window itself: windows can be translucent, and a shadow seen through one
    // would darken the room behind it. As on a Mac, the shadow only falls around the window.
    val window = Path().apply {
      addRoundRect(RoundRect(margin, margin, margin + windowWidthM * pxPerM, margin + windowHeightM * pxPerM, CornerRadius(corner)))
    }
    clipPath(window, ClipOp.Difference) {
      drawIntoCanvas {
        it.nativeCanvas.drawRoundRect(margin, margin + drop, margin + windowWidthM * pxPerM,
          margin + drop + windowHeightM * pxPerM, corner, corner, paint)
      }
    }
  }
}
