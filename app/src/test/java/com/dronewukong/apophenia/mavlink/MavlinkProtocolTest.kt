package com.dronewukong.apophenia.mavlink

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.CaptureSessionStatus
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.hardware.HardwareGates
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
class MavlinkProtocolTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
        HardwareGates.clearAuthorizationsForTests(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After
    fun tearDown() {
        MavlinkSessionManager.stop(context, interrupted = true, reason = "test_teardown")
        HardwareGates.clearAuthorizationsForTests(context)
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun parserResynchronizesValidatesCrcAndHandlesSplitV1V2Frames() {
        val v1 = MavlinkFrameEncoder.v1(0, heartbeatPayload(customMode = 4), sequence = 7, systemId = 42)
        val v2 = MavlinkFrameEncoder.v2(253, statusTextPayload("Exact status text"), sequence = 8, systemId = 42)
        val corrupted = v1.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 0x55).toByte() }
        val parser = MavlinkParser()

        assertTrue(parser.feed(byteArrayOf(1, 2, 3) + v1.copyOfRange(0, 4)).isEmpty())
        val first = parser.feed(v1.copyOfRange(4, v1.size) + corrupted)
        val second = parser.feed(v2)

        assertEquals(1, first.size)
        assertEquals(1, first.single().version)
        assertEquals(42, first.single().systemId)
        assertEquals(2, second.single().version)
        assertEquals("Exact status text", MavlinkDecoder.decode(second.single()).events.single().text)
    }

    @Test
    fun simulatedStreamCreatesFlightSessionAndPreservesStatusTextVerbatim() {
        authorizeGate()
        MavlinkSessionManager.arm(context, "simulation").getOrThrow()

        val accepted = MavlinkSessionManager.ingest(context, MavlinkSessionManager.simulationStream(42), 10_000)
        val state = MavlinkSessionManager.state.value
        val db = ObservationStore.repository(context).db()
        val session = db.session(state.sessionId!!)!!

        assertEquals(4, accepted)
        assertEquals(42, state.systemId)
        assertTrue(session.identityHash.startsWith("idhash:v1:"))
        assertFalse(session.identityHash.contains("42"))
        assertEquals("Simulation link established", db.sessionEvents(state.sessionId).single { it.eventType == "STATUSTEXT" }.text)
        assertTrue(db.allContext().any { it.metric == "mavlink_gps_hdop" && it.sessionId == state.sessionId })

        val snapshot = MavlinkSessionManager.collect(context, observationId = 9, isControl = false, nowMs = 15_001)
        val age = snapshot.single { it.metric == "mavlink_telemetry_age_ms" }
        assertEquals(5_001.0, age.value, 0.0)
        assertTrue(age.metadata.contains("stale=true"))

        MavlinkSessionManager.stop(context)
        assertEquals(CaptureSessionStatus.COMPLETED, db.session(state.sessionId)!!.status)
    }

    @Test
    fun bindsFirstHeartbeatIgnoresOtherSystemsAndCountsObservedSequenceGaps() {
        authorizeGate()
        MavlinkSessionManager.arm(context, "udp:14550").getOrThrow()
        val first = MavlinkFrameEncoder.v2(0, heartbeatPayload(), 2, systemId = 7)
        val otherSystem = MavlinkFrameEncoder.v2(24, gpsPayload(), 3, systemId = 8)
        val gap = MavlinkFrameEncoder.v2(24, gpsPayload(), 5, systemId = 7)

        assertEquals(2, MavlinkSessionManager.ingest(context, first + otherSystem + gap, 2_000))
        assertEquals(7, MavlinkSessionManager.state.value.systemId)
        assertEquals(2, MavlinkSessionManager.state.value.packetDropCount)
        val persisted = ObservationStore.repository(context).db().allContext()
        assertEquals(1, persisted.count { it.metric == "mavlink_gps_fix_type" })
        assertTrue(persisted.none { it.metadata.contains("system_id=") || it.metadata.contains("component_id=") })
    }

    @Test
    fun modeAndFailsafeTransitionsAreEventsWithoutHeartbeatSpam() {
        authorizeGate()
        MavlinkSessionManager.arm(context, "tcp:local").getOrThrow()
        val normal = MavlinkFrameEncoder.v2(0, heartbeatPayload(customMode = 1, status = 4), 1, 3)
        val changed = MavlinkFrameEncoder.v2(0, heartbeatPayload(customMode = 6, status = 5), 2, 3)
        val repeat = MavlinkFrameEncoder.v2(0, heartbeatPayload(customMode = 6, status = 5), 3, 3)
        MavlinkSessionManager.ingest(context, normal + changed + repeat, 4_000)

        val events = ObservationStore.repository(context).db().sessionEvents(MavlinkSessionManager.activeId())
        assertEquals(1, events.count { it.eventType == "MODE_TRANSITION" })
        assertEquals(1, events.count { it.eventType == "FAILSAFE_STATE" })
    }

    @Test
    fun gateOffPreventsFlightSessionAndRows() {
        assertTrue(MavlinkSessionManager.arm(context, "simulation").isFailure)
        assertEquals(0, MavlinkSessionManager.ingest(context, MavlinkSessionManager.simulationStream(), 1_000))
        assertTrue(ObservationStore.repository(context).db().sessions().isEmpty())
    }

    private fun authorizeGate() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_MAVLINK_CAPTURE,
            true,
            HardwareGates.ConsentProof.SingleConfirmation
        )
    }

    private fun heartbeatPayload(customMode: Int = 0, status: Int = 4): ByteArray =
        ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(customMode)
            put(2)
            put(3)
            put(0x80.toByte())
            put(status.toByte())
            put(3)
        }.array()

    private fun statusTextPayload(text: String): ByteArray = ByteArray(51).apply {
        this[0] = 4
        text.toByteArray().copyInto(this, 1)
    }

    private fun gpsPayload(): ByteArray = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
        putLong(0); putInt(1); putInt(2); putInt(3); putShort(100); putShort(0); putShort(0); putShort(0); put(3); put(10)
    }.array()
}
