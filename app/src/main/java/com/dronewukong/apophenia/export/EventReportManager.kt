package com.dronewukong.apophenia.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.media.MediaEvidenceReader
import com.dronewukong.apophenia.omniprobe.OmniprobeChannel
import com.dronewukong.apophenia.omniprobe.OmniprobeInspector
import com.dronewukong.apophenia.omniprobe.OmniprobeSnapshot
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class ReportEvent(
    val observation: Observation,
    val context: List<ContextSample>,
    val inventory: OmniprobeSnapshot
)

data class ReportFinding(val tier: String, val text: String)

interface ReportPdfRenderer {
    fun render(events: List<ReportEvent>, findings: List<ReportFinding>, stills: Map<Long, ByteArray>): ByteArray
}

/** Builds standard, self-contained event dossiers and selected-event HTML/PDF reports. */
class EventReportManager(
    context: Context,
    private val evidenceMaterializer: ExportEvidenceMaterializer = AndroidExportEvidenceMaterializer(context),
    private val pdfRenderer: ReportPdfRenderer = AndroidReportPdfRenderer()
) {
    private val app = context.applicationContext

    fun prepareDossier(
        db: ObservationDb,
        observationId: Long,
        dir: File,
        nowMs: Long = System.currentTimeMillis()
    ): PreparedExport {
        requireLive(db)
        val observation = observation(db, observationId)
        val context = db.contextForObservation(observationId).sortedBy { it.timestampMs }
        val payloads = mutableListOf(
            ExportPayload("dossier/event.json", dossierJson(db, observation, context).toString(2).toByteArray()),
            ExportPayload("dossier/summary.txt", dossierSummary(db, observation, context).toByteArray()),
            ExportPayload("dossier/context.csv", contextCsv(context).toByteArray()),
            ExportPayload("dossier/charts/derived-metrics.svg", derivedMetricSvg(listOf(observation to context)).toByteArray()),
            ExportInventoryMaterializer(app, db).payloadForEvent(observationId)
        )
        payloads += evidenceMaterializer.sensitivePayloads(db.sensitiveContextForObservation(observationId))
        payloads += evidenceMaterializer.mediaPayloads(db.mediaAssets(observationId, includePurged = false, limit = 10_000))
        payloads += rfPayloads(context)
        return ExportManager.preparePayloadBundle(ExportTier.SINGLE_EVENT_DOSSIER, dir, nowMs, payloads)
    }

    fun prepareReport(
        db: ObservationDb,
        observationIds: Set<Long>,
        includeAvStills: Boolean,
        dir: File,
        nowMs: Long = System.currentTimeMillis()
    ): PreparedExport {
        requireLive(db)
        require(observationIds.isNotEmpty()) { "Select at least one event" }
        require(observationIds.size <= 100) { "Reports are limited to 100 selected events" }
        val selected = db.observations(100_000).filter { it.id in observationIds }.sortedBy { it.timestampMs }
        require(selected.size == observationIds.size) { "One or more selected events no longer exist" }
        val inspector = OmniprobeInspector(app, db)
        val events = selected.map { observation ->
            ReportEvent(observation, db.contextForObservation(observation.id).sortedBy { it.timestampMs }, inspector.inspect(observation))
        }
        val findings = findings(db, events)
        val stills = if (includeAvStills) stills(db, selected) else emptyMap()
        val payloads = mutableListOf(
            ExportPayload("report/report.html", reportHtml(events, findings, stills).toByteArray()),
            ExportPayload("report/report.pdf", pdfRenderer.render(events, findings, stills)),
            ExportPayload("report/events.json", reportJson(events, findings).toString(2).toByteArray()),
            ExportPayload("report/context.csv", contextCsv(events.flatMap { it.context }).toByteArray()),
            ExportPayload("report/charts/derived-metrics.svg", derivedMetricSvg(events.map { it.observation to it.context }).toByteArray())
        )
        stills.forEach { (eventId, jpeg) ->
            payloads += ExportPayload("report/stills/event-$eventId.jpg", jpeg, containsRawAv = true)
        }
        return ExportManager.preparePayloadBundle(ExportTier.REPORT, dir, nowMs, payloads)
    }

    private fun observation(db: ObservationDb, id: Long): Observation = db.observations(100_000).firstOrNull { it.id == id }
        ?: error("Observation $id does not exist")

    private fun dossierJson(db: ObservationDb, observation: Observation, context: List<ContextSample>): JSONObject {
        val sessionIds = context.mapNotNull { it.sessionId }.toSet()
        return JSONObject()
            .put("schema", "apophenia.single-event-dossier.v1")
            .put("boundary", "Descriptive event evidence; post-event values are excluded from predictors and no row establishes causation.")
            .put("observation", observationJson(observation))
            .put("context", JSONArray().also { rows -> context.forEach { rows.put(contextJson(it)) } })
            .put("sessionEvents", JSONArray().also { rows -> sessionIds.forEach { id ->
                db.sessionEvents(id).forEach { event ->
                    rows.put(JSONObject().put("timestampMs", event.timestampMs).put("sessionId", event.sessionId)
                        .put("eventType", event.eventType).put("severity", event.severity)
                        .put("text", event.text).put("metadata", event.metadata))
                }
            } })
            .put("mediaInventory", JSONArray().also { rows -> db.mediaAssets(observation.id, includePurged = true, limit = 10_000).forEach { asset ->
                rows.put(JSONObject().put("id", asset.id).put("type", asset.mediaType.name).put("stream", asset.streamId)
                    .put("status", asset.status.name).put("createdAtMs", asset.createdAtMs)
                    .put("retentionUntilMs", asset.retentionUntilMs).put("keepForever", asset.keepForever)
                    .put("ciphertextSha256", asset.ciphertextSha256).put("sizeBytes", asset.sizeBytes))
            } })
    }

    private fun dossierSummary(db: ObservationDb, observation: Observation, context: List<ContextSample>): String {
        val sources = context.map { it.source }.distinct().sorted()
        val media = db.mediaAssets(observation.id, includePurged = true, limit = 10_000)
        val protected = db.sensitiveContextForObservation(observation.id).size
        val vibe = if (observation.kind == ObservationKind.VIBE) {
            " Vibe rating ${observation.vibeRating}${if (observation.egress) "; egress was logged" else ""}."
        } else ""
        return buildString {
            appendLine("Apophenia single-event dossier")
            appendLine("Event #${observation.id}: ${observation.label}")
            appendLine("Logged at ${Instant.ofEpochMilli(observation.timestampMs)}.$vibe")
            appendLine("${context.size} ordinary context values across ${sources.size} sources: ${sources.joinToString().ifBlank { "none" }}.")
            appendLine("${media.size} raw-media inventory rows and $protected Tier-2 content rows were associated with this event.")
            appendLine("The ZIP manifest states which raw AV/Tier-2 payloads were actually available at export time.")
            appendLine()
            appendLine("Evidence boundary: this is a descriptive snapshot. Correlation is not causation; post-event values are retained for reconstruction but excluded from predictor calculations. Missing channels are explained in the Omniprobe inventory and are not silently treated as normal values.")
        }
    }

    private fun rfPayloads(context: List<ContextSample>): List<ExportPayload> {
        val ids = context.asSequence().filter { it.source == "rf_survey" }.mapNotNull { sample ->
            metadataValue(sample.metadata, "iq_file_id")?.let { it to metadataValue(sample.metadata, "sha256") }
        }.distinctBy { it.first }.toList()
        if (ids.isEmpty()) return emptyList()
        val inventory = JSONArray()
        val payloads = ids.mapNotNull { (id, expectedSha) ->
            val safe = safeToken(id)
            val file = File(app.filesDir, "rf-survey/$safe.iq")
            if (!file.isFile) {
                inventory.put(JSONObject().put("id", id).put("expectedSha256", expectedSha).put("included", false).put("gap", "expired_or_missing"))
                null
            } else {
                val bytes = file.readBytes()
                val actualSha = sha256(bytes)
                require(expectedSha.isNullOrBlank() || actualSha == expectedSha) { "Retained RF IQ hash does not match event metadata" }
                inventory.put(JSONObject().put("id", id).put("expectedSha256", expectedSha).put("actualSha256", actualSha).put("included", true))
                ExportPayload("dossier/rf/$safe.iq", bytes)
            }
        }
        return payloads + ExportPayload(
            "dossier/rf/inventory.json",
            JSONObject().put("schema", "apophenia.dossier.rf.v1").put("files", inventory).toString(2).toByteArray()
        )
    }

    private fun stills(db: ObservationDb, observations: List<Observation>): Map<Long, ByteArray> {
        val reader = MediaEvidenceReader(app)
        return buildMap {
            observations.forEach { observation ->
                val asset = db.mediaAssets(observation.id, includePurged = false, limit = 1_000)
                    .firstOrNull { it.mediaType == MediaType.VIDEO } ?: return@forEach
                runCatching { reader.loadVideo(asset).let { frames -> frames.firstOrNull { it.phase == "pre" } ?: frames.firstOrNull() } }
                    .getOrNull()?.let { put(observation.id, it.jpeg) }
            }
        }
    }

    private fun findings(db: ObservationDb, events: List<ReportEvent>): List<ReportFinding> {
        val cohorts = events.flatMap { event ->
            buildList {
                add("label:${event.observation.label}")
                if (event.observation.egress) add("class:egress")
                if (event.observation.kind == ObservationKind.VIBE && (event.observation.vibeRating ?: 0) >= 3 && !event.observation.egress) add("class:vibe_bad_stayed")
            }
        }.toSet()
        val evaluated = db.hypotheses(100_000).filter { it.cohortId in cohorts }.flatMap { hypothesis ->
            db.hypothesisEvaluations(hypothesis.id, 100_000).takeLast(1).map { evaluation ->
                ReportFinding(evaluation.outcome.name.replace('_', ' '), evaluation.summary)
            }
        }
        if (evaluated.isNotEmpty()) return evaluated
        val minimum = events.size
        return listOf(
            ReportFinding(
                if (minimum < 10) "INTERESTING, NOT YET ESTABLISHED" else "DESCRIPTIVE ONLY",
                "No eligible pre-registered result is stored for this selected set. The timeline and channel differences below are descriptive and are not adjusted evidence."
            )
        )
    }

    private fun reportJson(events: List<ReportEvent>, findings: List<ReportFinding>): JSONObject = JSONObject()
        .put("schema", "apophenia.selected-event-report.v1")
        .put("boundary", "Reports preserve stored values and honest result tiers; selection is not a causal analysis.")
        .put("events", JSONArray().also { rows -> events.forEach { event ->
            rows.put(JSONObject().put("observation", observationJson(event.observation))
                .put("channels", redactedChannels(event.inventory.channels)))
        } })
        .put("findings", JSONArray().also { rows -> findings.forEach { rows.put(JSONObject().put("tier", it.tier).put("text", it.text)) } })

    private fun redactedChannels(channels: List<OmniprobeChannel>): JSONArray = JSONArray().also { rows -> channels.forEach { channel ->
        val protected = channel.gate in protectedReportGates
        rows.put(JSONObject().put("gate", channel.gate.name).put("title", channel.title).put("observed", channel.observed)
            .put("gapReason", channel.gapReason?.name).put("gapDetail", channel.gapDetail)
            .put("values", if (protected) JSONArray().put(JSONObject().put("protected", true).put("count", channel.values.size)) else JSONArray().also { values -> channel.values.forEach { value ->
                values.put(JSONObject().put("timestampMs", value.timestampMs).put("source", value.source).put("metric", value.metric)
                    .put("value", value.renderedValue).put("unit", value.unit).put("captureId", value.captureId).put("phase", value.phase))
            } }))
    } }

    private fun reportHtml(events: List<ReportEvent>, findings: List<ReportFinding>, stills: Map<Long, ByteArray>): String = buildString {
        append("<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>Apophenia report</title>")
        append("<style>body{font:14px system-ui;margin:32px;color:#172033}h1,h2{color:#071426}.boundary{padding:12px;background:#fff4d6;border-left:5px solid #d77b00}.event{page-break-before:always}table{border-collapse:collapse;width:100%;margin:12px 0}th,td{border:1px solid #ccd4e0;padding:6px;text-align:left;vertical-align:top}.gap{color:#854d0e}.tier{font-weight:700}.still{max-width:520px;max-height:360px}</style></head><body>")
        append("<h1>Apophenia selected-event report</h1><p class=\"boundary\">Descriptive local evidence. Correlation is not causation. Post-event rows are excluded from predictors. Tier-2 plaintext is omitted from this report; use a deliberately confirmed dossier/full-evidence package when required.</p>")
        append("<h2>Timeline</h2><ol>")
        events.forEach { append("<li>${html(Instant.ofEpochMilli(it.observation.timestampMs).toString())} — ${html(it.observation.label)}</li>") }
        append("</ol><h2>Findings</h2>")
        findings.forEach { append("<p><span class=\"tier\">${html(it.tier)}</span> — ${html(it.text)}</p>") }
        append("<h2>Derived-metric charts</h2><img src=\"charts/derived-metrics.svg\" alt=\"Selected event derived metrics chart\">")
        events.forEach { event ->
            val observation = event.observation
            append("<section class=\"event\"><h2>Event #${observation.id}: ${html(observation.label)}</h2><p>${html(Instant.ofEpochMilli(observation.timestampMs).toString())}")
            observation.vibeRating?.let { append(" · VIBE $it${if (observation.egress) " · EGRESS" else ""}") }
            append("</p>")
            stills[observation.id]?.let { append("<h3>Retained pre-event still</h3><img class=\"still\" src=\"stills/event-${observation.id}.jpg\" alt=\"Event ${observation.id} retained still\">") }
            append("<h3>Channel inventory</h3><table><tr><th>Channel</th><th>Status</th><th>Values / gap</th></tr>")
            event.inventory.channels.forEach { channel ->
                val protected = channel.gate in protectedReportGates
                val value = when {
                    protected && channel.observed -> "${channel.values.size} protected value(s) omitted"
                    channel.observed -> channel.values.take(8).joinToString("<br>") { "${html(it.metric)} = ${html(it.renderedValue)} ${html(it.unit)} [${html(it.phase)}]" }
                    else -> html(channel.gapDetail)
                }
                append("<tr><td>${html(channel.title)}<br><small>${channel.gate.name}</small></td><td>${if (channel.observed) "observed" else html(channel.gapReason?.name ?: "gap")}</td><td class=\"${if (channel.observed) "" else "gap"}\">$value</td></tr>")
            }
            append("</table><h3>Stored scalar timeline</h3><table><tr><th>Time</th><th>Source</th><th>Metric</th><th>Value</th><th>Phase / capture</th></tr>")
            event.context.forEach { sample ->
                append("<tr><td>${html(Instant.ofEpochMilli(sample.timestampMs).toString())}</td><td>${html(sample.source)}</td><td>${html(sample.metric)}</td><td>${sample.value} ${html(sample.unit)}</td><td>${sample.phase.name}<br><small>${html(sample.captureId)}</small></td></tr>")
            }
            append("</table></section>")
        }
        append("</body></html>")
    }

    private fun derivedMetricSvg(events: List<Pair<Observation, List<ContextSample>>>): String {
        val rows = events.flatMap { (observation, samples) -> samples.filter { it.source in derivedSources && it.phase != ContextPhase.POST }.map { observation to it } }
            .groupBy { it.second.metric }.entries.sortedBy { it.key }.take(12)
        val width = 1_000
        val rowHeight = 64
        val height = 90 + maxOf(1, rows.size) * rowHeight
        return buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\"><rect width=\"100%\" height=\"100%\" fill=\"#f7f9fc\"/><text x=\"24\" y=\"34\" font-family=\"sans-serif\" font-size=\"20\" fill=\"#172033\">Permanent pre/instant derived metrics</text>")
            if (rows.isEmpty()) append("<text x=\"24\" y=\"75\" font-family=\"sans-serif\" font-size=\"15\" fill=\"#566176\">No eligible audio/video derived metrics in this selection.</text>")
            rows.forEachIndexed { index, entry ->
                val y = 72 + index * rowHeight
                val values = entry.value.map { it.second.value }
                val min = values.minOrNull() ?: 0.0
                val max = values.maxOrNull() ?: 0.0
                append("<text x=\"24\" y=\"${y + 18}\" font-family=\"sans-serif\" font-size=\"13\" fill=\"#172033\">${xml(entry.key)}</text><line x1=\"280\" y1=\"${y + 25}\" x2=\"970\" y2=\"${y + 25}\" stroke=\"#c5cede\"/>")
                entry.value.forEachIndexed { point, pair ->
                    val normalized = if (max == min) 0.5 else (pair.second.value - min) / (max - min)
                    val x = 280 + (normalized * 690).toInt()
                    val color = if (pair.second.phase == ContextPhase.POST) "#c2410c" else "#2563eb"
                    append("<circle cx=\"$x\" cy=\"${y + 25}\" r=\"6\" fill=\"$color\"><title>event ${pair.first.id}: ${pair.second.value} ${xml(pair.second.unit)}</title></circle>")
                    if (point < 4) append("<text x=\"$x\" y=\"${y + 48}\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"10\" fill=\"#566176\">#${pair.first.id}</text>")
                }
            }
            append("</svg>")
        }
    }

    private fun observationJson(observation: Observation) = JSONObject()
        .put("id", observation.id).put("timestampMs", observation.timestampMs).put("kind", observation.kind.name)
        .put("label", observation.label).put("note", observation.note).put("severity", observation.severity)
        .put("confidence", observation.confidence).put("origin", observation.origin.name)
        .put("externalEventId", observation.externalEventId).put("vibeRating", observation.vibeRating).put("egress", observation.egress)

    private fun contextJson(sample: ContextSample) = JSONObject()
        .put("timestampMs", sample.timestampMs).put("observationId", sample.observationId).put("source", sample.source)
        .put("metric", sample.metric).put("value", sample.value).put("unit", sample.unit).put("isControl", sample.isControl)
        .put("metadata", sample.metadata).put("captureId", sample.captureId).put("phase", sample.phase.name).put("sessionId", sample.sessionId)

    private fun contextCsv(samples: List<ContextSample>): String = buildString {
        appendLine("timestamp_ms,observation_id,source,metric,value,unit,is_control,capture_id,phase,session_id,metadata")
        samples.sortedBy { it.timestampMs }.forEach { sample ->
            appendLine(listOf(sample.timestampMs, sample.observationId ?: "", sample.source, sample.metric, sample.value, sample.unit, sample.isControl, sample.captureId, sample.phase.name, sample.sessionId ?: "", sample.metadata).joinToString(",") { csv(it.toString()) })
        }
    }

    private fun metadataValue(metadata: String, key: String): String? = metadata.split(';').firstOrNull { it.substringBefore('=') == key }?.substringAfter('=')?.takeIf(String::isNotBlank)
    private fun safeToken(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }.joinToString("").take(120)
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun xml(value: String): String = html(value).replace("'", "&apos;")
    private fun requireLive(db: ObservationDb) = require(!db.isDemoDatabase) { "DEMO DATA is excluded from live exports" }

    companion object {
        private val derivedSources = setOf("audio_derived", "video_derived")
        private val protectedReportGates = setOf(
            com.dronewukong.apophenia.hardware.HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE,
            com.dronewukong.apophenia.hardware.HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE,
            com.dronewukong.apophenia.hardware.HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE,
            com.dronewukong.apophenia.hardware.HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE
        )
    }
}

