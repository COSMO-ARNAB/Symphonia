package com.symphonia.gate2.transport

import android.content.Context
import android.media.projection.MediaProjection
import org.mediasoup.droid.Device
import org.mediasoup.droid.MediasoupClient
import org.mediasoup.droid.PeerConnection as MediasoupPcOptions
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Mediasoup client lifecycle and PeerConnectionFactory instantiation
 * for both Speaker and Listener transport roles.
 */
object MediasoupClientManager {

    private val initialized = AtomicBoolean(false)

    fun initialize(context: Context) {
        if (initialized.compareAndSet(false, true)) {
            MediasoupClient.initialize(context.applicationContext)
        }
    }

    /**
     * Builds a PeerConnectionFactory tailored for Speaker operation, swapping
     * mic recording for app-audio capture and delivering PCM frames via callback.
     */
    fun createSpeakerFactory(
        context: Context,
        mediaProjection: MediaProjection,
        targetUid: Int,
        onSamplesReady: ((data: ByteArray, sampleRate: Int, channelCount: Int) -> Unit)? = null,
        onSwapOutcome: (swapped: Boolean, reason: String?) -> Unit,
    ): PeerConnectionFactory {
        initialize(context)
        return PcmInjectionProbe.buildInjectionFactory(
            context = context,
            mediaProjection = mediaProjection,
            targetUid = targetUid,
            onSamplesReady = onSamplesReady,
            onSwapOutcome = onSwapOutcome,
        )
    }

    /**
     * Builds a standard PeerConnectionFactory tailored for Listener playout via
     * JavaAudioDeviceModule with stereo output support.
     */
    fun createListenerFactory(context: Context): PeerConnectionFactory {
        initialize(context)
        val adm = JavaAudioDeviceModule.builder(context)
            .setSampleRate(48_000)
            .setUseStereoOutput(true)
            .createAudioDeviceModule()

        return PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    /**
     * Creates a new mediasoup Device preloaded with the given PeerConnectionFactory.
     */
    fun createDevice(factory: PeerConnectionFactory): Pair<Device, MediasoupPcOptions.Options> {
        val options = MediasoupPcOptions.Options().apply {
            setFactory(factory)
        }
        return Pair(Device(), options)
    }
}
