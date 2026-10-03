package com.dronewukong.apophenia.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.dronewukong.apophenia.audio.AudioArtifactStore
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.SensitiveContextRecord
import com.dronewukong.apophenia.export.AndroidExportEvidenceMaterializer
import com.dronewukong.apophenia.export.ExportEvidenceMaterializer
import com.dronewukong.apophenia.export.ExportManager
import com.dronewukong.apophenia.export.ExportPayload
import com.dronewukong.apophenia.export.ExportTier
import com.dronewukong.apophenia.export.PreparedExport
import com.dronewukong.apophenia.media.MediaRetentionManager
import com.dronewukong.apophenia.phone.SensitiveContentCipher
import com.dronewukong.apophenia.phone.SensitiveContextProvider
import com.dronewukong.apophenia.video.VideoArtifactStore
import com.dronewukong.apophenia.video.VideoFrame
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject

data class BackupInspection(
    val schemaVersion: Int,
    val observationCount: Int,
    val contextSampleCount: Int,
    val activeMediaCount: Int,
    val sensitiveRecordCount: Int,
    val rfIqFileCount: Int,
    val containsRawAv: Boolean,
    val containsTier2Contents: Boolean
)

data class RestoreResult(
    val observationCount: Int,
    val restoredMediaCount: Int,
    val restoredSensitiveCount: Int,
    val restoredRfIqCount: Int
)

data class RawDatabaseSnapshot(
    val file: File,
    val sha256: String,
    val schemaVersion: Int,
    val observationCount: Int,
    val contextSampleCount: Int
)

