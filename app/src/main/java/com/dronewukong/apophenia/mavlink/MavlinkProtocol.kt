package com.dronewukong.apophenia.mavlink

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

data class MavlinkFrame(
    val version: Int,
    val sequence: Int,
    val systemId: Int,
    val componentId: Int,
    val messageId: Int,
    val payload: ByteArray,
    val signed: Boolean
)

class MavlinkParser {
    private val pending = ArrayList<Byte>()

    fun feed(bytes: ByteArray): List<MavlinkFrame> {
        bytes.forEach(pending::add)
        val frames = mutableListOf<MavlinkFrame>()
        while (true) {
            val start = pending.indexOfFirst { it.u8() == MAVLINK_V1_MAGIC || it.u8() == MAVLINK_V2_MAGIC }
            if (start < 0) {
                pending.clear()
                break
            }
            repeat(start) { pending.removeAt(0) }
            val version = if (pending[0].u8() == MAVLINK_V2_MAGIC) 2 else 1
            val headerLength = if (version == 2) 10 else 6
            if (pending.size < headerLength) break
            val payloadLength = pending[1].u8()
            val signed = version == 2 && (pending[2].u8() and 0x01) != 0
            val totalLength = headerLength + payloadLength + 2 + if (signed) 13 else 0
            if (pending.size < totalLength) break
            val candidate = ByteArray(totalLength) { pending[it] }
            val messageId = if (version == 2) {
                candidate[7].u8() or (candidate[8].u8() shl 8) or (candidate[9].u8() shl 16)
            } else {
                candidate[5].u8()
            }
            val extra = CRC_EXTRAS[messageId]
            if (extra == null || !validChecksum(candidate, version, payloadLength, extra)) {
                pending.removeAt(0)
                continue
            }
            val payloadOffset = headerLength
            frames += MavlinkFrame(
                version = version,
                sequence = candidate[if (version == 2) 4 else 2].u8(),
                systemId = candidate[if (version == 2) 5 else 3].u8(),
                componentId = candidate[if (version == 2) 6 else 4].u8(),
                messageId = messageId,
                payload = candidate.copyOfRange(payloadOffset, payloadOffset + payloadLength),
                signed = signed
            )
            repeat(totalLength) { pending.removeAt(0) }
        }
        return frames
    }

    private fun validChecksum(frame: ByteArray, version: Int, payloadLength: Int, extra: Int): Boolean {
        val crcEndExclusive = (if (version == 2) 10 else 6) + payloadLength
        var crc = 0xFFFF
        for (index in 1 until crcEndExclusive) crc = crcAccumulate(frame[index].u8(), crc)
        crc = crcAccumulate(extra, crc)
        val received = frame[crcEndExclusive].u8() or (frame[crcEndExclusive + 1].u8() shl 8)
        return crc == received
    }

    companion object {
        const val MAVLINK_V1_MAGIC = 0xFE
        const val MAVLINK_V2_MAGIC = 0xFD

        val CRC_EXTRAS = mapOf(
            0 to 50,    // HEARTBEAT
            1 to 124,   // SYS_STATUS
            24 to 24,   // GPS_RAW_INT
            30 to 39,   // ATTITUDE
            33 to 104,  // GLOBAL_POSITION_INT
            74 to 20,   // VFR_HUD
            109 to 185, // RADIO_STATUS
            147 to 154, // BATTERY_STATUS
            193 to 71,  // EKF_STATUS_REPORT
            245 to 130, // EXTENDED_SYS_STATE
            253 to 83   // STATUSTEXT
        )

        fun crcAccumulate(data: Int, current: Int): Int {
            var tmp = data xor (current and 0xFF)
            tmp = tmp xor ((tmp shl 4) and 0xFF)
            return ((current shr 8) xor (tmp shl 8) xor (tmp shl 3) xor (tmp shr 4)) and 0xFFFF
        }
    }
}

