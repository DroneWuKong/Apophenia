package com.dronewukong.apophenia.correlation

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

data class AssociationResult(
    val eventCount: Int,
    val controlCount: Int,
    val eventMean: Double?,
    val controlMean: Double?,
    val delta: Double?,
    val standardizedEffect: Double?,
    val permutationP: Double?,
    val strength: String,
    val summary: String
)

object AssociationEngine {
    fun compare(eventValues: List<Double>, controlValues: List<Double>, permutations: Int = 600, seed: Int = 1337): AssociationResult {
        if (eventValues.size < 4 || controlValues.size < 4) return AssociationResult(
            eventValues.size, controlValues.size, eventValues.averageOrNull(), controlValues.averageOrNull(), null, null, null,
            "INSUFFICIENT", "Need at least 4 event samples and 4 control samples."
        )
        val em = eventValues.average(); val cm = controlValues.average(); val delta = em - cm
        val pooled = pooledStd(eventValues, controlValues)
        val effect = if (pooled > 1e-12) delta / pooled else 0.0
        val p = permutationP(eventValues, controlValues, permutations, seed)
        val strength = when {
            p > 0.10 || abs(effect) < 0.20 -> "WEAK"
            abs(effect) < 0.50 -> "SMALL"
            abs(effect) < 0.80 -> "MODERATE"
            else -> "STRONG"
        }
        val direction = if (delta >= 0) "higher" else "lower"
        val summary = "Event values were ${format(abs(delta))} $direction than controls on average; effect=${format(effect)}, permutation p=${format(p)}."
        return AssociationResult(eventValues.size, controlValues.size, em, cm, delta, effect, p, strength, summary)
    }

    private fun pooledStd(a: List<Double>, b: List<Double>): Double {
        fun variance(xs: List<Double>): Double { val m=xs.average(); return xs.sumOf { (it-m)*(it-m) }/(xs.size-1).coerceAtLeast(1) }
        return sqrt(((a.size-1)*variance(a)+(b.size-1)*variance(b))/(a.size+b.size-2).coerceAtLeast(1))
    }

    private fun permutationP(a: List<Double>, b: List<Double>, n: Int, seed: Int): Double {
        val observed = abs(a.average() - b.average())
        val pool = (a+b).toMutableList(); val r = Random(seed); var extreme=0
        repeat(n) {
            pool.shuffle(r)
            val am = pool.take(a.size).average(); val bm = pool.drop(a.size).average()
            if (abs(am-bm) >= observed) extreme++
        }
        return (extreme + 1.0)/(n + 1.0)
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
    private fun format(v: Double) = "%.3f".format(v)
}
