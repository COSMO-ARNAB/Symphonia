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
     * audioInput (WebRtcAudioRecord) → audioRecord field chain — the state
     * callback itself carries no back-reference.
     */
    fun run(
        context: Context,
        mediaProjection: MediaProjection,
        onResult: (ProbeResult) -> Unit = {},
    ): ProbeResult {
        var failure: String? = null
        var swapped = false
        var captureReady = false

        // 1. Real capture AudioRecord (fail-closed: no mic fallback).
        val captureRecord = try {
            val r = buildCaptureAudioRecord(mediaProjection)
            captureReady = r.state == AudioRecord.STATE_INITIALIZED
            r
        } catch (t: Throwable) {
            failure = "capture AudioRecord ctor: ${t.message}"; null
        } ?: return ProbeResult(false, false, false, failure).also(onResult)

        // 2. ADM with the swap wired at the record-start moment.
        // The callback needs the ADM instance (to walk its audioInput field),
        // so the builder callback captures a late-bound holder.
        val admHolder = AtomicReference<JavaAudioDeviceModule?>()
        val adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48_000)
            .setUseStereoInput(true)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    val target = admHolder.get() ?: run {
                        failure = "swap: ADM not yet constructed"; return
                    }
                    swapped = try {
                        swapRecordField(target, captureRecord)
                    } catch (t: Throwable) {
                        failure = "swap: ${t.message}"; false
                    }
                }
                override fun onWebRtcAudioRecordStop() {}
            })
            .createAudioDeviceModule()
        admHolder.set(adm)

        // 3. Factory with our ADM — the thing mediasoup's
        //    PeerConnection$Options.setFactory() accepts.
        val factory = try {
            PeerConnectionFactory.builder()
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
        } catch (t: Throwable) {
            failure = (failure?.plus(" | ") ?: "") + "factory: ${t.message}"; null
        }

        return ProbeResult(
            factoryConstructed = factory != null,
            recordFieldSwapped = swapped,
            captureRecordReady = captureReady,
            failureReason = failure,
        ).also(onResult)
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
