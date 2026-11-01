package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class FailureLogTest {
  @Test fun repeatedFrameFailuresAreLoggedOnceThenAsPeriodicCounts() {
    val lines = mutableListOf<Pair<String, Throwable?>>()
    val log = FailureLog("test") { message, error -> lines += message to error }
    val error = IllegalStateException("panel gone")
    repeat(1_800) { log.report("scene tick", error) }
    assertEquals(3, lines.size)
    assertSame(error, lines[0].second)
    assertTrue(lines[1].first.contains("900 times"))
    assertNull(lines[1].second)
    assertTrue(lines[2].first.contains("1800 times"))
  }

  @Test fun distinctFailuresEachGetAStackTraceAndMemoryStaysBounded() {
    val lines = mutableListOf<Pair<String, Throwable?>>()
    val log = FailureLog("test") { message, error -> lines += message to error }
    repeat(500) { log.report("tick", IllegalArgumentException("entity $it")) }
    assertEquals(500, lines.size)
    assertTrue(lines.all { it.second != null })
  }
}
