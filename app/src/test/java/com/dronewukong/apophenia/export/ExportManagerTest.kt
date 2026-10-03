package com.dronewukong.apophenia.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.SensitiveContextRecord
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExportManagerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var output: File
    private var observationId: Long = 0

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("export-framework-test.db")
        db = ObservationDb(context, "export-framework-test.db")
        output = File(context.cacheDir, "export-framework-test").apply { deleteRecursively(); mkdirs() }
        observationId = db.insertObservation(
            Observation(
                timestampMs = 1_000,
                kind = ObservationKind.WEIRD,
                label = "Test event, quoted",
                note = "line one, \"quoted\"\nline two"
            )
        )
        db.insertContext(listOf(ContextSample(timestampMs = 1_000, observationId = observationId, source = "test", metric = "derived_metric", value = 4.2, unit = "score", captureId = "event:$observationId")))
        db.insertSensitiveContext(listOf(SensitiveContextRecord(timestampMs = 1_001, observationId = observationId, source = "test", contentType = "notification_contents", ciphertextBase64 = "encrypted-secret", ivBase64 = "iv", keyAlias = "test", captureId = "event:$observationId")))
        db.registerMediaAsset(
            MediaAsset(
                id = "audio-test", observationId = observationId, mediaType = MediaType.AUDIO,
                streamId = "microphone", createdAtMs = 1_000, retentionUntilMs = 9_999,
                ciphertextRelativePath = "av/audio/test.aesgcm", manifestRelativePath = "av/audio/test.json",
                keyAlias = "test-key", ciphertextSha256 = "cipher-hash", sizeBytes = 128
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("export-framework-test.db")
        output.deleteRecursively()
    }

    @Test
    fun dataOnlyBundleExcludesRawAvAndTierTwoContents() {
        val prepared = ExportManager.prepareBundle(context, db, ExportTier.DATA_ONLY, output, nowMs = 2_000, evidenceMaterializer = fakeMaterializer())

        assertEquals(ExportTier.DATA_ONLY, prepared.manifest.tier)
        assertFalse(prepared.manifest.containsRawAv)
        assertFalse(prepared.manifest.containsTier2Contents)
        assertEquals(
            listOf(
                "analysis/README.md",
                "analysis/context-samples.csv",
                "analysis/data-dictionary.json",
                "analysis/hypotheses.csv",
                "analysis/hypothesis-evaluations.csv",
                "analysis/observations.csv",
                "analysis/session-events.csv",
                "analysis/sessions.csv",
                "data/apophenia-data.json"
            ),
            prepared.manifest.entries.map { it.path }
        )
        assertEquals(prepared.manifest, ExportManager.verifyBundle(prepared.bundle))
        val data = zipText(prepared.bundle, "data/apophenia-data.json")
        assertTrue(data.contains("derived_metric"))
        assertFalse(data.contains("plaintext-tier2"))
        assertFalse(data.contains("raw-wave"))
        assertFalse(data.contains("encrypted-secret"))
        assertEquals(1, JSONObject(data).getJSONArray("mediaInventory").length())
        val observationsCsv = zipText(prepared.bundle, "analysis/observations.csv")
        assertTrue(observationsCsv.contains("\"Test event, quoted\""))
        assertTrue(observationsCsv.contains("\"line one, \"\"quoted\"\"\nline two\""))
        assertTrue(zipText(prepared.bundle, "analysis/context-samples.csv").contains("event:$observationId"))
        assertTrue(zipText(prepared.bundle, "analysis/README.md").contains("no raw audio/video"))
        assertTrue(zipText(prepared.bundle, "analysis/data-dictionary.json").contains("capture_id"))
    }

    @Test
    fun fullEvidenceBundleIncludesFlaggedPortablePayloadsAndVerifiedHashes() {
        val prepared = ExportManager.prepareBundle(context, db, ExportTier.FULL_EVIDENCE, output, nowMs = 3_000, evidenceMaterializer = fakeMaterializer())

        assertEquals(ExportTier.FULL_EVIDENCE, prepared.manifest.tier)
        assertTrue(prepared.manifest.containsRawAv)
        assertTrue(prepared.manifest.containsTier2Contents)
        assertTrue(prepared.manifest.entries.any { it.path == "analysis/observations.csv" })
        assertTrue(prepared.manifest.entries.any { it.path == "analysis/data-dictionary.json" })
        assertTrue(prepared.manifest.entries.any { it.path == "av/event-1/audio-test.wav" })
        assertTrue(prepared.manifest.entries.any { it.path == "data/apophenia-data.json" })
        assertTrue(prepared.manifest.entries.any { it.path == "inventories/omniprobe.json" })
        assertTrue(prepared.manifest.entries.any { it.path == "tier2/contents.json" })
        assertEquals("raw-wave", zipText(prepared.bundle, "av/event-1/audio-test.wav"))
        assertEquals("plaintext-tier2", zipText(prepared.bundle, "tier2/contents.json"))
        assertTrue(zipText(prepared.bundle, "inventories/omniprobe.json").contains("LIVE_AUDIO_CAPTURE"))
        assertEquals(prepared.manifest, ExportManager.verifyBundle(prepared.bundle))
        assertEquals(64, prepared.bundleSha256.length)
    }

    @Test
    fun verifierRefusesPayloadThatNoLongerMatchesManifest() {
        val prepared = ExportManager.prepareBundle(context, db, ExportTier.DATA_ONLY, output, nowMs = 4_000, evidenceMaterializer = fakeMaterializer())
        val corrupted = File(output, "corrupted.zip")
        ZipFile(prepared.bundle).use { source ->
            ZipOutputStream(corrupted.outputStream()).use { target ->
                source.entries().asSequence().forEach { entry ->
                    target.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == "data/apophenia-data.json") target.write("tampered".toByteArray())
                    else source.getInputStream(entry).use { it.copyTo(target) }
                    target.closeEntry()
                }
            }
        }

        assertThrows(IllegalArgumentException::class.java) { ExportManager.verifyBundle(corrupted) }
    }

    private fun fakeMaterializer() = object : ExportEvidenceMaterializer {
        override fun sensitivePayloads(records: List<SensitiveContextRecord>): List<ExportPayload> {
            assertEquals(1, records.size)
            return listOf(ExportPayload("tier2/contents.json", "plaintext-tier2".toByteArray(), containsTier2Contents = true))
        }

        override fun mediaPayloads(assets: List<MediaAsset>): List<ExportPayload> {
            assertEquals(listOf("audio-test"), assets.map { it.id })
            return listOf(ExportPayload("av/event-1/audio-test.wav", "raw-wave".toByteArray(), containsRawAv = true))
        }
    }

    private fun zipText(bundle: File, path: String): String = ZipFile(bundle).use { zip ->
        zip.getInputStream(zip.getEntry(path)).bufferedReader().use { it.readText() }
    }
}
