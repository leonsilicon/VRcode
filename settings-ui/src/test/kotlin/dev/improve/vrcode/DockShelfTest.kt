package dev.improve.vrcode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DockShelfTest {
  @Test fun theIconUnderTheFingerGrowsMostAndTheEffectFadesWithinItsReach() {
    assertEquals(DockShelf.MAGNIFY, DockShelf.magnification(0f), 1e-6f)
    assertEquals(DockShelf.magnification(0.5f), DockShelf.magnification(-0.5f), 1e-6f)
    assertTrue(DockShelf.magnification(0.5f) < DockShelf.MAGNIFY)
    assertTrue(DockShelf.magnification(1f) < DockShelf.magnification(0.5f))
    assertEquals(1f, DockShelf.magnification(DockShelf.REACH), 1e-6f)
    assertEquals(1f, DockShelf.magnification(4f), 1e-6f)
  }

  @Test fun magnifiedNeighboursBarelyTouchBecauseIconsNeverMove() {
    // Icons one slot apart, the finger anywhere between them: their half-widths add up to about a slot.
    for (step in 0..10) {
      val finger = step / 10f
      val halves = DockShelf.TILE / 2 * (DockShelf.magnification(finger) + DockShelf.magnification(1f - finger))
      assertTrue("overlap ${halves - 1f} at $finger", halves <= 1.01f)
    }
  }

  @Test fun theShelfHugsItsTilesWithTheSameMarginOnEverySide() {
    assertEquals(DockShelf.TILE + 2 * DockShelf.PAD, DockShelf.HEIGHT, 1e-6f)
    // An end tile's edge is PAD from the shelf's end, as its top and bottom are from the shelf's.
    assertEquals(DockShelf.PAD, DockShelf.END - DockShelf.TILE / 2, 1e-6f)
    assertTrue(DockShelf.DOT < DockShelf.PAD)
  }

  @Test fun windowTitlesAreShortenedToFitTheirTiles() {
    assertEquals("zsh", DockShelf.shortTitle("zsh — ~/repo"))
    assertEquals("claude", DockShelf.shortTitle("claude"))
    assertEquals("vrcode", DockShelf.shortTitle("~/Developer/github/ImproveLabs/vrcode"))
    assertEquals("repo", DockShelf.shortTitle("leon@mac: ~/repo/"))
    assertEquals("3", DockShelf.shortTitle("Terminal 3"))
    assertEquals("~", DockShelf.shortTitle(" ~ "))
    assertEquals("/", DockShelf.shortTitle("/"))
  }
}
