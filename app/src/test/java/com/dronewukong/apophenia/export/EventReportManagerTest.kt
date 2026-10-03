package com.dronewukong.apophenia.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.SensitiveContextRecord
import java.io.File
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class EventReportManagerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private lateinit var output: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        output = File(context.cacheDir, "event-report-test").apply { deleteRecursively(); mkdirs() }
        db = ObservationDb(context, DB_NAME)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(DB_NAME)
        output.deleteRecursively()
    }

    @Test
    fun dossierContainsOneEventSummaryChartInventoryAndProtectedEvidence() {
        val eventId = insertEvent("Fucky", 5, egress = true)
        val otherId = insertEvent("Other", null, egress = false)
        insertContext(eventId)
        insertContext(otherId)
        val manager = EventReportManager(context, evidenceMaterializer = fakeEvidence, pdfRenderer = fakePdf)

        val prepared = manager.prepareDossier(db, eventId, output, nowMs = 9_000)

        assertEquals(ExportTier.SINGLE_EVENT_DOSSIER, prepared.manifest.tier)
        assertTrue(prepared.manifest.containsRawAv)
        assertTrue(prepared.manifest.containsTier2Contents)
        val names = prepared.manifest.entries.map { it.path }.toSet()
        assertTrue("dossier/event.json" in names)
        assertTrue("dossier/summary.txt" in names)
        assertTrue("dossier/charts/derived-metrics.svg" in names)
        assertTrue("inventories/omniprobe-event-$eventId.json" in names)
        ZipFile(prepared.bundle).use { zip ->
            val event = JSONObject(zip.getInputStream(zip.getEntry("dossier/event.json")).bufferedReader().use { it.readText() })
            assertEquals(eventId, event.getJSONObject("observation").getLong("id"))
            val rows = event.getJSONArray("context")
            repeat(rows.length()) { assertEquals(eventId, rows.getJSONObject(it).getLong("observationId")) }
            val summary = zip.getInputStream(zip.getEntry("dossier/summary.txt")).bufferedReader().use { it.readText() }
            assertTrue(summary.contains("descriptive snapshot"))
            assertTrue(summary.contains("post-event values"))
        }
    }

    @Test
    fun reportProducesEscapedHtmlPdfJsonCsvAndDerivedChartWithoutTier2Plaintext() {
        val eventId = insertEvent("<script>alert('x')</script>", 4, egress = false)
        insertContext(eventId)
        val manager = EventReportManager(context, evidenceMaterializer = fakeEvidence, pdfRenderer = fakePdf)

        val prepared = manager.prepareReport(db, setOf(eventId), includeAvStills = false, dir = output, nowMs = 10_000)

        assertEquals(ExportTier.REPORT, prepared.manifest.tier)
        assertFalse(prepared.manifest.containsRawAv)
        assertFalse(prepared.manifest.containsTier2Contents)
        val names = prepared.manifest.entries.map { it.path }.toSet()
        assertTrue(setOf("report/report.html", "report/report.pdf", "report/events.json", "report/context.csv", "report/charts/derived-metrics.svg").all(names::contains))
        ZipFile(prepared.bundle).use { zip ->
            val html = zip.getInputStream(zip.getEntry("report/report.html")).bufferedReader().use { it.readText() }
            assertFalse(html.contains("<script>alert"))
            assertTrue(html.contains("&lt;script&gt;"))
            assertTrue(html.contains("INTERESTING, NOT YET ESTABLISHED"))
            assertTrue(zip.getInputStream(zip.getEntry("report/report.pdf")).readBytes().toString(Charsets.US_ASCII).startsWith("%PDF"))
        }
    }

    @Test
    fun reportRequiresARealSelectionAndLiveDatabase() {
        val manager = EventReportManager(context, evidenceMaterializer = fakeEvidence, pdfRenderer = fakePdf)
        assertThrows(IllegalArgumentException::class.java) { manager.prepareReport(db, emptySet(), false, output) }
        val demo = ObservationDb(context, "apophenia-demo.db")
        try {
            assertThrows(IllegalArgumentException::class.java) { manager.prepareReport(demo, setOf(1), false, output) }
        } finally {
            demo.close(); context.deleteDatabase("apophenia-demo.db")
        }
    }

    private fun insertEvent(label: String, vibe: Int?, egress: Boolean): Long = db.insertObservation(
        Observation(
            timestampMs = 1_000L + db.observations().size,
            kind = if (vibe == null) ObservationKind.WEIRD else ObservationKind.VIBE,
            label = label,
            vibeRating = vibe,
            egress = egress
        )
    )

    private fun insertContext(eventId: Long) {
        db.insertContext(
            listOf(
                ContextSample(timestampMs = 900, observationId = eventId, source = "mavlink", metric = "mavlink_hdop", value = 2.1, unit = "HDOP", captureId = "event:$eventId", phase = ContextPhase.PRE),
                ContextSample(timestampMs = 950, observationId = eventId, source = "audio_derived", metric = "audio_loudness_dbfs", value = -18.0, unit = "dBFS", captureId = "event:$eventId:audio", phase = ContextPhase.PRE),
                ContextSample(timestampMs = 1_100, observationId = eventId, source = "audio_derived", metric = "audio_loudness_dbfs", value = -12.0, unit = "dBFS", captureId = "event:$eventId:audio", phase = ContextPhase.POST)
            )
        )
    }

    private val fakeEvidence = object : ExportEvidenceMaterializer {
        override fun sensitivePayloads(records: List<SensitiveContextRecord>) = listOf(
            ExportPayload("tier2/contents.json", "protected".toByteArray(), containsTier2Contents = true)
        )

        override fun mediaPayloads(assets: List<MediaAsset>) = listOf(
            ExportPayload("av/event/mock/audio.wav", byteArrayOf(1, 2, 3), containsRawAv = true)
        )
    }

    private val fakePdf = object : ReportPdfRenderer {
        override fun render(events: List<ReportEvent>, findings: List<ReportFinding>, stills: Map<Long, ByteArray>) = "%PDF-1.4\n%%EOF".toByteArray()
    }

    companion object { private const val DB_NAME = "event-report-test.db" }
}