object MavlinkFrameEncoder {
    fun v1(
        messageId: Int,
        payload: ByteArray,
        sequence: Int = 0,
        systemId: Int = 1,
        componentId: Int = 1
    ): ByteArray {
        require(messageId in 0..255)
        val extra = requireNotNull(MavlinkParser.CRC_EXTRAS[messageId]) { "Unknown CRC extra for message $messageId" }
        val frame = ByteArray(payload.size + 8)
        frame[0] = MavlinkParser.MAVLINK_V1_MAGIC.toByte()
        frame[1] = payload.size.toByte()
        frame[2] = sequence.toByte()
        frame[3] = systemId.toByte()
        frame[4] = componentId.toByte()
        frame[5] = messageId.toByte()
        payload.copyInto(frame, 6)
        var crc = 0xFFFF
        for (index in 1 until 6 + payload.size) crc = MavlinkParser.crcAccumulate(frame[index].toInt() and 0xFF, crc)
        crc = MavlinkParser.crcAccumulate(extra, crc)
        frame[6 + payload.size] = (crc and 0xFF).toByte()
        frame[7 + payload.size] = (crc shr 8).toByte()
        return frame
    }

    fun v2(
        messageId: Int,
        payload: ByteArray,
        sequence: Int = 0,
        systemId: Int = 1,
        componentId: Int = 1
    ): ByteArray {
        require(messageId in 0..0xFFFFFF)
        val extra = requireNotNull(MavlinkParser.CRC_EXTRAS[messageId]) { "Unknown CRC extra for message $messageId" }
        val frame = ByteArray(payload.size + 12)
        frame[0] = MavlinkParser.MAVLINK_V2_MAGIC.toByte()
        frame[1] = payload.size.toByte()
        frame[2] = 0
        frame[3] = 0
        frame[4] = sequence.toByte()
        frame[5] = systemId.toByte()
        frame[6] = componentId.toByte()
        frame[7] = (messageId and 0xFF).toByte()
        frame[8] = ((messageId shr 8) and 0xFF).toByte()
        frame[9] = ((messageId shr 16) and 0xFF).toByte()
        payload.copyInto(frame, 10)
        var crc = 0xFFFF
        for (index in 1 until 10 + payload.size) crc = MavlinkParser.crcAccumulate(frame[index].toInt() and 0xFF, crc)
        crc = MavlinkParser.crcAccumulate(extra, crc)
        frame[10 + payload.size] = (crc and 0xFF).toByte()
        frame[11 + payload.size] = (crc shr 8).toByte()
        return frame
    }
}

data class MavlinkMetric(val metric: String, val value: Double, val unit: String, val metadata: String = "")
data class MavlinkTextEvent(val eventType: String, val severity: Int?, val text: String, val metadata: String = "")
data class DecodedMavlink(val metrics: List<MavlinkMetric>, val events: List<MavlinkTextEvent>)

object MavlinkDecoder {
    fun decode(frame: MavlinkFrame): DecodedMavlink = when (frame.messageId) {
        0 -> heartbeat(frame.payload)
        1 -> sysStatus(frame.payload)
        24 -> gpsRaw(frame.payload)
        30 -> attitude(frame.payload)
        33 -> globalPosition(frame.payload)
        74 -> vfrHud(frame.payload)
        109 -> radioStatus(frame.payload)
        147 -> batteryStatus(frame.payload)
        193 -> ekfStatus(frame.payload)
        245 -> extendedState(frame.payload)
        253 -> statusText(frame.payload)
        else -> DecodedMavlink(emptyList(), emptyList())
    }

