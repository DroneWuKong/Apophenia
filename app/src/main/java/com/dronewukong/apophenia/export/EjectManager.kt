package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

enum class EjectWindow(val displayName: String, private val durationMs: Long?) {
    LAST_HOUR("Last hour", 60 * 60_000L),
    LAST_SIX_HOURS("Last 6 hours", 6 * 60 * 60_000L),
    LAST_DAY("Last 24 hours", 24 * 60 * 60_000L),
    ALL("All local evidence", null);

    fun fromMs(nowMs: Long): Long = durationMs?.let { (nowMs - it).coerceAtLeast(0L) } ?: 0L
}

class EjectManager(
    context: Context,
    private val evidenceMaterializer: ExportEvidenceMaterializer = AndroidExportEvidenceMaterializer(context)
) {
    private val app = context.applicationContext

    fun prepare(
        db: ObservationDb,
        window: EjectWindow,
        dir: File,
        nowMs: Long = System.currentTimeMillis()
    ): PreparedExport {
        require(!db.isDemoDatabase) { "DEMO DATA is excluded from EJECT" }
        val fromMs = window.fromMs(nowMs)
        val observations = db.observations(100_000).filter { it.timestampMs in fromMs..nowMs }.sortedBy { it.timestampMs }
        val observationIds = observations.map { it.id }.toSet()
        val context = db.allContext(100_000).filter { sample ->
            sample.timestampMs in fromMs..nowMs || (sample.observationId != null && sample.observationId in observationIds)
        }.sortedBy { it.timestampMs }
        val sensitive = db.allSensitiveContext(100_000).filter { row ->
            row.timestampMs in fromMs..nowMs || (row.observationId != null && row.observationId in observationIds)
        }
        val media = observationIds.flatMap { db.mediaAssets(it, includePurged = false, limit = 10_000) }
        val attachments = observationIds.flatMap { db.observationAttachments(it, limit = 10_000) }
        val payloads = mutableListOf(
            ExportPayload("eject/window.json", windowJson(db, window, fromMs, nowMs, observations, context).toString(2).toByteArray()),
            ExportInventoryMaterializer(app, db).payloadForEvents(observationIds, "eject/omniprobe-window.json")
        )
        val sensitivePayloads = evidenceMaterializer.sensitivePayloads(sensitive)
        val mediaPayloads = evidenceMaterializer.mediaPayloads(media)
        require(sensitive.isEmpty() || sensitivePayloads.any { it.containsTier2Contents }) { "EJECT did not materialize selected Tier-2 evidence" }
        require(media.isEmpty() || mediaPayloads.any { it.containsRawAv }) { "EJECT did not materialize selected retained AV" }
        payloads += sensitivePayloads.map { it.copy(path = "eject/${it.path}") }
        payloads += mediaPayloads.map { it.copy(path = "eject/${it.path}") }
        payloads += ObservationAttachmentStore(app, db).exportPayloads(attachments, prefix = "eject/attachments")
        payloads += rfPayloads(context)
        return ExportManager.preparePayloadBundle(ExportTier.EJECT, dir, nowMs, payloads, observationIds)
    }

    private fun windowJson(
        db: ObservationDb,
        window: EjectWindow,
        fromMs: Long,
        toMs: Long,
        observations: List<com.dronewukong.apophenia.data.Observation>,
        context: List<ContextSample>
    ): JSONObject {
        val sessionIds = context.mapNotNull { it.sessionId }.toSet()
        val sessions = db.sessions(100_000).filter { session ->
            session.id in sessionIds || (session.startedAtMs <= toMs && (session.endedAtMs ?: toMs) >= fromMs)
        }
        return JSONObject()
            .put("schema", "apophenia.eject-window.v1")
            .put("window", window.name).put("fromMs", fromMs).put("toMs", toMs)
            .put("boundary", "Verified full evidence for the selected time window. Route completion is recorded before local evidence is wiped; a sharesheet handoff is not eligible for EJECT.")
            .put("observations", JSONArray().also { rows -> observations.forEach { observation ->
                rows.put(JSONObject().put("id", observation.id).put("timestampMs", observation.timestampMs)
                    .put("kind", observation.kind.name).put("label", observation.label).put("note", observation.note)
                    .put("severity", observation.severity).put("confidence", observation.confidence).put("origin", observation.origin.name)
                    .put("externalEventId", observation.externalEventId).put("vibeRating", observation.vibeRating).put("egress", observation.egress))
            } })
            .put("context", JSONArray().also { rows -> context.forEach { rows.put(contextJson(it)) } })
            .put("sessions", JSONArray().also { rows -> sessions.forEach { session ->
                rows.put(JSONObject().put("id", session.id).put("type", session.type.name).put("startedAtMs", session.startedAtMs)
                    .put("endedAtMs", session.endedAtMs).put("identityHash", session.identityHash).put("status", session.status.name).put("metadata", session.metadata))
            } })
            .put("sessionEvents", JSONArray().also { rows -> db.sessionEvents(limit = 100_000).filter { it.sessionId in sessions.map { session -> session.id } && it.timestampMs in fromMs..toMs }.forEach { event ->
                rows.put(JSONObject().put("timestampMs", event.timestampMs).put("sessionId", event.sessionId).put("eventType", event.eventType)
                    .put("severity", event.severity).put("text", event.text).put("metadata", event.metadata))
            } })
            .put("hypotheses", JSONArray().also { rows -> db.hypotheses(100_000).filter { it.createdAtMs <= toMs }.forEach { hypothesis ->
                rows.put(JSONObject().put("id", hypothesis.id).put("createdAtMs", hypothesis.createdAtMs).put("cohortId", hypothesis.cohortId)
                    .put("eventLabel", hypothesis.eventLabel).put("metric", hypothesis.metric).put("direction", hypothesis.direction.name)
                    .put("windowStartMs", hypothesis.windowStartMs).put("windowEndMs", hypothesis.windowEndMs).put("lockedAtMs", hypothesis.lockedAtMs)
                    .put("note", hypothesis.note).put("evaluations", JSONArray().also { evaluations -> db.hypothesisEvaluations(hypothesis.id, 100_000).forEach { evaluation ->
                        evaluations.put(JSONObject().put("evaluatedAtMs", evaluation.evaluatedAtMs).put("outcome", evaluation.outcome.name)
                            .put("analysisSignature", evaluation.analysisSignature).put("adjustedP", evaluation.adjustedP).put("delta", evaluation.delta)
                            .put("comparisonsTested", evaluation.comparisonsTested).put("summary", evaluation.summary))
                    } }))
            } })
            .put("mediaInventory", JSONArray().also { rows -> observations.forEach { observation -> db.mediaAssets(observation.id, includePurged = true, limit = 10_000).forEach { asset ->
                rows.put(JSONObject().put("id", asset.id).put("observationId", asset.observationId).put("mediaType", asset.mediaType.name)
                    .put("streamId", asset.streamId).put("status", asset.status.name).put("retentionUntilMs", asset.retentionUntilMs)
                    .put("keepForever", asset.keepForever).put("ciphertextSha256", asset.ciphertextSha256).put("sizeBytes", asset.sizeBytes))
            } } })
            .put("attachmentInventory", JSONArray().also { rows -> observations.forEach { observation -> db.observationAttachments(observation.id, limit = 10_000).forEach { attachment ->
                rows.put(JSONObject().put("id", attachment.id).put("observationId", attachment.observationId)
                    .put("mimeType", attachment.mimeType).put("displayName", attachment.displayName)
                    .put("sha256", attachment.sha256).put("sizeBytes", attachment.sizeBytes))
            } } })
            .put("purgeLedger", JSONArray().also { rows -> db.purgeLedger(100_000).forEach { entry ->
                rows.put(JSONObject().put("mediaId", entry.mediaId).put("observationId", entry.observationId).put("mediaType", entry.mediaType.name)
                    .put("purgedAtMs", entry.purgedAtMs).put("reason", entry.reason).put("bytesDeleted", entry.bytesDeleted))
            } })
            .put("evidenceSeals", JSONArray().also { rows -> db.evidenceSeals().forEach { seal ->
                rows.put(JSONObject().put("scope", seal.scope.name).put("observationId", seal.observationId).put("sealedAtMs", seal.sealedAtMs).put("label", seal.label))
            } })
            .put("exportAuditLog", JSONArray().also { rows -> db.exportAuditLog(100_000).forEach { audit ->
                rows.put(JSONObject().put("occurredAtMs", audit.occurredAtMs).put("tier", audit.tier).put("route", audit.route.name)
                    .put("outcome", audit.outcome.name).put("bundleSha256", audit.bundleSha256).put("scope", audit.scope).put("detail", audit.detail))
            } })
    }

    private fun contextJson(sample: ContextSample) = JSONObject()
        .put("timestampMs", sample.timestampMs).put("observationId", sample.observationId).put("isControl", sample.isControl)
        .put("source", sample.source).put("metric", sample.metric).put("value", sample.value).put("unit", sample.unit)
        .put("metadata", sample.metadata).put("captureId", sample.captureId).put("phase", sample.phase.name).put("sessionId", sample.sessionId)

    private fun rfPayloads(context: List<ContextSample>): List<ExportPayload> {
        val ids = context.asSequence().filter { it.source == "rf_survey" }.mapNotNull { sample ->
            metadataValue(sample.metadata, "iq_file_id")?.let { it to metadataValue(sample.metadata, "sha256") }
        }.distinctBy { it.first }.toList()
        if (ids.isEmpty()) return emptyList()
        val inventory = JSONArray()
        val files = ids.mapNotNull { (id, expectedSha) ->
            val safe = safeToken(id)
            val file = File(app.filesDir, "rf-survey/$safe.iq")
            if (!file.isFile) {
                inventory.put(JSONObject().put("id", id).put("expectedSha256", expectedSha).put("included", false).put("gap", "expired_or_missing"))
                null
            } else {
                val bytes = file.readBytes()
                val actual = sha256(bytes)
                require(expectedSha.isNullOrBlank() || actual == expectedSha) { "Retained RF IQ hash does not match event metadata" }
                inventory.put(JSONObject().put("id", id).put("expectedSha256", expectedSha).put("actualSha256", actual).put("included", true))
                ExportPayload("eject/rf/$safe.iq", bytes)
            }
        }
        return files + ExportPayload("eject/rf/inventory.json", JSONObject().put("files", inventory).toString(2).toByteArray())
    }

    private fun metadataValue(metadata: String, key: String): String? = metadata.split(';').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=')?.takeIf(String::isNotBlank)
    private fun safeToken(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }.joinToString("").take(120)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
