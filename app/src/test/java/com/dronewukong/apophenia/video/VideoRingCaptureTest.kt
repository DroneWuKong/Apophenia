package com.dronewukong.apophenia.video

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.hardware.HardwareGates
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class VideoRingCaptureTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        VideoRingCaptureManager.resetForTests()
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @After fun tearDown() {
        VideoRingCaptureManager.resetForTests()
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @Test fun frameRingRetainsOnlyNewestFrames() {
        val ring = VideoFrameRing(2)
        ring.add(frame(1, 10))
        ring.add(frame(2, 20))
        ring.add(frame(3, 30))
        assertEquals(listOf(2L, 3L), ring.snapshot().map { it.timestampMs })
    }

    @Test fun freezeSeparatesPerStreamPreAndPostFrames() {
        val done = CountDownLatch(1)
        var result: Map<String, Pair<List<VideoFrame>, List<VideoFrame>>> = emptyMap()
        VideoRingCaptureManager.setFinalizerForTests { id, _, streams ->
            assertEquals(9L, id)
            result = streams
            done.countDown()
        }
        VideoRingCaptureManager.startSource(context, "camera", setOf("camera_back_0", "camera_front_0"))
        val now = System.currentTimeMillis()
        VideoRingCaptureManager.ingest(frame(now - 500, 10, "camera_back_0"))
        VideoRingCaptureManager.ingest(frame(now - 400, 20, "camera_front_0"))
        val token = VideoRingCaptureManager.freezeNow(now)!!
        VideoRingCaptureManager.attach(token, 9)
        VideoRingCaptureManager.ingest(frame(now + 500, 30, "camera_back_0"))
        VideoRingCaptureManager.finalizeNowForTests(token)
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertEquals(1, result.getValue("camera_back_0").first.size)
        assertEquals(1, result.getValue("camera_back_0").second.size)
        assertEquals(1, result.getValue("camera_front_0").first.size)
    }

    @Test fun derivedFramesExposeMotionBrightnessBandingAndPostPhase() {
        val flat = frame(1_000, 20)
        val stripes = frame(1_500, 20, luma = byteArrayOf(
            0, 0, 0, 0,
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte(),
            0, 0, 0, 0,
            255.toByte(), 255.toByte(), 255.toByte(), 255.toByte()
        ))
        val rows = VideoFeatureExtractor.contextSamples(4, "event:4:video:back", ContextPhase.POST, listOf(flat, stripes))
        assertTrue(rows.all { it.phase == ContextPhase.POST && it.captureId == "event:4:video:back" })
        assertTrue(rows.any { it.metric == "video_motion_energy" && it.value > 0.0 })
        assertTrue(rows.any { it.metric == "video_pwm_banding_score" && it.value > 0.0 })
        assertTrue(rows.any { it.metric == "video_pwm_frequency_observable" && it.value == 0.0 })
    }

    @Test fun delayedTimestampDoesNotMislabelCurrentVideoRing() {
        VideoRingCaptureManager.startSource(context, "camera", setOf("camera_back_0"))
        VideoRingCaptureManager.ingest(frame(System.currentTimeMillis(), 10))
        assertEquals(null, VideoRingCaptureManager.freezeNow(System.currentTimeMillis() - 60_000))
    }

    @Test fun callAudioExplainsPlatformAndStatutoryGaps() {
        HardwareGates.setAuthorized(
            context, HardwareGates.Gate.LIVE_CALL_AUDIO_CAPTURE, true,
            HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        )
        CallAudioCapability.setJurisdiction(context, CallConsentJurisdiction.UNKNOWN)
        assertEquals(HardwareGates.CapabilityState.PLATFORM_RESTRICTED, CallAudioCapability.capability(context))
        CallAudioCapability.setJurisdiction(context, CallConsentJurisdiction.ALL_PARTY)
        assertEquals(HardwareGates.CapabilityState.LOCKED_BY_STATUTE, CallAudioCapability.capability(context))
        assertTrue(CallAudioCapability.explanation(context).contains("Locked by statute"))
    }

    private fun frame(timestamp: Long, value: Int, stream: String = "camera_back_0", luma: ByteArray = ByteArray(16) { value.toByte() }) =
        VideoFrame(stream, stream.substringAfter("camera_"), timestamp, byteArrayOf(1, 2, 3), 4, 4, luma)
}
