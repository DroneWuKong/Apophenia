package com.dronewukong.apophenia.video

import android.content.Context
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ObservationStore
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class VideoRingState(
    val activeSources: Set<String> = emptySet(),
    val activeStreams: Set<String> = emptySet(),
    val framesCaptured: Long = 0,
    val pendingEvents: Int = 0,
    val degradation: String? = null,
    val lastError: String? = null
) { val active: Boolean get() = activeSources.isNotEmpty() }

class FrozenVideoToken internal constructor(internal val value: String)

object VideoRingCaptureManager {
    const val PRE_SECONDS = 15
    const val POST_SECONDS = 10
    const val TARGET_FPS = 2
    private const val MAX_FREEZE_SKEW_MS = 5_000L
    private val lock = Any()
    private val stateFlow = MutableStateFlow(VideoRingState())
    val state: StateFlow<VideoRingState> = stateFlow
    private val rings = linkedMapOf<String, VideoFrameRing>()
    private val pending = linkedMapOf<String, PendingVideo>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val writer = Executors.newSingleThreadExecutor()
    private var app: Context? = null
    @Volatile private var testFinalizer: ((Long, Long, Map<String, Pair<List<VideoFrame>, List<VideoFrame>>>) -> Unit)? = null

    private data class PendingVideo(
        val token: FrozenVideoToken,
        val eventAtMs: Long,
        val pre: Map<String, List<VideoFrame>>,
        val post: MutableMap<String, MutableList<VideoFrame>> = linkedMapOf(),
        var eventId: Long? = null,
        var finalized: Boolean = false,
        var future: ScheduledFuture<*>? = null
    )

    fun startSource(context: Context, source: String, streams: Set<String>, degradation: String? = null) {
        synchronized(lock) {
            app = context.applicationContext
            streams.forEach { rings.getOrPut(it) { VideoFrameRing(PRE_SECONDS * TARGET_FPS) } }
            stateFlow.value = stateFlow.value.copy(
                activeSources = stateFlow.value.activeSources + source,
                activeStreams = stateFlow.value.activeStreams + streams,
                degradation = degradation ?: stateFlow.value.degradation,
                lastError = null
            )
        }
    }

    fun stopSource(source: String, streams: Set<String>) {
        synchronized(lock) {
            val sources = stateFlow.value.activeSources - source
            stateFlow.value = stateFlow.value.copy(activeSources = sources, activeStreams = stateFlow.value.activeStreams - streams)
            if (sources.isEmpty()) rings.values.forEach(VideoFrameRing::clear)
        }
    }

    fun ingest(frame: VideoFrame) {
        synchronized(lock) {
            if (!stateFlow.value.active) return
            rings.getOrPut(frame.streamId) { VideoFrameRing(PRE_SECONDS * TARGET_FPS) }.add(frame)
            pending.values.filter { !it.finalized && frame.timestampMs >= it.eventAtMs }.forEach { capture ->
                if (frame.timestampMs <= capture.eventAtMs + POST_SECONDS * 1_000L) {
                    capture.post.getOrPut(frame.streamId) { mutableListOf() }.add(frame)
                }
            }
            stateFlow.value = stateFlow.value.copy(framesCaptured = stateFlow.value.framesCaptured + 1)
        }
    }

    fun freezeNow(eventAtMs: Long): FrozenVideoToken? = synchronized(lock) {
        if (!stateFlow.value.active || abs(System.currentTimeMillis() - eventAtMs) > MAX_FREEZE_SKEW_MS) return@synchronized null
        val token = FrozenVideoToken(UUID.randomUUID().toString())
        val capture = PendingVideo(token, eventAtMs, rings.mapValues { it.value.snapshot() })
        capture.future = scheduler.schedule({ finalizeIfAttached(token.value) }, POST_SECONDS + 1L, TimeUnit.SECONDS)
        pending[token.value] = capture
        stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
        token
    }

