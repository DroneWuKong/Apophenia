package com.dronewukong.apophenia.audio

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.presets.EvidencePresetManager
import com.dronewukong.apophenia.ui.MainActivity
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

class AudioRingCaptureService : Service() {
    @Volatile private var running = false
    @Volatile private var operatorStop = false
    private var captureThread: Thread? = null
    private var recorder: AudioRecord? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Audio ring capture", NotificationManager.IMPORTANCE_LOW)
        )
        runCatching { MediaRetentionManager(this).purgeExpired() }
        startForeground(NOTIFICATION_ID, notification("Microphone ring is starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            operatorStop = true
            stopCapture()
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY
        if (!HardwareGates.isAuthorized(this, HardwareGates.Gate.LIVE_AUDIO_CAPTURE)) {
            AudioRingCaptureManager.fail("LIVE_AUDIO_CAPTURE gate is off")
            stopSelf()
            return START_NOT_STICKY
        }
        val simulation = HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION
        if (!simulation && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AudioRingCaptureManager.fail("Microphone permission denied")
            stopSelf()
            return START_NOT_STICKY
        }
        val preSeconds = EvidencePresetManager.audioPreSeconds(this)
        AudioRingCaptureManager.arm(this, simulation, preSeconds = preSeconds)
        running = true
        captureThread = thread(name = "apophenia-audio-ring") {
            runCatching { if (simulation) simulationLoop() else microphoneLoop() }
                .onFailure { if (!operatorStop) AudioRingCaptureManager.fail(it.message ?: "Audio capture failed") }
            if (!operatorStop) stopSelf()
        }
        updateNotification(if (simulation) "SIMULATION · ${preSeconds}s pre / 30s post" else "Microphone · ${preSeconds}s pre / 30s post")
        return START_STICKY
    }

    @Suppress("MissingPermission")
    private fun microphoneLoop() {
        val minimum = AudioRecord.getMinBufferSize(
            AudioRingCaptureManager.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "No supported 16 kHz mono PCM microphone path" }
        fun create(source: Int) = AudioRecord(
            source, AudioRingCaptureManager.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 8_192)
        )
        var audioRecord = create(MediaRecorder.AudioSource.UNPROCESSED)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            audioRecord = create(MediaRecorder.AudioSource.MIC)
        }
        recorder = audioRecord
        check(audioRecord.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not initialize" }
        val buffer = ByteArray(maxOf(minimum, 8_192))
        audioRecord.startRecording()
        while (running) {
            val read = audioRecord.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read > 0) AudioRingCaptureManager.ingest(buffer, read)
            else if (read < 0) error("Microphone read failed: $read")
        }
    }

    private fun simulationLoop() {
        val samples = AudioRingCaptureManager.SAMPLE_RATE_HZ / 10
        val buffer = ByteArray(samples * 2)
        var offset = 0L
        while (running) {
            repeat(samples) { index ->
                val time = (offset + index) / AudioRingCaptureManager.SAMPLE_RATE_HZ.toDouble()
                val value = (7_000.0 * sin(2.0 * PI * 60.0 * time) + 2_000.0 * sin(2.0 * PI * 1_000.0 * time)).toInt().toShort()
                buffer[index * 2] = (value.toInt() and 0xff).toByte()
                buffer[index * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
            }
            AudioRingCaptureManager.ingest(buffer)
            offset += samples
            Thread.sleep(100)
        }
    }

    private fun stopCapture() {
        running = false
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        captureThread?.interrupt()
        captureThread = null
        AudioRingCaptureManager.stop()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AudioRingCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_weird)
            .setContentTitle("Audio ring buffering live")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Disarm", stop)
            .build()
    }

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) ?: Unit

    companion object {
        private const val CHANNEL_ID = "audio_ring_capture"
        private const val NOTIFICATION_ID = 3305
        private const val ACTION_STOP = "com.dronewukong.apophenia.audio.STOP"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, AudioRingCaptureService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, AudioRingCaptureService::class.java).setAction(ACTION_STOP))
    }
}
