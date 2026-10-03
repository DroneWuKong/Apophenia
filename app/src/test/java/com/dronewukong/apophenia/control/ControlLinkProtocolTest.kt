package com.dronewukong.apophenia.control

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ObservationStore
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
class ControlLinkProtocolTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObservationStore.resetForTests(); context.deleteDatabase("apophenia.db")
        HardwareGates.clearAuthorizationsForTests(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After fun tearDown() {
        ControlLinkManager.stop(); HardwareGates.clearAuthorizationsForTests(context)
        ObservationStore.resetForTests(); context.deleteDatabase("apophenia.db")
    }

    @Test fun crsfParserHandlesSplitFramesAndBothDirections() {
        val bytes = CrsfLinkParser.encodeLinkStats(byteArrayOf(62, 67, 96, 8, 1, 2, 4, 71, 91, (-3).toByte()))
        val parser = CrsfLinkParser()
        assertTrue(parser.feed(byteArrayOf(9, 8) + bytes.copyOfRange(0, 5)).isEmpty())
        val metrics = parser.feed(bytes.copyOfRange(5, bytes.size)).single().associateBy { it.metric }
        assertEquals(-62.0, metrics.getValue("control_uplink_rssi_dbm").value, 0.0)
        assertEquals(96.0, metrics.getValue("control_uplink_lq_pct").value, 0.0)
        assertEquals(-3.0, metrics.getValue("control_downlink_snr_db").value, 0.0)
        assertEquals(9.0, metrics.getValue("control_downlink_packet_loss_pct").value, 0.0)
    }

    @Test fun ghstParserUsesProvenLinkStatLayoutAndDisclosesMissingDownlink() {
        val bytes = GhstLinkParser.encodeLinkStats(byteArrayOf(64, 94, (-7).toByte(), 0, 100, 9, 196.toByte(), 12, 44, 4))
        val metrics = GhstLinkParser().feed(bytes).single().associateBy { it.metric }
        assertEquals(-64.0, metrics.getValue("control_uplink_rssi_dbm").value, 0.0)
        assertEquals(-7.0, metrics.getValue("control_uplink_snr_db").value, 0.0)
        assertEquals(100.0, metrics.getValue("control_tx_power_mw").value, 0.0)
        assertEquals(0.0, metrics.getValue("control_downlink_available").value, 0.0)
    }

    @Test fun managerPersistsSimulationFramesAndSnapshotsOnlyBehindGate() {
        assertTrue(ControlLinkManager.arm(context, ControlLinkProtocol.CRSF, "simulation").isFailure)
        HardwareGates.setAuthorized(context, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation)
        ControlLinkManager.arm(context, ControlLinkProtocol.CRSF, "simulation").getOrThrow()
        assertEquals(1, ControlLinkManager.ingest(context, ControlLinkManager.simulationBytes(ControlLinkProtocol.CRSF), 10_000))
        val snapshot = ControlLinkManager.collect(context, observationId = 7, isControl = false, nowMs = 12_000)
        assertTrue(snapshot.any { it.metric == "control_uplink_lq_pct" && it.value == 96.0 })
        assertTrue(snapshot.all { it.observationId == 7L })
        assertTrue(ObservationStore.repository(context).db().allContext().all { !it.metadata.contains("usb:") })
    }
}
