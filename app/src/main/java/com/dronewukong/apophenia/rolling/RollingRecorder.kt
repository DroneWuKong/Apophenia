package com.dronewukong.apophenia.rolling

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.hardware.DeviceContextCollector
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.hardware.SensorSnapshotCollector
import com.dronewukong.apophenia.ui.MainActivity
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object RollingRecorderConfig {
    const val PRE_WINDOW_MS = 30L * 60L * 1000L
    const val POST_WINDOW_MS = 30L * 60L * 1000L
    const val RETENTION_MS = 90L * 60L * 1000L
    const val SENSOR_PERIOD_SEC = 15L
    const val DEVICE_PERIOD_SEC = 60L
}

object RollingRecorderState {
    private const val PREF = "rolling_recorder"
    private const val ENABLED = "enabled"
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
}

data class RollingRecorderDiagnostics(
    val lastStartAtMs: Long = 0L,
    val lastSampleAtMs: Long = 0L,
    val lastSensorSampleAtMs: Long = 0L,
    val lastDeviceSampleAtMs: Long = 0L,
    val capturedSampleCount: Long = 0L,
    val failureCount: Long = 0L,
    val lastError: String = ""
)

object RollingRecorderHealth {
    private const val PREF = "rolling_recorder_health"

    fun snapshot(context: Context): RollingRecorderDiagnostics {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return RollingRecorderDiagnostics(
            lastStartAtMs = prefs.getLong("last_start_at", 0L),
            lastSampleAtMs = prefs.getLong("last_sample_at", 0L),
            lastSensorSampleAtMs = prefs.getLong("last_sensor_at", 0L),
            lastDeviceSampleAtMs = prefs.getLong("last_device_at", 0L),
            capturedSampleCount = prefs.getLong("sample_count", 0L),
            failureCount = prefs.getLong("failure_count", 0L),
            lastError = prefs.getString("last_error", "").orEmpty()
        )
    }

    @Synchronized
    fun recordStarted(context: Context, atMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putLong("last_start_at", atMs)
            .remove("last_error")
            .apply()
    }

    @Synchronized
    fun recordBatch(context: Context, source: String, sampleCount: Int, atMs: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("last_sample_at", atMs)
            .putLong(if (source == "sensor") "last_sensor_at" else "last_device_at", atMs)
            .putLong("sample_count", prefs.getLong("sample_count", 0L) + sampleCount)
            .apply()
    }

    @Synchronized
    fun recordFailure(context: Context, error: Throwable, atMs: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("failure_count", prefs.getLong("failure_count", 0L) + 1L)
            .putLong("last_failure_at", atMs)
            .putString("last_error", error.message ?: error.javaClass.simpleName)
            .apply()
    }

    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

class RollingRecorderService : Service() {
    private val scheduler = Executors.newScheduledThreadPool(1)
    private var sensorTask: ScheduledFuture<*>? = null
    private var deviceTask: ScheduledFuture<*>? = null

    override fun onCreate() { super.onCreate(); createChannel() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_STOP -> { RollingRecorderState.setEnabled(this, false); stopSelf(); return START_NOT_STICKY }
            ACTION_START -> {
                val foregroundType = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
                val started = runCatching {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), foregroundType)
                }.isSuccess
                if (!started) {
                    RollingRecorderHealth.recordFailure(this, IllegalStateException("Android refused foreground recorder startup"))
                    RollingRecorderState.setEnabled(this, false)
                    stopSelf()
                    return START_NOT_STICKY
                }
                RollingRecorderState.setEnabled(this, true)
                RollingRecorderHealth.recordStarted(this)
                startSampling()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() { sensorTask?.cancel(true); deviceTask?.cancel(true); scheduler.shutdownNow(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSampling() {
        if (sensorTask == null || sensorTask?.isCancelled == true) {
            sensorTask = scheduler.scheduleWithFixedDelay({
                runCatching {
                    val samples = SensorSnapshotCollector(applicationContext).collect(null, false, windowMs = 300)
                        .map { it.copy(source = "buffer/${it.source}", metadata = appendMetadata(it.metadata, "rolling=true")) }
                    val db = ObservationDb(applicationContext)
                    db.insertRolling(samples, RollingRecorderConfig.RETENTION_MS)
                    db.captureActivePostWindows(System.currentTimeMillis(), RollingRecorderConfig.POST_WINDOW_MS)
                    samples.size
                }.onSuccess { RollingRecorderHealth.recordBatch(applicationContext, "sensor", it) }
                    .onFailure { RollingRecorderHealth.recordFailure(applicationContext, it) }
            }, 0, RollingRecorderConfig.SENSOR_PERIOD_SEC, TimeUnit.SECONDS)
        }
        if (deviceTask == null || deviceTask?.isCancelled == true) {
            deviceTask = scheduler.scheduleWithFixedDelay({
                runCatching {
                    val samples = DeviceContextCollector(applicationContext).collect(null, false)
                        .map { it.copy(source = "buffer/${it.source}", metadata = appendMetadata(it.metadata, "rolling=true")) }
                    val db = ObservationDb(applicationContext)
                    db.insertRolling(samples, RollingRecorderConfig.RETENTION_MS)
                    db.captureActivePostWindows(System.currentTimeMillis(), RollingRecorderConfig.POST_WINDOW_MS)
                    samples.size
                }.onSuccess { RollingRecorderHealth.recordBatch(applicationContext, "device", it) }
                    .onFailure { RollingRecorderHealth.recordFailure(applicationContext, it) }
            }, 0, RollingRecorderConfig.DEVICE_PERIOD_SEC, TimeUnit.SECONDS)
        }
    }

    private fun appendMetadata(existing: String, value: String) = if (existing.isBlank()) value else "$existing;$value"

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL_ID, "Apophenia black box", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, RollingRecorderService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mode = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) "simulation" else "live sensors"
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_weird).setContentTitle("Apophenia black box active").setContentText("30 min rolling buffer · $mode").setContentIntent(open).setOngoing(true).addAction(0, "Stop", stop).build()
    }

    companion object {
        private const val CHANNEL_ID = "apophenia_black_box"; private const val NOTIFICATION_ID = 42
        const val ACTION_START = "com.dronewukong.apophenia.rolling.START"; const val ACTION_STOP = "com.dronewukong.apophenia.rolling.STOP"
        fun setEnabled(context: Context, enabled: Boolean) {
            RollingRecorderState.setEnabled(context, enabled)
            val intent = Intent(context, RollingRecorderService::class.java).setAction(if (enabled) ACTION_START else ACTION_STOP)
            if (enabled) ContextCompat.startForegroundService(context, intent) else context.startService(intent)
        }
    }
}
