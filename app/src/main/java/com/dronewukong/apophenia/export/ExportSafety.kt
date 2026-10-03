package com.dronewukong.apophenia.export

import com.dronewukong.apophenia.data.EvidenceSeal
import com.dronewukong.apophenia.data.EvidenceSealScope
import com.dronewukong.apophenia.data.ExportAuditEntry
import com.dronewukong.apophenia.data.ExportOutcome
import com.dronewukong.apophenia.data.ExportRoute
import com.dronewukong.apophenia.data.ObservationDb
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

object ExportReleasePolicy {
    const val RELEASE_PHRASE = "RELEASE SEALED EVIDENCE"

    fun sealsRequiringRelease(db: ObservationDb, prepared: PreparedExport): List<EvidenceSeal> {
        val seals = db.evidenceSeals()
        return seals.filter { seal ->
            seal.scope == EvidenceSealScope.GLOBAL || prepared.includesAllEvidence || prepared.manifest.tier == ExportTier.EJECT ||
                (seal.observationId != null && seal.observationId in prepared.observationIds)
        }
    }

    fun record(
        db: ObservationDb,
        prepared: PreparedExport,
        route: ExportRoute,
        outcome: ExportOutcome,
        detail: String,
        occurredAtMs: Long = System.currentTimeMillis()
    ): Long = db.insertExportAudit(
        ExportAuditEntry(
            occurredAtMs = occurredAtMs,
            tier = prepared.manifest.tier.name,
            route = route,
            outcome = outcome,
            bundleSha256 = prepared.bundleSha256,
            bundleName = prepared.bundle.name,
            payloadCount = prepared.manifest.entries.size,
            totalPayloadBytes = prepared.manifest.totalBytes,
            containsRawAv = prepared.manifest.containsRawAv,
            containsTier2Contents = prepared.manifest.containsTier2Contents,
            scope = when {
                prepared.includesAllEvidence -> "all"
                prepared.observationIds.isEmpty() -> "none"
                else -> prepared.observationIds.sorted().joinToString(",", prefix = "events:")
            },
            detail = detail
        )
    )
}

object DossierScrubber {
    fun prepareScrubbedCopy(prepared: PreparedExport, dir: File, nowMs: Long = System.currentTimeMillis()): PreparedExport {
        require(prepared.manifest.tier == ExportTier.SINGLE_EVENT_DOSSIER) { "Only event dossiers support scrub-before-share" }
        val verified = ExportManager.verifyBundle(prepared.bundle)
        require(verified == prepared.manifest) { "Dossier changed after preview" }
        val payloads = mutableListOf<ExportPayload>()
        ZipFile(prepared.bundle).use { zip ->
            prepared.manifest.entries.forEach { entry ->
                if (entry.containsRawAv) return@forEach
                val bytes = zip.getInputStream(zip.getEntry(entry.path)).use { it.readBytes() }
                when {
                    entry.path.startsWith("tier2/") -> Unit
                    entry.containsTier2Contents && entry.path.contains("omniprobe") -> payloads += ExportPayload(entry.path, redactInventory(bytes))
                    entry.containsTier2Contents -> Unit
                    else -> payloads += ExportPayload(entry.path, bytes)
                }
            }
        }
        return ExportManager.preparePayloadBundle(
            ExportTier.SCRUBBED_DOSSIER,
            dir,
            nowMs,
            payloads,
            prepared.observationIds
        )
    }

    private fun redactInventory(bytes: ByteArray): ByteArray {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        val events = root.optJSONArray("events") ?: JSONArray()
        repeat(events.length()) { eventIndex ->
            val event = events.getJSONObject(eventIndex)
            val channels = event.optJSONArray("channels") ?: JSONArray()
            repeat(channels.length()) { channelIndex ->
                val channel = channels.getJSONObject(channelIndex)
                if (channel.optString("gate") in protectedGates) {
                    val count = channel.optJSONArray("values")?.length() ?: 0
                    channel.put("values", JSONArray().put(JSONObject().put("protected", true).put("count", count)))
                }
            }
            val unmatched = event.optJSONArray("unmatchedValues") ?: JSONArray()
            val safe = JSONArray()
            repeat(unmatched.length()) { index ->
                val value = unmatched.getJSONObject(index)
                if (value.optString("source") != "android_tier2") safe.put(value)
            }
            event.put("unmatchedValues", safe)
        }
        root.put("scrubbed", true)
        root.put("scrubBoundary", "Raw AV and Tier-2 plaintext were removed before routing; protected channel counts and ordinary gap accounting remain.")
        return root.toString(2).toByteArray()
    }

    private val protectedGates = setOf(
        "LIVE_NOTIFICATION_CONTENTS_CAPTURE",
        "LIVE_CALENDAR_CONTENTS_CAPTURE",
        "LIVE_CONTACTS_CONTENTS_CAPTURE",
        "LIVE_MESSAGE_METADATA_CAPTURE"
    )
}
