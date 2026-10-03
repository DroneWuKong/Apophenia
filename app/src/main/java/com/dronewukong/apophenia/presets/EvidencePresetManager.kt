package com.dronewukong.apophenia.presets

import android.content.Context
import com.dronewukong.apophenia.hardware.HardwareGates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class EvidencePreset(val displayName: String, val audioPreSeconds: Int) {
    FIELD("FIELD", 120),
    DRIVE("DRIVE", 120),
    HOME("HOME", 60),
    EVERYTHING("EVERYTHING", 120)
}

data class EvidenceModeState(
    val activePreset: EvidencePreset? = null,
    val totalEvidenceActive: Boolean = false,
    val activatedAtMs: Long? = null,
    val audioPreSeconds: Int = 60
)

data class PresetApplyResult(
    val preset: EvidencePreset,
    val newlyArmed: Set<HardwareGates.Gate>,
    val alreadyArmed: Set<HardwareGates.Gate>,
    val rejected: Boolean,
    val totalEvidence: Boolean
) {
    val armedCount: Int get() = newlyArmed.size + alreadyArmed.size
}

/**
 * Deliberate bulk gate arming. This layer never calls a permission API, starts a hardware
 * session, or opens an export route. Those momentary capabilities remain visible and separate.
 */
object EvidencePresetManager {
    private const val PREFS = "evidence_presets"
    private const val ACTIVE_PRESET = "active_preset"
    private const val TOTAL = "total_evidence"
    private const val ACTIVATED_AT = "activated_at_ms"
    private const val AUDIO_PRE_SECONDS = "audio_pre_seconds"
    const val DEFAULT_AUDIO_PRE_SECONDS = 60
    const val MAX_AUDIO_PRE_SECONDS = 120

    private val stateFlow = MutableStateFlow(EvidenceModeState())
    val state: StateFlow<EvidenceModeState> = stateFlow

    fun load(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val preset = preferences.getString(ACTIVE_PRESET, null)?.let {
            runCatching { EvidencePreset.valueOf(it) }.getOrNull()
        }
        stateFlow.value = EvidenceModeState(
            activePreset = preset,
            totalEvidenceActive = preferences.getBoolean(TOTAL, false),
            activatedAtMs = preferences.getLong(ACTIVATED_AT, 0L).takeIf { it > 0L },
            audioPreSeconds = preferences.getInt(AUDIO_PRE_SECONDS, DEFAULT_AUDIO_PRE_SECONDS).coerceIn(DEFAULT_AUDIO_PRE_SECONDS, MAX_AUDIO_PRE_SECONDS)
        )
    }

    fun armPreset(
        context: Context,
        preset: EvidencePreset,
        holdDurationMs: Long,
        totalEvidence: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): PresetApplyResult {
        if (holdDurationMs < HardwareGates.DELIBERATE_HOLD_MS) {
            return PresetApplyResult(preset, emptySet(), emptySet(), rejected = true, totalEvidence = totalEvidence)
        }
        val target = gatesFor(preset)
        val already = target.filterTo(linkedSetOf()) { HardwareGates.isAuthorized(context, it) }
        val newly = linkedSetOf<HardwareGates.Gate>()
        target.filterNot { it in already }.forEach { gate ->
            val proof = when (gate.tier) {
                HardwareGates.GateTier.STANDARD -> HardwareGates.ConsentProof.SingleConfirmation
                HardwareGates.GateTier.DELIBERATE -> HardwareGates.ConsentProof.PressAndHold(holdDurationMs)
                HardwareGates.GateTier.CAPABILITY_CONDITIONAL -> HardwareGates.ConsentProof.CapabilityConditionalConfirmation
            }
            if (HardwareGates.setAuthorized(context, gate, true, proof) == HardwareGates.AuthorizationResult.ENABLED) newly += gate
        }
        val next = EvidenceModeState(preset, totalEvidence, nowMs, preset.audioPreSeconds)
        persist(context, next)
        return PresetApplyResult(preset, newly, already, rejected = false, totalEvidence = totalEvidence)
    }

    fun activateTotalEvidence(context: Context, holdDurationMs: Long, nowMs: Long = System.currentTimeMillis()): PresetApplyResult =
        armPreset(context, EvidencePreset.EVERYTHING, holdDurationMs, totalEvidence = true, nowMs = nowMs)

    /** Stops the master/preset mode without revoking the operator's individual gate choices. */
    fun deactivate(context: Context) {
        persist(context, EvidenceModeState())
    }

    fun audioPreSeconds(context: Context): Int {
        if (stateFlow.value.activePreset == null) load(context)
        return stateFlow.value.audioPreSeconds.coerceIn(DEFAULT_AUDIO_PRE_SECONDS, MAX_AUDIO_PRE_SECONDS)
    }

    fun gatesFor(preset: EvidencePreset): Set<HardwareGates.Gate> = when (preset) {
        EvidencePreset.FIELD -> linkedSetOf(
            HardwareGates.Gate.LIVE_MAVLINK_CAPTURE,
            HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE,
            HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE,
            HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE,
            HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE,
            HardwareGates.Gate.LIVE_AUDIO_CAPTURE,
            HardwareGates.Gate.LIVE_VIDEO_CAPTURE,
            HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE,
            HardwareGates.Gate.LIVE_MULTICAM_CAPTURE,
            HardwareGates.Gate.LIVE_SCREENRECORD_CAPTURE,
            HardwareGates.Gate.LIVE_GARMIN_BRIDGE
        )
        EvidencePreset.DRIVE -> linkedSetOf(
            HardwareGates.Gate.LIVE_VEHICLE_CAPTURE,
            HardwareGates.Gate.LIVE_CARPLAY_AUTOMOTIVE_CAPTURE,
            HardwareGates.Gate.LIVE_EV_CAPTURE,
            HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
            HardwareGates.Gate.LIVE_AUDIO_CAPTURE,
            HardwareGates.Gate.LIVE_VIDEO_CAPTURE,
            HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE,
            HardwareGates.Gate.LIVE_MULTICAM_CAPTURE
        )
        EvidencePreset.HOME -> linkedSetOf(
            HardwareGates.Gate.LIVE_SENSOR_CAPTURE,
            HardwareGates.Gate.LIVE_LOCATION_CAPTURE,
            HardwareGates.Gate.LIVE_ENVIRONMENT_LOOKUP,
            HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
            HardwareGates.Gate.LIVE_WIFI_CAPTURE,
            HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE,
            HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE,
            HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE,
            HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE,
            HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE,
            HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE
        )
        EvidencePreset.EVERYTHING -> HardwareGates.Gate.entries.filterTo(linkedSetOf()) {
            // Capture modes never authorize a route that can move data off-device.
            it != HardwareGates.Gate.LIVE_EXPORT_LAN
        }
    }

    private fun persist(context: Context, state: EvidenceModeState) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (state.activePreset == null) remove(ACTIVE_PRESET) else putString(ACTIVE_PRESET, state.activePreset.name)
            putBoolean(TOTAL, state.totalEvidenceActive)
            if (state.activatedAtMs == null) remove(ACTIVATED_AT) else putLong(ACTIVATED_AT, state.activatedAtMs)
            putInt(AUDIO_PRE_SECONDS, state.audioPreSeconds)
        }.apply()
        stateFlow.value = state
    }
}
