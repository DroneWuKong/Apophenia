package com.dronewukong.apophenia.vehicle

import com.dronewukong.apophenia.data.ContextSample

interface ObdTransport : AutoCloseable {
    fun connect(address: String)
    fun command(command: String): String
}

data class ObdSnapshot(
    val values: Map<String, ObdValue>,
    val storedDtcs: List<String>,
    val pendingDtcs: List<String>,
    val unsupportedCommands: Set<String>
)

data class ObdValue(
    val value: Double,
    val unit: String,
    val metadata: String = ""
)

private data class PidDefinition(
    val command: String,
    val metric: String,
    val unit: String,
    val coverage: String = "standard",
    val decode: (List<Int>) -> Double?
)

class Elm327Client(private val transport: ObdTransport) : AutoCloseable {
    fun initialize() {
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0").forEach { command ->
            val response = transport.command(command)
            check(!Elm327Parser.isTransportFailure(response)) { "ELM327 initialization failed at $command" }
        }
    }

    fun readSnapshot(): ObdSnapshot {
        val values = linkedMapOf<String, ObdValue>()
        val unsupported = linkedSetOf<String>()
        definitions.forEach { definition ->
            val response = runCatching { transport.command(definition.command) }.getOrElse {
                unsupported += definition.command
                return@forEach
            }
            val bytes = Elm327Parser.pidData(response, definition.command.takeLast(2).toInt(16))
            val decoded = bytes?.let(definition.decode)
            if (decoded == null || !decoded.isFinite()) {
                unsupported += definition.command
            } else {
                values[definition.metric] = ObdValue(
                    decoded,
                    definition.unit,
                    "pid=${definition.command.takeLast(2)};coverage=${definition.coverage}"
                )
            }
        }
        if ("obd_control_module_voltage_v" !in values) {
            runCatching { Elm327Parser.voltage(transport.command("ATRV")) }.getOrNull()?.let {
                values["obd_adapter_voltage_v"] = ObdValue(it, "V", "source=ELM_ATRV;coverage=adapter_reported")
            }
        }
        return ObdSnapshot(
            values = values,
            storedDtcs = Elm327Parser.dtcs(runCatching { transport.command("03") }.getOrDefault(""), 0x43),
            pendingDtcs = Elm327Parser.dtcs(runCatching { transport.command("07") }.getOrDefault(""), 0x47),
            unsupportedCommands = unsupported
        )
    }

    override fun close() = transport.close()

    companion object {
        private fun byte(index: Int, transform: (Int) -> Double) = { data: List<Int> -> data.getOrNull(index)?.let(transform) }
        private fun twoBytes(transform: (Int, Int) -> Double) = { data: List<Int> ->
            if (data.size < 2) null else transform(data[0], data[1])
        }

        private val definitions = listOf(
            PidDefinition("010C", "obd_engine_rpm", "rpm", decode = twoBytes { a, b -> (a * 256 + b) / 4.0 }),
            PidDefinition("010D", "obd_vehicle_speed_kph", "km/h", decode = byte(0) { it.toDouble() }),
            PidDefinition("0104", "obd_engine_load_pct", "percent", decode = byte(0) { it * 100.0 / 255.0 }),
            PidDefinition("0105", "obd_coolant_temp_c", "C", decode = byte(0) { it - 40.0 }),
            PidDefinition("010F", "obd_intake_temp_c", "C", decode = byte(0) { it - 40.0 }),
            PidDefinition("0146", "obd_ambient_temp_c", "C", decode = byte(0) { it - 40.0 }),
            PidDefinition("0170", "obd_cabin_or_ambient_temp_c", "C", "manufacturer_or_vehicle_dependent", byte(0) { it - 40.0 }),
            PidDefinition("0111", "obd_throttle_pct", "percent", decode = byte(0) { it * 100.0 / 255.0 }),
            PidDefinition("012F", "obd_fuel_level_pct", "percent", decode = byte(0) { it * 100.0 / 255.0 }),
            PidDefinition("0106", "obd_short_fuel_trim_bank1_pct", "percent", decode = byte(0) { (it - 128) * 100.0 / 128.0 }),
            PidDefinition("0107", "obd_long_fuel_trim_bank1_pct", "percent", decode = byte(0) { (it - 128) * 100.0 / 128.0 }),
            PidDefinition("0142", "obd_control_module_voltage_v", "V", decode = twoBytes { a, b -> (a * 256 + b) / 1000.0 })
        )
    }
}

