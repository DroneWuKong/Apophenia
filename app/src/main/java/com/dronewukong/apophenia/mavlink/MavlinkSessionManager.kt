package com.dronewukong.apophenia.mavlink

import android.content.Context
import com.dronewukong.apophenia.data.CaptureSession
import com.dronewukong.apophenia.data.CaptureSessionStatus
import com.dronewukong.apophenia.data.CaptureSessionType
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.data.SessionEvent
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FlightSessionState(
    val armed: Boolean = false,
    val sessionId: String? = null,
    val endpointLabel: String = "No MAVLink endpoint",
    val systemId: Int? = null,
    val startedAtMs: Long? = null,
    val lastTelemetryAtMs: Long? = null,
    val frameCount: Long = 0,
    val packetDropCount: Long = 0,
    val lastError: String? = null
)

private data class TimedMavlinkMetric(val metric: MavlinkMetric, val receivedAtMs: Long)
private data class HeartbeatState(val customMode: Long, val baseMode: Int, val armed: Boolean, val systemStatus: Int)

/** Owns the honest boundary between a transport stream and one identified airframe session. */
object MavlinkSessionManager {
    private val mutableState = MutableStateFlow(FlightSessionState())
    val state: StateFlow<FlightSessionState> = mutableState.asStateFlow()

    private var parser = MavlinkParser()
    private var endpointLabel = ""
    private var boundSystemId: Int? = null
    @Volatile private var activeSessionId: String? = null
    private val latestMetrics = linkedMapOf<String, TimedMavlinkMetric>()
    private val lastSequenceByComponent = mutableMapOf<Int, Int>()
    private var lastHeartbeat: HeartbeatState? = null
    private var lastFailsafeState: Int? = null

