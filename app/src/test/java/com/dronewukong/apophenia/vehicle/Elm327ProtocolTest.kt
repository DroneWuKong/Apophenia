package com.dronewukong.apophenia.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.CaptureSessionStatus
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationStore
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
class Elm327ProtocolTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @After
    fun tearDown() {
        DriveSessionManager.stop(context, interrupted = true, reason = "test_teardown")
        HardwareGates.clearAuthorizationsForTests(context)
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun parserHandlesPackedSpacedAndCanHeaderResponses() {
        assertEquals(listOf(0x1A, 0xF8), Elm327Parser.pidData("010C\r410C1AF8\r>", 0x0C))
        assertEquals(listOf(0x37), Elm327Parser.pidData("41 0D 37>", 0x0D))
        assertEquals(listOf(0x80), Elm327Parser.pidData("7E8 03 41 04 80>", 0x04))
        assertEquals(13.9, Elm327Parser.voltage("13.9V>")!!, 0.001)
        assertEquals(listOf("P0133", "U0100"), Elm327Parser.dtcs("43 01 33 C1 00 00 00>", 0x43))
        assertTrue(Elm327Parser.pidData("NO DATA>", 0x70) == null)
    }

    @Test
    fun clientDecodesStandardPidsAndKeepsCoverageHonest() {
        val client = Elm327Client(FakeTransport())
        client.initialize()

        val snapshot = client.readSnapshot()

        assertEquals(1726.0, snapshot.values.getValue("obd_engine_rpm").value, 0.001)
        assertEquals(55.0, snapshot.values.getValue("obd_vehicle_speed_kph").value, 0.001)
        assertEquals(83.0, snapshot.values.getValue("obd_coolant_temp_c").value, 0.001)
        assertEquals(13.999, snapshot.values.getValue("obd_control_module_voltage_v").value, 0.001)
        assertTrue("0170" in snapshot.unsupportedCommands)
        assertEquals(listOf("P0133"), snapshot.storedDtcs)
        assertTrue(snapshot.pendingDtcs.isEmpty())
    }

    @Test
    fun simulatedDriveSessionHashesAdapterAndGroupsSamples() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_VEHICLE_CAPTURE,
            true,
            HardwareGates.ConsentProof.SingleConfirmation
        )
        val adapter = DriveSessionManager.pairedAdapters(context).single()

        val started = DriveSessionManager.start(context, adapter).getOrThrow()
        val db = ObservationStore.repository(context).db()
        val observationId = db.insertObservation(
            Observation(timestampMs = System.currentTimeMillis(), kind = ObservationKind.OBSERVATION, label = "Drive")
        )
        val samples = DriveSessionManager.collect(context, observationId = observationId, isControl = false)
        db.insertContext(samples)

        assertTrue(started.active)
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.sessionId == started.sessionId })
        assertTrue(samples.any { it.metric == "obd_engine_rpm" })
        val stored = db.session(started.sessionId!!)!!
        assertTrue(stored.identityHash.startsWith("idhash:v1:"))
        assertFalse(stored.identityHash.contains(adapter.address))

        DriveSessionManager.stop(context)
        assertEquals(CaptureSessionStatus.COMPLETED, db.session(started.sessionId)!!.status)
    }

    @Test
    fun vehicleGateOffPreventsSessionAndRows() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        val adapter = DriveSessionManager.pairedAdapters(context).single()
        assertTrue(DriveSessionManager.start(context, adapter).isFailure)
        assertTrue(DriveSessionManager.collect(context, 1, false).isEmpty())
    }

    private class FakeTransport : ObdTransport {
        override fun connect(address: String) = Unit
        override fun command(command: String): String = when (command) {
            "010C" -> "41 0C 1A F8>"
            "010D" -> "41 0D 37>"
            "0104" -> "41 04 80>"
            "0105" -> "41 05 7B>"
            "010F" -> "41 0F 54>"
            "0146" -> "41 46 52>"
            "0170" -> "NO DATA>"
            "0111" -> "41 11 40>"
            "012F" -> "41 2F B3>"
            "0106" -> "41 06 82>"
            "0107" -> "41 07 7C>"
            "0142" -> "41 42 36 AF>"
            "03" -> "43 01 33 00 00>"
            "07" -> "47 00 00>"
            else -> "OK>"
        }
        override fun close() = Unit
    }
}
