package com.dronewukong.apophenia.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.export.ExportManager
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
class ObservationDbTest {
    private lateinit var context: Context
    private lateinit var db: ObservationDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("apophenia.db")
        db = ObservationDb(context)
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun preservesTimestampAndDeduplicatesExternalEvents() {
        val exactTime = 1_726_000_123_456L
        val first = db.insertObservationOrGet(
            Observation(
                timestampMs = exactTime,
                kind = ObservationKind.OBSERVATION,
                label = "Light",
                origin = ObservationOrigin.GARMIN,
                externalEventId = "watch-install:42"
            )
        )
        val duplicate = db.insertObservationOrGet(
            Observation(
                timestampMs = exactTime + 99_999L,
                kind = ObservationKind.OBSERVATION,
                label = "Duplicate",
                origin = ObservationOrigin.GARMIN,
                externalEventId = "watch-install:42"
            )
        )

        assertTrue(first.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(first.id, duplicate.id)
        assertEquals(exactTime, db.observationTimestamp(first.id))
        assertEquals(1, db.observations().size)
    }

    @Test
    fun rollingCopiesSeparatePrePostAndControlGroups() {
        val eventTime = System.currentTimeMillis()
        val observationId = db.insertObservation(
            Observation(timestampMs = eventTime, kind = ObservationKind.OBSERVATION, label = "Headache")
        )
        db.insertRolling(
            listOf(
                sample(eventTime - 20 * 60_000L, 10.0),
                sample(eventTime - 5 * 60_000L, 14.0),
                sample(eventTime + 5 * 60_000L, 99.0)
            ),
            retentionMs = 90 * 60_000L
        )

        assertEquals(2, db.copyRollingToObservation(observationId, eventTime - 30 * 60_000L, eventTime - 1, ContextPhase.PRE))
        assertEquals(1, db.copyRollingToObservation(observationId, eventTime, eventTime + 30 * 60_000L, ContextPhase.POST))
        assertEquals(0, db.copyRollingToObservation(observationId, eventTime, eventTime + 30 * 60_000L, ContextPhase.POST))

        db.insertContext(
            listOf(
                control(eventTime - 2_000, 2.0, "control:a"),
                control(eventTime - 1_000, 4.0, "control:a"),
                control(eventTime, 8.0, "control:b")
            )
        )

        assertEquals(listOf(12.0), db.eventFeatureValues("Headache", "pressure_hpa"))
        assertEquals(listOf(3.0, 8.0), db.controlFeatureValues("pressure_hpa"))
        assertEquals(listOf(ContextPhase.PRE, ContextPhase.PRE, ContextPhase.POST), db.contextForObservation(observationId).map { it.phase })
    }

    @Test
    fun hypothesesRemainSeparateFromEvidenceAndDeleteCascades() {
        val observationId = db.insertObservation(
            Observation(timestampMs = 100, kind = ObservationKind.COINCIDENCE, label = "Coincidence")
        )
        db.insertContext(listOf(sample(100, 1.0).copy(observationId = observationId)))
        db.insertHypothesis(
            Hypothesis(createdAtMs = 101, eventLabel = "Weather idea", metric = "", note = "Could pressure matter?")
        )

        assertEquals(listOf("Coincidence" to 1), db.labels())
        assertEquals(1, db.hypotheses().size)
        assertTrue(db.deleteObservation(observationId))
        assertTrue(db.contextForObservation(observationId).isEmpty())
        assertEquals(1, db.hypotheses().size)
    }

    @Test
    fun vibeAndEgressAreFirstClassTimestampedEvidence() {
        val timestamp = 1_780_123_456_789L
        val id = db.insertObservation(
            Observation(
                timestampMs = timestamp,
                kind = ObservationKind.VIBE,
                label = VibeGrade.EGRESS_LABEL,
                origin = ObservationOrigin.WIDGET,
                vibeRating = 5,
                egress = true
            )
        )

        val stored = db.observations().single()
        assertEquals(id, stored.id)
        assertEquals(timestamp, stored.timestampMs)
        assertEquals(ObservationKind.VIBE, stored.kind)
        assertEquals(5, stored.vibeRating)
        assertTrue(stored.egress)
    }

    @Test
    fun vibeModelRejectsInvalidOrLeakedFields() {
        assertThrows(IllegalArgumentException::class.java) {
            Observation(timestampMs = 1, kind = ObservationKind.VIBE, label = "Bad vibe")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Observation(
                timestampMs = 1,
                kind = ObservationKind.OBSERVATION,
                label = "Not a vibe",
                vibeRating = 2
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Observation(
                timestampMs = 1,
                kind = ObservationKind.VIBE,
                label = "Stayed",
                vibeRating = 4,
                egress = true
            )
        }
    }

    @Test
    fun vibePresentationContractIsExact() {
        assertEquals(
            listOf(
                "Vibe good 🙂",
                "Tolerable 😐",
                "Bad 🙁",
                "Fucked 😖",
                "Fucky 😵‍💫"
            ),
            (1..5).map { VibeGrade.fromRating(it).renderedLabel }
        )
        assertEquals("FUCK THIS, I'M OUT", VibeGrade.EGRESS_LABEL)
    }

    @Test
    fun upgradesVersionTwoDataWithoutLosingRows() {
        db.close()
        context.deleteDatabase("apophenia.db")
        val legacy = context.openOrCreateDatabase("apophenia.db", Context.MODE_PRIVATE, null)
        legacy.execSQL("CREATE TABLE observations(id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp_ms INTEGER NOT NULL,kind TEXT NOT NULL,label TEXT NOT NULL,note TEXT NOT NULL DEFAULT '',severity INTEGER,confidence INTEGER NOT NULL DEFAULT 3)")
        legacy.execSQL("CREATE TABLE context_samples(id INTEGER PRIMARY KEY AUTOINCREMENT,timestamp_ms INTEGER NOT NULL,observation_id INTEGER,is_control INTEGER NOT NULL DEFAULT 0,source TEXT NOT NULL,metric TEXT NOT NULL,value REAL NOT NULL,unit TEXT NOT NULL,metadata TEXT NOT NULL DEFAULT '',capture_id TEXT NOT NULL DEFAULT '')")
        legacy.execSQL("CREATE TABLE hypotheses(id INTEGER PRIMARY KEY AUTOINCREMENT,created_at_ms INTEGER NOT NULL,event_label TEXT NOT NULL,metric TEXT NOT NULL,direction TEXT NOT NULL DEFAULT 'ANY',enabled INTEGER NOT NULL DEFAULT 1)")
        legacy.execSQL("INSERT INTO observations(timestamp_ms,kind,label) VALUES(123,'OBSERVATION','Legacy')")
        legacy.version = 2
        legacy.close()

        db = ObservationDb(context)
        assertEquals(11, db.readableDatabase.version)
        assertEquals("Legacy", db.observations().single().label)
        assertEquals(ObservationOrigin.ANDROID, db.observations().single().origin)
    }

    @Test
    fun jsonExportIncludesEvidenceHypothesesControlsAndPhases() {
        val observationId = db.insertObservation(
            Observation(
                timestampMs = 500,
                kind = ObservationKind.VIBE,
                label = VibeGrade.FUCKED.renderedLabel,
                vibeRating = VibeGrade.FUCKED.rating
            )
        )
        db.insertSession(
            CaptureSession(
                id = "drive:test",
                type = CaptureSessionType.DRIVE_SESSION,
                startedAtMs = 450,
                identityHash = "idhash:v1:test"
            )
        )
        db.insertSessionEvents(
            listOf(
                SessionEvent(
                    timestampMs = 475,
                    sessionId = "drive:test",
                    eventType = "STATUSTEXT",
                    severity = 4,
                    text = "verbatim flight text",
                    metadata = "message_id=253"
                )
            )
        )
        db.insertContext(listOf(sample(490, 1009.2).copy(observationId = observationId, phase = ContextPhase.PRE, sessionId = "drive:test")))
        db.insertContext(listOf(sample(480, 52.0).copy(source = "obd_elm327", metric = "obd_vehicle_speed_kph", unit = "km/h", sessionId = "drive:test", captureId = "drive:test:stream:480")))
        db.insertContext(listOf(control(600, 1008.0, "control:export")))
        db.insertHypothesis(Hypothesis(createdAtMs = 700, eventLabel = "Pressure idea", metric = "pressure_hpa"))
        db.insertSensitiveContext(
            listOf(
                SensitiveContextRecord(
                    timestampMs = 701,
                    observationId = observationId,
                    source = "android_tier2",
                    contentType = "notification_contents",
                    ciphertextBase64 = "should-never-export",
                    ivBase64 = "iv",
                    keyAlias = "alias",
                    captureId = "event:$observationId:instant"
                )
            )
        )

        val outputDirectory = context.cacheDir.resolve("export-test")
        val exported = ExportManager.exportJson(db, outputDirectory)
        val json = JSONObject(exported.readText())

        assertEquals(11, json.getInt("schema"))
        val observation = json.getJSONArray("observations").getJSONObject(0)
        assertEquals(4, observation.getInt("vibeRating"))
        assertFalse(observation.getBoolean("egress"))
        assertEquals("PRE", observation.getJSONArray("context").getJSONObject(0).getString("phase"))
        assertEquals("drive:test", observation.getJSONArray("context").getJSONObject(0).getString("sessionId"))
        assertEquals(1, json.getJSONArray("hypotheses").length())
        assertEquals("CONTROL", json.getJSONArray("controls").getJSONObject(0).getString("phase"))
        assertFalse(json.has("sensitiveContext"))
        assertFalse(exported.readText().contains("should-never-export"))
        assertEquals("DRIVE_SESSION", json.getJSONArray("sessions").getJSONObject(0).getString("type"))
        assertEquals("drive:test", json.getJSONArray("sessionContext").getJSONObject(0).getString("sessionId"))
        assertEquals("verbatim flight text", json.getJSONArray("sessionEvents").getJSONObject(0).getString("text"))
        outputDirectory.deleteRecursively()
    }

    @Test
    fun captureSessionLifecycleAndContextJoinAreDurable() {
        db.insertSession(
            CaptureSession(
                id = "drive:one",
                type = CaptureSessionType.DRIVE_SESSION,
                startedAtMs = 1_000,
                identityHash = "idhash:v1:adapter"
            )
        )
        db.insertContext(listOf(sample(1_100, 44.0).copy(sessionId = "drive:one")))

        assertEquals("drive:one", db.allContext().single().sessionId)
        assertTrue(db.endSession("drive:one", 2_000, CaptureSessionStatus.COMPLETED, "end_reason=test"))
        val session = db.session("drive:one")!!
        assertEquals(CaptureSessionStatus.COMPLETED, session.status)
        assertEquals(2_000L, session.endedAtMs)
        assertTrue(session.metadata.contains("end_reason=test"))
    }

    @Test
    fun tierTwoContentsStayEncryptedInTheirOwnCascadingTable() {
        val observationId = db.insertObservation(
            Observation(timestampMs = 800, kind = ObservationKind.OBSERVATION, label = "Protected")
        )
        db.insertSensitiveContext(
            listOf(
                SensitiveContextRecord(
                    timestampMs = 801,
                    observationId = observationId,
                    source = "simulation/tier2",
                    contentType = "notification_contents",
                    ciphertextBase64 = "ciphertext-only",
                    ivBase64 = "iv-only",
                    keyAlias = "test",
                    captureId = "event:$observationId:instant"
                )
            )
        )

        val stored = db.sensitiveContextForObservation(observationId).single()
        assertEquals("ciphertext-only", stored.ciphertextBase64)
        assertEquals("notification_contents", stored.contentType)
        assertTrue(db.deleteObservation(observationId))
        assertTrue(db.sensitiveContextForObservation(observationId).isEmpty())
    }

    @Test
    fun egressAndBadStayedAreSeparateAnalysisCohorts() {
        val egressId = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.VIBE, label = VibeGrade.EGRESS_LABEL, vibeRating = 5, egress = true))
        val stayedId = db.insertObservation(Observation(timestampMs = 2_000, kind = ObservationKind.VIBE, label = VibeGrade.FUCKED.renderedLabel, vibeRating = 4))
        db.insertContext(listOf(
            sample(1_000, 10.0).copy(observationId = egressId),
            sample(2_000, 20.0).copy(observationId = stayedId)
        ))

        val cohorts = db.analysisCohorts().associateBy { it.id }
        assertEquals(1, cohorts.getValue(AnalysisCohort.EGRESS).eventCount)
        assertEquals("Egress · bailed", cohorts.getValue(AnalysisCohort.EGRESS).displayName)
        assertEquals(1, cohorts.getValue(AnalysisCohort.BAD_VIBE_STAYED).eventCount)
        assertEquals(listOf(10.0), db.eventFeatureCapturesForCohort(AnalysisCohort.EGRESS, "pressure_hpa").map { it.value })
        assertEquals(listOf(20.0), db.eventFeatureCapturesForCohort(AnalysisCohort.BAD_VIBE_STAYED, "pressure_hpa").map { it.value })
    }

