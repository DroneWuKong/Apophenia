package com.dronewukong.apophenia.correlation

import com.dronewukong.apophenia.data.AnalysisCohort
import com.dronewukong.apophenia.data.Hypothesis
import com.dronewukong.apophenia.data.HypothesisDirection
import com.dronewukong.apophenia.data.HypothesisOutcome
import com.dronewukong.apophenia.data.HypothesisWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HypothesisEvaluatorTest {
    private val strongHigher = AssociationEngine.compare(
        List(12) { 20.0 + it }, List(12) { it.toDouble() }, permutations = 2_000, seed = 42
    )

    @Test fun confirmedAndRefutedFollowTheRegisteredDirection() {
        val higher = hypothesis(HypothesisDirection.HIGHER)
        val lower = hypothesis(HypothesisDirection.LOWER)

        assertEquals(HypothesisOutcome.CONFIRMED, HypothesisEvaluator.evaluate(higher, strongHigher, 5_000)?.outcome)
        assertEquals(HypothesisOutcome.REFUTED, HypothesisEvaluator.evaluate(lower, strongHigher, 5_000)?.outcome)
        assertTrue(HypothesisEvaluator.evaluate(lower, strongHigher, 5_000)!!.summary.startsWith("Refuted"))
    }

    @Test fun correctedWeakResultIsNotYetSupportedAndInsufficientDataDoesNotLock() {
        val weak = AssociationEngine.compare(listOf(1.0,2.0,3.0,4.0,5.0), listOf(1.0,2.0,3.0,4.0,5.0), seed = 42)
        val insufficient = AssociationEngine.compare(listOf(1.0,2.0), listOf(1.0,2.0), seed = 42)

        assertEquals(HypothesisOutcome.NOT_YET_SUPPORTED, HypothesisEvaluator.evaluate(hypothesis(HypothesisDirection.ANY), weak)?.outcome)
        assertNull(HypothesisEvaluator.evaluate(hypothesis(HypothesisDirection.ANY), insufficient))
    }

    @Test fun registrationWindowMapsToTheExistingLagFeatureKey() {
        val registration = hypothesis(HypothesisDirection.HIGHER).copy(
            windowStartMs = HypothesisWindow.PRE_10_20_MIN.fromBeforeMs,
            windowEndMs = HypothesisWindow.PRE_10_20_MIN.toBeforeMs
        )
        assertEquals("pressure_hpa · 10-20m pre", HypothesisEvaluator.resultKey(registration))
    }

    private fun hypothesis(direction: HypothesisDirection) = Hypothesis(
        id = 7, createdAtMs = 1_000, eventLabel = "Egress · bailed", cohortId = AnalysisCohort.EGRESS,
        metric = "pressure_hpa", direction = direction, note = "Registered before results"
    )
}
