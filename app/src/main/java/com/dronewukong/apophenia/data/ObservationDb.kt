package com.dronewukong.apophenia.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ObservationDb(context: Context) : SQLiteOpenHelper(context, "apophenia.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE observations(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              kind TEXT NOT NULL,
              label TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT '',
              severity INTEGER,
              confidence INTEGER NOT NULL DEFAULT 3
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_observations_time ON observations(timestamp_ms)")
        db.execSQL("CREATE INDEX idx_observations_label ON observations(label)")
        createContextTable(db)
        createRollingTable(db)
        db.execSQL("""
            CREATE TABLE hypotheses(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              created_at_ms INTEGER NOT NULL,
              event_label TEXT NOT NULL,
              metric TEXT NOT NULL,
              direction TEXT NOT NULL DEFAULT 'ANY',
              enabled INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE context_samples ADD COLUMN capture_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_capture ON context_samples(capture_id, metric)")
            createRollingTable(db)
        }
    }

    private fun createContextTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE context_samples(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              observation_id INTEGER,
              is_control INTEGER NOT NULL DEFAULT 0,
              source TEXT NOT NULL,
              metric TEXT NOT NULL,
              value REAL NOT NULL,
              unit TEXT NOT NULL,
              metadata TEXT NOT NULL DEFAULT '',
              capture_id TEXT NOT NULL DEFAULT '',
              FOREIGN KEY(observation_id) REFERENCES observations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_context_obs ON context_samples(observation_id)")
        db.execSQL("CREATE INDEX idx_context_metric ON context_samples(metric)")
        db.execSQL("CREATE INDEX idx_context_control ON context_samples(is_control, metric)")
        db.execSQL("CREATE INDEX idx_context_capture ON context_samples(capture_id, metric)")
    }

    private fun createRollingTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS rolling_samples(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              source TEXT NOT NULL,
              metric TEXT NOT NULL,
              value REAL NOT NULL,
              unit TEXT NOT NULL,
              metadata TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rolling_time ON rolling_samples(timestamp_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_rolling_metric ON rolling_samples(metric,timestamp_ms)")
    }

    fun insertObservation(o: Observation): Long = writableDatabase.insertOrThrow("observations", null, ContentValues().apply {
        put("timestamp_ms", o.timestampMs); put("kind", o.kind.name); put("label", o.label); put("note", o.note)
        if (o.severity == null) putNull("severity") else put("severity", o.severity)
        put("confidence", o.confidence)
    })

    fun observationTimestamp(id: Long): Long? = readableDatabase.rawQuery(
        "SELECT timestamp_ms FROM observations WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun insertContext(samples: List<ContextSample>) {
        if (samples.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            samples.forEach { s -> insertContextRow(writableDatabase, s) }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    private fun insertContextRow(db: SQLiteDatabase, s: ContextSample) {
        db.insertOrThrow("context_samples", null, ContentValues().apply {
            put("timestamp_ms", s.timestampMs)
            if (s.observationId == null) putNull("observation_id") else put("observation_id", s.observationId)
            put("is_control", if (s.isControl) 1 else 0); put("source", s.source); put("metric", s.metric)
            put("value", s.value); put("unit", s.unit); put("metadata", s.metadata); put("capture_id", s.captureId)
        })
    }

    fun insertRolling(samples: List<ContextSample>, retentionMs: Long) {
        if (samples.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            samples.forEach { s ->
                writableDatabase.insertOrThrow("rolling_samples", null, ContentValues().apply {
                    put("timestamp_ms", s.timestampMs); put("source", s.source); put("metric", s.metric)
                    put("value", s.value); put("unit", s.unit); put("metadata", s.metadata)
                })
            }
            writableDatabase.delete("rolling_samples", "timestamp_ms<?", arrayOf((now-retentionMs).toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun copyRollingToObservation(observationId: Long, startMs: Long, endMs: Long, phase: String): Int {
        val capture = "event:$observationId:$phase"
        val sql = """
            INSERT INTO context_samples(timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id)
            SELECT r.timestamp_ms, ?, 0, 'rolling/' || r.source, r.metric, r.value, r.unit,
                   CASE WHEN r.metadata='' THEN ? ELSE r.metadata || ';' || ? END, ?
            FROM rolling_samples r
            WHERE r.timestamp_ms BETWEEN ? AND ?
              AND NOT EXISTS (
                SELECT 1 FROM context_samples c
                WHERE c.observation_id=? AND c.timestamp_ms=r.timestamp_ms
                  AND c.source='rolling/' || r.source AND c.metric=r.metric
              )
        """.trimIndent()
        val marker = "window=$phase"
        val st = writableDatabase.compileStatement(sql)
        return try {
            st.bindLong(1, observationId); st.bindString(2, marker); st.bindString(3, marker); st.bindString(4, capture)
            st.bindLong(5, startMs); st.bindLong(6, endMs); st.bindLong(7, observationId)
            st.executeUpdateDelete()
        } finally { st.close() }
    }

    fun copyRollingToControl(captureId: String, startMs: Long, endMs: Long): Int {
        val sql = """
            INSERT INTO context_samples(timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id)
            SELECT r.timestamp_ms, NULL, 1, 'rolling/' || r.source, r.metric, r.value, r.unit,
                   CASE WHEN r.metadata='' THEN 'window=control' ELSE r.metadata || ';window=control' END, ?
            FROM rolling_samples r
            WHERE r.timestamp_ms BETWEEN ? AND ?
        """.trimIndent()
        val st = writableDatabase.compileStatement(sql)
        return try {
            st.bindString(1, captureId); st.bindLong(2, startMs); st.bindLong(3, endMs)
            st.executeUpdateDelete()
        } finally { st.close() }
    }

    fun rollingStatus(): Triple<Int, Long?, Long?> = readableDatabase.rawQuery(
        "SELECT COUNT(*),MIN(timestamp_ms),MAX(timestamp_ms) FROM rolling_samples", null
    ).use { c ->
        if (!c.moveToFirst()) Triple(0,null,null) else Triple(c.getInt(0), if(c.isNull(1)) null else c.getLong(1), if(c.isNull(2)) null else c.getLong(2))
    }

    fun observations(limit: Int = 250): List<Observation> {
        val out = mutableListOf<Observation>()
        readableDatabase.rawQuery("SELECT id,timestamp_ms,kind,label,note,severity,confidence FROM observations ORDER BY timestamp_ms DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out += Observation(
                id = c.getLong(0), timestampMs = c.getLong(1), kind = ObservationKind.valueOf(c.getString(2)),
                label = c.getString(3), note = c.getString(4), severity = if (c.isNull(5)) null else c.getInt(5), confidence = c.getInt(6)
            )
        }
        return out
    }

    fun contextForObservation(observationId: Long): List<ContextSample> = queryContext(
        "SELECT id,timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id FROM context_samples WHERE observation_id=? ORDER BY timestamp_ms",
        arrayOf(observationId.toString())
    )

    fun eventFeatureValues(label: String, metric: String): List<Double> {
        val out = mutableListOf<Double>()
        readableDatabase.rawQuery("""
            SELECT AVG(cs.value) FROM context_samples cs
            JOIN observations o ON o.id=cs.observation_id
            WHERE o.label=? COLLATE NOCASE AND cs.metric=? AND cs.is_control=0
              AND (cs.source NOT LIKE 'rolling/%' OR cs.metadata LIKE '%window=pre%')
            GROUP BY o.id
        """.trimIndent(), arrayOf(label, metric)).use { c -> while (c.moveToNext()) out += c.getDouble(0) }
        return out
    }

    fun controlFeatureValues(metric: String): List<Double> {
        val out = mutableListOf<Double>()
        readableDatabase.rawQuery("""
            SELECT AVG(value) FROM context_samples
            WHERE is_control=1 AND metric=?
            GROUP BY CASE WHEN capture_id='' THEN CAST(timestamp_ms AS TEXT) ELSE capture_id END
        """.trimIndent(), arrayOf(metric)).use { c -> while (c.moveToNext()) out += c.getDouble(0) }
        return out
    }

    fun metricsForLabel(label: String): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("""
            SELECT DISTINCT cs.metric FROM context_samples cs JOIN observations o ON o.id=cs.observation_id
            WHERE o.label=? COLLATE NOCASE ORDER BY cs.metric
        """.trimIndent(), arrayOf(label)).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    fun labels(): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT label,COUNT(*) FROM observations GROUP BY lower(label) ORDER BY COUNT(*) DESC,label", null).use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getInt(1)
        }
        return out
    }

    fun allContext(limit: Int = 5000): List<ContextSample> = queryContext(
        "SELECT id,timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id FROM context_samples ORDER BY timestamp_ms DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    private fun queryContext(sql: String, args: Array<String>): List<ContextSample> {
        val out = mutableListOf<ContextSample>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out += ContextSample(
                id=c.getLong(0), timestampMs=c.getLong(1), observationId=if(c.isNull(2)) null else c.getLong(2),
                isControl=c.getInt(3)==1, source=c.getString(4), metric=c.getString(5), value=c.getDouble(6), unit=c.getString(7),
                metadata=c.getString(8), captureId=c.getString(9)
            )
        }
        return out
    }
}
