package com.dronewukong.apophenia.omniprobe

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.video.CallAudioCapability
import com.dronewukong.apophenia.video.CallConsentJurisdiction
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
class OmniprobeInspectorTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("apophenia.db")
        db = ObservationDb(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.LIVE)
        HardwareGates.Gate.entries.forEach { HardwareGates.setAuthorized(context, it, false) }
        CallAudioCapability.setJurisdiction(context, CallConsentJurisdiction.UNKNOWN)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun inventoriesStoredValuesWithCaptureIdentityAndExplicitGateOffGaps() {
        authorize(HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE)
        val observation = insertEvent()
        db.insertContext(
            listOf(
                ContextSample(
                    timestampMs = observation.timestampMs,
                    observationId = observation.id,
                    source = "android_power",
                    metric = "battery_level_pct",
                    value = 71.0,
                    unit = "percent",
                    captureId = "event:${observation.id}:instant"
                )
            )
        )

        val snapshot = OmniprobeInspector(context, db).inspect(observation)
        val power = snapshot.channel(HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE)
        val nfc = snapshot.channel(HardwareGates.Gate.LIVE_NFC_CAPTURE)

        assertTrue(power.observed)
        assertEquals("event:${observation.id}:instant", power.values.single().captureId)
        assertEquals("71", power.values.single().renderedValue)
        assertEquals(HardwareGates.GapReason.GATE_OFF, nfc.gapReason)
    }

    @Test
    fun separatesAvailableButUnsampledFromMissingSession() {
        authorize(HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE)
        authorize(HardwareGates.Gate.LIVE_MAVLINK_CAPTURE)
        val snapshot = OmniprobeInspector(context, db).inspect(insertEvent())

        assertEquals(
            HardwareGates.GapReason.NO_SAMPLE_IN_WINDOW,
            snapshot.channel(HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE).gapReason
        )
        assertEquals(
            HardwareGates.GapReason.NO_ACTIVE_SESSION,
            snapshot.channel(HardwareGates.Gate.LIVE_MAVLINK_CAPTURE).gapReason
        )
    }

    @Test
    fun preservesRowsThatNoCurrentGateMatcherRecognizes() {
        val observation = insertEvent()
        db.insertContext(
            listOf(
                ContextSample(
                    timestampMs = observation.timestampMs,
                    observationId = observation.id,
                    source = "future_adapter",
                    metric = "future_metric",
                    value = 9.0,
                    unit = "count",
                    captureId = "event:${observation.id}:instant"
                )
            )
        )

        val snapshot = OmniprobeInspector(context, db).inspect(observation)

        assertEquals("future_metric", snapshot.unmatchedValues.single().metric)
    }

    @Test
    fun reportsConfiguredStatutoryCallAudioLock() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE,
            true,
            HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        )
        CallAudioCapability.setJurisdiction(context, CallConsentJurisdiction.ALL_PARTY)

        val call = OmniprobeInspector(context, db).inspect(insertEvent())
            .channel(HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE)

        assertEquals(HardwareGates.GapReason.LOCKED_BY_STATUTE, call.gapReason)
        assertTrue(call.gapDetail.contains("not by Apophenia"))
    }

    private fun authorize(gate: HardwareGates.Gate) {
        val proof = when (gate.tier) {
            HardwareGates.GateTier.STANDARD -> HardwareGates.ConsentProof.SingleConfirmation
            HardwareGates.GateTier.DELIBERATE -> HardwareGates.ConsentProof.TypedGateName(gate.name)
            HardwareGates.GateTier.CAPABILITY_CONDITIONAL -> HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        }
        HardwareGates.setAuthorized(context, gate, true, proof)
    }

    private fun insertEvent(): Observation {
        val event = Observation(
            timestampMs = System.currentTimeMillis(),
            kind = ObservationKind.OBSERVATION,
            label = "Omniprobe test"
        )
        return event.copy(id = db.insertObservation(event))
    }

    private fun OmniprobeSnapshot.channel(gate: HardwareGates.Gate): OmniprobeChannel =
        channels.single { it.gate == gate }
}
