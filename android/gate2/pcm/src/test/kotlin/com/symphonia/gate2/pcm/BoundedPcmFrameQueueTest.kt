package com.symphonia.gate2.pcm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedPcmFrameQueueTest {
    private val format = PcmFormat(channelCount = 1)

    private fun frame(value: Short) = PcmFrame(
        ShortArray(format.sampleCountFor(10)) { value }, format, 10, value.toLong(),
    )

    @Test
    fun `full queue drops oldest frame and remains bounded`() {
        val queue = BoundedPcmFrameQueue(capacity = 2)
        assertFalse(queue.offer(frame(1)).droppedOldest)
        assertFalse(queue.offer(frame(2)).droppedOldest)
        assertTrue(queue.offer(frame(3)).droppedOldest)

        assertEquals(2, queue.size())
        assertEquals(2.toShort(), queue.poll()!!.samples.first())
        assertEquals(3.toShort(), queue.poll()!!.samples.first())
    }

    @Test
    fun `frame owns input and returned sample arrays`() {
        val source = ShortArray(format.sampleCountFor(10)) { 7 }
        val frame = PcmFrame(source, format, 10, 0)
        source[0] = 1
        val exposed = frame.samples
        exposed[0] = 2

        assertEquals(7.toShort(), frame.samples[0])
    }
}
