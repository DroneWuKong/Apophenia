package com.dronewukong.apophenia.export

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ExportRoutesTest {
    @Test
    fun multipleBundlesUseBatchSharesheetIntentWithReadGrants() {
        val files = listOf(File("first.zip"), File("second.zip"))
        val intent = ExportRoutes.shareIntent(files) { Uri.parse("content://exports/${it.name}") }

        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("application/zip", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(2, intent.clipData!!.itemCount)
        assertEquals(2, IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!.size)
    }

    @Test
    fun documentWriterCopiesTheExactPreparedBundleBytes() {
        val source = File.createTempFile("apophenia-route", ".zip").apply { writeBytes(byteArrayOf(0, 1, 2, 3, 127, -1)) }
        val output = ByteArrayOutputStream()
        try {
            assertEquals(source.length(), ExportRoutes.writeDocument(source, output))
            assertArrayEquals(source.readBytes(), output.toByteArray())
        } finally {
            source.delete()
        }
    }
}
