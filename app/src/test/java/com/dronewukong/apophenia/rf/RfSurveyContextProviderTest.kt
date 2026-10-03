package com.dronewukong.apophenia.rf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.HardwareGates
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
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
class RfSurveyContextProviderTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
        context.getSharedPreferences("rf_survey", Context.MODE_PRIVATE).edit().clear().commit()
        java.io.File(context.filesDir, "rf-survey").deleteRecursively()
    }

    @After fun tearDown() {
        HardwareGates.clearAuthorizationsForTests(context)
        java.io.File(context.filesDir, "rf-survey").deleteRecursively()
    }

    @Test fun spectrumFindsKnownPositiveOffset() {
        val iq = tone(256, 32)
        val summary = RfSpectrumAnalyzer.summarize(iq, 1_024_000)!!
        assertEquals(128_000.0, summary.peakOffsetHz, 0.1)
        assertTrue(summary.rmsDbfs.isFinite())
        assertTrue(summary.peakDbfs.isFinite())
    }

    @Test fun gateOffBypassesTransportAndAuthorizedWindowIsBoundedAndHashed() {
        var calls = 0
        val bytes = tone(2_048, 64)
        val transport = object : RfSurveyTransport {
            override fun capture(config: RfSurveyConfig): ByteArray { calls++; return bytes }
        }
        val provider = RfSurveyContextProvider(context, transport)
        assertTrue(provider.collect(7, false).isEmpty())
        assertEquals(0, calls)
        HardwareGates.setAuthorized(
            context, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE, true,
            HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        )
        RfSurveySettings.save(context, RfSurveyConfig(windowMs = 50))
        val rows = provider.collect(7, false)
        assertEquals(1, calls)
        assertTrue(rows.any { it.metric == "rf_peak_offset_hz" })
        assertTrue(rows.all { it.captureId.contains("event:7:rf_survey") && it.metadata.contains("bounded=true") })
        val file = java.io.File(context.filesDir, "rf-survey").listFiles()!!.single()
        assertEquals(bytes.size.toLong(), file.length())
        assertTrue(rows.none { it.metadata.contains(file.absolutePath) })
        assertTrue(rows.all { it.metadata.contains("sha256=") })
    }

    @Test fun simulationPersistsOnlyTheConfiguredCaptureWindow() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        HardwareGates.setAuthorized(
            context, HardwareGates.Gate.LIVE_RF_SURVEY_CAPTURE, true,
            HardwareGates.ConsentProof.CapabilityConditionalConfirmation
        )
        RfSurveySettings.save(context, RfSurveyConfig(sampleRateHz = 256_000, windowMs = 50))
        val rows = RfSurveyContextProvider(context).collect(null, true)
        assertTrue(rows.any { it.metric == "rf_iq_bytes" && it.value <= 25_600.0 })
        assertTrue(rows.all { it.isControl && it.captureId.startsWith("control:rf_survey:") })
    }

    private fun tone(points: Int, cycles: Int): ByteArray = ByteArray(points * 2).also { output ->
        repeat(points) { n ->
            val angle = 2.0 * PI * cycles * n / points
            output[n * 2] = (127.5 + 60.0 * cos(angle)).toInt().toByte()
            output[n * 2 + 1] = (127.5 + 60.0 * sin(angle)).toInt().toByte()
        }
    }
}
