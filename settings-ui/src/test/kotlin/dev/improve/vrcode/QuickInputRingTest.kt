package dev.improve.vrcode

import dev.improve.vrcode.QuickInputSlot.*
import dev.improve.vrcode.pie.PieIcon
import dev.improve.vrcode.pie.pieLabelCentre
import org.junit.Assert.*
import org.junit.Test

class QuickInputRingTest {
  @Test fun theDefaultRingIsEscCtrlCTabAndEnterOnTheDiagonals() {
    val ring = AppSettings().quickInputs
    assertEquals(QuickInputRing.Default, ring)
    assertEquals(
      listOf(TopRight to QuickInput.CtrlC, BottomRight to QuickInput.Enter, BottomLeft to QuickInput.Tab, TopLeft to QuickInput.Escape),
      ring.placed,
    )
    for (slot in listOf(Top, Right, Bottom, Left)) assertNull(slot.label, ring[slot])
    // Ctrl-C is a real chord, not the letter c.
    assertEquals("c", QuickInput.CtrlC.key)
    assertTrue(QuickInput.CtrlC.ctrl)
    assertEquals(PieIcon.Interrupt, QuickInput.CtrlC.icon)
  }

  @Test fun slotsPointWhereTheyAreNamed() {
    assertEquals(listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f), QuickInputSlot.entries.map { it.degrees })
  }

  @Test fun storageRoundTripsAndDamagedValuesFallBack() {
    val custom = QuickInputRing.ring(Top to QuickInput.Up, Bottom to QuickInput.Down, Right to QuickInput.Keyboard)
    assertEquals("up,,keyboard,,down,,,", custom.storageValue)
    assertEquals(custom, QuickInputRing.fromStorage(custom.storageValue))
    assertEquals(QuickInputRing.Default, QuickInputRing.fromStorage(QuickInputRing.Default.storageValue))
    // Every direction empty is a real choice: the pinch then opens nothing.
    assertEquals(QuickInputRing.Empty, QuickInputRing.fromStorage(",,,,,,,"))
    // A missing or damaged preference gives the default; unknown inputs leave their direction empty.
    assertEquals(QuickInputRing.Default, QuickInputRing.fromStorage(null))
    assertEquals(QuickInputRing.Default, QuickInputRing.fromStorage("escape,enter"))
    assertEquals(QuickInputRing.ring(Top to QuickInput.Enter), QuickInputRing.fromStorage("enter,removed-key,,,,,,"))
    // Repeats keep their first direction clockwise from the top.
    assertEquals(QuickInputRing.ring(Top to QuickInput.Enter), QuickInputRing.fromStorage("enter,,enter,,,,,"))
    for (input in QuickInput.entries) assertEquals(input, QuickInput.fromStorage(input.storageValue))
    assertEquals(QuickInput.entries.size, QuickInput.entries.map { it.storageValue }.toSet().size)
  }

  @Test fun puttingAPlacedInputSwapsSoNothingIsLost() {
    val ring = QuickInputRing.Default
    // Enter onto Ctrl-C's direction: Ctrl-C moves to where Enter was.
    val swapped = ring.with(TopRight, QuickInput.Enter)
    assertEquals(QuickInput.Enter, swapped[TopRight])
    assertEquals(QuickInput.CtrlC, swapped[BottomRight])
    // Onto an empty direction: it simply moves.
    val moved = ring.with(Top, QuickInput.Escape)
    assertEquals(QuickInput.Escape, moved[Top])
    assertNull(moved[TopLeft])
    // A new input replaces what was there; null empties the direction.
    assertEquals(QuickInput.ShiftTab, ring.with(TopLeft, QuickInput.ShiftTab)[TopLeft])
    assertNull(ring.with(TopRight, null)[TopRight])
    assertEquals(4, ring.with(TopRight, QuickInput.Enter).placed.size)
  }

  @Test fun quickInputsPersistThroughSettingsSync() {
    val custom = AppSettings(quickInputs = QuickInputRing.ring(Top to QuickInput.Up, BottomRight to QuickInput.CtrlD))
    val values = SettingsSync.values(custom)
    assertEquals(listOf("up", "", "", "ctrl_d", "", "", "", ""), values["quick_inputs"])
    assertEquals(custom, SettingsSync.apply(AppSettings(), values))
    assertTrue(SettingsSync.json(values).contains("\"quick_inputs\":[\"up\",\"\",\"\",\"ctrl_d\",\"\",\"\",\"\",\"\"]"))
    // Wrong shapes from the Mac are ignored rather than wiping the ring.
    for (bad in listOf("up", listOf("up"), 3, null)) {
      assertEquals("$bad", custom, SettingsSync.apply(custom, mapOf("quick_inputs" to bad)))
    }
    // JSON null inside the list empties that direction.
    assertNull(SettingsSync.apply(custom, mapOf("quick_inputs" to listOf(null, "", "", "ctrl_d", "", "", "", "")))
      .quickInputs[Top])
  }

  @Test fun theMacGetsTheKeypadAndEveryInput() {
    val pages = SettingsSync.schema()["pages"] as List<*>
    val customization = pages.map { it as Map<*, *> }.single { it["id"] == "customization" }
    assertTrue((customization["tabs"] as List<*>).any { (it as Map<*, *>)["id"] == CustomizationTab.QuickInputs.name })
    val section = (customization["sections"] as List<*>).map { it as Map<*, *> }.single { it["tab"] == CustomizationTab.QuickInputs.name }
    val items = (section["items"] as List<*>).map { it as Map<*, *> }
    val editor = items.single { it["type"] == "quick_inputs" }
    assertEquals(QuickInputSlot.entries.map { it.label }, (editor["slots"] as List<*>).map { (it as Map<*, *>)["label"] })
    assertEquals(QuickInput.entries.map { it.storageValue }, (editor["options"] as List<*>).map { (it as Map<*, *>)["value"] })
    val reset = items.single { it["type"] == "reset" }
    assertEquals(mapOf("quick_inputs" to QuickInputRing.Default.storageValues), reset["values"])
  }

  @Test fun labelsOnAGivenSpokeMatchTheEvenlySpacedOnes() {
    for (i in 0 until 8) {
      val degrees = 45f * i
      assertEquals(pieLabelCentre(i, 8).first, pieLabelCentre(degrees).first, 1e-6f)
      assertEquals(pieLabelCentre(i, 8).second, pieLabelCentre(degrees).second, 1e-6f)
    }
  }
}
