package com.symphonia.gate2.pcm

data class QueueOfferResult(val droppedOldest: Boolean, val size: Int)

class BoundedPcmFrameQueue(private val capacity: Int) {
    private val frames = ArrayDeque<PcmFrame>(capacity)

    init { require(capacity > 0) { "Queue capacity must be positive" } }

    @Synchronized
    fun offer(frame: PcmFrame): QueueOfferResult {
        val dropped = frames.size == capacity
        if (dropped) frames.removeFirst()
        frames.addLast(frame)
        return QueueOfferResult(droppedOldest = dropped, size = frames.size)
    }

    @Synchronized
    fun poll(): PcmFrame? = if (frames.isEmpty()) null else frames.removeFirst()

    @Synchronized
    fun size(): Int = frames.size

    @Synchronized
    fun clear() = frames.clear()
}
