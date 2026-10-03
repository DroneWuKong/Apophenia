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
class PhoneMetadataProviderTest {
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
    fun gateOffProducesNoMetadataRows() {
        assertTrue(PhoneMetadataProvider(context).collect(9, false).isEmpty())
    }

    @Test
    fun everyAuthorizedMetadataGateUsesEventCaptureIdentity() {
        val gates = listOf(
            HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE,
            HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE,
            HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE,
            HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE
        )
        gates.forEach { gate ->
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.SingleConfirmation
            )
        }

        val samples = PhoneMetadataProvider(context).collect(9, false)

        assertTrue(samples.map { it.metric }.containsAll(listOf(
            "audio_output_device",
            "screen_interactive",
            "battery_level_pct",
            "solar_elevation_deg"
        )))
        assertTrue(samples.all { it.captureId == "event:9:instant" })
        assertTrue(samples.all { it.source.startsWith("simulation/") })
    }

    @Test
    fun solarElevationIsBoundedAndPlausibleAtEquatorialNoon() {
        val instant = java.time.Instant.parse("2026-03-20T12:00:00Z").toEpochMilli()
        val elevation = SolarPhaseCalculator.elevationDegrees(instant, 0.0, 0.0)
        assertTrue(elevation in 85.0..90.0)
        assertEquals(elevation, elevation.coerceIn(-90.0, 90.0), 0.0)
    }
}
