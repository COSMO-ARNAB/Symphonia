package com.symphonia.gate2.transport

import android.content.Context
import android.util.Log
import com.symphonia.gate2.signaling.Gate2SignalingClient
import com.symphonia.gate2.signaling.SignalingException
import org.json.JSONArray
import org.json.JSONObject
import org.mediasoup.droid.Consumer
import org.mediasoup.droid.Device
import org.mediasoup.droid.RecvTransport
import org.mediasoup.droid.Transport
import org.webrtc.PeerConnectionFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listener-side WebRTC transport managing the mediasoup receive path.
 *
 * Implements:
 *  - Room joining and router capability negotiation via Gate2SignalingClient.
 *  - Strict Zod schema compliance for CONSUME requests (Fix 2):
 *     1. Strips "kind" from codecs.
 *     2. Filters headerExtensions to discard any "video" extensions.
 *     3. Ensures "kind": "audio" on all remaining headerExtensions.
 *     4. Auto-retries with channels: 1 if stereo negotiation fails with N-CANNOT_CONSUME.
 *  - Playout via WebRTC JavaAudioDeviceModule.
 *  - Inbound RTP statistics polling (packetsReceived, bytesReceived, packetsLost, jitter).
 */
class MediasoupListenerTransport(private val context: Context) {

    companion object {
        private const val TAG = "MediasoupListener"
    }

    data class ListenerTransportStats(
        val packetsReceived: Long = 0,
        val bytesReceived: Long = 0,
        val packetsLost: Long = 0,
        val jitterMs: Double = 0.0,
        val rttMs: Double = 0.0,
        val bitrateKbps: Double = 0.0,
    )

    private var signaling: Gate2SignalingClient? = null
    private var pcFactory: PeerConnectionFactory? = null
    private var device: Device? = null
    private var recvTransport: RecvTransport? = null
    private var consumer: Consumer? = null

    private var lastBytesReceived: Long = 0
    private var lastStatsTimestamp: Long = 0

    @Volatile var roomId: String? = null
        private set
    @Volatile var activeProducerId: String? = null
        private set
    @Volatile var consumerId: String? = null
        private set
    @Volatile var isConsuming: Boolean = false
        private set

    private val closed = AtomicBoolean(false)

    var onStateUpdate: ((state: String, appLabel: String?, reason: String?) -> Unit)? = null
    var onTransportClosed: (() -> Unit)? = null

    fun start(serverUrl: String, targetRoomId: String, timeoutMs: Long = 10_000): String {
        closed.set(false)
        roomId = targetRoomId

        Log.i(TAG, "Starting MediasoupListenerTransport for room=$targetRoomId")

        // 1. Setup WebRTC PeerConnectionFactory with stereo playout enabled
        val factory = MediasoupClientManager.createListenerFactory(context)
        pcFactory = factory

        // 2. Setup Mediasoup Device
        val (dev, pcOptions) = MediasoupClientManager.createDevice(factory)
        device = dev

        // 3. Connect to signaling
        val client = Gate2SignalingClient(serverUrl)
        client.connectBlocking(timeoutMs)
        signaling = client

        val producerLatch = CountDownLatch(1)

        client.onNotification = { type, data ->
            Log.i(TAG, "Notification received: $type")
            when (type) {
                "PRODUCER_AVAILABLE" -> {
                    val pid = data.optString("producerId")
                    if (pid.isNotBlank()) {
                        activeProducerId = pid
                        producerLatch.countDown()
                    }
                }
                "PRODUCER_CLOSED" -> {
                    Log.i(TAG, "Producer closed by host")
                    closeConsumer()
                }
                "STATE_UPDATE" -> {
                    val state = data.optString("state", "STOPPED")
                    val appLabel = if (data.has("appLabel") && !data.isNull("appLabel")) data.getString("appLabel") else null
                    val reason = if (data.has("reason") && !data.isNull("reason")) data.getString("reason") else null
                    onStateUpdate?.invoke(state, appLabel, reason)
                }
                "ROOM_CLOSED" -> {
                    Log.i(TAG, "Room closed by host")
                    close()
                    onTransportClosed?.invoke()
                }
            }
        }

        // 4. Join room
        val joinRes = client.joinRoom(targetRoomId, displayName = "Listener")
        val existingProducerId = joinRes.optString("producerId")
        if (!existingProducerId.isNullOrBlank() && existingProducerId != "null") {
            activeProducerId = existingProducerId
            producerLatch.countDown()
        }

        // 5. Get Router RTP Capabilities & Load Device
        val routerCaps = client.getRouterRtpCapabilities().getJSONObject("rtpCapabilities")
        dev.load(routerCaps.toString(), pcOptions)

        // 6. Create Recv Transport
        val transportInfo = client.createWebRtcTransport(direction = "recv")
        val transportId = transportInfo.getString("id")
        val iceParams = transportInfo.getString("iceParameters")
        val iceCandidates = transportInfo.getString("iceCandidates")
        val dtlsParams = transportInfo.getString("dtlsParameters")

        val transportListener = object : RecvTransport.Listener {
            override fun onConnect(transport: Transport, dtlsParameters: String) {
                try {
                    client.connectWebRtcTransport(transport.id, JSONObject(dtlsParameters))
                } catch (e: Exception) {
                    Log.e(TAG, "RecvTransport onConnect failed", e)
                }
            }

            override fun onConnectionStateChange(transport: Transport, newState: String) {
                Log.i(TAG, "RecvTransport state: $newState")
            }
        }

        val rTransport = dev.createRecvTransport(
            transportListener,
            transportId,
            iceParams,
            iceCandidates,
            dtlsParams,
            null,
            pcOptions,
            null
        )
        recvTransport = rTransport

        // 7. Await Producer and Consume
        val hasProducer = producerLatch.await(5_000, TimeUnit.MILLISECONDS)
        val producerToConsume = activeProducerId
        if (hasProducer && !producerToConsume.isNullOrBlank()) {
            consumeProducer(rTransport, dev, client, producerToConsume)
        } else {
            Log.i(TAG, "No active producer yet; awaiting PRODUCER_AVAILABLE notification")
            // Asynchronously consume when notification arrives
            Thread {
                if (producerLatch.await(60, TimeUnit.SECONDS)) {
                    val pId = activeProducerId
                    if (pId != null && !closed.get()) {
                        consumeProducer(rTransport, dev, client, pId)
                    }
                }
            }.start()
        }

        return targetRoomId
    }

