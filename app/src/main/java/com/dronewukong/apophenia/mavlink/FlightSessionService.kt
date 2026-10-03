package com.dronewukong.apophenia.mavlink

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
import com.dronewukong.apophenia.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Persistent capture indicator and transport owner for an armed MAVLink/FLIGHT_SESSION. */
class FlightSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transport: MavlinkTransport? = null
    private var readJob: Job? = null
    @Volatile private var operatorStop = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Waiting for a valid airframe heartbeat"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            operatorStop = true
            transport?.close()
            MavlinkSessionManager.stop(this, reason = "notification_stop")
            stopSelf()
            return START_NOT_STICKY
        }
        if (readJob?.isActive == true) return START_NOT_STICKY
        val endpoint = endpointFrom(intent) ?: run {
            MavlinkSessionManager.fail("Missing MAVLink endpoint")
            stopSelf()
            return START_NOT_STICKY
        }
        val armed = MavlinkSessionManager.arm(this, endpoint.label)
        if (armed.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        readJob = scope.launch {
            runCatching {
                if (endpoint == MavlinkEndpoint.Simulation) {
                    MavlinkSessionManager.ingest(this@FlightSessionService, MavlinkSessionManager.simulationStream())
                    updateNotification("SIMULATION airframe connected")
                    while (isActive && MavlinkSessionManager.isArmed()) delay(1_000)
                } else {
                    transport = when (endpoint) {
                        is MavlinkEndpoint.Tcp -> TcpMavlinkTransport(endpoint.host, endpoint.port)
                        is MavlinkEndpoint.Udp -> UdpMavlinkTransport(endpoint.port)
                        is MavlinkEndpoint.Usb -> UsbSerialMavlinkTransport(this@FlightSessionService, endpoint.deviceId, endpoint.baud)
                        MavlinkEndpoint.Simulation -> error("unreachable")
                    }
                    updateNotification("${endpoint.label} · capture live")
                    transport?.readLoop { bytes -> MavlinkSessionManager.ingest(this@FlightSessionService, bytes) }
                    if (!operatorStop && MavlinkSessionManager.isArmed()) {
                        MavlinkSessionManager.fail("MAVLink transport ended")
                    }
                }
            }.onFailure { error ->
                if (MavlinkSessionManager.isArmed()) MavlinkSessionManager.fail(error.message ?: "MAVLink transport failed")
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        transport?.close()
        transport = null
        readJob?.cancel()
        scope.cancel()
        val terminalError = MavlinkSessionManager.state.value.lastError
        if (!operatorStop && MavlinkSessionManager.isArmed()) {
            MavlinkSessionManager.stop(this, interrupted = true, reason = "transport_or_service_ended")
        }
        if (!terminalError.isNullOrBlank()) MavlinkSessionManager.fail(terminalError)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FlightSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_weird)
            .setContentTitle("Flight session capture active")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "End session", stop)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Flight session capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun endpointFrom(intent: Intent?): MavlinkEndpoint? = when (intent?.getStringExtra(EXTRA_TYPE)) {
        "udp" -> MavlinkEndpoint.Udp(intent.getIntExtra(EXTRA_PORT, 14550))
        "tcp" -> MavlinkEndpoint.Tcp(intent.getStringExtra(EXTRA_HOST).orEmpty(), intent.getIntExtra(EXTRA_PORT, 5760))
        "usb" -> MavlinkEndpoint.Usb(intent.getIntExtra(EXTRA_DEVICE_ID, -1), intent.getIntExtra(EXTRA_BAUD, 57_600))
        "simulation" -> MavlinkEndpoint.Simulation
        else -> null
    }

    companion object {
        private const val CHANNEL_ID = "flight_session_capture"
        private const val NOTIFICATION_ID = 3303
        private const val ACTION_STOP = "com.dronewukong.apophenia.mavlink.STOP_FLIGHT_SESSION"
        private const val EXTRA_TYPE = "endpoint_type"
        private const val EXTRA_HOST = "endpoint_host"
        private const val EXTRA_PORT = "endpoint_port"
        private const val EXTRA_DEVICE_ID = "usb_device_id"
        private const val EXTRA_BAUD = "usb_baud"

        fun start(context: Context, endpoint: MavlinkEndpoint) {
            val intent = Intent(context, FlightSessionService::class.java)
            when (endpoint) {
                is MavlinkEndpoint.Udp -> intent.putExtra(EXTRA_TYPE, "udp").putExtra(EXTRA_PORT, endpoint.port)
                is MavlinkEndpoint.Tcp -> intent.putExtra(EXTRA_TYPE, "tcp").putExtra(EXTRA_HOST, endpoint.host).putExtra(EXTRA_PORT, endpoint.port)
                is MavlinkEndpoint.Usb -> intent.putExtra(EXTRA_TYPE, "usb").putExtra(EXTRA_DEVICE_ID, endpoint.deviceId).putExtra(EXTRA_BAUD, endpoint.baud)
                MavlinkEndpoint.Simulation -> intent.putExtra(EXTRA_TYPE, "simulation")
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, FlightSessionService::class.java).setAction(ACTION_STOP))
        }
    }
}
