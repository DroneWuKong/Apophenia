package com.dronewukong.apophenia.export

import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationDb
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object ExportManager {
    fun exportJson(db: ObservationDb, dir: File): File {
        require(!db.isDemoDatabase) { "DEMO DATA is excluded from live exports" }
        dir.mkdirs()
        val root = JSONObject().put("schema", 9).put("exportedAtMs", System.currentTimeMillis())
        val observations = JSONArray()
        db.observations(100_000).forEach { observation ->
            val contexts = JSONArray()
            db.contextForObservation(observation.id).forEach { contexts.put(sampleJson(it)) }
            observations.put(JSONObject()
                .put("id", observation.id).put("timestampMs", observation.timestampMs).put("kind", observation.kind.name)
                .put("label", observation.label).put("note", observation.note).put("severity", observation.severity)
                .put("confidence", observation.confidence).put("origin", observation.origin.name)
                .put("externalEventId", observation.externalEventId).put("vibeRating", observation.vibeRating)
                .put("egress", observation.egress).put("context", contexts))
        }
        root.put("observations", observations)

        val hypotheses = JSONArray()
        db.hypotheses(100_000).forEach { hypothesis ->
            val evaluations = JSONArray()
            db.hypothesisEvaluations(hypothesis.id, 100_000).forEach { evaluation ->
                evaluations.put(JSONObject()
                    .put("evaluatedAtMs", evaluation.evaluatedAtMs).put("analysisSignature", evaluation.analysisSignature)
                    .put("outcome", evaluation.outcome.name).put("eventCount", evaluation.eventCount)
                    .put("controlCount", evaluation.controlCount).put("adjustedP", evaluation.adjustedP)
                    .put("delta", evaluation.delta).put("comparisonsTested", evaluation.comparisonsTested)
                    .put("summary", evaluation.summary))
            }
            hypotheses.put(JSONObject()
                .put("id", hypothesis.id).put("createdAtMs", hypothesis.createdAtMs)
                .put("eventLabel", hypothesis.eventLabel).put("cohortId", hypothesis.cohortId)
                .put("metric", hypothesis.metric).put("direction", hypothesis.direction.name)
                .put("windowStartMs", hypothesis.windowStartMs).put("windowEndMs", hypothesis.windowEndMs)
                .put("lockedAtMs", hypothesis.lockedAtMs).put("enabled", hypothesis.enabled)
                .put("note", hypothesis.note).put("source", hypothesis.source.name).put("evaluations", evaluations))
        }
        root.put("hypotheses", hypotheses)

        val allContext = db.allContext(100_000)
        val controls = JSONArray()
        allContext.filter { it.isControl }.forEach { controls.put(sampleJson(it)) }
        root.put("controls", controls)
        val sessions = JSONArray()
        db.sessions().forEach { session -> sessions.put(JSONObject()
            .put("id", session.id).put("type", session.type.name).put("startedAtMs", session.startedAtMs)
            .put("endedAtMs", session.endedAtMs).put("identityHash", session.identityHash)
            .put("status", session.status.name).put("metadata", session.metadata)) }
        root.put("sessions", sessions)
        val sessionContext = JSONArray()
        allContext.filter { !it.isControl && it.observationId == null && it.sessionId != null }.forEach { sessionContext.put(sampleJson(it)) }
        root.put("sessionContext", sessionContext)
        val sessionEvents = JSONArray()
        db.sessionEvents().forEach { event -> sessionEvents.put(JSONObject()
            .put("timestampMs", event.timestampMs).put("sessionId", event.sessionId)
            .put("eventType", event.eventType).put("severity", event.severity)
            .put("text", event.text).put("metadata", event.metadata)) }
        root.put("sessionEvents", sessionEvents)
        return File(dir, "apophenia-export-${System.currentTimeMillis()}.json").apply { writeText(root.toString(2)) }
    }

    private fun sampleJson(sample: ContextSample) = JSONObject()
        .put("timestampMs", sample.timestampMs).put("observationId", sample.observationId)
        .put("source", sample.source).put("metric", sample.metric).put("value", sample.value)
        .put("unit", sample.unit).put("isControl", sample.isControl).put("metadata", sample.metadata)
        .put("captureId", sample.captureId).put("phase", sample.phase.name).put("sessionId", sample.sessionId)
}
