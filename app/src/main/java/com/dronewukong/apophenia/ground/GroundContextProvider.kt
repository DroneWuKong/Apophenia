package com.dronewukong.apophenia.ground

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.phone.SolarPhaseCalculator
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlin.math.sqrt
import org.json.JSONArray

data class SpaceWeatherSnapshot(
    val kp: Double,
    val kpObservedAtMs: Long,
    val solarFluxSfu: Double,
    val solarFluxObservedAtMs: Long
)

object GroundContextMath {
    fun pressureHpa(value: Double): Double = if (value > 2_000.0) value / 100.0 else value

    fun trendHpaPerHour(previousHpa: Double, previousAtMs: Long, currentHpa: Double, currentAtMs: Long): Double? {
        val hours = (currentAtMs - previousAtMs) / 3_600_000.0
        return if (previousAtMs > 0L && hours in (1.0 / 60.0)..24.0) (currentHpa - previousHpa) / hours else null
    }

    fun magneticMagnitudeUt(x: Double, y: Double, z: Double): Double = sqrt(x * x + y * y + z * z)
}

object SpaceWeatherSnapshotParser {
    fun parse(kpJson: String, fluxJson: String): SpaceWeatherSnapshot? = runCatching {
        val kpItems = JSONArray(kpJson)
        val kp = kpItems.getJSONObject(kpItems.length() - 1)
        val fluxItems = JSONArray(fluxJson)
        val flux = fluxItems.getJSONObject(fluxItems.length() - 1)
        SpaceWeatherSnapshot(
            kp = kp.getDouble("Kp"),
            kpObservedAtMs = parseUtc(kp.getString("time_tag")),
            solarFluxSfu = flux.getDouble("flux"),
            solarFluxObservedAtMs = parseUtc(flux.getString("time_tag"))
        )
    }.getOrNull()

    private fun parseUtc(value: String): Long = Instant.parse(if (value.endsWith("Z")) value else "${value}Z").toEpochMilli()
}

interface SpaceWeatherSource {
    fun fetch(): SpaceWeatherSnapshot?
}

internal class NoaaSpaceWeatherSource : SpaceWeatherSource {
    override fun fetch(): SpaceWeatherSnapshot? = runCatching {
        val kp = get(KP_URL)
        val flux = get(SOLAR_FLUX_URL)
        SpaceWeatherSnapshotParser.parse(kp, flux)
    }.getOrNull()

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_500
            readTimeout = 2_500
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Apophenia-Android/0.3")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private companion object {
        const val KP_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"
        const val SOLAR_FLUX_URL = "https://services.swpc.noaa.gov/products/summary/10cm-flux.json"
    }
}