object Elm327Parser {
    fun isTransportFailure(response: String): Boolean {
        val upper = response.uppercase()
        return listOf("?", "UNABLE TO CONNECT", "BUS ERROR", "ERROR").any(upper::contains)
    }

    fun pidData(response: String, pid: Int): List<Int>? {
        if (response.contains("NO DATA", ignoreCase = true) || isTransportFailure(response)) return null
        return responseLines(response).firstNotNullOfOrNull { bytes ->
            val index = bytes.indices.firstOrNull { i ->
                bytes[i] == 0x41 && bytes.getOrNull(i + 1) == pid
            } ?: return@firstNotNullOfOrNull null
            bytes.drop(index + 2)
        }
    }

    fun voltage(response: String): Double? =
        Regex("([0-9]+(?:\\.[0-9]+)?)\\s*V", RegexOption.IGNORE_CASE)
            .find(response)?.groupValues?.get(1)?.toDoubleOrNull()

    fun dtcs(response: String, responseMode: Int): List<String> {
        val bytes = responseLines(response).firstNotNullOfOrNull { line ->
            val index = line.indexOf(responseMode)
            if (index < 0) null else line.drop(index + 1)
        }.orEmpty()
        return bytes.chunked(2).mapNotNull { pair ->
            if (pair.size < 2 || (pair[0] == 0 && pair[1] == 0)) return@mapNotNull null
            val system = "PCBU"[(pair[0] shr 6) and 0x3]
            val second = (pair[0] shr 4) and 0x3
            val remaining = "%03X".format(((pair[0] and 0xF) shl 8) or pair[1])
            "$system$second$remaining"
        }.distinct()
    }

    private fun responseLines(response: String): List<List<Int>> = response
        .uppercase()
        .replace('>', '\n')
        .lineSequence()
        .map { it.substringBefore("SEARCHING") }
        .map { line -> line.replace(Regex("[^0-9A-F ]"), " ").trim() }
        .filter(String::isNotBlank)
        .mapNotNull { line ->
            val rawTokens = line.split(Regex("\\s+")).filter(String::isNotBlank).toMutableList()
            if (rawTokens.size > 1 && rawTokens.firstOrNull()?.length in setOf(3, 8)) rawTokens.removeAt(0)
            val bytes = rawTokens.flatMap { token ->
                if (token.length % 2 == 0) token.chunked(2) else emptyList()
            }.mapNotNull { it.toIntOrNull(16) }
            bytes.takeIf(List<Int>::isNotEmpty)
        }
        .toList()
}

object ObdSnapshotEncoder {
    fun samples(
        snapshot: ObdSnapshot,
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean,
        sessionId: String
    ): List<ContextSample> = buildList {
        val captureId = observationId?.let { "event:$it:instant" }.orEmpty()
        snapshot.values.forEach { (metric, reading) ->
            add(
                ContextSample(
                    timestampMs = timestampMs,
                    observationId = observationId,
                    isControl = isControl,
                    source = "obd_elm327",
                    metric = metric,
                    value = reading.value,
                    unit = reading.unit,
                    metadata = listOf(reading.metadata, "window=instant").filter(String::isNotBlank).joinToString(";"),
                    captureId = captureId,
                    sessionId = sessionId
                )
            )
        }
        snapshot.storedDtcs.forEach { code ->
            add(ContextSample(timestampMs = timestampMs, observationId = observationId, isControl = isControl, source = "obd_elm327", metric = "obd_stored_dtc", value = 1.0, unit = "presence", metadata = "code=$code;window=instant", captureId = captureId, sessionId = sessionId))
        }
        snapshot.pendingDtcs.forEach { code ->
            add(ContextSample(timestampMs = timestampMs, observationId = observationId, isControl = isControl, source = "obd_elm327", metric = "obd_pending_dtc", value = 1.0, unit = "presence", metadata = "code=$code;window=instant", captureId = captureId, sessionId = sessionId))
        }
        add(ContextSample(timestampMs = timestampMs, observationId = observationId, isControl = isControl, source = "obd_elm327", metric = "obd_stored_dtc_count", value = snapshot.storedDtcs.size.toDouble(), unit = "count", metadata = "window=instant", captureId = captureId, sessionId = sessionId))
        add(ContextSample(timestampMs = timestampMs, observationId = observationId, isControl = isControl, source = "obd_elm327", metric = "obd_pending_dtc_count", value = snapshot.pendingDtcs.size.toDouble(), unit = "count", metadata = "window=instant", captureId = captureId, sessionId = sessionId))
    }
}
