package com.dronewukong.apophenia.hardware

import android.content.Context
import com.dronewukong.apophenia.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single authorization boundary for live capture.
 *
 * A gate records user intent only. Android permissions and momentary hardware/platform/legal
 * availability remain separate inputs so an authorized-but-unavailable channel is visible rather
 * than silently disappearing.
 */
object HardwareGates {
    enum class RuntimeMode { LIVE, SIMULATION }

    enum class GateTier {
        STANDARD,
        DELIBERATE,
        CAPABILITY_CONDITIONAL
    }

    enum class Gate(
        val tier: GateTier,
        val defaultAuthorized: Boolean = false
    ) {
        // Existing v0.2/v0.3-preview channels retain their prior enabled defaults.
        LIVE_SENSOR_CAPTURE(GateTier.STANDARD, defaultAuthorized = true),
        LIVE_LOCATION_CAPTURE(GateTier.STANDARD, defaultAuthorized = true),
        LIVE_ENVIRONMENT_LOOKUP(GateTier.STANDARD, defaultAuthorized = true),
        LIVE_GARMIN_BRIDGE(GateTier.STANDARD, defaultAuthorized = true),

        LIVE_BLUETOOTH_CAPTURE(GateTier.STANDARD),
        LIVE_WIFI_CAPTURE(GateTier.STANDARD),
        LIVE_NETWORK_STATE_CAPTURE(GateTier.STANDARD),
        LIVE_WIFI_P2P_CAPTURE(GateTier.STANDARD),
        LIVE_NFC_CAPTURE(GateTier.STANDARD),
        LIVE_AUDIO_METADATA_CAPTURE(GateTier.STANDARD),
        LIVE_DISPLAY_INTERACTION_CAPTURE(GateTier.STANDARD),
        LIVE_NOTIFICATION_CONTENTS_CAPTURE(GateTier.DELIBERATE),
        LIVE_CALENDAR_CONTENTS_CAPTURE(GateTier.DELIBERATE),
        LIVE_CONTACTS_CONTENTS_CAPTURE(GateTier.DELIBERATE),
        LIVE_MESSAGE_METADATA_CAPTURE(GateTier.DELIBERATE),
        LIVE_POWER_THERMAL_CAPTURE(GateTier.STANDARD),
        LIVE_TIME_CONTEXT_CAPTURE(GateTier.STANDARD),

        LIVE_VEHICLE_CAPTURE(GateTier.STANDARD),
        LIVE_CARPLAY_AUTOMOTIVE_CAPTURE(GateTier.STANDARD),
        LIVE_EV_CAPTURE(GateTier.CAPABILITY_CONDITIONAL),

        LIVE_MAVLINK_CAPTURE(GateTier.STANDARD),
        LIVE_CRSF_GHST_CAPTURE(GateTier.STANDARD),
        LIVE_FIELD_KIT_CAPTURE(GateTier.STANDARD),
        LIVE_TAK_CAPTURE(GateTier.STANDARD),
        LIVE_TAK_CAPTURE_FULL(GateTier.CAPABILITY_CONDITIONAL),
        LIVE_GROUND_CONTEXT_CAPTURE(GateTier.STANDARD),
        LIVE_RF_SURVEY_CAPTURE(GateTier.CAPABILITY_CONDITIONAL),

        LIVE_AUDIO_CAPTURE(GateTier.DELIBERATE),
        LIVE_VIDEO_CAPTURE(GateTier.DELIBERATE),
        LIVE_VIDEO_SELFCAPTURE(GateTier.DELIBERATE),
        LIVE_MULTICAM_CAPTURE(GateTier.DELIBERATE),
        LIVE_SCREENRECORD_CAPTURE(GateTier.DELIBERATE),
        LIVE_CALL_AUDIO_CAPTURE(GateTier.CAPABILITY_CONDITIONAL),

        LIVE_EXPORT_LAN(GateTier.STANDARD)
    }

    sealed interface ConsentProof {
        data object SingleConfirmation : ConsentProof
        data class TypedGateName(val value: String) : ConsentProof
        data class PressAndHold(val durationMs: Long) : ConsentProof
        data object CapabilityConditionalConfirmation : ConsentProof
    }

    enum class AuthorizationResult { ENABLED, DISABLED, REJECTED_CONFIRMATION }

    enum class CapabilityState {
        AVAILABLE,
        PERMISSION_DENIED,
        PLATFORM_RESTRICTED,
        HARDWARE_ABSENT,
        LOCKED_BY_STATUTE
    }

    enum class GapReason {
        GATE_OFF,
        PERMISSION_DENIED,
        PLATFORM_RESTRICTED,
        HARDWARE_ABSENT,
        LOCKED_BY_STATUTE,
        BUILD_DISABLED,
        SIMULATION_MODE,
        NO_SAMPLE_IN_WINDOW,
        NO_ACTIVE_SESSION
    }

    data class GateStatus(
        val gate: Gate,
        val authorized: Boolean,
        val captureActive: Boolean,
        val gapReason: GapReason? = null
    )

