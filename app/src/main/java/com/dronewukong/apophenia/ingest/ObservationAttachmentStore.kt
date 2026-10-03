package com.dronewukong.apophenia.ingest

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dronewukong.apophenia.data.ObservationAttachment
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.export.ExportPayload
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

sealed interface IncomingAttachment {
    val mimeType: String
    val displayName: String

    data class Text(
        val text: String,
        override val displayName: String = "shared-text.txt",
        override val mimeType: String = "text/plain"
    ) : IncomingAttachment

    data class ContentUri(
        val uri: Uri,
        override val mimeType: String,
        override val displayName: String
    ) : IncomingAttachment
}

/** App-private storage for inbound share attachments. Source content URIs are never persisted. */
class ObservationAttachmentStore(context: Context, private val db: ObservationDb) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, DIRECTORY).apply { mkdirs() }

    fun store(observationId: Long, attachment: IncomingAttachment, createdAtMs: Long): ObservationAttachment {
        require(attachment.mimeType.startsWith("text/") || attachment.mimeType.startsWith("image/")) {
            "Only shared text and images are accepted"
        }
        val id = UUID.randomUUID().toString()
        val extension = safeExtension(attachment.displayName, attachment.mimeType)
        val relativePath = "$DIRECTORY/$id$extension"
        val target = resolve(relativePath)
        val temporary = File(root, ".$id.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            attachment.open(app).use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        require(size <= MAX_ATTACHMENT_BYTES) { "Shared attachment exceeds the 25 MiB limit" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            check(temporary.renameTo(target)) { "Could not commit shared attachment" }
            val row = ObservationAttachment(
                id = id,
                observationId = observationId,
                createdAtMs = createdAtMs,
                mimeType = attachment.mimeType.take(MAX_MIME_CHARS),
                displayName = safeDisplayName(attachment.displayName),
                relativePath = relativePath,
                sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                sizeBytes = size
            )
            try {
                db.insertAttachment(row)
            } catch (error: Throwable) {
                target.delete()
                throw error
            }
            return row
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    fun exportPayloads(
        attachments: List<ObservationAttachment>,
        prefix: String = "attachments"
    ): List<ExportPayload> {
        if (attachments.isEmpty()) return emptyList()
        val index = JSONArray()
        val payloads = attachments.sortedWith(compareBy<ObservationAttachment> { it.observationId }.thenBy { it.id }).map { row ->
            val file = resolve(row.relativePath)
            require(file.isFile) { "Attachment ${row.id} is missing" }
            require(file.length() == row.sizeBytes) { "Attachment ${row.id} size changed" }
            val bytes = file.readBytes()
            require(sha256(bytes) == row.sha256) { "Attachment ${row.id} hash changed" }
            val path = "$prefix/event-${row.observationId}/${safeToken(row.id)}-${safeDisplayName(row.displayName)}"
            index.put(JSONObject()
                .put("id", row.id).put("observationId", row.observationId).put("createdAtMs", row.createdAtMs)
                .put("mimeType", row.mimeType).put("displayName", row.displayName).put("relativePath", row.relativePath)
                .put("sha256", row.sha256).put("sizeBytes", row.sizeBytes).put("payloadPath", path))
            ExportPayload(path, bytes)
        }.toMutableList()
        payloads += ExportPayload(
            "$prefix/index.json",
            JSONObject()
                .put("schema", "apophenia.observation-attachments.v1")
                .put("sourceUrisPersisted", false)
                .put("attachments", index)
                .toString(2).toByteArray(Charsets.UTF_8)
        )
        return payloads
    }

    fun restoreFromEvidence(bundle: File, expected: List<ObservationAttachment>) {
        if (expected.isEmpty()) return
        ZipFile(bundle).use { zip ->
            val indexEntry = zip.getEntry("attachments/index.json") ?: error("Backup evidence is missing attachments/index.json")
            val rootJson = JSONObject(zip.getInputStream(indexEntry).bufferedReader().use { it.readText() })
            require(rootJson.getString("schema") == "apophenia.observation-attachments.v1") { "Unsupported attachment index" }
            val rows = rootJson.getJSONArray("attachments")
            val indexed = buildMap<String, JSONObject> {
                repeat(rows.length()) { index -> rows.getJSONObject(index).also { put(it.getString("id"), it) } }
            }
            require(indexed.keys == expected.map { it.id }.toSet()) { "Attachment index does not match SQLite" }
            expected.forEach { row ->
                val descriptor = requireNotNull(indexed[row.id])
                require(descriptor.getLong("observationId") == row.observationId) { "Attachment observation mismatch" }
                require(descriptor.getString("relativePath") == row.relativePath) { "Attachment path mismatch" }
                require(descriptor.getString("sha256") == row.sha256 && descriptor.getLong("sizeBytes") == row.sizeBytes) {
                    "Attachment integrity metadata mismatch"
                }
                val payloadPath = descriptor.getString("payloadPath")
                val entry = zip.getEntry(payloadPath) ?: error("Attachment payload is missing: $payloadPath")
                val target = resolve(row.relativePath)
                target.parentFile?.mkdirs()
                val temporary = File(target.parentFile, ".${target.name}.restore")
                zip.getInputStream(entry).use { input -> temporary.outputStream().use { input.copyTo(it) } }
                require(temporary.length() == row.sizeBytes && sha256(temporary.readBytes()) == row.sha256) {
                    "Restored attachment ${row.id} failed verification"
                }
                if (target.exists()) check(target.delete()) { "Could not replace attachment ${row.id}" }
                check(temporary.renameTo(target)) { "Could not commit attachment ${row.id}" }
            }
        }
    }

    fun deleteAll(): Pair<Int, Long> {
        val files = root.listFiles()?.filter { it.isFile } ?: emptyList()
        var bytes = 0L
        var count = 0
        files.forEach { file ->
            val length = file.length()
            require(file.delete()) { "Could not delete inbound attachment ${file.name}" }
            count++
            bytes += length
        }
        return count to bytes
    }

    private fun IncomingAttachment.open(context: Context): InputStream = when (this) {
        is IncomingAttachment.Text -> text.byteInputStream(Charsets.UTF_8)
        is IncomingAttachment.ContentUri -> context.contentResolver.openInputStream(uri)
            ?: error("Android did not provide the shared image")
    }

    private fun resolve(relativePath: String): File {
        require(relativePath.startsWith("$DIRECTORY/") && ".." !in relativePath && ':' !in relativePath) { "Unsafe attachment path" }
        val file = File(app.filesDir, relativePath).canonicalFile
        require(file.path.startsWith(root.canonicalPath + File.separator)) { "Attachment path escapes app storage" }
        return file
    }

    companion object {
        const val DIRECTORY = "attachments"
        const val MAX_ATTACHMENT_BYTES = 25L * 1024L * 1024L
        private const val MAX_MIME_CHARS = 120

        fun displayName(context: Context, uri: Uri): String {
            val fromProvider = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()
            return safeDisplayName(fromProvider ?: uri.lastPathSegment ?: "shared-image")
        }

        private fun safeDisplayName(value: String): String = value
            .substringAfterLast('/').substringAfterLast('\\')
            .map { if (it.isLetterOrDigit() || it in " _-.") it else '_' }
            .joinToString("").trim().take(100).ifBlank { "attachment" }

        private fun safeToken(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }.joinToString("").take(100)
        private fun safeExtension(name: String, mimeType: String): String {
            val candidate = name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            val fallback = when (mimeType.lowercase()) {
                "text/plain" -> "txt"
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/gif" -> "gif"
                "image/webp" -> "webp"
                else -> "bin"
            }
            return ".${candidate ?: fallback}"
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
