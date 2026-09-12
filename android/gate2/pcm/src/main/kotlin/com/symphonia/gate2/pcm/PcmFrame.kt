package com.symphonia.gate2.pcm

data class PcmFormat(
    val sampleRateHz: Int = 48_000,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz > 0) { "Sample rate must be positive" }
        require(channelCount in 1..2) { "Only mono or stereo PCM is supported" }
    }

    fun sampleCountFor(durationMs: Int): Int {
        require(durationMs > 0) { "Frame duration must be positive" }
        require(sampleRateHz.toLong() * durationMs % 1_000L == 0L) {
            "Duration does not contain a whole number of samples"
        }
        return (sampleRateHz.toLong() * durationMs / 1_000L * channelCount).toInt()
    }
}

class PcmFrame(
    samples: ShortArray,
    val format: PcmFormat,
    val durationMs: Int,
    val capturedAtNanos: Long,
) {
    private val ownedSamples = samples.copyOf()

    val samples: ShortArray
        get() = ownedSamples.copyOf()

    init {
        require(ownedSamples.size == format.sampleCountFor(durationMs)) {
            "PCM sample count does not match format and duration"
        }
        require(capturedAtNanos >= 0) { "Capture timestamp must be non-negative" }
    }
}
