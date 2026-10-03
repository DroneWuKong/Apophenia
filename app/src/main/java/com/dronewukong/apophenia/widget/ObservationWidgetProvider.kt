package com.dronewukong.apophenia.widget
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.ui.MainActivity
class ObservationWidgetProvider:AppWidgetProvider(){
 override fun onUpdate(context:Context,manager:AppWidgetManager,ids:IntArray){ids.forEach{id->manager.updateAppWidget(id,views(context))}}
 override fun onReceive(context:Context,intent:Intent){super.onReceive(context,intent);when(intent.action){ACTION_WEIRD->{val timestamp=System.currentTimeMillis();val pending=goAsync();ObservationStore.repository(context).log(ObservationKind.WEIRD,"That was weird",timestampMs=timestamp,origin=ObservationOrigin.WIDGET,onSaved={pending.finish()})};ACTION_OBSERVATION->context.startActivity(Intent(context,MainActivity::class.java).apply{addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);putExtra("open_log",true)})}}
 private fun views(context:Context)=RemoteViews(context.packageName,R.layout.widget_observation).apply{setOnClickPendingIntent(R.id.widget_weird,broadcast(context,ACTION_WEIRD,101));setOnClickPendingIntent(R.id.widget_observation,broadcast(context,ACTION_OBSERVATION,102));setOnClickPendingIntent(R.id.widget_open,PendingIntent.getActivity(context,103,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))}
 private fun broadcast(context:Context,action:String,code:Int)=PendingIntent.getBroadcast(context,code,Intent(context,ObservationWidgetProvider::class.java).setAction(action),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
 companion object{const val ACTION_WEIRD="com.dronewukong.apophenia.WIDGET_WEIRD";const val ACTION_OBSERVATION="com.dronewukong.apophenia.WIDGET_OBSERVATION"}
}