    @Synchronized
    fun arm(context: Context, endpoint: String): Result<FlightSessionState> = runCatching {
        check(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_MAVLINK_CAPTURE)) {
            "LIVE_MAVLINK_CAPTURE is off"
        }
        check(!mutableState.value.armed) { "A MAVLink capture session is already armed" }
        resetRuntime()
        endpointLabel = endpoint
        FlightSessionState(armed = true, endpointLabel = endpoint).also { mutableState.value = it }
    }.onFailure { error ->
        mutableState.value = mutableState.value.copy(lastError = error.message ?: "Unable to arm MAVLink capture")
    }

    /**
     * Accepts arbitrary stream chunks. A durable FLIGHT_SESSION is created only after a valid
     * HEARTBEAT identifies the primary system; subsequent traffic from other system IDs is ignored.
     */
    @Synchronized
    fun ingest(context: Context, bytes: ByteArray, receivedAtMs: Long = System.currentTimeMillis()): Int {
        if (!mutableState.value.armed || bytes.isEmpty()) return 0
        val frames = parser.feed(bytes)
        var accepted = 0
        frames.forEach { frame ->
            if (boundSystemId == null) {
                if (frame.messageId != 0 || frame.systemId == 0) return@forEach
                bindAirframe(context.applicationContext, frame.systemId, receivedAtMs)
            }
            if (frame.systemId != boundSystemId) return@forEach
            val sessionId = activeSessionId ?: return@forEach
            accepted++
            recordSequence(frame)
            val decoded = MavlinkDecoder.decode(frame)
            decoded.metrics.forEach { latestMetrics[it.metric] = TimedMavlinkMetric(it, receivedAtMs) }
            val metadata = "message_id=${frame.messageId};mavlink_v=${frame.version};window=session_stream;identity=session_hash"
            val streamSamples = decoded.metrics.map { metric ->
                ContextSample(
                    timestampMs = receivedAtMs,
                    source = "mavlink",
                    metric = metric.metric,
                    value = metric.value,
                    unit = metric.unit,
                    metadata = listOf(metric.metadata, metadata).filter(String::isNotBlank).joinToString(";"),
                    captureId = "$sessionId:stream:$receivedAtMs:${frame.sequence}:${frame.messageId}",
                    sessionId = sessionId
                )
            }
            val db = ObservationStore.repository(context.applicationContext).db()
            db.insertContext(streamSamples)
            val events = buildList {
                decoded.events.filter { it.eventType == "STATUSTEXT" }.forEach { event ->
                    add(SessionEvent(timestampMs = receivedAtMs, sessionId = sessionId, eventType = event.eventType, severity = event.severity, text = event.text, metadata = metadata))
                }
                if (frame.messageId == 0) addAll(heartbeatEvents(frame, decoded, sessionId, receivedAtMs, metadata))
            }
            db.insertSessionEvents(events)
            mutableState.value = mutableState.value.copy(
                lastTelemetryAtMs = receivedAtMs,
                frameCount = mutableState.value.frameCount + 1,
                lastError = null
            )
        }
        return accepted
    }

    @Synchronized
    fun collect(
        context: Context,
        observationId: Long?,
        isControl: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_MAVLINK_CAPTURE)) return emptyList()
        val sessionId = activeSessionId ?: return emptyList()
        val lastAt = mutableState.value.lastTelemetryAtMs ?: return emptyList()
        val captureId = if (isControl) "control:mavlink:$nowMs" else observationId?.let { "event:$it:mavlink:$nowMs" } ?: "$sessionId:snapshot:$nowMs"
        val age = (nowMs - lastAt).coerceAtLeast(0L)
        return buildList {
            latestMetrics.values.forEach { timed ->
                add(
                    ContextSample(
                        timestampMs = nowMs,
                        observationId = observationId,
                        isControl = isControl,
                        source = "mavlink",
                        metric = timed.metric.metric,
                        value = timed.metric.value,
                        unit = timed.metric.unit,
                        metadata = listOf(
                            timed.metric.metadata,
                            "telemetry_timestamp_ms=${timed.receivedAtMs}",
                            "telemetry_age_ms=${(nowMs - timed.receivedAtMs).coerceAtLeast(0L)}",
                            "stale=${nowMs - timed.receivedAtMs > STALE_AFTER_MS}",
                            "window=instant"
                        ).filter(String::isNotBlank).joinToString(";"),
                        captureId = captureId,
                        sessionId = sessionId
                    )
                )
            }
            add(
                ContextSample(
                    timestampMs = nowMs,
                    observationId = observationId,
                    isControl = isControl,
                    source = "mavlink",
                    metric = "mavlink_telemetry_age_ms",
                    value = age.toDouble(),
                    unit = "ms",
                    metadata = "stale=${age > STALE_AFTER_MS};threshold_ms=$STALE_AFTER_MS;window=instant",
                    captureId = captureId,
                    sessionId = sessionId
                )
            )
            add(
                ContextSample(
                    timestampMs = nowMs,
                    observationId = observationId,
                    isControl = isControl,
                    source = "mavlink",
                    metric = "mavlink_sequence_drops",
                    value = mutableState.value.packetDropCount.toDouble(),
                    unit = "count",
                    metadata = "scope=session_observed_sequence_gaps;not_transport_exactly_once;window=instant",
                    captureId = captureId,
                    sessionId = sessionId
                )
            )
        }
    }

    @Synchronized
    fun stop(context: Context, interrupted: Boolean = false, reason: String = "operator_stop") {
        val sessionId = activeSessionId
        if (sessionId != null) {
            ObservationStore.repository(context.applicationContext).db().endSession(
                sessionId,
                System.currentTimeMillis(),
                if (interrupted) CaptureSessionStatus.INTERRUPTED else CaptureSessionStatus.COMPLETED,
                "end_reason=$reason"
            )
        }
        resetRuntime()
        mutableState.value = FlightSessionState()
    }

    @Synchronized
    fun fail(message: String) {
        mutableState.value = mutableState.value.copy(lastError = message)
    }

    fun activeId(): String? = activeSessionId
    fun isArmed(): Boolean = mutableState.value.armed

    fun reconcileProcessStart(context: Context) {
        ObservationStore.repository(context.applicationContext).db().interruptActiveSessions(
            CaptureSessionType.FLIGHT_SESSION,
            System.currentTimeMillis()
        )
    }

    fun simulationStream(systemId: Int = 42): ByteArray {
        val heartbeat = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4)
            put(2)
            put(3)
            put(0x80.toByte())
            put(4)
            put(3)
        }.array()
        val gps = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(0)
            putInt((41.881832 * 1e7).toInt())
            putInt((-87.623177 * 1e7).toInt())
            putInt(188_400)
            putShort(145)
            putShort(0xFFFF.toShort())
            putShort(0)
            putShort(0)
            put(3)
            put(14)
        }.array()
        val radio = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(2)
            putShort(1)
            put(190.toByte())
            put(175.toByte())
            put(82)
            put(92)
            put(94)
        }.array()
        val status = ByteArray(51).apply {
            this[0] = 4
            "Simulation link established".toByteArray().copyInto(this, 1)
        }
        return MavlinkFrameEncoder.v2(0, heartbeat, 10, systemId) +
            MavlinkFrameEncoder.v2(24, gps, 11, systemId) +
            MavlinkFrameEncoder.v2(109, radio, 12, systemId) +
            MavlinkFrameEncoder.v2(253, status, 13, systemId)
    }

    private fun bindAirframe(context: Context, systemId: Int, receivedAtMs: Long) {
        val hasher = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it * 11 + 5).toByte() })
        } else {
            DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context))
        }
        val sessionId = "flight:${UUID.randomUUID()}"
        ObservationStore.repository(context).db().insertSession(
            CaptureSession(
                id = sessionId,
                type = CaptureSessionType.FLIGHT_SESSION,
                startedAtMs = receivedAtMs,
                identityHash = hasher.hash(DeviceIdentifierKind.ADAPTER_SYSID, "mavlink:$systemId"),
                metadata = "boundary=one_connection_one_primary_airframe;transport=${endpointLabel.substringBefore(':')};raw_sysid_not_persisted=true"
            )
        )
        boundSystemId = systemId
        activeSessionId = sessionId
        mutableState.value = mutableState.value.copy(
            sessionId = sessionId,
            systemId = systemId,
            startedAtMs = receivedAtMs
        )
    }

    private fun recordSequence(frame: MavlinkFrame) {
        val key = (frame.systemId shl 8) or frame.componentId
        val prior = lastSequenceByComponent.put(key, frame.sequence) ?: return
        val delta = (frame.sequence - prior + 256) % 256
        if (delta in 2..127) {
            val drops = delta - 1L
            mutableState.value = mutableState.value.copy(packetDropCount = mutableState.value.packetDropCount + drops)
        }
    }

    private fun heartbeatEvents(
        frame: MavlinkFrame,
        decoded: DecodedMavlink,
        sessionId: String,
        timestampMs: Long,
        metadata: String
    ): List<SessionEvent> {
        val values = decoded.metrics.associate { it.metric to it.value }
        val current = HeartbeatState(
            customMode = values.getValue("mavlink_custom_mode").toLong(),
            baseMode = values.getValue("mavlink_base_mode").toInt(),
            armed = values.getValue("mavlink_armed") == 1.0,
            systemStatus = values.getValue("mavlink_system_status").toInt()
        )
        val prior = lastHeartbeat
        lastHeartbeat = current
        return buildList {
            if (prior != null && (
                    prior.customMode != current.customMode ||
                        prior.baseMode != current.baseMode ||
                        prior.armed != current.armed
                    )
            ) {
                add(
                    SessionEvent(
                        timestampMs = timestampMs,
                        sessionId = sessionId,
                        eventType = "MODE_TRANSITION",
                        text = "custom_mode=${prior.customMode}->${current.customMode};base_mode=${prior.baseMode}->${current.baseMode};armed=${prior.armed}->${current.armed}",
                        metadata = metadata
                    )
                )
            }
            if (current.systemStatus >= 5 && lastFailsafeState != current.systemStatus) {
                add(
                    SessionEvent(
                        timestampMs = timestampMs,
                        sessionId = sessionId,
                        eventType = "FAILSAFE_STATE",
                        severity = current.systemStatus,
                        text = "MAV_STATE=${current.systemStatus}",
                        metadata = metadata
                    )
                )
            }
            lastFailsafeState = current.systemStatus.takeIf { it >= 5 }
        }
    }

    private fun resetRuntime() {
        parser = MavlinkParser()
        endpointLabel = ""
        boundSystemId = null
        activeSessionId = null
        latestMetrics.clear()
        lastSequenceByComponent.clear()
        lastHeartbeat = null
        lastFailsafeState = null
    }

    const val STALE_AFTER_MS = 3_000L
}

class MavlinkContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> =
        MavlinkSessionManager.collect(context, observationId, isControl)
}
