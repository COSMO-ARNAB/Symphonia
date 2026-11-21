package com.symphonia.gate2.transport

import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import com.symphonia.gate2.signaling.Gate2SignalingClient
import org.json.JSONArray
import org.json.JSONObject
import org.mediasoup.droid.Device
import org.mediasoup.droid.Producer
import org.mediasoup.droid.SendTransport
import org.mediasoup.droid.Transport
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaker-side WebRTC transport managing the mediasoup send path.
 *
 * Implements:
 *  - Room creation and router capability negotiation via Gate2SignalingClient.
 *  - App-scoped capture PCM injection into WebRTC via PcmInjectionProbe.
 *  - Opus 48kHz audio track production.
 *  - Pause/resume state synchronization with signaling.
 *  - WebRTC transport statistics polling (outbound-rtp and remote-inbound-rtp).
 */
class MediasoupSpeakerTransport(private val context: Context) {

    companion object {
        private const val TAG = "MediasoupSpeaker"
    }

    data class SpeakerTransportStats(
        val packetsSent: Long = 0,
        val bytesSent: Long = 0,
        val packetsLost: Long = 0,
        val jitterMs: Double = 0.0,
        val rttMs: Double = 0.0,
        val bitrateKbps: Double = 0.0,
    )

    private var signaling: Gate2SignalingClient? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var device: Device? = null
    private var sendTransport: SendTransport? = null
    private var producer: Producer? = null

    private var lastBytesSent: Long = 0
    private var lastStatsTimestamp: Long = 0

    @Volatile var roomId: String? = null
        private set
    @Volatile var producerId: String? = null
        private set
    @Volatile var isProducing: Boolean = false
        private set
    @Volatile var isPaused: Boolean = false
        private set

    private var currentAppLabel: String? = null
    private val closed = AtomicBoolean(false)

    var onTransportClosed: (() -> Unit)? = null
    var onSwapOutcome: ((Boolean, String?) -> Unit)? = null

