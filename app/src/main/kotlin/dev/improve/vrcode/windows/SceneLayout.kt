package dev.improve.vrcode.windows

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import dev.improve.vrcode.WindowMode
import dev.improve.xmux.spatial.WindowLayout
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan
import org.json.JSONArray
import org.json.JSONObject

/**
 * One terminal window as cmux stores it. [pose] is in the scene's reference space for a
 * [WindowMode.Fixed] window. For a [WindowMode.Follow] one it is an offset from the user's position,
 * in the scene's axes: the window travels with the user as they walk, but turning or tilting the
 * head does not move it, so the windows stay around the user. A window's pixel size is not here:
 * it follows the cmux terminal grid. [scale] is the uniform enlargement the corner handles give it
 * on top of that, text included.
 */
data class WindowPlacement(
  val mode: WindowMode,
  val pose: Pose,
  val minimized: Boolean = false,
  /** Fields this build does not know, as JSON text, written back unchanged. */
  val extra: String? = null,
  val scale: Float = 1f,
) {
  /**
   * The window's pose in the scene, outside fullscreen, given the user's (eased) position. Null when
   * it follows the user and that position is unknown.
   */
  fun worldPose(user: Vector3?): Pose? = if (mode == WindowMode.Follow) user?.let { Pose(it + pose.t, pose.q) } else pose
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

  /**
   * Where to draw [screen] this frame. Fullscreen is locked to the [head]; a following window hangs
   * from the [user]'s position ([userFollow]). Null when the pose it needs is not available.
   */
  fun renderPose(screen: String, head: Pose?, user: Vector3?, widthM: Float, heightM: Float): Pose? {
    if (fullscreen == screen) return head?.let { it * fullscreenOffset(widthM, heightM) }
    return windows[screen]?.worldPose(user)
  }

  /**
   * [screen] was moved to [world] (a title-bar drag, a grab, Arrange or a dock recall). A
   * following window keeps the new offset from the [user]. Moves of the fullscreen window are
   * ignored, so its normal placement survives.
   */
  fun moved(screen: String, world: Pose, user: Vector3?): SceneLayout {
    val window = windows[screen] ?: return this
    if (fullscreen == screen) return this
    val pose = if (window.mode == WindowMode.Follow) user?.let { offset(world, it) } ?: return this else world
    return with(screen, window.copy(pose = pose))
  }

  /** [screen] was scaled with its corner handles. Out-of-range or invalid scales are ignored. */
  fun scaled(screen: String, scale: Float): SceneLayout {
    val window = windows[screen] ?: return this
    if (!validScale(scale) || window.scale == scale) return this
    return with(screen, window.copy(scale = scale))
  }

  /** Adds [screen] at [world] if cmux has no placement for it yet. */
  fun seeded(screen: String, mode: WindowMode, world: Pose, user: Vector3?): SceneLayout {
    if (screen in windows) return this
    val follow = mode == WindowMode.Follow && user != null
    val pose = if (follow) offset(world, user!!) else world
    return with(screen, WindowPlacement(if (follow) WindowMode.Follow else WindowMode.Fixed, pose))
  }

  /** A quick-action terminal opens along the current gaze, even if its arc slot was already seeded. */
  fun openedInFront(screen: String, mode: WindowMode, head: Pose, user: Vector3?, distanceM: Float): SceneLayout {
    val world = WindowLayout.inFrontOfHead(head, distanceM)
    return exitFullscreen().seeded(screen, mode, world, user).shown(screen).moved(screen, world, user)
  }

  /** Switching modes keeps the window where it is: Follow stores the current offset, Fixed the current pose. */
  fun withMode(screen: String, mode: WindowMode, user: Vector3?): SceneLayout {
    val window = windows[screen] ?: return this
    val left = exitFullscreen(screen)
    if (window.mode == mode) return left
    val world = window.worldPose(user) ?: return left
    val pose = if (mode == WindowMode.Follow) user?.let { offset(world, it) } ?: return left else world
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

  /** A following window's stored pose: [world] relative to the [user]'s position, keeping its orientation. */
  private fun offset(world: Pose, user: Vector3) = Pose(world.t - user, world.q)

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
      if (window.scale != 1f) entry.put(SCALE, window.scale.toDouble()) else entry.remove(SCALE)
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
    private const val SCALE = "scale"
    private val KNOWN = setOf(SCHEMA_VERSION, SPACE, FULLSCREEN, WINDOWS)
    private val KNOWN_WINDOW = setOf(MODE, POSITION, ORIENTATION, MINIMIZED, SCALE)
    /** The corner handles' range: from a fifth of the grid's size to ten times it. */
    const val MIN_SCALE = 0.2f
    const val MAX_SCALE = 10f

    fun validScale(scale: Float) = scale.isFinite() && scale in MIN_SCALE..MAX_SCALE

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
      val scale = json.optDouble(SCALE, 1.0).toFloat().takeIf(::validScale) ?: 1f
      return WindowPlacement(mode, pose, json.optBoolean(MINIMIZED, false), extraOf(json, KNOWN_WINDOW), scale)
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

    /**
     * The point Follow windows hang from: the head's position, smoothed ([UserFollow]). Leaning in
     * to read, swaying, or the few centimetres the eyes shift when the head turns stay inside the
     * 40 cm dead zone and leave the windows still; walking, standing up or sitting down carries them
     * along on a critically damped spring (0.35 s across the floor, 0.6 s in height, so a walking
     * gait's bob barely reaches them), and they catch up once the user stops. A tracking jump of
     * over 3 m snaps.
     */
    internal fun userFollow() = UserFollow(deadZoneM = 0.4f, horizontalTimeS = 0.35f, verticalTimeS = 0.6f, snapM = 3f)

    /** Fullscreen fits the panel into a 74° wide by 58° tall field of view, straight ahead of the head. */
    fun fullscreenOffset(widthM: Float, heightM: Float): Pose {
      val distance = max(widthM / (2f * tan(Math.toRadians(37.0)).toFloat()),
        heightM / (2f * tan(Math.toRadians(29.0)).toFloat())).coerceAtLeast(0.6f)
      return Pose(Vector3(0f, 0f, distance))
    }
  }
}
