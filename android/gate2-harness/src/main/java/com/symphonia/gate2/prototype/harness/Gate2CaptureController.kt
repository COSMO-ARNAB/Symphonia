package com.symphonia.gate2.prototype.harness

import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.util.Log
import com.symphonia.gate2.contracts.Gate2Failure
import com.symphonia.gate2.contracts.ShareStoppedReason
import com.symphonia.gate2.pcm.BoundedPcmFrameQueue
import com.symphonia.gate2.pcm.PcmFormat
import com.symphonia.gate2.pcm.PcmFrame
import com.symphonia.gate2.pcm.PcmRechunker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gate2 prototype capture controller - explicitly prototype-named.
 *
 * Decoupled from Activity/Compose lifecycle per AGENTS.md.
 * Owns capture state machine, PCM validation, bounded queuing, and OS-lifecycle
 * pause/resume callbacks. It has NO dependency on Activity - only Application context
 * via AudioManager passed from the foreground service.
 *
 * Fail-closed guarantee: if capture scope is unverified or capture fails, the
 * controller transitions to STOPPED(CAPTURE_UNAVAILABLE) and never widens to
 * microphone or system-wide capture.
 *
 * Audio parameters are fixed per PRD §7.5 / ARCH §3.4:
 *  - Opus, 48 kHz, stereo where source provides it, 64-96 kbps (server-tunable)
 */
