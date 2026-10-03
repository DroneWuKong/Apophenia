package com.dronewukong.apophenia.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextPhase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlin.math.PI
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AudioRingBufferTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        AudioRingCaptureManager.resetForTests()
    }

    @After fun tearDown() = AudioRingCaptureManager.resetForTests()

    @Test fun circularBufferReturnsNewestBytesInChronologicalOrder() {
        val ring = PcmRingBuffer(6)
        ring.write(byteArrayOf(1, 2, 3, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), ring.snapshot())
        ring.write(byteArrayOf(5, 6, 7, 8))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), ring.snapshot())
    }

    @Test fun freezeUsesTapTimeAndKeepsPreAndPostWindowsSeparate() {
        val done = CountDownLatch(1)
        var captured: List<Any> = emptyList()
        AudioRingCaptureManager.setFinalizerForTests { id, at, pre, post ->
            captured = listOf(id, at, pre, post)
            done.countDown()
        }
        AudioRingCaptureManager.arm(context, simulation = true, preSeconds = 1, postSeconds = 1)
        val pre = ByteArray(32_000) { 1 }
        val post = ByteArray(32_000) { 2 }
        AudioRingCaptureManager.ingest(pre)
        val eventAt = System.currentTimeMillis()
        val token = AudioRingCaptureManager.freezeNow(eventAt)
        AudioRingCaptureManager.attach(token, 17L)
        AudioRingCaptureManager.ingest(post)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(17L, captured[0])
        assertEquals(eventAt, captured[1])
        assertArrayEquals(pre, captured[2] as ByteArray)
        assertArrayEquals(post, captured[3] as ByteArray)
    }

    @Test fun delayedExternalTimestampDoesNotMislabelCurrentPhoneAudio() {
        AudioRingCaptureManager.arm(context, simulation = true, preSeconds = 1, postSeconds = 1)
        AudioRingCaptureManager.ingest(ByteArray(32_000) { 3 })
        assertEquals(null, AudioRingCaptureManager.freezeNow(System.currentTimeMillis() - 60_000L))
    }

    @Test fun derivedFeaturesRemainGroupedAndPostIsExcludedByPhase() {
        val pcm = tone(60.0, seconds = 2)
        val summary = AudioFeatureExtractor.summarize(pcm, 16_000)
        assertEquals(2, summary.loudnessCurveDbfs.size)
        assertTrue(summary.humDbfs > summary.voiceDbfs)
        val rows = AudioFeatureExtractor.contextSamples(5, 100_000, "event:5:audio", ContextPhase.POST, pcm, 16_000)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.phase == ContextPhase.POST && it.captureId == "event:5:audio" })
        assertEquals(100_000L, rows.minOf { it.timestampMs })
    }

    @Test fun eventCipherRoundTripsAndRejectsWrongKey() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
        val other = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
        val plaintext = "pre-event sound".encodeToByteArray()
        val encrypted = AudioArtifactCrypto.encrypt(key, plaintext)
        assertArrayEquals(plaintext, AudioArtifactCrypto.decrypt(key, encrypted))
        assertTrue(runCatching { AudioArtifactCrypto.decrypt(other, encrypted) }.isFailure)
    }

    private fun tone(hz: Double, seconds: Int): ByteArray {
        val samples = 16_000 * seconds
        return ByteArray(samples * 2).also { output ->
            repeat(samples) { index ->
                val value = (8_000.0 * sin(2.0 * PI * hz * index / 16_000.0)).toInt().toShort()
                output[index * 2] = (value.toInt() and 0xff).toByte()
                output[index * 2 + 1] = ((value.toInt() shr 8) and 0xff).toByte()
            }
        }
    }
}
