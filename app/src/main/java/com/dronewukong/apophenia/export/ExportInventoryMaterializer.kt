package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.omniprobe.OmniprobeInspector
import org.json.JSONArray
import org.json.JSONObject

/** Serializes the same per-event channel accounting shown by Omniprobe. */
class ExportInventoryMaterializer(private val context: Context, private val db: ObservationDb) {
    fun payload(): ExportPayload {
        val inspector = OmniprobeInspector(context, db)
        val events = JSONArray()
        db.observations(100_000).sortedBy { it.timestampMs }.forEach { observation ->
            val snapshot = inspector.inspect(observation)
            events.put(
                JSONObject()
                    .put("observationId", observation.id)
                    .put("timestampMs", observation.timestampMs)
                    .put("label", observation.label)
                    .put("channels", JSONArray().also { channels -> snapshot.channels.forEach { channel ->
                        channels.put(
                            JSONObject()
                                .put("gate", channel.gate.name)
                                .put("title", channel.title)
                                .put("observed", channel.observed)
                                .put("gapReason", channel.gapReason?.name)
                                .put("gapDetail", channel.gapDetail)
                                .put("values", JSONArray().also { values -> channel.values.forEach { value ->
                                    values.put(
                                        JSONObject()
                                            .put("timestampMs", value.timestampMs)
                                            .put("source", value.source)
                                            .put("metric", value.metric)
                                            .put("value", value.renderedValue)
                                            .put("unit", value.unit)
                                            .put("captureId", value.captureId)
                                            .put("phase", value.phase)
                                            .put("metadata", value.metadata)
                                    )
                                } })
                        )
                    } })
                    .put("unmatchedValues", JSONArray().also { values -> snapshot.unmatchedValues.forEach { value ->
                        values.put(
                            JSONObject().put("timestampMs", value.timestampMs).put("source", value.source)
                                .put("metric", value.metric).put("value", value.renderedValue).put("unit", value.unit)
                                .put("captureId", value.captureId).put("phase", value.phase).put("metadata", value.metadata)
                        )
                    } })
            )
        }
        val root = JSONObject()
            .put("schema", "apophenia.omniprobe.export.v1")
            .put("gapReasonBoundary", "Absent-channel reasons use the gate, permission, platform, and hardware state visible at export time; they are accounting aids, not retroactive hardware proof.")
            .put("events", events)
        return ExportPayload(
            path = "inventories/omniprobe.json",
            bytes = root.toString(2).toByteArray(Charsets.UTF_8),
            containsTier2Contents = db.allSensitiveContext(1).isNotEmpty()
        )
    }
}
