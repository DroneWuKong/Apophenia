package com.dronewukong.apophenia.video

import android.content.Context
import com.dronewukong.apophenia.hardware.HardwareGates

enum class CallConsentJurisdiction {
    UNKNOWN,
    ONE_PARTY,
    ALL_PARTY
}

object CallAudioCapability {
    private const val PREFS = "call_audio_capability"
    private const val JURISDICTION = "jurisdiction"

    fun jurisdiction(context: Context): CallConsentJurisdiction = runCatching {
        CallConsentJurisdiction.valueOf(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(JURISDICTION, CallConsentJurisdiction.UNKNOWN.name)!!
        )
    }.getOrDefault(CallConsentJurisdiction.UNKNOWN)

    fun setJurisdiction(context: Context, value: CallConsentJurisdiction) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(JURISDICTION, value.name).apply()
    }

    fun capability(context: Context): HardwareGates.CapabilityState = when (jurisdiction(context)) {
        CallConsentJurisdiction.ALL_PARTY -> HardwareGates.CapabilityState.LOCKED_BY_STATUTE
        else -> HardwareGates.CapabilityState.PLATFORM_RESTRICTED
    }

    fun explanation(context: Context): String = when (capability(context)) {
        HardwareGates.CapabilityState.LOCKED_BY_STATUTE -> "Locked by statute, not by Apophenia. The configured jurisdiction requires all-party consent; this build has no consent-attestation workflow."
        else -> "Platform restricted. Ordinary Android apps cannot capture the remote side of a cellular/VoIP call through a supported public API. The gate and per-call intent remain visible; no substitute microphone audio is mislabeled as call audio."
    }
}
