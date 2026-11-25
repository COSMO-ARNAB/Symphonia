package com.symphonia.gate2.signaling

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Gate2SignalingClientTest {

    private var server: MockSignalingServer? = null
    private var client: Gate2SignalingClient? = null

    class MockSignalingServer(port: Int = 0) : WebSocketServer(InetSocketAddress("127.0.0.1", port)) {
        val messagesReceived = CopyOnWriteArrayList<JSONObject>()
        var messageHandler: ((WebSocket, JSONObject) -> Unit)? = null
        val serverReadyLatch = CountDownLatch(1)

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {}
        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {}
        override fun onError(conn: WebSocket?, ex: Exception) {}
        override fun onStart() {
            serverReadyLatch.countDown()
        }

        override fun onMessage(conn: WebSocket, message: String) {
            val json = JSONObject(message)
            messagesReceived.add(json)
            messageHandler?.invoke(conn, json)
        }
    }

    @Before
    fun setUp() {
        val s = MockSignalingServer(0)
        s.isReuseAddr = true
        s.start()
        assertTrue("Server failed to start", s.serverReadyLatch.await(5, TimeUnit.SECONDS))
        server = s
    }

    @After
    fun tearDown() {
        client?.close()
        server?.stop(1000)
    }

    @Test
    fun testUrlNormalization() {
        assertEquals(URI("ws://10.0.2.2:3000/gate2"), Gate2SignalingClient.normalizeUrl("ws://10.0.2.2:3000"))
        assertEquals(URI("ws://10.0.2.2:3000/gate2"), Gate2SignalingClient.normalizeUrl("ws://10.0.2.2:3000/"))
        assertEquals(URI("ws://10.0.2.2:3000/gate2"), Gate2SignalingClient.normalizeUrl("ws://10.0.2.2:3000/gate2"))
        assertEquals(URI("ws://10.0.2.2:3000/gate2"), Gate2SignalingClient.normalizeUrl("ws://10.0.2.2:3000/gate2/"))
        assertEquals(URI("ws://192.168.1.50:8080/gate2"), Gate2SignalingClient.normalizeUrl("  http://192.168.1.50:8080  "))
        assertEquals(URI("wss://sfu.example.com/gate2"), Gate2SignalingClient.normalizeUrl("https://sfu.example.com"))
    }

    @Test
    fun testSuccessfulRequestResponseCorrelation() {
        val port = server!!.port
        server!!.messageHandler = { conn, json ->
            val id = json.getString("id")
            val type = json.getString("type")
            if (type == "CREATE_ROOM") {
                val res = JSONObject().apply {
                    put("id", id)
                    put("type", "RESPONSE")
                    put("ok", true)
                    put("data", JSONObject().apply {
                        put("roomId", "ROOM01")
                        put("role", "HOST")
                    })
                }
                conn.send(res.toString())
            }
        }

        client = Gate2SignalingClient("ws://127.0.0.1:$port/gate2")
        client!!.connectBlocking(5000)
        assertTrue(client!!.isConnected)

        val roomData = client!!.createRoom("TestHost")
        assertEquals("ROOM01", roomData.getString("roomId"))
        assertEquals("HOST", roomData.getString("role"))

        assertEquals(1, server!!.messagesReceived.size)
        val sent = server!!.messagesReceived[0]
        assertEquals("CREATE_ROOM", sent.getString("type"))
        assertEquals("TestHost", sent.getJSONObject("data").getString("displayName"))
    }

    @Test
    fun testProtocolErrorExtraction() {
        val port = server!!.port
        server!!.messageHandler = { conn, json ->
            val id = json.getString("id")
            val res = JSONObject().apply {
                put("id", id)
                put("type", "RESPONSE")
                put("ok", false)
                put("error", JSONObject().apply {
                    put("code", "N-CANNOT_CONSUME")
                    put("message", "RTP capabilities cannot consume this producer")
                })
            }
            conn.send(res.toString())
        }

        client = Gate2SignalingClient("ws://127.0.0.1:$port/gate2")
        client!!.connectBlocking(5000)

        try {
            client!!.consume("trans-1", "prod-1", JSONObject())
            fail("Expected SignalingException")
        } catch (e: SignalingException) {
            assertEquals("N-CANNOT_CONSUME", e.code)
            assertTrue(e.message.contains("RTP capabilities cannot consume"))
        }
    }

    @Test
    fun testServerNotificationDispatch() {
        val port = server!!.port
        client = Gate2SignalingClient("ws://127.0.0.1:$port/gate2")

        val notificationLatch = CountDownLatch(1)
        var receivedType: String? = null
        var receivedProducerId: String? = null

        client!!.onNotification = { type, data ->
            receivedType = type
            receivedProducerId = data.optString("producerId")
            notificationLatch.countDown()
        }

        client!!.connectBlocking(5000)

        // Server broadcasts PRODUCER_AVAILABLE
        val notif = JSONObject().apply {
            put("type", "PRODUCER_AVAILABLE")
            put("data", JSONObject().put("producerId", "p-999"))
        }
        server!!.broadcast(notif.toString())

        assertTrue("Timed out waiting for notification", notificationLatch.await(5, TimeUnit.SECONDS))
        assertEquals("PRODUCER_AVAILABLE", receivedType)
        assertEquals("p-999", receivedProducerId)
    }

    @Test
    fun testGetListenerInviteParsesSignedUrlAndExpiry() {
        val port = server!!.port
        server!!.messageHandler = { conn, json ->
            val id = json.getString("id")
            if (json.getString("type") == "GET_LISTENER_INVITE") {
                val res = JSONObject().apply {
                    put("id", id)
                    put("type", "RESPONSE")
                    put("ok", true)
                    put("data", JSONObject().apply {
                        put("url", "https://203.0.113.10/listener.html?roomId=ROOM01#invite=ROOM01.1700000000000.abcd")
                        put("expiresAt", 1700000000000L)
                    })
                }
                conn.send(res.toString())
            }
        }

        client = Gate2SignalingClient("ws://127.0.0.1:$port/gate2")
        client!!.connectBlocking(5000)

        val invite = client!!.getListenerInvite()
        assertEquals(
            "https://203.0.113.10/listener.html?roomId=ROOM01#invite=ROOM01.1700000000000.abcd",
            invite.url
        )
        assertEquals(1700000000000L, invite.expiresAt)
    }
}
