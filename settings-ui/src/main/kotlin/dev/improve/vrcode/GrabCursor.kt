package dev.improve.vrcode

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The left hand's cursor, a hand like a desktop's grab cursor, so the left ray reads as "move
 * this window": an open hand while it points at a window it can move, a closed blue fist while
 * it holds one. It is a left hand seen from behind, thumb on the right, the way the wearer sees
 * their own hand reaching out. The middle of the hand is the aim point. It never takes input.
 */
class GrabCursor : Drawable() {
  private val open = centred(openHand())
  private val closed = centred(closedHand())
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
  private var x = 0f
  private var y = 0f
  private var scale = 1f
  var grabbing = false
    private set

  fun pointAt(x: Float, y: Float, scale: Float, grabbing: Boolean) {
    if (this.x == x && this.y == y && this.scale == scale && this.grabbing == grabbing) return
    this.x = x
    this.y = y
    this.scale = scale
    this.grabbing = grabbing
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    val path = if (grabbing) closed else open
    val save = canvas.save()
    canvas.translate(x, y)
    canvas.scale(scale, scale)
    paint.style = Paint.Style.FILL
    paint.color = if (grabbing) GRABBING else OPEN
    canvas.drawPath(path, paint)
    // The outline also fills the slits between the fingers, drawing them as dark lines.
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = OUTLINE
    paint.color = INK
    canvas.drawPath(path, paint)
    canvas.restoreToCount(save)
  }

  override fun setAlpha(alpha: Int) {}
  override fun setColorFilter(colorFilter: ColorFilter?) {}
  @Deprecated("Required by Drawable")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  companion object {
    /** Both hands fit a square this many cursor units across, centred on the aim point. */
    const val EXTENT = 26f
    const val OPEN = 0xFFFFFFFF.toInt()
    /** The pressed blue of the right hand's arrow. */
    const val GRABBING = 0xFF7DC7FF.toInt()
    const val INK = 0xFF101820.toInt()
    private const val OUTLINE = 1.8f
    private const val FINGER = 3.6f
    private const val GAP = 0.45f

    /** Pinky to index, left to right, fingers up and spread, the thumb out to the lower right. */
    private fun openHand(): Path {
      val hand = roundRect(-7.9f, -3f, 7.9f, 9.5f, 4.5f)
      for ((i, top) in listOf(-8f, -11.5f, -12.5f, -11f).withIndex()) hand.union(finger(i, top, 1f))
      hand.union(capsule(6f, 6.5f, 11.5f, -0.5f))
      return hand
    }

    /** A fist from behind: the knuckles of four curled fingers along the top, the thumb at the side. */
    private fun closedHand(): Path {
      val hand = roundRect(-7.9f, -3.5f, 7.9f, 9f, 4.5f)
      for ((i, top) in listOf(-5f, -6.4f, -6.8f, -6.2f).withIndex()) hand.union(finger(i, top, 0f))
      hand.union(capsule(5.2f, 7f, 10.6f, 1.6f))
      return hand
    }

    private fun finger(index: Int, top: Float, bottom: Float): Path {
      val left = -7.9f + index * (FINGER + GAP)
      return roundRect(left, top, left + FINGER, bottom, FINGER / 2f)
    }

    /** A round-ended stroke [FINGER] wide from ([x0], [y0]) to ([x1], [y1]). */
    private fun capsule(x0: Float, y0: Float, x1: Float, y1: Float): Path {
      val dx = x1 - x0
      val dy = y1 - y0
      val length = kotlin.math.hypot(dx, dy) + FINGER
      val path = roundRect(-FINGER / 2f, -length / 2f, FINGER / 2f, length / 2f, FINGER / 2f)
      // The capsule starts upright; turn it to point along (dx, dy), clockwise being positive.
      val degrees = Math.toDegrees(kotlin.math.atan2(dx.toDouble(), -dy.toDouble())).toFloat()
      path.transform(Matrix().apply { setRotate(degrees); postTranslate((x0 + x1) / 2f, (y0 + y1) / 2f) })
      return path
    }

    private fun roundRect(left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
      Path().apply { addRoundRect(RectF(left, top, right, bottom), radius, radius, Path.Direction.CW) }

    private fun Path.union(other: Path) {
      op(other, Path.Op.UNION)
    }

    /** Moves [path] so the middle of its bounds is the origin, the aim point. */
    private fun centred(path: Path): Path {
      val bounds = RectF()
      path.computeBounds(bounds, true)
      path.offset(-bounds.centerX(), -bounds.centerY())
      return path
    }
  }
}
