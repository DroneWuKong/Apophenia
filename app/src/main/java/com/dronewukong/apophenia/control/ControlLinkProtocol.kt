package com.dronewukong.apophenia.control

import kotlin.math.max

enum class ControlLinkProtocol(val displayName: String, val defaultBaud: Int) {
    CRSF("CRSF / ELRS", 115_200),
    GHST("GHST / IRONghost", 115_200)
}

data class ControlLinkMetric(val metric: String, val value: Double, val unit: String, val metadata: String = "")

private data class RawLinkFrame(val type: Int, val payload: ByteArray)

/** Streaming CRSF link-statistics decoder adapted from the proven Command serial path. */
class CrsfLinkParser {
    private val pending = ArrayList<Byte>()

    fun feed(bytes: ByteArray): List<List<ControlLinkMetric>> = frames(bytes, SYNC, 64).mapNotNull { frame ->
        if (frame.type != 0x14 || frame.payload.size < 10) return@mapNotNull null
        val uplinkRssi1 = -frame.payload.u8(0)
        val uplinkRssi2 = -frame.payload.u8(1)
        val uplinkLq = frame.payload.u8(2).coerceIn(0, 100)
        val uplinkSnr = frame.payload[3].toInt()
        val downlinkRssi = -frame.payload.u8(7)
        val downlinkLq = frame.payload.u8(8).coerceIn(0, 100)
        val downlinkSnr = frame.payload[9].toInt()
        listOf(
            ControlLinkMetric("control_uplink_rssi_dbm", max(uplinkRssi1, uplinkRssi2).toDouble(), "dBm", "protocol=CRSF;best_of_two_antennas=true"),
            ControlLinkMetric("control_uplink_rssi_antenna1_dbm", uplinkRssi1.toDouble(), "dBm", "protocol=CRSF"),
            ControlLinkMetric("control_uplink_rssi_antenna2_dbm", uplinkRssi2.toDouble(), "dBm", "protocol=CRSF"),
            ControlLinkMetric("control_uplink_lq_pct", uplinkLq.toDouble(), "percent", "protocol=CRSF"),
            ControlLinkMetric("control_uplink_snr_db", uplinkSnr.toDouble(), "dB", "protocol=CRSF"),
            ControlLinkMetric("control_uplink_packet_loss_pct", (100 - uplinkLq).toDouble(), "percent", "protocol=CRSF;derived_from_lq=true"),
            ControlLinkMetric("control_active_antenna", frame.payload.u8(4).toDouble(), "enum", "protocol=CRSF"),
            ControlLinkMetric("control_rf_mode", frame.payload.u8(5).toDouble(), "enum", "protocol=CRSF"),
            ControlLinkMetric("control_tx_power_enum", frame.payload.u8(6).toDouble(), "enum", "protocol=CRSF;not_relabelled_as_mw"),
            ControlLinkMetric("control_downlink_rssi_dbm", downlinkRssi.toDouble(), "dBm", "protocol=CRSF"),
            ControlLinkMetric("control_downlink_lq_pct", downlinkLq.toDouble(), "percent", "protocol=CRSF"),
            ControlLinkMetric("control_downlink_snr_db", downlinkSnr.toDouble(), "dB", "protocol=CRSF"),
            ControlLinkMetric("control_downlink_packet_loss_pct", (100 - downlinkLq).toDouble(), "percent", "protocol=CRSF;derived_from_lq=true")
        )
    }

    private fun frames(bytes: ByteArray, sync: Set<Int>, maxLength: Int): List<RawLinkFrame> {
        bytes.forEach(pending::add)
        val out = mutableListOf<RawLinkFrame>()
        while (true) {
            val start = pending.indexOfFirst { it.u8() in sync }
            if (start < 0) { pending.clear(); break }
            repeat(start) { pending.removeAt(0) }
            if (pending.size < 2) break
            val length = pending[1].u8()
            val total = length + 2
            if (length < 2 || total > maxLength) { pending.removeAt(0); continue }
            if (pending.size < total) break
            val candidate = ByteArray(total) { pending[it] }
            val expected = candidate.last().u8()
            val actual = crcDvbS2(candidate, 2, total - 1)
            if (actual != expected) { pending.removeAt(0); continue }
            out += RawLinkFrame(candidate[2].u8(), candidate.copyOfRange(3, total - 1))
            repeat(total) { pending.removeAt(0) }
        }
        return out
    }

