package com.symphonia.gate2.spikeapp

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * THROWAWAY spike signaling client - speaks the Node prototype protocol
 * (signaling/src/protocol.ts): correlated request/response envelopes plus
 * untyped server notifications. Plain blocking calls (no coroutines) - the
 * spike service runs it on its own worker thread.
 */
class SpikeSignalingClient(serverUrl: String) {

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var response: JSONObject? = null
        @Volatile var socketClosed = false
    }

    private val requestUrl = URI("${serverUrl.removeSuffix("/")}/gate2")
    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, Waiter>()
    private var socket: InnerClient? = null

    /** Server notifications (PRODUCER_CLOSED, TRANSPORT_CLOSED, ...) -> log. */
    var onNotification: ((type: String, data: JSONObject) -> Unit)? = null

    private inner class InnerClient(uri: URI) : WebSocketClient(uri) {
        override fun onOpen(handshake: ServerHandshake) {}
        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            for (waiter in pending.values) { waiter.socketClosed = true; waiter.latch.countDown() }
        }
        override fun onError(ex: Exception) {}
        override fun onMessage(message: String) {
            val json = JSONObject(message)
            val id = json.optString("id", null)
            if (id != null && json.optString("type") == "RESPONSE") {
                pending.remove(id)?.let { waiter ->
                    waiter.response = json
                    waiter.latch.countDown()
                }
            } else {
                onNotification?.invoke(json.optString("type"), json.optJSONObject("data") ?: JSONObject())
            }
        }
    }

    fun connectBlocking(timeoutMs: Long = 8_000) {
        val client = InnerClient(requestUrl)
        if (!client.connectBlocking(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("WebSocket connect timeout: $requestUrl")
        }
        socket = client
    }

    fun request(type: String, data: JSONObject, timeoutMs: Long = 10_000): JSONObject {
        val client = socket ?: throw IllegalStateException("not connected")
        val id = "spike-${nextId.incrementAndGet()}"
        val waiter = Waiter()
        pending[id] = waiter
        client.send(JSONObject().put("id", id).put("type", type).put("data", data).toString())
        try {
            if (!waiter.latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("$type timed out after ${timeoutMs}ms")
            }
            if (waiter.socketClosed) throw IllegalStateException("$type failed: connection closed")
            val response = waiter.response
                ?: throw IllegalStateException("$type failed: empty response")
            if (!response.optBoolean("ok", false)) {
                val error = response.optJSONObject("error") ?: JSONObject()
                throw IllegalStateException("$type failed: ${error.optString("code")} ${error.optString("message")}")
            }
            return response.optJSONObject("data") ?: JSONObject()
        } finally {
            pending.remove(id)
        }
    }

    fun close() {
        socket?.close()
        socket = null
        for (waiter in pending.values) { waiter.socketClosed = true; waiter.latch.countDown() }
        pending.clear()
    }
}
