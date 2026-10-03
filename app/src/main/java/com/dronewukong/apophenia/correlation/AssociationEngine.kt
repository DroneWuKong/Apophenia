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
    val effectCiLow: Double?,
    val effectCiHigh: Double?,
    val permutationP: Double?,
    val adjustedP: Double?,
    val permutationSeed: Int?,
    val permutationCount: Int,
    val pResolution: Double?,
    val persistentDirection: Boolean,
    val effectMagnitude: String,
    val evidence: String,
    val strength: String,
    val summary: String
)

object AssociationEngine {
    fun compare(
        eventValues: List<Double>,
        controlValues: List<Double>,
        permutations: Int = 1_000,
        seed: Int? = null,
        bootstrapIterations: Int = 1_000
    ): AssociationResult {
        val permutationCount = permutations.coerceAtLeast(1)
        if (eventValues.size < 4 || controlValues.size < 4) return insufficient(eventValues, controlValues, permutationCount)

        val resolvedSeed = seed ?: Random.Default.nextInt()
        val eventMean = eventValues.average()
        val controlMean = controlValues.average()
        val delta = eventMean - controlMean
        val effect = standardizedEffect(eventValues, controlValues)
        val p = permutationP(eventValues, controlValues, permutationCount, resolvedSeed)
        val ci = bootstrapEffectCi(eventValues, controlValues, bootstrapIterations, resolvedSeed xor 0x5F3759DF)
        val persistent = splitHalfPersistence(eventValues, controlValues, delta)
        return buildResult(
            eventValues, controlValues, delta, effect, ci.first, ci.second, p, p,
            resolvedSeed, permutationCount, persistent
        )
    }

    fun compareAll(
        values: Map<String, Pair<List<Double>, List<Double>>>,
        permutations: Int = 1_000,
        seed: Int? = null,
        bootstrapIterations: Int = 1_000
    ): Map<String, AssociationResult> {
        val rootSeed = seed ?: Random.Default.nextInt()
        val raw = values.mapValues { (metric, groups) ->
            compare(groups.first, groups.second, permutations, rootSeed xor metric.hashCode(), bootstrapIterations)
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
                values.getValue(metric).first,
                values.getValue(metric).second,
                result.delta!!,
                result.standardizedEffect!!,
                result.effectCiLow!!,
                result.effectCiHigh!!,
                result.permutationP!!,
                q,
                result.permutationSeed!!,
                result.permutationCount,
                result.persistentDirection
            )
        }
    }

    private fun insufficient(events: List<Double>, controls: List<Double>, permutations: Int) = AssociationResult(
        eventCount = events.size,
        controlCount = controls.size,
        eventMean = events.averageOrNull(),
        controlMean = controls.averageOrNull(),
        eventMedian = events.medianOrNull(),
        controlMedian = controls.medianOrNull(),
        eventMad = events.madOrNull(),
        controlMad = controls.madOrNull(),
        delta = null,
        standardizedEffect = null,
        effectCiLow = null,
        effectCiHigh = null,
        permutationP = null,
        adjustedP = null,
        permutationSeed = null,
        permutationCount = permutations,
        pResolution = null,
        persistentDirection = false,
        effectMagnitude = "not estimated",
        evidence = "insufficient data",
        strength = "insufficient data",
        summary = "Need at least 4 matched event captures and 4 matched control captures."
    )

    private fun buildResult(
        events: List<Double>,
        controls: List<Double>,
        delta: Double,
        effect: Double,
        ciLow: Double,
        ciHigh: Double,
        p: Double,
        adjustedP: Double,
        seed: Int,
        permutations: Int,
        persistent: Boolean
    ): AssociationResult {
        val magnitude = effectMagnitude(effect)
        val evidence = when {
            adjustedP > 0.10 -> "not enough evidence"
            events.size >= 10 && controls.size >= 10 && adjustedP <= 0.05 && abs(effect) >= 0.50 && persistent ->
                "repeatable association worth investigating"
            else -> "possible association"
        }
        val direction = if (delta >= 0) "higher" else "lower"
        val persistenceText = if (persistent) " The direction persisted across early and later captures." else ""
        val sampleText = if (events.size < 10 || controls.size < 10) " Small sample: uncertainty remains high." else ""
        val resolution = 1.0 / (permutations + 1.0)
        val summary = "Event captures were ${format(abs(delta))} $direction than matched controls on average. " +
            "Effect estimate: $magnitude (d=${format(effect)}, 95% bootstrap CI ${formatInterval(ciLow, ciHigh)}). " +
            "Permutation p=${format(p)}, FDR-adjusted p=${format(adjustedP)}, resolution=${format(resolution)}, seed=$seed.$persistenceText$sampleText " +
            "This is an association, not evidence of causation."
        return AssociationResult(
            events.size,
            controls.size,
            events.average(),
            controls.average(),
            events.medianOrNull(),
            controls.medianOrNull(),
            events.madOrNull(),
            controls.madOrNull(),
            delta,
            effect,
            ciLow,
            ciHigh,
            p,
            adjustedP,
            seed,
            permutations,
            resolution,
            persistent,
            magnitude,
            evidence,
            evidence,
            summary
        )
    }

    private fun effectMagnitude(effect: Double): String = when {
        abs(effect) < 0.20 -> "negligible effect"
        abs(effect) < 0.50 -> "small effect"
        abs(effect) < 0.80 -> "moderate effect"
        else -> "large effect"
    }

    private fun standardizedEffect(a: List<Double>, b: List<Double>): Double {
        val pooled = pooledStd(a, b)
        return if (pooled > 1e-12) (a.average() - b.average()) / pooled else 0.0
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
        repeat(n) {
            pool.shuffle(random)
            val eventMean = pool.take(a.size).average()
            val controlMean = pool.drop(a.size).average()
            if (abs(eventMean - controlMean) >= observed) extreme++
        }
        return (extreme + 1.0) / (n + 1.0)
    }

    private fun bootstrapEffectCi(a: List<Double>, b: List<Double>, iterations: Int, seed: Int): Pair<Double, Double> {
        val random = Random(seed)
        val estimates = DoubleArray(iterations.coerceAtLeast(200)) {
            val sampledA = List(a.size) { a[random.nextInt(a.size)] }
            val sampledB = List(b.size) { b[random.nextInt(b.size)] }
            standardizedEffect(sampledA, sampledB)
        }.sorted()
        return percentile(estimates, 0.025) to percentile(estimates, 0.975)
    }

    private fun percentile(sorted: List<Double>, probability: Double): Double {
        val position = probability * (sorted.size - 1)
        val lower = position.toInt()
        val upper = kotlin.math.ceil(position).toInt()
        if (lower == upper) return sorted[lower]
        val fraction = position - lower
        return sorted[lower] * (1.0 - fraction) + sorted[upper] * fraction
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

    private fun format(value: Double) = "%.4f".format(value)
    private fun formatInterval(low: Double, high: Double) = "[${format(low)}, ${format(high)}]"
}
