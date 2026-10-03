package com.dronewukong.apophenia.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaStatus
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MediaRetentionManagerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var directory: File
    private val deletedKeys = mutableListOf<String>()

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("apophenia.db")
        db = ObservationDb(context)
        directory = File(context.filesDir, "av/retention-test").apply { mkdirs() }
    }

    @After fun tearDown() {
        db.close()
        context.deleteDatabase("apophenia.db")
        directory.deleteRecursively()
    }

    @Test fun expiryDeletesRawMediaAndKeyButKeepsDerivedMetricsAndLedger() {
        val observationId = eventWithDerivedMetric()
        val asset = asset(observationId, "expired", retentionUntilMs = 1_500)
        assertTrue(manager().register(asset))

        assertEquals(1, manager().purgeExpired(2_000))
        assertFalse(File(context.filesDir, asset.ciphertextRelativePath).exists())
        assertFalse(File(context.filesDir, asset.manifestRelativePath).exists())
        assertEquals(listOf("test-key-expired"), deletedKeys)
        assertEquals(MediaStatus.PURGED, db.mediaAsset(asset.id)?.status)
        assertEquals("RETENTION_EXPIRED", db.purgeLedger().single().reason)
        assertTrue(db.purgeLedger().single().derivedMetricsRetained)
        assertEquals("audio_loudness_dbfs", db.contextForObservation(observationId).single().metric)
    }

    @Test fun keepForeverBlocksExpiryAndScrubPreventsLateFinalizationResurrection() {
        val observationId = eventWithDerivedMetric()
        val asset = asset(observationId, "kept", retentionUntilMs = 1_500)
        assertTrue(manager().register(asset))
        assertEquals(1, manager().setEventKeepForever(observationId, true))
        assertEquals(0, manager().purgeExpired(2_000))
        assertTrue(File(context.filesDir, asset.ciphertextRelativePath).exists())

        assertEquals(1, manager().scrubEvent(observationId))
        writeFiles(asset)
        assertFalse(manager().register(asset.copy(ciphertextSha256 = "late-final")))
        assertFalse(File(context.filesDir, asset.ciphertextRelativePath).exists())
        assertEquals(MediaStatus.PURGED, db.mediaAsset(asset.id)?.status)
        assertEquals(1, db.purgeLedger().size)
    }

    @Test fun rejectsPathsOutsideTheApplicationFilesRoot() {
        val observationId = eventWithDerivedMetric()
        val unsafe = asset(observationId, "unsafe", 1_500).copy(ciphertextRelativePath = "../outside.bin")
        assertTrue(manager().register(unsafe))
        assertEquals(0, manager().purgeExpired(2_000))
        assertEquals(MediaStatus.ACTIVE, db.mediaAsset(unsafe.id)?.status)
        assertTrue(db.purgeLedger().isEmpty())
    }

    private fun eventWithDerivedMetric(): Long {
        val id = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.OBSERVATION, label = "WEIRD"))
        db.insertContext(listOf(ContextSample(timestampMs = 1_000, observationId = id, source = "audio/derived", metric = "audio_loudness_dbfs", value = -22.0, unit = "dBFS", phase = ContextPhase.PRE)))
        return id
    }

    private fun asset(observationId: Long, suffix: String, retentionUntilMs: Long): MediaAsset {
        val ciphertext = File(directory, "$suffix.pcm.aesgcm")
        val manifest = File(directory, "$suffix.json")
        ciphertext.writeBytes(byteArrayOf(1, 2, 3, 4))
        manifest.writeText("{}")
        return MediaAsset(
            id = "audio-$suffix", observationId = observationId, mediaType = MediaType.AUDIO,
            streamId = "microphone", createdAtMs = 1_000, retentionUntilMs = retentionUntilMs,
            ciphertextRelativePath = ciphertext.relativeTo(context.filesDir).invariantSeparatorsPath,
            manifestRelativePath = manifest.relativeTo(context.filesDir).invariantSeparatorsPath,
            keyAlias = "test-key-$suffix", ciphertextSha256 = "hash", sizeBytes = ciphertext.length()
        )
    }

    private fun writeFiles(asset: MediaAsset) {
        File(context.filesDir, asset.ciphertextRelativePath).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9)) }
        File(context.filesDir, asset.manifestRelativePath).writeText("{}")
    }

    private fun manager() = MediaRetentionManager(context, db, context.filesDir) { deletedKeys += it }
}
