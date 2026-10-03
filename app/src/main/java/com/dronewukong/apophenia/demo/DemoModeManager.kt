package com.dronewukong.apophenia.demo

import android.content.Context
import com.dronewukong.apophenia.hardware.HardwareGates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class DemoModeState(val active: Boolean = false, val activatedAtMs: Long? = null)

object DemoModeManager {
    const val DATABASE_NAME = "apophenia-demo.db"
    private const val PREFS = "demo_mode"
    private const val ACTIVE = "active"
    private const val ACTIVATED_AT = "activated_at_ms"
    private const val PREVIOUS_RUNTIME = "previous_runtime"
    private val stateFlow = MutableStateFlow(DemoModeState())
    val state: StateFlow<DemoModeState> = stateFlow

    fun load(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val active = preferences.getBoolean(ACTIVE, false)
        stateFlow.value = DemoModeState(active, preferences.getLong(ACTIVATED_AT, 0L).takeIf { it > 0L })
        if (active && HardwareGates.runtimeMode != HardwareGates.RuntimeMode.SIMULATION) {
            HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        }
    }

    fun enable(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!stateFlow.value.active) {
            preferences.edit()
                .putString(PREVIOUS_RUNTIME, HardwareGates.runtimeMode.name)
                .putBoolean(ACTIVE, true)
                .putLong(ACTIVATED_AT, nowMs)
                .apply()
        }
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        stateFlow.value = DemoModeState(true, nowMs)
    }

    fun disable(context: Context) {
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = runCatching {
            HardwareGates.RuntimeMode.valueOf(preferences.getString(PREVIOUS_RUNTIME, HardwareGates.RuntimeMode.LIVE.name)!!)
        }.getOrDefault(HardwareGates.RuntimeMode.LIVE)
        preferences.edit().putBoolean(ACTIVE, false).remove(ACTIVATED_AT).remove(PREVIOUS_RUNTIME).apply()
        HardwareGates.setRuntimeMode(context, previous)
        stateFlow.value = DemoModeState()
    }
}
