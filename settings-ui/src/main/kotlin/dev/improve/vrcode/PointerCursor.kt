package dev.improve.vrcode

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

enum class PointerCursorStyle(val storageValue: String, val label: String) {
  Arrow("arrow", "Arrow"),
  Dot("dot", "Dot"),
  Crosshair("crosshair", "Crosshair"),
  None("none", "No cursor");

  companion object {
    fun fromStorage(value: String?): PointerCursorStyle = entries.firstOrNull { it.storageValue == value } ?: Arrow
  }
}

/** A panel overlay: the arrow tip or reticle centre is the exact aim point; it never takes input. */
class PointerCursor : Drawable() {
  var style: PointerCursorStyle = PointerCursorStyle.Arrow
    set(value) {
      if (field == value) return
      field = value
      invalidateSelf()
    }
  private val arrow = Path().apply {
    moveTo(0f, 0f)
    lineTo(0f, 23f)
    lineTo(6f, 17f)
    lineTo(10.5f, 27f)
    lineTo(15f, 25f)
    lineTo(10.5f, 15f)
    lineTo(20f, 15f)
    close()
  }
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
  private var x = 0f
  private var y = 0f
  private var scale = 1f
  private var pressed = false

  fun pointAt(x: Float, y: Float, scale: Float, pressed: Boolean) {
    if (this.x == x && this.y == y && this.scale == scale && this.pressed == pressed) return
    this.x = x
    this.y = y
    this.scale = scale
    this.pressed = pressed
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    if (style == PointerCursorStyle.None) return
    val save = canvas.save()
    canvas.translate(x, y)
    canvas.scale(scale, scale)
    if (pressed) {
      paint.style = Paint.Style.FILL
      paint.color = 0x664A90FF
      canvas.drawCircle(0f, 0f, 9f, paint)
    }
    paint.style = Paint.Style.FILL
    paint.color = if (pressed) 0xFF7DC7FF.toInt() else Color.WHITE
    when (style) {
      PointerCursorStyle.Arrow -> canvas.drawPath(arrow, paint)
      PointerCursorStyle.Dot -> canvas.drawCircle(0f, 0f, 4.5f, paint)
      PointerCursorStyle.Crosshair -> {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0xFF101820.toInt()
        drawCrosshair(canvas)
        paint.strokeWidth = 2f
        paint.color = if (pressed) 0xFF7DC7FF.toInt() else Color.WHITE
        drawCrosshair(canvas)
      }
      PointerCursorStyle.None -> Unit
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.8f
    paint.color = 0xFF101820.toInt()
    when (style) {
      PointerCursorStyle.Arrow -> canvas.drawPath(arrow, paint)
      PointerCursorStyle.Dot -> canvas.drawCircle(0f, 0f, 4.5f, paint)
      else -> Unit
    }
    canvas.restoreToCount(save)
  }

  private fun drawCrosshair(canvas: Canvas) {
    canvas.drawLine(-10f, 0f, 10f, 0f, paint)
    canvas.drawLine(0f, -10f, 0f, 10f, paint)
  }

  override fun setAlpha(alpha: Int) {}
  override fun setColorFilter(colorFilter: ColorFilter?) {}
  @Deprecated("Required by Drawable")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
