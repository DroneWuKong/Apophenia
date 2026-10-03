package com.dronewukong.apophenia.audio

class PcmRingBuffer(capacityBytes: Int) {
    private val bytes = ByteArray(capacityBytes.coerceAtLeast(2))
    private var writeIndex = 0
    private var size = 0

    @Synchronized
    fun write(chunk: ByteArray, count: Int = chunk.size) {
        val safeCount = count.coerceIn(0, chunk.size)
        if (safeCount >= bytes.size) {
            chunk.copyInto(bytes, 0, safeCount - bytes.size, safeCount)
            writeIndex = 0
            size = bytes.size
            return
        }
        repeat(safeCount) { offset ->
            bytes[writeIndex] = chunk[offset]
            writeIndex = (writeIndex + 1) % bytes.size
        }
        size = (size + safeCount).coerceAtMost(bytes.size)
    }

    @Synchronized
    fun snapshot(): ByteArray {
        val output = ByteArray(size)
        val start = (writeIndex - size + bytes.size) % bytes.size
        repeat(size) { output[it] = bytes[(start + it) % bytes.size] }
        return output
    }

    @Synchronized
    fun clear() {
        writeIndex = 0
        size = 0
        bytes.fill(0)
    }
}
