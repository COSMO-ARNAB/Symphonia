package com.symphonia.gate2.signaling

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Protocol failure returned by the Node mediasoup signaling server.
 */
class SignalingException(
    val code: String,
    override val message: String,
    val details: JSONObject? = null
) : Exception("[$code] $message")

/**
 * Gate 2 Signaling Client — speaks the Node prototype protocol (signaling/src/protocol.ts).
 *
 * Implements correlated request/response envelopes, server notification dispatch,
 * and URL normalization (Fix 5: prevents /gate2/gate2 403 Forbidden).
 */
class Gate2SignalingClient(serverUrl: String) {

    private class PendingRequest {
        val latch = CountDownLatch(1)
        @Volatile var response: JSONObject? = null
        @Volatile var socketClosed = false
    }

    val requestUri: URI = normalizeUrl(serverUrl)
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, PendingRequest>()
    private var socket: WebSocketImpl? = null

    /** Server notifications: PRODUCER_AVAILABLE, PRODUCER_CLOSED, TRANSPORT_CLOSED, etc. */
    var onNotification: ((type: String, data: JSONObject) -> Unit)? = null
    var onConnectionClosed: ((code: Int, reason: String?, remote: Boolean) -> Unit)? = null
    var onConnectionError: ((ex: Exception) -> Unit)? = null

    val isConnected: Boolean
        get() = socket?.isOpen == true

    companion object {
        /**
         * Normalizes server URLs:
         *  - Converts http:// -> ws:// and https:// -> wss:// to avoid Java-WebSocket scheme crashes
         *  - Appends "/gate2" only if not already present, preventing double "/gate2/gate2" 403 errors
         */
        fun normalizeUrl(raw: String): URI {
            var trimmed = raw.trim().removeSuffix("/")
            if (trimmed.startsWith("http://", ignoreCase = true)) {
                trimmed = "ws://" + trimmed.substring(7)
            } else if (trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed = "wss://" + trimmed.substring(8)
            }
            val pathAdded = if (trimmed.endsWith("/gate2")) trimmed else "$trimmed/gate2"
            return URI(pathAdded)
        }
    }

    private inner class WebSocketImpl(uri: URI) : WebSocketClient(uri) {
        override fun onOpen(handshake: ServerHandshake) {}

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            for (req in pending.values) {
                req.socketClosed = true
                req.latch.countDown()
            }
            onConnectionClosed?.invoke(code, reason, remote)
        }

        override fun onError(ex: Exception) {
            onConnectionError?.invoke(ex)
        }

        override fun onMessage(message: String) {
            val json = runCatching { JSONObject(message) }.getOrNull() ?: return
            val id = if (json.has("id") && !json.isNull("id")) json.getString("id") else null
            val type = json.optString("type")

            if (id != null && type == "RESPONSE") {
                pending.remove(id)?.let { req ->
                    req.response = json
                    req.latch.countDown()
                }
            } else {
                val data = json.optJSONObject("data") ?: JSONObject()
                onNotification?.invoke(type, data)
            }
        }
    }

    fun connectBlocking(timeoutMs: Long = 8_000) {
        close()
        val client = WebSocketImpl(requestUri)
        if (!client.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("WebSocket connect timeout: $requestUri")
        }
        socket = client
    }

    fun request(type: String, data: JSONObject = JSONObject(), timeoutMs: Long = 10_000): JSONObject {
        val client = socket ?: throw IllegalStateException("Signaling client not connected")
        if (!client.isOpen) throw IllegalStateException("Signaling socket is closed")

        val id = "g2-${nextId.incrementAndGet()}"
        val req = PendingRequest()
        pending[id] = req

        val payload = JSONObject().apply {
            put("id", id)
            put("type", type)
            if (data.length() > 0) {
                put("data", data)
            }
        }

        client.send(payload.toString())

        try {
            if (!req.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("$type timed out after ${timeoutMs}ms")
            }
            if (req.socketClosed) {
                throw IllegalStateException("$type failed: connection closed during request")
            }
            val res = req.response ?: throw IllegalStateException("$type failed: empty response")

            if (!res.optBoolean("ok", false)) {
                val err = res.optJSONObject("error") ?: JSONObject()
                val code = err.optString("code", "UNKNOWN_ERROR")
                val msg = err.optString("message", "No error message provided")
                throw SignalingException(code, msg, err.optJSONObject("details"))
            }

            return res.optJSONObject("data") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    // High-level request helpers matching signaling/src/protocol.ts

    fun createRoom(displayName: String? = null): JSONObject =
        request("CREATE_ROOM", JSONObject().apply {
            displayName?.let { put("displayName", it) }
        })

    fun joinRoom(roomId: String, displayName: String? = null): JSONObject =
        request("JOIN_ROOM", JSONObject().apply {
            put("roomId", roomId)
            displayName?.let { put("displayName", it) }
        })

    fun leaveRoom(): JSONObject =
        request("LEAVE_ROOM", JSONObject())

    fun getRouterRtpCapabilities(): JSONObject =
        request("GET_ROUTER_RTP_CAPABILITIES", JSONObject())

    fun createWebRtcTransport(direction: String): JSONObject =
        request("CREATE_WEBRTC_TRANSPORT", JSONObject().apply {
            put("direction", direction)
        })

    fun connectWebRtcTransport(transportId: String, dtlsParameters: JSONObject): JSONObject =
        request("CONNECT_WEBRTC_TRANSPORT", JSONObject().apply {
            put("transportId", transportId)
            put("dtlsParameters", dtlsParameters)
        })

    fun produce(
        transportId: String,
        kind: String = "audio",
        rtpParameters: JSONObject,
        appLabel: String? = null
    ): JSONObject =
        request("PRODUCE", JSONObject().apply {
            put("transportId", transportId)
            put("kind", kind)
            put("rtpParameters", rtpParameters)
            if (!appLabel.isNullOrBlank()) {
                put("appData", JSONObject().put("appLabel", appLabel))
            }
        })

    fun consume(transportId: String, producerId: String, rtpCapabilities: JSONObject): JSONObject =
        request("CONSUME", JSONObject().apply {
            put("transportId", transportId)
            put("producerId", producerId)
            put("rtpCapabilities", rtpCapabilities)
        })

    fun resumeConsumer(consumerId: String): JSONObject =
        request("RESUME_CONSUMER", JSONObject().apply {
            put("consumerId", consumerId)
        })

    fun restartIce(transportId: String): JSONObject =
        request("RESTART_ICE", JSONObject().apply {
            put("transportId", transportId)
        })

    fun stateUpdate(state: String, appLabel: String? = null, reason: String? = null): JSONObject =
        request("STATE_UPDATE", JSONObject().apply {
            put("state", state)
            if (appLabel != null) put("appLabel", appLabel)
            if (reason != null) put("reason", reason)
        })

    fun ping(timestamp: Double = System.currentTimeMillis().toDouble()): JSONObject =
        request("PING", JSONObject().apply {
            put("timestamp", timestamp)
        })

    fun close() {
        socket?.close()
        socket = null
        for (req in pending.values) {
            req.socketClosed = true
            req.latch.countDown()
        }
        pending.clear()
    }
}
