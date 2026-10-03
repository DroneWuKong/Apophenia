package com.dronewukong.apophenia.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class GarminMessageDecoderTest {
    private val first = mapOf("type" to "observation", "event_id" to "watch:1")
    private val second = mapOf("type" to "observation", "event_id" to "watch:2")

    @Test
    fun acceptsFlatSdkPayload() {
        assertEquals(listOf(first, second), GarminMessageDecoder.observationPackets(listOf(first, second)))
    }

    @Test
    fun acceptsNestedWatchBatchPayload() {
        assertEquals(listOf(first, second), GarminMessageDecoder.observationPackets(listOf(listOf(first, second))))
    }

    @Test
    fun ignoresNonObservationValues() {
        val payload: List<Any> = listOf("noise", listOf(mapOf("type" to "other")), 3)
        assertEquals(emptyList<Map<*, *>>(), GarminMessageDecoder.observationPackets(payload))
    }
}
