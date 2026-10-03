package com.dronewukong.apophenia.control

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
import com.dronewukong.apophenia.mavlink.UsbSerialMavlinkTransport
import com.dronewukong.apophenia.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ControlLinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reader: UsbSerialMavlinkTransport? = null
    private var job: Job? = null
    @Volatile private var operatorStop = false

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Control-link capture", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(NOTIFICATION_ID, notification("Waiting for link frames"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            operatorStop = true; reader?.close(); ControlLinkManager.stop(); stopSelf(); return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val protocol = runCatching { ControlLinkProtocol.valueOf(intent?.getStringExtra(EXTRA_PROTOCOL).orEmpty()) }.getOrNull()
            ?: run { stopSelf(); return START_NOT_STICKY }
        val simulation = intent?.getBooleanExtra(EXTRA_SIMULATION, false) == true
        val deviceId = intent?.getIntExtra(EXTRA_DEVICE_ID, -1) ?: -1
        val baud = intent?.getIntExtra(EXTRA_BAUD, protocol.defaultBaud) ?: protocol.defaultBaud
        if (ControlLinkManager.arm(this, protocol, if (simulation) "simulation" else "usb:$baud").isFailure) {
            stopSelf(); return START_NOT_STICKY
        }
        job = scope.launch {
            runCatching {
                if (simulation) {
                    ControlLinkManager.ingest(this@ControlLinkService, ControlLinkManager.simulationBytes(protocol))
                    updateNotification("${protocol.displayName} · SIMULATION")
                    while (isActive && ControlLinkManager.state.value.active) delay(1_000)
                } else {
                    reader = UsbSerialMavlinkTransport(this@ControlLinkService, deviceId, baud)
                    updateNotification("${protocol.displayName} · USB $baud baud")
                    reader?.readLoop { ControlLinkManager.ingest(this@ControlLinkService, it) }
                    if (!operatorStop && ControlLinkManager.state.value.active) ControlLinkManager.fail("Control-link transport ended")
                }
            }.onFailure { if (ControlLinkManager.state.value.active) ControlLinkManager.fail(it.message ?: "Control-link transport failed") }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reader?.close(); reader = null; job?.cancel(); scope.cancel()
        val error = ControlLinkManager.state.value.lastError
        if (!operatorStop && ControlLinkManager.state.value.active) ControlLinkManager.stop()
        if (!error.isNullOrBlank()) ControlLinkManager.fail(error)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ControlLinkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_weird).setContentTitle("Control-link capture active")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "End capture", stop).build()
    }

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) ?: Unit

    companion object {
        private const val CHANNEL_ID = "control_link_capture"
        private const val NOTIFICATION_ID = 3304
        private const val ACTION_STOP = "com.dronewukong.apophenia.control.STOP"
        private const val EXTRA_PROTOCOL = "protocol"
        private const val EXTRA_DEVICE_ID = "device_id"
        private const val EXTRA_BAUD = "baud"
        private const val EXTRA_SIMULATION = "simulation"

        fun start(context: Context, protocol: ControlLinkProtocol, deviceId: Int, baud: Int, simulation: Boolean = false) {
            ContextCompat.startForegroundService(context, Intent(context, ControlLinkService::class.java)
                .putExtra(EXTRA_PROTOCOL, protocol.name).putExtra(EXTRA_DEVICE_ID, deviceId).putExtra(EXTRA_BAUD, baud).putExtra(EXTRA_SIMULATION, simulation))
        }
        fun stop(context: Context) { context.startService(Intent(context, ControlLinkService::class.java).setAction(ACTION_STOP)) }
    }
}
