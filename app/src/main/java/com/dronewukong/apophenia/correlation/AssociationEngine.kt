package com.dronewukong.apophenia.correlation

import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

data class AssociationResult(
    val eventCount: Int,
    val controlCount: Int,
    val eventMean: Double?,
    val controlMean: Double?,
    val eventMedian: Double?,
    val controlMedian: Double?,
    val eventMad: Double?,
    val controlMad: Double?,
    val delta: Double?,
    val standardizedEffect: Double?,
    val permutationP: Double?,
    val adjustedP: Double?,
    val persistentDirection: Boolean,
    val strength: String,
    val summary: String
)

object AssociationEngine {
    fun compare(
        eventValues: List<Double>,
        controlValues: List<Double>,
        permutations: Int = 1_000,
        seed: Int = 1337
    ): AssociationResult {
        if (eventValues.size < 4 || controlValues.size < 4) return AssociationResult(
            eventValues.size, controlValues.size,
            eventValues.averageOrNull(), controlValues.averageOrNull(),
            eventValues.medianOrNull(), controlValues.medianOrNull(),
            eventValues.madOrNull(), controlValues.madOrNull(),
            null, null, null, null, false,
            "insufficient data", "Need at least 4 event captures and 4 control captures."
        )
        val eventMean = eventValues.average()
        val controlMean = controlValues.average()
        val delta = eventMean - controlMean
        val pooled = pooledStd(eventValues, controlValues)
        val effect = if (pooled > 1e-12) delta / pooled else 0.0
        val p = permutationP(eventValues, controlValues, permutations, seed)
        val persistent = splitHalfPersistence(eventValues, controlValues, delta)
        return buildResult(eventValues, controlValues, delta, effect, p, p, persistent)
    }

    fun compareAll(
        values: Map<String, Pair<List<Double>, List<Double>>>,
        permutations: Int = 1_000,
        seed: Int = 1337
    ): Map<String, AssociationResult> {
        val raw = values.mapValues { (metric, groups) ->
            compare(groups.first, groups.second, permutations, seed xor metric.hashCode())
        }
        val eligible = raw.filterValues { it.permutationP != null }.toList().sortedBy { it.second.permutationP }
        if (eligible.isEmpty()) return raw
        val adjusted = mutableMapOf<String, Double>()
        var runningMinimum = 1.0
        for (index in eligible.indices.reversed()) {
            val rank = index + 1
            val candidate = (eligible[index].second.permutationP!! * eligible.size / rank).coerceAtMost(1.0)
            runningMinimum = minOf(runningMinimum, candidate)
            adjusted[eligible[index].first] = runningMinimum
        }
        return raw.mapValues { (metric, result) ->
            val q = adjusted[metric] ?: return@mapValues result
            buildResult(
                values.getValue(metric).first, values.getValue(metric).second,
                result.delta!!, result.standardizedEffect!!, result.permutationP!!, q, result.persistentDirection
            )
        }
    }

    private fun buildResult(
        events: List<Double>, controls: List<Double>, delta: Double, effect: Double,
        p: Double, adjustedP: Double, persistent: Boolean
    ): AssociationResult {
        val strength = when {
            adjustedP > 0.10 || abs(effect) < 0.20 -> "weak association"
            events.size >= 8 && controls.size >= 8 && adjustedP <= 0.05 && abs(effect) >= 0.50 && persistent ->
                "repeatable association worth investigating"
            else -> "possible association"
        }
        val direction = if (delta >= 0) "higher" else "lower"
        val persistenceText = if (persistent) " The direction persisted across early and later captures." else ""
        val summary = "Event captures were ${format(abs(delta))} $direction than controls on average; " +
            "effect=${format(effect)}, permutation p=${format(p)}, adjusted p=${format(adjustedP)}.$persistenceText " +
            "This is an association, not evidence of causation."
        return AssociationResult(
            events.size, controls.size, events.average(), controls.average(), events.medianOrNull(), controls.medianOrNull(),
            events.madOrNull(), controls.madOrNull(), delta, effect, p, adjustedP, persistent, strength, summary
        )
    }

    private fun pooledStd(a: List<Double>, b: List<Double>): Double {
        fun variance(xs: List<Double>): Double {
            val mean = xs.average()
            return xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1).coerceAtLeast(1)
        }
        return sqrt(((a.size - 1) * variance(a) + (b.size - 1) * variance(b)) / (a.size + b.size - 2).coerceAtLeast(1))
    }

    private fun permutationP(a: List<Double>, b: List<Double>, n: Int, seed: Int): Double {
        val observed = abs(a.average() - b.average())
        val pool = (a + b).toMutableList()
        val random = Random(seed)
        var extreme = 0
        repeat(n.coerceAtLeast(1)) {
            pool.shuffle(random)
            val eventMean = pool.take(a.size).average()
            val controlMean = pool.drop(a.size).average()
            if (abs(eventMean - controlMean) >= observed) extreme++
        }
        return (extreme + 1.0) / (n.coerceAtLeast(1) + 1.0)
    }

    private fun splitHalfPersistence(events: List<Double>, controls: List<Double>, overallDelta: Double): Boolean {
        if (events.size < 8 || controls.size < 8 || overallDelta == 0.0) return false
        val eventMiddle = events.size / 2
        val controlMiddle = controls.size / 2
        val firstDelta = events.take(eventMiddle).average() - controls.take(controlMiddle).average()
        val secondDelta = events.drop(eventMiddle).average() - controls.drop(controlMiddle).average()
        return firstDelta != 0.0 && secondDelta != 0.0 && firstDelta.sign == overallDelta.sign && secondDelta.sign == overallDelta.sign
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    private fun List<Double>.medianOrNull(): Double? {
        if (isEmpty()) return null
        val sorted = sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }
    private fun List<Double>.madOrNull(): Double? {
        val median = medianOrNull() ?: return null
        return map { abs(it - median) }.medianOrNull()
    }
    private fun format(value: Double) = "%.3f".format(value)
}
