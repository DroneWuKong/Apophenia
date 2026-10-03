package com.dronewukong.apophenia.hardware

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.work.EventEnrichmentWorker
import com.dronewukong.apophenia.work.ControlSampleWorker
import kotlinx.coroutines.runBlocking
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
class SimulationModeTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After
    fun tearDown() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.LIVE)
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun simulationProducesSensorDeviceAndEnvironmentSamplesWithoutHardware() = runBlocking {
        val sensor = SensorSnapshotCollector(context).collect(7, false)
        val device = DeviceContextCollector(context).collect(7, false)
        val environment = EnvironmentProvider(context).collect(7, false)

        assertTrue(sensor.isNotEmpty())
        assertTrue(device.isNotEmpty())
        assertTrue(environment.isNotEmpty())
        assertTrue((sensor + device + environment).all { it.observationId == 7L })
        assertTrue((sensor + device + environment).all { it.source.startsWith("simulation") })
        assertEquals(HardwareGates.RuntimeMode.SIMULATION, HardwareGates.runtimeMode)
    }

    @Test
    fun simulationUsesTheProductionEnrichmentAndDatabasePipeline() {
        val db = ObservationDb(context)
        val id = db.insertObservation(
            Observation(timestampMs = 1234L, kind = ObservationKind.OBSERVATION, label = "Simulation event")
        )
        val worker = TestListenableWorkerBuilder<EventEnrichmentWorker>(context)
            .setInputData(Data.Builder().putLong(EventEnrichmentWorker.KEY_OBSERVATION_ID, id).build())
            .build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        val samples = db.contextForObservation(id)
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.source.startsWith("simulation") })
        assertTrue(samples.any { it.metric == "health_heart_rate_avg_bpm" })
        db.close()
    }

    @Test
    fun promptedNeutralCheckInUsesTheControlPipeline() {
        val capturedAt = 1_780_000_000_000L
        val worker = TestListenableWorkerBuilder<ControlSampleWorker>(context)
            .setInputData(
                Data.Builder()
                    .putString(ControlSampleWorker.KEY_CONTROL_SOURCE, ControlSampleWorker.SOURCE_PROMPTED)
                    .putLong(ControlSampleWorker.KEY_CAPTURED_AT, capturedAt)
                    .build()
            )
            .build()

        assertEquals(androidx.work.ListenableWorker.Result.success(), worker.doWork())
        val controls = ObservationDb(context).allContext().filter { it.isControl }
        assertTrue(controls.isNotEmpty())
        assertTrue(controls.all { it.captureId.startsWith("prompted-control:") })
        assertTrue(controls.all { it.metadata.contains("control_source=prompted") })
    }
}
