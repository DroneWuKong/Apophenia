package com.dronewukong.apophenia.control

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationStore
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.mavlink.MavlinkSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ControlLinkState(
    val active: Boolean = false,
    val protocol: ControlLinkProtocol? = null,
    val endpointLabel: String = "No control-link input",
    val frameCount: Long = 0,
    val lastFrameAtMs: Long? = null,
    val lastError: String? = null
)

private data class TimedControlMetric(val metric: ControlLinkMetric, val timestampMs: Long)

object ControlLinkManager {
    private val mutableState = MutableStateFlow(ControlLinkState())
    val state: StateFlow<ControlLinkState> = mutableState.asStateFlow()
    private var crsf = CrsfLinkParser()
    private var ghst = GhstLinkParser()
    private val latest = linkedMapOf<String, TimedControlMetric>()

    @Synchronized
    fun arm(context: Context, protocol: ControlLinkProtocol, endpointLabel: String): Result<ControlLinkState> = runCatching {
        check(HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE)) { "LIVE_CRSF_GHST_CAPTURE is off" }
        check(!mutableState.value.active) { "Control-link capture is already active" }
        crsf = CrsfLinkParser(); ghst = GhstLinkParser(); latest.clear()
        ControlLinkState(active = true, protocol = protocol, endpointLabel = endpointLabel).also { mutableState.value = it }
    }.onFailure { mutableState.value = mutableState.value.copy(lastError = it.message ?: "Unable to arm control-link capture") }

    @Synchronized
    fun ingest(context: Context, bytes: ByteArray, timestampMs: Long = System.currentTimeMillis()): Int {
        val protocol = mutableState.value.protocol ?: return 0
        if (!mutableState.value.active || bytes.isEmpty()) return 0
        val decoded = when (protocol) {
            ControlLinkProtocol.CRSF -> crsf.feed(bytes)
            ControlLinkProtocol.GHST -> ghst.feed(bytes)
        }
        val db = ObservationStore.repository(context.applicationContext).db()
        decoded.forEachIndexed { index, metrics ->
            metrics.forEach { latest[it.metric] = TimedControlMetric(it, timestampMs) }
            val flightSession = MavlinkSessionManager.activeId()
            db.insertContext(metrics.map { metric ->
                ContextSample(
                    timestampMs = timestampMs,
                    source = "control_link",
                    metric = metric.metric,
                    value = metric.value,
                    unit = metric.unit,
                    metadata = "${metric.metadata};window=session_stream;raw_endpoint_not_persisted=true",
                    captureId = "control-link:${protocol.name}:$timestampMs:$index",
                    sessionId = flightSession
                )
            })
        }
        if (decoded.isNotEmpty()) mutableState.value = mutableState.value.copy(
            frameCount = mutableState.value.frameCount + decoded.size,
            lastFrameAtMs = timestampMs,
            lastError = null
        )
        return decoded.size
    }

    @Synchronized
    fun collect(context: Context, observationId: Long?, isControl: Boolean, nowMs: Long = System.currentTimeMillis()): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_CRSF_GHST_CAPTURE)) return emptyList()
        val state = mutableState.value
        if (!state.active || latest.isEmpty()) return emptyList()
        val captureId = if (isControl) "control:control-link:$nowMs" else observationId?.let { "event:$it:control-link:$nowMs" } ?: "control-link:snapshot:$nowMs"
        val sessionId = MavlinkSessionManager.activeId()
        return buildList {
            latest.values.forEach { timed -> add(
                ContextSample(
                    timestampMs = nowMs,
                    observationId = observationId,
                    isControl = isControl,
                    source = "control_link",
                    metric = timed.metric.metric,
                    value = timed.metric.value,
                    unit = timed.metric.unit,
                    metadata = "${timed.metric.metadata};telemetry_timestamp_ms=${timed.timestampMs};telemetry_age_ms=${(nowMs - timed.timestampMs).coerceAtLeast(0)};stale=${nowMs - timed.timestampMs > STALE_AFTER_MS};window=instant",
                    captureId = captureId,
                    sessionId = sessionId
                )
            ) }
            state.lastFrameAtMs?.let { last -> add(
                ContextSample(
                    timestampMs = nowMs,
                    observationId = observationId,
                    isControl = isControl,
                    source = "control_link",
                    metric = "control_link_telemetry_age_ms",
                    value = (nowMs - last).coerceAtLeast(0).toDouble(),
                    unit = "ms",
                    metadata = "protocol=${state.protocol};stale=${nowMs - last > STALE_AFTER_MS};threshold_ms=$STALE_AFTER_MS;window=instant",
                    captureId = captureId,
                    sessionId = sessionId
                )
            ) }
        }
    }

    @Synchronized
    fun stop() { crsf = CrsfLinkParser(); ghst = GhstLinkParser(); latest.clear(); mutableState.value = ControlLinkState() }
    fun fail(message: String) { mutableState.value = mutableState.value.copy(lastError = message) }

    fun simulationBytes(protocol: ControlLinkProtocol): ByteArray = when (protocol) {
        ControlLinkProtocol.CRSF -> CrsfLinkParser.encodeLinkStats(byteArrayOf(62, 67, 96, 8, 0, 2, 4, 71, 91, (-3).toByte()))
        ControlLinkProtocol.GHST -> GhstLinkParser.encodeLinkStats(byteArrayOf(64, 94, 7, 0, 100, 9, 196.toByte(), 12, 44, 4))
    }

    private const val STALE_AFTER_MS = 3_000L
}

class ControlLinkContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> =
        ControlLinkManager.collect(context, observationId, isControl)
}
