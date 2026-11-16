package com.symphonia.testharness

import org.junit.Assert.assertEquals
import org.junit.Test

class TelemetryExportTest {
    @Test
    fun testSummaryComputationWithNullableCollector() {
        val manager = TrialExecutionManager(null)
        manager.recordTrialResult(1, "Spotify", "InternalSpeaker", 30L, 1000L, 10L, 10L)
        manager.recordTrialResult(2, "Spotify", "InternalSpeaker", 32L, 1000L, 10L, 10L)
        manager.recordTrialResult(3, "Spotify", "InternalSpeaker", 28L, 1000L, 10L, 10L)

        val summary = manager.computeSummary("Spotify", "InternalSpeaker")
        assertEquals(30.0, summary.meanLatencyMs, 0.01)
        assertEquals("PASS", summary.passStatus)
        assertEquals(3, summary.trials.size)
        assertEquals(3000L, summary.totalBytesCaptured)
    }
}
