package com.dronewukong.apophenia.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeCaptureTest {
    @Test
    fun ordinaryVibePreservesTapTimestampAndExactLabel() {
        val request = VibeCapture.request(
            grade = VibeGrade.BAD,
            timestampMs = 1_780_000_123_456L,
            origin = ObservationOrigin.WIDGET
        )

        assertEquals(1_780_000_123_456L, request.timestampMs)
        assertEquals(ObservationKind.VIBE, request.kind)
        assertEquals("Bad 🙁", request.label)
        assertEquals(3, request.vibeRating)
        assertFalse(request.egress)
        assertEquals(ObservationOrigin.WIDGET, request.origin)
    }

    @Test
    fun egressIsAlwaysDistinctVibeFiveEvidence() {
        val request = VibeCapture.request(
            grade = VibeGrade.JANKY,
            timestampMs = 42,
            note = "Left by the west door",
            egress = true
        )

        assertEquals("NOPE, I'M OUT", request.label)
        assertEquals(5, request.vibeRating)
        assertTrue(request.egress)
        assertEquals("Left by the west door", request.note)
    }

    @Test
    fun egressCannotBeAttachedToLowerRating() {
        assertThrows(IllegalArgumentException::class.java) {
            VibeCapture.request(VibeGrade.SNAFU, timestampMs = 1, egress = true)
        }
    }
}
