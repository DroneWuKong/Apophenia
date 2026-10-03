package com.dronewukong.apophenia.correlation

import kotlin.math.abs

data class AmbientDifference(
    val feature: String,
    val eventCount: Int,
    val controlCount: Int,
    val eventMean: Double,
    val controlMean: Double,
    val descriptiveScore: Double,
    val summary: String
)

/** Descriptive screen only: useful before inferential thresholds, never a significance claim. */
object ConfounderSurfacer {
    fun scan(values: Map<String, Pair<List<Double>, List<Double>>>): List<AmbientDifference> = values.mapNotNull { (feature, groups) ->
        val events = groups.first
        val controls = groups.second
        if (events.isEmpty() || controls.isEmpty()) return@mapNotNull null
        val eventMean = events.average()
        val controlMean = controls.average()
        val combined = events + controls
        val binary = combined.all { it == 0.0 || it == 1.0 }
        val score = if (binary) abs(eventMean - controlMean) else {
            val range = (combined.maxOrNull()!! - combined.minOrNull()!!).coerceAtLeast(1e-9)
            (abs(eventMean - controlMean) / range).coerceAtMost(1.0)
        }
        val readable = feature.replace('_', ' ')
        val comparison = if (binary) {
            "$readable appeared in ${percent(eventMean)} of event windows versus ${percent(controlMean)} of matched controls."
        } else {
            val direction = if (eventMean >= controlMean) "higher" else "lower"
            "$readable averaged ${number(eventMean)} at events versus ${number(controlMean)} at controls (${number(abs(eventMean - controlMean))} $direction)."
        }
        AmbientDifference(
            feature = feature,
            eventCount = events.size,
            controlCount = controls.size,
            eventMean = eventMean,
            controlMean = controlMean,
            descriptiveScore = score,
            summary = "$comparison Possible confounder or context difference to check; descriptive only, not adjusted evidence."
        )
    }.sortedByDescending { it.descriptiveScore }

    private fun percent(value: Double) = "%.0f%%".format(value * 100.0)
    private fun number(value: Double) = "%.3f".format(value)
}
