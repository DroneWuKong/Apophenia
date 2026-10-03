package com.dronewukong.apophenia.export

import com.dronewukong.apophenia.data.ObservationDb
import org.json.JSONArray
import org.json.JSONObject

/**
 * Human- and machine-readable views over the ordinary data-only export surface.
 *
 * These payloads never read sensitive_context and never materialize attachment or AV bytes.
 * The nested JSON export remains canonical; the CSV files are flat analysis conveniences.
 */
internal object AnalysisExportPayloads {
    fun build(db: ObservationDb, exportedAtMs: Long): List<ExportPayload> {
        val observations = db.observations(100_000)
        val context = db.allContext(100_000)
        val hypotheses = db.hypotheses(100_000)
        val evaluations = hypotheses.flatMap { db.hypothesisEvaluations(it.id, 100_000) }
        val sessions = db.sessions(100_000)
        val sessionEvents = db.sessionEvents(limit = 100_000)

        return listOf(
            textPayload(
                "analysis/README.md",
                analysisReadme(
                    exportedAtMs = exportedAtMs,
                    observationCount = observations.size,
                    contextCount = context.size,
                    hypothesisCount = hypotheses.size,
                    sessionCount = sessions.size
                )
            ),
            textPayload(
                "analysis/observations.csv",
                csv(
                    listOf(
                        "id", "timestamp_ms", "kind", "label", "note", "severity", "confidence",
                        "origin", "external_event_id", "vibe_rating", "egress"
                    ),
                    observations.map {
                        listOf(
                            it.id, it.timestampMs, it.kind.name, it.label, it.note, it.severity, it.confidence,
                            it.origin.name, it.externalEventId, it.vibeRating, it.egress
                        )
                    }
                )
            ),
            textPayload(
                "analysis/context-samples.csv",
                csv(
                    listOf(
                        "id", "timestamp_ms", "observation_id", "is_control", "source", "metric", "value",
                        "unit", "metadata", "capture_id", "phase", "session_id"
                    ),
                    context.map {
                        listOf(
                            it.id, it.timestampMs, it.observationId, it.isControl, it.source, it.metric, it.value,
                            it.unit, it.metadata, it.captureId, it.phase.name, it.sessionId
                        )
                    }
                )
            ),
            textPayload(
                "analysis/hypotheses.csv",
                csv(
                    listOf(
                        "id", "created_at_ms", "event_label", "cohort_id", "metric", "direction",
                        "window_start_ms", "window_end_ms", "locked_at_ms", "enabled", "note", "source"
                    ),
                    hypotheses.map {
                        listOf(
                            it.id, it.createdAtMs, it.eventLabel, it.cohortId, it.metric, it.direction.name,
                            it.windowStartMs, it.windowEndMs, it.lockedAtMs, it.enabled, it.note, it.source.name
                        )
                    }
                )
            ),
            textPayload(
                "analysis/hypothesis-evaluations.csv",
                csv(
                    listOf(
                        "id", "hypothesis_id", "evaluated_at_ms", "analysis_signature", "outcome", "event_count",
                        "control_count", "adjusted_p", "delta", "comparisons_tested", "summary"
                    ),
                    evaluations.map {
                        listOf(
                            it.id, it.hypothesisId, it.evaluatedAtMs, it.analysisSignature, it.outcome.name,
                            it.eventCount, it.controlCount, it.adjustedP, it.delta, it.comparisonsTested, it.summary
                        )
                    }
                )
            ),
            textPayload(
                "analysis/sessions.csv",
                csv(
                    listOf("id", "type", "started_at_ms", "ended_at_ms", "identity_hash", "status", "metadata"),
                    sessions.map {
                        listOf(
                            it.id, it.type.name, it.startedAtMs, it.endedAtMs, it.identityHash, it.status.name,
                            it.metadata
                        )
                    }
                )
            ),
            textPayload(
                "analysis/session-events.csv",
                csv(
                    listOf("id", "timestamp_ms", "session_id", "event_type", "severity", "text", "metadata"),
                    sessionEvents.map {
                        listOf(it.id, it.timestampMs, it.sessionId, it.eventType, it.severity, it.text, it.metadata)
                    }
                )
            ),
            textPayload("analysis/data-dictionary.json", dataDictionary().toString(2))
        )
    }