class BackupManager(
    context: Context,
    private val evidenceMaterializer: ExportEvidenceMaterializer = AndroidExportEvidenceMaterializer(context),
    private val protectedRestorer: ProtectedEvidenceRestorer = AndroidProtectedEvidenceRestorer(context)
) {
    private val app = context.applicationContext

    fun prepare(db: ObservationDb, dir: File, nowMs: Long = System.currentTimeMillis()): PreparedExport {
        require(!db.isDemoDatabase) { "DEMO DATA cannot be backed up as live evidence" }
        val work = File(app.cacheDir, "backup-build-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val databaseCopy = File(work, "apophenia.db")
            checkpointAndCopy(db, databaseCopy)
            val databaseInfo = inspectDatabase(databaseCopy)
            val evidence = ExportManager.prepareBundle(
                context = app,
                db = db,
                tier = ExportTier.FULL_EVIDENCE,
                dir = work,
                nowMs = nowMs,
                evidenceMaterializer = evidenceMaterializer
            )
            val payloads = mutableListOf(
                ExportPayload("database/apophenia.db", databaseCopy.readBytes()),
                ExportPayload(
                    "portable/full-evidence.zip",
                    evidence.bundle.readBytes(),
                    containsRawAv = evidence.manifest.containsRawAv,
                    containsTier2Contents = evidence.manifest.containsTier2Contents
                ),
                ExportPayload(
                    "database/schema.json",
                    JSONObject()
                        .put("schema", "apophenia.sqlite.backup.v1")
                        .put("schemaVersion", databaseInfo.schemaVersion)
                        .put("walCheckpoint", "FULL")
                        .put("integrityCheck", "ok")
                        .put("databaseFile", "database/apophenia.db")
                        .put("portableEvidenceFile", "portable/full-evidence.zip")
                        .toString(2).toByteArray(Charsets.UTF_8)
                )
            )
            val rfDirectory = File(app.filesDir, "rf-survey")
            rfDirectory.listFiles()?.filter { it.isFile && it.extension == "iq" }?.sortedBy { it.name }?.forEach { file ->
                payloads += ExportPayload("rf/${safeToken(file.name)}", file.readBytes())
            }
            val prepared = ExportManager.preparePayloadBundle(ExportTier.FULL_BACKUP, dir, nowMs, payloads, includesAllEvidence = true)
            try {
                val verified = verifyToStaging(prepared.bundle)
                verified.directory.deleteRecursively()
                return prepared
            } catch (error: Throwable) {
                prepared.bundle.delete()
                throw error
            }
        } finally {
            work.deleteRecursively()
        }
    }

    fun prepareRawDatabase(db: ObservationDb, dir: File, nowMs: Long = System.currentTimeMillis()): RawDatabaseSnapshot {
        require(!db.isDemoDatabase) { "DEMO DATA cannot be exported as live evidence" }
        dir.mkdirs()
        val target = File(dir, "apophenia-sqlite-$nowMs.db")
        return try {
            checkpointAndCopy(db, target)
            val info = inspectDatabase(target)
            RawDatabaseSnapshot(
                file = target,
                sha256 = sha256(target),
                schemaVersion = info.schemaVersion,
                observationCount = info.observationCount,
                contextSampleCount = info.contextCount
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun inspect(bundle: File): BackupInspection {
        val verified = verifyToStaging(bundle)
        return try { verified.inspection } finally { verified.directory.deleteRecursively() }
    }

    fun restore(db: ObservationDb, bundle: File): RestoreResult = synchronized(restoreLock) {
        require(!db.isDemoDatabase) { "Restore is allowed only into the live database" }
        val verified = verifyToStaging(bundle)
        val rollback = File(app.cacheDir, "backup-rollback-${UUID.randomUUID()}").apply { mkdirs() }
        val rollbackDb = File(rollback, "apophenia.db")
        val currentAv = File(app.filesDir, "av")
        val currentRf = File(app.filesDir, "rf-survey")
        val rollbackAv = File(rollback, "av")
        val rollbackRf = File(rollback, "rf-survey")
        val oldAliases = db.mediaAssets(includePurged = true, limit = 100_000).map { it.keyAlias }.filter(String::isNotBlank).toSet()
        val createdAliases = mutableSetOf<String>()
        try {
            checkpointAndCopy(db, rollbackDb)
            moveDirectoryAside(currentAv, rollbackAv)
            moveDirectoryAside(currentRf, rollbackRf)
            replaceDatabaseContents(db, verified.database)
            val restored = protectedRestorer.restore(db, verified.evidence, createdAliases)
            val rfCount = restoreRf(verified.bundle)
            require(integrityCheck(db.writableDatabase) == "ok") { "Restored database failed integrity_check" }
            val newAliases = db.mediaAssets(includePurged = true, limit = 100_000).map { it.keyAlias }.filter(String::isNotBlank).toSet()
            (oldAliases - newAliases).forEach(::deleteKey)
            rollback.deleteRecursively()
            return RestoreResult(
                observationCount = db.observations(100_000).size,
                restoredMediaCount = restored.first,
                restoredSensitiveCount = restored.second,
                restoredRfIqCount = rfCount
            )
        } catch (error: Throwable) {
            runCatching { replaceDatabaseContents(db, rollbackDb) }
            File(app.filesDir, "av").deleteRecursively()
            File(app.filesDir, "rf-survey").deleteRecursively()
            restoreDirectory(rollbackAv, File(app.filesDir, "av"))
            restoreDirectory(rollbackRf, File(app.filesDir, "rf-survey"))
            (createdAliases - oldAliases).forEach(::deleteKey)
            throw error
        } finally {
            verified.directory.deleteRecursively()
            rollback.deleteRecursively()
        }
    }

    private data class DatabaseInfo(
        val schemaVersion: Int,
        val observationCount: Int,
        val contextCount: Int,
        val activeMediaCount: Int,
        val sensitiveCount: Int,
        val activeMedia: List<BackupMedia>
    )

    private data class BackupMedia(val id: String, val observationId: Long, val type: MediaType)

    private data class VerifiedBackup(
        val directory: File,
        val bundle: File,
        val database: File,
        val evidence: File,
        val inspection: BackupInspection
    )

    private fun verifyToStaging(bundle: File): VerifiedBackup {
        val manifest = ExportManager.verifyBundle(bundle)
        require(manifest.tier == ExportTier.FULL_BACKUP) { "Selected file is not an Apophenia full backup" }
        val directory = File(app.cacheDir, "backup-verify-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val database = File(directory, "apophenia.db")
            val evidence = File(directory, "full-evidence.zip")
            ZipFile(bundle).use { zip ->
                zip.getInputStream(zip.getEntry("database/apophenia.db") ?: error("Backup database is missing")).use { input -> database.outputStream().use { input.copyTo(it) } }
                zip.getInputStream(zip.getEntry("portable/full-evidence.zip") ?: error("Portable evidence is missing")).use { input -> evidence.outputStream().use { input.copyTo(it) } }
            }
            val databaseInfo = inspectDatabase(database)
            val evidenceManifest = ExportManager.verifyBundle(evidence)
            require(evidenceManifest.tier == ExportTier.FULL_EVIDENCE) { "Nested evidence package has the wrong tier" }
            require(databaseInfo.activeMediaCount == 0 || evidenceManifest.containsRawAv) { "Backup omits active raw AV" }
            require(databaseInfo.sensitiveCount == 0 || evidenceManifest.containsTier2Contents) { "Backup omits Tier-2 contents" }
            validatePortableEvidence(databaseInfo, evidence)
            val rfCount = ZipFile(bundle).use { zip ->
                val schema = JSONObject(zip.getInputStream(zip.getEntry("database/schema.json") ?: error("Backup schema descriptor is missing")).bufferedReader().use { it.readText() })
                require(schema.getString("schema") == "apophenia.sqlite.backup.v1") { "Unsupported backup schema descriptor" }
                require(schema.getInt("schemaVersion") == databaseInfo.schemaVersion) { "Backup schema descriptor does not match SQLite" }
                require(schema.getString("databaseFile") == "database/apophenia.db") { "Backup database path is inconsistent" }
                require(schema.getString("portableEvidenceFile") == "portable/full-evidence.zip") { "Backup evidence path is inconsistent" }
                val rfEntries = zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("rf/") }.toList()
                require(rfEntries.all { it.name.substringAfterLast('/').endsWith(".iq") }) { "Unexpected RF backup payload" }
                rfEntries.size
            }
            return VerifiedBackup(
                directory = directory,
                bundle = bundle,
                database = database,
                evidence = evidence,
                inspection = BackupInspection(
                    schemaVersion = databaseInfo.schemaVersion,
                    observationCount = databaseInfo.observationCount,
                    contextSampleCount = databaseInfo.contextCount,
                    activeMediaCount = databaseInfo.activeMediaCount,
                    sensitiveRecordCount = databaseInfo.sensitiveCount,
                    rfIqFileCount = rfCount,
                    containsRawAv = evidenceManifest.containsRawAv,
                    containsTier2Contents = evidenceManifest.containsTier2Contents
                )
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun checkpointAndCopy(db: ObservationDb, destination: File) {
        val checkpoint = db.writableDatabase.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            check(cursor.moveToFirst()) { "SQLite did not return a checkpoint result" }
            Triple(cursor.getInt(0), cursor.getInt(1), cursor.getInt(2))
        }
        require(checkpoint.first == 0) { "SQLite WAL checkpoint remained busy" }
        val source = app.getDatabasePath(db.databaseFileName)
        require(source.isFile) { "Live SQLite database is missing" }
        destination.parentFile?.mkdirs()
        source.copyTo(destination, overwrite = true)
        inspectDatabase(destination)
    }

    private fun inspectDatabase(file: File): DatabaseInfo {
        require(file.isFile) { "Backup SQLite file is missing" }
        val database = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return database.use {
            require(integrityCheck(it) == "ok") { "SQLite integrity_check failed" }
            require(it.version == ObservationDb.SCHEMA_VERSION) { "Unsupported schema version ${it.version}" }
            val tables = it.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            require(requiredTables.all(tables::contains)) { "SQLite backup is missing required tables" }
            DatabaseInfo(
                schemaVersion = it.version,
                observationCount = count(it, "observations"),
                contextCount = count(it, "context_samples"),
                activeMediaCount = scalar(it, "SELECT COUNT(*) FROM media_assets WHERE status='ACTIVE'"),
                sensitiveCount = count(it, "sensitive_context"),
                activeMedia = it.rawQuery(
                    "SELECT id,observation_id,media_type FROM media_assets WHERE status='ACTIVE' ORDER BY created_at_ms,id",
                    null
                ).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) {
                            add(BackupMedia(cursor.getString(0), cursor.getLong(1), MediaType.valueOf(cursor.getString(2))))
                        }
                    }
                }
            )
        }
    }

    /** Fully validates portable protected evidence before the live store is isolated or mutated. */
    private fun validatePortableEvidence(info: DatabaseInfo, evidence: File) {
        ZipFile(evidence).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            if (info.sensitiveCount > 0) {
                val entry = zip.getEntry("tier2/contents.json") ?: error("Backup evidence is missing Tier-2 contents")
                val root = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                require(root.getString("schema") == "apophenia.tier2.export.v1") { "Unsupported Tier-2 evidence schema" }
                require(root.getJSONArray("records").length() == info.sensitiveCount) { "Tier-2 evidence count does not match SQLite" }
            }
            info.activeMedia.forEach { asset ->
                val base = "av/event-${asset.observationId}/${safeEvidenceToken(asset.id)}"
                when (asset.type) {
                    MediaType.AUDIO -> {
                        val sidecarPath = "$base.json"
                        val wavPath = "$base.wav"
                        require(sidecarPath in names && wavPath in names) { "Backup evidence is incomplete for ${asset.id}" }
                        val sidecar = JSONObject(zip.getInputStream(zip.getEntry(sidecarPath)).bufferedReader().use { it.readText() })
                        require(sidecar.getString("schema") == "apophenia.export.audio.v1") { "Unsupported audio evidence schema" }
                        require(sidecar.getString("assetId") == asset.id && sidecar.getLong("observationId") == asset.observationId) { "Audio evidence identity mismatch" }
                        val wav = zip.getInputStream(zip.getEntry(wavPath)).use { it.readBytes() }
                        require(wav.size >= 44 && wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") { "Invalid WAV for ${asset.id}" }
                        require(littleEndianInt(wav, 40) == wav.size - 44) { "WAV length mismatch for ${asset.id}" }
                        require(sidecar.getInt("preBytes") + sidecar.getInt("postBytes") == wav.size - 44) { "Audio phase lengths do not match ${asset.id}" }
                        wav.fill(0)
                    }
                    MediaType.VIDEO -> {
                        val indexPath = "$base/index.json"
                        require(indexPath in names) { "Backup evidence is incomplete for ${asset.id}" }
                        val index = JSONObject(zip.getInputStream(zip.getEntry(indexPath)).bufferedReader().use { it.readText() })
                        require(index.getString("schema") == "apophenia.export.video-frames.v1") { "Unsupported video evidence schema" }
                        require(index.getString("assetId") == asset.id && index.getLong("observationId") == asset.observationId) { "Video evidence identity mismatch" }
                        val frames = index.getJSONArray("frames")
                        require(frames.length() > 0) { "Video evidence has no frames for ${asset.id}" }
                        repeat(frames.length()) { row ->
                            val frameName = frames.getJSONObject(row).getString("file")
                            require(frameName.isNotBlank() && '/' !in frameName && '\\' !in frameName) { "Unsafe video frame name" }
                            require("$base/$frameName" in names) { "Backup evidence is missing a frame for ${asset.id}" }
                        }
                    }
                }
            }
        }
    }

    private fun replaceDatabaseContents(target: ObservationDb, source: File) {
        inspectDatabase(source)
        val database = target.writableDatabase
        database.execSQL("ATTACH DATABASE ? AS imported", arrayOf(source.absolutePath))
        try {
            database.beginTransaction()
            try {
                deleteOrder.forEach { database.delete(it, null, null) }
                insertOrder.forEach { table -> database.execSQL("INSERT INTO main.$table SELECT * FROM imported.$table") }
                database.delete("sqlite_sequence", null, null)
                database.execSQL("INSERT INTO main.sqlite_sequence SELECT * FROM imported.sqlite_sequence")
                database.setTransactionSuccessful()
            } finally { database.endTransaction() }
        } finally {
            database.execSQL("DETACH DATABASE imported")
        }
    }

    private fun restoreRf(bundle: File): Int {
        val root = File(app.filesDir, "rf-survey").apply { mkdirs() }
        var count = 0
        ZipFile(bundle).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("rf/") }.forEach { entry ->
                val name = safeToken(entry.name.substringAfterLast('/'))
                require(name.endsWith(".iq")) { "Unexpected RF backup payload" }
                zip.getInputStream(entry).use { input -> File(root, name).outputStream().use { input.copyTo(it) } }
                count++
            }
        }
        return count
    }

    private fun moveDirectoryAside(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) {
            source.copyRecursively(destination, overwrite = true)
            check(source.deleteRecursively()) { "Could not isolate current ${source.name} directory" }
        }
    }

    private fun restoreDirectory(source: File, destination: File) {
        if (!source.exists()) return
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) source.copyRecursively(destination, overwrite = true)
    }

    private fun count(db: SQLiteDatabase, table: String): Int = scalar(db, "SELECT COUNT(*) FROM $table")
    private fun scalar(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use { it.moveToFirst(); it.getInt(0) }
    private fun integrityCheck(db: SQLiteDatabase): String = db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst(); it.getString(0) }
    private fun safeToken(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-." ) it else '_' }.joinToString("").take(120)
    private fun safeEvidenceToken(value: String): String = safeToken(value).take(100).ifBlank { "unnamed" }
    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
    private fun sha256(file: File): String = java.security.MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun deleteKey(alias: String) = runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }.getOrNull()

    companion object {
        private val restoreLock = Any()
        private val requiredTables = setOf(
            "observations", "context_samples", "rolling_samples", "hypotheses", "sensitive_context",
            "capture_sessions", "session_events", "media_assets", "purge_ledger", "hypothesis_evaluations", "analysis_views",
            "evidence_seals", "export_audit_log"
        )
        private val deleteOrder = listOf(
            "sensitive_context", "session_events", "context_samples", "rolling_samples", "hypothesis_evaluations",
            "analysis_views", "evidence_seals", "purge_ledger", "media_assets", "hypotheses", "observations", "capture_sessions",
            "export_audit_log"
        )
        private val insertOrder = listOf(
            "observations", "evidence_seals", "hypotheses", "capture_sessions", "context_samples", "rolling_samples", "sensitive_context",
            "session_events", "media_assets", "hypothesis_evaluations", "analysis_views", "purge_ledger", "export_audit_log"
        )
    }
}

