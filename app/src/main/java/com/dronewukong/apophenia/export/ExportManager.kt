package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationDb
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

object ExportManager {
    private val DATA_SCHEMA_VERSION = ObservationDb.SCHEMA_VERSION
    private const val MANIFEST_SCHEMA = "apophenia.export.manifest.v1"

    /** Legacy plain-JSON route retained for compatibility. New UI routes use [prepareBundle]. */
    fun exportJson(db: ObservationDb, dir: File): File {
        requireLiveDatabase(db)
        dir.mkdirs()
        return File(dir, "apophenia-export-${System.currentTimeMillis()}.json").apply {
            writeText(dataJson(db, System.currentTimeMillis(), ExportTier.DATA_ONLY).toString(2))
        }
    }

    fun prepareBundle(
        context: Context,
        db: ObservationDb,
        tier: ExportTier,
        dir: File,
        nowMs: Long = System.currentTimeMillis(),
        evidenceMaterializer: ExportEvidenceMaterializer = AndroidExportEvidenceMaterializer(context)
    ): PreparedExport {
        requireLiveDatabase(db)
        require(tier == ExportTier.DATA_ONLY || tier == ExportTier.FULL_EVIDENCE) {
            "${tier.displayName} is built by its dedicated exporter"
        }
        dir.mkdirs()
        val payloads = mutableListOf(
            ExportPayload(
                path = "data/apophenia-data.json",
                bytes = dataJson(db, nowMs, tier).toString(2).toByteArray(Charsets.UTF_8)
            )
        )
        if (tier == ExportTier.FULL_EVIDENCE) {
            payloads += evidenceMaterializer.sensitivePayloads(db.allSensitiveContext(100_000))
            payloads += evidenceMaterializer.mediaPayloads(db.mediaAssets(includePurged = false, limit = 10_000))
            payloads += ExportInventoryMaterializer(context, db).payload()
        }
        return preparePayloadBundle(tier, dir, nowMs, payloads)
    }

