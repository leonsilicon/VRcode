package dev.improve.vrcode.pie

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PieGeometryTest {
  private val h = PIE_ITEM_HEIGHT

  /** A horizontal stadium: centre, total width, height [h]. */
  private class Pill(val x: Float, val y: Float, val w: Float)

  private fun icon(i: Int, count: Int): Pair<Float, Float> {
    val a = 2 * Math.PI * i / count
    return (PIE_ITEM_RADIUS * sin(a)).toFloat() to (PIE_ITEM_RADIUS * cos(a)).toFloat()
  }

  private fun label(i: Int, count: Int) = pieLabelCentre(i, count).let { Pill(it.first, it.second, PIE_LABEL_WIDTH) }

  /** Distance from ([cx], [cy]) to the nearest point of pill [p]; negative inside it. */
  private fun distance(p: Pill, cx: Float, cy: Float): Float {
    val half = (p.w - h) / 2
    val nx = cx.coerceIn(p.x - half, p.x + half)
    return hypot(cx - nx, cy - p.y) - h / 2
  }

  /** How far out along item [i]'s spoke pill [p] begins: its nearest point in that direction. */
  private fun start(p: Pill, i: Int, count: Int): Float {
    val a = 2 * Math.PI * i / count
    val s = sin(a).toFloat()
    val c = cos(a).toFloat()
    return p.x * s + p.y * c - ((p.w - h) / 2 * abs(s) + h / 2)
  }

  @Test fun theLabelSitsAsFarPastItsIconAsTheIconIsFromTheCentre() {
    assertEquals(2 * PIE_ITEM_RADIUS, PIE_LABEL_START, 1e-6f)
    for (count in 1..8) for (i in 0 until count) {
      val label = label(i, count)
      // Every label begins the same distance out along its spoke, so the hand reaches each one alike.
      assertEquals("item $i of $count", PIE_LABEL_START, start(label, i, count), 1e-5f)
      // Nowhere nearer its icon's centre than the icon is to the pie's.
      val (ix, iy) = icon(i, count)
      assertTrue("item $i of $count: label clear of its icon", distance(label, ix, iy) >= PIE_ITEM_RADIUS - 1e-5f)
    }
    // Straight up and to the side the label's nearest point is on the spoke, exactly that far.
    for (i in 0 until 4) {
      val (ix, iy) = icon(i, 4)
      assertEquals("item $i of 4", PIE_ITEM_RADIUS, distance(label(i, 4), ix, iy), 1e-5f)
    }
  }

  @Test fun theRayKeepsItsItemAcrossTheGapAndRunsItOnlyOnTheShownLabel() {
    val angles = pieEvenAngles(8)
    for (i in angles.indices) {
      val a = 2 * Math.PI * i / 8
      val s = sin(a).toFloat()
      val c = cos(a).toFloat()
      fun at(d: Float, across: Float = 0f) = (s * d + c * across) to (c * d - s * across)
      fun target(p: Pair<Float, Float>, highlighted: Int?) = pieRayTarget(p.first, p.second, angles, highlighted)
      val (lx, ly) = pieLabelCentre(i, 8)
      // Its icon, with or without its label showing.
      assertEquals(PieRayTarget(i, onLabel = false), target(at(PIE_ITEM_RADIUS), null))
      assertEquals(PieRayTarget(i, onLabel = false), target(at(PIE_ITEM_RADIUS), (i + 1) % 8))
      // Along the gap out to the label: nothing until the label shows, then still the item.
      for (d in listOf(0.21f, 0.26f, 0.31f)) {
        assertEquals("item $i at $d", null, target(at(d), null))
        assertEquals("item $i at $d", PieRayTarget(i, onLabel = false), target(at(d), i))
        assertEquals("item $i at $d, a bit off the spoke", PieRayTarget(i, onLabel = false), target(at(d, h / 3), i))
        assertEquals("item $i at $d, beside the strip", null, target(at(d, h), i))
      }
      // The label runs the item only while it shows.
      assertEquals(PieRayTarget(i, onLabel = true), target(lx to ly, i))
      assertEquals(null, target(lx to ly, null))
      assertEquals(null, target(lx to ly, (i + 1) % 8))
    }
    // The centre runs nothing.
    assertEquals(null, pieRayTarget(0f, 0f, angles, 0))
  }

  @Test fun everySpokeFitsOnThePanelAndClearsTheOtherIcons() {
    for (count in 1..8) for (i in 0 until count) {
      val p = label(i, count)
      assertTrue("item $i of $count: label at (${p.x}, ${p.y}) inside the panel",
        abs(p.x) + p.w / 2 <= 0.5f && abs(p.y) + h / 2 <= 0.5f)
      for (j in 0 until count) if (j != i) {
        val (jx, jy) = icon(j, count)
        assertTrue("item $i of $count: its label clears icon $j", distance(p, jx, jy) > h / 2)
      }
      // The centre circle (dead zone) leaves room before the icons.
      assertTrue(PIE_ITEM_RADIUS - h / 2 > 0.1f)
    }
  }
}
