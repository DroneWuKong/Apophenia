package com.dronewukong.apophenia.ingest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
class ObservationReceiver:BroadcastReceiver(){override fun onReceive(context:Context,intent:Intent?){if(intent?.action!="com.dronewukong.apophenia.LOG_OBSERVATION")return;val timestamp=System.currentTimeMillis();val pending=goAsync();val label=intent.getStringExtra("label")?:"External observation";val note=intent.getStringExtra("note")?:"";val kind=runCatching{ObservationKind.valueOf(intent.getStringExtra("kind")?:"OBSERVATION")}.getOrDefault(ObservationKind.OBSERVATION);ObservationStore.repository(context).log(kind,label,note,timestampMs=timestamp,origin=ObservationOrigin.EXTERNAL,onSaved={pending.finish()})}}