    private fun analysisReadme(
        exportedAtMs: Long,
        observationCount: Int,
        contextCount: Int,
        hypothesisCount: Int,
        sessionCount: Int
    ) = """
        # Apophenia analysis pack

        Exported at Unix epoch milliseconds: $exportedAtMs

        This directory is the flat, analysis-friendly view of the default data-only export. It contains
        $observationCount observations, $contextCount context samples, $hypothesisCount registered hypotheses,
        and $sessionCount drive or flight sessions. It contains no raw audio/video, no Tier-2 plaintext contents,
        and no inbound attachment bytes.

        ## Choose the representation

        - `../data/apophenia-data.json` is the canonical nested export for scripts, notebooks, and lossless import.
        - The CSV files in this directory are flat UTF-8 tables for spreadsheets, R, Python, Julia, MATLAB, and BI tools.
        - `data-dictionary.json` names every CSV column and the rules that matter during analysis.
        - The separate raw SQLite action produces a checkpointed `.db` for SQL tools; it is not embedded in this ZIP.
        - Selected-event report mode produces human-readable HTML/PDF plus JSON, CSV, and SVG.

        ## Analysis rules that must travel with the data

        - Unix timestamps are milliseconds. Empty CSV cells represent null or unavailable values.
        - `capture_id` groups dense samples from one event/control window. Rows sharing a capture are not independent observations.
        - `phase=POST` is reconstruction context and must not be used as an event predictor.
        - `is_control=true` identifies jittered control windows captured through the same pipeline.
        - Device, adapter, and airframe identifiers are locally keyed hashes, not raw identifiers.
        - Associations are descriptive. Correlation is not causation, and multiple comparisons require correction.
    """.trimIndent() + "\n"

    private fun dataDictionary(): JSONObject = JSONObject()
        .put("schema", "apophenia.analysis.pack.v1")
        .put("encoding", "UTF-8")
        .put("nullRepresentation", "empty CSV cell")
        .put("timestampUnit", "Unix epoch milliseconds")
        .put("canonicalPayload", "data/apophenia-data.json")
        .put("rules", JSONArray(listOf(
            "capture_id groups dense samples and is the unit of independence",
            "phase POST is excluded from predictor calculations",
            "is_control marks jittered control windows",
            "identifiers are locally keyed hashes",
            "associations are descriptive and require multiple-comparisons disclosure"
        )))
        .put("tables", JSONObject()
            .put("observations.csv", JSONArray(listOf(
                "id", "timestamp_ms", "kind", "label", "note", "severity", "confidence", "origin",
                "external_event_id", "vibe_rating", "egress"
            )))
            .put("context-samples.csv", JSONArray(listOf(
                "id", "timestamp_ms", "observation_id", "is_control", "source", "metric", "value", "unit",
                "metadata", "capture_id", "phase", "session_id"
            )))
            .put("hypotheses.csv", JSONArray(listOf(
                "id", "created_at_ms", "event_label", "cohort_id", "metric", "direction", "window_start_ms",
                "window_end_ms", "locked_at_ms", "enabled", "note", "source"
            )))
            .put("hypothesis-evaluations.csv", JSONArray(listOf(
                "id", "hypothesis_id", "evaluated_at_ms", "analysis_signature", "outcome", "event_count",
                "control_count", "adjusted_p", "delta", "comparisons_tested", "summary"
            )))
            .put("sessions.csv", JSONArray(listOf(
                "id", "type", "started_at_ms", "ended_at_ms", "identity_hash", "status", "metadata"
            )))
            .put("session-events.csv", JSONArray(listOf(
                "id", "timestamp_ms", "session_id", "event_type", "severity", "text", "metadata"
            )))
        )

    private fun textPayload(path: String, value: String) = ExportPayload(path, value.toByteArray(Charsets.UTF_8))

    private fun csv(headers: List<String>, rows: List<List<Any?>>): String = buildString {
        appendLine(headers.joinToString(","))
        rows.forEach { row ->
            require(row.size == headers.size) { "CSV row does not match its header" }
            appendLine(row.joinToString(",") { csvCell(it) })
        }
    }

    private fun csvCell(value: Any?): String {
        if (value == null) return ""
        val raw = when (value) {
            is Boolean -> value.toString().lowercase()
            else -> value.toString()
        }
        return if (raw.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${raw.replace("\"", "\"\"")}\""
        } else raw
    }
}
