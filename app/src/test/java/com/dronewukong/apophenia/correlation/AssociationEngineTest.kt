package com.dronewukong.apophenia.correlation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssociationEngineTest {
    @Test
    fun identicalGroupsAreWeak() {
        val result = AssociationEngine.compare(
            listOf(1.0, 2.0, 3.0, 4.0, 5.0),
            listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        )
        assertEquals("weak association", result.strength)
        assertTrue(kotlin.math.abs(result.standardizedEffect ?: 99.0) < 0.01)
        assertTrue(result.summary.contains("not evidence of causation"))
    }

    @Test
    fun separatedGroupsProduceLargePersistentEffect() {
        val result = AssociationEngine.compare(
            listOf(10.0, 11.0, 12.0, 13.0, 10.5, 11.5, 12.5, 13.5),
            listOf(1.0, 2.0, 3.0, 4.0, 1.5, 2.5, 3.5, 4.5),
            permutations = 2_000
        )
        assertTrue((result.standardizedEffect ?: 0.0) > 1.0)
        assertTrue(result.persistentDirection)
        assertNotNull(result.eventMedian)
        assertNotNull(result.eventMad)
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
            permutations = 1_000
        )
        assertTrue(results.values.all { it.adjustedP != null })
        assertTrue(results.getValue("pressure").adjustedP!! >= results.getValue("pressure").permutationP!!)
    }
}
