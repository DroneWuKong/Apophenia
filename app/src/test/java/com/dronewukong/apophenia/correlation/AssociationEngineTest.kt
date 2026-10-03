package com.dronewukong.apophenia.correlation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationEngineTest {
    @Test
    fun identicalGroupsReportNegligibleEffectAndInsufficientEvidence() {
        val result = AssociationEngine.compare(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0),
            listOf(1.0, 2.0, 3.0, 4.0, 5.0),
            seed = 42
        )
        assertEquals("indistinguishable from noise", result.evidence)
        assertEquals("negligible effect", result.effectMagnitude)
        assertTrue(kotlin.math.abs(result.standardizedEffect ?: 99.0) < 0.01)
        assertTrue(result.summary.contains("not evidence of causation"))
    }

    @Test
    fun separatedGroupsProduceLargePersistentEffect() {
        val result = AssociationEngine.compare(
            listOf(10.0, 11.0, 12.0, 13.0, 10.5, 11.5, 12.5, 13.5),
            listOf(1.0, 2.0, 3.0, 4.0, 1.5, 2.5, 3.5, 4.5),
            permutations = 2_000,
            seed = 42
        )
        assertTrue((result.standardizedEffect ?: 0.0) > 1.0)
        assertTrue(result.persistentDirection)
        assertNotNull(result.eventMedian)
        assertNotNull(result.eventMad)
        assertNotNull(result.effectCiLow)
        assertNotNull(result.effectCiHigh)
    }

    @Test
    fun smallSamplesAreNotOverclaimed() {
        val result = AssociationEngine.compare(listOf(10.0, 11.0), listOf(1.0, 2.0))
        assertEquals("insufficient data", result.strength)
        assertEquals(null, result.permutationP)
    }

    @Test
    fun multipleComparisonsReceiveAdjustedPValues() {
        val results = AssociationEngine.compareAll(
            mapOf(
                "pressure" to (List(10) { 10.0 + it } to List(10) { it.toDouble() }),
                "light" to (List(10) { it.toDouble() } to List(10) { it.toDouble() })
            ),
            permutations = 1_000,
            seed = 42
        )
        assertTrue(results.values.all { it.adjustedP != null })
        assertTrue(results.getValue("pressure").adjustedP!! >= results.getValue("pressure").permutationP!!)
        assertTrue(results.values.all { it.comparisonsTested == 2 && it.comparisonsEligible == 2 })
        assertTrue(results.values.all { it.summary.contains("2 features tested") })
        assertTrue(results.getValue("light").indistinguishableFromNoise)
    }

    @Test
    fun recordsSeedResolutionAndSeparatesEffectFromEvidence() {
        val result = AssociationEngine.compare(
            listOf(1.0, 1.0, 1.0, 8.0),
            listOf(0.0, 0.0, 0.0, 0.0),
            permutations = 199,
            seed = 8675309,
            bootstrapIterations = 250
        )

        assertEquals(8675309, result.permutationSeed)
        assertEquals(0.005, result.pResolution!!, 0.0000001)
        assertTrue(result.effectMagnitude.endsWith("effect"))
        assertTrue(result.evidence.isNotBlank())
        assertTrue(result.summary.contains("seed=8675309"))
        assertTrue(result.summary.contains("95% bootstrap CI"))
    }
}