    companion object {
        private val SYNC = setOf(0xC8, 0xEE, 0xEA, 0xEC, 0x00)

        fun encodeLinkStats(payload: ByteArray, sync: Int = 0xC8): ByteArray {
            require(payload.size == 10)
            val frame = ByteArray(payload.size + 4)
            frame[0] = sync.toByte()
            frame[1] = (payload.size + 2).toByte()
            frame[2] = 0x14
            payload.copyInto(frame, 3)
            frame[frame.lastIndex] = crcDvbS2(frame, 2, frame.lastIndex).toByte()
            return frame
        }
    }
}

/** Streaming GHST link-statistics decoder adapted from the proven IRONghost path. */
class GhstLinkParser {
    private val pending = ArrayList<Byte>()

    fun feed(bytes: ByteArray): List<List<ControlLinkMetric>> {
        bytes.forEach(pending::add)
        val out = mutableListOf<List<ControlLinkMetric>>()
        while (true) {
            val start = pending.indexOfFirst { it.u8() in ADDRESSES }
            if (start < 0) { pending.clear(); break }
            repeat(start) { pending.removeAt(0) }
            if (pending.size < 2) break
            val length = pending[1].u8()
            val total = length + 2
            if (length < 2 || total > 80) { pending.removeAt(0); continue }
            if (pending.size < total) break
            val candidate = ByteArray(total) { pending[it] }
            if (crcDvbS2(candidate, 2, total - 1) != candidate.last().u8()) { pending.removeAt(0); continue }
            val type = candidate[2].u8()
            val payload = candidate.copyOfRange(3, total - 1)
            if (type == 0x50 || type == 0x21) decode(payload)?.let(out::add)
            repeat(total) { pending.removeAt(0) }
        }
        return out
    }

    private fun decode(payload: ByteArray): List<ControlLinkMetric>? {
        if (payload.size < 10) return null
        val rssi = -payload.u8(0)
        val lq = payload.u8(1).coerceIn(0, 100)
        val snr = payload[2].toInt()
        return listOf(
            ControlLinkMetric("control_uplink_rssi_dbm", rssi.toDouble(), "dBm", "protocol=GHST;receiver_reported=true"),
            ControlLinkMetric("control_uplink_lq_pct", lq.toDouble(), "percent", "protocol=GHST;receiver_reported=true"),
            ControlLinkMetric("control_uplink_snr_db", snr.toDouble(), "dB", "protocol=GHST;receiver_reported=true"),
            ControlLinkMetric("control_uplink_packet_loss_pct", (100 - lq).toDouble(), "percent", "protocol=GHST;derived_from_lq=true"),
            ControlLinkMetric("control_tx_power_mw", payload.u16be(3).toDouble(), "mW", "protocol=GHST"),
            ControlLinkMetric("control_frame_interval_us", payload.u16be(5).toDouble(), "us", "protocol=GHST"),
            ControlLinkMetric("control_total_latency_us", payload.u16be(7).toDouble(), "us", "protocol=GHST"),
            ControlLinkMetric("control_rf_mode", payload.u8(9).toDouble(), "enum", "protocol=GHST"),
            ControlLinkMetric("control_downlink_available", 0.0, "bool", "protocol=GHST;reason=not_exposed_by_link_stat_frame")
        )
    }

    companion object {
        private val ADDRESSES = setOf(0x80, 0x81, 0x88, 0x82, 0x89)

        fun encodeLinkStats(payload: ByteArray, address: Int = 0x80, type: Int = 0x50): ByteArray {
            require(payload.size == 10)
            val frame = ByteArray(payload.size + 4)
            frame[0] = address.toByte()
            frame[1] = (payload.size + 2).toByte()
            frame[2] = type.toByte()
            payload.copyInto(frame, 3)
            frame[frame.lastIndex] = crcDvbS2(frame, 2, frame.lastIndex).toByte()
            return frame
        }
    }
}

private fun crcDvbS2(bytes: ByteArray, start: Int, endExclusive: Int): Int {
    var crc = 0
    for (index in start until endExclusive) {
        crc = crc xor bytes[index].u8()
        repeat(8) { crc = (if (crc and 0x80 != 0) (crc shl 1) xor 0xD5 else crc shl 1) and 0xFF }
    }
    return crc
}

private fun Byte.u8(): Int = toInt() and 0xFF
private fun ByteArray.u8(index: Int): Int = this[index].u8()
private fun ByteArray.u16be(index: Int): Int = (u8(index) shl 8) or u8(index + 1)