    fun start(
        serverUrl: String,
        mediaProjection: MediaProjection,
        targetUid: Int,
        appLabel: String? = null,
        onSamplesReady: ((data: ByteArray, sampleRate: Int, channelCount: Int) -> Unit)? = null,
    ): String {
        currentAppLabel = appLabel
        closed.set(false)

        Log.i(TAG, "Initializing MediasoupSpeakerTransport for uid=$targetUid app=$appLabel")

        // 1. PeerConnectionFactory with injected AudioRecord swap
        val factory = MediasoupClientManager.createSpeakerFactory(
            context = context,
            mediaProjection = mediaProjection,
            targetUid = targetUid,
            onSamplesReady = onSamplesReady,
            onSwapOutcome = { swapped, reason ->
                onSwapOutcome?.invoke(swapped, reason)
                if (!swapped) {
                    Log.e(TAG, "Fail-closed: AudioRecord swap failed ($reason). Stopping session.")
                    close()
                } else {
                    Log.i(TAG, "AudioRecord swap active; streaming app audio")
                }
            }
        )
        pcFactory = factory

        // 2. Mediasoup Device
        val (dev, pcOptions) = MediasoupClientManager.createDevice(factory)
        device = dev

        // 3. Signaling connection
        val client = Gate2SignalingClient(serverUrl)
        client.connectBlocking(8_000)
        signaling = client

        // 4. Create room
        val roomInfo = client.createRoom(displayName = "Speaker")
        val createdRoomId = roomInfo.getString("roomId")
        roomId = createdRoomId
        Log.i(TAG, "Room created: $createdRoomId")

        // 5. Query Router RTP Capabilities & Load Device
        val routerCaps = client.getRouterRtpCapabilities().getJSONObject("rtpCapabilities")
        dev.load(routerCaps.toString(), pcOptions)

        // 6. Create WebRtcTransport
        val transportInfo = client.createWebRtcTransport(direction = "send")
        val transportId = transportInfo.getString("id")
        val iceParams = transportInfo.getString("iceParameters")
        val iceCandidates = transportInfo.getString("iceCandidates")
        val dtlsParams = transportInfo.getString("dtlsParameters")

        val transportListener = object : SendTransport.Listener {
            override fun onConnect(transport: Transport, dtlsParameters: String) {
                try {
                    client.connectWebRtcTransport(transport.id, JSONObject(dtlsParameters))
                } catch (e: Exception) {
                    Log.e(TAG, "onConnect failed", e)
                }
            }

            override fun onConnectionStateChange(transport: Transport, newState: String) {
                Log.i(TAG, "SendTransport state: $newState")
            }

            override fun onProduce(transport: Transport, kind: String, rtpParameters: String, appData: String?): String {
                val res = client.produce(
                    transportId = transport.id,
                    kind = kind,
                    rtpParameters = JSONObject(rtpParameters),
                    appLabel = currentAppLabel
                )
                return res.getString("id")
            }

            override fun onProduceData(transport: Transport, sctpStreamParameters: String, label: String, protocol: String, appData: String): String {
                throw UnsupportedOperationException("Data producers not supported")
            }
        }

        val sendTrans = dev.createSendTransport(
            transportListener,
            transportId,
            iceParams,
            iceCandidates,
            dtlsParams,
            null,
            pcOptions,
            null
        )
        sendTransport = sendTrans

        // 7. Produce Audio Track with voice processing disabled for raw low-latency music streaming
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "false"))
        }
        val audioSrc = factory.createAudioSource(audioConstraints)
        audioSource = audioSrc
        val track = factory.createAudioTrack("gate2-speaker-track", audioSrc)
        audioTrack = track

        val prodListener = Producer.Listener {
            Log.i(TAG, "Producer listener notified of closure")
            isProducing = false
            onTransportClosed?.invoke()
        }

        val prod = sendTrans.produce(prodListener, track, null, null, null)
        producer = prod
        producerId = prod.id
        isProducing = true
        isPaused = false

        client.stateUpdate(state = "LIVE", appLabel = appLabel)
        Log.i(TAG, "Mediasoup producer live: ${prod.id}")

        return createdRoomId
    }

    fun pause() {
        if (!isProducing || isPaused) return
        try {
            producer?.pause()
            isPaused = true
            signaling?.stateUpdate(state = "PAUSED", appLabel = currentAppLabel)
            Log.i(TAG, "Producer paused")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause producer: ${e.message}")
        }
    }

    fun resume() {
        if (!isProducing || !isPaused) return
        try {
            producer?.resume()
            isPaused = false
            signaling?.stateUpdate(state = "LIVE", appLabel = currentAppLabel)
            Log.i(TAG, "Producer resumed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume producer: ${e.message}")
        }
    }

    /**
     * Polls WebRTC statistics (Fix 6).
     * Extracts outbound-rtp and remote-inbound-rtp (RTCP receiver report from SFU).
     */
    fun getStats(): SpeakerTransportStats {
        val trans = sendTransport ?: return SpeakerTransportStats()
        val statsStr = try {
            trans.getStats()
        } catch (e: Exception) {
            return SpeakerTransportStats()
        }

        var packetsSent = 0L
        var bytesSent = 0L
        var packetsLost = 0L
        var jitterMs = 0.0
        var rttMs = 0.0

        val now = System.currentTimeMillis()

        runCatching {
            val statsList = if (statsStr.trim().startsWith("[")) {
                val arr = JSONArray(statsStr)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            } else if (statsStr.trim().startsWith("{")) {
                val obj = JSONObject(statsStr)
                obj.keys().asSequence().mapNotNull { obj.optJSONObject(it) }.toList()
            } else emptyList()

            for (stat in statsList) {
                val type = stat.optString("type")
                if (type == "outbound-rtp") {
                    packetsSent = stat.optLong("packetsSent", packetsSent)
                    bytesSent = stat.optLong("bytesSent", bytesSent)
                } else if (type == "remote-inbound-rtp") {
                    packetsLost = stat.optLong("packetsLost", packetsLost)
                    // jitter is in seconds in WebRTC stats
                    val jitterSec = stat.optDouble("jitter", 0.0)
                    jitterMs = if (jitterSec > 0.0) jitterSec * 1000.0 else stat.optDouble("jitterMs", jitterMs)
                    val rttSec = stat.optDouble("roundTripTime", 0.0)
                    rttMs = if (rttSec > 0.0) rttSec * 1000.0 else stat.optDouble("roundTripTimeMs", rttMs)
                } else if (type == "candidate-pair" && stat.optBoolean("nominated", false)) {
                    if (rttMs == 0.0) {
                        val currentRtt = stat.optDouble("currentRoundTripTime", 0.0)
                        if (currentRtt > 0.0) rttMs = currentRtt * 1000.0
                    }
                }
            }
        }

        var bitrateKbps = 0.0
        if (lastStatsTimestamp > 0 && now > lastStatsTimestamp && bytesSent >= lastBytesSent) {
            val deltaBytes = bytesSent - lastBytesSent
            val deltaMs = now - lastStatsTimestamp
            bitrateKbps = (deltaBytes * 8.0) / deltaMs
        }
        lastBytesSent = bytesSent
        lastStatsTimestamp = now

        return SpeakerTransportStats(
            packetsSent = packetsSent,
            bytesSent = bytesSent,
            packetsLost = packetsLost,
            jitterMs = jitterMs,
            rttMs = rttMs,
            bitrateKbps = bitrateKbps
        )
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { signaling?.stateUpdate(state = "STOPPED", appLabel = currentAppLabel, reason = "host_ended") }
            runCatching { producer?.close() }
            runCatching { audioTrack?.dispose() }
            runCatching { audioSource?.dispose() }
            runCatching { sendTransport?.close() }
            runCatching { device?.dispose() }
            runCatching { signaling?.close() }
            runCatching { pcFactory?.dispose() }

            producer = null
            audioTrack = null
            audioSource = null
            sendTransport = null
            device = null
            signaling = null
            pcFactory = null
            isProducing = false
            isPaused = false
            roomId = null
            producerId = null
            Log.i(TAG, "MediasoupSpeakerTransport closed")
        }
    }
}
