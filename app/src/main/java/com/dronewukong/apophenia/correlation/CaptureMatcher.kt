package com.dronewukong.apophenia.correlation

import java.time.Instant
import java.time.ZoneId

data class TimedCaptureValue(val timestampMs: Long, val value: Double)

data class MatchedCaptureValues(
    val events: List<Double>,
    val controls: List<Double>,
    val unmatchedEventCount: Int,
    val strategy: String = "one-to-one · 4-hour local-time block · weekday/weekend"
)

object CaptureMatcher {
    fun match(
        events: List<TimedCaptureValue>,
        controls: List<TimedCaptureValue>,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): MatchedCaptureValues {
        val available = controls.toMutableList()
        val matchedEvents = mutableListOf<Double>()
        val matchedControls = mutableListOf<Double>()

        events.sortedBy { it.timestampMs }.forEach { event ->
            val eventStratum = stratum(event.timestampMs, zoneId)
            val candidate = available
                .withIndex()
                .filter { stratum(it.value.timestampMs, zoneId) == eventStratum }
                .minByOrNull { cyclicMinuteDistance(event.timestampMs, it.value.timestampMs, zoneId) }
                ?: return@forEach
            matchedEvents += event.value
            matchedControls += candidate.value.value
            available.removeAt(candidate.index)
        }

        return MatchedCaptureValues(
            events = matchedEvents,
            controls = matchedControls,
            unmatchedEventCount = events.size - matchedEvents.size
        )
    }

    private fun stratum(timestampMs: Long, zoneId: ZoneId): Pair<Int, Boolean> {
        val local = Instant.ofEpochMilli(timestampMs).atZone(zoneId)
        val fourHourBlock = local.hour / 4
        val weekend = local.dayOfWeek.value >= 6
        return fourHourBlock to weekend
    }

    private fun cyclicMinuteDistance(aMs: Long, bMs: Long, zoneId: ZoneId): Int {
        fun minuteOfDay(value: Long): Int {
            val local = Instant.ofEpochMilli(value).atZone(zoneId)
            return local.hour * 60 + local.minute
        }
        val raw = kotlin.math.abs(minuteOfDay(aMs) - minuteOfDay(bMs))
        return minOf(raw, 24 * 60 - raw)
    }
}
