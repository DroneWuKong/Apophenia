package com.dronewukong.apophenia.fieldkit

import android.content.Context
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.mavlink.MavlinkSessionManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.json.JSONObject

object FieldKitSettings {
    private const val PREFS = "field_kit_context"
    private const val PORT = "udp_port"
    const val DEFAULT_PORT = 47_500

    fun port(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(PORT, DEFAULT_PORT).takeIf { it in 1..65_535 } ?: DEFAULT_PORT

    fun setPort(context: Context, port: Int) {
        require(port in 1..65_535)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(PORT, port).apply()
    }
}

object FieldKitSnapshotParser {
    fun parse(
        json: String,
        observationId: Long?,
        isControl: Boolean,
        hasher: DeviceIdentifierHasher,
        receivedAtMs: Long = System.currentTimeMillis(),
        sessionId: String? = null
    ): List<ContextSample> {
        val root = JSONObject(json)
        val rawDeviceId = root.optString("device_id").trim()
        if (rawDeviceId.isEmpty()) return emptyList()
        val deviceHash = hasher.hash(DeviceIdentifierKind.ADAPTER_SYSID, "field-kit:$rawDeviceId")
        val capturedAt = root.optLong("captured_at_ms", receivedAtMs).takeIf { it > 0 } ?: receivedAtMs
        val captureId = if (isControl) "control:field-kit:$receivedAtMs" else observationId?.let { "event:$it:field-kit:$receivedAtMs" } ?: "field-kit:$receivedAtMs"
        val baseMetadata = "device_hash=$deviceHash;visible_from=owned_field_kit;received_at_ms=$receivedAtMs;window=instant"
        val out = mutableListOf<ContextSample>()
        val bands = root.optJSONArray("bands")
        var crossings = 0
        for (index in 0 until (bands?.length() ?: 0)) {
            val band = bands?.optJSONObject(index) ?: continue
            val name = token(band.optString("name", "unknown"))
            val rssi = band.optDouble("rssi_dbm", Double.NaN)
            val threshold = band.optDouble("threshold_dbm", Double.NaN)
            val crossed = band.optBoolean("crossed", rssi.isFinite() && threshold.isFinite() && rssi >= threshold)
            if (crossed) crossings++
            if (rssi.isFinite()) out += sample(capturedAt, observationId, isControl, "field_kit_band_rssi_dbm", rssi, "dBm", "$baseMetadata;band=$name", captureId, sessionId)
            if (threshold.isFinite()) out += sample(capturedAt, observationId, isControl, "field_kit_threshold_dbm", threshold, "dBm", "$baseMetadata;band=$name", captureId, sessionId)
            out += sample(capturedAt, observationId, isControl, "field_kit_threshold_crossed", if (crossed) 1.0 else 0.0, "bool", "$baseMetadata;band=$name", captureId, sessionId)
        }
        val triggers = root.optJSONArray("triggers")
        for (index in 0 until (triggers?.length() ?: 0)) {
            val trigger = triggers?.optJSONObject(index) ?: continue
            val type = token(trigger.optString("type", "trigger"))
            val band = token(trigger.optString("band", "unknown"))
            out += sample(capturedAt, observationId, isControl, "field_kit_trigger", 1.0, "event", "$baseMetadata;type=$type;band=$band", captureId, sessionId)
        }
        out += sample(capturedAt, observationId, isControl, "field_kit_threshold_crossing_count", crossings.toDouble(), "count", baseMetadata, captureId, sessionId)
        out += sample(capturedAt, observationId, isControl, "field_kit_trigger_count", (triggers?.length() ?: 0).toDouble(), "count", baseMetadata, captureId, sessionId)
        return out
    }

    private fun sample(
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean,
        metric: String,
        value: Double,
        unit: String,
        metadata: String,
        captureId: String,
        sessionId: String?
    ) = ContextSample(timestampMs = timestampMs, observationId = observationId, isControl = isControl, source = "field_kit", metric = metric, value = value, unit = unit, metadata = metadata, captureId = captureId, sessionId = sessionId)

    private fun token(value: String): String = value.trim().take(80).map { if (it.isLetterOrDigit() || it in "._-:/") it else '_' }.joinToString("")
}

class FieldKitContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE)) return emptyList()
        val hasher = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it * 13 + 9).toByte() })
        } else DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context.applicationContext))
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return FieldKitSnapshotParser.parse(SIMULATION_JSON, observationId, isControl, hasher, sessionId = MavlinkSessionManager.activeId())
                .map { it.copy(source = "simulation/field_kit") }
        }
        val received = receiveWindow(FieldKitSettings.port(context))
        return received.flatMapIndexed { index, packet ->
            runCatching {
                FieldKitSnapshotParser.parse(packet.second, observationId, isControl, hasher, packet.first, MavlinkSessionManager.activeId())
                    .map { it.copy(captureId = "${it.captureId}:$index") }
            }.getOrDefault(emptyList())
        }
    }

    private fun receiveWindow(port: Int): List<Pair<Long, String>> = runCatching {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port))
            socket.soTimeout = WINDOW_MS
            val out = mutableListOf<Pair<Long, String>>()
            val buffer = ByteArray(8_192)
            while (out.size < MAX_PACKETS) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    out += System.currentTimeMillis() to packet.data.copyOfRange(packet.offset, packet.offset + packet.length).toString(Charsets.UTF_8)
                    socket.soTimeout = 50
                } catch (_: SocketTimeoutException) { break }
            }
            out
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val WINDOW_MS = 650
        private const val MAX_PACKETS = 8
        private const val SIMULATION_JSON = """{"device_id":"FIELD-KIT-SIM-01","captured_at_ms":1760000000000,"bands":[{"name":"915MHz","rssi_dbm":-41.5,"threshold_dbm":-55.0,"crossed":true},{"name":"2.4GHz","rssi_dbm":-78.0,"threshold_dbm":-60.0,"crossed":false}],"triggers":[{"type":"rssi_spike","band":"915MHz"}]}"""
    }
}
