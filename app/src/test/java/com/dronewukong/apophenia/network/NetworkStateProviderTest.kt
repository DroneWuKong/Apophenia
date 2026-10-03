package com.dronewukong.apophenia.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class NetworkStateProviderTest {
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
        assertTrue(NetworkStateProvider(context).collect(3, false).isEmpty())
    }

    @Test
    fun simulationUsesTheProductionMetricContract() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE,
            enabled = true,
            proof = HardwareGates.ConsentProof.SingleConfirmation
        )

        val samples = NetworkStateProvider(context).collect(3, false)

        assertEquals(1.0, samples.single { it.metric == "network_connected" }.value, 0.0)
        assertEquals(-91.0, samples.single { it.metric == "network_signal_dbm" }.value, 0.0)
        assertTrue(samples.all { it.captureId == "event:3:instant" })
        assertTrue(samples.all { it.metadata.contains("network_type=5g_nr") })
    }

    @Test
    fun metadataDelimitersAreSanitized() {
        val samples = NetworkStateEncoder.samples(
            NetworkStateSnapshot(true, true, false, "wifi;vpn", "Carrier=Name", "lte", false, null),
            timestampMs = 1,
            observationId = null,
            isControl = true
        )
        val metadata = samples.first().metadata

        assertFalse(metadata.contains("transport=wifi;vpn"))
        assertFalse(metadata.contains("carrier=Carrier=Name"))
        assertTrue(metadata.contains("transport=wifi_vpn"))
        assertTrue(metadata.contains("carrier=Carrier_Name"))
    }
}