    fun attach(token: FrozenVideoToken?, eventId: Long) {
        if (token == null) return
        var checkpoint: PendingVideo? = null
        synchronized(lock) {
            pending[token.value]?.let { capture -> capture.eventId = eventId; checkpoint = capture }
        }
        checkpoint?.let(::checkpointPreEvent)
    }

    fun discard(token: FrozenVideoToken?) {
        if (token == null) return
        synchronized(lock) {
            pending.remove(token.value)?.future?.cancel(false)
            stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
        }
    }

    fun fail(source: String, message: String) {
        synchronized(lock) { stateFlow.value = stateFlow.value.copy(lastError = "$source: $message") }
    }

    private fun finalizeIfAttached(tokenValue: String) {
        var capture: PendingVideo? = null
        synchronized(lock) {
            val candidate = pending[tokenValue] ?: return
            if (candidate.eventId == null) {
                candidate.future = scheduler.schedule({ finalizeIfAttached(tokenValue) }, 1, TimeUnit.SECONDS)
                return
            }
            if (!candidate.finalized) {
                candidate.finalized = true
                capture = candidate
            }
        }
        capture?.let(::finalizeCapture)
    }

    private fun finalizeCapture(capture: PendingVideo) {
        val eventId = capture.eventId ?: return
        val streams = (capture.pre.keys + capture.post.keys).associateWith { stream ->
            capture.pre[stream].orEmpty() to capture.post[stream].orEmpty().toList()
        }
        writer.execute {
            val override = testFinalizer
            if (override != null) override(eventId, capture.eventAtMs, streams)
            else {
                val context = app ?: return@execute
                runCatching {
                    val rows = streams.flatMap { (stream, windows) ->
                        val all = windows.first + windows.second
                        if (all.isEmpty()) return@flatMap emptyList()
                        val lens = all.first().lensTag
                        val artifact = VideoArtifactStore(context).persist(eventId, capture.eventAtMs, stream, lens, windows.first, windows.second)
                        val captureId = "event:$eventId:video:$stream"
                        (VideoFeatureExtractor.contextSamples(eventId, captureId, ContextPhase.PRE, windows.first) +
                            VideoFeatureExtractor.contextSamples(eventId, captureId, ContextPhase.POST, windows.second)).map { row ->
                            row.copy(metadata = "${row.metadata};video_file_id=${artifact.fileId};cipher=AES-256-GCM;ciphertext_sha256=${artifact.ciphertextSha256};raw_retention_days=14")
                        }
                    }
                    ObservationStore.repository(context).insertContext(rows)
                }.onFailure { fail("finalizer", it.message ?: "Video finalization failed") }
            }
            synchronized(lock) {
                pending.remove(capture.token.value)
                stateFlow.value = stateFlow.value.copy(pendingEvents = pending.size)
            }
        }
    }

    private fun checkpointPreEvent(capture: PendingVideo) {
        if (testFinalizer != null) return
        val context = app ?: return
        val eventId = capture.eventId ?: return
        writer.execute {
            runCatching {
                capture.pre.forEach { (stream, frames) ->
                    if (frames.isNotEmpty()) VideoArtifactStore(context).persist(
                        eventId, capture.eventAtMs, stream, frames.first().lensTag, frames, emptyList()
                    )
                }
            }.onFailure { fail("checkpoint", it.message ?: "Video pre-event checkpoint failed") }
        }
    }

    internal fun setFinalizerForTests(finalizer: ((Long, Long, Map<String, Pair<List<VideoFrame>, List<VideoFrame>>>) -> Unit)?) {
        testFinalizer = finalizer
    }

    internal fun finalizeNowForTests(token: FrozenVideoToken) = finalizeIfAttached(token.value)

    internal fun resetForTests() {
        synchronized(lock) {
            pending.values.forEach { it.future?.cancel(false) }
            pending.clear(); rings.clear(); app = null; stateFlow.value = VideoRingState()
        }
        testFinalizer = null
    }
}
