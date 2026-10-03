package com.dronewukong.apophenia.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dronewukong.apophenia.R
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.data.VibeCapture
import com.dronewukong.apophenia.data.VibeGrade
import com.dronewukong.apophenia.ui.MainActivity

class ObservationWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, views(context)) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val tappedAt = System.currentTimeMillis()
        super.onReceive(context, intent)
        if (intent.action != ACTION_VIBE) return

        val grade = runCatching {
            VibeGrade.fromRating(intent.getIntExtra(EXTRA_VIBE_RATING, 0))
        }.getOrNull() ?: return
        val egress = intent.getBooleanExtra(EXTRA_EGRESS, false)
        if (egress && grade != VibeGrade.FUCKY) return

        val pending = goAsync()
        ObservationStore.repository(context).log(
            VibeCapture.request(
                grade = grade,
                timestampMs = tappedAt,
                egress = egress,
                origin = ObservationOrigin.WIDGET
            ),
            onSaved = { pending.finish() }
        )
    }

    private fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_observation).apply {
        setOnClickPendingIntent(R.id.widget_vibe_1, vibeBroadcast(context, VibeGrade.GOOD, 201))
        setOnClickPendingIntent(R.id.widget_vibe_2, vibeBroadcast(context, VibeGrade.TOLERABLE, 202))
        setOnClickPendingIntent(R.id.widget_vibe_3, vibeBroadcast(context, VibeGrade.BAD, 203))
        setOnClickPendingIntent(R.id.widget_vibe_4, vibeBroadcast(context, VibeGrade.FUCKED, 204))
        setOnClickPendingIntent(R.id.widget_vibe_5, vibeBroadcast(context, VibeGrade.FUCKY, 205))
        setOnClickPendingIntent(
            R.id.widget_egress,
            vibeBroadcast(context, VibeGrade.FUCKY, 206, egress = true)
        )
        setOnClickPendingIntent(
            R.id.widget_open,
            PendingIntent.getActivity(
                context,
                207,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
    }

    private fun vibeBroadcast(
        context: Context,
        grade: VibeGrade,
        requestCode: Int,
        egress: Boolean = false
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, ObservationWidgetProvider::class.java)
            .setAction(ACTION_VIBE)
            .putExtra(EXTRA_VIBE_RATING, grade.rating)
            .putExtra(EXTRA_EGRESS, egress),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val ACTION_VIBE = "com.dronewukong.apophenia.WIDGET_VIBE"
        const val EXTRA_VIBE_RATING = "vibe_rating"
        const val EXTRA_EGRESS = "egress"
    }
}
