package com.dronewukong.apophenia.audio

import android.content.Context
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ObservationStore
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class AudioRingState(
    val active: Boolean = false,
    val simulation: Boolean = false,
    val sampleRateHz: Int = AudioRingCaptureManager.SAMPLE_RATE_HZ,
    val preSeconds: Int = AudioRingCaptureManager.DEFAULT_PRE_SECONDS,
    val postSeconds: Int = AudioRingCaptureManager.DEFAULT_POST_SECONDS,
    val startedAtMs: Long? = null,
    val capturedBytes: Long = 0,
    val pendingEvents: Int = 0,
    val lastError: String? = null
)

class FrozenAudioToken internal constructor(internal val value: String)

object AudioRingCaptureManager {
    const val SAMPLE_RATE_HZ = 16_000
    const val DEFAULT_PRE_SECONDS = 60
    const val DEFAULT_POST_SECONDS = 30
    private const val BYTES_PER_SAMPLE = 2
    private val stateFlow = MutableStateFlow(AudioRingState())
    val state: StateFlow<AudioRingState> = stateFlow
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    private var ring: PcmRingBuffer? = null
    private val pending = linkedMapOf<String, PendingCapture>()
    @Volatile private var testFinalizer: ((Long, Long, ByteArray, ByteArray) -> Unit)? = null

    private data class PendingCapture(
        val token: FrozenAudioToken,
        val eventAtMs: Long,
        val pre: ByteArray,
        val post: ByteArrayOutputStream,
        val postTargetBytes: Int,
        var eventId: Long? = null,
        var discarded: Boolean = false,
        var finalized: Boolean = false
    )

