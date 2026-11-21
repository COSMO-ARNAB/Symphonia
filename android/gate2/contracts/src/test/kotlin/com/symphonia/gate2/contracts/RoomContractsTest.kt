package com.symphonia.gate2.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoomContractsTest {

    @Test
    fun validRoomIdsAccepted() {
        val validIds = listOf("A1B2C3", "000000", "ZZZZZZ", "123456", "ABCDEF")
        for (id in validIds) {
            assertTrue("Expected isValid to be true for $id", RoomId.isValid(id))
            val roomId = RoomId(id)
            assertEquals(id, roomId.value)
        }
    }

    @Test
    fun invalidRoomIdsRejected() {
        val invalidIds = listOf(
            "",
            "   ",
            "A1B2C",
            "A",
            "12345",
            "A1B2C3D",
            "ROOM123",
            "a1b2c3",
            "ROOM-1",
            "A1B2C!",
            "test-room-123456789012345678901234567890123"
        )
        for (invalid in invalidIds) {
            assertFalse("Expected isValid to be false for '$invalid'", RoomId.isValid(invalid))
            try {
                RoomId(invalid)
                fail("Expected IllegalArgumentException for '$invalid'")
            } catch (expected: IllegalArgumentException) {
                // Expected
            }
        }
    }

    @Test
    fun signedRoomTokenAcceptsLongTokenStrings() {
        val tokenString = "test-room-123456789012345678901234567890123"
        val token = SignedRoomToken(tokenString)
        assertEquals(tokenString, token.value)
        assertEquals("SignedRoomToken([REDACTED])", token.toString())
    }
}
