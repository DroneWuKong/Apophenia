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
import java.util.UUID
class ControlSampleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork(): Result {
  val db=ObservationDb(applicationContext); val captureId="control:${UUID.randomUUID()}"; val now=System.currentTimeMillis()
  db.copyRollingToControl(captureId, now-RollingRecorderConfig.PRE_WINDOW_MS, now)
  val samples=mutableListOf<ContextSample>(); samples+=SensorSnapshotCollector(applicationContext).collect(null,true,windowMs=600); samples+=DeviceContextCollector(applicationContext).collect(null,true); samples+=EnvironmentProvider(applicationContext).collect(null,true)
  db.insertContext(samples.map{it.copy(captureId=captureId)}); ControlScheduler.scheduleNext(applicationContext); return Result.success()
 }
}
