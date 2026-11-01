package dev.improve.vrcode

import android.view.View
import androidx.compose.ui.geometry.Offset
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Controller
import com.meta.spatial.toolkit.PlayerBodyAttachmentSystem
import com.meta.spatial.toolkit.Transform
import dev.improve.xmux.spatial.XmuxSpatial
import dev.improve.xmux.client.ScreenId

/** Paint the right aim ray's hit on the panel itself, including its header and tab strips. */
internal class RightHandPointer(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val onHeaderHover: (ScreenId?, Offset?) -> Unit = { _, _ -> },
) {
  private val cursor = PointerCursor()
  private var host: View? = null
  private var wasActive = false

  /** Returns true when a previously tracked right hand/controller was lost. */
  fun update(visible: Boolean, style: PointerCursorStyle, size: Float): Boolean {
    cursor.style = style
    val right = activity.systemManager.tryFindSystem<PlayerBodyAttachmentSystem>()
      ?.tryGetLocalPlayerAvatarBody()?.rightHand
    val controller = right?.tryGetComponent<Controller>()
    val active = controller?.isActive == true
    val lost = wasActive && !active
    wasActive = active
    // Hover remains active with No cursor selected; the graphic is only a display preference.
    val pose = if (active && visible) right?.tryGetComponent<Transform>()?.transform else null
    val hit = pose?.let { xmux.hitTest(it.t, it.forward()) }
    val root = hit?.let { (it.window.screenView ?: it.window.view)?.rootView }
    if (root !== host) {
      hide()
      host = root
      root?.overlay?.add(cursor)
    }
    if (root != null) {
      val spec = hit.window.panelSize
      val sx = root.width.toFloat() / spec.widthPx
      val sy = root.height.toFloat() / spec.heightPx
      cursor.setBounds(0, 0, root.width, root.height)
      cursor.pointAt(hit.x * sx, hit.y * sy, sx * size,
        controller != null && controller.isDown(ButtonBits.ButtonTriggerR))
      onHeaderHover(hit.window.screen.takeIf { hit.onHeader }, Offset(hit.x * sx, hit.y * sy).takeIf { hit.onHeader })
    } else {
      onHeaderHover(null, null)
    }
    return lost
  }

  fun hide() {
    onHeaderHover(null, null)
    host?.overlay?.remove(cursor)
    host = null
  }
}
