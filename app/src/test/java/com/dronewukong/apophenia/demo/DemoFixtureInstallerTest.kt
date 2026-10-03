package com.dronewukong.apophenia.demo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.data.AnalysisCohort
import com.dronewukong.apophenia.data.MediaStatus
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.VibeGrade
import com.dronewukong.apophenia.data.HypothesisOutcome
import com.dronewukong.apophenia.correlation.AssociationEngine
import com.dronewukong.apophenia.correlation.CaptureMatcher
import com.dronewukong.apophenia.correlation.HypothesisEvaluator
import com.dronewukong.apophenia.export.ExportManager
import com.dronewukong.apophenia.hardware.HardwareGates
import java.io.File
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
class DemoFixtureInstallerTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb
    private val anchor = 1_799_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DemoModeManager.DATABASE_NAME)
        db = ObservationDb(context, DemoModeManager.DATABASE_NAME)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.LIVE)
        context.getSharedPreferences("demo_mode", Context.MODE_PRIVATE).edit().clear().commit()
        DemoModeManager.load(context)
    }

    @After
    fun tearDown() {
        DemoModeManager.disable(context)
        db.close()
        context.deleteDatabase(DemoModeManager.DATABASE_NAME)
    }

    @Test
    fun installsCompleteIsolatedSixtyDayCorpus() {
        val summary = DemoFixtureInstaller.reset(db, anchor)

        assertEquals(45, summary.eventCount)
        assertEquals(120, summary.controlCaptureCount)
        assertEquals(1, summary.registeredHypothesisCount)
        assertEquals(1, summary.flightSessionCount)
        assertEquals(1, summary.purgeEntryCount)
        assertEquals(60, db.rollingStatus().first)
        assertTrue(db.observations().all { it.origin == ObservationOrigin.SIMULATION })
        val span = db.observations().maxOf { it.timestampMs } - db.observations().minOf { it.timestampMs }
        assertTrue(span >= 58L * 86_400_000L)
    }

    @Test
    fun bleStoryContainsFourteenOfSixteenEventsAndThreeOfFortyControls() {
        DemoFixtureInstaller.reset(db, anchor)
        val weird = db.observations().filter { it.kind == ObservationKind.WEIRD && it.label == "That was weird" }
        val eventPresence = weird.count { observation ->
            db.contextForObservation(observation.id).any { it.metric == "bt_device_rssi_dbm" && it.metadata.contains(DemoFixtureInstaller.DEMO_DEVICE_HASH) }
        }
        val btControls = db.allContext(100_000).filter { it.isControl && it.metric == "bt_nearby_count" }.map { it.captureId }.distinct()
        val controlPresence = db.allContext(100_000).filter {
            it.isControl && it.metric == "bt_device_rssi_dbm" && it.metadata.contains(DemoFixtureInstaller.DEMO_DEVICE_HASH)
        }.map { it.captureId }.distinct()

        assertEquals(16, weird.size)
        assertEquals(14, eventPresence)
        assertEquals(40, btControls.size)
        assertEquals(3, controlPresence.size)
    }

    @Test
    fun includesRefutedRegistrationEgressFlightAndPurgedAvStories() {
        DemoFixtureInstaller.reset(db, anchor)
        val hypothesis = db.hypotheses().single()
        assertEquals(AnalysisCohort.BAD_VIBE_STAYED, hypothesis.cohortId)
        assertTrue(hypothesis.note.contains("bad vibes follow poor sleep"))

        val egress = db.observations().filter { it.egress }
        assertEquals(2, egress.size)
        assertTrue(egress.all { it.kind == ObservationKind.VIBE && it.vibeRating == 5 && it.label == VibeGrade.EGRESS_LABEL })

        val flight = db.observations().single { it.label == "Flight anomaly" }
        val flightMetrics = db.contextForObservation(flight.id).map { it.metric }.toSet()
        assertTrue(setOf("mavlink_hdop", "control_link_margin_db", "field_kit_threshold_crossed", "health_stress_score").all { it in flightMetrics })
        val priorVibe = db.observations().single { it.note.contains("90 seconds before") }
        assertEquals(90_000L, flight.timestampMs - priorVibe.timestampMs)

        val av = db.observations().single { it.label == "AV transient" }
        assertTrue(db.contextForObservation(av.id).any { it.metric == "audio_broadband_energy_ratio" })
        assertEquals(MediaStatus.PURGED, db.mediaAssets(av.id, includePurged = true).single().status)
        assertTrue(db.purgeLedger().single().derivedMetricsRetained)
    }

    @Test
    fun demoDatabaseCannotUseLiveJsonExporterAndModeRestoresRuntime() {
        DemoFixtureInstaller.reset(db, anchor)
        assertThrows(IllegalArgumentException::class.java) {
            ExportManager.exportJson(db, File(context.cacheDir, "demo-export-test"))
        }

        DemoModeManager.enable(context, anchor)
        assertTrue(DemoModeManager.state.value.active)
        assertEquals(HardwareGates.RuntimeMode.SIMULATION, HardwareGates.runtimeMode)
        DemoModeManager.disable(context)
        assertFalse(DemoModeManager.state.value.active)
        assertEquals(HardwareGates.RuntimeMode.LIVE, HardwareGates.runtimeMode)
    }

    @Test
    fun fixturesDriveConfirmedRefutedAndSmallSampleEngineStories() {
        DemoFixtureInstaller.reset(db, anchor)
        val weirdCohort = AnalysisCohort.labelId("That was weird")
        val values = linkedMapOf<String, Pair<List<Double>, List<Double>>>()
        repeat(10) { index ->
            val metric = "demo_decoy_${(index + 1).toString().padStart(2, '0')}"
            val matched = CaptureMatcher.match(
                db.eventFeatureCapturesForCohort(weirdCohort, metric),
                db.controlFeatureCaptures(metric)
            )
            values[metric] = matched.events to matched.controls
        }
        val deviceFeature = db.devicePresenceCapturesForCohort(weirdCohort).entries.single {
            it.key.contains(DemoFixtureInstaller.DEMO_DEVICE_HASH)
        }
        val deviceMatched = CaptureMatcher.match(deviceFeature.value.first, deviceFeature.value.second)
        values[deviceFeature.key] = deviceMatched.events to deviceMatched.controls
        val deviceResult = AssociationEngine.compareAll(values, seed = 77).getValue(deviceFeature.key)
        assertTrue(deviceResult.adjustedP != null && deviceResult.adjustedP <= 0.05)
        assertFalse(deviceResult.indistinguishableFromNoise)

        val hypothesis = db.hypotheses().single()
        val sleepMatched = CaptureMatcher.match(
            db.eventFeatureCapturesForCohort(hypothesis.cohortId, hypothesis.metric),
            db.controlFeatureCaptures(hypothesis.metric)
        )
        val sleepResult = AssociationEngine.compare(sleepMatched.events, sleepMatched.controls, seed = 88, featureName = hypothesis.metric)
        val evaluation = requireNotNull(HypothesisEvaluator.evaluate(hypothesis, sleepResult, anchor + 1))
        assertEquals(HypothesisOutcome.REFUTED, evaluation.outcome)
        assertTrue(evaluation.summary.contains("Good news"))

        val weatherCohort = AnalysisCohort.labelId("Weather front weird")
        val weatherMatched = CaptureMatcher.match(
            db.eventFeatureCapturesForCohort(weatherCohort, "weather_front_strength"),
            db.controlFeatureCaptures("weather_front_strength")
        )
        val weather = AssociationEngine.compare(weatherMatched.events, weatherMatched.controls, seed = 99, featureName = "weather_front_strength")
        assertEquals(9, weather.eventCount)
        assertTrue(weather.smallSample)
        assertTrue(weather.plainLanguageSummary.contains("Interesting, not yet established"))
    }
}
