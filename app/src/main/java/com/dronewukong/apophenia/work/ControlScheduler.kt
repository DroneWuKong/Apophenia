package com.dronewukong.apophenia.work
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlin.random.Random
object ControlScheduler {
 private const val UNIQUE="apophenia-random-control"
 fun ensureScheduled(context:Context,random:Random=Random.Default){enqueue(context,ExistingWorkPolicy.KEEP,random)}
 fun scheduleNext(context:Context,random:Random=Random.Default){enqueue(context,ExistingWorkPolicy.APPEND_OR_REPLACE,random)}
 private fun enqueue(context:Context,policy:ExistingWorkPolicy,random:Random){val delayMinutes=random.nextLong(35,181);val request=OneTimeWorkRequestBuilder<ControlSampleWorker>().setInitialDelay(delayMinutes,TimeUnit.MINUTES).build();WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE,policy,request)}
}
