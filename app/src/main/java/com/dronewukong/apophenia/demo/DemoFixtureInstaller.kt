package com.dronewukong.apophenia.demo

import com.dronewukong.apophenia.data.AnalysisCohort
import com.dronewukong.apophenia.data.CaptureSession
import com.dronewukong.apophenia.data.CaptureSessionStatus
import com.dronewukong.apophenia.data.CaptureSessionType
import com.dronewukong.apophenia.data.ContextPhase
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.Hypothesis
import com.dronewukong.apophenia.data.HypothesisDirection
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaStatus
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.SessionEvent
import com.dronewukong.apophenia.data.VibeGrade
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class DemoFixtureSummary(
    val eventCount: Int,
    val controlCaptureCount: Int,
    val registeredHypothesisCount: Int,
    val flightSessionCount: Int,
    val purgeEntryCount: Int
)

/** Deterministic, synthetic 60-day corpus. It is installed only in apophenia-demo.db. */
object DemoFixtureInstaller {
    const val FIXTURE_VERSION = "total-circumstances-v1"
    const val DEMO_DEVICE_HASH = "demo_ble_7f83b1657ff1"
    private const val DAY_MS = 86_400_000L

    fun ensureInstalled(db: ObservationDb, anchorMs: Long = System.currentTimeMillis()): DemoFixtureSummary {
        if (db.observations(1).isEmpty()) populate(db, anchorMs)
        return summary(db)
    }

    fun reset(db: ObservationDb, anchorMs: Long = System.currentTimeMillis()): DemoFixtureSummary {
        db.deleteAllData()
        populate(db, anchorMs)
        return summary(db)
    }

    fun summary(db: ObservationDb): DemoFixtureSummary {
        val controls = db.allContext(100_000).filter { it.isControl }.map { it.captureId }.filter(String::isNotBlank).distinct().size
        return DemoFixtureSummary(
            eventCount = db.observations(10_000).size,
            controlCaptureCount = controls,
            registeredHypothesisCount = db.hypotheses(10_000).size,
            flightSessionCount = db.sessions().count { it.type == CaptureSessionType.FLIGHT_SESSION },
            purgeEntryCount = db.purgeLedger().size
        )
    }

    private fun populate(db: ObservationDb, anchorMs: Long) {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(anchorMs).atZone(zone)
            .truncatedTo(ChronoUnit.DAYS).withHour(14).minusDays(59).toInstant().toEpochMilli()
        val eventTimes = MutableList(45) { index -> start + ((index * 59.0 / 44.0).roundToInt() * DAY_MS) }
        val flightAt = eventTimes[37]
        eventTimes[27] = flightAt - 90_000L
        val ids = mutableMapOf<Int, Long>()

        repeat(45) { index ->
            val definition = definition(index)
            val observation = Observation(
                timestampMs = eventTimes[index],
                kind = definition.kind,
                label = definition.label,
                note = definition.note,
                severity = definition.severity,
                confidence = 4,
                origin = ObservationOrigin.SIMULATION,
                externalEventId = "demo:$FIXTURE_VERSION:event:$index",
                vibeRating = definition.vibeRating,
                egress = definition.egress
            )
            val id = db.insertObservation(observation)
            ids[index] = id
            db.insertContext(eventContext(index, id, eventTimes[index]))
        }

        val controls = mutableListOf<ContextSample>()
        repeat(120) { index ->
            val timestamp = if (index < eventTimes.size) eventTimes[index] + 30 * 60_000L
            else start + ((index * 37L) % 60L) * DAY_MS + 30 * 60_000L
            controls += controlContext(index, timestamp)
        }
        db.insertContext(controls)

        db.insertHypothesis(
            Hypothesis(
                createdAtMs = eventTimes[16] - DAY_MS,
                eventLabel = "Bad vibes · stayed",
                metric = "health_sleep_hours_24h",
                direction = HypothesisDirection.LOWER,
                note = "demo_fixture=true; Registered before results: bad vibes follow poor sleep.",
                source = ObservationOrigin.SIMULATION,
                cohortId = AnalysisCohort.BAD_VIBE_STAYED
            )
        )

        val flightId = "demo-flight-session-1"
        db.insertSession(
            CaptureSession(
                id = flightId,
                type = CaptureSessionType.FLIGHT_SESSION,
                startedAtMs = flightAt - 12 * 60_000L,
                endedAtMs = flightAt + 8 * 60_000L,
                identityHash = "demo_sysid_2c26b46b68ff",
                status = CaptureSessionStatus.COMPLETED,
                metadata = "demo_fixture=true;transport=simulation;airframe=own"
            )
        )
        db.insertSessionEvents(
            listOf(
                SessionEvent(timestampMs = flightAt - 12 * 60_000L, sessionId = flightId, eventType = "HEARTBEAT", text = "Own airframe connected", metadata = "demo_fixture=true"),
                SessionEvent(timestampMs = flightAt - 20_000L, sessionId = flightId, eventType = "STATUSTEXT", severity = 4, text = "GPS quality marginal", metadata = "demo_fixture=true;verbatim=true"),
                SessionEvent(timestampMs = flightAt + 8 * 60_000L, sessionId = flightId, eventType = "SESSION_END", text = "Simulation session complete", metadata = "demo_fixture=true")
            )
        )

        val avObservationId = ids.getValue(38)
        val media = MediaAsset(
            id = "demo-audio-transient",
            observationId = avObservationId,
            mediaType = MediaType.AUDIO,
            streamId = "microphone",
            createdAtMs = eventTimes[38],
            retentionUntilMs = eventTimes[38] + 14 * DAY_MS,
            status = MediaStatus.ACTIVE,
            ciphertextRelativePath = "demo/purged/audio.bin",
            manifestRelativePath = "demo/purged/audio.json",
            keyAlias = "demo_purged_key",
            ciphertextSha256 = "8f14e45fceea167a5a36dedd4bea2543d4d86f3a5d5f7f7d7a7a7a7a7a7a7a7a",
            sizeBytes = 384_000L
        )
        db.registerMediaAsset(media)
        db.markMediaPurged(media.id, eventTimes[38] + 14 * DAY_MS, "demo_retention_expired", media.sizeBytes)

        val rolling = (0 until 60).map { index ->
            ContextSample(
                timestampMs = anchorMs - (59 - index) * 30_000L,
                source = "demo/rolling",
                metric = "demo_ambient_loudness_dbfs",
                value = -42.0 + (index % 7),
                unit = "dBFS",
                metadata = "demo_fixture=true"
            )
        }
        db.insertRolling(rolling, retentionMs = 90 * 60_000L)
    }

