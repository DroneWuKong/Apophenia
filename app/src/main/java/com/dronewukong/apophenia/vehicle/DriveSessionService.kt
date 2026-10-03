package com.dronewukong.apophenia.vehicle

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Persistent visible owner of active DRIVE_SESSION sampling. */
class DriveSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var samplingJob: Job? = null
    private var operatorStop = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            operatorStop = true
            DriveSessionManager.stop(this, reason = "notification_stop")
            stopSelf()
            return START_NOT_STICKY
        }
        if (samplingJob?.isActive != true) {
            samplingJob = scope.launch {
                while (isActive && DriveSessionManager.activeId() != null) {
                    val capturedAt = System.currentTimeMillis()
                    val sessionId = DriveSessionManager.activeId() ?: break
                    val samples = DriveSessionManager.collect(this@DriveSessionService, null, false).map { sample ->
                        sample.copy(
                            captureId = "$sessionId:stream:$capturedAt",
                            metadata = listOf(sample.metadata, "session_stream=true").filter(String::isNotBlank).joinToString(";")
                        )
                    }
                    ObservationStore.repository(this@DriveSessionService).db().insertContext(samples)
                    delay(SAMPLE_INTERVAL_MS)
                }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        samplingJob?.cancel()
        scope.cancel()
        if (!operatorStop && DriveSessionManager.activeId() != null) {
            DriveSessionManager.stop(this, interrupted = true, reason = "service_destroyed")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, DriveSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_weird)
            .setContentTitle("Drive session capture active")
            .setContentText("OBD-II and authorized phone context are grouped on one local timeline")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "End session", stop)
            .build()
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Drive session capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        private const val CHANNEL_ID = "drive_session_capture"
        private const val NOTIFICATION_ID = 3302
        private const val SAMPLE_INTERVAL_MS = 10_000L
        private const val ACTION_STOP = "com.dronewukong.apophenia.vehicle.STOP_DRIVE_SESSION"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DriveSessionService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DriveSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
