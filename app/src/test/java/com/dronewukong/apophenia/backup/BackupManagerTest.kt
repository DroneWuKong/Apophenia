package com.dronewukong.apophenia.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.ExportAuditEntry
import com.dronewukong.apophenia.data.ExportOutcome
import com.dronewukong.apophenia.data.ExportRoute
import com.dronewukong.apophenia.export.ExportEvidenceMaterializer
import com.dronewukong.apophenia.export.ExportPayload
import com.dronewukong.apophenia.ingest.IncomingAttachment
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupManagerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var output: File
    private lateinit var manager: BackupManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        File(context.filesDir, "av").deleteRecursively()
        File(context.filesDir, "rf-survey").deleteRecursively()
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
        output = File(context.cacheDir, "backup-manager-test").apply { deleteRecursively(); mkdirs() }
        db = ObservationDb(context, DB_NAME)
        manager = BackupManager(context, emptyMaterializer, object : ProtectedEvidenceRestorer {
            override fun restore(db: ObservationDb, evidenceBundle: File, createdAliases: MutableSet<String>) = 0 to 0
        })
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
        output.deleteRecursively()
        File(context.filesDir, "av").deleteRecursively()
        File(context.filesDir, "rf-survey").deleteRecursively()
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
    }

    @Test
    fun checkpointedBackupInspectsAndRestoresExactSqliteState() {
        db.writableDatabase.enableWriteAheadLogging()
        val backedUpId = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Backed up"))
        db.setEventSeal(backedUpId, true, 1_100)
        db.insertExportAudit(ExportAuditEntry(
            occurredAtMs = 1_200, tier = "DATA_ONLY", route = ExportRoute.SHARESHEET,
            outcome = ExportOutcome.HANDOFF_TO_CHOOSER, bundleSha256 = "a".repeat(64), bundleName = "earlier.zip",
            payloadCount = 1, totalPayloadBytes = 10, containsRawAv = false, containsTier2Contents = false,
            scope = "events:$backedUpId", detail = "chooser opened"
        ))
        ObservationAttachmentStore(context, db).store(backedUpId, IncomingAttachment.Text("share evidence"), 1_050)
        File(context.filesDir, "rf-survey").apply { mkdirs() }.resolve("window.iq").writeBytes(byteArrayOf(9, 8, 7))
        val prepared = manager.prepare(db, output, nowMs = 5_000)
        val inspection = manager.inspect(prepared.bundle)
        assertEquals(ObservationDb.SCHEMA_VERSION, inspection.schemaVersion)
        assertEquals(1, inspection.observationCount)
        assertEquals(1, inspection.rfIqFileCount)
        assertEquals(1, inspection.attachmentCount)

        db.deleteAllData()
        File(context.filesDir, "rf-survey").deleteRecursively()
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
        db.insertObservation(Observation(timestampMs = 2_000, kind = ObservationKind.OBSERVATION, label = "After backup"))
        val result = manager.restore(db, prepared.bundle)

        assertEquals(1, result.observationCount)
        assertEquals(1, result.restoredRfIqCount)
        assertEquals(1, result.restoredAttachmentCount)
        assertEquals(listOf("Backed up"), db.observations().map { it.label })
        assertEquals(listOf(backedUpId), db.evidenceSeals().map { it.observationId })
        assertEquals(ExportOutcome.HANDOFF_TO_CHOOSER, db.exportAuditLog().single().outcome)
        assertArrayEquals(byteArrayOf(9, 8, 7), File(context.filesDir, "rf-survey/window.iq").readBytes())
        assertEquals("share evidence", File(context.filesDir, db.observationAttachments(backedUpId).single().relativePath).readText())
    }

    @Test
    fun rawSnapshotIsCheckpointedIntegrityCheckedAndVersioned() {
        db.writableDatabase.enableWriteAheadLogging()
        db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Raw snapshot"))

        val snapshot = manager.prepareRawDatabase(db, output, nowMs = 4_000)

        assertEquals("apophenia-sqlite-4000.db", snapshot.file.name)
        assertEquals(ObservationDb.SCHEMA_VERSION, snapshot.schemaVersion)
        assertEquals(1, snapshot.observationCount)
        assertEquals(64, snapshot.sha256.length)
    }

    @Test
    fun corruptedBackupIsRefusedBeforeCurrentStoreChanges() {
        db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Protected current state"))
        val prepared = manager.prepare(db, output, nowMs = 6_000)
        val corrupted = File(output, "corrupted-backup.zip")
        ZipFile(prepared.bundle).use { source ->
            ZipOutputStream(corrupted.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "database/apophenia.db") target.write("not sqlite".toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }

        assertThrows(IllegalArgumentException::class.java) { manager.restore(db, corrupted) }
        assertEquals(listOf("Protected current state"), db.observations().map { it.label })
    }

    @Test
    fun backupPreparationRefusesIncompletePortableMedia() {
        val backedUpId = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Backed up with media"))
        db.registerMediaAsset(
            MediaAsset(
                id = "audio-$backedUpId-1000", observationId = backedUpId, mediaType = MediaType.AUDIO,
                streamId = "mic", createdAtMs = 1_000, retentionUntilMs = 90_000_000,
                ciphertextRelativePath = "av/missing.enc", manifestRelativePath = "av/missing.json",
                keyAlias = "test", ciphertextSha256 = "00", sizeBytes = 12
            )
        )
        assertThrows(IllegalArgumentException::class.java) { manager.prepare(db, output, nowMs = 7_000) }
        assertEquals(listOf("Backed up with media"), db.observations().map { it.label })
        assertEquals(0, output.listFiles()?.size ?: 0)
    }

    private val emptyMaterializer = object : ExportEvidenceMaterializer {
        override fun sensitivePayloads(records: List<com.dronewukong.apophenia.data.SensitiveContextRecord>): List<ExportPayload> = emptyList()
        override fun mediaPayloads(assets: List<com.dronewukong.apophenia.data.MediaAsset>): List<ExportPayload> = emptyList()
    }

    companion object { private const val DB_NAME = "backup-manager-test.db" }
}