    private data class EventDefinition(
        val kind: ObservationKind,
        val label: String,
        val note: String,
        val severity: Int? = null,
        val vibeRating: Int? = null,
        val egress: Boolean = false
    )

    private fun definition(index: Int): EventDefinition = when (index) {
        in 0..15 -> EventDefinition(ObservationKind.WEIRD, "That was weird", "Synthetic BLE-presence story · demo data", severity = 3)
        in 16..27 -> {
            val egress = index in 16..17
            val rating = if (egress) 5 else 3 + (index % 3)
            val grade = VibeGrade.fromRating(rating)
            EventDefinition(
                ObservationKind.VIBE,
                if (egress) VibeGrade.EGRESS_LABEL else grade.renderedLabel,
                if (index == 27) "Bad vibe 90 seconds before the synthetic flight anomaly · demo data" else "Synthetic vibe fixture · demo data",
                vibeRating = rating,
                egress = egress
            )
        }
        in 28..36 -> EventDefinition(ObservationKind.COINCIDENCE, "Weather front weird", "Synthetic 2× weather-front story; n=9 · demo data", severity = 2)
        37 -> EventDefinition(ObservationKind.WEIRD, "Flight anomaly", "Synthetic own-airframe session · demo data", severity = 4)
        38 -> EventDefinition(ObservationKind.OBSERVATION, "AV transient", "Synthetic broadband spike; raw audio retained then purged · demo data", severity = 3)
        else -> EventDefinition(ObservationKind.OBSERVATION, "Ordinary check-in", "Synthetic neutral event · demo data", severity = 1)
    }

