package com.dronewukong.apophenia.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.dronewukong.apophenia.rolling.RollingRecorderConfig
import com.dronewukong.apophenia.work.EventEnrichmentWorker
import com.dronewukong.apophenia.work.PostEventWindowWorker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ObservationRepository(context: Context) {
    private val app = context.applicationContext
    private val db = ObservationDb(app)
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun log(kind: ObservationKind, label: String, note: String = "", severity: Int? = null, confidence: Int = 3, timestampMs: Long = System.currentTimeMillis(), onSaved: ((Long)->Unit)? = null) {
        executor.execute {
            val id = db.insertObservation(Observation(timestampMs=timestampMs, kind=kind, label=label.trim().ifBlank { "Unlabeled observation" }, note=note, severity=severity, confidence=confidence))
            db.copyRollingToObservation(id, timestampMs - RollingRecorderConfig.PRE_WINDOW_MS, timestampMs, "pre")

            WorkManager.getInstance(app).enqueue(
                OneTimeWorkRequestBuilder<EventEnrichmentWorker>()
                    .setInputData(Data.Builder().putLong(EventEnrichmentWorker.KEY_OBSERVATION_ID, id).build())
                    .build()
            )
            WorkManager.getInstance(app).enqueue(
                OneTimeWorkRequestBuilder<PostEventWindowWorker>()
                    .setInitialDelay(RollingRecorderConfig.POST_WINDOW_MS + 60_000L, TimeUnit.MILLISECONDS)
                    .setInputData(Data.Builder().putLong(PostEventWindowWorker.KEY_OBSERVATION_ID, id).putLong(PostEventWindowWorker.KEY_EVENT_TS, timestampMs).build())
                    .build()
            )
            if (onSaved != null) main.post { onSaved.invoke(id) }
        }
    }

    fun observations(limit: Int = 250): List<Observation> = db.observations(limit)
    fun db(): ObservationDb = db
}
