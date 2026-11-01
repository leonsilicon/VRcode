package dev.improve.vrcode.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicNoticeTest {
  @Test fun aMicThatCanHearStartsWithoutANotice() {
    assertNull(MicNotice.atStart(allowed = true, muted = false, connected = true, address = "10.0.0.2:7273"))
  }

  @Test fun aMutedQuestMicStopsTheFistFromListening() {
    assertEquals(MicNotice.MUTED, MicNotice.atStart(allowed = true, muted = true, connected = true, address = "10.0.0.2:7273"))
  }

  @Test fun problemsComeInTheOrderTheUserFixesThem() {
    // Permission first (nothing else matters without it), then the Quest's mute, then the Mac.
    assertEquals(MicNotice.NO_PERMISSION, MicNotice.atStart(allowed = false, muted = true, connected = false, address = null))
    assertEquals(MicNotice.MUTED, MicNotice.atStart(allowed = true, muted = true, connected = false, address = null))
    val offline = MicNotice.atStart(allowed = true, muted = false, connected = false, address = null)
    assertTrue(offline!!.startsWith("Mac speech service not connected"))
    assertTrue(offline.endsWith("(no address)"))
  }

  @Test fun silencingShowsOnTheListeningPaneAndClearsWhenItEnds() {
    val silenced = MicNotice.whileListening(null, silenced = true)
    assertEquals(MicNotice.SILENCED, silenced)
    assertNull(MicNotice.whileListening(silenced, silenced = false))
  }

  @Test fun anotherNoticeIsLeftAloneWhenSilencingEnds() {
    val other = "Mac speech service disconnected"
    assertEquals(other, MicNotice.whileListening(other, silenced = false))
    assertNull(MicNotice.whileListening(null, silenced = false))
  }
}
