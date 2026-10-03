package com.dronewukong.apophenia.video

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.ui.MainActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class CameraStreamPlan(val cameraId: String, val streamId: String, val lensTag: String)
data class CameraPlan(val streams: List<CameraStreamPlan>, val degradation: String?)

object CameraPlanner {
    fun plan(manager: CameraManager, main: Boolean, front: Boolean, multicam: Boolean): CameraPlan {
        val descriptors = manager.cameraIdList.mapNotNull { id ->
            runCatching {
                val facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                id to when (facing) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                    else -> "unknown"
                }
            }.getOrNull()
        }
        val requested = descriptors.filter { (_, lens) -> multicam || (main && lens == "back") || (front && lens == "front") }
        val selectedIds: Set<String>
        var degradation: String? = null
        if (multicam) {
            val concurrent = if (Build.VERSION.SDK_INT >= 30) manager.concurrentCameraIds.maxByOrNull { set -> set.count { id -> requested.any { it.first == id } } } else null
            selectedIds = concurrent?.filterTo(linkedSetOf()) { id -> requested.any { it.first == id } }.orEmpty().ifEmpty {
                degradation = "platform exposes no concurrent camera set; using one stream"
                requested.firstOrNull()?.let { setOf(it.first) }.orEmpty()
            }
            if (selectedIds.size < requested.size) degradation = "${selectedIds.size}/${requested.size} requested camera streams concurrently available"
        } else {
            selectedIds = requested.groupBy { it.second }.values.mapNotNull { it.firstOrNull()?.first }.toSet()
            if (selectedIds.size > 1 && (Build.VERSION.SDK_INT < 30 || manager.concurrentCameraIds.none { it.containsAll(selectedIds) })) {
                degradation = "main/front concurrent capture unavailable; using ${requested.firstOrNull()?.second ?: "one"} stream"
            }
        }
        val actuallySelected = if (degradation != null && !multicam && selectedIds.size > 1) setOf(selectedIds.first()) else selectedIds
        val counters = mutableMapOf<String, Int>()
        return CameraPlan(
            descriptors.filter { it.first in actuallySelected }.map { (id, lens) ->
                val index = counters.getOrDefault(lens, 0).also { counters[lens] = it + 1 }
                CameraStreamPlan(id, "camera_${lens}_$index", "${lens}_$index")
            },
            degradation
        )
    }
}

