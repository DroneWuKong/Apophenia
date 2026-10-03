package com.dronewukong.apophenia.ingest

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.export.ExportTier
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
class InboundShareTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("inbound-share-test.db")
        context.filesDir.resolve(ObservationAttachmentStore.DIRECTORY).deleteRecursively()
        db = ObservationDb(context, "inbound-share-test.db")
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("inbound-share-test.db")
        context.filesDir.resolve(ObservationAttachmentStore.DIRECTORY).deleteRecursively()
    }

    @Test
    fun textShareUsesReceiptTimestampAndPersistsVerifiableAttachmentWithoutSourceUri() {
        val receivedAt = 1_234_567L
        val parsed = InboundShareParser.parse(
            context,
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Field note")
                .putExtra(Intent.EXTRA_TEXT, "shared body"),
            receivedAt
        )
        assertEquals(receivedAt, parsed.request.timestampMs)
        assertEquals(ObservationOrigin.EXTERNAL, parsed.request.origin)
        assertEquals("Field note", parsed.request.label)

        val observationId = db.insertObservation(
            Observation(timestampMs = receivedAt, kind = ObservationKind.OBSERVATION, label = parsed.request.label, origin = ObservationOrigin.EXTERNAL)
        )
        val row = ObservationAttachmentStore(context, db).store(observationId, parsed.attachment, receivedAt)
        val payloads = ObservationAttachmentStore(context, db).exportPayloads(db.observationAttachments(observationId))

        assertEquals("shared body", context.filesDir.resolve(row.relativePath).readText())
        assertEquals(2, payloads.size)
        assertTrue(payloads.any { it.path == "attachments/index.json" })
        assertFalse(payloads.joinToString { it.path }.contains("content://"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedShareActionIsRejected() {
        InboundShareParser.parse(context, Intent(Intent.ACTION_VIEW), 1L)
    }

    @Test
    fun taskerContractIgnoresCallerTimestampAndRejectsPrivilegedKinds() {
        val request = TaskerAutomationContract.captureRequest(
            Intent(TaskerAutomationContract.ACTION_LOG)
                .putExtra(TaskerAutomationContract.EXTRA_LABEL, "Automated marker")
                .putExtra("timestamp_ms", 1L),
            9_999L
        )
        assertEquals(9_999L, request.timestampMs)
        assertEquals("Automated marker", request.label)

        val rejected = runCatching {
            TaskerAutomationContract.captureRequest(
                Intent(TaskerAutomationContract.ACTION_LOG).putExtra(TaskerAutomationContract.EXTRA_KIND, "VIBE"),
                10_000L
            )
        }
        assertTrue(rejected.isFailure)
        assertEquals(ExportTier.DATA_ONLY, TaskerAutomationContract.exportTier(TaskerAutomationContract.ACTION_EXPORT_DATA))
        assertEquals(ExportTier.FULL_EVIDENCE, TaskerAutomationContract.exportTier(TaskerAutomationContract.ACTION_EXPORT_FULL))
        assertEquals(null, TaskerAutomationContract.exportTier("com.example.UNKNOWN"))
    }
}
