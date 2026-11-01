package dev.improve.vrcode

import org.junit.Assert.*
import org.junit.Test

class BoundaryRequestTest {
  @Test fun aPositiveRefusalRetriesOncePassthroughHasStarted() {
    val state = BoundaryRequest()
    val refused = 1000528000 // XR_BOUNDARY_VISIBILITY_SUPPRESSION_NOT_ALLOWED_META
    assertEquals(refused, state.update(1, true, 1000) { refused })
    assertNull(state.update(1, true, 1499) { fail("Must throttle retries"); 0 })
    assertEquals(0, state.update(1, true, 1500) { 0 })
  }

  @Test fun disablingPassthroughRestoresBoundaryImmediatelyDuringRetryDelay() {
    val state = BoundaryRequest()
    state.update(1, true, 1000) { 1000528000 }
    assertEquals(0, state.update(1, false, 1001) { suppress -> assertFalse(suppress); 0 })
    assertNull(state.update(1, false, 2500) { fail("Restoration already accepted"); 0 })
  }

  @Test fun acceptedSuppressionIsReassertedAfterSystemInterruptions() {
    val state = BoundaryRequest()
    state.update(1, true, 1000) { 0 }
    assertNull(state.update(1, true, 1999) { fail("Must throttle requests"); 0 })
    assertEquals(0, state.update(1, true, 2000) { suppress -> assertTrue(suppress); 0 })
  }

  @Test fun newSessionAndResumeBothReapplyTheBoundaryRequest() {
    val state = BoundaryRequest()
    state.update(1, false, 1000) { 0 }
    assertEquals(0, state.update(2, false, 1001) { 0 })
    state.reset()
    assertEquals(0, state.update(2, false, 1002) { 0 })
  }

  @Test fun missingSessionNeverCallsOpenXr() {
    assertNull(BoundaryRequest().update(0, true, 1000) { fail("No session"); 0 })
  }

  @Test fun unsupportedRuntimeKeepsItsBoundaryWithoutRepeatedErrors() {
    val state = BoundaryRequest()
    assertEquals(-8, state.update(1, true, 1000) { -8 })
    assertNull(state.update(1, true, 2000) { fail("Unsupported for this session"); 0 })
    assertEquals(0, state.update(2, true, 2001) { 0 })
  }
}
