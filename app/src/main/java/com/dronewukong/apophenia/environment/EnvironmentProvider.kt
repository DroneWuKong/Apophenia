package com.dronewukong.apophenia.environment

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class EnvironmentProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (!HardwareGates.locationEnabled) return emptyList()
        val location = bestLastLocation() ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = mutableListOf(
            ContextSample(timestampMs=now, observationId=observationId, isControl=isControl, source="location", metric="latitude", value=location.latitude, unit="deg"),
            ContextSample(timestampMs=now, observationId=observationId, isControl=isControl, source="location", metric="longitude", value=location.longitude, unit="deg"),
            ContextSample(timestampMs=now, observationId=observationId, isControl=isControl, source="location", metric="location_accuracy_m", value=location.accuracy.toDouble(), unit="m")
        )
        if (HardwareGates.environmentEnabled) out += fetchWeather(location, observationId, isControl)
        return out
    }

    private fun bestLastLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }.minByOrNull { it.accuracy }
    }

    private fun fetchWeather(loc: Location, observationId: Long?, isControl: Boolean): List<ContextSample> = runCatching {
        val fields = "temperature_2m,relative_humidity_2m,surface_pressure,wind_speed_10m,precipitation,cloud_cover"
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=$fields&timezone=auto")
        val conn = (url.openConnection() as HttpURLConnection).apply { connectTimeout=3500; readTimeout=3500; requestMethod="GET" }
        conn.inputStream.bufferedReader().use { reader ->
            val current = JSONObject(reader.readText()).getJSONObject("current")
            val t = System.currentTimeMillis()
            fun s(metric:String, key:String, unit:String) = ContextSample(timestampMs=t, observationId=observationId, isControl=isControl, source="open_meteo", metric=metric, value=current.optDouble(key, Double.NaN), unit=unit)
            listOf(
                s("weather_temperature_c","temperature_2m","C"), s("weather_humidity_pct","relative_humidity_2m","%"),
                s("weather_pressure_hpa","surface_pressure","hPa"), s("weather_wind_kmh","wind_speed_10m","km/h"),
                s("weather_precip_mm","precipitation","mm"), s("weather_cloud_pct","cloud_cover","%")
            ).filter { !it.value.isNaN() }
        }
    }.getOrDefault(emptyList())
}
