package com.dronewukong.apophenia.ingest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dronewukong.apophenia.data.ObservationCaptureRequest
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.export.ExportTier

object TaskerAutomationContract {
    const val ACTION_LOG = "com.dronewukong.apophenia.TASKER_LOG_OBSERVATION"
    const val ACTION_EXPORT_DATA = "com.dronewukong.apophenia.AUTOMATION_EXPORT_DATA"
    const val ACTION_EXPORT_FULL = "com.dronewukong.apophenia.AUTOMATION_EXPORT_FULL"
    const val EXTRA_LABEL = "label"
    const val EXTRA_NOTE = "note"
    const val EXTRA_KIND = "kind"
    const val EXTRA_EXTERNAL_EVENT_ID = "external_event_id"

    fun exportTier(action: String?): ExportTier? = when (action) {
        ACTION_EXPORT_DATA -> ExportTier.DATA_ONLY
        ACTION_EXPORT_FULL -> ExportTier.FULL_EVIDENCE
        else -> null
    }

    fun captureRequest(intent: Intent, receivedAtMs: Long): ObservationCaptureRequest {
        require(intent.action == ACTION_LOG) { "Unsupported automation action" }
        val kind = runCatching {
            ObservationKind.valueOf(intent.getStringExtra(EXTRA_KIND)?.uppercase() ?: ObservationKind.OBSERVATION.name)
        }.getOrDefault(ObservationKind.OBSERVATION)
        require(kind in setOf(ObservationKind.OBSERVATION, ObservationKind.WEIRD, ObservationKind.COINCIDENCE)) {
            "Tasker capture supports OBSERVATION, WEIRD, or COINCIDENCE"
        }
        return ObservationCaptureRequest(
            timestampMs = receivedAtMs,
            kind = kind,
            label = intent.getStringExtra(EXTRA_LABEL)?.trim()?.take(160).orEmpty().ifBlank { "Tasker observation" },
            note = intent.getStringExtra(EXTRA_NOTE)?.take(4_000).orEmpty(),
            origin = ObservationOrigin.EXTERNAL,
            externalEventId = intent.getStringExtra(EXTRA_EXTERNAL_EVENT_ID)?.trim()?.take(160)?.takeIf(String::isNotBlank)
        )
    }
}

/** Gate-bounded background observation logging. This receiver has no export route. */
class TaskerAutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != TaskerAutomationContract.ACTION_LOG) return
        if (!HardwareGates.isCaptureEnabled(context, HardwareGates.Gate.LIVE_TASKER_CAPTURE)) return
        val receivedAtMs = System.currentTimeMillis()
        val request = runCatching { TaskerAutomationContract.captureRequest(intent, receivedAtMs) }.getOrNull() ?: return
        val pending = goAsync()
        ObservationStore.liveRepository(context).log(request) { pending.finish() }
    }
}