    @Test
    fun hashedDevicePresenceUsesOnlyCapturesWhereTheChannelRan() {
        val first = db.insertObservation(Observation(timestampMs = 1_000, kind = ObservationKind.VIBE, label = VibeGrade.EGRESS_LABEL, vibeRating = 5, egress = true))
        val second = db.insertObservation(Observation(timestampMs = 2_000, kind = ObservationKind.VIBE, label = VibeGrade.EGRESS_LABEL, vibeRating = 5, egress = true))
        db.insertObservation(Observation(timestampMs = 3_000, kind = ObservationKind.VIBE, label = VibeGrade.EGRESS_LABEL, vibeRating = 5, egress = true))
        db.insertContext(listOf(
            ContextSample(timestampMs = 1_000, observationId = first, source = "android_bluetooth", metric = "bt_nearby_count", value = 1.0, unit = "count", captureId = "event:$first:instant"),
            ContextSample(timestampMs = 1_000, observationId = first, source = "android_bluetooth", metric = "bt_device_rssi_dbm", value = -40.0, unit = "dBm", metadata = "device_hash=idhash:v1:alpha;window=instant", captureId = "event:$first:instant"),
            ContextSample(timestampMs = 2_000, observationId = second, source = "android_bluetooth", metric = "bt_nearby_count", value = 0.0, unit = "count", captureId = "event:$second:instant"),
            ContextSample(timestampMs = 1_100, isControl = true, source = "android_bluetooth", metric = "bt_nearby_count", value = 1.0, unit = "count", captureId = "control:one", phase = ContextPhase.CONTROL),
            ContextSample(timestampMs = 1_100, isControl = true, source = "android_bluetooth", metric = "bt_device_rssi_dbm", value = -50.0, unit = "dBm", metadata = "device_hash=idhash:v1:alpha;window=instant", captureId = "control:one", phase = ContextPhase.CONTROL),
            ContextSample(timestampMs = 2_100, isControl = true, source = "android_bluetooth", metric = "bt_nearby_count", value = 1.0, unit = "count", captureId = "control:two", phase = ContextPhase.CONTROL),
            ContextSample(timestampMs = 2_100, isControl = true, source = "android_bluetooth", metric = "bt_device_rssi_dbm", value = -60.0, unit = "dBm", metadata = "device_hash=idhash:v1:beta;window=instant", captureId = "control:two", phase = ContextPhase.CONTROL)
        ))

        val features = db.devicePresenceCapturesForCohort(AnalysisCohort.EGRESS)
        val alpha = features.getValue("bluetooth device presence · idhash:v1:alpha")
        assertEquals(listOf(1.0, 0.0), alpha.first.map { it.value })
        assertEquals(listOf(1.0, 0.0), alpha.second.map { it.value })
        assertEquals(2, alpha.first.size)
        assertTrue(features.containsKey("bluetooth device presence · idhash:v1:beta"))
    }

