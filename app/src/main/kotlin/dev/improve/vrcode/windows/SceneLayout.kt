package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.WindowMode
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject

/**
 * One terminal window as cmux stores it. [pose] is in the scene's reference space for a
 * [WindowMode.Fixed] window and relative to the head for a [WindowMode.Follow] one. A window's size
 * is not here: it follows the cmux terminal grid.
 */
data class WindowPlacement(
  val mode: WindowMode,
  val pose: Pose,
  val minimized: Boolean = false,
  /** Fields this build does not know, as JSON text, written back unchanged. */
  val extra: String? = null,
) {
  /** The window's pose in the scene, outside fullscreen. Null when it follows the head and the head is unknown. */
  fun worldPose(head: Pose?): Pose? = if (mode == WindowMode.Follow) head?.let { it * pose } else pose
}

/**
 * The terminal window layout for one headset scene, keyed by stable cmux screen ids. cmux keeps
 * it as a `frontend_projection`; vrcode renders it and keeps no window state of its own. Every
 * operation returns a new layout.
 */
data class SceneLayout(
  val windows: Map<String, WindowPlacement> = emptyMap(),
  /** The one screen shown fullscreen, if any. Every other window is hidden while it is set. */
  val fullscreen: String? = null,
  val extra: String? = null,
) {
  fun visible(screen: String): Boolean = windows[screen]?.minimized != true && (fullscreen == null || fullscreen == screen)

  /** Where to draw [screen] this frame; null when it needs a head pose that is not available. */
  fun renderPose(screen: String, head: Pose?, widthM: Float, heightM: Float): Pose? {
    if (fullscreen == screen) return head?.let { it * fullscreenOffset(widthM, heightM) }
    return windows[screen]?.worldPose(head)
  }

  /**
   * [screen] was moved to [world] (a title-bar drag, a grab, Arrange or a dock recall). A
   * following window keeps the new offset from the head. Moves of the fullscreen window are
   * ignored, so its normal placement survives.
   */
  fun moved(screen: String, world: Pose, head: Pose?): SceneLayout {
    val window = windows[screen] ?: return this
    if (fullscreen == screen) return this
    val pose = if (window.mode == WindowMode.Follow) head?.let { it.inverse() * world } ?: return this else world
    return with(screen, window.copy(pose = pose))
  }

  /** Adds [screen] at [world] if cmux has no placement for it yet. */
  fun seeded(screen: String, mode: WindowMode, world: Pose, head: Pose?): SceneLayout {
    if (screen in windows) return this
    val follow = mode == WindowMode.Follow && head != null
    val pose = if (follow) head!!.inverse() * world else world
    return with(screen, WindowPlacement(if (follow) WindowMode.Follow else WindowMode.Fixed, pose))
  }

  /** Switching modes keeps the window where it is: Follow stores the current offset, Fixed the current pose. */
  fun withMode(screen: String, mode: WindowMode, head: Pose?): SceneLayout {
    val window = windows[screen] ?: return this
    val left = exitFullscreen(screen)
    if (window.mode == mode) return left
    val world = window.worldPose(head) ?: return left
    val pose = if (mode == WindowMode.Follow) head?.let { it.inverse() * world } ?: return left else world
    return left.with(screen, window.copy(mode = mode, pose = pose))
  }

  fun minimized(screen: String): SceneLayout {
    val window = windows[screen] ?: return this
    return exitFullscreen(screen).with(screen, window.copy(minimized = true))
  }

  fun shown(screen: String): SceneLayout {
    val window = windows[screen] ?: return this
    return if (window.minimized) with(screen, window.copy(minimized = false)) else this
  }

  fun fullscreenToggled(screen: String): SceneLayout {
    val window = windows[screen] ?: return this
    if (fullscreen == screen) return copy(fullscreen = null)
    return copy(fullscreen = screen).with(screen, window.copy(minimized = false))
  }

  /** Leaves fullscreen, or does nothing when [screen] is given and is not the fullscreen one. */
  fun exitFullscreen(screen: String? = null): SceneLayout =
    if (fullscreen != null && (screen == null || fullscreen == screen)) copy(fullscreen = null) else this

  /** Drops placements of screens cmux no longer has. */
  fun pruned(live: Set<String>): SceneLayout {
    if (windows.keys.all { it in live } && (fullscreen == null || fullscreen in live)) return this
    return copy(windows = windows.filterKeys { it in live }, fullscreen = fullscreen?.takeIf { it in live })
  }

  /** This layout with [local]'s value for each of [screens] (and its fullscreen choice when [fullscreenToo]). */
  fun overlaid(local: SceneLayout, screens: Set<String>, fullscreenToo: Boolean): SceneLayout {
    if (screens.isEmpty() && !fullscreenToo) return this
    val merged = windows.toMutableMap()
    for (screen in screens) {
      val value = local.windows[screen]
      if (value == null) merged.remove(screen) else merged[screen] = value
    }
    return copy(windows = merged, fullscreen = if (fullscreenToo) local.fullscreen else fullscreen)
  }

  private fun with(screen: String, window: WindowPlacement) = copy(windows = windows + (screen to window))

  fun toJson(): JSONObject {
    val json = extra?.let(::JSONObject) ?: JSONObject()
    json.put(SCHEMA_VERSION, VERSION).put(SPACE, LOCAL_FLOOR)
    if (fullscreen != null) json.put(FULLSCREEN, fullscreen) else json.remove(FULLSCREEN)
    val list = JSONObject()
    for ((screen, window) in windows.toSortedMap()) {
      val entry = window.extra?.let(::JSONObject) ?: JSONObject()
      val t = window.pose.t
      val q = window.pose.q
      entry.put(MODE, window.mode.storageValue)
        .put(POSITION, JSONArray().put(t.x.toDouble()).put(t.y.toDouble()).put(t.z.toDouble()))
        .put(ORIENTATION, JSONArray().put(q.x.toDouble()).put(q.y.toDouble()).put(q.z.toDouble()).put(q.w.toDouble()))
        .put(MINIMIZED, window.minimized)
      list.put(screen, entry)
    }
    return json.put(WINDOWS, list)
  }

  companion object {
    const val VERSION = 1
    const val LOCAL_FLOOR = "local_floor"
    private const val SCHEMA_VERSION = "schema_version"
    private const val SPACE = "space"
    private const val FULLSCREEN = "fullscreen_screen_id"
    private const val WINDOWS = "windows"
    private const val MODE = "mode"
    private const val POSITION = "position_m"
    private const val ORIENTATION = "orientation_xyzw"
    private const val MINIMIZED = "minimized"
    private val KNOWN = setOf(SCHEMA_VERSION, SPACE, FULLSCREEN, WINDOWS)
    private val KNOWN_WINDOW = setOf(MODE, POSITION, ORIENTATION, MINIMIZED)

    /**
     * Reads a projection. Null means another schema version or reference space wrote it: vrcode then
     * shows its default layout and leaves cmux's copy alone. Malformed windows are skipped.
     */
    fun fromJson(value: Any?): SceneLayout? {
      val json = value as? JSONObject ?: return if (value == null || value == JSONObject.NULL) SceneLayout() else null
      if (json.length() == 0) return SceneLayout()
      if (json.optInt(SCHEMA_VERSION, -1) != VERSION || json.optString(SPACE) != LOCAL_FLOOR) return null
      val windows = mutableMapOf<String, WindowPlacement>()
      val list = json.optJSONObject(WINDOWS) ?: JSONObject()
      for (screen in list.keys()) windowFrom(list.optJSONObject(screen))?.let { windows[screen] = it }
      val fullscreen = json.optString(FULLSCREEN).takeIf { it in windows }
      return SceneLayout(windows, fullscreen, extraOf(json, KNOWN))
    }

    private fun windowFrom(json: JSONObject?): WindowPlacement? {
      json ?: return null
      val mode = WindowMode.entries.firstOrNull { it.storageValue == json.optString(MODE) } ?: return null
      val t = floats(json.optJSONArray(POSITION), 3) ?: return null
      val q = floats(json.optJSONArray(ORIENTATION), 4) ?: return null
      val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
      if (length < 1e-4f) return null
      val pose = Pose(Vector3(t[0], t[1], t[2]), Quaternion(q[3] / length, q[0] / length, q[1] / length, q[2] / length))
      return WindowPlacement(mode, pose, json.optBoolean(MINIMIZED, false), extraOf(json, KNOWN_WINDOW))
    }

    private fun floats(array: JSONArray?, count: Int): FloatArray? {
      if (array == null || array.length() != count) return null
      val values = FloatArray(count) { array.optDouble(it, Double.NaN).toFloat() }
      return values.takeIf { v -> v.all { it.isFinite() && kotlin.math.abs(it) < 1000f } }
    }

    private fun extraOf(json: JSONObject, known: Set<String>): String? {
      val extra = JSONObject()
      for (key in json.keys()) if (key !in known) extra.put(key, json.get(key))
      return if (extra.length() == 0) null else extra.toString()
    }

    /** Fullscreen fits the panel into a 74° wide by 58° tall field of view, straight ahead of the head. */
    fun fullscreenOffset(widthM: Float, heightM: Float): Pose {
      val distance = max(widthM / (2f * tan(Math.toRadians(37.0)).toFloat()),
        heightM / (2f * tan(Math.toRadians(29.0)).toFloat())).coerceAtLeast(0.6f)
      return Pose(Vector3(0f, 0f, distance))
    }
  }
}
