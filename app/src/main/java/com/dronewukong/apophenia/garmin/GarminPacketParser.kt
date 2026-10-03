package com.dronewukong.apophenia.garmin

import com.dronewukong.apophenia.data.ObservationKind

data class ParsedGarminEvent(
    val protocolVersion: Int,
    val eventId: String?,
    val timestampMs: Long,
    val kind: ObservationKind,
    val label: String,
    val note: String,
    val metrics: Map<String, Double>
)

object GarminPacketParser {
    fun parse(packet: Map<*, *>, receivedAtMs: Long = System.currentTimeMillis()): ParsedGarminEvent? {
        return (parseDetailed(packet, receivedAtMs) as? GarminParseResult.Accepted)?.event
    }

    fun parseDetailed(packet: Map<*, *>, receivedAtMs: Long = System.currentTimeMillis()): GarminParseResult {
        if (packet["type"]?.toString() != "observation") {
            return GarminParseResult.Rejected("Ignored Garmin packet with unsupported type")
        }
        val warnings = mutableListOf<String>()
        val version = (packet["v"] as? Number)?.toInt() ?: 2
        val label = packet["label"]?.toString()?.trim().orEmpty().ifBlank { "Garmin observation" }
        val timestamp = (packet["ts_ms"] as? Number)?.toLong()?.takeIf { it > 0L } ?: receivedAtMs.also {
            warnings += "Garmin packet had no valid watch timestamp; used phone receive time"
        }
        val kind = runCatching { ObservationKind.valueOf(packet["kind"]?.toString().orEmpty()) }
            .getOrElse {
                warnings += "Garmin packet had an unknown observation kind; used a neutral fallback"
                if (label.equals("That was weird", true)) ObservationKind.WEIRD else ObservationKind.OBSERVATION
            }
        val metrics = (packet["metrics"] as? Map<*, *>).orEmpty().mapNotNull { (rawKey, rawValue) ->
            val key = rawKey?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = (rawValue as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return@mapNotNull null
            key to value
        }.toMap()
        return GarminParseResult.Accepted(ParsedGarminEvent(
            protocolVersion = version,
            eventId = packet["event_id"]?.toString()?.takeIf { version >= 3 && it.isNotBlank() },
            timestampMs = timestamp,
            kind = kind,
            label = label,
            note = packet["note"]?.toString().orEmpty(),
            metrics = metrics
        ), warnings)
    }
}

sealed interface GarminParseResult {
    data class Accepted(val event: ParsedGarminEvent, val warnings: List<String>) : GarminParseResult
    data class Rejected(val reason: String) : GarminParseResult
}
