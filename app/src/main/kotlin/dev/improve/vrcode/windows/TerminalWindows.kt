package dev.improve.vrcode.windows

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.WindowMode
import dev.improve.vrcode.cmux.CmuxLink
import dev.improve.xmux.client.ScreenId
import dev.improve.xmux.client.XmuxSession
import dev.improve.xmux.spatial.Placement
import dev.improve.xmux.spatial.ScreenWindow
import dev.improve.xmux.spatial.WindowLayout
import dev.improve.xmux.spatial.XmuxSpatial
import dev.improve.xmux.window.frame.WindowChrome
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope

/**
 * Draws one window per cmux screen where cmux's layout says it is. Placement, scale, Fixed/Follow mode,
 * minimized and fullscreen live in a cmux `frontend_projection` ([LayoutSync]); each frame this
 * class applies that layout to the xmux windows, and turns the user's moves (title-bar drags,
 * grabs, corner-handle scaling, Arrange, dock recalls, header buttons) into layout changes. A
 * window's pixel size is not part of it: that follows the cmux terminal grid, which an edge drag
 * changes ([EdgeResize]). Main thread only.
 */
class TerminalWindows(
  private val xmux: XmuxSpatial,
  scope: CoroutineScope,
  deviceKey: String,
  private val head: () -> Pose?,
  private val defaultMode: () -> WindowMode,
  private val distanceM: () -> Float,
) {
  private val link: CmuxLink = CmuxLink(scope, { sync.onConnected() }, { sync.onStream(it) })
  private val sync = LayoutSync(scope, ProjectionIdentity.forDevice(deviceKey), link::call, { link.connected })
  private var session: XmuxSession? = null
  private var chrome: (ScreenId) -> WindowChrome = { WindowChrome.Default }
  private var screensSinceMs: Long? = null
  /** The pose this class last gave each window; a different pose means the user grabbed it. */
  private val applied = mutableMapOf<ScreenWindow, Pose>()
  /** Likewise the scale; a different one means the user dragged a corner handle. */
  private val appliedScale = mutableMapOf<ScreenWindow, Float>()
  private val ticks = mutableMapOf<ScreenWindow, Int>()
  private val modes = mutableStateMapOf<ScreenId, WindowMode>()
  /** The user's eased position, which every Follow window hangs from. */
  private val userFollow = SceneLayout.userFollow()
  private var user: Vector3? = null
  private var lastTickMs: Long? = null

  /** The window shown fullscreen, as Compose state for headers. */
  var fullscreenScreen by mutableStateOf<ScreenId?>(null)
    private set

  fun mode(screen: ScreenId): WindowMode = modes[screen] ?: defaultMode()
  fun isFullscreen(screen: ScreenId): Boolean = fullscreenScreen == screen
  fun isMinimized(screen: ScreenId): Boolean = idOf(screen)?.let { sync.layout.windows[it]?.minimized } == true

  /** Connects the cmux control connection that carries the layout (the vrcode Mac service). */
  fun connect(host: String, port: Int) {
    sync.reset()
    link.connect(host, port)
  }

  /** Mounts a window for each screen of [session] from now on; [chrome] builds each header. */
  fun follow(session: XmuxSession, chrome: (ScreenId) -> WindowChrome) {
    this.session = session
    this.chrome = chrome
    screensSinceMs = null
  }

  /** Stops mounting and following the layout. The caller unmounts the windows. */
  fun disconnect() {
    session = null
    link.close()
    sync.reset()
    applied.clear()
    appliedScale.clear()
    ticks.clear()
    modes.clear()
    resetUser()
    fullscreenScreen = null
  }

  /** Applies cmux's layout to every window. Pass [resumed] false while the app is paused (no head pose). */
  fun tick(resumed: Boolean) {
    val s = session
    val head = head()
    val user = updateUser(head, resumed)
    if (s != null) mountNewScreens(s, head, user)
    for (window in xmux.windows) {
      val screen = window.screen ?: continue
      val id = idOf(window) ?: continue
      val age = (ticks[window] ?: 0) + 1
      ticks[window] = age
      window.setVisible(sync.layout.visible(id))
      // Until xmux has placed a new window, let it; then cmux's layout owns the window.
      if (id !in sync.layout.windows) {
        val pose = window.pose()
        if (age <= SETTLE_TICKS || pose == null || !resumed) continue
        sync.seed(id, defaultMode(), pose, user)
      }
      applyScale(window, id, age)
      if (!resumed || !window.visible) { applied.remove(window); continue }
      val current = window.pose() ?: continue
      val last = applied[window]
      if (last != null && age > SETTLE_TICKS && !same(last, current)) sync.edit { it.moved(id, current, user) }
      val (width, height) = size(window)
      val desired = sync.layout.renderPose(id, head, user, width, height) ?: continue
      if (!same(desired, current)) window.setPose(desired)
      applied[window] = desired
      publish(screen, id)
    }
    applied.keys.retainAll(xmux.windows.toSet())
    appliedScale.keys.retainAll(xmux.windows.toSet())
    ticks.keys.retainAll(xmux.windows.toSet())
    modes.keys.retainAll(xmux.windows.mapNotNull { it.screen }.toSet())
    publishFullscreen()
    sync.tick(liveScreens())
  }

  /** Saves unsaved changes now (the app may be killed while paused). */
  fun flush() = sync.flush(liveScreens())

  /** The user moved [screen] to [world]; it stays there (relative to the user, if it follows). */
  fun move(screen: ScreenId, world: Pose) {
    val window = windowOf(screen) ?: return
    val id = idOf(window) ?: return
    sync.edit { it.moved(id, world, user ?: head()?.t) }
    window.setPose(world)
    applied[window] = world
  }

  /** Lays every shown window out on an arc in front of the user, leaving fullscreen. */
  fun arrange() {
    val head = head() ?: return
    sync.edit { it.exitFullscreen() }
    applyVisibility()
    val windows = xmux.windows.filter { it.visible }
    for ((index, window) in windows.withIndex()) {
      move(window.screen ?: continue, WindowLayout.arc(head, index, windows.size, window.widthM, window.heightM,
        distanceM().coerceAtLeast(window.widthM / 2f + 0.1f)))
    }
  }

  /**
   * A corner-handle scale the user made is saved (once the window has settled); otherwise the
   * window takes cmux's scale, which another frontend or an earlier session may have set.
   */
  private fun applyScale(window: ScreenWindow, id: String, age: Int) {
    val last = appliedScale[window]
    if (last != null && age > SETTLE_TICKS && abs(window.scale - last) > SCALE_EPSILON) sync.edit { it.scaled(id, window.scale) }
    val wanted = sync.layout.windows[id]?.scale ?: return
    if (abs(window.scale - wanted) > SCALE_EPSILON) xmux.setScale(window, wanted)
    appliedScale[window] = wanted
  }

  fun minimize(screen: ScreenId) = change(screen) { layout, id -> layout.minimized(id) }

  /** Shows a minimized window and leaves fullscreen (a dock recall). */
  fun show(screen: ScreenId) = change(screen) { layout, id -> layout.exitFullscreen().shown(id) }

  fun toggleFullscreen(screen: ScreenId) = change(screen) { layout, id -> layout.fullscreenToggled(id) }

  fun exitFullscreen() {
    sync.edit { it.exitFullscreen() }
    applyVisibility()
  }

  fun setMode(screen: ScreenId, mode: WindowMode) = change(screen) { layout, id -> layout.withMode(id, mode, user ?: head()?.t) }

  fun close() {
    disconnect()
  }

  /**
   * Eases the anchor toward the head's position. While paused it is cleared, so on resume the
   * following windows reappear around the user wherever they are.
   */
  private fun updateUser(head: Pose?, resumed: Boolean): Vector3? {
    if (!resumed || head == null) {
      resetUser()
      return null
    }
    val now = SystemClock.uptimeMillis()
    val dtS = lastTickMs?.let { (now - it) / 1000f } ?: 0f
    lastTickMs = now
    return userFollow.update(head.t, dtS).also { user = it }
  }

  private fun resetUser() {
    userFollow.reset()
    user = null
    lastTickMs = null
  }

  private fun change(screen: ScreenId, block: (SceneLayout, String) -> SceneLayout) {
    val id = idOf(screen) ?: return
    sync.edit { block(it, id) }
    applyVisibility()
  }

  /** Visibility takes effect at once, so the caller can focus the window it just showed. */
  private fun applyVisibility() {
    for (window in xmux.windows) {
      val screen = window.screen ?: continue
      val id = idOf(window) ?: continue
      window.setVisible(sync.layout.visible(id))
      publish(screen, id)
    }
    publishFullscreen()
  }

  private fun publish(screen: ScreenId, id: String) {
    val mode = sync.layout.windows[id]?.mode ?: return
    if (modes[screen] != mode) modes[screen] = mode
  }

  private fun publishFullscreen() {
    val id = sync.layout.fullscreen
    val screen = id?.let { full -> xmux.windows.firstOrNull { idOf(it) == full }?.screen }
    if (fullscreenScreen != screen) fullscreenScreen = screen
  }

  /**
   * New cmux screens get their saved placement, else the next free slot on the arc. Mounting waits
   * (up to [LOAD_WAIT_MS]) for cmux's layout, so restored windows appear in place rather than jump.
   */
  private fun mountNewScreens(session: XmuxSession, head: Pose?, user: Vector3?) {
    val screens = session.topology.value.screens
    if (screens.isEmpty()) return
    val now = SystemClock.uptimeMillis()
    val since = screensSinceMs ?: now.also { screensSinceMs = it }
    if (!sync.loaded && now - since < LOAD_WAIT_MS) return
    val shown = xmux.windows.filter { it.session === session }.mapNotNull { it.screen }.toSet()
    val missing = screens.filter { it.id !in shown }
    for ((index, screen) in missing.withIndex()) {
      val width = xmux.spec.widthM
      val height = xmux.spec.heightM
      val saved = sync.layout.renderPose(screen.cmuxId, head, user, width, height)
      val placement = when {
        saved != null -> Placement.Fixed(saved)
        head != null -> Placement.Fixed(WindowLayout.arc(head, shown.size + index, screens.size, width, height,
          distanceM().coerceAtLeast(width / 2f + 0.1f)))
        else -> Placement.InFrontOfHead
      }
      xmux.mount(session, screen.id, placement, chrome(screen.id))
      Log.i(TAG, "mounted screen ${screen.cmuxId} ${if (saved != null) "at its cmux placement" else "in a new slot"}")
    }
  }

  /** Every screen cmux has, once the bridge has sent its topology. */
  private fun liveScreens(): Set<String>? {
    val topology = session?.topology?.value ?: return null
    if (topology.revision <= 0 || topology.screens.isEmpty()) return null
    return topology.screens.map { it.cmuxId }.toSet()
  }

  private fun windowOf(screen: ScreenId) = xmux.windows.firstOrNull { it.screen == screen }
  private fun idOf(screen: ScreenId): String? = windowOf(screen)?.let(::idOf)
  private fun idOf(window: ScreenWindow): String? =
    window.screen?.let { window.session?.topology?.value?.screen(it)?.cmuxId }

  private fun size(window: ScreenWindow): Pair<Float, Float> = window.widthM to window.heightM

  private companion object {
    const val TAG = "VrcodeWindow"
    const val SCALE_EPSILON = 0.001f
    /** Frames before a newly mounted window's pose is xmux's placement rather than its spawn pose. */
    const val SETTLE_TICKS = 3
    const val LOAD_WAIT_MS = 3000L

    /** Equal within half a millimetre and about a tenth of a degree. */
    fun same(a: Pose, b: Pose): Boolean =
      (a.t - b.t).length() < 0.0005f && abs(a.q.dot(b.q)) > 0.999999f
  }
}