    private fun consumeProducer(
        rTransport: RecvTransport,
        dev: Device,
        client: Gate2SignalingClient,
        producerId: String
    ) {
        synchronized(this) {
            if (consumer != null || closed.get()) return

            val rawCaps = JSONObject(dev.rtpCapabilities)

            // Try stereo negotiation first, fallback to mono if rejected with N-CANNOT_CONSUME (Fix 2)
            val consumeResponse = try {
                val cleanStereoCaps = cleanRtpCapabilitiesForSignaling(rawCaps, forceMono = false)
                client.consume(rTransport.id, producerId, cleanStereoCaps)
            } catch (e: SignalingException) {
                if (e.code == "N-CANNOT_CONSUME") {
                    Log.w(TAG, "Stereo consume rejected with N-CANNOT_CONSUME; auto-retrying with mono")
                    val cleanMonoCaps = cleanRtpCapabilitiesForSignaling(rawCaps, forceMono = true)
                    client.consume(rTransport.id, producerId, cleanMonoCaps)
                } else {
                    throw e
                }
            }

            val cInfo = consumeResponse ?: throw IllegalStateException("Empty consume response")
            val id = cInfo.getString("id")
            val kind = cInfo.getString("kind")
            val rtpParameters = cInfo.getJSONObject("rtpParameters").toString()
            val appData = cInfo.optJSONObject("appData")?.toString()

            val consumerListener = Consumer.Listener {
                Log.i(TAG, "Consumer transport closed")
                isConsuming = false
            }

            val cons = rTransport.consume(consumerListener, id, producerId, kind, rtpParameters, appData)
            consumer = cons
            consumerId = cons.id

            // Resume consumer on signaling server and locally
            client.resumeConsumer(cons.id)
            cons.resume()
            runCatching {
                (cons.track as? org.webrtc.AudioTrack)?.setEnabled(true)
                (cons.track as? org.webrtc.AudioTrack)?.setVolume(1.0)
            }

            isConsuming = true
            Log.i(TAG, "Consumer active: ${cons.id} playing $kind")
        }
    }