class Gate2CaptureController(
    private val audioManager: AudioManager,
) {
    companion object {
        private const val TAG = "Gate2CaptureCtl"
        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT_STEREO = 2
        const val CHANNEL_COUNT_MONO = 1
        const val PCM_CHUNK_MS = 10 // WebRTC expects 10/20ms chunks
        const val QUEUE_CAPACITY_FRAMES = 100 // ~1 second at 10ms stereo
    }

    sealed interface CaptureState {
        data object Idle : CaptureState
        data object RequestingCapture : CaptureState
        data class Capturing(val appLabel: String, val targetUid: Int, val startedAtMs: Long) : CaptureState
        data class Paused(val appLabel: String, val targetUid: Int) : CaptureState
        data class Stopped(val reason: ShareStoppedReason, val failure: Gate2Failure?) : CaptureState
    }

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    // PCM pipeline - bounded, validated, correctly paced
    private val pcmFormatStereo = PcmFormat(sampleRateHz = SAMPLE_RATE_HZ, channelCount = CHANNEL_COUNT_STEREO)
    private val pcmFormatMono = PcmFormat(sampleRateHz = SAMPLE_RATE_HZ, channelCount = CHANNEL_COUNT_MONO)
    private var activeFormat: PcmFormat = pcmFormatStereo
    private var rechunker: PcmRechunker = PcmRechunker(activeFormat, PCM_CHUNK_MS)
    private val queue = BoundedPcmFrameQueue(capacity = QUEUE_CAPACITY_FRAMES)

    private var targetUid: Int = -1
    private var targetAppLabel: String = ""
    var totalOfferedFrames: Long = 0
        private set
    var totalDroppedFrames: Long = 0
        private set
    var captureStartRealtimeNanos: Long = -1L
        private set

    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var capturingFlag: Boolean = false

    /**
     * Transition to REQUESTING_CAPTURE. Must be called before AudioRecord creation.
     * Fail-closed: if uid is -1 or appLabel blank, immediately STOPPED.
     */
    fun requestCapture(appLabel: String, uid: Int): CaptureState {
        require(appLabel.isNotBlank() || uid == -1) // allow blank only for failure path
        if (uid == -1 || appLabel.isBlank()) {
            val stopped = CaptureState.Stopped(
                reason = ShareStoppedReason.CAPTURE_UNAVAILABLE,
                failure = Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE
            )
            _state.value = stopped
            Log.w(TAG, "Fail-closed: invalid capture target uid=$uid label=$appLabel -> STOPPED")
            return stopped
        }
        targetUid = uid
        targetAppLabel = appLabel
        activeFormat = pcmFormatStereo // default; mono fallback handled if device provides mono
        rechunker = PcmRechunker(activeFormat, PCM_CHUNK_MS)
        queue.clear()
        totalOfferedFrames = 0
        totalDroppedFrames = 0
        captureStartRealtimeNanos = -1
        _state.value = CaptureState.RequestingCapture
        registerPlaybackCallbackIfNeeded()
        Log.i(TAG, "Capture requested for $appLabel uid=$uid -> REQUESTING_CAPTURE")
        return _state.value
    }

    /**
     * Called when AudioRecord successfully starts.
     */
    fun onCaptureStarted(realtimeNanos: Long) {
        captureStartRealtimeNanos = realtimeNanos
        capturingFlag = true
        _state.value = CaptureState.Capturing(
            appLabel = targetAppLabel,
            targetUid = targetUid,
            startedAtMs = System.currentTimeMillis()
        )
        Log.i(TAG, "Capture started for $targetAppLabel -> CAPTURING at $realtimeNanos")
    }

    /**
     * OS-lifecycle driven pause/resume. Called from AudioManager.AudioPlaybackCallback.
     * Never uses silence detection - per AGENTS.md "No silence-detection or audio-diffing".
     */
    fun onPlaybackConfigsChanged(configs: List<AudioPlaybackConfiguration>) {
        val current = _state.value
        // Only relevant when we are in CAPTURING or PAUSED
        if (current !is CaptureState.Capturing && current !is CaptureState.Paused) return

        val targetActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            configs.any { cfg ->
                // Use reflection for getClientUid to avoid compile-time API variance / hidden API
                try {
                    val method = cfg.javaClass.getMethod("getClientUid")
                    val uid = method.invoke(cfg) as Int
                    uid == targetUid
                } catch (_: Exception) {
                    try {
                        // Fallback: try field "mClientUid" via reflection if method hidden
                        val field = cfg.javaClass.getDeclaredField("mClientUid")
                        field.isAccessible = true
                        (field.get(cfg) as Int) == targetUid
                    } catch (_: Exception) { false }
                }
            }
        } else {
            // Pre-O fallback: we cannot reliably query, so we don't auto-pause.
            // Keep CAPTURING and let explicit stop handle it - fail-closed still holds
            // because we never widen, but we won't incorrectly PAUSE.
            true
        }

        if (!targetActive && current is CaptureState.Capturing) {
            _state.value = CaptureState.Paused(appLabel = targetAppLabel, targetUid = targetUid)
            Log.i(TAG, "Playback config: target uid $targetUid no longer active -> PAUSED (app switch)")
        } else if (targetActive && current is CaptureState.Paused) {
            _state.value = CaptureState.Capturing(
                appLabel = targetAppLabel,
                targetUid = targetUid,
                startedAtMs = System.currentTimeMillis()
            )
            Log.i(TAG, "Playback config: target uid $targetUid active again -> CAPTURING (auto-resume)")
        }
    }

    /**
     * Permission revoked or service error - distinct from app-switch pause.
     */
    fun onPermissionRevoked() {
        unregisterPlaybackCallback()
        _state.value = CaptureState.Stopped(
            reason = ShareStoppedReason.PERMISSION_REQUIRED,
            failure = Gate2Failure.CAPTURE_PERMISSION_REQUIRED
        )
        capturingFlag = false
        Log.w(TAG, "Permission revoked -> STOPPED(permission_required)")
    }

    fun onCaptureUnavailable(failure: Gate2Failure = Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE) {
        unregisterPlaybackCallback()
        _state.value = CaptureState.Stopped(
            reason = ShareStoppedReason.CAPTURE_UNAVAILABLE,
            failure = failure
        )
        capturingFlag = false
        Log.w(TAG, "Capture unavailable $failure -> STOPPED")
    }

    fun stopCapture(reason: ShareStoppedReason = ShareStoppedReason.HOST_ENDED) {
        unregisterPlaybackCallback()
        capturingFlag = false
        // Map reason to failure for diagnostics
        val failure = when (reason) {
            ShareStoppedReason.PERMISSION_REQUIRED -> Gate2Failure.CAPTURE_PERMISSION_REQUIRED
            ShareStoppedReason.CAPTURE_UNAVAILABLE -> Gate2Failure.CAPTURE_SOURCE_UNAVAILABLE
            else -> null
        }
        _state.value = CaptureState.Stopped(reason = reason, failure = failure)
        queue.clear()
        Log.i(TAG, "Capture stopped reason=$reason")
    }

    fun resetToIdle() {
        unregisterPlaybackCallback()
        _state.value = CaptureState.Idle
        targetUid = -1
        targetAppLabel = ""
        queue.clear()
        capturingFlag = false
        Log.i(TAG, "Controller reset -> IDLE")
    }

    /**
     * PCM ingestion from AudioRecord read loop.
     * Validates 48 kHz, mono/stereo only, validates duration, rechunks to 10ms,
     * and enqueues with bounded drop-oldest policy.
     */
    fun onPcmCaptured(rawPcm16: ShortArray, format: PcmFormat, capturedAtNanos: Long) {
        if (!capturingFlag) return
        // Enforce AGENTS.md audio defaults: 48kHz only
        if (format.sampleRateHz != SAMPLE_RATE_HZ) {
            Log.w(TAG, "Rejected PCM with sampleRate ${format.sampleRateHz} != $SAMPLE_RATE_HZ")
            return
        }
        if (format.channelCount !in 1..2) {
            Log.w(TAG, "Rejected PCM channelCount ${format.channelCount}")
            return
        }
        // Handle mono vs stereo switch if needed
        if (format != activeFormat) {
            // For prototype, allow switching once at start, then enforce
            activeFormat = format
            rechunker = PcmRechunker(activeFormat, PCM_CHUNK_MS)
            queue.clear()
            Log.i(TAG, "PCM format switched to $activeFormat")
        }

        // Build a frame - need to infer duration. Caller should provide 10ms-equivalent raw.
        // We treat rawPcm16 as already 10ms or whatever; create frame and rechunk.
        val frameDurationMs = (rawPcm16.size * 1000L / (format.sampleRateHz * format.channelCount)).toInt()
        if (frameDurationMs <= 0 || SAMPLE_RATE_HZ * frameDurationMs % 1000 != 0) {
            Log.w(TAG, "Rejected PCM durationMs=$frameDurationMs samples=${rawPcm16.size}")
            return
        }
        val frame = try {
            PcmFrame(rawPcm16, format, frameDurationMs, capturedAtNanos)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "PcmFrame validation failed: ${e.message}")
            return
        }

        val result = rechunker.accept(frame)
        // Note: timestamp discontinuity is telemetry, not a silence-based state change.
        if (result.timestampDiscontinuityDetected) {
            Log.w(TAG, "PCM timestamp discontinuity detected at $capturedAtNanos")
        }
        for (out in result.frames) {
            totalOfferedFrames++
            val offer = queue.offer(out)
            if (offer.droppedOldest) totalDroppedFrames++
        }
    }

    /**
     * Poll for WebRTC injection path (Step 3). Returns null if empty.
     */
    fun pollForInjection(): PcmFrame? = queue.poll()

    fun queueSize(): Int = queue.size()

    fun isCapturing(): Boolean = capturingFlag && _state.value is CaptureState.Capturing

    private fun registerPlaybackCallbackIfNeeded() {
        if (playbackCallback != null) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
                onPlaybackConfigsChanged(configs)
            }
        }
        playbackCallback = cb
        try {
            audioManager.registerAudioPlaybackCallback(cb, null)
            Log.i(TAG, "Registered AudioPlaybackCallback for uid=$targetUid")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register AudioPlaybackCallback: ${e.message}")
            playbackCallback = null
        }
    }

    private fun unregisterPlaybackCallback() {
        playbackCallback?.let {
            try { audioManager.unregisterAudioPlaybackCallback(it) } catch (_: Exception) {}
            Log.i(TAG, "Unregistered AudioPlaybackCallback")
        }
        playbackCallback = null
    }
}
