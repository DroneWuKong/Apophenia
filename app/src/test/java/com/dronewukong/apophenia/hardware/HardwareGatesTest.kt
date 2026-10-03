package com.dronewukong.apophenia.hardware

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class HardwareGatesTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @After
    fun tearDown() {
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @Test
    fun standardGateNeedsOneConfirmationAndPersists() {
        val gate = HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE

        assertFalse(HardwareGates.isAuthorized(context, gate))
        assertEquals(
            HardwareGates.AuthorizationResult.REJECTED_CONFIRMATION,
            HardwareGates.setAuthorized(context, gate, enabled = true)
        )
        assertEquals(
            HardwareGates.AuthorizationResult.ENABLED,
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.SingleConfirmation
            )
        )

        HardwareGates.load(context)
        assertTrue(HardwareGates.isAuthorized(context, gate))
        assertTrue(HardwareGates.isCaptureEnabled(context, gate))
    }

    @Test
    fun deliberateGateRejectsAccidentalEnablement() {
        val gate = HardwareGates.Gate.LIVE_AUDIO_CAPTURE

        assertEquals(
            HardwareGates.AuthorizationResult.REJECTED_CONFIRMATION,
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.TypedGateName("LIVE_VIDEO_CAPTURE")
            )
        )
        assertEquals(
            HardwareGates.AuthorizationResult.REJECTED_CONFIRMATION,
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.PressAndHold(HardwareGates.DELIBERATE_HOLD_MS - 1)
            )
        )
        assertEquals(
            HardwareGates.AuthorizationResult.ENABLED,
            HardwareGates.setAuthorized(
                context,
                gate,
                enabled = true,
                proof = HardwareGates.ConsentProof.TypedGateName(gate.name)
            )
        )
    }

    @Test
    fun capabilityGateKeepsAuthorizationSeparateFromCurrentAvailability() {
        val gate = HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE
        HardwareGates.setAuthorized(
            context,
            gate,
            enabled = true,
            proof = HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        )

        val locked = HardwareGates.status(
            context,
            gate,
            HardwareGates.CapabilityState.LOCKED_BY_STATUTE
        )

        assertTrue(locked.authorized)
        assertFalse(locked.captureActive)
        assertEquals(HardwareGates.GapReason.LOCKED_BY_STATUTE, locked.gapReason)
    }

    @Test
    fun simulationNeverReportsLiveCapture() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)

        val status = HardwareGates.status(context, HardwareGates.Gate.LIVE_SENSOR_CAPTURE)

        assertTrue(status.authorized)
        assertFalse(status.captureActive)
        assertEquals(HardwareGates.GapReason.SIMULATION_MODE, status.gapReason)
    }

    @Test
    fun sensitiveAndConditionalGateInventoriesDoNotRegress() {
        val deliberate = HardwareGates.Gate.entries
            .filter { it.tier == HardwareGates.GateTier.DELIBERATE }
            .toSet()
        val conditional = HardwareGates.Gate.entries
            .filter { it.tier == HardwareGates.GateTier.CAPABILITY_CONDITIONAL }
            .toSet()

        assertEquals(
            setOf(
                HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE,
                HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE,
                HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE,
                HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE,
                HardwareGates.Gate.LIVE_AUDIO_CAPTURE,
                HardwareGates.Gate.LIVE_VIDEO_CAPTURE,
                HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE,
                HardwareGates.Gate.LIVE_MULTICAM_CAPTURE,
                HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE,
                HardwareGates.Gate.LIVE_TASKER_CAPTURE,
                HardwareGates.Gate.LIVE_TASKER_EXPORT
            ),
            deliberate
        )
        assertEquals(
            setOf(
                HardwareGates.Gate.LIVE_EV_CAPTURE,
                HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL,
                HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE,
                HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE
            ),
            conditional
        )
    }
}
