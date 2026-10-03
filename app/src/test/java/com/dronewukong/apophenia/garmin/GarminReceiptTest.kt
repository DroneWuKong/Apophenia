package com.dronewukong.apophenia.garmin

import org.junit.Assert.assertEquals
import org.junit.Test

class GarminReceiptTest {
    @Test
    fun encodesDurableStorageReceiptForWatch() {
        assertEquals(
            mapOf<String, Any>(
                "v" to 1,
                "type" to "receipt",
                "event_ids" to listOf("install:9")
            ),
            GarminReceipt.payload("install:9")
        )
    }
}