class GroundContextProvider(
    private val context: Context,
    private val spaceWeatherSource: SpaceWeatherSource = NoaaSpaceWeatherSource()
) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val gate = HardwareGates.Gate.LIVE_GROUND_CONTEXT_CAPTURE
        if (!HardwareGates.isAuthorized(context, gate)) return emptyList()
        val now = System.currentTimeMillis()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return simulation(now, observationId, isControl)
        }
        val captureId = captureId(now, observationId, isControl)
        val sensor = collectSensors()
        val location = lastLocation()
        return buildList {
            sensor.pressureHpa?.let { pressure ->
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_pressure_hpa", pressure, "hPa", "normalized_to_hpa=true"))
                pressureTrend(now, pressure)?.let { trend ->
                    add(sample(now, observationId, isControl, captureId, "android_ground", "ground_pressure_trend_hpa_per_hour", trend, "hPa/hour"))
                }
            }
            sensor.magneticUt?.let { (x, y, z) ->
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_magnetic_x_ut", x, "uT"))
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_magnetic_y_ut", y, "uT"))
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_magnetic_z_ut", z, "uT"))
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_magnetic_magnitude_ut", GroundContextMath.magneticMagnitudeUt(x, y, z), "uT"))
            }
            location?.let { loc ->
                val field = GeomagneticField(loc.latitude.toFloat(), loc.longitude.toFloat(), loc.altitude.toFloat(), now)
                add(sample(now, observationId, isControl, captureId, "android_ground", "ground_magnetic_declination_deg", field.declination.toDouble(), "deg", "wmm_platform_model=true"))
                val elevation = SolarPhaseCalculator.elevationDegrees(now, loc.latitude, loc.longitude)
                val phase = when {
                    elevation >= 0.0 -> "day"
                    elevation >= -6.0 -> "civil_twilight"
                    elevation >= -12.0 -> "nautical_twilight"
                    elevation >= -18.0 -> "astronomical_twilight"
                    else -> "night"
                }
                add(sample(now, observationId, isControl, captureId, "local_solar", "ground_solar_elevation_deg", elevation, "deg", "phase=$phase;computed_locally=true"))
            }
            if (HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_ENVIRONMENT_LOOKUP)) {
                cachedSpaceWeather(now)?.let { space ->
                    add(sample(now, observationId, isControl, captureId, "noaa_swpc", "space_weather_kp", space.kp, "index", "observed_at_ms=${space.kpObservedAtMs};public_network_lookup=true"))
                    add(sample(now, observationId, isControl, captureId, "noaa_swpc", "space_weather_f107_sfu", space.solarFluxSfu, "sfu", "observed_at_ms=${space.solarFluxObservedAtMs};public_network_lookup=true"))
                }
            }
        }
    }

    private data class GroundSensorSnapshot(val pressureHpa: Double?, val magneticUt: Triple<Double, Double, Double>?)

    private fun collectSensors(windowMs: Long = 450L): GroundSensorSnapshot {
        val manager = context.getSystemService(SensorManager::class.java) ?: return GroundSensorSnapshot(null, null)
        var pressure: Double? = null
        var magnetic: Triple<Double, Double, Double>? = null
        val thread = HandlerThread("apophenia-ground-snapshot").apply { start() }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_PRESSURE -> pressure = event.values.firstOrNull()?.toDouble()?.let(GroundContextMath::pressureHpa)
                    Sensor.TYPE_MAGNETIC_FIELD -> if (event.values.size >= 3) magnetic = Triple(event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        try {
            val handler = Handler(thread.looper)
            manager.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
            Thread.sleep(windowMs)
        } finally {
            manager.unregisterListener(listener)
            thread.quitSafely()
        }
        return GroundSensorSnapshot(pressure, magnetic)
    }

    private fun pressureTrend(now: Long, pressureHpa: Double): Double? {
        val prefs = context.getSharedPreferences("ground_context", Context.MODE_PRIVATE)
        val previousAt = prefs.getLong("pressure_at", 0L)
        val previous = Double.fromBits(prefs.getLong("pressure_bits", Double.NaN.toBits()))
        prefs.edit().putLong("pressure_at", now).putLong("pressure_bits", pressureHpa.toBits()).apply()
        return if (previous.isFinite()) GroundContextMath.trendHpaPerHour(previous, previousAt, pressureHpa, now) else null
    }

    private fun cachedSpaceWeather(now: Long): SpaceWeatherSnapshot? {
        val prefs = context.getSharedPreferences("ground_context", Context.MODE_PRIVATE)
        val fetchedAt = prefs.getLong("space_fetched_at", 0L)
        if (now - fetchedAt in 0..SPACE_CACHE_MS && prefs.contains("space_kp_bits")) {
            return SpaceWeatherSnapshot(
                Double.fromBits(prefs.getLong("space_kp_bits", 0L)), prefs.getLong("space_kp_at", 0L),
                Double.fromBits(prefs.getLong("space_flux_bits", 0L)), prefs.getLong("space_flux_at", 0L)
            )
        }
        return spaceWeatherSource.fetch()?.also { snapshot ->
            prefs.edit()
                .putLong("space_fetched_at", now)
                .putLong("space_kp_bits", snapshot.kp.toBits()).putLong("space_kp_at", snapshot.kpObservedAtMs)
                .putLong("space_flux_bits", snapshot.solarFluxSfu.toBits()).putLong("space_flux_at", snapshot.solarFluxObservedAtMs)
                .apply()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull(Location::getTime)
    }

    private fun simulation(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> {
        val captureId = captureId(now, observationId, isControl)
        return listOf(
            sample(now, observationId, isControl, captureId, "simulation/ground", "ground_pressure_hpa", 1007.8, "hPa", "normalized_to_hpa=true"),
            sample(now, observationId, isControl, captureId, "simulation/ground", "ground_pressure_trend_hpa_per_hour", -0.7, "hPa/hour"),
            sample(now, observationId, isControl, captureId, "simulation/ground", "ground_magnetic_magnitude_ut", 47.2, "uT"),
            sample(now, observationId, isControl, captureId, "simulation/ground", "ground_magnetic_declination_deg", -3.8, "deg", "wmm_platform_model=true"),
            sample(now, observationId, isControl, captureId, "simulation/ground", "ground_solar_elevation_deg", 18.0, "deg", "phase=day;computed_locally=true"),
            sample(now, observationId, isControl, captureId, "simulation/noaa_swpc", "space_weather_kp", 2.33, "index", "public_network_lookup=simulated"),
            sample(now, observationId, isControl, captureId, "simulation/noaa_swpc", "space_weather_f107_sfu", 142.0, "sfu", "public_network_lookup=simulated")
        )
    }

    private fun sample(
        now: Long, observationId: Long?, isControl: Boolean, captureId: String,
        source: String, metric: String, value: Double, unit: String, metadata: String = ""
    ) = ContextSample(
        timestampMs = now, observationId = observationId, isControl = isControl, source = source,
        metric = metric, value = value, unit = unit,
        metadata = listOf(metadata, "window=instant").filter(String::isNotBlank).joinToString(";"),
        captureId = captureId
    )

    private fun captureId(now: Long, observationId: Long?, isControl: Boolean): String = when {
        isControl -> "control:ground:$now"
        observationId != null -> "event:$observationId:ground:$now"
        else -> "ground:$now"
    }

    private companion object {
        const val SPACE_CACHE_MS = 15L * 60L * 1_000L
    }
}