class AndroidReportPdfRenderer : ReportPdfRenderer {
    override fun render(events: List<ReportEvent>, findings: List<ReportFinding>, stills: Map<Long, ByteArray>): ByteArray {
        val document = PdfDocument()
        try {
            var pageNumber = 1
            fun page(title: String, draw: (android.graphics.Canvas, Paint, Paint) -> Unit) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumber++).create())
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(23, 32, 51); textSize = 11f }
                val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(37, 99, 235); style = Paint.Style.FILL }
                page.canvas.drawColor(Color.WHITE)
                text.textSize = 20f; text.isFakeBoldText = true
                page.canvas.drawText(title.take(70), 36f, 42f, text)
                text.textSize = 11f; text.isFakeBoldText = false
                draw(page.canvas, text, accent)
                document.finishPage(page)
            }
            events.chunked(34).forEachIndexed { index, timelineEvents ->
                page(if (index == 0) "Apophenia selected-event report" else "Timeline continued") { canvas, text, _ ->
                    var y = 76f
                    if (index == 0) {
                        y = lines(canvas, text, "Descriptive local evidence. Correlation is not causation. Post-event values are excluded from predictors. Tier-2 plaintext is omitted.", 36f, y, 74)
                        y += 18f
                    }
                    text.isFakeBoldText = true; canvas.drawText("Timeline", 36f, y, text); text.isFakeBoldText = false; y += 18f
                    timelineEvents.forEach { event -> y = lines(canvas, text, "${Instant.ofEpochMilli(event.observation.timestampMs)} - ${event.observation.label}", 44f, y, 82) }
                }
            }
            events.forEach { event ->
                page("Event #${event.observation.id}: ${event.observation.label}") { canvas, text, accent ->
                    var y = 72f
                    y = lines(canvas, text, Instant.ofEpochMilli(event.observation.timestampMs).toString(), 36f, y, 82)
                    event.observation.vibeRating?.let { y = lines(canvas, text, "VIBE $it${if (event.observation.egress) " - EGRESS" else ""}", 36f, y, 82) }
                    y += 8f
                    val observed = event.inventory.observedChannelCount
                    text.isFakeBoldText = true; canvas.drawText("Channel coverage: $observed/${event.inventory.channels.size}", 36f, y, text); text.isFakeBoldText = false
                    canvas.drawRect(36f, y + 8f, 36f + 520f * observed / event.inventory.channels.size.coerceAtLeast(1), y + 20f, accent)
                    y += 42f
                    text.isFakeBoldText = true; canvas.drawText("Stored values", 36f, y, text); text.isFakeBoldText = false; y += 18f
                    event.context.take(18).forEach { sample ->
                        y = lines(canvas, text, "${sample.source}.${sample.metric} = ${String.format(Locale.US, "%.3f", sample.value)} ${sample.unit} [${sample.phase.name}]", 42f, y, 86)
                    }
                    val derived = event.context.filter { it.source in setOf("audio_derived", "video_derived") && it.phase != ContextPhase.POST }.take(8)
                    if (derived.isNotEmpty() && y < 610f) {
                        y += 8f; text.isFakeBoldText = true; canvas.drawText("Derived metrics", 36f, y, text); text.isFakeBoldText = false; y += 16f
                        val max = derived.maxOf { kotlin.math.abs(it.value) }.coerceAtLeast(1e-9)
                        derived.forEach { sample ->
                            canvas.drawText(sample.metric.take(35), 42f, y, text)
                            canvas.drawRect(270f, y - 9f, 270f + (250f * kotlin.math.abs(sample.value) / max).toFloat(), y + 2f, accent)
                            y += 18f
                        }
                    }
                    stills[event.observation.id]?.let { jpeg ->
                        if (y < 620f) BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { bitmap ->
                            val scale = minOf(520f / bitmap.width, (740f - y) / bitmap.height, 1f)
                            val destination = android.graphics.RectF(36f, y + 8f, 36f + bitmap.width * scale, y + 8f + bitmap.height * scale)
                            canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
                            bitmap.recycle()
                        }
                    }
                }
            }
            findings.chunked(6).forEachIndexed { index, pageFindings ->
                page(if (index == 0) "Findings and evidence tiers" else "Findings continued") { canvas, text, _ ->
                    var y = 76f
                    pageFindings.forEach { finding ->
                        text.isFakeBoldText = true; y = lines(canvas, text, finding.tier, 36f, y, 76); text.isFakeBoldText = false
                        y = lines(canvas, text, finding.text, 44f, y, 74); y += 12f
                    }
                }
            }
            return ByteArrayOutputStream().also(document::writeTo).toByteArray()
        } finally {
            document.close()
        }
    }

    private fun lines(canvas: android.graphics.Canvas, paint: Paint, value: String, x: Float, startY: Float, width: Int): Float {
        var y = startY
        value.chunked(width).forEach { line -> canvas.drawText(line, x, y, paint); y += 15f }
        return y
    }
}
