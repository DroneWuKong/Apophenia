package com.dronewukong.apophenia.garmin

import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.data.ObservationCaptureRequest
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationRepository

interface GarminEventStore {
    fun log(request: ObservationCaptureRequest, onResult: (id: Long, inserted: Boolean) -> Unit)
    fun insertContext(samples: List<ContextSample>)
}

class RepositoryGarminEventStore(private val repository: ObservationRepository) : GarminEventStore {
    override fun log(request: ObservationCaptureRequest, onResult: (Long, Boolean) -> Unit) =
        repository.logWithResult(request, onResult)

    override fun insertContext(samples: List<ContextSample>) = repository.insertContext(samples)
}

sealed interface GarminIngestResult {
    data class Accepted(val warnings: List<String>) : GarminIngestResult
    data class Rejected(val reason: String) : GarminIngestResult
}

data class GarminPersistenceReceipt(val eventId: String, val inserted: Boolean)

class GarminEventIngestor(
    private val store: GarminEventStore,
    private val watchAppId: String,
    private val onDiagnostic: (String) -> Unit = {}
) {
    fun ingest(
        deviceName: String,
        packet: Map<*, *>,
        receivedAtMs: Long = System.currentTimeMillis(),
        onPersisted: (GarminPersistenceReceipt) -> Unit = {}
    ): GarminIngestResult {
        return when (val parsed = GarminPacketParser.parseDetailed(packet, receivedAtMs)) {
            is GarminParseResult.Rejected -> {
                onDiagnostic(parsed.reason)
                GarminIngestResult.Rejected(parsed.reason)
            }
            is GarminParseResult.Accepted -> {
                parsed.warnings.forEach(onDiagnostic)
                val event = parsed.event
                val request = ObservationCaptureRequest(
                    timestampMs = event.timestampMs,
                    kind = event.kind,
                    label = event.label,
                    note = event.note.ifBlank { "Logged on $deviceName" },
                    origin = ObservationOrigin.GARMIN,
                    externalEventId = event.eventId
                )
                store.log(request) { id, inserted ->
                    when {
                        !inserted -> onDiagnostic("Ignored duplicate Garmin event ${event.eventId ?: event.timestampMs}")
                        event.kind == ObservationKind.HYPOTHESIS_NOTE -> onDiagnostic("Stored Garmin hypothesis note")
                        else -> {
                            store.insertContext(event.metrics.map { (key, value) ->
                                ContextSample(
                                    timestampMs = event.timestampMs,
                                    observationId = id,
                                    source = "garmin/$deviceName",
                                    metric = key,
                                    value = value,
                                    unit = unitFor(key),
                                    metadata = "watch_app_id=$watchAppId;protocol=${event.protocolVersion}",
                                    captureId = "garmin:event:$id"
                                )
                            })
                            onDiagnostic("Stored Garmin event from $deviceName")
                        }
                    }
                    event.eventId?.let { onPersisted(GarminPersistenceReceipt(it, inserted)) }
                }
                GarminIngestResult.Accepted(parsed.warnings)
            }
        }
    }

    private fun unitFor(key: String): String = when (key) {
        "garmin_heart_rate_bpm" -> "bpm"
        "garmin_stress", "garmin_body_battery" -> "score"
        "garmin_spo2_pct" -> "%"
        "garmin_pressure_hpa" -> "hPa"
        "garmin_temperature_c" -> "C"
        "garmin_phone_connected" -> "bool"
        else -> "value"
    }
}