    @Test
    fun hypothesisRegistrationLocksOnFirstEligibleEvaluationAndKeepsHistoryImmutable() {
        val id = db.insertHypothesis(Hypothesis(
            createdAtMs = 1_000, eventLabel = "Egress · bailed", cohortId = AnalysisCohort.EGRESS,
            metric = "network_signal_dbm", direction = HypothesisDirection.LOWER,
            windowStartMs = 0, windowEndMs = 600_000, note = "Egress follows weaker signal"
        ))
        val original = db.hypotheses().single()
        assertTrue(db.updateHypothesisRegistration(original.copy(note = "Clarified before results")))
        val evaluation = HypothesisEvaluation(
            hypothesisId = id, evaluatedAtMs = 2_000, analysisSignature = "snapshot-one",
            outcome = HypothesisOutcome.CONFIRMED, eventCount = 12, controlCount = 12,
            adjustedP = 0.02, delta = -18.0, comparisonsTested = 24, summary = "Confirmed test result"
        )
        assertTrue(db.recordHypothesisEvaluation(evaluation))
        assertFalse(db.recordHypothesisEvaluation(evaluation.copy(evaluatedAtMs = 3_000)))
        assertFalse(db.updateHypothesisRegistration(db.hypotheses().single().copy(note = "Too late")))
        assertThrows(android.database.sqlite.SQLiteException::class.java) {
            db.writableDatabase.execSQL("UPDATE hypotheses SET note='raw rewrite' WHERE id=?", arrayOf(id))
        }

        val locked = db.hypotheses().single()
        assertEquals(2_000L, locked.lockedAtMs)
        assertEquals("Clarified before results", locked.note)
        assertEquals(HypothesisOutcome.CONFIRMED, db.latestHypothesisEvaluation(id)?.outcome)
        assertEquals(1, db.hypothesisEvaluations(id).size)
    }

