package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.hardware.DeviceContextCollector
import com.dronewukong.apophenia.hardware.SensorSnapshotCollector
import com.dronewukong.apophenia.rolling.RollingRecorderConfig
import com.dronewukong.apophenia.health.HealthConnectProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID
class ControlSampleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork(): Result {
  val db=ObservationDb(applicationContext); val captureId="control:${UUID.randomUUID()}"; val now=System.currentTimeMillis()
  return try {
   db.copyRollingToControl(captureId, now-RollingRecorderConfig.PRE_WINDOW_MS, now)
   val samples=mutableListOf<ContextSample>()
   samples+=runCatching{SensorSnapshotCollector(applicationContext).collect(null,true,windowMs=600)}.getOrDefault(emptyList())
   samples+=runCatching{DeviceContextCollector(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{EnvironmentProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runBlocking{HealthConnectProvider(applicationContext).collect(null,true)}
   db.insertContext(samples.map{it.copy(captureId=captureId)}); ControlScheduler.scheduleNext(applicationContext); Result.success()
  } catch(_:Exception) { Result.retry() }
 }
}
