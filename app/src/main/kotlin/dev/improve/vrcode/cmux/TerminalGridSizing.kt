package dev.improve.vrcode.cmux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.util.Log
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.SceneObjectSystem
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.spatial.GridSize
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.XmuxSpatial
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Resizing a terminal window changes how many columns and rows its terminals have in cmux, instead
 * of stretching the panel. xmux (with `SpatialConfig.resizeGrids`) reports the grid each terminal
 * needs to fill the resized window; this pins those grids in cmux through the Mac service, so they
 * hold while a Mac frontend shows the same terminal (it crops or pans). Without the service it
 * falls back to xmux's viewer report, which an attached Mac frontend can outvote.
 */
class TerminalGridSizing(
  private val activity: AppSystemActivity,
  private val xmux: XmuxSpatial,
  private val cmux: CmuxFeature,
  private val scope: CoroutineScope,
) {
  private var debugRegistered = false

  fun install() {
    xmux.onGridResize = ::apply
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && !debugRegistered) {
      activity.registerReceiver(debugReceiver, IntentFilter(DEBUG_ACTION), Context.RECEIVER_EXPORTED)
      debugRegistered = true
    }
  }

  fun dispose() {
    if (debugRegistered) activity.unregisterReceiver(debugReceiver)
    debugRegistered = false
  }

  private fun apply(window: ScreenWindow, sizes: Map<TerminalId, GridSize>) {
    val session = window.session ?: return
    val terminals = session.topology.value.terminals
    for ((id, size) in sizes) {
      val cmuxId = terminals[id]?.cmuxId
      if (cmuxId == null || !cmux.controlConnected) {
        Log.i(TAG, "resize ${cmuxId ?: id} to ${size.cols}x${size.rows} as a viewer (Mac service not connected)")
        viewerResize(window, session, id, size)
        continue
      }
      scope.launch {
        try {
          val result = cmux.pinTerminalSize(cmuxId, size.cols, size.rows)
          Log.i(TAG, "pinned $cmuxId to ${size.cols}x${size.rows}: cmux grid ${result.opt("cols")}x${result.opt("rows")}")
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          Log.w(TAG, "could not pin $cmuxId to ${size.cols}x${size.rows}; asking as a viewer", error)
          viewerResize(window, session, id, size)
        }
      }
    }
  }

  /** Only terminals the window shows are attached; reporting for another would attach it. */
  private fun viewerResize(window: ScreenWindow, session: XmuxSession, id: TerminalId, size: GridSize) {
    val shown = window.screen?.let { session.topology.value.screen(it) }?.panes?.mapNotNull { it.activeTerminal }.orEmpty()
    if (id in shown) session.terminal(id).resize(size.cols, size.rows)
  }

  /**
   * Debug builds only: resizes a window without hands, the same way a corner drag does, e.g.
   * `adb shell am broadcast -a dev.improve.vrcode.RESIZE --ef w 1.6 --ef h 0.9` (metres, header
   * included) or `--ef sw 1.5 --ef sh 1` (factors of the current size). `--es screen <cmux id>`
   * picks the window; otherwise the focused one. With `--ez panel true` it resizes the panel's
   * pixels instead, as ISDK does when a Relayout corner drag ends.
   */
  private val debugReceiver =
    object : BroadcastReceiver() {
      @OptIn(SpatialSDKExperimentalAPI::class)
      override fun onReceive(context: Context, intent: Intent) {
        val cmuxScreen = intent.getStringExtra("screen")
        val window =
          if (cmuxScreen != null) xmux.windows.firstOrNull { w -> w.screen?.let { w.session?.topology?.value?.screen(it)?.cmuxId } == cmuxScreen }
          else xmux.focused ?: xmux.windows.firstOrNull { it.visible && it.screen != null }
        if (window == null) {
          Log.w(TAG, "debug resize: no window ${cmuxScreen ?: "(focused)"}")
          return
        }
        val spec = window.panelSize
        if (intent.getBooleanExtra("panel", false)) {
          // What an ISDK Relayout resize does on release: new panel pixels at the same density.
          val width = (spec.widthPx * intent.getFloatExtra("sw", 1f)).toInt()
          val height = (spec.heightPx * intent.getFloatExtra("sh", 1f)).toInt()
          val entity = window.entity ?: return
          val panel = activity.systemManager.findSystem(SceneObjectSystem::class).getSceneObject(entity)?.getNow(null) as? PanelSceneObject
          Log.i(TAG, "debug panel resize screen ${window.screen?.value}: ${spec.widthPx}x${spec.heightPx} -> ${width}x$height px (${panel != null})")
          panel?.resize(width, height)
          return
        }
        val width = intent.getFloatExtra("w", spec.widthM * intent.getFloatExtra("sw", 1f))
        val height = intent.getFloatExtra("h", spec.heightM * intent.getFloatExtra("sh", 1f))
        Log.i(TAG, "debug resize screen ${window.screen?.value}: ${spec.widthM}x${spec.heightM} m -> ${width}x$height m")
        xmux.resizeWindow(window, width, height)
      }
    }

  private companion object {
    const val TAG = "VrcodeGridSize"
    const val DEBUG_ACTION = "dev.improve.vrcode.RESIZE"
  }
}
