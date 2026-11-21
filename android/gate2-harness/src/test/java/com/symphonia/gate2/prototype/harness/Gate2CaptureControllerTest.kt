package com.symphonia.gate2.prototype.harness

import com.symphonia.gate2.contracts.Gate2Failure
import com.symphonia.gate2.contracts.ShareStoppedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class Gate2CaptureControllerTest {

    private lateinit var controller: Gate2CaptureController

    @Before
    fun setUp() {
        controller = Gate2CaptureController()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(Gate2CaptureController.CaptureState.Idle, controller.state.value)
    }

    @Test
    fun testFailClosedOnInvalidUidOrBlankLabel() {
        val s1 = controller.requestCapture("TestApp", -1)
        assertTrue(s1 is Gate2CaptureController.CaptureState.Stopped)
        val stopped1 = s1 as Gate2CaptureController.CaptureState.Stopped
        assertEquals(ShareStoppedReason.CAPTURE_UNAVAILABLE, stopped1.reason)
        assertEquals(Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE, stopped1.failure)

        controller.resetToIdle()

        val s2 = controller.requestCapture("", 10042)
        assertTrue(s2 is Gate2CaptureController.CaptureState.Stopped)
    }

    @Test
    fun testSuccessfulCaptureAndLifecycleTransitions() {
        val requestedState = controller.requestCapture("Spotify", 10042)
        assertEquals(Gate2CaptureController.CaptureState.RequestingCapture, requestedState)

        controller.onCaptureStarted(100_000_000L)
        val capturingState = controller.state.value
        assertTrue(capturingState is Gate2CaptureController.CaptureState.Capturing)
        val cap = capturingState as Gate2CaptureController.CaptureState.Capturing
        assertEquals("Spotify", cap.appLabel)
        assertEquals(10042, cap.targetUid)
        assertTrue(controller.isCapturing())

        // OS pause: player stopped/switched
        controller.applyPlaybackActive(false)
        val pausedState = controller.state.value
        assertTrue(pausedState is Gate2CaptureController.CaptureState.Paused)
        val paused = pausedState as Gate2CaptureController.CaptureState.Paused
        assertEquals("Spotify", paused.appLabel)

        // OS resume: player active again
        controller.applyPlaybackActive(true)
        assertTrue(controller.state.value is Gate2CaptureController.CaptureState.Capturing)

        // Stop session
        controller.stopCapture(ShareStoppedReason.HOST_ENDED)
        val stoppedState = controller.state.value
        assertTrue(stoppedState is Gate2CaptureController.CaptureState.Stopped)
        assertEquals(ShareStoppedReason.HOST_ENDED, (stoppedState as Gate2CaptureController.CaptureState.Stopped).reason)
    }

    @Test
    fun testPcmIngestionMaintainsFrameCountersAndEnqueues() {
        controller.requestCapture("VLC", 10042)
        controller.onCaptureStarted(100_000_000L)

        // 10ms of 48kHz stereo 16-bit PCM: 480 samples * 2 channels = 960 samples = 1920 bytes
        val testPcm10ms = ByteArray(1920)
        // Fill with some dummy audio data
        for (i in testPcm10ms.indices) {
            testPcm10ms[i] = (i % 127).toByte()
        }

        controller.onPcmCaptured(testPcm10ms, sampleRateHz = 48_000, channelCount = 2)

        assertEquals(1L, controller.totalOfferedFrames)
        assertEquals(0L, controller.totalDroppedFrames)
        assertEquals(1, controller.queueSize())

        val polled = controller.pollForInjection()
        assertNotNull(polled)
        assertEquals(0, controller.queueSize())
    }

    @Test
    fun testPcmWithNon48kHzIsRejected() {
        controller.requestCapture("VLC", 10042)
        controller.onCaptureStarted(100_000_000L)

        val non48k = ByteArray(1764) // 44.1kHz
        controller.onPcmCaptured(non48k, sampleRateHz = 44_100, channelCount = 2)

        assertEquals(0L, controller.totalOfferedFrames)
        assertEquals(0, controller.queueSize())
    }

    @Test
    fun testMediaSessionPlayingPreventsFalsePause() {
        controller.requestCapture("RVX Music", 10503)
        controller.onCaptureStarted(100_000_000L)

        // MediaSession indicates active playback (playing/buffering)
        controller.applyPlaybackActive(true, fromMediaSession = true)
        assertTrue(controller.state.value is Gate2CaptureController.CaptureState.Capturing)

        // Volume is set to 0, causing a transient AudioPlaybackCallback inactive report
        controller.applyPlaybackActive(false, fromMediaSession = false)

        // Must remain Capturing because MediaSession is actively playing!
        assertTrue(controller.state.value is Gate2CaptureController.CaptureState.Capturing)

        // When MediaSession explicitly pauses (e.g. user taps Pause in music player)
        controller.applyPlaybackActive(false, fromMediaSession = true)
        assertTrue(controller.state.value is Gate2CaptureController.CaptureState.Paused)
    }

    @Test
    fun testDrainQueuePreventsOverflow() {
        controller.requestCapture("Spotify", 10042)
        controller.onCaptureStarted(100_000_000L)

        val testPcm10ms = ByteArray(1920)
        controller.onPcmCaptured(testPcm10ms, sampleRateHz = 48_000, channelCount = 2)
        assertEquals(1, controller.queueSize())

        controller.drainQueue()
        assertEquals(0, controller.queueSize())
        assertEquals(0L, controller.totalDroppedFrames)
    }
}
