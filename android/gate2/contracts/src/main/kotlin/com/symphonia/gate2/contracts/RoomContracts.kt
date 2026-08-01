package com.symphonia.gate2.contracts

const val GATE2_PROTOCOL_VERSION: Int = 1
const val MVP_SPEAKER_SLOT: Int = 1

@JvmInline
value class RoomId(val value: String) {
    init { require(value.isNotBlank()) { "Room ID must not be blank" } }
}

@JvmInline
value class GuestId(val value: String) {
    init { require(value.isNotBlank()) { "Guest ID must not be blank" } }
}

@JvmInline
value class SignedRoomToken(val value: String) {
    init { require(value.isNotBlank()) { "Signed room token must not be blank" } }
    override fun toString(): String = "SignedRoomToken([REDACTED])"
}

@JvmInline
value class DeviceToken(val value: String) {
    init { require(value.isNotBlank()) { "Device token must not be blank" } }
    override fun toString(): String = "DeviceToken([REDACTED])"
}

enum class RoomState { ACTIVE, GRACE, CLOSED }
enum class ParticipantRole { HOST, SPEAKER, LISTENER }
enum class ConnectionState { CONNECTED, RECONNECTING, DISCONNECTED }
enum class ShareState { LIVE, PAUSED, RECONNECTING, STOPPED }
enum class ShareStoppedReason { APP_ENDED, PERMISSION_REQUIRED, HOST_ENDED, CAPTURE_UNAVAILABLE }

data class Room(
    val id: RoomId,
    val hostGuestId: GuestId,
    val createdAtEpochMs: Long,
    val state: RoomState,
    val graceExpiresAtEpochMs: Long? = null,
)

data class GuestIdentity(
    val guestId: GuestId,
    val nickname: String,
    val deviceToken: DeviceToken,
    val boundDeviceId: String,
) {
    override fun toString(): String =
        "GuestIdentity(guestId=$guestId, nickname=$nickname, deviceToken=[REDACTED], boundDeviceId=$boundDeviceId)"
}

data class Participant(
    val guestId: GuestId,
    val roomId: RoomId,
    val role: ParticipantRole,
    val connectionState: ConnectionState,
    val disconnectedAtEpochMs: Long? = null,
)

data class SpeakerSlot(val number: Int = MVP_SPEAKER_SLOT) {
    init { require(number == MVP_SPEAKER_SLOT) { "Gate 2 supports exactly one speaker slot" } }
}

data class ShareSession(
    val roomId: RoomId,
    val speakerSlot: SpeakerSlot,
    val speakerGuestId: GuestId,
    val sourceAppLabel: String,
    val state: ShareState,
    val stoppedReason: ShareStoppedReason? = null,
    val startedAtEpochMs: Long,
    val pausedAtEpochMs: Long? = null,
)
