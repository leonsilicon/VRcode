package dev.improve.vrcode.cmux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.AppSystemActivity
import dev.improve.xmux.client.TerminalId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.spatial.GridSize
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.WindowEdge
import dev.improve.xmux.spatial.XmuxSpatial
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Resizing a terminal window from an edge changes how many columns and rows its terminals have in
 * cmux, instead of stretching the panel (its corners scale it). xmux (with
 * `SpatialConfig.resizeGrids`) reports the grid each terminal needs to fill the resized window;
 * this pins those grids in cmux through the Mac service, so they hold while a Mac frontend shows
 * the same terminal (it crops or pans). Without the service it falls back to xmux's viewer report,
 * which an attached Mac frontend can outvote.
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
   * Debug builds only: resizes a window without hands, e.g.
   * `adb shell am broadcast -a dev.improve.vrcode.RESIZE --ef w 1.6 --ef h 0.9` (metres, header
   * included) or `--ef sw 1.5 --ef sh 1` (factors of the current size) sets its grids to fill that
   * size about its centre. `--es edge right --ef by 0.2` drags an edge (left, right, top, bottom)
   * 0.2 m outward and releases it, as a hand does; `--ef scale 1.5` scales it as the corner
   * handles do, keeping its grids. `--ez where true` only logs the window's size and where its
   * edges are (every call logs that first). `--es screen <cmux id>` picks the window; otherwise
   * the focused one.
   */
  private val debugReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val cmuxScreen = intent.getStringExtra("screen")
        val window =
          if (cmuxScreen != null) xmux.windows.firstOrNull { w -> w.screen?.let { w.session?.topology?.value?.screen(it)?.cmuxId } == cmuxScreen }
          else xmux.focused ?: xmux.windows.firstOrNull { it.visible && it.screen != null }
        if (window == null) {
          Log.w(TAG, "debug resize: no window ${cmuxScreen ?: "(focused)"}")
          return
        }
        Log.i(TAG, "debug window ${where(window)}")
        if (intent.getBooleanExtra("where", false)) return
        if (intent.hasExtra("scale")) {
          val scale = intent.getFloatExtra("scale", 1f)
          Log.i(TAG, "debug scale screen ${window.screen?.value}: ${window.scale} -> $scale")
          xmux.setScale(window, scale)
          return
        }
        val edgeName = intent.getStringExtra("edge")
        if (edgeName != null) {
          val edge = WindowEdge.entries.firstOrNull { it.name.equals(edgeName, ignoreCase = true) } ?: return
          val by = intent.getFloatExtra("by", 0.1f)
          val extent = (if (edge == WindowEdge.Left || edge == WindowEdge.Right) window.widthM else window.heightM) + by
          Log.i(TAG, "debug edge drag screen ${window.screen?.value}: ${edge.name.lowercase()} edge by $by m to $extent m")
          xmux.dragEdge(window, edge, extent)
          // Holding lets the preview be inspected before the release asks for the grids.
          val hold = intent.getLongExtra("hold", 0L)
          if (hold > 0L) Handler(Looper.getMainLooper()).postDelayed({ xmux.endEdgeDrag(window) }, hold) else xmux.endEdgeDrag(window)
          return
        }
        val width = intent.getFloatExtra("w", window.widthM * intent.getFloatExtra("sw", 1f))
        val height = intent.getFloatExtra("h", window.heightM * intent.getFloatExtra("sh", 1f))
        Log.i(TAG, "debug resize screen ${window.screen?.value}: ${window.widthM}x${window.heightM} m -> ${width}x$height m")
        xmux.resizeWindow(window, width, height)
      }
    }

  /** A window's size, scale and where its edges are in the room, for checking a resize from logcat. */
  private fun where(window: ScreenWindow): String {
    val pose = window.pose() ?: return "screen ${window.screen?.value}: not placed"
    fun edge(x: Float, y: Float) = (pose.t + pose.q.times(Vector3(x * window.widthM / 2f, y * window.heightM / 2f, 0f)))
      .let { String.format(Locale.US, "(%.3f, %.3f, %.3f)", it.x, it.y, it.z) }
    val spec = window.panelSize
    return String.format(Locale.US, "screen %s: %dx%d px, %.3f x %.3f m, scale %.3f; ", window.screen?.value, spec.widthPx,
      spec.heightPx, window.widthM, window.heightM, window.scale) +
      "left ${edge(-1f, 0f)} right ${edge(1f, 0f)} top ${edge(0f, 1f)} bottom ${edge(0f, -1f)}"
  }

  private companion object {
    const val TAG = "VrcodeGridSize"
    const val DEBUG_ACTION = "dev.improve.vrcode.RESIZE"
  }
}