    /**
     * Fix 2: Transforms device.rtpCapabilities into strict Zod-compliant JSON.
     *  - Strips "kind" from codecs[]
     *  - Filters out any video header extensions
     *  - Enforces "kind": "audio" on all remaining header extensions
     */
    fun cleanRtpCapabilitiesForSignaling(raw: JSONObject, forceMono: Boolean = false): JSONObject {
        val result = JSONObject()

        // 1. Codecs
        val rawCodecs = raw.optJSONArray("codecs") ?: JSONArray()
        val cleanCodecs = JSONArray()

        for (i in 0 until rawCodecs.length()) {
            val c = rawCodecs.getJSONObject(i)
            val mimeType = c.optString("mimeType", "")
            if (!mimeType.contains("opus", ignoreCase = true)) continue

            val clean = JSONObject()
            clean.put("mimeType", "audio/opus")
            if (c.has("preferredPayloadType")) clean.put("preferredPayloadType", c.getInt("preferredPayloadType"))
            else if (c.has("payloadType")) clean.put("payloadType", c.getInt("payloadType"))
            clean.put("clockRate", 48_000)

            val channels = if (forceMono) 1 else c.optInt("channels", 2).coerceIn(1, 2)
            clean.put("channels", channels)

            if (c.has("parameters")) {
                val params = c.getJSONObject("parameters")
                val cleanParams = JSONObject()
                if (params.has("minptime")) cleanParams.put("minptime", params.getInt("minptime"))
                if (params.has("maxptime")) cleanParams.put("maxptime", params.getInt("maxptime"))
                if (params.has("useinbandfec")) cleanParams.put("useinbandfec", params.getInt("useinbandfec"))
                if (params.has("usedtx")) cleanParams.put("usedtx", params.getInt("usedtx"))
                if (params.has("cbr")) cleanParams.put("cbr", params.getInt("cbr"))
                if (!forceMono && params.has("stereo")) cleanParams.put("stereo", params.getInt("stereo"))
                if (!forceMono && params.has("sprop-stereo")) cleanParams.put("sprop-stereo", params.getInt("sprop-stereo"))
                if (params.has("maxaveragebitrate")) cleanParams.put("maxaveragebitrate", params.getInt("maxaveragebitrate"))
                if (params.has("maxplaybackrate")) cleanParams.put("maxplaybackrate", params.getInt("maxplaybackrate"))
                if (cleanParams.length() > 0) clean.put("parameters", cleanParams)
            }

            if (c.has("rtcpFeedback")) {
                clean.put("rtcpFeedback", c.getJSONArray("rtcpFeedback"))
            }

            cleanCodecs.put(clean)
        }

        // Fallback default Opus codec if none matched
        if (cleanCodecs.length() == 0) {
            cleanCodecs.put(JSONObject().apply {
                put("mimeType", "audio/opus")
                put("preferredPayloadType", 111)
                put("clockRate", 48_000)
                put("channels", if (forceMono) 1 else 2)
            })
        }
        result.put("codecs", cleanCodecs)

        // 2. Header Extensions
        val rawExts = raw.optJSONArray("headerExtensions") ?: JSONArray()
        val cleanExts = JSONArray()

        for (i in 0 until rawExts.length()) {
            val ext = rawExts.getJSONObject(i)
            val extKind = ext.optString("kind", "")
            if (extKind.equals("video", ignoreCase = true)) continue

            val cleanExt = JSONObject()
            cleanExt.put("kind", "audio")
            cleanExt.put("uri", ext.getString("uri"))
            if (ext.has("preferredId")) cleanExt.put("preferredId", ext.getInt("preferredId"))
            if (ext.has("preferredEncrypt")) cleanExt.put("preferredEncrypt", ext.getBoolean("preferredEncrypt"))
            if (ext.has("direction")) cleanExt.put("direction", ext.getString("direction"))
            if (ext.has("id")) cleanExt.put("id", ext.getInt("id"))
            if (ext.has("encrypt")) cleanExt.put("encrypt", ext.getBoolean("encrypt"))
            // Zod protocol strictly enforces parameters: z.object({}).strict().optional()
            if (ext.has("parameters")) {
                val params = ext.optJSONObject("parameters")
                if (params != null && params.length() == 0) {
                    cleanExt.put("parameters", JSONObject())
                }
            }

            cleanExts.put(cleanExt)
        }
        result.put("headerExtensions", cleanExts)

        return result
    }

    /**
     * Polls WebRTC inbound RTP statistics.
     */
    fun getStats(): ListenerTransportStats {
        val trans = recvTransport ?: return ListenerTransportStats()
        val statsStr = try {
            trans.getStats()
        } catch (e: Exception) {
            return ListenerTransportStats()
        }

        var packetsReceived = 0L
        var bytesReceived = 0L
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
                if (type == "inbound-rtp") {
                    packetsReceived = stat.optLong("packetsReceived", packetsReceived)
                    bytesReceived = stat.optLong("bytesReceived", bytesReceived)
                    packetsLost = stat.optLong("packetsLost", packetsLost)
                    val jitterSec = stat.optDouble("jitter", 0.0)
                    jitterMs = if (jitterSec > 0.0) jitterSec * 1000.0 else stat.optDouble("jitterMs", jitterMs)
                } else if (type == "candidate-pair" && stat.optBoolean("nominated", false)) {
                    val currentRtt = stat.optDouble("currentRoundTripTime", 0.0)
                    if (currentRtt > 0.0) rttMs = currentRtt * 1000.0
                }
            }
        }

        var bitrateKbps = 0.0
        if (lastStatsTimestamp > 0 && now > lastStatsTimestamp && bytesReceived >= lastBytesReceived) {
            val deltaBytes = bytesReceived - lastBytesReceived
            val deltaMs = now - lastStatsTimestamp
            bitrateKbps = (deltaBytes * 8.0) / deltaMs
        }
        lastBytesReceived = bytesReceived
        lastStatsTimestamp = now

        return ListenerTransportStats(
            packetsReceived = packetsReceived,
            bytesReceived = bytesReceived,
            packetsLost = packetsLost,
            jitterMs = jitterMs,
            rttMs = rttMs,
            bitrateKbps = bitrateKbps
        )
    }

    private fun closeConsumer() {
        runCatching { consumer?.close() }
        consumer = null
        consumerId = null
        isConsuming = false
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            closeConsumer()
            runCatching { recvTransport?.close() }
            runCatching { device?.dispose() }
            runCatching { signaling?.close() }
            runCatching { pcFactory?.dispose() }

            recvTransport = null
            device = null
            signaling = null
            pcFactory = null
            roomId = null
            activeProducerId = null
            Log.i(TAG, "MediasoupListenerTransport closed")
        }
    }
}
