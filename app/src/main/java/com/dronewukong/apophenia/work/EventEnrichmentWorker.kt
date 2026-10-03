package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.bluetooth.BluetoothContextProvider
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.environment.EnvironmentProvider
import com.dronewukong.apophenia.hardware.DeviceContextCollector
import com.dronewukong.apophenia.hardware.SensorSnapshotCollector
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
import com.dronewukong.apophenia.mavlink.MavlinkContextProvider
import com.dronewukong.apophenia.mavlink.MavlinkSessionManager
import com.dronewukong.apophenia.control.ControlLinkContextProvider
import com.dronewukong.apophenia.fieldkit.FieldKitContextProvider
import com.dronewukong.apophenia.tak.TakContextProvider
import kotlinx.coroutines.runBlocking
class EventEnrichmentWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
 override fun doWork():Result{val id=inputData.getLong(KEY_OBSERVATION_ID,-1L);if(id<=0)return Result.failure();val db=ObservationStore.repository(applicationContext).db();val samples=mutableListOf<com.dronewukong.apophenia.data.ContextSample>();samples+=runCatching{SensorSnapshotCollector(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{DeviceContextCollector(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{EnvironmentProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runBlocking{HealthConnectProvider(applicationContext).collect(id,false)};samples+=runCatching{HomeContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{BluetoothContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{WifiContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{NetworkStateProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{PhoneMetadataProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{AuxiliaryPresenceProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{ObdContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{AutomotiveContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{MavlinkContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{ControlLinkContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{FieldKitContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());samples+=runCatching{TakContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList());val activeSession=MavlinkSessionManager.activeId()?:DriveSessionManager.activeId();db.insertContext(samples.map{if(activeSession!=null&&it.sessionId==null)it.copy(sessionId=activeSession)else it});db.insertSensitiveContext(runCatching{SensitiveContextProvider(applicationContext).collect(id,false)}.getOrDefault(emptyList()));return Result.success()}
 companion object{const val KEY_OBSERVATION_ID="observation_id"}
}
