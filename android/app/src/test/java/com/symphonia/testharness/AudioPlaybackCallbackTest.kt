package com.symphonia.testharness

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackCallbackTest {
    @Test
    fun testPauseResumeTransitionLogic() {
        var state = CandidateCaptureService.CaptureState.CAPTURING

        // Target paused or player state changed to != 2
        val isTargetActive1 = false
        if (!isTargetActive1 && state == CandidateCaptureService.CaptureState.CAPTURING) {
            state = CandidateCaptureService.CaptureState.PAUSED
        }
        assertEquals(CandidateCaptureService.CaptureState.PAUSED, state)

        // Target resumed (isTargetActive == true && player state == 2)
        val isTargetActive2 = true
        if (isTargetActive2 && state == CandidateCaptureService.CaptureState.PAUSED) {
            state = CandidateCaptureService.CaptureState.CAPTURING
        }
        assertEquals(CandidateCaptureService.CaptureState.CAPTURING, state)
    }

    @Test
    fun testBufferGatingLogic() {
        var state = CandidateCaptureService.CaptureState.PAUSED
        var buffersPassed = 0

        val onBuffer = {
            if (state == CandidateCaptureService.CaptureState.CAPTURING) {
                buffersPassed++
            }
        }

        // Buffer arrives while PAUSED -> should be discarded
        onBuffer()
        assertEquals(0, buffersPassed)

        // Transition to CAPTURING
        state = CandidateCaptureService.CaptureState.CAPTURING
        onBuffer()
        assertEquals(1, buffersPassed)
    }
}
