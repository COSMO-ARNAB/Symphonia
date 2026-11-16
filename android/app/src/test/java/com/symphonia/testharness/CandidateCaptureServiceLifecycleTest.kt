package com.symphonia.testharness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CandidateCaptureServiceLifecycleTest {
    @Test
    fun testCaptureStateEnumValues() {
        assertEquals(5, CandidateCaptureService.CaptureState.values().size)
        assertNotNull(CandidateCaptureService.CaptureState.valueOf("IDLE"))
        assertNotNull(CandidateCaptureService.CaptureState.valueOf("REQUESTING_CAPTURE"))
        assertNotNull(CandidateCaptureService.CaptureState.valueOf("CAPTURING"))
        assertNotNull(CandidateCaptureService.CaptureState.valueOf("PAUSED"))
        assertNotNull(CandidateCaptureService.CaptureState.valueOf("STOPPED"))
    }
}
