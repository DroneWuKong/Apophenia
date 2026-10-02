package com.dronewukong.apophenia.garmin

import com.dronewukong.apophenia.data.ObservationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminPacketParserTest {
    @Test
    fun versionThreePreservesWatchTimestampAndEventId() {
        val parsed = GarminPacketParser.parse(
            mapOf(
                "type" to "observation",
                "v" to 3,
                "event_id" to "install-abc:17",
                "ts_ms" to 1_726_000_123_456L,
                "kind" to "COINCIDENCE",
                "label" to "Coincidence",
                "metrics" to mapOf("heart_rate_bpm" to 78, "missing" to null, "bad" to "unknown")
            )
        )!!

        assertEquals(1_726_000_123_456L, parsed.timestampMs)
        assertEquals("install-abc:17", parsed.eventId)
        assertEquals(ObservationKind.COINCIDENCE, parsed.kind)
        assertEquals(mapOf("heart_rate_bpm" to 78.0), parsed.metrics)
    }

    @Test
    fun legacyPacketFallsBackCleanlyAndOmitsUnavailableMetrics() {
        val parsed = GarminPacketParser.parse(
            mapOf("type" to "observation", "label" to "That was weird", "metrics" to emptyMap<String, Any>()),
            receivedAtMs = 99L
        )!!
        assertEquals(2, parsed.protocolVersion)
        assertEquals(99L, parsed.timestampMs)
        assertEquals(ObservationKind.WEIRD, parsed.kind)
        assertNull(parsed.eventId)
        assertTrue(parsed.metrics.isEmpty())
        assertTrue(parsed.note.isEmpty())
    }

    @Test
    fun rejectsNonObservationMessages() {
        assertNull(GarminPacketParser.parse(mapOf("type" to "ping")))
    }
}
