package com.dronewukong.apophenia.video

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.ui.MainActivity
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {
    private val thread = HandlerThread("apophenia-screen-ring").apply { start() }
    private val handler = Handler(thread.looper)
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var stream = emptySet<String>()
    private var started = false
    @Volatile private var lastFrameAt = 0L

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen ring capture", NotificationManager.IMPORTANCE_LOW)
        )
        runCatching { MediaRetentionManager(this).purgeExpired() }
        startForeground(NOTIFICATION_ID, notification("Screen ring is starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            stream = setOf(STREAM_ID)
            VideoRingCaptureManager.startSource(this, SOURCE, stream)
            var frameNumber = 0
            val task = object : Runnable {
                override fun run() {
                    val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.rgb((frameNumber * 17) % 255, 80, 120))
                    val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
                    bitmap.recycle()
                    VideoRingCaptureManager.ingest(screenFrame(System.currentTimeMillis(), jpeg))
                    frameNumber++
                    handler.postDelayed(this, 500)
                }
            }
            handler.post(task)
            updateNotification("SIMULATION screen · 15s pre / 10s post")
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            VideoRingCaptureManager.fail(SOURCE, "Screen-capture consent unavailable")
            stopSelf(); return START_NOT_STICKY
        }
        val manager = getSystemService(MediaProjectionManager::class.java) ?: run { stopSelf(); return START_NOT_STICKY }
        val mediaProjection = manager.getMediaProjection(resultCode, resultData) ?: run {
            VideoRingCaptureManager.fail(SOURCE, "Android did not provide a MediaProjection")
            stopSelf(); return START_NOT_STICKY
        }
        projection = mediaProjection
        mediaProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)
        val metrics = resources.displayMetrics
        val maxWidth = 720
        val scale = minOf(1.0, maxWidth / metrics.widthPixels.toDouble())
        val width = (metrics.widthPixels * scale).toInt().coerceAtLeast(1)
        val height = (metrics.heightPixels * scale).toInt().coerceAtLeast(1)
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader = it }
        imageReader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < 500L) return@use
                lastFrameAt = now
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                val jpeg = ByteArrayOutputStream().also { cropped.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
                if (cropped !== padded) cropped.recycle()
                padded.recycle()
                VideoRingCaptureManager.ingest(screenFrame(now, jpeg))
            }
        }, handler)
        mediaProjection.createVirtualDisplay(
            "Apophenia screen evidence ring", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader.surface, null, handler
        )
        stream = setOf(STREAM_ID)
        VideoRingCaptureManager.startSource(this, SOURCE, stream)
        updateNotification("Screen · 15s pre / 10s post")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reader?.close(); reader = null
        projection?.stop(); projection = null
        VideoRingCaptureManager.stopSource(SOURCE, stream)
        thread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun screenFrame(timestampMs: Long, jpeg: ByteArray): VideoFrame {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return VideoFrame(STREAM_ID, "screen", timestampMs, jpeg, 0, 0, ByteArray(0))
        val width = 64
        val height = (bitmap.height * width / bitmap.width).coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = ByteArray(pixels.size) { index ->
            val color = pixels[index]
            ((android.graphics.Color.red(color) * 0.2126 + android.graphics.Color.green(color) * 0.7152 + android.graphics.Color.blue(color) * 0.0722).toInt().coerceIn(0, 255)).toByte()
        }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        return VideoFrame(STREAM_ID, "screen", timestampMs, jpeg, width, height, luma)
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_weird)
            .setContentTitle("Screen ring buffering live").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Disarm screen", stop).build()
    }

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) ?: Unit

    companion object {
        private const val SOURCE = "screen"
        private const val STREAM_ID = "screen_0"
        private const val CHANNEL_ID = "screen_ring_capture"
        private const val NOTIFICATION_ID = 3307
        private const val ACTION_STOP = "com.dronewukong.apophenia.video.SCREEN_STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) = ContextCompat.startForegroundService(
            context,
            Intent(context, ScreenCaptureService::class.java).putExtra(EXTRA_RESULT_CODE, resultCode).putExtra(EXTRA_RESULT_DATA, resultData)
        )
        fun startSimulation(context: Context) = ContextCompat.startForegroundService(context, Intent(context, ScreenCaptureService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP))
    }
}
