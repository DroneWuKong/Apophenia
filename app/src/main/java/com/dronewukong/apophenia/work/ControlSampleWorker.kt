package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.bluetooth.BluetoothContextProvider
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.hardware.DeviceContextCollector
import com.dronewukong.apophenia.hardware.SensorSnapshotCollector
import com.dronewukong.apophenia.rolling.RollingRecorderConfig
import com.dronewukong.apophenia.health.HealthConnectProvider
import com.dronewukong.apophenia.home.HomeContextProvider
import com.dronewukong.apophenia.network.NetworkStateProvider
import com.dronewukong.apophenia.phone.PhoneMetadataProvider
import com.dronewukong.apophenia.phone.AuxiliaryPresenceProvider
import com.dronewukong.apophenia.phone.SensitiveContextProvider
import com.dronewukong.apophenia.wifi.WifiContextProvider
import com.dronewukong.apophenia.vehicle.DriveSessionManager
import com.dronewukong.apophenia.vehicle.ObdContextProvider
import com.dronewukong.apophenia.vehicle.AutomotiveContextProvider
import kotlinx.coroutines.runBlocking
import java.util.UUID
class ControlSampleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork(): Result {
  val source=inputData.getString(KEY_CONTROL_SOURCE)?:SOURCE_RANDOM
  val requestedAt=inputData.getLong(KEY_CAPTURED_AT,-1L)
  val now=if(requestedAt>0L)requestedAt else System.currentTimeMillis()
  val db=ObservationStore.repository(applicationContext).db(); val captureId="$source-control:${UUID.randomUUID()}"
  return try {
   db.copyRollingToControl(captureId, now-RollingRecorderConfig.PRE_WINDOW_MS, now)
   val samples=mutableListOf<ContextSample>()
   samples+=runCatching{SensorSnapshotCollector(applicationContext).collect(null,true,windowMs=600)}.getOrDefault(emptyList())
   samples+=runCatching{DeviceContextCollector(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{EnvironmentProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runBlocking{HealthConnectProvider(applicationContext).collect(null,true)}
   samples+=runCatching{HomeContextProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{BluetoothContextProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{WifiContextProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{NetworkStateProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{PhoneMetadataProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{AuxiliaryPresenceProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{ObdContextProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   samples+=runCatching{AutomotiveContextProvider(applicationContext).collect(null,true)}.getOrDefault(emptyList())
   val driveSession=DriveSessionManager.activeId()
   db.insertContext(samples.map{it.copy(captureId=captureId,metadata=if(it.metadata.isBlank())"control_source=$source" else "${it.metadata};control_source=$source",sessionId=it.sessionId?:driveSession)})
   db.insertSensitiveContext(runCatching{SensitiveContextProvider(applicationContext).collect(null,true,captureId)}.getOrDefault(emptyList()))
   if(source==SOURCE_RANDOM)ControlScheduler.scheduleNext(applicationContext)
   Result.success()
  } catch(_:Exception) { Result.retry() }
 }
 companion object {
  const val KEY_CONTROL_SOURCE="control_source"
  const val KEY_CAPTURED_AT="captured_at"
  const val SOURCE_RANDOM="random"
  const val SOURCE_PROMPTED="prompted"
 }
}
