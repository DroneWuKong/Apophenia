package com.dronewukong.apophenia.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AuxiliaryPresenceProviderTest {
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
    fun auxiliaryChannelsAreOffByDefault() {
        assertTrue(AuxiliaryPresenceProvider(context).collect(2, false).isEmpty())
    }

    @Test
    fun explicitlyEnabledChannelsProduceBoundedSnapshots() {
        listOf(
            HardwareGates.Gate.LIVE_WIFI_P2P_CAPTURE,
            HardwareGates.Gate.LIVE_NFC_CAPTURE
        ).forEach { gate ->
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.SingleConfirmation
            )
        }

        val samples = AuxiliaryPresenceProvider(context).collect(2, false)

        assertEquals(5, samples.size)
        assertTrue(samples.all { it.captureId == "event:2:instant" })
        assertTrue(samples.any { it.metric == "nfc_adapter_enabled" })
        assertTrue(samples.any { it.metric == "wifi_p2p_group_formed" })
    }
}
