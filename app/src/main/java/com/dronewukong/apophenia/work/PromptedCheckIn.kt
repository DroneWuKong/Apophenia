package com.dronewukong.apophenia.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.ui.MainActivity
import java.util.concurrent.TimeUnit
import kotlin.random.Random

object PromptedCheckInState {
    private const val PREFS = "prompted_check_ins"
    private const val ENABLED = "enabled"
    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
}

object PromptedCheckInScheduler {
    private const val UNIQUE = "apophenia-prompted-check-in"

    fun setEnabled(context: Context, enabled: Boolean) {
        PromptedCheckInState.setEnabled(context, enabled)
        if (enabled) scheduleNext(context, ExistingWorkPolicy.REPLACE) else WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }

    fun ensureScheduled(context: Context) {
        if (PromptedCheckInState.isEnabled(context)) scheduleNext(context, ExistingWorkPolicy.KEEP)
    }

    fun scheduleNext(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE, random: Random = Random.Default) {
        if (!PromptedCheckInState.isEnabled(context)) return
        val delayMinutes = random.nextLong(180, 361)
        val request = OneTimeWorkRequestBuilder<PromptedCheckInWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, policy, request)
    }
}

class PromptedCheckInWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!PromptedCheckInState.isEnabled(applicationContext)) return Result.success()
        PromptedCheckInNotifications.show(applicationContext)
        PromptedCheckInScheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}

class PromptedCheckInReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_NOTHING_UNUSUAL) return
        val capturedAt = System.currentTimeMillis()
        val request = OneTimeWorkRequestBuilder<ControlSampleWorker>()
            .setInputData(
                Data.Builder()
                    .putString(ControlSampleWorker.KEY_CONTROL_SOURCE, ControlSampleWorker.SOURCE_PROMPTED)
                    .putLong(ControlSampleWorker.KEY_CAPTURED_AT, capturedAt)
                    .build()
            )
            .build()
        WorkManager.getInstance(context).enqueue(request)
        context.getSystemService(NotificationManager::class.java).cancel(PromptedCheckInNotifications.NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_NOTHING_UNUSUAL = "com.dronewukong.apophenia.checkin.NOTHING_UNUSUAL"
    }
}

private object PromptedCheckInNotifications {
    const val NOTIFICATION_ID = 73
    private const val CHANNEL_ID = "apophenia_check_ins"

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Apophenia neutral check-ins", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context,
            73,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val neutral = PendingIntent.getBroadcast(
            context,
            74,
            Intent(context, PromptedCheckInReceiver::class.java).setAction(PromptedCheckInReceiver.ACTION_NOTHING_UNUSUAL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_weird)
                .setContentTitle("Quick neutral check-in")
                .setContentText("Anything unusual right now?")
                .setContentIntent(open)
                .setAutoCancel(true)
                .addAction(0, "Nothing unusual", neutral)
                .build()
        )
    }
}
