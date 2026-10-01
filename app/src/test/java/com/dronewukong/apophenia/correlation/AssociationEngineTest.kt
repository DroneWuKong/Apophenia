package com.dronewukong.apophenia.correlation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class AssociationEngineTest {
 @Test fun identicalGroupsAreWeak(){val r=AssociationEngine.compare(listOf(1.0,2.0,3.0,4.0,5.0),listOf(1.0,2.0,3.0,4.0,5.0));assertEquals("WEAK",r.strength);assertTrue(kotlin.math.abs(r.standardizedEffect?:99.0)<0.01)}
 @Test fun separatedGroupsProduceLargeEffect(){val r=AssociationEngine.compare(listOf(10.0,11.0,12.0,13.0,14.0),listOf(1.0,2.0,3.0,4.0,5.0));assertTrue((r.standardizedEffect?:0.0)>1.0)}
 @Test fun smallSamplesAreNotOverclaimed(){val r=AssociationEngine.compare(listOf(10.0,11.0),listOf(1.0,2.0));assertEquals("INSUFFICIENT",r.strength)}
}
