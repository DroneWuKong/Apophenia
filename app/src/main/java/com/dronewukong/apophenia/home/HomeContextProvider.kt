package com.dronewukong.apophenia.home

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object HomeContextSettings {
    private const val PREFS = "home_context"
    private const val ENABLED = "enabled"
    private const val ENDPOINT = "endpoint"
    const val DEFAULT_ENDPOINT = "http://octopod.home"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()

    fun endpoint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENDPOINT, DEFAULT_ENDPOINT)
            ?.trim()?.trimEnd('/').orEmpty().ifBlank { DEFAULT_ENDPOINT }

    fun setEndpoint(context: Context, endpoint: String) {
        val normalized = endpoint.trim().trimEnd('/').ifBlank { DEFAULT_ENDPOINT }
        require(normalized.startsWith("http://") || normalized.startsWith("https://"))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ENDPOINT, normalized).apply()
    }
}

object HomeContextParser {
    fun parse(
        homeJson: String?,
        camerasJson: String?,
        observationId: Long?,
        isControl: Boolean,
        timestampMs: Long = System.currentTimeMillis()
    ): List<ContextSample> {
        val values = linkedMapOf<String, Pair<Double, String>>()
        runCatching {
            val data = homeJson?.let(::JSONObject)?.takeIf { it.optBoolean("ok") }?.optJSONObject("data")
                ?: return@runCatching
            fun collection(name: String, active: String) {
                val group = data.optJSONObject(name) ?: return
                values["home_${name}_active"] = group.optJSONArray(active).safeLength().toDouble() to "count"
                values["home_${name}_total"] = group.optInt("total", 0).toDouble() to "count"
            }
            collection("contacts", "open")
            collection("motion", "active")
            collection("leak", "wet")
            collection("locks", "unlocked")
            collection("presence", "home")
            data.optJSONObject("lights")?.let {
                values["home_lights_on"] = it.optInt("on", 0).toDouble() to "count"
                values["home_lights_total"] = it.optInt("total", 0).toDouble() to "count"
            }
            val temps = data.optJSONArray("temps")
            val temperatureValues = (0 until temps.safeLength()).mapNotNull { index ->
                temps?.optJSONObject(index)?.optDouble("tempF", Double.NaN)?.takeIf(Double::isFinite)
            }
            if (temperatureValues.isNotEmpty()) values["home_temperature_avg_f"] = temperatureValues.average() to "F"
            values["home_low_battery_count"] = data.optJSONArray("lowBatteries").safeLength().toDouble() to "count"
        }
        runCatching {
            val cameras = camerasJson?.let(::JSONObject)?.takeIf { it.optBoolean("ok") }
                ?.optJSONObject("data")?.optJSONArray("cameras") ?: return@runCatching
            var connected = 0
            var enabled = 0
            for (index in 0 until cameras.length()) {
                cameras.optJSONObject(index)?.let {
                    if (it.optBoolean("connected")) connected++
                    if (it.optBoolean("enabled", true)) enabled++
                }
            }
            values["home_cameras_connected"] = connected.toDouble() to "count"
            values["home_cameras_enabled"] = enabled.toDouble() to "count"
        }
        return values.map { (metric, valueAndUnit) ->
            ContextSample(
                timestampMs = timestampMs,
                observationId = observationId,
                isControl = isControl,
                source = "octopod",
                metric = metric,
                value = valueAndUnit.first,
                unit = valueAndUnit.second,
                metadata = "privacy=aggregate;window=instant"
            )
        }
    }

    private fun org.json.JSONArray?.safeLength(): Int = this?.length() ?: 0
}

class HomeContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean, force: Boolean = false): List<ContextSample> {
        if (!force && !HomeContextSettings.isEnabled(context)) return emptyList()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return HomeContextParser.parse(SIMULATED_HOME, SIMULATED_CAMERAS, observationId, isControl)
                .map { it.copy(source = "simulation/octopod") }
        }
        val base = HomeContextSettings.endpoint(context)
        val home = fetch("$base/api/home")
        val cameras = fetch("$base/api/cameras")
        return HomeContextParser.parse(home, cameras, observationId, isControl)
    }

    private fun fetch(url: String): String? = runCatching {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_500
            readTimeout = 2_500
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val SIMULATED_HOME = """{"ok":true,"data":{"contacts":{"open":[],"total":4},"motion":{"active":["Hall"],"total":5},"leak":{"wet":[],"total":3},"locks":{"unlocked":[],"total":2},"lights":{"on":2,"total":8},"presence":{"home":["Person"],"total":2},"temps":[{"name":"Room","tempF":71}],"lowBatteries":[]}}"""
        private const val SIMULATED_CAMERAS = """{"ok":true,"data":{"cameras":[{"connected":true,"enabled":true},{"connected":false,"enabled":true}]}}"""
    }
}
