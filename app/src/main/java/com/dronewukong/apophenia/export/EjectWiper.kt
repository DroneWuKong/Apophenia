package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.data.ExportAuditEntry
import com.dronewukong.apophenia.data.ExportOutcome
import com.dronewukong.apophenia.data.ExportRoute
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import java.io.File

data class EjectWipeResult(
    val purgedMediaCount: Int,
    val deletedRfFileCount: Int,
    val deletedRfBytes: Long,
    val deletedAttachmentFileCount: Int = 0,
    val deletedAttachmentBytes: Long = 0
)

class EjectWiper(context: Context) {
    private val app = context.applicationContext

    fun wipeAfterVerifiedRoute(
        db: ObservationDb,
        prepared: PreparedExport,
        route: ExportRoute,
        routeOutcome: ExportOutcome,
        routeDetail: String,
        nowMs: Long = System.currentTimeMillis()
    ): EjectWipeResult {
        require(prepared.manifest.tier == ExportTier.EJECT) { "Only an EJECT package can authorize an EJECT wipe" }
        require(route == ExportRoute.SAF || route == ExportRoute.LAN_DOCUMENT_TREE || route == ExportRoute.LAN_HTTP) {
            "EJECT requires a completed SAF or LAN write; sharesheet handoff is insufficient"
        }
        require(routeOutcome == ExportOutcome.WRITE_COMPLETED || routeOutcome == ExportOutcome.ENDPOINT_ACKNOWLEDGED) {
            "EJECT route has not completed"
        }
        require(ExportManager.verifyBundle(prepared.bundle) == prepared.manifest) { "EJECT bundle no longer matches its preview" }

        ExportReleasePolicy.record(db, prepared, route, routeOutcome, routeDetail, nowMs)
        val activeMedia = db.mediaAssets(limit = 100_000).size
        val purgedMedia = MediaRetentionManager(app, db).scrubAll("EJECT_AFTER_VERIFIED_EXPORT")
        require(purgedMedia == activeMedia) { "Could not delete every retained AV artifact; local evidence was not wiped" }
        val (rfFiles, rfBytes) = deleteRfEvidence()
        val (attachmentFiles, attachmentBytes) = ObservationAttachmentStore(app, db).deleteAll()
        db.wipeEvidenceForEject()
        db.insertExportAudit(
            ExportAuditEntry(
                occurredAtMs = nowMs,
                tier = ExportTier.EJECT.name,
                route = ExportRoute.EJECT_WIPE,
                outcome = ExportOutcome.WIPE_COMPLETED,
                bundleSha256 = prepared.bundleSha256,
                bundleName = prepared.bundle.name,
                payloadCount = prepared.manifest.entries.size,
                totalPayloadBytes = prepared.manifest.totalBytes,
                containsRawAv = prepared.manifest.containsRawAv,
                containsTier2Contents = prepared.manifest.containsTier2Contents,
                scope = "local-evidence-store",
                detail = "AV purge receipts retained=$purgedMedia; RF files deleted=$rfFiles; RF bytes deleted=$rfBytes; attachments deleted=$attachmentFiles; attachment bytes deleted=$attachmentBytes"
            )
        )
        return EjectWipeResult(purgedMedia, rfFiles, rfBytes, attachmentFiles, attachmentBytes)
    }

    private fun deleteRfEvidence(): Pair<Int, Long> {
        val root = File(app.filesDir, "rf-survey")
        if (!root.exists()) return 0 to 0L
        val canonicalFiles = app.filesDir.canonicalFile
        val canonicalRoot = root.canonicalFile
        require(canonicalRoot.parentFile == canonicalFiles && canonicalRoot.name == "rf-survey") { "RF evidence path escaped app storage" }
        val files = canonicalRoot.walkTopDown().filter { it.isFile }.toList()
        val bytes = files.sumOf { it.length() }
        require(canonicalRoot.deleteRecursively()) { "Could not delete retained RF evidence; local evidence was not wiped" }
        return files.size to bytes
    }
}
