package com.symphonia.gate2.transport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MediasoupSpeakerTransportTest {

    @Test
    fun tracksListenerJoinLeaveAndRoomCloseNotifications() {
        val observedCounts = mutableListOf<Int>()
        val transport = MediasoupSpeakerTransport(android.content.ContextWrapper(null))
        transport.onListenerCountChanged = { observedCounts.add(it) }

        // Host/Speaker connections do not count as listeners
        transport.handleSignalingNotification(
            "PARTICIPANT_CONNECTED",
            JSONObject().put("participantId", "host-1").put("role", "HOST")
        )
        assertEquals(0, transport.listenerCount)

        // Two listeners join (plus duplicate event ignored)
        transport.handleSignalingNotification(
            "PARTICIPANT_CONNECTED",
            JSONObject().put("participantId", "l-1").put("role", "LISTENER")
        )
        transport.handleSignalingNotification(
            "PARTICIPANT_CONNECTED",
            JSONObject().put("participantId", "l-1").put("role", "LISTENER")
        )
        transport.handleSignalingNotification(
            "PARTICIPANT_CONNECTED",
            JSONObject().put("participantId", "l-2").put("role", "LISTENER")
        )
        assertEquals(2, transport.listenerCount)

        // One listener leaves
        transport.handleSignalingNotification(
            "PARTICIPANT_DISCONNECTED",
            JSONObject().put("participantId", "l-1").put("role", "LISTENER")
        )
        assertEquals(1, transport.listenerCount)

        // Room closed resets to zero
        transport.handleSignalingNotification("ROOM_CLOSED", JSONObject())
        assertEquals(0, transport.listenerCount)
        assertEquals(listOf(1, 2, 1, 0), observedCounts)
    }
}