    private fun eventContext(index: Int, observationId: Long, timestampMs: Long): List<ContextSample> {
        val captureId = "demo:event:$observationId:instant"
        val rows = mutableListOf<ContextSample>()
        fun add(metric: String, value: Double, unit: String, source: String = "demo/context", metadata: String = "") {
            rows += sample(timestampMs, observationId, false, source, metric, value, unit, captureId, ContextPhase.INSTANT, metadata)
        }
        add("ambient_temperature_c", 21.0 + (index % 5) * 0.2, "C")
        add("notification_count_visible", (index % 4).toDouble(), "count")
        add("battery_level_pct", (55 + index % 30).toDouble(), "percent")
        repeat(10) { decoy -> add("demo_decoy_${(decoy + 1).toString().padStart(2, '0')}", ((index + decoy) % 7).toDouble(), "score") }
        when (index) {
            in 0..15 -> {
                add("bt_nearby_count", (7 + index % 5).toDouble(), "count", "simulation/bluetooth")
                add("bt_rssi_max", (-42 - index % 8).toDouble(), "dBm", "simulation/bluetooth")
                if (index < 14) add("bt_device_rssi_dbm", (-48 - index % 6).toDouble(), "dBm", "simulation/bluetooth", "device_hash=$DEMO_DEVICE_HASH;name_category=wearable")
            }
            in 16..27 -> {
                add("health_sleep_hours_24h", 8.1 + (index % 3) * 0.1, "h", "simulation/health_connect")
                val egress = index in 16..17
                add("bt_nearby_count", if (egress) 15.0 else 4.0 + index % 2, "count", "simulation/bluetooth")
                add("network_signal_dbm", if (egress) -114.0 else -84.0 - index % 4, "dBm", "simulation/network")
            }
            in 28..36 -> {
                add("weather_front_strength", 2.0, "relative", "simulation/open_meteo")
                add("ground_pressure_trend_hpa_per_hour", -1.8, "hPa/hour", "simulation/ground")
            }
            37 -> {
                add("mavlink_hdop", 2.9, "ratio", "mavlink", "session=demo-flight-session-1")
                add("mavlink_satellites_visible", 8.0, "count", "mavlink", "session=demo-flight-session-1")
                add("control_link_margin_db", 3.0, "dB", "control_link", "protocol=CRSF;session=demo-flight-session-1")
                add("field_kit_threshold_crossed", 1.0, "bool", "field_kit", "band=915mhz;session=demo-flight-session-1")
                add("health_stress_score", 82.0, "score", "simulation/health_connect")
            }
            38 -> {
                rows += sample(timestampMs - 1_000L, observationId, false, "audio_derived", "audio_broadband_energy_ratio", 0.91, "ratio", "demo:event:$observationId:pre", ContextPhase.PRE, "demo_fixture=true;derived_from_encrypted_audio=true")
                rows += sample(timestampMs - 500L, observationId, false, "audio_derived", "audio_loudness_dbfs", -8.0, "dBFS", "demo:event:$observationId:pre", ContextPhase.PRE, "demo_fixture=true;transient=true")
                rows += sample(timestampMs + 2_000L, observationId, false, "audio_derived", "audio_broadband_energy_ratio", 0.18, "ratio", "demo:event:$observationId:post", ContextPhase.POST, "demo_fixture=true;derived_from_encrypted_audio=true")
            }
        }
        return rows
    }

    private fun controlContext(index: Int, timestampMs: Long): List<ContextSample> {
        val captureId = "demo:control:$index"
        val rows = mutableListOf<ContextSample>()
        fun add(metric: String, value: Double, unit: String, source: String = "demo/context", metadata: String = "") {
            rows += sample(timestampMs, null, true, source, metric, value, unit, captureId, ContextPhase.CONTROL, metadata)
        }
        add("ambient_temperature_c", 21.4 + (index % 4) * 0.1, "C")
        add("notification_count_visible", (index % 4).toDouble(), "count")
        add("battery_level_pct", (58 + index % 25).toDouble(), "percent")
        add("health_sleep_hours_24h", 6.0 + (index % 3) * 0.1, "h", "simulation/health_connect")
        add("weather_front_strength", 1.0, "relative", "simulation/open_meteo")
        add("ground_pressure_trend_hpa_per_hour", -0.9, "hPa/hour", "simulation/ground")
        add("network_signal_dbm", -88.0 - index % 5, "dBm", "simulation/network")
        repeat(10) { decoy -> add("demo_decoy_${(decoy + 1).toString().padStart(2, '0')}", ((index + decoy + 2) % 7).toDouble(), "score") }
        if (index < 40) {
            add("bt_nearby_count", (3 + index % 5).toDouble(), "count", "simulation/bluetooth")
            add("bt_rssi_max", (-58 - index % 10).toDouble(), "dBm", "simulation/bluetooth")
            if (index < 3) add("bt_device_rssi_dbm", -65.0 - index, "dBm", "simulation/bluetooth", "device_hash=$DEMO_DEVICE_HASH;name_category=wearable")
        }
        return rows
    }

    private fun sample(
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean,
        source: String,
        metric: String,
        value: Double,
        unit: String,
        captureId: String,
        phase: ContextPhase,
        metadata: String
    ) = ContextSample(
        timestampMs = timestampMs,
        observationId = observationId,
        isControl = isControl,
        source = source,
        metric = metric,
        value = value,
        unit = unit,
        metadata = listOf("demo_fixture=true", "fixture_version=$FIXTURE_VERSION", metadata).filter(String::isNotBlank).joinToString(";"),
        captureId = captureId,
        phase = phase,
        sessionId = if (metadata.contains("session=demo-flight-session-1")) "demo-flight-session-1" else null
    )
}
