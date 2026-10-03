package com.dronewukong.apophenia.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ExportOutcome
import com.dronewukong.apophenia.data.ExportRoute
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONArray
import org.json.JSONObject
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
class ExportSafetyTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var output: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("export-safety-test.db")
        db = ObservationDb(context, "export-safety-test.db")
        output = File(context.cacheDir, "export-safety-test").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("export-safety-test.db")
        output.deleteRecursively()
    }

    @Test
    fun globalAndEventSealsApplyToTheRightPreparedScopes() {
        val first = db.insertObservation(Observation(timestampMs = 1, kind = ObservationKind.WEIRD, label = "First"))
        val second = db.insertObservation(Observation(timestampMs = 2, kind = ObservationKind.WEIRD, label = "Second"))
        db.setGlobalSeal(true, 10)
        db.setEventSeal(second, true, 11)
        val firstOnly = prepared(setOf(first))

        assertEquals(listOf("global"), ExportReleasePolicy.sealsRequiringRelease(db, firstOnly).map { it.scopeKey })
        assertEquals(2, ExportReleasePolicy.sealsRequiringRelease(db, firstOnly.copy(includesAllEvidence = true)).size)
        assertEquals(2, ExportReleasePolicy.sealsRequiringRelease(db, firstOnly.copy(manifest = firstOnly.manifest.copy(tier = ExportTier.EJECT))).size)
    }

    @Test
    fun routeAuditUsesHonestHandoffOutcomeAndSurvivesEjectWipe() {
        db.insertObservation(Observation(timestampMs = 1, kind = ObservationKind.WEIRD, label = "Evidence"))
        val prepared = prepared(includesAll = true)
        ExportReleasePolicy.record(
            db, prepared, ExportRoute.SHARESHEET, ExportOutcome.HANDOFF_TO_CHOOSER,
            "Android chooser opened; delivery unknown", occurredAtMs = 20
        )
        db.wipeEvidenceForEject()

        assertTrue(db.observations().isEmpty())
        val audit = db.exportAuditLog().single()
        assertEquals(ExportOutcome.HANDOFF_TO_CHOOSER, audit.outcome)
        assertTrue(audit.detail.contains("delivery unknown"))
    }

    @Test
    fun scrubbedDossierDropsRawAndTierTwoButRedactsInventory() {
        val inventory = JSONObject().put("events", JSONArray().put(JSONObject()
            .put("channels", JSONArray().put(JSONObject().put("gate", "LIVE_NOTIFICATION_CONTENTS_CAPTURE")
                .put("values", JSONArray().put(JSONObject().put("value", "secret")))))
            .put("unmatchedValues", JSONArray().put(JSONObject().put("source", "android_tier2").put("value", "secret")))))
        val original = ExportManager.preparePayloadBundle(
            ExportTier.SINGLE_EVENT_DOSSIER, output, 30,
            mutableListOf(
                ExportPayload("dossier/event.json", "event".toByteArray()),
                ExportPayload("inventories/omniprobe-event-1.json", inventory.toString().toByteArray(), containsTier2Contents = true),
                ExportPayload("tier2/contents.json", "secret".toByteArray(), containsTier2Contents = true),
                ExportPayload("av/event-1/audio.wav", "raw".toByteArray(), containsRawAv = true)
            ),
            setOf(1)
        )

        val scrubbed = DossierScrubber.prepareScrubbedCopy(original, output, 31)

        assertEquals(ExportTier.SCRUBBED_DOSSIER, scrubbed.manifest.tier)
        assertFalse(scrubbed.manifest.containsRawAv)
        assertFalse(scrubbed.manifest.containsTier2Contents)
        assertEquals(listOf("dossier/event.json", "inventories/omniprobe-event-1.json"), scrubbed.manifest.entries.map { it.path })
        val redacted = ZipFile(scrubbed.bundle).use { zip -> zip.getInputStream(zip.getEntry("inventories/omniprobe-event-1.json")).bufferedReader().readText() }
        assertTrue(redacted.contains("\"protected\": true"))
        assertFalse(redacted.contains("secret"))
    }

    private fun prepared(ids: Set<Long> = emptySet(), includesAll: Boolean = false): PreparedExport {
        val file = File(output, "prepared-${ids.joinToString("-")}-${if (includesAll) "all" else "some"}.zip").apply { writeText("bundle") }
        return PreparedExport(
            file, "a".repeat(64),
            ExportManifest(1, ExportTier.DATA_ONLY, ObservationDb.SCHEMA_VERSION, listOf(ExportManifestEntry("data.json", 6, "b".repeat(64)))),
            ids, includesAll
        )
    }
}
