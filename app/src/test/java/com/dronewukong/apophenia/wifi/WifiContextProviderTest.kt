package com.dronewukong.apophenia.wifi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WifiContextProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After
    fun tearDown() = HardwareGates.clearAuthorizationsForTests(context)

    @Test
    fun gateOffProducesNoRows() {
        assertTrue(WifiContextProvider(context).collect(4, false).isEmpty())
    }

    @Test
    fun authorizedSimulationProducesHashedRowsAndAggregates() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_WIFI_CAPTURE,
            enabled = true,
            proof = HardwareGates.ConsentProof.SingleConfirmation
        )

        val samples = WifiContextProvider(context).collect(4, false)

        assertEquals(3.0, samples.single { it.metric == "wifi_visible_count" }.value, 0.0)
        assertEquals(-44.0, samples.single { it.metric == "wifi_rssi_max" }.value, 0.0)
        assertEquals(setOf("2.4GHz", "5GHz", "6GHz"), samples
            .filter { it.metric == "wifi_ap_rssi_dbm" }
            .map { Regex("band=([^;]+)").find(it.metadata)!!.groupValues[1] }
            .toSet())
        assertTrue(samples.all { it.captureId == "event:4:instant" })
    }

    @Test
    fun encoderNeverPersistsRawBssid() {
        val bssid = "AA:BB:CC:DD:EE:FF"
        val samples = WifiSnapshotEncoder.samples(
            listOf(WifiAccessPointSnapshot(bssid, -60, 5_180)),
            DeviceIdentifierHasher.withFixedKey(ByteArray(32) { it.toByte() }),
            timestampMs = 1,
            observationId = 2,
            isControl = false
        )

        val serialized = samples.joinToString("|") { it.metadata }
        assertFalse(serialized.contains(bssid, ignoreCase = true))
        assertTrue(serialized.contains("bssid_hash=idhash:v1:"))
    }
}
