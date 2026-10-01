package com.dronewukong.apophenia.export
import com.dronewukong.apophenia.data.ObservationDb
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
object ExportManager{
 fun exportJson(db:ObservationDb,dir:File):File{val observations=db.observations(100000);val root=JSONObject().put("schema",2).put("exportedAtMs",System.currentTimeMillis());val obs=JSONArray();observations.forEach{o->val contexts=JSONArray();db.contextForObservation(o.id).forEach{s->contexts.put(sampleJson(s))};obs.put(JSONObject().put("id",o.id).put("timestampMs",o.timestampMs).put("kind",o.kind.name).put("label",o.label).put("note",o.note).put("severity",o.severity).put("confidence",o.confidence).put("context",contexts))};root.put("observations",obs);val controls=JSONArray();db.allContext(100000).filter{it.isControl}.forEach{controls.put(sampleJson(it))};root.put("controls",controls);return File(dir,"apophenia-export-${System.currentTimeMillis()}.json").apply{writeText(root.toString(2))}}
 private fun sampleJson(s:com.dronewukong.apophenia.data.ContextSample)=JSONObject().put("timestampMs",s.timestampMs).put("source",s.source).put("metric",s.metric).put("value",s.value).put("unit",s.unit).put("isControl",s.isControl).put("metadata",s.metadata).put("captureId",s.captureId)
}
