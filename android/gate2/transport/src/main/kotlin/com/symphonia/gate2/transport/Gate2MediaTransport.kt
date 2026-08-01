package com.symphonia.gate2.transport

import com.symphonia.gate2.contracts.Gate2Failure
import com.symphonia.gate2.contracts.RoomId

enum class MediaTransportState { IDLE, CONNECTING, READY, UNAVAILABLE, CLOSED }

sealed interface MediaTransportResult {
    data class Success(val state: MediaTransportState) : MediaTransportResult
    data class Failure(val failure: Gate2Failure) : MediaTransportResult
}

/**
 * Boundary for a future encrypted WebRTC/SRTP implementation.
 * Gate 2 deliberately defines lifecycle only: it neither encodes PCM nor presents byte arrays as Opus.
 */
interface Gate2MediaTransport {
    val state: MediaTransportState
    fun connect(roomId: RoomId): MediaTransportResult
    fun close()
}

class UnsupportedGate2MediaTransport : Gate2MediaTransport {
    override val state: MediaTransportState = MediaTransportState.UNAVAILABLE

    override fun connect(roomId: RoomId): MediaTransportResult =
        MediaTransportResult.Failure(Gate2Failure.MEDIA_TRANSPORT_UNSUPPORTED)

    override fun close() = Unit
}

class UnavailableGate2MediaTransport : Gate2MediaTransport {
    override val state: MediaTransportState = MediaTransportState.UNAVAILABLE

    override fun connect(roomId: RoomId): MediaTransportResult =
        MediaTransportResult.Failure(Gate2Failure.MEDIA_TRANSPORT_UNAVAILABLE)

    override fun close() = Unit
}