    private fun heartbeat(p: ByteArray): DecodedMavlink {
        if (p.size < 9) return empty()
        val baseMode = p.u8(6)
        val systemStatus = p.u8(7)
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_custom_mode", p.u32(0).toDouble(), "enum"),
                MavlinkMetric("mavlink_base_mode", baseMode.toDouble(), "bitmask"),
                MavlinkMetric("mavlink_armed", if ((baseMode and 0x80) != 0) 1.0 else 0.0, "bool"),
                MavlinkMetric("mavlink_system_status", systemStatus.toDouble(), "enum"),
                MavlinkMetric("mavlink_vehicle_type", p.u8(4).toDouble(), "enum"),
                MavlinkMetric("mavlink_autopilot_type", p.u8(5).toDouble(), "enum")
            ),
            if (systemStatus >= 5) listOf(MavlinkTextEvent("FAILSAFE_STATE", systemStatus, "MAV_STATE=$systemStatus")) else emptyList()
        )
    }

    private fun sysStatus(p: ByteArray): DecodedMavlink {
        if (p.size < 31) return empty()
        val voltage = p.u16(14)
        val current = p.i16(16)
        val remaining = p.i8(30)
        return DecodedMavlink(buildList {
            if (voltage != 0xFFFF) add(MavlinkMetric("mavlink_battery_voltage_v", voltage / 1000.0, "V", "message=SYS_STATUS"))
            if (current != -1) add(MavlinkMetric("mavlink_battery_current_a", current / 100.0, "A", "message=SYS_STATUS"))
            if (remaining >= 0) add(MavlinkMetric("mavlink_battery_remaining_pct", remaining.toDouble(), "percent", "message=SYS_STATUS"))
            add(MavlinkMetric("mavlink_drop_rate_comm_pct", p.u16(18) / 100.0, "percent"))
            add(MavlinkMetric("mavlink_comm_errors", p.u16(20).toDouble(), "count"))
        }, emptyList())
    }

    private fun gpsRaw(p: ByteArray): DecodedMavlink {
        if (p.size < 30) return empty()
        val eph = p.u16(20)
        return DecodedMavlink(buildList {
            add(MavlinkMetric("mavlink_gps_fix_type", p.u8(28).toDouble(), "enum"))
            add(MavlinkMetric("mavlink_gps_satellites", p.u8(29).toDouble(), "count"))
            add(MavlinkMetric("mavlink_latitude_deg", p.i32(8) / 1e7, "deg", "message=GPS_RAW_INT"))
            add(MavlinkMetric("mavlink_longitude_deg", p.i32(12) / 1e7, "deg", "message=GPS_RAW_INT"))
            add(MavlinkMetric("mavlink_altitude_msl_m", p.i32(16) / 1000.0, "m", "message=GPS_RAW_INT"))
            if (eph != 0xFFFF) add(MavlinkMetric("mavlink_gps_hdop", eph / 100.0, "ratio"))
        }, emptyList())
    }

    private fun attitude(p: ByteArray): DecodedMavlink {
        if (p.size < 28) return empty()
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_roll_rad", p.f32(4).toDouble(), "rad"),
                MavlinkMetric("mavlink_pitch_rad", p.f32(8).toDouble(), "rad"),
                MavlinkMetric("mavlink_yaw_rad", p.f32(12).toDouble(), "rad")
            ),
            emptyList()
        )
    }

    private fun globalPosition(p: ByteArray): DecodedMavlink {
        if (p.size < 28) return empty()
        val north = p.i16(20) / 100.0
        val east = p.i16(22) / 100.0
        val down = p.i16(24) / 100.0
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_latitude_deg", p.i32(4) / 1e7, "deg", "message=GLOBAL_POSITION_INT"),
                MavlinkMetric("mavlink_longitude_deg", p.i32(8) / 1e7, "deg", "message=GLOBAL_POSITION_INT"),
                MavlinkMetric("mavlink_altitude_msl_m", p.i32(12) / 1000.0, "m", "message=GLOBAL_POSITION_INT"),
                MavlinkMetric("mavlink_relative_altitude_m", p.i32(16) / 1000.0, "m"),
                MavlinkMetric("mavlink_velocity_north_mps", north, "m/s"),
                MavlinkMetric("mavlink_velocity_east_mps", east, "m/s"),
                MavlinkMetric("mavlink_velocity_down_mps", down, "m/s"),
                MavlinkMetric("mavlink_ground_speed_mps", sqrt(north * north + east * east), "m/s")
            ),
            emptyList()
        )
    }

    private fun vfrHud(p: ByteArray): DecodedMavlink {
        if (p.size < 20) return empty()
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_airspeed_mps", p.f32(0).toDouble(), "m/s"),
                MavlinkMetric("mavlink_ground_speed_mps", p.f32(4).toDouble(), "m/s"),
                MavlinkMetric("mavlink_altitude_msl_m", p.f32(12).toDouble(), "m", "message=VFR_HUD"),
                MavlinkMetric("mavlink_climb_mps", p.f32(16).toDouble(), "m/s")
            ),
            emptyList()
        )
    }

    private fun radioStatus(p: ByteArray): DecodedMavlink {
        if (p.size < 9) return empty()
        val rssi = p.u8(4)
        val remoteRssi = p.u8(5)
        val noise = p.u8(7)
        val remoteNoise = p.u8(8)
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_radio_rssi_raw", rssi.toDouble(), "raw"),
                MavlinkMetric("mavlink_radio_remote_rssi_raw", remoteRssi.toDouble(), "raw"),
                MavlinkMetric("mavlink_radio_local_margin_raw", (rssi - noise).toDouble(), "raw"),
                MavlinkMetric("mavlink_radio_remote_margin_raw", (remoteRssi - remoteNoise).toDouble(), "raw"),
                MavlinkMetric("mavlink_radio_tx_buffer_pct", p.u8(6).toDouble(), "percent"),
                MavlinkMetric("mavlink_radio_rx_errors", p.u16(0).toDouble(), "count"),
                MavlinkMetric("mavlink_radio_fixed_packets", p.u16(2).toDouble(), "count")
            ),
            emptyList()
        )
    }

    private fun batteryStatus(p: ByteArray): DecodedMavlink {
        if (p.size < 36) return empty()
        val voltage = (0 until 10).map { p.u16(10 + it * 2) }
            .filter { it != 0 && it != 0xFFFF }.sum()
        val current = p.i16(30)
        val remaining = p.i8(35)
        return DecodedMavlink(buildList {
            if (voltage > 0) add(MavlinkMetric("mavlink_battery_voltage_v", voltage / 1000.0, "V", "message=BATTERY_STATUS;id=${p.u8(32)}"))
            if (current != -1) add(MavlinkMetric("mavlink_battery_current_a", current / 100.0, "A", "message=BATTERY_STATUS;id=${p.u8(32)}"))
            if (remaining >= 0) add(MavlinkMetric("mavlink_battery_remaining_pct", remaining.toDouble(), "percent", "message=BATTERY_STATUS;id=${p.u8(32)}"))
        }, emptyList())
    }

    private fun ekfStatus(p: ByteArray): DecodedMavlink {
        if (p.size < 22) return empty()
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_ekf_flags", p.u16(20).toDouble(), "bitmask"),
                MavlinkMetric("mavlink_ekf_velocity_variance", p.f32(0).toDouble(), "variance"),
                MavlinkMetric("mavlink_ekf_horizontal_variance", p.f32(4).toDouble(), "variance"),
                MavlinkMetric("mavlink_ekf_vertical_variance", p.f32(8).toDouble(), "variance"),
                MavlinkMetric("mavlink_ekf_compass_variance", p.f32(12).toDouble(), "variance")
            ),
            emptyList()
        )
    }

    private fun extendedState(p: ByteArray): DecodedMavlink {
        if (p.size < 2) return empty()
        return DecodedMavlink(
            listOf(
                MavlinkMetric("mavlink_vtol_state", p.u8(0).toDouble(), "enum"),
                MavlinkMetric("mavlink_landed_state", p.u8(1).toDouble(), "enum")
            ),
            emptyList()
        )
    }

    private fun statusText(p: ByteArray): DecodedMavlink {
        if (p.size < 2) return empty()
        val bytes = p.copyOfRange(1, minOf(p.size, 51))
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        val text = bytes.copyOf(end).toString(Charsets.UTF_8)
        return DecodedMavlink(emptyList(), listOf(MavlinkTextEvent("STATUSTEXT", p.u8(0), text)))
    }

    private fun empty() = DecodedMavlink(emptyList(), emptyList())

    private fun ByteArray.buffer(offset: Int): ByteBuffer = ByteBuffer.wrap(this, offset, size - offset).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF
    private fun ByteArray.i8(offset: Int): Int = this[offset].toInt()
    private fun ByteArray.u16(offset: Int): Int = buffer(offset).short.toInt() and 0xFFFF
    private fun ByteArray.i16(offset: Int): Int = buffer(offset).short.toInt()
    private fun ByteArray.u32(offset: Int): Long = buffer(offset).int.toLong() and 0xFFFFFFFFL
    private fun ByteArray.i32(offset: Int): Int = buffer(offset).int
    private fun ByteArray.f32(offset: Int): Float = buffer(offset).float
}

private fun Byte.u8(): Int = toInt() and 0xFF
