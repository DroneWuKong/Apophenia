package com.dronewukong.apophenia.export

import java.io.File

enum class ExportTier(val displayName: String) {
    DATA_ONLY("Data-only"),
    FULL_EVIDENCE("Full evidence package"),
    FULL_BACKUP("Full backup"),
    SINGLE_EVENT_DOSSIER("Single-event dossier"),
    REPORT("Selected-event report")
}

data class ExportManifestEntry(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
    val containsRawAv: Boolean = false,
    val containsTier2Contents: Boolean = false
)

data class ExportManifest(
    val createdAtMs: Long,
    val tier: ExportTier,
    val schemaVersion: Int,
    val entries: List<ExportManifestEntry>
) {
    val totalBytes: Long get() = entries.sumOf { it.sizeBytes }
    val containsRawAv: Boolean get() = entries.any { it.containsRawAv }
    val containsTier2Contents: Boolean get() = entries.any { it.containsTier2Contents }
}

data class PreparedExport(
    val bundle: File,
    val bundleSha256: String,
    val manifest: ExportManifest
)

data class ExportPayload(
    val path: String,
    val bytes: ByteArray,
    val containsRawAv: Boolean = false,
    val containsTier2Contents: Boolean = false
)
