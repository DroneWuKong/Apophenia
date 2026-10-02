package com.dronewukong.apophenia.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import java.time.Duration
import java.time.Instant

object HealthConnectAccess {
    const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    )

    fun sdkStatus(context: Context): Int = HealthConnectClient.getSdkStatus(context)

    fun requestablePermissions(context: Context): Set<String> = buildSet {
        addAll(readPermissions)
        val client = HealthConnectClient.getOrCreate(context)
        if (client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        ) {
            add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        }
    }

    suspend fun permissionSummary(context: Context): String = when (sdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> runCatching {
            val granted = HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
            val count = readPermissions.count { it in granted }
            when {
                count == readPermissions.size -> "Connected · $count read permissions"
                count > 0 -> "Partially connected · $count of ${readPermissions.size}"
                else -> "Not connected"
            }
        }.getOrDefault("Available · access status unavailable")
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Update required"
        else -> "Unavailable on this device"
    }

    fun statusText(context: Context): String = when (sdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "Available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Provider update required"
        else -> "Unavailable on this device"
    }
}

class HealthConnectProvider(private val context: Context) {
    suspend fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return simulated(observationId, isControl)
        }
        if (HealthConnectAccess.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) return emptyList()
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val end = Instant.now()
            val recentStart = end.minus(Duration.ofMinutes(30))
            val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
            val oxygenPermission = HealthPermission.getReadPermission(OxygenSaturationRecord::class)
            val dailyMetrics = buildSet<AggregateMetric<*>> {
                if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
                    add(RestingHeartRateRecord.BPM_AVG)
                }
                if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                    add(SleepSessionRecord.SLEEP_DURATION_TOTAL)
                }
                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                    add(StepsRecord.COUNT_TOTAL)
                }
                if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                    add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
                }
            }
            val recent = client.aggregate(
                AggregateRequest(
                    metrics = if (heartPermission in granted) setOf(HeartRateRecord.BPM_AVG) else emptySet(),
                    timeRangeFilter = TimeRangeFilter.between(recentStart, end)
                )
            )
            val daily = client.aggregate(
                AggregateRequest(
                    metrics = dailyMetrics,
                    timeRangeFilter = TimeRangeFilter.between(end.minus(Duration.ofHours(24)), end)
                )
            )
            val oxygenAverage = if (oxygenPermission in granted) {
                client.readRecords(
                    ReadRecordsRequest(
                        recordType = OxygenSaturationRecord::class,
                        timeRangeFilter = TimeRangeFilter.between(recentStart, end)
                    )
                ).records.map { it.percentage.value }.takeIf { it.isNotEmpty() }?.average()
            } else null
            val now = System.currentTimeMillis()
            val out = mutableListOf<ContextSample>()
            fun add(metric: String, value: Number?, unit: String, window: String) {
                if (value != null) out += ContextSample(
                    timestampMs = now, observationId = observationId, isControl = isControl,
                    source = "health_connect", metric = metric, value = value.toDouble(), unit = unit,
                    metadata = "window=$window"
                )
            }
            add("health_heart_rate_avg_bpm", recent[HeartRateRecord.BPM_AVG], "bpm", "30m")
            add("health_spo2_avg_pct", oxygenAverage, "%", "30m")
            add("health_resting_heart_rate_bpm", daily[RestingHeartRateRecord.BPM_AVG], "bpm", "24h")
            add("health_steps_24h", daily[StepsRecord.COUNT_TOTAL], "count", "24h")
            add("health_sleep_hours_24h", daily[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMillis()?.div(3_600_000.0), "h", "24h")
            add("health_active_minutes_24h", daily[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMillis()?.div(60_000.0), "min", "24h")
            out
        }.getOrDefault(emptyList())
    }

    private fun simulated(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val now = System.currentTimeMillis()
        val minute = ((now / 60_000L) % 30).toDouble()
        fun sample(metric: String, value: Double, unit: String, window: String) = ContextSample(
            timestampMs = now, observationId = observationId, isControl = isControl,
            source = "simulation/health_connect", metric = metric, value = value, unit = unit,
            metadata = "window=$window"
        )
        return listOf(
            sample("health_heart_rate_avg_bpm", 68.0 + minute / 10.0, "bpm", "30m"),
            sample("health_spo2_avg_pct", 97.0, "%", "30m"),
            sample("health_resting_heart_rate_bpm", 61.0, "bpm", "24h"),
            sample("health_steps_24h", 4200.0 + minute * 4.0, "count", "24h"),
            sample("health_sleep_hours_24h", 7.4, "h", "24h"),
            sample("health_active_minutes_24h", 34.0, "min", "24h")
        )
    }
}