    @Test
    fun anAlreadyViewedExactResultCannotBeBackdatedAsAPreregistration() {
        val registration = Hypothesis(
            createdAtMs = 5_000, eventLabel = "Egress · bailed", cohortId = AnalysisCohort.EGRESS,
            metric = "pressure_hpa", direction = HypothesisDirection.HIGHER, note = "Pressure rises before egress"
        )
        assertTrue(db.insertHypothesisIfUnviewed(registration, "pressure_hpa") != null)
        db.recordAnalysisView(AnalysisCohort.EGRESS, "pressure_hpa · 0-10m pre", 6_000, "view-one")
        assertTrue(db.hasAnalysisView(AnalysisCohort.EGRESS, "pressure_hpa · 0-10m pre"))
        assertEquals(null, db.insertHypothesisIfUnviewed(
            registration.copy(createdAtMs = 7_000, windowStartMs = 0, windowEndMs = 600_000),
            "pressure_hpa · 0-10m pre"
        ))
    }

    private fun sample(timestamp: Long, value: Double) = ContextSample(
        timestampMs = timestamp,
        source = "sensor",
        metric = "pressure_hpa",
        value = value,
        unit = "hPa"
    )

    private fun control(timestamp: Long, value: Double, capture: String) = sample(timestamp, value).copy(
        isControl = true,
        captureId = capture,
        phase = ContextPhase.CONTROL
    )
}
