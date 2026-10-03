package com.dronewukong.apophenia.correlation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class CaptureMatcherTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 10, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun matchesOneControlPerEventWithinTimeAndWeekdayStratum() {
        val events = listOf(
            TimedCaptureValue(at(5, 9), 10.0),
            TimedCaptureValue(at(6, 10), 20.0)
        )
        val controls = listOf(
            TimedCaptureValue(at(7, 9, 20), 1.0),
            TimedCaptureValue(at(8, 10, 10), 2.0),
            TimedCaptureValue(at(10, 21), 99.0)
        )

        val result = CaptureMatcher.match(events, controls, zone)

        assertEquals(listOf(10.0, 20.0), result.events)
        assertEquals(listOf(1.0, 2.0), result.controls)
        assertEquals(0, result.unmatchedEventCount)
    }

    @Test
    fun doesNotReuseAControlOrCrossWeekendBoundary() {
        val events = listOf(
            TimedCaptureValue(at(3, 9), 10.0),
            TimedCaptureValue(at(4, 9), 20.0)
        )
        val controls = listOf(
            TimedCaptureValue(at(5, 9), 1.0),
            TimedCaptureValue(at(10, 9), 2.0)
        )

        val result = CaptureMatcher.match(events, controls, zone)

        assertEquals(1, result.events.size)
        assertEquals(1, result.controls.size)
        assertEquals(1, result.unmatchedEventCount)
    }
}