class CameraCaptureService : Service() {
    private val nodes = mutableListOf<CameraNode>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var running = false
    private var streams = emptySet<String>()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Camera ring capture", NotificationManager.IMPORTANCE_LOW)
        )
        runCatching { MediaRetentionManager(this).purgeExpired() }
        startForeground(NOTIFICATION_ID, notification("Camera ring is starting"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (running) return START_STICKY
        val simulation = HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION
        val main = HardwareGates.isAuthorized(this, HardwareGates.Gate.LIVE_VIDEO_CAPTURE)
        val front = HardwareGates.isAuthorized(this, HardwareGates.Gate.LIVE_VIDEO_SELFCAPTURE)
        val multi = HardwareGates.isAuthorized(this, HardwareGates.Gate.LIVE_MULTICAM_CAPTURE)
        if (!main && !front && !multi) { VideoRingCaptureManager.fail(SOURCE, "No video gate is authorized"); stopSelf(); return START_NOT_STICKY }
        if (!simulation && ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            VideoRingCaptureManager.fail(SOURCE, "Camera permission denied"); stopSelf(); return START_NOT_STICKY
        }
        running = true
        runCatching {
            if (simulation) startSimulation(multi || main, multi || front) else startCameras(main, front, multi)
        }.onFailure {
            VideoRingCaptureManager.fail(SOURCE, it.message ?: "Camera startup failed")
            stopSelf()
        }
        return START_STICKY
    }

    private fun startSimulation(back: Boolean, front: Boolean) {
        streams = buildSet { if (back) add("camera_back_0"); if (front) add("camera_front_0") }
        VideoRingCaptureManager.startSource(this, SOURCE, streams)
        var frame = 0
        scheduler.scheduleAtFixedRate({
            streams.forEachIndexed { index, stream -> VideoRingCaptureManager.ingest(simulationFrame(stream, index, frame)) }
            frame++
        }, 0, 500, TimeUnit.MILLISECONDS)
        updateNotification("SIMULATION · ${streams.size} stream(s) · 15s pre / 10s post")
    }

    @SuppressLint("MissingPermission")
    private fun startCameras(main: Boolean, front: Boolean, multi: Boolean) {
        val manager = getSystemService(CameraManager::class.java) ?: error("Camera manager unavailable")
        val plan = CameraPlanner.plan(manager, main, front, multi)
        if (plan.streams.isEmpty()) { VideoRingCaptureManager.fail(SOURCE, "No eligible camera is exposed"); stopSelf(); return }
        streams = plan.streams.mapTo(linkedSetOf()) { it.streamId }
        VideoRingCaptureManager.startSource(this, SOURCE, streams, plan.degradation)
        plan.streams.forEach { stream ->
            val node = CameraNode(manager, stream) { VideoRingCaptureManager.ingest(it) }
            nodes += node
            node.open()
        }
        scheduler.scheduleAtFixedRate({ nodes.forEach(CameraNode::capture) }, 500, 500, TimeUnit.MILLISECONDS)
        updateNotification("${streams.size} camera stream(s) · 15s pre / 10s post" + (plan.degradation?.let { " · degraded" } ?: ""))
    }

    override fun onDestroy() {
        running = false
        scheduler.shutdownNow()
        nodes.forEach(CameraNode::close)
        nodes.clear()
        VideoRingCaptureManager.stopSource(SOURCE, streams)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun simulationFrame(stream: String, streamIndex: Int, frame: Int): VideoFrame {
        val width = 160; val height = 120
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        repeat(height) { y -> repeat(width) { x ->
            val value = (40 + (x + frame * 12 + streamIndex * 30) % 180).coerceIn(0, 255)
            bitmap.setPixel(x, y, Color.rgb(value, value, value))
        } }
        val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }.toByteArray()
        bitmap.recycle()
        return frame(stream, stream.substringAfter("camera_"), System.currentTimeMillis(), jpeg)
    }

    private fun notification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraCaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(R.drawable.ic_weird)
            .setContentTitle("Camera ring buffering live").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Disarm cameras", stop).build()
    }

    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text)) ?: Unit

    companion object {
        private const val SOURCE = "camera"
        private const val CHANNEL_ID = "camera_ring_capture"
        private const val NOTIFICATION_ID = 3306
        private const val ACTION_STOP = "com.dronewukong.apophenia.video.CAMERA_STOP"
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, CameraCaptureService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, CameraCaptureService::class.java).setAction(ACTION_STOP))
    }
}

private class CameraNode(
    private val manager: CameraManager,
    private val plan: CameraStreamPlan,
    private val onFrame: (VideoFrame) -> Unit
) {
    private val thread = HandlerThread("apophenia-${plan.streamId}").apply { start() }
    private val handler = Handler(thread.looper)
    private val characteristics = manager.getCameraCharacteristics(plan.cameraId)
    private val size: Size = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { kotlin.math.abs(it.width * it.height - 640 * 480) } ?: Size(640, 480)
    private val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null

    init {
        reader.setOnImageAvailableListener({ source ->
            source.acquireLatestImage()?.use { image ->
                val buffer = image.planes[0].buffer
                val jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                onFrame(frame(plan.streamId, plan.lensTag, System.currentTimeMillis(), jpeg))
            }
        }, handler)
    }

    @SuppressLint("MissingPermission")
    fun open() {
        manager.openCamera(plan.cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) { session = value }
                    override fun onConfigureFailed(value: CameraCaptureSession) { close() }
                }, handler)
            }
            override fun onDisconnected(camera: CameraDevice) = close()
            override fun onError(camera: CameraDevice, error: Int) = close()
        }, handler)
    }

    fun capture() {
        val camera = device ?: return
        val current = session ?: return
        runCatching {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            }.build()
            current.capture(request, null, handler)
        }
    }

    fun close() {
        session?.close(); session = null
        device?.close(); device = null
        reader.close()
        thread.quitSafely()
    }
}

private fun frame(streamId: String, lensTag: String, timestampMs: Long, jpeg: ByteArray): VideoFrame {
    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return VideoFrame(streamId, lensTag, timestampMs, jpeg, 0, 0, ByteArray(0))
    val width = 64
    val height = (bitmap.height * width / bitmap.width).coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
    val pixels = IntArray(width * height)
    scaled.getPixels(pixels, 0, width, 0, 0, width, height)
    val luma = ByteArray(pixels.size) { index ->
        val color = pixels[index]
        ((Color.red(color) * 0.2126 + Color.green(color) * 0.7152 + Color.blue(color) * 0.0722).toInt().coerceIn(0, 255)).toByte()
    }
    if (scaled !== bitmap) scaled.recycle()
    bitmap.recycle()
    return VideoFrame(streamId, lensTag, timestampMs, jpeg, width, height, luma)
}