    private const val PREF = "hardware_gates"
    private const val KEY_MODE = "runtime_mode"
    private const val KEY_GATE_PREFIX = "gate."
    const val DELIBERATE_HOLD_MS = 1_500L
    private val authorizations = ConcurrentHashMap<Gate, Boolean>().apply {
        Gate.entries.forEach { put(it, it.defaultAuthorized) }
    }
    private val revisionFlow = MutableStateFlow(0L)
    val authorizationRevision: StateFlow<Long> = revisionFlow

    @Volatile
    var runtimeMode: RuntimeMode = RuntimeMode.LIVE
        private set

    fun load(context: Context) {
        val preferences = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        runtimeMode = runCatching {
            RuntimeMode.valueOf(
                preferences.getString(KEY_MODE, RuntimeMode.LIVE.name)!!
            )
        }.getOrDefault(RuntimeMode.LIVE)
        Gate.entries.forEach { gate ->
            authorizations[gate] = preferences.getBoolean(KEY_GATE_PREFIX + gate.name, gate.defaultAuthorized)
        }
        revisionFlow.value++
    }

    fun setRuntimeMode(context: Context, mode: RuntimeMode) {
        runtimeMode = mode
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
        revisionFlow.value++
    }

    fun isAuthorized(context: Context, gate: Gate): Boolean =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getBoolean(KEY_GATE_PREFIX + gate.name, gate.defaultAuthorized)

    fun setAuthorized(
        context: Context,
        gate: Gate,
        enabled: Boolean,
        proof: ConsentProof? = null
    ): AuthorizationResult {
        if (!enabled) {
            writeAuthorization(context, gate, false)
            return AuthorizationResult.DISABLED
        }
        if (!accepts(gate, proof)) return AuthorizationResult.REJECTED_CONFIRMATION
        writeAuthorization(context, gate, true)
        return AuthorizationResult.ENABLED
    }

    fun status(
        context: Context,
        gate: Gate,
        capabilityState: CapabilityState = CapabilityState.AVAILABLE
    ): GateStatus {
        val authorized = isAuthorized(context, gate)
        val gap = when {
            !authorized -> GapReason.GATE_OFF
            runtimeMode != RuntimeMode.LIVE -> GapReason.SIMULATION_MODE
            !buildAllows(gate) -> GapReason.BUILD_DISABLED
            capabilityState == CapabilityState.PERMISSION_DENIED -> GapReason.PERMISSION_DENIED
            capabilityState == CapabilityState.PLATFORM_RESTRICTED -> GapReason.PLATFORM_RESTRICTED
            capabilityState == CapabilityState.HARDWARE_ABSENT -> GapReason.HARDWARE_ABSENT
            capabilityState == CapabilityState.LOCKED_BY_STATUTE -> GapReason.LOCKED_BY_STATUTE
            else -> null
        }
        return GateStatus(gate, authorized, captureActive = gap == null, gapReason = gap)
    }

    fun isCaptureEnabled(context: Context, gate: Gate): Boolean = status(context, gate).captureActive

    private fun accepts(gate: Gate, proof: ConsentProof?): Boolean = when (gate.tier) {
        GateTier.STANDARD -> proof == ConsentProof.SingleConfirmation
        GateTier.DELIBERATE -> when (proof) {
            is ConsentProof.TypedGateName -> proof.value.trim() == gate.name
            is ConsentProof.PressAndHold -> proof.durationMs >= DELIBERATE_HOLD_MS
            else -> false
        }
        GateTier.CAPABILITY_CONDITIONAL -> proof == ConsentProof.CapabilityConditionalConfirmation
    }

    private fun writeAuthorization(context: Context, gate: Gate, authorized: Boolean) {
        authorizations[gate] = authorized
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_GATE_PREFIX + gate.name, authorized)
            .apply()
        revisionFlow.value++
    }

    private fun buildAllows(gate: Gate): Boolean = when (gate) {
        Gate.LIVE_SENSOR_CAPTURE -> BuildConfig.LIVE_SENSOR_CAPTURE
        Gate.LIVE_LOCATION_CAPTURE -> BuildConfig.LIVE_LOCATION_CAPTURE
        Gate.LIVE_ENVIRONMENT_LOOKUP -> BuildConfig.LIVE_ENVIRONMENT_LOOKUP
        Gate.LIVE_GARMIN_BRIDGE -> BuildConfig.LIVE_GARMIN_BRIDGE
        else -> true
    }

    val sensorsEnabled get() = legacyGateEnabled(Gate.LIVE_SENSOR_CAPTURE)
    val locationEnabled get() = legacyGateEnabled(Gate.LIVE_LOCATION_CAPTURE)
    val environmentEnabled get() = legacyGateEnabled(Gate.LIVE_ENVIRONMENT_LOOKUP)
    val garminEnabled get() = legacyGateEnabled(Gate.LIVE_GARMIN_BRIDGE)

    private fun legacyGateEnabled(gate: Gate): Boolean =
        runtimeMode == RuntimeMode.LIVE && buildAllows(gate) && (authorizations[gate] ?: gate.defaultAuthorized)

    internal fun clearAuthorizationsForTests(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit()
        runtimeMode = RuntimeMode.LIVE
        Gate.entries.forEach { authorizations[it] = it.defaultAuthorized }
        revisionFlow.value++
    }
}
