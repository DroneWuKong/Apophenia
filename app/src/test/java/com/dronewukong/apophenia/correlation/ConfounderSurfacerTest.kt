package com.dronewukong.apophenia.correlation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfounderSurfacerTest {
    @Test fun surfacesDescriptiveDifferencesWithOneCapturePerGroup() {
        val rows = ConfounderSurfacer.scan(mapOf(
            "network_signal_dbm" to (listOf(-110.0) to listOf(-80.0)),
            "same" to (listOf(1.0) to listOf(1.0))
        ))
        assertEquals("network_signal_dbm", rows.first().feature)
        assertTrue(rows.first().summary.contains("descriptive only"))
        assertEquals(1, rows.first().eventCount)
    }

    @Test fun binaryPresenceUsesHumanReadableRates() {
        val row = ConfounderSurfacer.scan(mapOf(
            "bluetooth device presence" to (listOf(1.0,1.0,0.0,1.0) to listOf(0.0,0.0,0.0,1.0))
        )).single()
        assertTrue(row.summary.contains("75%"))
        assertTrue(row.summary.contains("25%"))
    }

    @Test fun omitsFeaturesWithoutBothGroupsRatherThanInventingABaseline() {
        assertTrue(ConfounderSurfacer.scan(mapOf("missing" to (listOf(1.0) to emptyList()))).isEmpty())
    }
}
