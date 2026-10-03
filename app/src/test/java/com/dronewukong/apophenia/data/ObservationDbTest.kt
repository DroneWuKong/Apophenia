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
        assertEquals(6, db.readableDatabase.version)
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

        assertEquals(6, json.getInt("schema"))
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