    fun arm(context: Context, simulation: Boolean, preSeconds: Int = DEFAULT_PRE_SECONDS, postSeconds: Int = DEFAULT_POST_SECONDS) {
        require(preSeconds in 1..120)
        require(postSeconds in 0..120)
        synchronized(lock) {
            app = context.applicationContext
            ring = PcmRingBuffer(SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * preSeconds)
            pending.clear()
            stateFlow.value = AudioRingState(
                active = true, simulation = simulation, preSeconds = preSeconds, postSeconds = postSeconds,
                startedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun ingest(pcm16le: ByteArray, count: Int = pcm16le.size) {
        val finalize = mutableListOf<PendingCapture>()
        synchronized(lock) {
            if (!stateFlow.value.active) return
            val safe = count.coerceIn(0, pcm16le.size)
            ring?.write(pcm16le, safe)
            pending.values.forEach { capture ->
                if (capture.discarded || capture.finalized) return@forEach
                val remaining = capture.postTargetBytes - capture.post.size()
                if (remaining > 0) capture.post.write(pcm16le, 0, minOf(remaining, safe))
                if (capture.post.size() >= capture.postTargetBytes && capture.eventId != null) {
                    capture.finalized = true
                    finalize += capture
                }
            }
            stateFlow.value = stateFlow.value.copy(
                capturedBytes = stateFlow.value.capturedBytes + safe,
                pendingEvents = pending.values.count { !it.finalized && !it.discarded }
            )
        }
        finalize.forEach(::finalizeCapture)
    }

    fun freezeNow(eventAtMs: Long): FrozenAudioToken? = synchronized(lock) {
        if (!stateFlow.value.active) return@synchronized null
        // A delayed watch/import timestamp cannot honestly freeze the phone audio that existed then.
        if (abs(System.currentTimeMillis() - eventAtMs) > MAX_FREEZE_SKEW_MS) return@synchronized null
        val token = FrozenAudioToken(UUID.randomUUID().toString())
        val capture = PendingCapture(
            token = token,
            eventAtMs = eventAtMs,
            pre = ring?.snapshot() ?: ByteArray(0),
            post = ByteArrayOutputStream(SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * stateFlow.value.postSeconds),
            postTargetBytes = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE * stateFlow.value.postSeconds
        )
        pending[token.value] = capture
        stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
        token
    }

    fun attach(token: FrozenAudioToken?, eventId: Long) {
        if (token == null) return
        var ready: PendingCapture? = null
        var checkpoint: PendingCapture? = null
        synchronized(lock) {
            val capture = pending[token.value] ?: return
            capture.eventId = eventId
            checkpoint = capture
            if ((capture.post.size() >= capture.postTargetBytes || !stateFlow.value.active) && !capture.finalized) {
                capture.finalized = true
                ready = capture
            }
        }
        checkpoint?.let(::checkpointPreEvent)
        ready?.let(::finalizeCapture)
    }

    fun discard(token: FrozenAudioToken?) {
        if (token == null) return
        synchronized(lock) {
            pending.remove(token.value)?.apply {
                discarded = true
                pre.fill(0)
                post.reset()
            }
            stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
        }
    }

    fun stop() {
        val finalize = mutableListOf<PendingCapture>()
        synchronized(lock) {
            pending.values.forEach { capture ->
                if (!capture.discarded && !capture.finalized && capture.eventId != null) {
                    capture.finalized = true
                    finalize += capture
                }
            }
            stateFlow.value = stateFlow.value.copy(active = false, pendingEvents = finalize.size)
            ring?.clear()
            ring = null
        }
        finalize.forEach(::finalizeCapture)
    }

    fun fail(message: String) {
        stop()
        stateFlow.value = stateFlow.value.copy(lastError = message)
    }

    private fun finalizeCapture(capture: PendingCapture) {
        val context = app ?: return
        val eventId = capture.eventId ?: return
        val postBytes = capture.post.toByteArray()
        writer.execute {
            val override = testFinalizer
            if (override != null) {
                override(eventId, capture.eventAtMs, capture.pre.copyOf(), postBytes.copyOf())
                capture.pre.fill(0)
                postBytes.fill(0)
                synchronized(lock) {
                    pending.remove(capture.token.value)
                    stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
                }
                return@execute
            }
            runCatching {
                val artifact = AudioArtifactStore(context).persist(
                    eventId = eventId,
                    eventAtMs = capture.eventAtMs,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    prePcm = capture.pre,
                    postPcm = postBytes
                )
                val captureId = "event:$eventId:audio"
                val samples = AudioFeatureExtractor.contextSamples(eventId, capture.eventAtMs, captureId, ContextPhase.PRE, capture.pre, SAMPLE_RATE_HZ) +
                    AudioFeatureExtractor.contextSamples(eventId, capture.eventAtMs, captureId, ContextPhase.POST, postBytes, SAMPLE_RATE_HZ)
                val inventory = samples.map { row ->
                    row.copy(metadata = "${row.metadata};audio_file_id=${artifact.fileId};cipher=AES-256-GCM;ciphertext_sha256=${artifact.ciphertextSha256};raw_media_status=${if (artifact.retained) "active" else "purged"}")
                }
                ObservationStore.repository(context).insertContext(inventory)
            }.onFailure { stateFlow.value = stateFlow.value.copy(lastError = it.message ?: "Audio event finalization failed") }
            capture.pre.fill(0)
            postBytes.fill(0)
            synchronized(lock) {
                pending.remove(capture.token.value)
                stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
            }
        }
    }

    private fun checkpointPreEvent(capture: PendingCapture) {
        if (testFinalizer != null) return
        val context = app ?: return
        val eventId = capture.eventId ?: return
        writer.execute {
            runCatching {
                AudioArtifactStore(context).persist(
                    eventId = eventId,
                    eventAtMs = capture.eventAtMs,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    prePcm = capture.pre,
                    postPcm = ByteArray(0)
                )
            }.onFailure {
                stateFlow.value = stateFlow.value.copy(lastError = it.message ?: "Audio pre-event checkpoint failed")
            }
        }
    }

    internal fun setFinalizerForTests(finalizer: ((Long, Long, ByteArray, ByteArray) -> Unit)?) {
        testFinalizer = finalizer
    }

    internal fun resetForTests() {
        synchronized(lock) {
            pending.clear()
            ring = null
            app = null
            stateFlow.value = AudioRingState()
        }
        testFinalizer = null
    }

    private const val MAX_FREEZE_SKEW_MS = 5_000L
}
