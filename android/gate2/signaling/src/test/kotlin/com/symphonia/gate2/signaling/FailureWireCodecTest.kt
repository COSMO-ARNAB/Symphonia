package com.symphonia.gate2.signaling

import com.symphonia.gate2.contracts.FailureReport
import com.symphonia.gate2.contracts.Gate2Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureWireCodecTest {
    @Test
    fun `failure report round trips as versioned metadata-only JSON`() {
        val report = FailureReport(
            failure = Gate2Failure.MEDIA_TRANSPORT_UNAVAILABLE,
            occurredAtEpochMs = 1234L,
            operation = "publish \"room\"\\attempt",
        )

        val encoded = FailureWireCodec.encode(report)

        assertEquals(report, FailureWireCodec.decode(encoded))
        assertTrue(encoded.contains("\"schemaVersion\":1"))
        assertTrue(encoded.contains("\"code\":\"G2-MED-02\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown failure code is rejected`() {
        FailureWireCodec.decode(
            """{"schemaVersion":1,"code":"unknown","category":"internal","retryable":false,"occurredAtEpochMs":1,"operation":"start"}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate fields are rejected`() {
        FailureWireCodec.decode(
            """{"schemaVersion":1,"schemaVersion":1,"code":"G2-INT-01","category":"internal","retryable":false,"occurredAtEpochMs":1,"operation":"start"}""",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `trailing JSON content is rejected`() {
        FailureWireCodec.decode(
            """{"schemaVersion":1,"code":"G2-INT-01","category":"internal","retryable":false,"occurredAtEpochMs":1,"operation":"start"} garbage""",
        )
    }

    @Test
    fun `control characters and unicode escapes decode safely`() {
        val decoded = FailureWireCodec.decode(
            """{"schemaVersion":1,"code":"G2-INT-01","category":"internal","retryable":false,"occurredAtEpochMs":1,"operation":"line\n\u0041"}""",
        )
        assertEquals("line\nA", decoded.operation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized failure payload is rejected`() {
        FailureWireCodec.decode(" ".repeat(8_193))
    }

    @Test
    fun `signed room token and join message redact secrets`() {
        val secret = "header.payload.signature"
        val token = com.symphonia.gate2.contracts.SignedRoomToken(secret)
        val join = JoinRoom("message-1", com.symphonia.gate2.contracts.RoomId("room-1"), token, com.symphonia.gate2.contracts.GuestId("guest-1"))

        assertFalse(token.toString().contains(secret))
        assertFalse(join.toString().contains(secret))
        assertTrue(join.toString().contains("REDACTED"))
    }

    @Test
    fun `device token and identity redact secrets`() {
        val secret = "device-secret"
        val token = com.symphonia.gate2.contracts.DeviceToken(secret)
        val identity = com.symphonia.gate2.contracts.GuestIdentity(
            com.symphonia.gate2.contracts.GuestId("guest-1"), "Guest", token, "device-1",
        )

        assertFalse(token.toString().contains(secret))
        assertFalse(identity.toString().contains(secret))
        assertTrue(identity.toString().contains("REDACTED"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `share state requires stopped reason exactly when stopped`() {
        ShareStateChanged(
            "message-2", com.symphonia.gate2.contracts.RoomId("room-1"),
            com.symphonia.gate2.contracts.ShareState.STOPPED, "Player", null,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `room grace expiry is rejected outside grace state`() {
        RoomStateChanged(
            "message-3", com.symphonia.gate2.contracts.RoomId("room-1"),
            com.symphonia.gate2.contracts.RoomState.ACTIVE, 100,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported protocol version is rejected`() {
        ParticipantChanged(
            "message-4", com.symphonia.gate2.contracts.RoomId("room-1"),
            com.symphonia.gate2.contracts.GuestId("guest-1"), com.symphonia.gate2.contracts.ParticipantRole.LISTENER,
            com.symphonia.gate2.contracts.ConnectionState.CONNECTED, protocolVersion = 2,
        )
    }
}
