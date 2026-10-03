package com.dronewukong.apophenia.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.HardwareGates
import java.util.concurrent.atomic.AtomicBoolean
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
class AutomotiveContextProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @After
    fun tearDown() = HardwareGates.clearAuthorizationsForTests(context)

    @Test
    fun gateOffDoesNotTouchAutomotiveApi() {
        val called = AtomicBoolean(false)
        val source = AutomotivePropertySource {
            called.set(true)
            AutomotiveReadResult(emptyList(), HardwareGates.CapabilityState.AVAILABLE, "unexpected")
        }

        assertTrue(AutomotiveContextProvider(context, source).collect(1, false).isEmpty())
        assertFalse(called.get())
    }

    @Test
    fun exposedPropertiesKeepPropertyAndAreaProvenance() {
        authorize()
        val source = AutomotivePropertySource {
            AutomotiveReadResult(
                listOf(
                    AutomotivePropertyReading("automotive_cabin_temp_c", 22.25, "C", "HVAC_TEMPERATURE_CURRENT", 7),
                    AutomotivePropertyReading("automotive_speed_mps", 12.5, "m/s", "PERF_VEHICLE_SPEED")
                ),
                HardwareGates.CapabilityState.AVAILABLE,
                "2_properties"
            )
        }

        val rows = AutomotiveContextProvider(context, source).collect(8, false)

        assertEquals(2, rows.size)
        assertTrue(rows.all { it.captureId == "event:8:instant" })
        assertTrue(rows.single { it.metric == "automotive_cabin_temp_c" }.metadata.contains("area_id=7"))
        assertTrue(rows.all { it.source == "android_automotive" })
    }

    @Test
    fun permissionOrProjectionGapProducesNoInventedRows() {
        authorize()
        val denied = AutomotivePropertySource {
            AutomotiveReadResult(emptyList(), HardwareGates.CapabilityState.PERMISSION_DENIED, "permission_denied")
        }
        assertTrue(AutomotiveContextProvider(context, denied).collect(8, false).isEmpty())
    }

    @Test
    fun simulationExercisesCompleteMetricShapeWithoutPlatformClasses() {
        authorize()
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)

        val rows = AutomotiveContextProvider(context).collect(null, true)

        assertTrue(rows.map { it.metric }.containsAll(listOf(
            "automotive_cabin_temp_c",
            "automotive_outside_temp_c",
            "automotive_speed_mps",
            "automotive_gear_code",
            "automotive_fuel_level_ml",
            "automotive_ev_battery_level_wh",
            "automotive_odometer_km"
        )))
        assertTrue(rows.all { it.source == "simulation/automotive" })
    }

    private fun authorize() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE,
            true,
            HardwareGates.ConsentProof.SingleConfirmation
        )
    }
}
