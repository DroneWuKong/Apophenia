package com.dronewukong.apophenia.garmin

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationRepository
import com.dronewukong.apophenia.hardware.HardwareGates
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.exception.InvalidStateException
import com.garmin.android.connectiq.exception.ServiceUnavailableException
import java.util.concurrent.Executors

object GarminBridge {
    const val WATCH_APP_ID = "4f4d0f7b3d6f4b36b3e88b91129c70a2"
    @Volatile var statusText:String="Not initialized"; private set
    @Volatile var deviceText:String="No Garmin device seen"; private set
    private val io=Executors.newSingleThreadExecutor()
    @Volatile private var initialized=false
    @Volatile private var initializing=false
    private var connectIQ:ConnectIQ?=null
    private var appContext:Context?=null

    @Synchronized fun initialize(context:Context){
        appContext=context.applicationContext
        if(!HardwareGates.garminEnabled){statusText=if(HardwareGates.runtimeMode==HardwareGates.RuntimeMode.SIMULATION)"Garmin bridge bypassed in simulation mode" else "Garmin bridge disabled by build gate";initialized=false;return}
        if(initialized||initializing)return
        val app=context.applicationContext
        val iq=ConnectIQ.getInstance(app,ConnectIQ.IQConnectType.WIRELESS)
        connectIQ=iq;statusText="Connecting to Garmin Connect…";initializing=true
        iq.initialize(app,true,object:ConnectIQ.ConnectIQListener{
            override fun onInitializeError(errStatus:ConnectIQ.IQSdkErrorStatus){statusText="Garmin SDK initialization failed: ${errStatus.name}";initialized=false;initializing=false}
            override fun onSdkReady(){initialized=true;initializing=false;statusText="Garmin bridge ready";registerKnownDevices()}
            override fun onSdkShutDown(){initialized=false;initializing=false;statusText="Garmin bridge stopped"}
        })
    }

    @Synchronized fun refresh(context:Context){if(!HardwareGates.garminEnabled){statusText="Garmin bridge bypassed";return};if(!initialized&&!initializing)initialize(context) else if(initialized)registerKnownDevices()}

    private fun registerKnownDevices(){
        val iq=connectIQ?:return
        val devices=try{iq.knownDevices?:emptyList()}catch(_:InvalidStateException){statusText="Garmin SDK is not ready";return}catch(_:ServiceUnavailableException){statusText="Garmin Connect service unavailable";return}
        if(devices.isEmpty()){deviceText="No paired Connect IQ device found";statusText="Pair the Epix Pro in Garmin Connect, then refresh";return}
        devices.forEach{device->runCatching{
            device.status=iq.getDeviceStatus(device)
            iq.unregisterForDeviceEvents(device)
            iq.registerForDeviceEvents(device){changed,status->deviceText="${changed.friendlyName}: ${status.name}"}
            iq.unregisterForApplicationEvents(device,IQApp(WATCH_APP_ID))
            iq.registerForAppEvents(device,IQApp(WATCH_APP_ID)){commDevice,_,message,_->deviceText="${commDevice.friendlyName}: message received";ingestMessage(commDevice,message)}
        }}
        val connected=devices.firstOrNull{it.status==IQDevice.IQDeviceStatus.CONNECTED};val best=connected?:devices.first()
        deviceText="${best.friendlyName}: ${best.status?.name ?: "UNKNOWN"}"
        statusText=if(connected!=null)"Listening for Apophenia watch events" else "Garmin device known but not connected"
    }

    fun openWatchLogger(context:Context){
        refresh(context);val iq=connectIQ?:return
        val device=try{iq.knownDevices?.firstOrNull{it.status==IQDevice.IQDeviceStatus.CONNECTED}}catch(_:Exception){null}
        if(device==null){statusText="No connected Garmin device";return}
        try{iq.openApplication(device,IQApp(WATCH_APP_ID)){_,_,result->statusText=when(result){ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE->"Open prompt sent to ${device.friendlyName}";ConnectIQ.IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING->"Apophenia is already open on ${device.friendlyName}";else->"Could not open watch app: ${result.name}"}}}catch(_:InvalidStateException){statusText="Garmin SDK is not ready"}catch(_:ServiceUnavailableException){statusText="Garmin Connect service unavailable"}
    }

    private fun ingestMessage(device:IQDevice,message:List<Any>){message.filterIsInstance<Map<*,*>>().forEach{ingestPacket(device,it)}}

    private fun ingestPacket(device:IQDevice,packet:Map<*,*>){
        if(packet["type"]?.toString()!="observation")return
        val label=packet["label"]?.toString()?.ifBlank{"Garmin observation"}?:"Garmin observation"
        val ts=(packet["ts_ms"] as? Number)?.toLong()?.takeIf{it>0}?:System.currentTimeMillis()
        val note=packet["note"]?.toString().orEmpty()
        val kind=runCatching{ObservationKind.valueOf(packet["kind"]?.toString().orEmpty())}.getOrElse{if(label.equals("That was weird",true))ObservationKind.WEIRD else ObservationKind.OBSERVATION}
        val context=appContext?:return
        ObservationRepository(context).log(kind,label,note.ifBlank{"Logged on ${device.friendlyName}"},timestampMs=ts){id->
            val metrics=packet["metrics"] as? Map<*,*>?:return@log
            io.execute{
                val samples=metrics.mapNotNull{(rawKey,rawValue)->
                    val key=rawKey?.toString()?:return@mapNotNull null
                    val value=(rawValue as? Number)?.toDouble()?:return@mapNotNull null
                    val unit=when(key){"garmin_heart_rate_bpm"->"bpm";"garmin_stress","garmin_body_battery"->"score";"garmin_spo2_pct"->"%";"garmin_pressure_hpa"->"hPa";"garmin_temperature_c"->"C";"garmin_phone_connected"->"bool";else->"value"}
                    ContextSample(timestampMs=ts,observationId=id,source="garmin/${device.friendlyName}",metric=key,value=value,unit=unit,metadata="watch_app_id=$WATCH_APP_ID",captureId="garmin:event:$id")
                }
                ObservationDb(context).insertContext(samples)
            }
        }
    }

    @Synchronized fun shutdown(context:Context){val iq=connectIQ?:return;runCatching{iq.unregisterAllForEvents()};runCatching{iq.shutdown(context.applicationContext)};connectIQ=null;initialized=false;initializing=false;statusText="Garmin bridge stopped"}
}
