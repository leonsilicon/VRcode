package dev.improve.vrcode.windows

import android.net.Uri
import com.meta.spatial.core.Color4
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.Material
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Sphere
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.Visible

/** A dot at the actual ray crossing, just in front of the edge plane, and a short laser behind it. */
internal class EdgePointerShape(val dot: Pose, val diameter: Float, val laser: Pose, val laserSize: Vector3) {
  companion object {
    fun at(origin: Vector3, direction: Vector3, distance: Float): EdgePointerShape? {
      val length = direction.length()
      if (!(distance > 0f && distance.isFinite() && length > 0f && length.isFinite())) return null
      val dir = direction * (1f / length)
      val rotation = Quaternion.lookRotation(dir)
      val end = (distance - 0.008f).coerceAtLeast(0f)
      val tail = minOf(end, 0.12f)
      val diameter = (distance * 0.009f).coerceIn(0.004f, 0.018f)
      val width = diameter * 0.08f
      return EdgePointerShape(Pose(origin + dir * end, rotation), diameter,
        Pose(origin + dir * (end - tail / 2f), rotation), Vector3(width, width, tail))
    }
  }
}

/**
 * The focus dot and laser while aiming past a panel, where the SDK cannot draw its own cursor.
 * These meshes never take input or collisions. The panel's usual pointer resumes on its surface.
 */
internal class EdgePointer : AutoCloseable {
  private val dot = Marker(sphere = true)
  private val laser = Marker(sphere = false)

  fun show(origin: Vector3, direction: Vector3, distance: Float, dragging: Boolean) {
    val shape = EdgePointerShape.at(origin, direction, distance) ?: return hide()
    dot.show(shape.dot, Vector3(shape.diameter, shape.diameter, shape.diameter), dragging)
    laser.show(shape.laser, shape.laserSize, dragging)
  }

  fun hide() { dot.hide(); laser.hide() }

  override fun close() { dot.close(); laser.close() }

  private class Marker(private val sphere: Boolean) {
    private var entity: Entity? = null
    private var visible = false
    private var dragging = false

    fun show(pose: Pose, size: Vector3, dragging: Boolean) {
      val current = entity
      if (current == null) {
        entity = Entity.create(listOf(
          Mesh(Uri.parse(if (sphere) "mesh://sphere" else "mesh://box"), MeshCollision.NoCollision),
          if (sphere) Sphere(0.5f) else Box(Vector3(-0.5f, -0.5f, -0.5f), Vector3(0.5f, 0.5f, 0.5f)),
          material(dragging), Transform(pose), Scale(size), Visible(true),
        ))
      } else {
        current.setComponent(Transform(pose))
        current.setComponent(Scale(size))
        if (this.dragging != dragging) current.setComponent(material(dragging))
        if (!visible) current.setComponent(Visible(true))
      }
      this.dragging = dragging
      visible = true
    }

    fun hide() {
      if (visible) entity?.setComponent(Visible(false))
      visible = false
    }

    fun close() {
      entity?.destroy()
      entity = null
      visible = false
    }

    private fun material(dragging: Boolean) = Material().apply {
      baseColor = if (dragging) Color4(0.49f, 0.78f, 1f, 1f) else Color4(1f, 1f, 1f, 1f)
      unlit = true
    }
  }
}
