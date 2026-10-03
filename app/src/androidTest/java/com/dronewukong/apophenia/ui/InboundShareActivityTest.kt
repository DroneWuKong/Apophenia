package com.dronewukong.apophenia.ui

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.ingest.ObservationAttachmentStore
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboundShareActivityTest {
    private lateinit var context: Context

    @Before
    fun clearData() {
        context = ApplicationProvider.getApplicationContext()
        ObservationDb(context).use { it.deleteAllData() }
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
    }

    @After
    fun cleanUp() {
        ObservationDb(context).use { it.deleteAllData() }
        File(context.filesDir, ObservationAttachmentStore.DIRECTORY).deleteRecursively()
    }

    @Test
    fun actionSendTextCreatesExternalObservationAndPrivateAttachment() {
        val before = System.currentTimeMillis()
        val intent = Intent(Intent.ACTION_SEND)
            .setClass(context, ShareToApopheniaActivity::class.java)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Shared test note")
            .putExtra(Intent.EXTRA_TEXT, "attachment body")

        ActivityScenario.launch<ShareToApopheniaActivity>(intent).use {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val complete = ObservationDb(context).use { db ->
                    db.observations().singleOrNull()?.let { observation ->
                        val attachment = db.observationAttachments(observation.id).singleOrNull()
                        observation.origin == ObservationOrigin.EXTERNAL && attachment != null
                    } == true
                }
                if (complete) break
                Thread.sleep(50)
            }
        }

        ObservationDb(context).use { db ->
            val observation = db.observations().single()
            val attachment = db.observationAttachments(observation.id).single()
            assertEquals("Shared test note", observation.label)
            assertEquals(ObservationOrigin.EXTERNAL, observation.origin)
            assertTrue(observation.timestampMs >= before)
            assertEquals("attachment body", File(context.filesDir, attachment.relativePath).readText())
        }
    }
}
