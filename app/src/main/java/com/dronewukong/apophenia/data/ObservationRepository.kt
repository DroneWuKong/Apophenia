package com.dronewukong.apophenia.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.audio.AudioRingCaptureManager
import com.dronewukong.apophenia.video.VideoRingCaptureManager
import com.dronewukong.apophenia.rolling.RollingRecorderConfig
import com.dronewukong.apophenia.work.EventEnrichmentWorker
import com.dronewukong.apophenia.work.PostEventWindowWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObservationRepository(context: Context, databaseName: String = "apophenia.db") {
    private val app = context.applicationContext
    private val db = ObservationDb(app, databaseName)
    private val main = Handler(Looper.getMainLooper())

    fun log(
        kind: ObservationKind,
        label: String,
        note: String = "",
        severity: Int? = null,
        confidence: Int = 3,
        timestampMs: Long = System.currentTimeMillis(),
        origin: ObservationOrigin = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) ObservationOrigin.SIMULATION else ObservationOrigin.ANDROID,
        externalEventId: String? = null,
        vibeRating: Int? = null,
        egress: Boolean = false,
        onSaved: ((Long)->Unit)? = null
    ) = log(
        ObservationCaptureRequest(
            timestampMs = timestampMs,
            kind = kind,
            label = label,
            note = note,
            severity = severity,
            confidence = confidence,
            origin = origin,
            externalEventId = externalEventId,
            vibeRating = vibeRating,
            egress = egress
        ),
        onSaved
    )

    fun log(request: ObservationCaptureRequest, onSaved: ((Long) -> Unit)? = null) {
        logWithResult(request) { id, _ -> onSaved?.invoke(id) }
    }

    fun logWithResult(request: ObservationCaptureRequest, onSaved: ((Long, Boolean) -> Unit)? = null) {
        // Freeze at the user-supplied tap time, before SQLite or WorkManager can add latency.
        val audioFreeze = if (request.kind == ObservationKind.HYPOTHESIS_NOTE) null
        else AudioRingCaptureManager.freezeNow(request.timestampMs)
        val videoFreeze = if (request.kind == ObservationKind.HYPOTHESIS_NOTE) null
        else VideoRingCaptureManager.freezeNow(request.timestampMs)
        executor.execute {
            if (request.kind == ObservationKind.HYPOTHESIS_NOTE) {
                val id = db.insertHypothesis(
                    Hypothesis(
                        createdAtMs = request.timestampMs,
                        eventLabel = request.label.trim().ifBlank { "Untitled hypothesis" },
                        metric = "",
                        note = request.note,
                        source = request.origin
                    )
                )
                if (onSaved != null) main.post { onSaved.invoke(id, true) }
                return@execute
            }

            val insert = db.insertObservationOrGet(
                Observation(
                    timestampMs = request.timestampMs,
                    kind = request.kind,
                    label = request.label.trim().ifBlank { "Unlabeled observation" },
                    note = request.note,
                    severity = request.severity,
                    confidence = request.confidence,
                    origin = request.origin,
                    externalEventId = request.externalEventId,
                    vibeRating = request.vibeRating,
                    egress = request.egress
                )
            )
            val id = insert.id
            if (!insert.inserted) {
                AudioRingCaptureManager.discard(audioFreeze)
                VideoRingCaptureManager.discard(videoFreeze)
                if (onSaved != null) main.post { onSaved.invoke(id, false) }
                return@execute
            }

            AudioRingCaptureManager.attach(audioFreeze, id)
            VideoRingCaptureManager.attach(videoFreeze, id)

            db.copyRollingToObservation(
                id,
                request.timestampMs - RollingRecorderConfig.PRE_WINDOW_MS,
                request.timestampMs - 1L,
                ContextPhase.PRE
            )

            WorkManager.getInstance(app).enqueue(
                OneTimeWorkRequestBuilder<EventEnrichmentWorker>()
                    .setInputData(Data.Builder().putLong(EventEnrichmentWorker.KEY_OBSERVATION_ID, id).putBoolean(EventEnrichmentWorker.KEY_DEMO_DATABASE, db.isDemoDatabase).build())
                    .build()
            )
            WorkManager.getInstance(app).enqueue(
                OneTimeWorkRequestBuilder<PostEventWindowWorker>()
                    .setInitialDelay(RollingRecorderConfig.POST_WINDOW_MS + 60_000L, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putLong(PostEventWindowWorker.KEY_OBSERVATION_ID, id).putLong(PostEventWindowWorker.KEY_EVENT_TS, request.timestampMs).putBoolean(PostEventWindowWorker.KEY_DEMO_DATABASE, db.isDemoDatabase).build())
                    .build()
            )
            if (onSaved != null) main.post { onSaved.invoke(id, true) }
        }
    }

    fun observations(limit: Int = 250): List<Observation> = db.observations(limit)
    fun hypotheses(limit: Int = 250): List<Hypothesis> = db.hypotheses(limit)
    fun registerHypothesis(hypothesis: Hypothesis, featureKey: String, onSaved: ((Long?) -> Unit)? = null) {
        require(hypothesis.eventLabel.isNotBlank()) { "A registration needs an event class" }
        require(hypothesis.metric.isNotBlank()) { "A registration needs an exact metric" }
        require(hypothesis.note.isNotBlank()) { "A registration needs an expected association" }
        executor.execute {
            val id = db.insertHypothesisIfUnviewed(hypothesis, featureKey)
            if (onSaved != null) main.post { onSaved(id) }
        }
    }
    fun insertContext(samples: List<ContextSample>) = db.insertContext(samples)
    fun db(): ObservationDb = db

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}
