package dev.improve.vrcode.windows

import android.content.Context
import android.graphics.Canvas
import android.util.Log
import android.view.View
import androidx.compose.ui.geometry.Offset
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
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.ViewPanelRegistration
import com.meta.spatial.toolkit.Visible
import dev.improve.vrcode.GrabCursor
import dev.improve.vrcode.PanelScale
import dev.improve.vrcode.panelScale
import dev.improve.vrcode.titleBarGrabPoint
import dev.improve.xmux.spatial.ScreenWindow

/**
 * Where the left hand's grab cursor sits on a window: at [pixel] (panel pixels) and, for the part
 * past the window's edges, at [pose] in the world, [widthM] across. [onPanel] is true when the
 * whole cursor fits on the panel, so nothing of it lies past an edge.
 */
internal class GrabCursorSpot(val pixel: Offset, val onPanel: Boolean, val pose: Pose, val widthM: Float) {
  companion object {
    /** The world copy floats this far in front of the window's plane, toward the viewer. */
    const val LIFT_M = 0.002f

    fun at(window: Pose, scale: PanelScale, widthPx: Int, heightPx: Int, pixel: Offset, size: Float): GrabCursorSpot {
      val half = GrabCursor.EXTENT / 2f * size
      val onPanel = pixel.x - half >= 0f && pixel.x + half <= widthPx && pixel.y - half >= 0f && pixel.y + half <= heightPx
      val point = titleBarGrabPoint(window, pixel, scale.centre, scale.metresPerPixelX, scale.metresPerPixelY)
      // A panel's visible face is its local -Z, so -Z is toward the viewer.
      val lifted = point + window.q.times(Vector3(0f, 0f, -LIFT_M))
      return GrabCursorSpot(pixel, onPanel, Pose(lifted, window.q), GrabCursor.EXTENT * size * scale.metresPerPixelX)
    }
  }
}

/**
 * Shows [GrabCursor] where the left ray meets a window it can move, so the wearer can tell the left
 * hand is for moving windows. On the window it is painted into the panel itself, like the right
 * hand's arrow ([dev.improve.vrcode.RightHandPointer]): window panels are compositor layers and
 * cover anything the eye buffer draws in front of them. Past the window's edges, in the band where
 * the left pinch still grabs it ([EdgeBand.around]), a small transparent mesh panel in the window's
 * plane shows the same hand at the same spot and size, so a cursor that straddles an edge reads as
 * one. Neither ever takes a ray or a click. Main thread only.
 */
internal class LeftHandCursor(private val activity: AppSystemActivity) : AutoCloseable {
  private val overlay = GrabCursor()
  private var host: View? = null
  private var sprite: Entity? = null
  private var spriteView: SpriteView? = null
  private var spriteShown = false
  private var spriteScale: Vector3? = null
  private var shownFor: ScreenWindow? = null
  private var shownGrabbing: Boolean? = null

  /** Shows the cursor at [pixel] on [window] (an open hand, or a fist while [grabbing]), [size] times its usual size. */
  fun show(window: ScreenWindow, pixel: Offset, grabbing: Boolean, size: Float) {
    val pose = window.pose()
    val spec = window.panelSize
    if (pose == null) return hide()
    val spot = GrabCursorSpot.at(pose, window.panelScale(), spec.widthPx, spec.heightPx, pixel, size)
    val root = (window.screenView ?: window.view)?.rootView
    if (root !== host) {
      host?.overlay?.remove(overlay)
      host = root
      root?.overlay?.add(overlay)
    }
    if (root != null) {
      val sx = root.width.toFloat() / spec.widthPx
      val sy = root.height.toFloat() / spec.heightPx
      overlay.setBounds(0, 0, root.width, root.height)
      overlay.pointAt(pixel.x * sx, pixel.y * sy, sx * size, grabbing)
    }
    if (spot.onPanel) hideSprite() else showSprite(spot, grabbing)
    if (shownFor != window || shownGrabbing != grabbing) {
      Log.d(TAG, "left-hand cursor: ${if (grabbing) "closed" else "open"} hand on screen ${window.screen?.value}")
      shownFor = window
      shownGrabbing = grabbing
    }
  }

  fun hide() {
    host?.overlay?.remove(overlay)
    host = null
    hideSprite()
    shownFor = null
    shownGrabbing = null
  }

  override fun close() {
    hide()
    sprite?.destroy()
    sprite = null
    spriteView = null
  }

  private fun showSprite(spot: GrabCursorSpot, grabbing: Boolean) {
    val entity = sprite ?: create().also { sprite = it }
    spriteView?.grabbing = grabbing
    entity.setComponent(Transform(spot.pose))
    val k = spot.widthM / SPRITE_M
    val scale = Vector3(k, k, 1f)
    if (spriteScale != scale) {
      entity.setComponent(Scale(scale))
      spriteScale = scale
    }
    if (!spriteShown) {
      entity.setComponent(Visible(true))
      spriteShown = true
    }
  }

  private fun hideSprite() {
    if (spriteShown) sprite?.setComponent(Visible(false))
    spriteShown = false
  }

  private fun create(): Entity {
    activity.registerPanel(
      ViewPanelRegistration(
        REGISTRATION_ID,
        dynamicViewCreator = { _, context -> SpriteView(context).also { spriteView = it } },
        settingsCreator = {
          val settings = UIPanelSettings(
            shape = QuadShapeOptions(width = SPRITE_M, height = SPRITE_M),
            display = DpDisplayOptions(SPRITE_PX.toFloat(), SPRITE_PX.toFloat(), 160),
            rendering = UIPanelRenderOptions(PanelRenderMode.Mesh()),
            style = PanelStyleOptions(themeResourceId = dev.improve.xmux.spatial.R.style.XmuxPanelThemeTransparent),
          )
          // Mesh panels default to MASKED alpha, which cuts the cursor's anti-aliased edge; blend it.
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
    Log.i(TAG, "left-hand cursor: grab hand drawn past window edges as well as on them")
    return Entity.create(
      listOf(
        Panel(REGISTRATION_ID),
        Transform(Pose(Vector3(0f, 0f, 0f), Quaternion(0f, 0f, 0f))),
        Visible(false),
        Hittable(MeshCollision.NoCollision),
      ),
    )
  }

  /** The sprite panel's content: the hand, filling the panel's [SPRITE_PX] square. */
  private class SpriteView(context: Context) : View(context) {
    private val cursor = GrabCursor()
    var grabbing = false
      set(value) {
        if (field == value) return
        field = value
        invalidate()
      }

    override fun onDraw(canvas: Canvas) {
      cursor.setBounds(0, 0, width, height)
      cursor.pointAt(width / 2f, height / 2f, width / GrabCursor.EXTENT, grabbing)
      cursor.draw(canvas)
    }
  }

  private companion object {
    const val TAG = "VrcodeWindow"
    /** Past the window shadows' registrations (900 000 on) and xmux's (10 000 on). */
    const val REGISTRATION_ID = 950_000
    /** The sprite panel's registered size; [Scale] sets the cursor's actual size. */
    const val SPRITE_M = 0.05f
    const val SPRITE_PX = 128
  }
}
