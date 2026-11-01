package dev.improve.vrcode.windows

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelSettings
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.TransformParent
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.WindowShadow
import dev.improve.vrcode.WindowShadowStyle
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A soft drop shadow behind every visible terminal window, as macOS draws one under each window:
 * larger and darker under the focused (key) window ([isFocused] is read as Compose state).
 *
 * Each shadow is a small transparent panel parented to its window's entity a little behind it, so
 * it follows moves, grabs and Follow mode with no lag and never takes a ray. It renders as a mesh
 * into the eye buffer; window panels are compositor layers, which always draw over it, so a shadow
 * can never darken a window. Call [tick] once per frame on the main thread.
 */
class WindowShadows(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val isFocused: (ScreenId) -> Boolean,
) {
  private class Shadow(val entity: Entity, val parent: Entity, val widthM: Float, val heightM: Float, var visible: Boolean)

  private val shadows = HashMap<ScreenWindow, Shadow>()
  /** A size that differs from a shadow's, and since when; resizes settle before the panel is rebuilt. */
  private val pendingSize = HashMap<ScreenWindow, Pair<Pair<Float, Float>, Long>>()
  private var nextRegistration = BASE_REGISTRATION_ID

  fun tick() {
    val live = xmux.windows.toSet()
    for (window in shadows.keys - live) remove(window)
    val now = SystemClock.uptimeMillis()
    for (window in live) {
      val parent = window.entity
      val screen = window.screen
      if (parent == null || screen == null) {
        remove(window)
        continue
      }
      val spec = window.panelSize
      val size = window.widthM to window.heightM
      var shadow = shadows[window]
      val resized = shadow != null && (abs(shadow.widthM - size.first) > RESIZE_M || abs(shadow.heightM - size.second) > RESIZE_M)
      if (shadow == null || shadow.parent != parent || resized && settled(window, size, now)) {
        remove(window)
        val cornerM = window.chrome.cornerRadiusDp * size.first / spec.widthPx
        shadow = create(parent, screen, size.first, size.second, cornerM, window.visible)
        shadows[window] = shadow
      }
      if (shadow.visible != window.visible) {
        shadow.visible = window.visible
        shadow.entity.setComponent(Visible(window.visible))
      }
    }
  }

  fun clear() {
    for (window in shadows.keys.toList()) remove(window)
  }

  /** True once [size] has held for [SETTLE_MS], so a corner drag rebuilds the panel once, on release. */
  private fun settled(window: ScreenWindow, size: Pair<Float, Float>, now: Long): Boolean {
    val pending = pendingSize[window]
    val same = pending != null && abs(pending.first.first - size.first) < RESIZE_M && abs(pending.first.second - size.second) < RESIZE_M
    if (!same) {
      pendingSize[window] = size to now
      return false
    }
    return now - pending!!.second >= SETTLE_MS
  }

  private fun remove(window: ScreenWindow) {
    shadows.remove(window)?.entity?.destroy()
    pendingSize.remove(window)
  }

  private fun create(parent: Entity, screen: ScreenId, widthM: Float, heightM: Float, cornerM: Float, visible: Boolean): Shadow {
    val panelWidthM = widthM + 2 * WindowShadowStyle.MARGIN_M
    val panelHeightM = heightM + 2 * WindowShadowStyle.MARGIN_M
    val widthPx = (panelWidthM * WindowShadowStyle.PX_PER_M).roundToInt().coerceAtLeast(16)
    val heightPx = (panelHeightM * WindowShadowStyle.PX_PER_M).roundToInt().coerceAtLeast(16)
    val id = nextRegistration++
    activity.registerPanel(
      ComposeViewPanelRegistration(
        id,
        composeViewCreator = { _, context ->
          ComposeView(context).apply { setContent { WindowShadow(widthM, heightM, cornerM) { isFocused(screen) } } }
        },
        settingsCreator = {
          val settings = UIPanelSettings(
            shape = QuadShapeOptions(width = panelWidthM, height = panelHeightM),
            display = DpDisplayOptions(widthPx.toFloat(), heightPx.toFloat(), 160),
            rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
            style = PanelStyleOptions(themeResourceId = dev.improve.xmux.spatial.R.style.XmuxPanelThemeTransparent),
          )
          // Mesh panels default to MASKED alpha, which cuts away every pixel under the mask
          // threshold: a soft shadow is nothing but such pixels, so blend it instead.
          object : PanelSettings {
            override fun toPanelConfigOptions(): PanelConfigOptions =
              settings.toPanelConfigOptions().apply {
                alphaMode = AlphaMode.TRANSLUCENT
                enableTransparent = true
              }
          }
        },
      ),
    )
    // A panel's visible face is its local -Z, so +Z is behind the window.
    val entity = Entity.create(
      listOf(
        Panel(id),
        TransformParent(parent),
        Transform(Pose(Vector3(0f, 0f, DEPTH_M), Quaternion(0f, 0f, 0f))),
        Visible(visible),
        Hittable(MeshCollision.NoCollision),
      ),
    )
    Log.i(TAG, "shadow for screen ${screen.value}: ${"%.2f".format(widthM)} x ${"%.2f".format(heightM)} m ($widthPx x $heightPx px)")
    return Shadow(entity, parent, widthM, heightM, visible)
  }

  private companion object {
    const val TAG = "VrcodeShadow"
    /** Past the registration ids xmux hands out from 10 000. */
    const val BASE_REGISTRATION_ID = 900_000
    /** How far behind its window a shadow floats. */
    const val DEPTH_M = 0.012f
    const val RESIZE_M = 0.01f
    const val SETTLE_MS = 300L
  }
}
