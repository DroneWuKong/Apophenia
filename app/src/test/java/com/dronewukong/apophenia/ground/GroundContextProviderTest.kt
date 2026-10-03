package com.dronewukong.apophenia.ground

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GroundContextProviderTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @After fun tearDown() = HardwareGates.clearAuthorizationsForTests(context)

    @Test fun normalizesPressureAndComputesBoundedTrend() {
        assertEquals(1_013.25, GroundContextMath.pressureHpa(101_325.0), 0.0001)
        assertEquals(1_013.25, GroundContextMath.pressureHpa(1_013.25), 0.0001)
        assertEquals(-1.5, GroundContextMath.trendHpaPerHour(1_013.0, 1_000L, 1_011.5, 3_601_000L)!!, 0.0001)
        assertEquals(null, GroundContextMath.trendHpaPerHour(1_013.0, 1_000L, 1_011.5, 25L * 3_600_000L))
    }

    @Test fun parsesOfficialSwpcShapesWithObservationTimestamps() {
        val snapshot = SpaceWeatherSnapshotParser.parse(
            """[{"time_tag":"2026-10-03T09:00:00","Kp":2.0}]""",
            """[{"flux":92,"time_tag":"2026-10-02T20:00:00"}]"""
        )
        assertNotNull(snapshot)
        assertEquals(2.0, snapshot!!.kp, 0.0)
        assertEquals(92.0, snapshot.solarFluxSfu, 0.0)
        assertTrue(snapshot.kpObservedAtMs > snapshot.solarFluxObservedAtMs)
    }

    @Test fun gateOffBypassesAndSimulationExercisesCompleteGroundPath() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        assertTrue(GroundContextProvider(context).collect(9, false).isEmpty())
        HardwareGates.setAuthorized(
            context, HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE, true,
            HardwareGates.ConsentProof.SingleConfirmation
        )
        val rows = GroundContextProvider(context).collect(9, false)
        assertTrue(rows.any { it.metric == "ground_pressure_hpa" })
        assertTrue(rows.any { it.metric == "ground_magnetic_declination_deg" })
        assertTrue(rows.any { it.metric == "space_weather_kp" })
        assertTrue(rows.all { it.captureId.contains("event:9:ground") })
    }
}
