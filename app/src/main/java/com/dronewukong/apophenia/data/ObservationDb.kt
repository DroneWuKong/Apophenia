package com.dronewukong.apophenia.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.dronewukong.apophenia.correlation.TimedCaptureValue

data class ObservationInsertResult(val id: Long, val inserted: Boolean)

class ObservationDb(context: Context, databaseName: String = "apophenia.db") : SQLiteOpenHelper(context, databaseName, null, 9) {
    val isDemoDatabase: Boolean = databaseName == "apophenia-demo.db"
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE observations(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              kind TEXT NOT NULL,
              label TEXT NOT NULL,
              note TEXT NOT NULL DEFAULT '',
              severity INTEGER,
              confidence INTEGER NOT NULL DEFAULT 3,
              origin TEXT NOT NULL DEFAULT 'ANDROID',
              external_event_id TEXT,
              vibe_rating INTEGER CHECK(vibe_rating BETWEEN 1 AND 5),
              egress INTEGER NOT NULL DEFAULT 0 CHECK(egress IN (0,1))
            )
        """.trimIndent())
        createObservationIndexes(db)
        createContextTable(db)
        createRollingTable(db)
        createHypothesisTable(db)
        createHypothesisEvaluationTable(db)
        createAnalysisViewTable(db)
        createSensitiveContextTable(db)
        createCaptureSessionTable(db)
        createSessionEventTable(db)
        createMediaTables(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE context_samples ADD COLUMN capture_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_capture ON context_samples(capture_id, metric)")
            createRollingTable(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE observations ADD COLUMN origin TEXT NOT NULL DEFAULT 'ANDROID'")
            db.execSQL("ALTER TABLE observations ADD COLUMN external_event_id TEXT")
            db.execSQL("ALTER TABLE context_samples ADD COLUMN phase TEXT NOT NULL DEFAULT 'INSTANT'")
            db.execSQL("UPDATE context_samples SET phase='CONTROL' WHERE is_control=1")
            db.execSQL("UPDATE context_samples SET phase='PRE' WHERE metadata LIKE '%window=pre%'")
            db.execSQL("UPDATE context_samples SET phase='POST' WHERE metadata LIKE '%window=post%'")
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN source TEXT NOT NULL DEFAULT 'ANDROID'")
            createObservationIndexes(db)
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE observations ADD COLUMN vibe_rating INTEGER CHECK(vibe_rating BETWEEN 1 AND 5)")
            db.execSQL("ALTER TABLE observations ADD COLUMN egress INTEGER NOT NULL DEFAULT 0 CHECK(egress IN (0,1))")
        }
        if (oldVersion < 5) createSensitiveContextTable(db)
        if (oldVersion < 6) {
            createCaptureSessionTable(db)
            db.execSQL("ALTER TABLE context_samples ADD COLUMN session_id TEXT")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_context_session ON context_samples(session_id,timestamp_ms)")
        }
        if (oldVersion < 7) createSessionEventTable(db)
        if (oldVersion < 8) createMediaTables(db)
        if (oldVersion < 9) {
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN cohort_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN window_start_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN window_end_ms INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE hypotheses ADD COLUMN locked_at_ms INTEGER")
            db.execSQL("UPDATE hypotheses SET cohort_id='label:' || event_label WHERE cohort_id=''")
            createHypothesisEvaluationTable(db)
            createAnalysisViewTable(db)
        }
    }

    private fun createObservationIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_observations_time ON observations(timestamp_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_observations_label ON observations(label)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_observations_external ON observations(origin, external_event_id) WHERE external_event_id IS NOT NULL")
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
              phase TEXT NOT NULL DEFAULT 'INSTANT',
              session_id TEXT,
              FOREIGN KEY(observation_id) REFERENCES observations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_context_obs ON context_samples(observation_id)")
        db.execSQL("CREATE INDEX idx_context_metric ON context_samples(metric)")
        db.execSQL("CREATE INDEX idx_context_control ON context_samples(is_control, metric)")
        db.execSQL("CREATE INDEX idx_context_capture ON context_samples(capture_id, metric)")
        db.execSQL("CREATE INDEX idx_context_phase ON context_samples(phase, metric)")
        db.execSQL("CREATE INDEX idx_context_session ON context_samples(session_id,timestamp_ms)")
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

    private fun createHypothesisTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE hypotheses(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              created_at_ms INTEGER NOT NULL,
              event_label TEXT NOT NULL,
              metric TEXT NOT NULL,
              direction TEXT NOT NULL DEFAULT 'ANY',
              enabled INTEGER NOT NULL DEFAULT 1,
              note TEXT NOT NULL DEFAULT '',
              source TEXT NOT NULL DEFAULT 'ANDROID',
              cohort_id TEXT NOT NULL DEFAULT '',
              window_start_ms INTEGER NOT NULL DEFAULT 0,
              window_end_ms INTEGER NOT NULL DEFAULT 0,
              locked_at_ms INTEGER
            )
        """.trimIndent())
    }

    private fun createHypothesisEvaluationTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS hypothesis_evaluations(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              hypothesis_id INTEGER NOT NULL,
              evaluated_at_ms INTEGER NOT NULL,
              analysis_signature TEXT NOT NULL,
              outcome TEXT NOT NULL,
              event_count INTEGER NOT NULL,
              control_count INTEGER NOT NULL,
              adjusted_p REAL,
              delta REAL,
              comparisons_tested INTEGER NOT NULL,
              summary TEXT NOT NULL,
              UNIQUE(hypothesis_id,analysis_signature),
              FOREIGN KEY(hypothesis_id) REFERENCES hypotheses(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hypothesis_evaluation ON hypothesis_evaluations(hypothesis_id,evaluated_at_ms)")
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS prevent_locked_hypothesis_update
            BEFORE UPDATE OF event_label,metric,direction,enabled,note,cohort_id,window_start_ms,window_end_ms ON hypotheses
            WHEN OLD.locked_at_ms IS NOT NULL
            BEGIN
              SELECT RAISE(ABORT,'locked hypothesis registrations are immutable');
            END
        """.trimIndent())
    }

    private fun createAnalysisViewTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS analysis_views(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              cohort_id TEXT NOT NULL,
              feature_key TEXT NOT NULL,
              first_viewed_at_ms INTEGER NOT NULL,
              latest_signature TEXT NOT NULL,
              UNIQUE(cohort_id,feature_key)
            )
        """.trimIndent())
    }

    private fun createSensitiveContextTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS sensitive_context(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              observation_id INTEGER,
              is_control INTEGER NOT NULL DEFAULT 0 CHECK(is_control IN (0,1)),
              source TEXT NOT NULL,
              content_type TEXT NOT NULL,
              ciphertext_base64 TEXT NOT NULL,
              iv_base64 TEXT NOT NULL,
              key_alias TEXT NOT NULL,
              capture_id TEXT NOT NULL,
              FOREIGN KEY(observation_id) REFERENCES observations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensitive_observation ON sensitive_context(observation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sensitive_capture ON sensitive_context(capture_id,content_type)")
    }

    private fun createCaptureSessionTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS capture_sessions(
              id TEXT PRIMARY KEY,
              type TEXT NOT NULL,
              started_at_ms INTEGER NOT NULL,
              ended_at_ms INTEGER,
              identity_hash TEXT NOT NULL,
              status TEXT NOT NULL,
              metadata TEXT NOT NULL DEFAULT ''
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_type_time ON capture_sessions(type,started_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_status ON capture_sessions(status,type)")
    }

    private fun createSessionEventTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS session_events(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              timestamp_ms INTEGER NOT NULL,
              session_id TEXT NOT NULL,
              event_type TEXT NOT NULL,
              severity INTEGER,
              text TEXT NOT NULL,
              metadata TEXT NOT NULL DEFAULT '',
              FOREIGN KEY(session_id) REFERENCES capture_sessions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_event_time ON session_events(session_id,timestamp_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_session_event_type ON session_events(event_type,timestamp_ms)")
    }

    private fun createMediaTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS media_assets(
              id TEXT PRIMARY KEY,
              observation_id INTEGER NOT NULL,
              media_type TEXT NOT NULL,
              stream_id TEXT NOT NULL,
              created_at_ms INTEGER NOT NULL,
              retention_until_ms INTEGER NOT NULL,
              keep_forever INTEGER NOT NULL DEFAULT 0 CHECK(keep_forever IN (0,1)),
              status TEXT NOT NULL DEFAULT 'ACTIVE',
              ciphertext_relative_path TEXT NOT NULL,
              manifest_relative_path TEXT NOT NULL,
              key_alias TEXT NOT NULL,
              ciphertext_sha256 TEXT NOT NULL,
              size_bytes INTEGER NOT NULL,
              FOREIGN KEY(observation_id) REFERENCES observations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_observation ON media_assets(observation_id,status)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_media_retention ON media_assets(status,keep_forever,retention_until_ms)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS purge_ledger(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              media_id TEXT NOT NULL,
              observation_id INTEGER NOT NULL,
              media_type TEXT NOT NULL,
              purged_at_ms INTEGER NOT NULL,
              reason TEXT NOT NULL,
              bytes_deleted INTEGER NOT NULL,
              derived_metrics_retained INTEGER NOT NULL DEFAULT 1 CHECK(derived_metrics_retained IN (0,1))
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purge_time ON purge_ledger(purged_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_purge_observation ON purge_ledger(observation_id,purged_at_ms)")
    }

    fun insertObservation(o: Observation): Long = insertObservationOrGet(o).id

    fun insertObservationOrGet(o: Observation): ObservationInsertResult {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("timestamp_ms", o.timestampMs); put("kind", o.kind.name); put("label", o.label); put("note", o.note)
                if (o.severity == null) putNull("severity") else put("severity", o.severity)
                put("confidence", o.confidence); put("origin", o.origin.name)
                if (o.externalEventId == null) putNull("external_event_id") else put("external_event_id", o.externalEventId)
                if (o.vibeRating == null) putNull("vibe_rating") else put("vibe_rating", o.vibeRating)
                put("egress", if (o.egress) 1 else 0)
            }
            val insertedId = db.insertWithOnConflict("observations", null, values, SQLiteDatabase.CONFLICT_IGNORE)
            val result = if (insertedId != -1L) ObservationInsertResult(insertedId, true) else {
                val externalId = requireNotNull(o.externalEventId) { "Observation insert conflicted without an external event id" }
                val existing = db.rawQuery(
                    "SELECT id FROM observations WHERE origin=? AND external_event_id=?",
                    arrayOf(o.origin.name, externalId)
                ).use { cursor -> check(cursor.moveToFirst()); cursor.getLong(0) }
                ObservationInsertResult(existing, false)
            }
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun insertHypothesis(h: Hypothesis): Long = writableDatabase.insertOrThrow("hypotheses", null, ContentValues().apply {
        put("created_at_ms", h.createdAtMs); put("event_label", h.eventLabel); put("metric", h.metric)
        put("direction", h.direction.name); put("enabled", if (h.enabled) 1 else 0); put("note", h.note); put("source", h.source.name)
        put("cohort_id", h.cohortId); put("window_start_ms", h.windowStartMs); put("window_end_ms", h.windowEndMs)
        if (h.lockedAtMs == null) putNull("locked_at_ms") else put("locked_at_ms", h.lockedAtMs)
    })

    fun insertHypothesisIfUnviewed(h: Hypothesis, featureKey: String): Long? {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val viewed = database.rawQuery("SELECT 1 FROM analysis_views WHERE cohort_id=? AND feature_key=? LIMIT 1", arrayOf(h.cohortId, featureKey)).use { it.moveToFirst() }
            if (viewed) return null
            val id = insertHypothesis(h)
            database.setTransactionSuccessful()
            return id
        } finally { database.endTransaction() }
    }

    fun recordAnalysisView(cohortId: String, featureKey: String, viewedAtMs: Long, signature: String) {
        val database = writableDatabase
        database.beginTransaction()
        try {
            database.insertWithOnConflict("analysis_views", null, ContentValues().apply {
                put("cohort_id", cohortId); put("feature_key", featureKey); put("first_viewed_at_ms", viewedAtMs); put("latest_signature", signature)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            database.update("analysis_views", ContentValues().apply { put("latest_signature", signature) }, "cohort_id=? AND feature_key=?", arrayOf(cohortId, featureKey))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    fun hasAnalysisView(cohortId: String, featureKey: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM analysis_views WHERE cohort_id=? AND feature_key=? LIMIT 1", arrayOf(cohortId, featureKey)
    ).use { it.moveToFirst() }

    fun hypotheses(limit: Int = 250): List<Hypothesis> {
        val out = mutableListOf<Hypothesis>()
        readableDatabase.rawQuery(
            "SELECT id,created_at_ms,event_label,metric,direction,enabled,note,source,cohort_id,window_start_ms,window_end_ms,locked_at_ms FROM hypotheses ORDER BY created_at_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out += Hypothesis(
            id=c.getLong(0), createdAtMs=c.getLong(1), eventLabel=c.getString(2), metric=c.getString(3),
            direction=runCatching { HypothesisDirection.valueOf(c.getString(4)) }.getOrDefault(HypothesisDirection.ANY), enabled=c.getInt(5)==1, note=c.getString(6),
            source=runCatching { ObservationOrigin.valueOf(c.getString(7)) }.getOrDefault(ObservationOrigin.ANDROID),
            cohortId=c.getString(8).ifBlank { AnalysisCohort.labelId(c.getString(2)) }, windowStartMs=c.getLong(9), windowEndMs=c.getLong(10),
            lockedAtMs=if(c.isNull(11))null else c.getLong(11)
        ) }
        return out
    }

    fun updateHypothesisRegistration(h: Hypothesis): Boolean = writableDatabase.update(
        "hypotheses",
        ContentValues().apply {
            put("event_label", h.eventLabel); put("metric", h.metric); put("direction", h.direction.name); put("enabled", if(h.enabled)1 else 0)
            put("note", h.note); put("cohort_id", h.cohortId); put("window_start_ms", h.windowStartMs); put("window_end_ms", h.windowEndMs)
        },
        "id=? AND locked_at_ms IS NULL",
        arrayOf(h.id.toString())
    ) > 0

    fun recordHypothesisEvaluation(evaluation: HypothesisEvaluation): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val inserted = database.insertWithOnConflict("hypothesis_evaluations", null, ContentValues().apply {
                put("hypothesis_id", evaluation.hypothesisId); put("evaluated_at_ms", evaluation.evaluatedAtMs)
                put("analysis_signature", evaluation.analysisSignature); put("outcome", evaluation.outcome.name)
                put("event_count", evaluation.eventCount); put("control_count", evaluation.controlCount)
                if (evaluation.adjustedP == null) putNull("adjusted_p") else put("adjusted_p", evaluation.adjustedP)
                if (evaluation.delta == null) putNull("delta") else put("delta", evaluation.delta)
                put("comparisons_tested", evaluation.comparisonsTested); put("summary", evaluation.summary)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            database.execSQL("UPDATE hypotheses SET locked_at_ms=COALESCE(locked_at_ms,?) WHERE id=?", arrayOf(evaluation.evaluatedAtMs, evaluation.hypothesisId))
            database.setTransactionSuccessful()
            return inserted != -1L
        } finally { database.endTransaction() }
    }

    fun hypothesisEvaluations(hypothesisId: Long, limit: Int = 100): List<HypothesisEvaluation> {
        val out = mutableListOf<HypothesisEvaluation>()
        readableDatabase.rawQuery(
            "SELECT id,hypothesis_id,evaluated_at_ms,analysis_signature,outcome,event_count,control_count,adjusted_p,delta,comparisons_tested,summary FROM hypothesis_evaluations WHERE hypothesis_id=? ORDER BY evaluated_at_ms DESC,id DESC LIMIT ?",
            arrayOf(hypothesisId.toString(), limit.toString())
        ).use { c -> while(c.moveToNext()) out += HypothesisEvaluation(
            id=c.getLong(0), hypothesisId=c.getLong(1), evaluatedAtMs=c.getLong(2), analysisSignature=c.getString(3), outcome=HypothesisOutcome.valueOf(c.getString(4)),
            eventCount=c.getInt(5), controlCount=c.getInt(6), adjustedP=if(c.isNull(7))null else c.getDouble(7), delta=if(c.isNull(8))null else c.getDouble(8),
            comparisonsTested=c.getInt(9), summary=c.getString(10)
        ) }
        return out
    }

    fun latestHypothesisEvaluation(hypothesisId: Long): HypothesisEvaluation? = hypothesisEvaluations(hypothesisId, 1).firstOrNull()

    fun observationTimestamp(id: Long): Long? = readableDatabase.rawQuery(
        "SELECT timestamp_ms FROM observations WHERE id=?", arrayOf(id.toString())
    ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

    fun insertContext(samples: List<ContextSample>) {
        if (samples.isEmpty()) return
        writableDatabase.beginTransaction()
        try { samples.forEach { insertContextRow(writableDatabase, it) }; writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }

    fun insertSensitiveContext(records: List<SensitiveContextRecord>) {
        if (records.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            records.forEach { record ->
                writableDatabase.insertOrThrow("sensitive_context", null, ContentValues().apply {
                    put("timestamp_ms", record.timestampMs)
                    if (record.observationId == null) putNull("observation_id") else put("observation_id", record.observationId)
                    put("is_control", if (record.isControl) 1 else 0)
                    put("source", record.source)
                    put("content_type", record.contentType)
                    put("ciphertext_base64", record.ciphertextBase64)
                    put("iv_base64", record.ivBase64)
                    put("key_alias", record.keyAlias)
                    put("capture_id", record.captureId)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun insertSession(session: CaptureSession) {
        writableDatabase.insertOrThrow("capture_sessions", null, ContentValues().apply {
            put("id", session.id)
            put("type", session.type.name)
            put("started_at_ms", session.startedAtMs)
            if (session.endedAtMs == null) putNull("ended_at_ms") else put("ended_at_ms", session.endedAtMs)
            put("identity_hash", session.identityHash)
            put("status", session.status.name)
            put("metadata", session.metadata)
        })
    }

    fun insertSessionEvents(events: List<SessionEvent>) {
        if (events.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            events.forEach { event ->
                writableDatabase.insertOrThrow("session_events", null, ContentValues().apply {
                    put("timestamp_ms", event.timestampMs)
                    put("session_id", event.sessionId)
                    put("event_type", event.eventType)
                    if (event.severity == null) putNull("severity") else put("severity", event.severity)
                    put("text", event.text)
                    put("metadata", event.metadata)
                })
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun sessionEvents(sessionId: String? = null, limit: Int = 100_000): List<SessionEvent> {
        val where = if (sessionId == null) "" else " WHERE session_id=?"
        val args = if (sessionId == null) arrayOf(limit.toString()) else arrayOf(sessionId, limit.toString())
        val out = mutableListOf<SessionEvent>()
        readableDatabase.rawQuery(
            "SELECT id,timestamp_ms,session_id,event_type,severity,text,metadata FROM session_events$where ORDER BY timestamp_ms LIMIT ?",
            args
        ).use { c -> while (c.moveToNext()) out += SessionEvent(
            id = c.getLong(0),
            timestampMs = c.getLong(1),
            sessionId = c.getString(2),
            eventType = c.getString(3),
            severity = if (c.isNull(4)) null else c.getInt(4),
            text = c.getString(5),
            metadata = c.getString(6)
        ) }
        return out
    }

    fun endSession(
        id: String,
        endedAtMs: Long,
        status: CaptureSessionStatus,
        metadataSuffix: String = ""
    ): Boolean {
        require(status != CaptureSessionStatus.ACTIVE)
        val existing = session(id) ?: return false
        val metadata = listOf(existing.metadata, metadataSuffix).filter(String::isNotBlank).joinToString(";")
        return writableDatabase.update("capture_sessions", ContentValues().apply {
            put("ended_at_ms", endedAtMs)
            put("status", status.name)
            put("metadata", metadata)
        }, "id=? AND status=?", arrayOf(id, CaptureSessionStatus.ACTIVE.name)) > 0
    }

    fun interruptActiveSessions(type: CaptureSessionType, endedAtMs: Long): Int =
        writableDatabase.update("capture_sessions", ContentValues().apply {
            put("ended_at_ms", endedAtMs)
            put("status", CaptureSessionStatus.INTERRUPTED.name)
        }, "type=? AND status=?", arrayOf(type.name, CaptureSessionStatus.ACTIVE.name))

    fun session(id: String): CaptureSession? = querySessions("id=?", arrayOf(id), 1).firstOrNull()

    fun sessions(limit: Int = 10_000): List<CaptureSession> = querySessions(null, emptyArray(), limit)

    private fun querySessions(where: String?, args: Array<String>, limit: Int): List<CaptureSession> {
        val clause = where?.let { " WHERE $it" }.orEmpty()
        val out = mutableListOf<CaptureSession>()
        readableDatabase.rawQuery(
            "SELECT id,type,started_at_ms,ended_at_ms,identity_hash,status,metadata FROM capture_sessions$clause ORDER BY started_at_ms DESC LIMIT ?",
            args + limit.toString()
        ).use { c -> while (c.moveToNext()) out += CaptureSession(
            id = c.getString(0),
            type = CaptureSessionType.valueOf(c.getString(1)),
            startedAtMs = c.getLong(2),
            endedAtMs = if (c.isNull(3)) null else c.getLong(3),
            identityHash = c.getString(4),
            status = CaptureSessionStatus.valueOf(c.getString(5)),
            metadata = c.getString(6)
        ) }
        return out
    }

    fun sensitiveContextForObservation(observationId: Long): List<SensitiveContextRecord> =
        querySensitive("observation_id=?", arrayOf(observationId.toString()))

    fun allSensitiveContext(limit: Int = 100_000): List<SensitiveContextRecord> =
        querySensitive(null, emptyArray(), limit)

    private fun querySensitive(
        where: String?,
        args: Array<String>,
        limit: Int = 100_000
    ): List<SensitiveContextRecord> {
        val out = mutableListOf<SensitiveContextRecord>()
        val clause = where?.let { " WHERE $it" }.orEmpty()
        readableDatabase.rawQuery(
            "SELECT id,timestamp_ms,observation_id,is_control,source,content_type,ciphertext_base64,iv_base64,key_alias,capture_id FROM sensitive_context$clause ORDER BY timestamp_ms LIMIT ?",
            args + limit.toString()
        ).use { c ->
            while (c.moveToNext()) out += SensitiveContextRecord(
                id = c.getLong(0),
                timestampMs = c.getLong(1),
                observationId = if (c.isNull(2)) null else c.getLong(2),
                isControl = c.getInt(3) == 1,
                source = c.getString(4),
                contentType = c.getString(5),
                ciphertextBase64 = c.getString(6),
                ivBase64 = c.getString(7),
                keyAlias = c.getString(8),
                captureId = c.getString(9)
            )
        }
        return out
    }

    private fun insertContextRow(db: SQLiteDatabase, s: ContextSample) {
        db.insertOrThrow("context_samples", null, ContentValues().apply {
            put("timestamp_ms", s.timestampMs); if (s.observationId == null) putNull("observation_id") else put("observation_id", s.observationId)
            put("is_control", if (s.isControl) 1 else 0); put("source", s.source); put("metric", s.metric)
            put("value", s.value); put("unit", s.unit); put("metadata", s.metadata); put("capture_id", s.captureId); put("phase", s.phase.name)
            if (s.sessionId == null) putNull("session_id") else put("session_id", s.sessionId)
        })
    }

    fun insertRolling(samples: List<ContextSample>, retentionMs: Long) {
        if (samples.isEmpty()) return
        val now = System.currentTimeMillis()
        writableDatabase.beginTransaction()
        try {
            samples.forEach { s -> writableDatabase.insertOrThrow("rolling_samples", null, ContentValues().apply {
                put("timestamp_ms", s.timestampMs); put("source", s.source); put("metric", s.metric)
                put("value", s.value); put("unit", s.unit); put("metadata", s.metadata)
            }) }
            writableDatabase.delete("rolling_samples", "timestamp_ms<?", arrayOf((now-retentionMs).toString()))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun captureActivePostWindows(nowMs: Long, postWindowMs: Long): Int {
        val active = mutableListOf<Pair<Long,Long>>()
        readableDatabase.rawQuery(
            "SELECT id,timestamp_ms FROM observations WHERE timestamp_ms<=? AND timestamp_ms>=? AND kind<>?",
            arrayOf(nowMs.toString(), (nowMs-postWindowMs).toString(), ObservationKind.HYPOTHESIS_NOTE.name)
        ).use { c -> while(c.moveToNext()) active += c.getLong(0) to c.getLong(1) }
        return active.sumOf { (id,eventTime) -> copyRollingToObservation(id,eventTime,minOf(nowMs,eventTime+postWindowMs),ContextPhase.POST) }
    }

    fun copyRollingToObservation(observationId: Long, startMs: Long, endMs: Long, phase: ContextPhase): Int {
        require(phase == ContextPhase.PRE || phase == ContextPhase.POST)
        val marker="window=${phase.name.lowercase()}"; val capture="event:$observationId:${phase.name.lowercase()}"
        val sql="""
            INSERT INTO context_samples(timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id,phase)
            SELECT r.timestamp_ms, ?, 0, 'rolling/' || r.source, r.metric, r.value, r.unit,
                   CASE WHEN r.metadata='' THEN ? ELSE r.metadata || ';' || ? END, ?, ?
            FROM rolling_samples r WHERE r.timestamp_ms BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM context_samples c WHERE c.observation_id=? AND c.timestamp_ms=r.timestamp_ms
                AND c.source='rolling/' || r.source AND c.metric=r.metric AND c.phase=?)
        """.trimIndent()
        val st=writableDatabase.compileStatement(sql)
        return try {
            st.bindLong(1,observationId); st.bindString(2,marker); st.bindString(3,marker); st.bindString(4,capture); st.bindString(5,phase.name)
            st.bindLong(6,startMs); st.bindLong(7,endMs); st.bindLong(8,observationId); st.bindString(9,phase.name); st.executeUpdateDelete()
        } finally { st.close() }
    }

    fun copyRollingToControl(captureId: String, startMs: Long, endMs: Long): Int {
        val sql="""
            INSERT INTO context_samples(timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id,phase)
            SELECT r.timestamp_ms,NULL,1,'rolling/' || r.source,r.metric,r.value,r.unit,
                   CASE WHEN r.metadata='' THEN 'window=control' ELSE r.metadata || ';window=control' END,?,'CONTROL'
            FROM rolling_samples r WHERE r.timestamp_ms BETWEEN ? AND ?
              AND NOT EXISTS (SELECT 1 FROM context_samples c WHERE c.capture_id=? AND c.timestamp_ms=r.timestamp_ms
                AND c.source='rolling/' || r.source AND c.metric=r.metric AND c.phase='CONTROL')
        """.trimIndent()
        val st=writableDatabase.compileStatement(sql)
        return try { st.bindString(1,captureId); st.bindLong(2,startMs); st.bindLong(3,endMs); st.bindString(4,captureId); st.executeUpdateDelete() }
        finally { st.close() }
    }

    fun rollingStatus(): Triple<Int,Long?,Long?> = readableDatabase.rawQuery(
        "SELECT COUNT(*),MIN(timestamp_ms),MAX(timestamp_ms) FROM rolling_samples",null
    ).use { c -> if(!c.moveToFirst()) Triple(0,null,null) else Triple(c.getInt(0),if(c.isNull(1))null else c.getLong(1),if(c.isNull(2))null else c.getLong(2)) }

    fun observations(limit:Int=250):List<Observation>{
        val out=mutableListOf<Observation>()
        readableDatabase.rawQuery(
            "SELECT id,timestamp_ms,kind,label,note,severity,confidence,origin,external_event_id,vibe_rating,egress FROM observations ORDER BY timestamp_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while(c.moveToNext()) out += Observation(
            id=c.getLong(0),timestampMs=c.getLong(1),kind=ObservationKind.valueOf(c.getString(2)),label=c.getString(3),note=c.getString(4),
            severity=if(c.isNull(5))null else c.getInt(5),confidence=c.getInt(6),
            origin=runCatching{ObservationOrigin.valueOf(c.getString(7))}.getOrDefault(ObservationOrigin.ANDROID),
            externalEventId=if(c.isNull(8))null else c.getString(8),
            vibeRating=if(c.isNull(9))null else c.getInt(9),egress=c.getInt(10)==1
        ) }
        return out
    }

    fun contextForObservation(observationId:Long):List<ContextSample> = queryContext(
        "SELECT id,timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id,phase,session_id FROM context_samples WHERE observation_id=? ORDER BY timestamp_ms",
        arrayOf(observationId.toString())
    )

    private data class CohortPredicate(val sql: String, val args: List<String>)

    private fun cohortPredicate(cohortId: String, observationAlias: String = "o"): CohortPredicate = when (cohortId) {
        AnalysisCohort.EGRESS -> CohortPredicate("$observationAlias.egress=1", emptyList())
        AnalysisCohort.BAD_VIBE_STAYED -> CohortPredicate(
            "$observationAlias.kind=? AND $observationAlias.vibe_rating>=3 AND $observationAlias.egress=0",
            listOf(ObservationKind.VIBE.name)
        )
        else -> {
            require(cohortId.startsWith(AnalysisCohort.LABEL_PREFIX)) { "Unknown analysis cohort" }
            CohortPredicate(
                "$observationAlias.label=? COLLATE NOCASE AND $observationAlias.kind<>?",
                listOf(cohortId.removePrefix(AnalysisCohort.LABEL_PREFIX), ObservationKind.HYPOTHESIS_NOTE.name)
            )
        }
    }

    fun eventFeatureCaptures(label:String,metric:String):List<TimedCaptureValue> =
        eventFeatureCapturesForCohort(AnalysisCohort.labelId(label), metric)

    fun eventFeatureCapturesForCohort(cohortId:String,metric:String):List<TimedCaptureValue>{
        val predicate = cohortPredicate(cohortId)
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            SELECT o.timestamp_ms,AVG(cs.value) FROM context_samples cs JOIN observations o ON o.id=cs.observation_id
            WHERE ${predicate.sql} AND cs.metric=? AND cs.is_control=0 AND cs.phase<>'POST' GROUP BY o.id ORDER BY o.timestamp_ms
        """.trimIndent(),(predicate.args + metric).toTypedArray()).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun eventFeatureValues(label:String,metric:String):List<Double> = eventFeatureCaptures(label,metric).map{it.value}

    fun controlFeatureCaptures(metric:String):List<TimedCaptureValue>{
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            SELECT MAX(timestamp_ms),AVG(value) FROM context_samples WHERE is_control=1 AND metric=? AND phase='CONTROL'
            GROUP BY CASE WHEN capture_id='' THEN CAST(timestamp_ms AS TEXT) ELSE capture_id END ORDER BY MIN(timestamp_ms)
        """.trimIndent(),arrayOf(metric)).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun controlFeatureValues(metric:String):List<Double> = controlFeatureCaptures(metric).map{it.value}

    fun eventLagFeatureCaptures(label:String,metric:String,fromBeforeMs:Long,toBeforeMs:Long):List<TimedCaptureValue> =
        eventLagFeatureCapturesForCohort(AnalysisCohort.labelId(label), metric, fromBeforeMs, toBeforeMs)

    fun eventLagFeatureCapturesForCohort(cohortId:String,metric:String,fromBeforeMs:Long,toBeforeMs:Long):List<TimedCaptureValue>{
        val predicate = cohortPredicate(cohortId)
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            SELECT o.timestamp_ms,AVG(cs.value) FROM context_samples cs JOIN observations o ON o.id=cs.observation_id
            WHERE ${predicate.sql} AND cs.metric=? AND cs.phase='PRE'
              AND cs.timestamp_ms>=o.timestamp_ms-? AND cs.timestamp_ms<o.timestamp_ms-? GROUP BY o.id ORDER BY o.timestamp_ms
        """.trimIndent(),(predicate.args + listOf(metric,toBeforeMs.toString(),fromBeforeMs.toString())).toTypedArray()).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun eventLagFeatureValues(label:String,metric:String,fromBeforeMs:Long,toBeforeMs:Long):List<Double> = eventLagFeatureCaptures(label,metric,fromBeforeMs,toBeforeMs).map{it.value}

    fun controlLagFeatureCaptures(metric:String,fromBeforeMs:Long,toBeforeMs:Long):List<TimedCaptureValue>{
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            WITH anchors AS (SELECT capture_id,MAX(timestamp_ms) anchor FROM context_samples WHERE is_control=1 AND phase='CONTROL' AND capture_id<>'' GROUP BY capture_id)
            SELECT a.anchor,AVG(c.value) FROM context_samples c JOIN anchors a ON a.capture_id=c.capture_id
            WHERE c.metric=? AND c.phase='CONTROL' AND c.source LIKE 'rolling/%'
              AND c.timestamp_ms>=a.anchor-? AND c.timestamp_ms<a.anchor-? GROUP BY c.capture_id ORDER BY a.anchor
        """.trimIndent(),arrayOf(metric,toBeforeMs.toString(),fromBeforeMs.toString())).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun controlLagFeatureValues(metric:String,fromBeforeMs:Long,toBeforeMs:Long):List<Double> = controlLagFeatureCaptures(metric,fromBeforeMs,toBeforeMs).map{it.value}

    fun eventBeforeDeltaCaptures(label:String,metric:String):List<TimedCaptureValue> =
        eventBeforeDeltaCapturesForCohort(AnalysisCohort.labelId(label), metric)

    fun eventBeforeDeltaCapturesForCohort(cohortId:String,metric:String):List<TimedCaptureValue>{
        val predicate = cohortPredicate(cohortId)
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            SELECT o.timestamp_ms,AVG(CASE WHEN cs.timestamp_ms>=o.timestamp_ms-600000 THEN cs.value END)-
                   AVG(CASE WHEN cs.timestamp_ms<o.timestamp_ms-1200000 THEN cs.value END)
            FROM context_samples cs JOIN observations o ON o.id=cs.observation_id
            WHERE ${predicate.sql} AND cs.metric=? AND cs.phase='PRE'
              AND cs.timestamp_ms>=o.timestamp_ms-1800000 AND cs.timestamp_ms<o.timestamp_ms
            GROUP BY o.id HAVING COUNT(CASE WHEN cs.timestamp_ms>=o.timestamp_ms-600000 THEN 1 END)>0
              AND COUNT(CASE WHEN cs.timestamp_ms<o.timestamp_ms-1200000 THEN 1 END)>0 ORDER BY o.timestamp_ms
        """.trimIndent(),(predicate.args + metric).toTypedArray()).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun eventBeforeDeltaValues(label:String,metric:String):List<Double> = eventBeforeDeltaCaptures(label,metric).map{it.value}

    fun controlBeforeDeltaCaptures(metric:String):List<TimedCaptureValue>{
        val out=mutableListOf<TimedCaptureValue>()
        readableDatabase.rawQuery("""
            WITH anchors AS (SELECT capture_id,MAX(timestamp_ms) anchor FROM context_samples WHERE is_control=1 AND phase='CONTROL' AND capture_id<>'' GROUP BY capture_id)
            SELECT a.anchor,AVG(CASE WHEN c.timestamp_ms>=a.anchor-600000 THEN c.value END)-
                   AVG(CASE WHEN c.timestamp_ms<a.anchor-1200000 THEN c.value END)
            FROM context_samples c JOIN anchors a ON a.capture_id=c.capture_id
            WHERE c.metric=? AND c.phase='CONTROL' AND c.source LIKE 'rolling/%'
              AND c.timestamp_ms>=a.anchor-1800000 AND c.timestamp_ms<a.anchor
            GROUP BY c.capture_id HAVING COUNT(CASE WHEN c.timestamp_ms>=a.anchor-600000 THEN 1 END)>0
              AND COUNT(CASE WHEN c.timestamp_ms<a.anchor-1200000 THEN 1 END)>0 ORDER BY a.anchor
        """.trimIndent(),arrayOf(metric)).use{c->while(c.moveToNext())out+=TimedCaptureValue(c.getLong(0),c.getDouble(1))}
        return out
    }
    fun controlBeforeDeltaValues(metric:String):List<Double> = controlBeforeDeltaCaptures(metric).map{it.value}

    fun metricsForLabel(label:String):List<String> = metricsForCohort(AnalysisCohort.labelId(label))

    fun metricsForCohort(cohortId:String):List<String>{
        val predicate = cohortPredicate(cohortId)
        val out=mutableListOf<String>()
        readableDatabase.rawQuery("""
            SELECT DISTINCT cs.metric FROM context_samples cs JOIN observations o ON o.id=cs.observation_id
            WHERE ${predicate.sql} AND cs.phase<>'POST' ORDER BY cs.metric
        """.trimIndent(),predicate.args.toTypedArray()).use{c->while(c.moveToNext())out+=c.getString(0)}
        return out
    }

    fun analysisCohorts():List<AnalysisCohort>{
        val out=mutableListOf<AnalysisCohort>()
        readableDatabase.rawQuery("SELECT COUNT(*) FROM observations WHERE egress=1",emptyArray()).use { c ->
            if (c.moveToFirst() && c.getInt(0)>0) out += AnalysisCohort(AnalysisCohort.EGRESS,"Egress · bailed",c.getInt(0))
        }
        readableDatabase.rawQuery("SELECT COUNT(*) FROM observations WHERE kind=? AND vibe_rating>=3 AND egress=0",arrayOf(ObservationKind.VIBE.name)).use { c ->
            if (c.moveToFirst() && c.getInt(0)>0) out += AnalysisCohort(AnalysisCohort.BAD_VIBE_STAYED,"Bad vibes · stayed",c.getInt(0))
        }
        readableDatabase.rawQuery("SELECT label,COUNT(*) FROM observations WHERE kind<>? AND egress=0 GROUP BY lower(label) ORDER BY COUNT(*) DESC,label",arrayOf(ObservationKind.HYPOTHESIS_NOTE.name)).use { c ->
            while(c.moveToNext()) out += AnalysisCohort(AnalysisCohort.labelId(c.getString(0)),c.getString(0),c.getInt(1))
        }
        return out
    }

    fun devicePresenceCapturesForCohort(cohortId: String): Map<String, Pair<List<TimedCaptureValue>, List<TimedCaptureValue>>> {
        data class Window(val timestampMs: Long, val available: MutableSet<String> = mutableSetOf(), val hashes: MutableSet<String> = mutableSetOf())
        fun applyRow(window: Window, metric: String, metadata: String) {
            when (metric) {
                "bt_nearby_count" -> window.available += "bluetooth"
                "wifi_visible_count" -> window.available += "wifi"
                "bt_device_rssi_dbm" -> metadataValue(metadata,"device_hash")?.let { window.hashes += "bluetooth:$it" }
                "wifi_ap_rssi_dbm" -> metadataValue(metadata,"bssid_hash")?.let { window.hashes += "wifi:$it" }
            }
        }

        val predicate = cohortPredicate(cohortId)
        val eventWindows = linkedMapOf<Long,Window>()
        readableDatabase.rawQuery("""
            SELECT o.id,o.timestamp_ms,cs.metric,cs.metadata FROM observations o JOIN context_samples cs ON cs.observation_id=o.id
            WHERE ${predicate.sql} AND cs.is_control=0 AND cs.phase<>'POST'
              AND cs.metric IN ('bt_nearby_count','bt_device_rssi_dbm','wifi_visible_count','wifi_ap_rssi_dbm')
            ORDER BY o.timestamp_ms
        """.trimIndent(),predicate.args.toTypedArray()).use { c -> while(c.moveToNext()) {
            val window=eventWindows.getOrPut(c.getLong(0)){Window(c.getLong(1))}; applyRow(window,c.getString(2),c.getString(3))
        } }

        val controlWindows = linkedMapOf<String,Window>()
        readableDatabase.rawQuery("""
            SELECT capture_id,timestamp_ms,metric,metadata FROM context_samples
            WHERE is_control=1 AND phase='CONTROL'
              AND metric IN ('bt_nearby_count','bt_device_rssi_dbm','wifi_visible_count','wifi_ap_rssi_dbm')
            ORDER BY timestamp_ms
        """.trimIndent(),emptyArray()).use { c -> while(c.moveToNext()) {
            val key=c.getString(0).ifBlank{"timestamp:${c.getLong(1)}"}
            val current=controlWindows[key]
            val window=if(current==null) Window(c.getLong(1)).also{controlWindows[key]=it}
                else if(c.getLong(1)>current.timestampMs) current.copy(timestampMs=c.getLong(1)).also{controlWindows[key]=it} else current
            applyRow(window,c.getString(2),c.getString(3))
        } }

        val candidates=(eventWindows.values.flatMap{it.hashes}+controlWindows.values.flatMap{it.hashes}).toSortedSet()
        return candidates.associateWith { candidate ->
            val channel=candidate.substringBefore(':')
            eventWindows.values.filter{channel in it.available}.map{TimedCaptureValue(it.timestampMs,if(candidate in it.hashes)1.0 else 0.0)} to
                controlWindows.values.filter{channel in it.available}.map{TimedCaptureValue(it.timestampMs,if(candidate in it.hashes)1.0 else 0.0)}
        }.mapKeys { (candidate, _) ->
            val channel=candidate.substringBefore(':')
            val hash=candidate.substringAfter(':')
            "$channel device presence · $hash"
        }
    }

    private fun metadataValue(metadata:String,key:String):String? = metadata.split(';').firstOrNull{it.startsWith("$key=")}?.substringAfter('=')?.takeIf{it.isNotBlank()}

    fun labels():List<Pair<String,Int>>{
        val out=mutableListOf<Pair<String,Int>>()
        readableDatabase.rawQuery("SELECT label,COUNT(*) FROM observations WHERE kind<>? GROUP BY lower(label) ORDER BY COUNT(*) DESC,label",arrayOf(ObservationKind.HYPOTHESIS_NOTE.name)).use{c->while(c.moveToNext())out+=c.getString(0) to c.getInt(1)}
        return out
    }

    fun allContext(limit:Int=5000):List<ContextSample> = queryContext(
        "SELECT id,timestamp_ms,observation_id,is_control,source,metric,value,unit,metadata,capture_id,phase,session_id FROM context_samples ORDER BY timestamp_ms DESC LIMIT ?",
        arrayOf(limit.toString())
    )

    fun registerMediaAsset(asset: MediaAsset): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val existing = mediaAsset(asset.id)
            if (existing?.status == MediaStatus.PURGED) return false
            val values = ContentValues().apply {
                put("observation_id", asset.observationId)
                put("media_type", asset.mediaType.name)
                put("stream_id", asset.streamId)
                put("created_at_ms", asset.createdAtMs)
                put("retention_until_ms", asset.retentionUntilMs)
                put("keep_forever", if (existing?.keepForever == true || asset.keepForever) 1 else 0)
                put("status", MediaStatus.ACTIVE.name)
                put("ciphertext_relative_path", asset.ciphertextRelativePath)
                put("manifest_relative_path", asset.manifestRelativePath)
                put("key_alias", asset.keyAlias)
                put("ciphertext_sha256", asset.ciphertextSha256)
                put("size_bytes", asset.sizeBytes)
            }
            if (existing == null) {
                values.put("id", asset.id)
                database.insertOrThrow("media_assets", null, values)
            } else {
                database.update("media_assets", values, "id=?", arrayOf(asset.id))
            }
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    fun mediaAsset(id: String): MediaAsset? = queryMedia("id=?", arrayOf(id), 1).firstOrNull()

    fun mediaAssets(observationId: Long? = null, includePurged: Boolean = false, limit: Int = 1_000): List<MediaAsset> {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()
        observationId?.let { clauses += "observation_id=?"; args += it.toString() }
        if (!includePurged) clauses += "status='ACTIVE'"
        return queryMedia(clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "), args.toTypedArray(), limit)
    }

    private fun queryMedia(where: String?, args: Array<String>, limit: Int): List<MediaAsset> {
        val clause = where?.let { " WHERE $it" }.orEmpty()
        val out = mutableListOf<MediaAsset>()
        readableDatabase.rawQuery(
            "SELECT id,observation_id,media_type,stream_id,created_at_ms,retention_until_ms,keep_forever,status,ciphertext_relative_path,manifest_relative_path,key_alias,ciphertext_sha256,size_bytes FROM media_assets$clause ORDER BY created_at_ms DESC LIMIT ?",
            args + limit.toString()
        ).use { c -> while (c.moveToNext()) out += MediaAsset(
            id = c.getString(0), observationId = c.getLong(1), mediaType = MediaType.valueOf(c.getString(2)),
            streamId = c.getString(3), createdAtMs = c.getLong(4), retentionUntilMs = c.getLong(5),
            keepForever = c.getInt(6) == 1, status = MediaStatus.valueOf(c.getString(7)),
            ciphertextRelativePath = c.getString(8), manifestRelativePath = c.getString(9),
            keyAlias = c.getString(10), ciphertextSha256 = c.getString(11), sizeBytes = c.getLong(12)
        ) }
        return out
    }

    fun setMediaKeepForever(id: String, keepForever: Boolean): Boolean = writableDatabase.update(
        "media_assets", ContentValues().apply { put("keep_forever", if (keepForever) 1 else 0) },
        "id=? AND status='ACTIVE'", arrayOf(id)
    ) > 0

    fun markMediaPurged(id: String, purgedAtMs: Long, reason: String, bytesDeleted: Long): Boolean {
        val asset = mediaAsset(id) ?: return false
        if (asset.status != MediaStatus.ACTIVE) return false
        val database = writableDatabase
        database.beginTransaction()
        try {
            val updated = database.update(
                "media_assets", ContentValues().apply { put("status", MediaStatus.PURGED.name); put("size_bytes", 0L) },
                "id=? AND status='ACTIVE'", arrayOf(id)
            )
            if (updated == 0) return false
            database.insertOrThrow("purge_ledger", null, ContentValues().apply {
                put("media_id", id); put("observation_id", asset.observationId); put("media_type", asset.mediaType.name)
                put("purged_at_ms", purgedAtMs); put("reason", reason); put("bytes_deleted", bytesDeleted)
                put("derived_metrics_retained", 1)
            })
            database.setTransactionSuccessful()
            return true
        } finally { database.endTransaction() }
    }

    fun purgeLedger(limit: Int = 1_000): List<PurgeLedgerEntry> {
        val out = mutableListOf<PurgeLedgerEntry>()
        readableDatabase.rawQuery(
            "SELECT id,media_id,observation_id,media_type,purged_at_ms,reason,bytes_deleted,derived_metrics_retained FROM purge_ledger ORDER BY purged_at_ms DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out += PurgeLedgerEntry(
            id = c.getLong(0), mediaId = c.getString(1), observationId = c.getLong(2), mediaType = MediaType.valueOf(c.getString(3)),
            purgedAtMs = c.getLong(4), reason = c.getString(5), bytesDeleted = c.getLong(6), derivedMetricsRetained = c.getInt(7) == 1
        ) }
        return out
    }

    fun deleteObservation(id:Long):Boolean = writableDatabase.delete("observations","id=?",arrayOf(id.toString()))>0

    fun deleteAllData(){
        writableDatabase.beginTransaction()
        try { writableDatabase.delete("sensitive_context",null,null); writableDatabase.delete("session_events",null,null); writableDatabase.delete("context_samples",null,null); writableDatabase.delete("rolling_samples",null,null); writableDatabase.delete("hypothesis_evaluations",null,null); writableDatabase.delete("hypotheses",null,null); writableDatabase.delete("analysis_views",null,null); writableDatabase.delete("media_assets",null,null); writableDatabase.delete("purge_ledger",null,null); writableDatabase.delete("observations",null,null); writableDatabase.delete("capture_sessions",null,null); writableDatabase.setTransactionSuccessful() }
        finally { writableDatabase.endTransaction() }
    }

    private fun queryContext(sql:String,args:Array<String>):List<ContextSample>{
        val out=mutableListOf<ContextSample>()
        readableDatabase.rawQuery(sql,args).use{c->while(c.moveToNext())out+=ContextSample(
            id=c.getLong(0),timestampMs=c.getLong(1),observationId=if(c.isNull(2))null else c.getLong(2),isControl=c.getInt(3)==1,
            source=c.getString(4),metric=c.getString(5),value=c.getDouble(6),unit=c.getString(7),metadata=c.getString(8),captureId=c.getString(9),
            phase=runCatching{ContextPhase.valueOf(c.getString(10))}.getOrDefault(if(c.getInt(3)==1)ContextPhase.CONTROL else ContextPhase.INSTANT),
            sessionId=if(c.columnCount<=11||c.isNull(11))null else c.getString(11)
        )}
        return out
    }
}