interface ProtectedEvidenceRestorer {
    /** Returns restored media count and restored Tier-2 row count. */
    fun restore(db: ObservationDb, evidenceBundle: File, createdAliases: MutableSet<String>): Pair<Int, Int>
}

class AndroidProtectedEvidenceRestorer(context: Context) : ProtectedEvidenceRestorer {
    private val app = context.applicationContext

    override fun restore(db: ObservationDb, evidenceBundle: File, createdAliases: MutableSet<String>): Pair<Int, Int> {
        val manifest = ExportManager.verifyBundle(evidenceBundle)
        require(manifest.tier == ExportTier.FULL_EVIDENCE)
        val sensitive = restoreSensitive(db, evidenceBundle)
        var restoredMedia = 0
        val retention = MediaRetentionManager(app, db)
        ZipFile(evidenceBundle).use { zip ->
            db.mediaAssets(includePurged = false, limit = 100_000).sortedBy { it.createdAtMs }.forEach { asset ->
                when (asset.mediaType) {
                    MediaType.AUDIO -> restoreAudio(zip, asset, db, retention, createdAliases)
                    MediaType.VIDEO -> restoreVideo(zip, asset, db, retention, createdAliases)
                }
                restoredMedia++
            }
        }
        return restoredMedia to sensitive
    }

