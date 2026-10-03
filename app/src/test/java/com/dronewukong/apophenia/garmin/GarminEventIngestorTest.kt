package com.dronewukong.apophenia.garmin

import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationCaptureRequest
import com.dronewukong.apophenia.data.ObservationOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GarminEventIngestorTest {
    @Test
    fun preservesWatchTimestampAndAttachesMetricsToInsertedObservation() {
        val store = FakeStore(inserted = true)
        val diagnostics = mutableListOf<String>()
        val ingestor = GarminEventIngestor(store, "watch-app", diagnostics::add)

        val result = ingestor.ingest(
            "Epix Pro",
            mapOf(
                "type" to "observation",
                "v" to 3,
                "event_id" to "install:9",
                "ts_ms" to 1_700_000_000_123L,
                "kind" to "OBSERVATION",
                "label" to "Light changed",
                "metrics" to mapOf("garmin_heart_rate_bpm" to 72)
            )
        )

        assertTrue(result is GarminIngestResult.Accepted)
        assertEquals(1_700_000_000_123L, store.request!!.timestampMs)
        assertEquals(ObservationOrigin.GARMIN, store.request!!.origin)
        assertEquals("install:9", store.request!!.externalEventId)
        assertEquals(1, store.samples.size)
        assertEquals(42L, store.samples.single().observationId)
        assertEquals("bpm", store.samples.single().unit)
        assertTrue(diagnostics.last().contains("Stored Garmin event"))
    }

    @Test
    fun duplicateRetryDoesNotAttachContextTwice() {
        val store = FakeStore(inserted = false)
        val diagnostics = mutableListOf<String>()
        val ingestor = GarminEventIngestor(store, "watch-app", diagnostics::add)

        ingestor.ingest(
            "Epix Pro",
            mapOf(
                "type" to "observation",
                "v" to 3,
                "event_id" to "install:9",
                "ts_ms" to 1_700_000_000_123L,
                "kind" to "OBSERVATION",
                "metrics" to mapOf("garmin_stress" to 30)
            )
        )

        assertTrue(store.samples.isEmpty())
        assertTrue(diagnostics.any { it.contains("duplicate") })
    }

    @Test
    fun malformedPacketIsRejectedWithDiagnostic() {
        val store = FakeStore(inserted = true)
        val diagnostics = mutableListOf<String>()
        val result = GarminEventIngestor(store, "watch-app", diagnostics::add)
            .ingest("Epix Pro", mapOf("type" to "status"))

        assertTrue(result is GarminIngestResult.Rejected)
        assertTrue(store.request == null)
        assertTrue(diagnostics.single().contains("unsupported type"))
    }

    private class FakeStore(private val inserted: Boolean) : GarminEventStore {
        var request: ObservationCaptureRequest? = null
        val samples = mutableListOf<ContextSample>()

        override fun log(request: ObservationCaptureRequest, onResult: (Long, Boolean) -> Unit) {
            this.request = request
            onResult(42L, inserted)
        }

        override fun insertContext(samples: List<ContextSample>) {
            this.samples += samples
        }
    }
}
