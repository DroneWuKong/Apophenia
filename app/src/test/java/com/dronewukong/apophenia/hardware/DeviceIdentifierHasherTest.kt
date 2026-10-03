package com.dronewukong.apophenia.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentifierHasherTest {
    private val rawMac = "aa:bb:cc:dd:ee:ff"

    @Test
    fun samePhysicalIdentifierIsStableAcrossCapturesAndFormatting() {
        val key = ByteArray(32) { it.toByte() }
        val hasher = DeviceIdentifierHasher.withFixedKey(key)

        val first = hasher.hash(DeviceIdentifierKind.MAC_ADDRESS, rawMac)
        val second = hasher.hash(DeviceIdentifierKind.MAC_ADDRESS, "AA-BB-CC-DD-EE-FF")
        val afterRestart = DeviceIdentifierHasher.withFixedKey(key)
            .hash(DeviceIdentifierKind.MAC_ADDRESS, rawMac)

        assertEquals(first, second)
        assertEquals(first, afterRestart)
        assertTrue(first.startsWith("idhash:v1:"))
        assertFalse(first.contains("AA", ignoreCase = true))
        assertFalse(first.contains(rawMac, ignoreCase = true))
    }

    @Test
    fun namespacePreventsCrossChannelIdentifierCollisions() {
        val hasher = DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it + 7).toByte() })

        assertNotEquals(
            hasher.hash(DeviceIdentifierKind.MAC_ADDRESS, rawMac),
            hasher.hash(DeviceIdentifierKind.WIFI_BSSID, rawMac)
        )
    }

    @Test
    fun saltRotationInvalidatesPriorHashesAndAdvancesGeneration() {
        val hasher = DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it + 11).toByte() })
        val before = hasher.hash(DeviceIdentifierKind.ADAPTER_SYSID, "Airframe-12")

        assertEquals(2, hasher.rotateSalt())
        val after = hasher.hash(DeviceIdentifierKind.ADAPTER_SYSID, "Airframe-12")

        assertNotEquals(before, after)
        assertTrue(after.startsWith("idhash:v2:"))
    }
}
