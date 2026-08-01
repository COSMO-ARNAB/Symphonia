package com.symphonia.gate2.signaling

import com.symphonia.gate2.contracts.ConnectionState
import com.symphonia.gate2.contracts.GATE2_PROTOCOL_VERSION
import com.symphonia.gate2.contracts.GuestId
import com.symphonia.gate2.contracts.ParticipantRole
import com.symphonia.gate2.contracts.RoomId
import com.symphonia.gate2.contracts.RoomState
import com.symphonia.gate2.contracts.ShareState
import com.symphonia.gate2.contracts.ShareStoppedReason
import com.symphonia.gate2.contracts.SignedRoomToken

/** Transport-independent control-plane messages. Audio data is intentionally absent. */
sealed interface SignalingMessage {
    val protocolVersion: Int
    val messageId: String
    val roomId: RoomId
}

data class JoinRoom(
    override val messageId: String,
    override val roomId: RoomId,
    val signedRoomToken: SignedRoomToken,
    val guestId: GuestId,
    override val protocolVersion: Int = GATE2_PROTOCOL_VERSION,
) : SignalingMessage {
    init { validateEnvelope(protocolVersion, messageId) }
    override fun toString(): String =
        "JoinRoom(messageId=$messageId, roomId=$roomId, signedRoomToken=[REDACTED], guestId=$guestId, protocolVersion=$protocolVersion)"
}

data class ParticipantChanged(
    override val messageId: String,
    override val roomId: RoomId,
    val guestId: GuestId,
    val role: ParticipantRole,
    val connectionState: ConnectionState,
    override val protocolVersion: Int = GATE2_PROTOCOL_VERSION,
) : SignalingMessage {
    init { validateEnvelope(protocolVersion, messageId) }
}

data class RoomStateChanged(
    override val messageId: String,
    override val roomId: RoomId,
    val state: RoomState,
    val graceExpiresAtEpochMs: Long? = null,
    override val protocolVersion: Int = GATE2_PROTOCOL_VERSION,
) : SignalingMessage {
    init {
        validateEnvelope(protocolVersion, messageId)
        require((state == RoomState.GRACE) == (graceExpiresAtEpochMs != null)) {
            "Only a room in grace state has a grace expiry"
        }
        require(graceExpiresAtEpochMs == null || graceExpiresAtEpochMs >= 0) { "Grace expiry must be non-negative" }
    }
}

data class ShareStateChanged(
    override val messageId: String,
    override val roomId: RoomId,
    val state: ShareState,
    val sourceAppLabel: String?,
    val stoppedReason: ShareStoppedReason? = null,
    override val protocolVersion: Int = GATE2_PROTOCOL_VERSION,
) : SignalingMessage {
    init {
        validateEnvelope(protocolVersion, messageId)
        require((state == ShareState.STOPPED) == (stoppedReason != null)) {
            "A stopped reason is required only when sharing is stopped"
        }
        require(state == ShareState.STOPPED || !sourceAppLabel.isNullOrBlank()) {
            "An active share state requires a source app label"
        }
        require(sourceAppLabel == null || sourceAppLabel.isNotBlank()) { "Source app label must not be blank" }
    }
}

private fun validateEnvelope(protocolVersion: Int, messageId: String) {
    require(protocolVersion == GATE2_PROTOCOL_VERSION) { "Unsupported signaling protocol version" }
    require(messageId.isNotBlank()) { "Message ID must not be blank" }
}
