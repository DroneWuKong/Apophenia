package com.dronewukong.apophenia.correlation

import com.dronewukong.apophenia.data.Hypothesis
import com.dronewukong.apophenia.data.HypothesisDirection
import com.dronewukong.apophenia.data.HypothesisEvaluation
import com.dronewukong.apophenia.data.HypothesisOutcome
import com.dronewukong.apophenia.data.HypothesisWindow
import java.security.MessageDigest

object HypothesisEvaluator {
    fun resultKey(hypothesis: Hypothesis): String {
        val window = HypothesisWindow.fromBounds(hypothesis.windowStartMs, hypothesis.windowEndMs)
        return if (window == HypothesisWindow.INSTANT) hypothesis.metric else {
            val index = HypothesisWindow.entries.indexOf(window) - 1
            "${hypothesis.metric} · ${index * 10}-${(index + 1) * 10}m pre"
        }
    }

    fun evaluate(hypothesis: Hypothesis, result: AssociationResult, evaluatedAtMs: Long = System.currentTimeMillis()): HypothesisEvaluation? {
        val adjustedP = result.adjustedP ?: return null
        val delta = result.delta ?: return null
        val supported = adjustedP <= 0.05
        val expectedDirection = when (hypothesis.direction) {
            HypothesisDirection.HIGHER -> delta > 0
            HypothesisDirection.LOWER -> delta < 0
            HypothesisDirection.ANY -> delta != 0.0
        }
        val outcome = when {
            supported && expectedDirection -> HypothesisOutcome.CONFIRMED
            supported && !expectedDirection -> HypothesisOutcome.REFUTED
            else -> HypothesisOutcome.NOT_YET_SUPPORTED
        }
        val prefix = when (outcome) {
            HypothesisOutcome.CONFIRMED -> "Confirmed against this registered direction and window."
            HypothesisOutcome.REFUTED -> "Refuted: the corrected result points against the registered direction."
            HypothesisOutcome.NOT_YET_SUPPORTED -> "Not yet supported after multiple-comparisons correction."
        }
        val signature = analysisSignature(hypothesis.id, result)
        return HypothesisEvaluation(
            hypothesisId = hypothesis.id,
            evaluatedAtMs = evaluatedAtMs,
            analysisSignature = signature,
            outcome = outcome,
            eventCount = result.eventCount,
            controlCount = result.controlCount,
            adjustedP = adjustedP,
            delta = delta,
            comparisonsTested = result.comparisonsTested,
            summary = "$prefix Registered ${hypothesis.direction.name.lowercase()} for ${HypothesisWindow.fromBounds(hypothesis.windowStartMs, hypothesis.windowEndMs).displayName.lowercase()}; adjusted p=${"%.4f".format(adjustedP)}, delta=${"%.4f".format(delta)}. Association only; not causation."
        )
    }

    fun analysisSignature(registrationId: Long, result: AssociationResult): String {
        val signatureSource = listOf(
            registrationId, result.eventCount, result.controlCount, result.permutationSeed,
            result.permutationCount, result.adjustedP, result.delta, result.comparisonsTested
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(signatureSource.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