    internal fun preparePayloadBundle(
        tier: ExportTier,
        dir: File,
        nowMs: Long,
        payloads: MutableList<ExportPayload>
    ): PreparedExport {
        dir.mkdirs()
        validatePayloads(payloads)
        val entries = payloads.sortedBy { it.path }.map { payload ->
            ExportManifestEntry(
                path = payload.path,
                sizeBytes = payload.bytes.size.toLong(),
                sha256 = sha256(payload.bytes),
                containsRawAv = payload.containsRawAv,
                containsTier2Contents = payload.containsTier2Contents
            )
        }
        val manifest = ExportManifest(nowMs, tier, DATA_SCHEMA_VERSION, entries)
        val target = File(dir, "apophenia-${tier.name.lowercase().replace('_', '-')}-$nowMs.zip")
        val temporary = File(dir, ".${target.name}.tmp")
        if (temporary.exists()) check(temporary.delete()) { "Could not clear incomplete export" }
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifestJson(manifest).toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                payloads.sortedBy { it.path }.forEach { payload ->
                    zip.putNextEntry(ZipEntry(payload.path))
                    zip.write(payload.bytes)
                    zip.closeEntry()
                }
            }
            if (target.exists()) check(target.delete()) { "Could not replace prepared export" }
            check(temporary.renameTo(target)) { "Could not commit prepared export" }
            val verified = verifyBundle(target)
            check(verified == manifest) { "Prepared export manifest changed during verification" }
            return PreparedExport(target, sha256(target), manifest)
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        } finally {
            payloads.forEach { payload ->
                if (payload.containsRawAv || payload.containsTier2Contents) payload.bytes.fill(0)
            }
        }
    }

    /** Verifies every declared payload and rejects undeclared or duplicated ZIP entries. */
    fun verifyBundle(bundle: File): ExportManifest {
        require(bundle.isFile) { "Export bundle is missing" }
        ZipFile(bundle).use { zip ->
            val allEntries = zip.entries().asSequence().toList()
            val names = allEntries.map { it.name }
            require(names.size == names.distinct().size) { "Export contains duplicate paths" }
            require(names.all(::safePath)) { "Export contains an unsafe path" }
            val manifestEntry = zip.getEntry("manifest.json") ?: error("Export manifest is missing")
            val manifest = parseManifest(zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() })
            val expected = manifest.entries.map { it.path }.toSet() + "manifest.json"
            require(names.toSet() == expected) { "Export contents do not match the manifest" }
            manifest.entries.forEach { expectedEntry ->
                val entry = zip.getEntry(expectedEntry.path) ?: error("Missing ${expectedEntry.path}")
                require(entry.size == expectedEntry.sizeBytes) { "Size mismatch for ${expectedEntry.path}" }
                val digest = zip.getInputStream(entry).use(::sha256)
                require(digest == expectedEntry.sha256) { "Hash mismatch for ${expectedEntry.path}" }
            }
            return manifest
        }
    }

    private fun dataJson(db: ObservationDb, exportedAtMs: Long, bundleTier: ExportTier): JSONObject {
        val root = JSONObject()
            .put("schema", DATA_SCHEMA_VERSION)
            .put("exportedAtMs", exportedAtMs)
            .put("bundleTier", bundleTier.name)
            .put("payloadScope", ExportTier.DATA_ONLY.name)
            .put("rawAvIncludedInThisFile", false)
            .put("tier2ContentsIncludedInThisFile", false)
        val observations = JSONArray()
        db.observations(100_000).forEach { observation ->
            val contexts = JSONArray()
            db.contextForObservation(observation.id).forEach { contexts.put(sampleJson(it)) }
            observations.put(
                JSONObject()
                    .put("id", observation.id).put("timestampMs", observation.timestampMs).put("kind", observation.kind.name)
                    .put("label", observation.label).put("note", observation.note).put("severity", observation.severity)
                    .put("confidence", observation.confidence).put("origin", observation.origin.name)
                    .put("externalEventId", observation.externalEventId).put("vibeRating", observation.vibeRating)
                    .put("egress", observation.egress).put("context", contexts)
            )
        }
        root.put("observations", observations)

        val hypotheses = JSONArray()
        db.hypotheses(100_000).forEach { hypothesis ->
            val evaluations = JSONArray()
            db.hypothesisEvaluations(hypothesis.id, 100_000).forEach { evaluation ->
                evaluations.put(
                    JSONObject()
                        .put("evaluatedAtMs", evaluation.evaluatedAtMs).put("analysisSignature", evaluation.analysisSignature)
                        .put("outcome", evaluation.outcome.name).put("eventCount", evaluation.eventCount)
                        .put("controlCount", evaluation.controlCount).put("adjustedP", evaluation.adjustedP)
                        .put("delta", evaluation.delta).put("comparisonsTested", evaluation.comparisonsTested)
                        .put("summary", evaluation.summary)
                )
            }
            hypotheses.put(
                JSONObject()
                    .put("id", hypothesis.id).put("createdAtMs", hypothesis.createdAtMs)
                    .put("eventLabel", hypothesis.eventLabel).put("cohortId", hypothesis.cohortId)
                    .put("metric", hypothesis.metric).put("direction", hypothesis.direction.name)
                    .put("windowStartMs", hypothesis.windowStartMs).put("windowEndMs", hypothesis.windowEndMs)
                    .put("lockedAtMs", hypothesis.lockedAtMs).put("enabled", hypothesis.enabled)
                    .put("note", hypothesis.note).put("source", hypothesis.source.name).put("evaluations", evaluations)
            )
        }
        root.put("hypotheses", hypotheses)

        val allContext = db.allContext(100_000)
        root.put("contextSamples", JSONArray().also { rows -> allContext.forEach { rows.put(sampleJson(it)) } })
        root.put("controls", JSONArray().also { rows -> allContext.filter { it.isControl }.forEach { rows.put(sampleJson(it)) } })
        root.put("sessions", JSONArray().also { rows -> db.sessions().forEach { session ->
            rows.put(
                JSONObject().put("id", session.id).put("type", session.type.name).put("startedAtMs", session.startedAtMs)
                    .put("endedAtMs", session.endedAtMs).put("identityHash", session.identityHash)
                    .put("status", session.status.name).put("metadata", session.metadata)
            )
        } })
        root.put("sessionContext", JSONArray().also { rows ->
            allContext.filter { !it.isControl && it.observationId == null && it.sessionId != null }.forEach { rows.put(sampleJson(it)) }
        })
        root.put("sessionEvents", JSONArray().also { rows -> db.sessionEvents().forEach { event ->
            rows.put(
                JSONObject().put("timestampMs", event.timestampMs).put("sessionId", event.sessionId)
                    .put("eventType", event.eventType).put("severity", event.severity)
                    .put("text", event.text).put("metadata", event.metadata)
            )
        } })
        root.put("mediaInventory", JSONArray().also { rows -> db.mediaAssets(includePurged = true, limit = 10_000).forEach { asset ->
            rows.put(
                JSONObject().put("id", asset.id).put("observationId", asset.observationId)
                    .put("mediaType", asset.mediaType.name).put("streamId", asset.streamId)
                    .put("createdAtMs", asset.createdAtMs).put("retentionUntilMs", asset.retentionUntilMs)
                    .put("keepForever", asset.keepForever).put("status", asset.status.name)
                    .put("ciphertextSha256", asset.ciphertextSha256).put("sizeBytes", asset.sizeBytes)
            )
        } })
        root.put("purgeLedger", JSONArray().also { rows -> db.purgeLedger(100_000).forEach { entry ->
            rows.put(
                JSONObject().put("id", entry.id).put("mediaId", entry.mediaId)
                    .put("observationId", entry.observationId).put("mediaType", entry.mediaType.name)
                    .put("purgedAtMs", entry.purgedAtMs).put("reason", entry.reason)
                    .put("bytesDeleted", entry.bytesDeleted).put("derivedMetricsRetained", entry.derivedMetricsRetained)
            )
        } })
        return root
    }

    private fun manifestJson(manifest: ExportManifest): JSONObject = JSONObject()
        .put("schema", MANIFEST_SCHEMA)
        .put("createdAtMs", manifest.createdAtMs)
        .put("tier", manifest.tier.name)
        .put("dataSchemaVersion", manifest.schemaVersion)
        .put("entryCount", manifest.entries.size)
        .put("totalPayloadBytes", manifest.totalBytes)
        .put("containsRawAv", manifest.containsRawAv)
        .put("containsTier2Contents", manifest.containsTier2Contents)
        .put("files", JSONArray().also { files -> manifest.entries.forEach { entry ->
            files.put(
                JSONObject().put("path", entry.path).put("sizeBytes", entry.sizeBytes)
                    .put("sha256", entry.sha256).put("containsRawAv", entry.containsRawAv)
                    .put("containsTier2Contents", entry.containsTier2Contents)
            )
        } })

    private fun parseManifest(raw: String): ExportManifest {
        val json = JSONObject(raw)
        require(json.getString("schema") == MANIFEST_SCHEMA) { "Unsupported export manifest" }
        val files = json.getJSONArray("files")
        val entries = buildList {
            repeat(files.length()) { index ->
                val file = files.getJSONObject(index)
                add(
                    ExportManifestEntry(
                        path = file.getString("path"),
                        sizeBytes = file.getLong("sizeBytes"),
                        sha256 = file.getString("sha256"),
                        containsRawAv = file.getBoolean("containsRawAv"),
                        containsTier2Contents = file.getBoolean("containsTier2Contents")
                    )
                )
            }
        }
        return ExportManifest(
            createdAtMs = json.getLong("createdAtMs"),
            tier = ExportTier.valueOf(json.getString("tier")),
            schemaVersion = json.getInt("dataSchemaVersion"),
            entries = entries
        )
    }

    private fun sampleJson(sample: ContextSample) = JSONObject()
        .put("timestampMs", sample.timestampMs).put("observationId", sample.observationId)
        .put("source", sample.source).put("metric", sample.metric).put("value", sample.value)
        .put("unit", sample.unit).put("isControl", sample.isControl).put("metadata", sample.metadata)
        .put("captureId", sample.captureId).put("phase", sample.phase.name).put("sessionId", sample.sessionId)

    private fun validatePayloads(payloads: List<ExportPayload>) {
        require(payloads.isNotEmpty()) { "Export has no payloads" }
        require(payloads.map { it.path }.distinct().size == payloads.size) { "Export payload paths must be unique" }
        require(payloads.all { safePath(it.path) && it.path != "manifest.json" }) { "Export payload path is unsafe" }
    }

    private fun safePath(path: String): Boolean = path.isNotBlank() &&
        !path.startsWith('/') && !path.startsWith('\\') &&
        !path.contains("../") && !path.contains("..\\") &&
        !path.contains(':') && path.split('/', '\\').none { it == ".." }

    private fun requireLiveDatabase(db: ObservationDb) {
        require(!db.isDemoDatabase) { "DEMO DATA is excluded from live exports" }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
