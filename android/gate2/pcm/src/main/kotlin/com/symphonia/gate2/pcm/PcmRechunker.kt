package com.symphonia.gate2.pcm

class PcmRechunker(
    private val format: PcmFormat,
    private val outputDurationMs: Int,
) {
    private val outputSampleCount = format.sampleCountFor(outputDurationMs)
    private val pending = ShortArray(outputSampleCount)
    private var pendingSize = 0
    private var pendingCapturedAtNanos: Long? = null
    private var expectedInputTimestampNanos: Long? = null

    data class Result(
        val frames: List<PcmFrame>,
        val timestampDiscontinuityDetected: Boolean,
    )

    @Synchronized
    fun accept(frame: PcmFrame): Result {
        require(frame.format == format) { "PCM format changed during rechunking" }
        val discontinuity = expectedInputTimestampNanos?.let { it != frame.capturedAtNanos } == true
        if (discontinuity) clearPending()

        val input = frame.samples
        if (pendingSize == 0) pendingCapturedAtNanos = frame.capturedAtNanos

        val output = mutableListOf<PcmFrame>()
        val frameNanos = outputDurationMs * 1_000_000L
        var inputOffset = 0
        while (inputOffset < input.size) {
            val copied = minOf(outputSampleCount - pendingSize, input.size - inputOffset)
            input.copyInto(pending, pendingSize, inputOffset, inputOffset + copied)
            pendingSize += copied
            inputOffset += copied
            if (pendingSize == outputSampleCount) {
                val timestamp = checkNotNull(pendingCapturedAtNanos)
                output += PcmFrame(pending, format, outputDurationMs, timestamp)
                pendingSize = 0
                pendingCapturedAtNanos = if (inputOffset < input.size) timestamp + frameNanos else null
            }
        }
        expectedInputTimestampNanos = frame.capturedAtNanos + frame.durationMs * 1_000_000L
        return Result(output, discontinuity)
    }

    @Synchronized
    fun reset() {
        clearPending()
        expectedInputTimestampNanos = null
    }

    private fun clearPending() {
        pendingSize = 0
        pendingCapturedAtNanos = null
    }
}
