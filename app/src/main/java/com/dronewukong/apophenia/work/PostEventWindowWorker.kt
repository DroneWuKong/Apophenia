package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.rolling.RollingRecorderConfig
class PostEventWindowWorker(context: Context, params: WorkerParameters):Worker(context,params){
 override fun doWork():Result{val id=inputData.getLong(KEY_OBSERVATION_ID,-1L);val eventTs=inputData.getLong(KEY_EVENT_TS,-1L);if(id<=0||eventTs<=0)return Result.failure();ObservationStore.repository(applicationContext,inputData.getBoolean(KEY_DEMO_DATABASE,false)).db().copyRollingToObservation(id,eventTs,eventTs+RollingRecorderConfig.POST_WINDOW_MS,ContextPhase.POST);return Result.success()}
 companion object{const val KEY_OBSERVATION_ID="observation_id";const val KEY_EVENT_TS="event_ts";const val KEY_DEMO_DATABASE="demo_database"}
}
