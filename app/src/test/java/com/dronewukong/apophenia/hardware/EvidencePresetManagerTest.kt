package com.dronewukong.apophenia.hardware

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.presets.EvidencePreset
import com.dronewukong.apophenia.presets.EvidencePresetManager
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
class EvidencePresetManagerTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
        context.getSharedPreferences("evidence_presets", Context.MODE_PRIVATE).edit().clear().commit()
        EvidencePresetManager.load(context)
    }

    @After
    fun tearDown() {
        EvidencePresetManager.deactivate(context)
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @Test
    fun rejectsShortHoldWithoutChangingDeliberateGates() {
        val result = EvidencePresetManager.armPreset(
            context,
            EvidencePreset.FIELD,
            HardwareGates.DELIBERATE_HOLD_MS - 1
        )

        assertTrue(result.rejected)
        assertFalse(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_AUDIO_CAPTURE))
        assertEquals(null, EvidencePresetManager.state.value.activePreset)
    }

    @Test
    fun fieldArmsExactCaptureSetAndMaximumFieldAudioWindow() {
        HardwareGates.setAuthorized(context, HardwareGates.Gate.LIVE_LOCATION_CAPTURE, false)

        val result = EvidencePresetManager.armPreset(
            context,
            EvidencePreset.FIELD,
            HardwareGates.DELIBERATE_HOLD_MS
        )

        assertFalse(result.rejected)
        EvidencePresetManager.gatesFor(EvidencePreset.FIELD).forEach {
            assertTrue("Expected $it to be armed", HardwareGates.isAuthorized(context, it))
        }
        assertFalse(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_LOCATION_CAPTURE))
        assertFalse(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_EXPORT_LAN))
        assertEquals(120, EvidencePresetManager.audioPreSeconds(context))
    }

    @Test
    fun totalEvidenceArmsEveryCaptureGateButNeverExportOrAndroidPermission() {
        val microphoneBefore = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)

        val result = EvidencePresetManager.activateTotalEvidence(
            context,
            HardwareGates.DELIBERATE_HOLD_MS,
            nowMs = 123_456L
        )

        assertFalse(result.rejected)
        HardwareGates.Gate.entries.filter { it != HardwareGates.Gate.LIVE_EXPORT_LAN && it != HardwareGates.Gate.LIVE_TASKER_EXPORT }.forEach {
            assertTrue("Expected $it to be armed", HardwareGates.isAuthorized(context, it))
        }
        assertFalse(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_EXPORT_LAN))
        assertFalse(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_TASKER_EXPORT))
        assertEquals(microphoneBefore, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        assertEquals(PackageManager.PERMISSION_DENIED, microphoneBefore)
        assertTrue(EvidencePresetManager.state.value.totalEvidenceActive)
        assertEquals(123_456L, EvidencePresetManager.state.value.activatedAtMs)
    }

    @Test
    fun deactivationPreservesIndividualGateAuthorization() {
        EvidencePresetManager.armPreset(context, EvidencePreset.DRIVE, HardwareGates.DELIBERATE_HOLD_MS)

        EvidencePresetManager.deactivate(context)

        assertEquals(null, EvidencePresetManager.state.value.activePreset)
        assertFalse(EvidencePresetManager.state.value.totalEvidenceActive)
        assertTrue(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_VEHICLE_CAPTURE))
        assertEquals(60, EvidencePresetManager.audioPreSeconds(context))
    }
}
