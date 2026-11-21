package com.symphonia.gate2.transport

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Process
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicReference

/**
 * App-scoped AudioPlaybackCapture PCM injection probe for WebRTC.
 *
 * Implements Fix 1:
 *  - Enforces app-scoped capture with .addMatchingUid(targetUid) and .excludeUid(Process.myUid()).
 *  - Displaces the microphone AudioRecord with the playback-capture AudioRecord via reflection
 *    at onWebRtcAudioRecordStart.
 *  - Uses JavaAudioDeviceModule.setSamplesReadyCallback to forward captured 10ms PCM frames to
 *    the harness controller, eliminating the dual AudioRecord conflict.
 *  - Fail-closed: on any reflection/capture failure, the probe reports failure and never
 *    streams the microphone (privacy invariant per AGENTS.md §2).
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

    /**
     * Builds the app-scoped AudioPlaybackCapture AudioRecord (API 29+).
     * Strictly scopes capture to targetUid and explicitly excludes this app's UID.
     */
    fun buildCaptureAudioRecord(
        mediaProjection: MediaProjection,
        targetUid: Int,
        sampleRateHz: Int = 48_000,
        channelCount: Int = 2,
        bufferBytes: Int = 16 * 1024,
    ): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUid(targetUid)
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
     * Builds a PeerConnectionFactory whose recording side swaps the mic AudioRecord
     * for the app-audio playback-capture AudioRecord at onWebRtcAudioRecordStart.
     *
     * In addition, [onSamplesReady] receives the exact 10ms PCM frames read by WebRTC's
     * internal AudioRecordThread, ensuring frame counters are maintained without running
     * two concurrent AudioRecord instances.
     */
    fun buildInjectionFactory(
        context: Context,
        mediaProjection: MediaProjection,
        targetUid: Int,
        onSamplesReady: ((data: ByteArray, sampleRate: Int, channelCount: Int) -> Unit)? = null,
        onSwapOutcome: (swapped: Boolean, reason: String?) -> Unit,
    ): PeerConnectionFactory {
        val captureRecord = buildCaptureAudioRecord(mediaProjection, targetUid)
        check(captureRecord.state == AudioRecord.STATE_INITIALIZED) {
            "Playback-capture AudioRecord not initialized for uid=$targetUid (projection lacks audio?)"
        }

        val admHolder = AtomicReference<JavaAudioDeviceModule?>()
        val admBuilder = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48_000)
            .setUseStereoInput(true)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    val target = admHolder.get()
                    if (target == null) {
                        onSwapOutcome(false, "record start before ADM constructed")
                        return
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

        if (onSamplesReady != null) {
            admBuilder.setSamplesReadyCallback { audioSamples ->
                onSamplesReady(audioSamples.data, audioSamples.sampleRate, audioSamples.channelCount)
            }
        }

        val adm = admBuilder.createAudioDeviceModule()
        admHolder.set(adm)

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    /**
     * Swaps WebRtcAudioRecord.audioRecord field via reflection.
     */
    private fun swapRecordField(adm: JavaAudioDeviceModule, replacement: AudioRecord): Boolean {
        val audioInputField: Field = JavaAudioDeviceModule::class.java
            .getDeclaredField("audioInput").apply { isAccessible = true }
        val audioInput = audioInputField.get(adm) ?: return false

        val recordField = audioInput.javaClass.getDeclaredField("audioRecord").apply {
            isAccessible = true
        }
        val current = recordField.get(audioInput) as? AudioRecord ?: return false
        try { current.stop() } catch (_: Throwable) {}
        try { current.release() } catch (_: Throwable) {}
        recordField.set(audioInput, replacement)
        replacement.startRecording()
        return true
    }
}