    private fun restoreSensitive(db: ObservationDb, bundle: File): Int {
        val cipher = SensitiveContentCipher()
        val records = ZipFile(bundle).use { zip ->
            val entry = zip.getEntry("tier2/contents.json")
            if (entry == null) emptyList() else {
                val root = JSONObject(zip.getInputStream(entry).bufferedReader().use { it.readText() })
                val rows = root.getJSONArray("records")
                buildList {
                    repeat(rows.length()) { index ->
                        val row = rows.getJSONObject(index)
                        val source = row.getString("source")
                        val type = row.getString("contentType")
                        val captureId = row.getString("captureId")
                        val content = row.get("content").let { value ->
                            when (value) {
                                is JSONObject, is JSONArray -> value.toString()
                                else -> value.toString()
                            }
                        }
                        val encrypted = cipher.encrypt(content, SensitiveContextProvider.associatedData(source, type, captureId))
                        add(
                            SensitiveContextRecord(
                                timestampMs = row.getLong("timestampMs"),
                                observationId = if (row.isNull("observationId")) null else row.getLong("observationId"),
                                isControl = row.getBoolean("isControl"), source = source, contentType = type,
                                ciphertextBase64 = encrypted.ciphertextBase64, ivBase64 = encrypted.ivBase64,
                                keyAlias = encrypted.keyAlias, captureId = captureId
                            )
                        )
                    }
                }
            }
        }
        db.replaceSensitiveContext(records)
        return records.size
    }

