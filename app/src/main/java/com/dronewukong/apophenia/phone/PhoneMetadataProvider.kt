package com.dronewukong.apophenia.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

class PhoneMetadataProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val now = System.currentTimeMillis()
        val enabled = HardwareGates.Gate.entries.filter { gate ->
            gate in supportedGates && HardwareGates.isAuthorized(context, gate)
        }
        if (enabled.isEmpty()) return emptyList()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return enabled.flatMap { simulation(it, now, observationId, isControl) }
        }
        return buildList {
            if (HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE in enabled) addAll(audio(now, observationId, isControl))
            if (HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE in enabled) addAll(display(now, observationId, isControl))
            if (HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE in enabled) addAll(power(now, observationId, isControl))
            if (HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE in enabled) addAll(time(now, observationId, isControl))
        }
    }

    private fun simulation(
        gate: HardwareGates.Gate,
        now: Long,
        observationId: Long?,
        isControl: Boolean
    ): List<ContextSample> = when (gate) {
        HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE -> listOf(
            sample(now, observationId, isControl, "simulation/audio", "audio_output_device", 2.0, "type_code", "type=speaker"),
            sample(now, observationId, isControl, "simulation/audio", "audio_ringer_mode", 2.0, "mode"),
            sample(now, observationId, isControl, "simulation/audio", "audio_music_volume_pct", 42.0, "percent"),
            sample(now, observationId, isControl, "simulation/audio", "audio_active_playback_count", 1.0, "count", "app_identity=platform_restricted"),
            sample(now, observationId, isControl, "simulation/audio", "audio_focus_observer_available", 0.0, "bool", "reason=platform_restricted")
        )
        HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE -> listOf(
            sample(now, observationId, isControl, "simulation/display", "screen_interactive", 1.0, "bool"),
            sample(now, observationId, isControl, "simulation/display", "screen_brightness", 128.0, "0-255"),
            sample(now, observationId, isControl, "simulation/display", "night_light_active", 1.0, "bool"),
            sample(now, observationId, isControl, "simulation/display", "notification_count_visible", 3.0, "count", "scope=notification_listener"),
            sample(now, observationId, isControl, "simulation/display", "keyboard_accepting_text_in_app", 0.0, "bool")
        )
        HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE -> listOf(
            sample(now, observationId, isControl, "simulation/power", "battery_level_pct", 64.0, "percent"),
            sample(now, observationId, isControl, "simulation/power", "battery_charging", 1.0, "bool"),
            sample(now, observationId, isControl, "simulation/power", "thermal_status", 1.0, "status"),
            sample(now, observationId, isControl, "simulation/power", "ram_available_pct", 38.0, "percent"),
            sample(now, observationId, isControl, "simulation/power", "cpu_load_1m", 0.42, "load")
        )
        HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE -> listOf(
            sample(now, observationId, isControl, "simulation/time", "timezone_offset_min", -300.0, "minutes", "zone=America/Chicago"),
            sample(now, observationId, isControl, "simulation/time", "weekend", 0.0, "bool"),
            sample(now, observationId, isControl, "simulation/time", "day_part", 2.0, "bucket", "name=afternoon"),
            sample(now, observationId, isControl, "simulation/time", "solar_elevation_deg", 24.0, "degrees", "phase=day")
        )
        else -> emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun audio(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return buildList {
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }.getOrDefault(emptyArray()).forEach { device ->
                add(sample(now, observationId, isControl, "android_audio", "audio_output_device", device.type.toDouble(), "type_code", "type=${audioType(device.type)}"))
            }
            add(sample(now, observationId, isControl, "android_audio", "audio_ringer_mode", manager.ringerMode.toDouble(), "mode"))
            val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
            add(sample(now, observationId, isControl, "android_audio", "audio_music_volume_pct", current * 100.0 / max, "percent"))
            val activePlaybackCount = runCatching { manager.activePlaybackConfigurations.size }.getOrDefault(0)
            add(
                sample(
                    now,
                    observationId,
                    isControl,
                    "android_audio",
                    "audio_active_playback_count",
                    activePlaybackCount.toDouble(),
                    "count",
                    "app_identity=platform_restricted"
                )
            )
            add(sample(now, observationId, isControl, "android_audio", "audio_focus_observer_available", 0.0, "bool", "reason=platform_restricted;scope=other_apps"))
        }
    }

    private fun display(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> = buildList {
        val power = context.getSystemService(PowerManager::class.java)
        add(sample(now, observationId, isControl, "android_display", "screen_interactive", if (power?.isInteractive == true) 1.0 else 0.0, "bool"))
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()?.let {
            add(sample(now, observationId, isControl, "android_display", "screen_brightness", it.toDouble(), "0-255"))
        }
        runCatching { Settings.Secure.getInt(context.contentResolver, "night_display_activated") }.getOrNull()?.let {
            add(sample(now, observationId, isControl, "android_display", "night_light_active", if (it == 1) 1.0 else 0.0, "bool"))
        }
        val visibleNotifications = NotificationCaptureService.activeSnapshot()?.size
        val ownNotifications = runCatching { context.getSystemService(NotificationManager::class.java)?.activeNotifications?.size }.getOrNull()
        (visibleNotifications ?: ownNotifications)?.let {
            val scope = if (visibleNotifications != null) "notification_listener" else "own_app_only"
            add(sample(now, observationId, isControl, "android_display", "notification_count_visible", it.toDouble(), "count", "scope=$scope;contents_not_stored=true"))
        }
        val keyboard = context.getSystemService(InputMethodManager::class.java)
        add(sample(now, observationId, isControl, "android_display", "keyboard_accepting_text_in_app", if (keyboard?.isAcceptingText == true) 1.0 else 0.0, "bool"))
        activePackage(now)?.let {
            add(sample(now, observationId, isControl, "android_display", "active_app_package", 1.0, "count", "package=${sanitize(it)}"))
        }
    }

    private fun power(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> = buildList {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        level?.let {
            add(sample(now, observationId, isControl, "android_power", "battery_level_pct", it.toDouble(), "percent"))
            batteryRate(now, it)?.let { rate ->
                add(sample(now, observationId, isControl, "android_power", "battery_rate_pct_per_hour", rate, "percent/hour"))
            }
        }
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Int.MIN_VALUE }?.let {
            add(sample(now, observationId, isControl, "android_power", "battery_current_ua", it.toDouble(), "uA"))
        }
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        add(sample(now, observationId, isControl, "android_power", "battery_charging", if (charging) 1.0 else 0.0, "bool"))
        if (Build.VERSION.SDK_INT >= 29) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus?.let {
                add(sample(now, observationId, isControl, "android_power", "thermal_status", it.toDouble(), "status"))
            }
        }
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memory)
        if (memory.totalMem > 0) {
            add(sample(now, observationId, isControl, "android_power", "ram_available_pct", memory.availMem * 100.0 / memory.totalMem, "percent", "low_memory=${memory.lowMemory}"))
        }
        runCatching { java.io.File("/proc/loadavg").readText().substringBefore(' ').toDouble() }.getOrNull()?.let {
            add(sample(now, observationId, isControl, "android_power", "cpu_load_1m", it, "load"))
        }
    }

    private fun time(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> = buildList {
        val dateTime = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        val offsetMinutes = dateTime.offset.totalSeconds / 60
        val weekend = dateTime.dayOfWeek.value >= 6
        val (dayPart, dayPartName) = when (dateTime.hour) {
            in 5..10 -> 0 to "morning"
            in 11..13 -> 1 to "midday"
            in 14..17 -> 2 to "afternoon"
            in 18..21 -> 3 to "evening"
            else -> 4 to "night"
        }
        add(sample(now, observationId, isControl, "android_time", "timezone_offset_min", offsetMinutes.toDouble(), "minutes", "zone=${sanitize(dateTime.zone.id)}"))
        add(sample(now, observationId, isControl, "android_time", "weekday", dateTime.dayOfWeek.value.toDouble(), "iso_day"))
        add(sample(now, observationId, isControl, "android_time", "weekend", if (weekend) 1.0 else 0.0, "bool"))
        add(sample(now, observationId, isControl, "android_time", "day_part", dayPart.toDouble(), "bucket", "name=$dayPartName"))
        lastLocation()?.let { location ->
            val elevation = SolarPhaseCalculator.elevationDegrees(now, location.latitude, location.longitude)
            val phase = when {
                elevation >= 0.0 -> "day"
                elevation >= -6.0 -> "civil_twilight"
                else -> "night"
            }
            add(sample(now, observationId, isControl, "android_time", "solar_elevation_deg", elevation, "degrees", "phase=$phase;computed_locally=true"))
        }
    }

    private fun sample(
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean,
        source: String,
        metric: String,
        value: Double,
        unit: String,
        metadata: String = ""
    ): ContextSample = ContextSample(
        timestampMs = timestampMs,
        observationId = observationId,
        isControl = isControl,
        source = source,
        metric = metric,
        value = value,
        unit = unit,
        metadata = listOf(metadata, "window=instant").filter(String::isNotBlank).joinToString(";"),
        captureId = observationId?.let { "event:$it:instant" }.orEmpty()
    )

    private fun batteryRate(now: Long, level: Int): Double? {
        val prefs = context.getSharedPreferences("power_context", Context.MODE_PRIVATE)
        val previousAt = prefs.getLong("battery_at", 0L)
        val previousLevel = prefs.getInt("battery_level", -1)
        prefs.edit().putLong("battery_at", now).putInt("battery_level", level).apply()
        val elapsedHours = (now - previousAt) / 3_600_000.0
        return if (previousLevel in 0..100 && elapsedHours >= 1.0 / 12.0) {
            (level - previousLevel) / elapsedHours
        } else {
            null
        }
    }

    private fun activePackage(now: Long): String? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = runCatching { manager.queryEvents(now - 30_000L, now) }.getOrNull() ?: return null
        val event = UsageEvents.Event()
        var latest: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val movedToForeground = if (Build.VERSION.SDK_INT >= 29) {
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            } else {
                @Suppress("DEPRECATION")
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            }
            if (movedToForeground) latest = event.packageName
        }
        return latest
    }

    @SuppressLint("MissingPermission")
    private fun lastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        return manager.getProviders(true).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }
    }

    private fun audioType(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        else -> "other_$type"
    }

    private fun sanitize(value: String): String = value.replace(';', '_').replace('=', '_').take(160)

    companion object {
        private val supportedGates = setOf(
            HardwareGates.Gate.LIVE_AUDIO_METADATA_CAPTURE,
            HardwareGates.Gate.LIVE_DISPLAY_INTERACTION_CAPTURE,
            HardwareGates.Gate.LIVE_POWER_THERMAL_CAPTURE,
            HardwareGates.Gate.LIVE_TIME_CONTEXT_CAPTURE
        )
    }
}

object SolarPhaseCalculator {
    fun elevationDegrees(timestampMs: Long, latitude: Double, longitude: Double): Double {
        val utc = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.of("UTC"))
        val gamma = 2.0 * PI / 365.0 * (utc.dayOfYear - 1 + (utc.hour - 12) / 24.0)
        val equationMinutes = 229.18 * (
            0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
            )
        val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
            0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
            0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
        val minutes = utc.hour * 60.0 + utc.minute + utc.second / 60.0
        val trueSolarMinutes = (minutes + equationMinutes + 4.0 * longitude).mod(1_440.0)
        val hourAngle = Math.toRadians(trueSolarMinutes / 4.0 - 180.0)
        val latitudeRad = Math.toRadians(latitude.coerceIn(-90.0, 90.0))
        val zenith = acos(
            (sin(latitudeRad) * sin(declination) + cos(latitudeRad) * cos(declination) * cos(hourAngle))
                .coerceIn(-1.0, 1.0)
        )
        return Math.toDegrees(asin(cos(zenith))).coerceIn(-90.0, 90.0)
    }
}
