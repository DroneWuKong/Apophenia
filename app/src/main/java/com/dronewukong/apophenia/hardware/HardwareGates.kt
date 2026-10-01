package com.dronewukong.apophenia.hardware

import android.content.Context
import com.dronewukong.apophenia.BuildConfig

object HardwareGates {
    enum class RuntimeMode { LIVE, SIMULATION }
    private const val PREF = "hardware_gates"
    private const val KEY_MODE = "runtime_mode"

    @Volatile var runtimeMode: RuntimeMode = RuntimeMode.LIVE
        private set

    fun load(context: Context) {
        runtimeMode = runCatching {
            RuntimeMode.valueOf(context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_MODE, RuntimeMode.LIVE.name)!!)
        }.getOrDefault(RuntimeMode.LIVE)
    }

    fun setRuntimeMode(context: Context, mode: RuntimeMode) {
        runtimeMode = mode
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
    }

    val sensorsEnabled get() = runtimeMode == RuntimeMode.LIVE && BuildConfig.LIVE_SENSOR_CAPTURE
    val locationEnabled get() = runtimeMode == RuntimeMode.LIVE && BuildConfig.LIVE_LOCATION_CAPTURE
    val environmentEnabled get() = runtimeMode == RuntimeMode.LIVE && BuildConfig.LIVE_ENVIRONMENT_LOOKUP
    val garminEnabled get() = runtimeMode == RuntimeMode.LIVE && BuildConfig.LIVE_GARMIN_BRIDGE
}