    private fun restoreAudio(
        zip: ZipFile,
        asset: MediaAsset,
        db: ObservationDb,
        retention: MediaRetentionManager,
        aliases: MutableSet<String>
    ) {
        val base = "av/event-${asset.observationId}/${safeToken(asset.id)}"
        val sidecar = json(zip, "$base.json")
        val wav = bytes(zip, "$base.wav")
        require(wav.size >= 44 && wav.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF") { "Invalid WAV for ${asset.id}" }
        val sampleRate = littleInt(wav, 24)
        val dataBytes = littleInt(wav, 40)
        require(dataBytes == wav.size - 44) { "WAV length mismatch for ${asset.id}" }
        val pcm = wav.copyOfRange(44, wav.size)
        val preBytes = sidecar.getInt("preBytes")
        val postBytes = sidecar.getInt("postBytes")
        require(preBytes + postBytes == pcm.size) { "Audio phase lengths do not match ${asset.id}" }
        val result = AudioArtifactStore(app, retention).persist(
            asset.observationId, asset.createdAtMs, sampleRate,
            pcm.copyOfRange(0, preBytes), pcm.copyOfRange(preBytes, pcm.size),
            absoluteRetentionUntilMs = asset.retentionUntilMs
        )
        require(result.fileId == asset.id && result.retained) { "Could not restore ${asset.id}" }
        aliases += result.keyAlias
        if (asset.keepForever) db.setMediaKeepForever(asset.id, true)
        pcm.fill(0); wav.fill(0)
    }

    private fun restoreVideo(
        zip: ZipFile,
        asset: MediaAsset,
        db: ObservationDb,
        retention: MediaRetentionManager,
        aliases: MutableSet<String>
    ) {
        val base = "av/event-${asset.observationId}/${safeToken(asset.id)}"
        val index = json(zip, "$base/index.json").getJSONArray("frames")
        val pre = mutableListOf<VideoFrame>()
        val post = mutableListOf<VideoFrame>()
        repeat(index.length()) { row ->
            val frame = index.getJSONObject(row)
            val name = frame.getString("file")
            val phase = frame.getString("phase")
            val item = VideoFrame(asset.streamId, asset.streamId, frame.optLong("timestampMs", asset.createdAtMs), bytes(zip, "$base/$name"), 0, 0, byteArrayOf())
            if (phase == "pre") pre += item else if (phase == "post") post += item else error("Invalid video phase")
        }
        val result = VideoArtifactStore(app, retention).persist(
            asset.observationId, asset.createdAtMs, asset.streamId, asset.streamId, pre, post,
            absoluteRetentionUntilMs = asset.retentionUntilMs
        )
        require(result.fileId == asset.id && result.retained) { "Could not restore ${asset.id}" }
        aliases += db.mediaAsset(asset.id)?.keyAlias.orEmpty()
        if (asset.keepForever) db.setMediaKeepForever(asset.id, true)
        (pre + post).forEach { it.jpeg.fill(0) }
    }

    private fun json(zip: ZipFile, path: String): JSONObject = JSONObject(bytes(zip, path).toString(Charsets.UTF_8))
    private fun bytes(zip: ZipFile, path: String): ByteArray = zip.getInputStream(zip.getEntry(path) ?: error("Backup evidence is missing $path")).use { it.readBytes() }
    private fun littleInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or ((bytes[offset + 2].toInt() and 0xff) shl 16) or ((bytes[offset + 3].toInt() and 0xff) shl 24)
    private fun safeToken(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-." ) it else '_' }.joinToString("").take(100).ifBlank { "unnamed" }
}
