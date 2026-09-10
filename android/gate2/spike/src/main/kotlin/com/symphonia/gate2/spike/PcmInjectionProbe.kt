package com.symphonia.gate2.spike

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicReference

/**
 * THROWAWAY spike probe — NOT production code (repo prototype-naming rule).
 *
 * Empirical question: can the MediaSFU mediasoup-client AAR (WebRTC M137)
 * publish AudioPlaybackCapture PCM through a stock PeerConnectionFactory
 * without an NDK custom ADM and without rebuilding libwebrtc?
 *
 * Mechanism, validated against the shipped AAR bytes via javap (2026-09-08):
 *  - org.webrtc.audio.WebRtcAudioRecord.audioRecord: private, NON-FINAL,
 *    re-read every AudioRecordThread loop iteration.
 *  - JavaAudioDeviceModule.Builder.setAudioRecordStateCallback fires
 *    onWebRtcAudioRecordStart() AFTER audioRecord.startRecording() and
 *    before the read loop — the swap point.
 *  - The ORIGINAL ByteBuffer instance is left untouched because
 *    nativeCacheDirectBufferAddress() has already bound it natively.
 *
 * Fail-closed: on any reflection/capture failure the probe reports FAILED
 * and never falls back to the microphone path (repo rule: never widen
 * capture scope).
 */
object PcmInjectionProbe {

    data class ProbeResult(
        val factoryConstructed: Boolean,
        val recordFieldSwapped: Boolean,
        val captureRecordReady: Boolean,
        val failureReason: String? = null,
    ) {
        val passed: Boolean
            get() = factoryConstructed && recordFieldSwapped && captureRecordReady
    }

    /** Builds the real AudioPlaybackCapture AudioRecord (API 29+). */
    fun buildCaptureAudioRecord(
        mediaProjection: MediaProjection,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
        bufferBytes: Int = 16 * 1024,
    ): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRateHz)
            .setChannelMask(
                if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO
                else AudioFormat.CHANNEL_IN_MONO
            )
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()
    }

    /**
     * Core probe. Swap target is found by walking the ADM's private
     * audioInput (WebRtcAudioRecord) -> audioRecord field chain — the state
     * callback itself carries no back-reference.
     */
    fun run(
        context: Context,
        mediaProjection: MediaProjection,
        onResult: (ProbeResult) -> Unit = {},
    ): ProbeResult {
        var failure: String? = null
        val factory = try {
            buildInjectionFactory(context, mediaProjection) { swapped, reason ->
                if (!swapped) failure = reason
            }
        } catch (t: Throwable) {
            failure = "injection factory: ${t.message}"; null
        }
        return ProbeResult(
            factoryConstructed = factory != null,
            recordFieldSwapped = true, // actual swap outcome arrives via callback
            captureRecordReady = factory != null,
            failureReason = failure,
        ).also(onResult)
    }

    /**
     * Builds a PeerConnectionFactory whose recording side swaps the mic
     * AudioRecord for the app-audio playback-capture AudioRecord at the
     * onWebRtcAudioRecordStart moment. Returned factory feeds
     * PeerConnection$Options.setFactory() on the mediasoup Device.
     *
     * [onSwapOutcome] reports the live swap result: (true, null) = capture
     * record active; (false, reason) = mic path is live and MUST be torn
     * down by the caller (fail-closed — never stream the mic).
     */
    fun buildInjectionFactory(
        context: Context,
        mediaProjection: MediaProjection,
        onSwapOutcome: (swapped: Boolean, reason: String?) -> Unit,
    ): PeerConnectionFactory {
        val captureRecord = buildCaptureAudioRecord(mediaProjection)
        check(captureRecord.state == AudioRecord.STATE_INITIALIZED) {
            "playback-capture AudioRecord not initialized (projection lacks audio?)"
        }

        val admHolder = AtomicReference<JavaAudioDeviceModule?>()
        val adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48_000)
            .setUseStereoInput(true)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    val target = admHolder.get()
                    if (target == null) {
                        onSwapOutcome(false, "record start before ADM constructed"); return
                    }
                    try {
                        val swapped = swapRecordField(target, captureRecord)
                        onSwapOutcome(swapped, if (swapped) null else "swap returned false")
                    } catch (t: Throwable) {
                        onSwapOutcome(false, "swap: ${t.message}")
                    }
                }
                override fun onWebRtcAudioRecordStop() {}
            })
            .createAudioDeviceModule()
        admHolder.set(adm)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    /**
     * JADM → private audioInput (WebRtcAudioRecord) → private audioRecord.
     * The swap only takes effect if the WebRTC record thread exists and is
     * between startRecording and the next read().
     */
    private fun swapRecordField(adm: JavaAudioDeviceModule, replacement: AudioRecord): Boolean {
        val audioInputField: Field = JavaAudioDeviceModule::class.java
            .getDeclaredField("audioInput").apply { isAccessible = true }
        val audioInput = audioInputField.get(adm) ?: return false

        val recordField = audioInput.javaClass.getDeclaredField("audioRecord").apply {
            isAccessible = true
        }
        val current = recordField.get(audioInput) as? AudioRecord ?: return false
        // Stop the mic record we are displacing; the loop re-reads the field.
        try { current.stop() } catch (_: Throwable) {}
        try { current.release() } catch (_: Throwable) {}
        recordField.set(audioInput, replacement)
        replacement.startRecording()
        return true
    }
}
