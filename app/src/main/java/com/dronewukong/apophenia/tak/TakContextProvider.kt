package com.dronewukong.apophenia.tak

import android.content.Context
import android.net.wifi.WifiManager
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.mavlink.MavlinkSessionManager
import java.io.StringReader
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.time.Instant
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

object TakSettings {
    private const val PREFS = "tak_context"
    private const val OWN_UID_HASH = "own_uid_hash"
    private const val GROUP = "group"
    private const val PORT = "port"
    const val DEFAULT_OWN_UID = "apophenia-own"
    const val DEFAULT_GROUP = "239.2.3.1"
    const val DEFAULT_PORT = 6969

    fun ownUidConfigured(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(OWN_UID_HASH)
    fun ownUidHash(context: Context, hasher: DeviceIdentifierHasher): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(OWN_UID_HASH, null) ?: hasher.hash(DeviceIdentifierKind.TAK_UID, DEFAULT_OWN_UID)
    fun group(context: Context): String = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(GROUP, DEFAULT_GROUP)?.trim().orEmpty().ifBlank { DEFAULT_GROUP }
    fun port(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(PORT, DEFAULT_PORT).takeIf { it in 1..65_535 } ?: DEFAULT_PORT

    fun save(
        context: Context,
        ownUid: String,
        group: String,
        port: Int
    ) {
        val hasher = if (ownUid.isBlank()) null else DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context.applicationContext))
        save(context, ownUid, group, port, hasher)
    }

    internal fun save(
        context: Context,
        ownUid: String,
        group: String,
        port: Int,
        hasher: DeviceIdentifierHasher?
    ) {
        require(group.isNotBlank())
        require(port in 1..65_535)
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(GROUP, group.trim()).putInt(PORT, port)
        if (ownUid.isNotBlank()) {
            checkNotNull(hasher) { "A hasher is required when storing an own-asset UID" }
            edit.putString(OWN_UID_HASH, hasher.hash(DeviceIdentifierKind.TAK_UID, ownUid))
        }
        edit.apply()
    }
}

object TakSnapshotParser {
    fun parse(
        xml: String,
        ownUidHash: String,
        fullCapture: Boolean,
        observationId: Long?,
        isControl: Boolean,
        hasher: DeviceIdentifierHasher,
        receivedAtMs: Long = System.currentTimeMillis(),
        sessionId: String? = null
    ): List<ContextSample> {
        if (xml.length > 65_507 || xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return emptyList()
        val track = parseTrack(xml, receivedAtMs) ?: return emptyList()
        val uidHash = hasher.hash(DeviceIdentifierKind.TAK_UID, track.uid)
        if (!fullCapture && uidHash != ownUidHash) return emptyList()
        val scope = if (uidHash == ownUidHash) "own_asset" else "visible_on_your_connection"
        val type = token(track.type)
        val category = when {
            track.type.startsWith("a-f") -> "friendly"
            track.type.startsWith("a-h") -> "hostile"
            track.type.startsWith("a-n") -> "neutral"
            track.type.startsWith("a-u") -> "unknown"
            track.type.startsWith("b-m") -> "marker"
            else -> "other"
        }
        val captureId = if (isControl) "control:tak:$receivedAtMs" else observationId?.let { "event:$it:tak:$receivedAtMs" } ?: "tak:$receivedAtMs"
        val metadata = "scope=$scope;uid_hash=$uidHash;cot_type=$type;category=$category;full_gate=$fullCapture;callsign_persisted=false;window=instant"
        val source = if (scope == "own_asset") "tak_own" else "tak_visible"
        fun sample(metric: String, value: Double, unit: String) = ContextSample(
            timestampMs = receivedAtMs,
            observationId = observationId,
            isControl = isControl,
            source = source,
            metric = metric,
            value = value,
            unit = unit,
            metadata = metadata,
            captureId = captureId,
            sessionId = sessionId
        )
        return buildList {
            add(sample("tak_latitude_deg", track.latitude, "deg"))
            add(sample("tak_longitude_deg", track.longitude, "deg"))
            if (track.haeM.isFinite()) add(sample("tak_hae_m", track.haeM, "m"))
            if (track.circularErrorM.isFinite()) add(sample("tak_circular_error_m", track.circularErrorM, "m"))
            if (track.linearErrorM.isFinite()) add(sample("tak_linear_error_m", track.linearErrorM, "m"))
            track.courseDeg?.let { add(sample("tak_course_deg", it, "deg")) }
            track.speedMps?.let { add(sample("tak_speed_mps", it, "m/s")) }
            add(sample("tak_track_age_ms", (receivedAtMs - track.eventTimeMs).coerceAtLeast(0).toDouble(), "ms"))
            add(sample("tak_callsign_present", if (track.callsignPresent) 1.0 else 0.0, "bool"))
        }
    }

    private fun parseTrack(xml: String, receivedAtMs: Long): CotTrack? = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var uid = ""
        var type = ""
        var eventTime = receivedAtMs
        var lat = Double.NaN
        var lon = Double.NaN
        var hae = Double.NaN
        var ce = Double.NaN
        var le = Double.NaN
        var course: Double? = null
        var speed: Double? = null
        var callsignPresent = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (parser.name) {
                "event" -> {
                    uid = parser.getAttributeValue(null, "uid").orEmpty()
                    type = parser.getAttributeValue(null, "type").orEmpty()
                    eventTime = parser.getAttributeValue(null, "time")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: receivedAtMs
                }
                "point" -> {
                    lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: Double.NaN
                    lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: Double.NaN
                    hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: Double.NaN
                    ce = parser.getAttributeValue(null, "ce")?.toDoubleOrNull() ?: Double.NaN
                    le = parser.getAttributeValue(null, "le")?.toDoubleOrNull() ?: Double.NaN
                }
                "track" -> {
                    course = parser.getAttributeValue(null, "course")?.toDoubleOrNull()
                    speed = parser.getAttributeValue(null, "speed")?.toDoubleOrNull()
                }
                "contact" -> callsignPresent = !parser.getAttributeValue(null, "callsign").isNullOrBlank()
            }
            event = parser.next()
        }
        if (uid.isBlank() || type.isBlank() || !lat.isFinite() || !lon.isFinite()) null
        else CotTrack(uid, type, eventTime, lat, lon, hae, ce, le, course, speed, callsignPresent)
    }.getOrNull()

    private fun token(value: String): String = value.trim().take(80).map { if (it.isLetterOrDigit() || it in ".-_:") it else '_' }.joinToString("")
}

class TakContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        if (!HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_TAK_CAPTURE)) return emptyList()
        val full = HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_TAK_CAPTURE_FULL)
        val hasher = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            DeviceIdentifierHasher.withFixedKey(ByteArray(32) { (it * 17 + 1).toByte() })
        } else DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context.applicationContext))
        val ownUidHash = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            hasher.hash(DeviceIdentifierKind.TAK_UID, TakSettings.DEFAULT_OWN_UID)
        } else TakSettings.ownUidHash(context, hasher)
        val packets = if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            val now = System.currentTimeMillis()
            listOf(now to SIM_OWN.replace("OWN_UID", TakSettings.DEFAULT_OWN_UID), now to SIM_VISIBLE)
        } else receiveWindow(TakSettings.group(context), TakSettings.port(context))
        val sessionId = MavlinkSessionManager.activeId()
        return packets.flatMapIndexed { index, packet ->
            TakSnapshotParser.parse(packet.second, ownUidHash, full, observationId, isControl, hasher, packet.first, sessionId)
                .map { it.copy(captureId = "${it.captureId}:$index") }
        }
    }

    private fun receiveWindow(group: String, port: Int): List<Pair<Long, String>> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("apophenia-tak-window")?.apply { setReferenceCounted(false); acquire() }
        return try {
            runCatching {
                val address = InetAddress.getByName(group)
                require(address.isMulticastAddress) { "TAK group must be multicast" }
                MulticastSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress("0.0.0.0", port))
                    @Suppress("DEPRECATION")
                    socket.joinGroup(address)
                    socket.soTimeout = WINDOW_MS
                    val out = mutableListOf<Pair<Long, String>>()
                    val buffer = ByteArray(MAX_PACKET_BYTES)
                    while (out.size < MAX_PACKETS) {
                        try {
                            val packet = DatagramPacket(buffer, buffer.size)
                            socket.receive(packet)
                            out += System.currentTimeMillis() to packet.data.copyOfRange(packet.offset, packet.offset + packet.length).toString(Charsets.UTF_8)
                            socket.soTimeout = 50
                        } catch (_: SocketTimeoutException) { break }
                    }
                    @Suppress("DEPRECATION")
                    socket.leaveGroup(address)
                    out
                }
            }.getOrDefault(emptyList())
        } finally { if (lock?.isHeld == true) lock.release() }
    }

    companion object {
        private const val WINDOW_MS = 650
        private const val MAX_PACKETS = 32
        private const val MAX_PACKET_BYTES = 65_507
        private const val SIM_OWN = """<?xml version="1.0"?><event version="2.0" uid="OWN_UID" type="a-f-A-M-H-Q" time="2026-10-03T12:00:00.000Z" start="2026-10-03T12:00:00.000Z" stale="2026-10-03T12:01:00.000Z" how="m-g"><point lat="41.881832" lon="-87.623177" hae="188.4" ce="4.0" le="7.0"/><detail><contact callsign="OWN"/><track course="90.0" speed="12.5"/></detail></event>"""
        private const val SIM_VISIBLE = """<?xml version="1.0"?><event version="2.0" uid="visible-contact-2" type="a-f-G-U-C" time="2026-10-03T12:00:00.000Z" start="2026-10-03T12:00:00.000Z" stale="2026-10-03T12:01:00.000Z" how="m-g"><point lat="41.882" lon="-87.624" hae="180" ce="8" le="12"/><detail><contact callsign="VISIBLE"/><track course="270" speed="1.2"/></detail></event>"""
    }
}
