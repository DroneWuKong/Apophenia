package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.hardware.DeviceContextCollector
import com.dronewukong.apophenia.hardware.SensorSnapshotCollector
class EventEnrichmentWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork():Result{val id=inputData.getLong(KEY_OBSERVATION_ID,-1L);if(id<=0)return Result.failure();val db=ObservationDb(applicationContext);val samples=mutableListOf<com.dronewukong.apophenia.data.ContextSample>();samples+=SensorSnapshotCollector(applicationContext).collect(id,false);samples+=DeviceContextCollector(applicationContext).collect(id,false);samples+=EnvironmentProvider(applicationContext).collect(id,false);db.insertContext(samples);return Result.success()}
 companion object{const val KEY_OBSERVATION_ID="observation_id"}
}
