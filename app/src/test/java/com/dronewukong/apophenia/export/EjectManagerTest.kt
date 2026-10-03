package com.dronewukong.apophenia.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ExportOutcome
import com.dronewukong.apophenia.data.ExportRoute
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.SensitiveContextRecord
import com.dronewukong.apophenia.ingest.IncomingAttachment
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EjectManagerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var output: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("eject-test.db")
        db = ObservationDb(context, "eject-test.db")
        output = File(context.cacheDir, "eject-test").apply { deleteRecursively(); mkdirs() }
        File(context.filesDir, "rf-survey").deleteRecursively()
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("eject-test.db")
        output.deleteRecursively()
        File(context.filesDir, "rf-survey").deleteRecursively()
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
        File(context.filesDir, "av/undeletable-media").deleteRecursively()
        File(context.filesDir, "av/undeletable-manifest").deleteRecursively()
    }

    @Test
    fun chosenWindowScopesPortableEvidenceAndKeepsManifestVerified() {
        val now = 10 * 60 * 60_000L
        db.insertObservation(Observation(timestampMs = now - 2 * 60 * 60_000L, kind = ObservationKind.WEIRD, label = "Old"))
        val recent = db.insertObservation(Observation(timestampMs = now - 10_000, kind = ObservationKind.VIBE, label = "Recent", vibeRating = 3))
        db.insertContext(listOf(ContextSample(timestampMs = now - 9_000, observationId = recent, source = "test", metric = "signal", value = 7.0, unit = "score", captureId = "event:$recent")))
        db.insertSensitiveContext(listOf(SensitiveContextRecord(timestampMs = now - 8_000, observationId = recent, source = "android_tier2", contentType = "notification_contents", ciphertextBase64 = "cipher", ivBase64 = "iv", keyAlias = "alias", captureId = "event:$recent")))
        val manager = EjectManager(context, object : ExportEvidenceMaterializer {
            override fun sensitivePayloads(records: List<SensitiveContextRecord>): List<ExportPayload> {
                assertEquals(listOf(recent), records.map { it.observationId })
                return listOf(ExportPayload("tier2/contents.json", "portable".toByteArray(), containsTier2Contents = true))
            }
            override fun mediaPayloads(assets: List<MediaAsset>): List<ExportPayload> = emptyList()
        })

        val prepared = manager.prepare(db, EjectWindow.LAST_HOUR, output, now)

        assertEquals(ExportTier.EJECT, prepared.manifest.tier)
        assertEquals(setOf(recent), prepared.observationIds)
        assertTrue(prepared.manifest.containsTier2Contents)
        assertEquals(prepared.manifest, ExportManager.verifyBundle(prepared.bundle))
        val window = ZipFile(prepared.bundle).use { zip -> JSONObject(zip.getInputStream(zip.getEntry("eject/window.json")).bufferedReader().readText()) }
        assertEquals("Recent", window.getJSONArray("observations").getJSONObject(0).getString("label"))
        assertFalse(window.toString().contains("Old"))
    }

    @Test
    fun verifiedCompletedRouteWipesEvidenceButRetainsAuditAndPurgeReceipts() {
        val observationId = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Evidence"))
        ObservationAttachmentStore(context, db).store(observationId, IncomingAttachment.Text("eject attachment"), 1_000)
        val prepared = EjectManager(context, object : ExportEvidenceMaterializer {
            override fun sensitivePayloads(records: List<SensitiveContextRecord>) = emptyList<ExportPayload>()
            override fun mediaPayloads(assets: List<MediaAsset>) = emptyList<ExportPayload>()
        }).prepare(db, EjectWindow.ALL, output, nowMs = 2_000)
        val rfDir = File(context.filesDir, "rf-survey").apply { mkdirs() }
        File(rfDir, "leftover.iq").writeBytes(byteArrayOf(1, 2, 3))

        val result = EjectWiper(context).wipeAfterVerifiedRoute(
            db, prepared, ExportRoute.SAF, ExportOutcome.WRITE_COMPLETED, "test byte-complete save", nowMs = 3_000
        )

        assertTrue(db.observations().isEmpty())
        assertFalse(rfDir.exists())
        assertEquals(1, result.deletedRfFileCount)
        assertEquals(1, result.deletedAttachmentFileCount)
        assertTrue(db.observationAttachments().isEmpty())
        assertEquals(listOf(ExportOutcome.WIPE_COMPLETED, ExportOutcome.WRITE_COMPLETED), db.exportAuditLog().map { it.outcome })
    }

    @Test
    fun unverifiedRouteOrIncompleteMediaDeletionNeverWipesStore() {
        val observationId = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.WEIRD, label = "Keep me"))
        db.registerMediaAsset(MediaAsset(
            id = "unsafe", observationId = observationId, mediaType = MediaType.AUDIO, streamId = "mic",
            createdAtMs = 1_000, retentionUntilMs = 9_000, ciphertextRelativePath = "av/undeletable-media",
            manifestRelativePath = "av/undeletable-manifest", keyAlias = "", ciphertextSha256 = "x", sizeBytes = 4
        ))
        File(context.filesDir, "av/undeletable-media/child").apply { parentFile?.mkdirs(); writeText("keep") }
        File(context.filesDir, "av/undeletable-manifest/child").apply { parentFile?.mkdirs(); writeText("keep") }
        val prepared = EjectManager(context, object : ExportEvidenceMaterializer {
            override fun sensitivePayloads(records: List<SensitiveContextRecord>) = emptyList<ExportPayload>()
            override fun mediaPayloads(assets: List<MediaAsset>) = listOf(ExportPayload("av/unsafe.wav", "raw".toByteArray(), containsRawAv = true))
        }).prepare(db, EjectWindow.ALL, output, nowMs = 2_000)

        assertThrows(IllegalArgumentException::class.java) {
            EjectWiper(context).wipeAfterVerifiedRoute(db, prepared, ExportRoute.SHARESHEET, ExportOutcome.HANDOFF_TO_CHOOSER, "chooser", 3_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EjectWiper(context).wipeAfterVerifiedRoute(db, prepared, ExportRoute.SAF, ExportOutcome.WRITE_COMPLETED, "saved", 3_001)
        }
        assertEquals("Keep me", db.observations().single().label)
        assertEquals(1, db.mediaAssets().size)
    }
}
