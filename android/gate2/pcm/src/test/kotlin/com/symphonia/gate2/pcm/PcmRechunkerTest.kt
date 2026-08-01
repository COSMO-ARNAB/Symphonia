package com.symphonia.gate2.pcm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmRechunkerTest {
    private val format = PcmFormat(channelCount = 2)

    @Test
    fun `combines contiguous frames without merging caller-owned storage`() {
        val first = ShortArray(format.sampleCountFor(10)) { 1 }
        val second = ShortArray(format.sampleCountFor(10)) { 2 }
        val rechunker = PcmRechunker(format, 20)

        assertTrue(rechunker.accept(PcmFrame(first, format, 10, 100L)).frames.isEmpty())
        first.fill(9)
        val result = rechunker.accept(PcmFrame(second, format, 10, 10_000_100L))

        assertFalse(result.timestampDiscontinuityDetected)
        assertArrayEquals(ShortArray(first.size) { 1 } + second, result.frames.single().samples)
    }

    @Test
    fun `splits one frame with advancing output timestamps`() {
        val input = ShortArray(format.sampleCountFor(20)) { it.toShort() }
        val frames = PcmRechunker(format, 10).accept(PcmFrame(input, format, 20, 50L)).frames

        assertEquals(2, frames.size)
        assertEquals(50L, frames[0].capturedAtNanos)
        assertEquals(10_000_050L, frames[1].capturedAtNanos)
    }

    @Test
    fun `timestamp gap discards pending samples and reports reset`() {
        val rechunker = PcmRechunker(format, 20)
        rechunker.accept(PcmFrame(ShortArray(format.sampleCountFor(10)) { 1 }, format, 10, 0L))

        val gap = rechunker.accept(PcmFrame(ShortArray(format.sampleCountFor(10)) { 2 }, format, 10, 30_000_000L))
        val completion = rechunker.accept(PcmFrame(ShortArray(format.sampleCountFor(10)) { 3 }, format, 10, 40_000_000L))

        assertTrue(gap.timestampDiscontinuityDetected)
        assertTrue(gap.frames.isEmpty())
        assertArrayEquals(
            ShortArray(format.sampleCountFor(10)) { 2 } + ShortArray(format.sampleCountFor(10)) { 3 },
            completion.frames.single().samples,
        )
        assertEquals(30_000_000L, completion.frames.single().capturedAtNanos)
    }
}
