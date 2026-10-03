package com.dronewukong.apophenia.media

import android.content.Context
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaStatus
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationStore
import java.io.File
import java.security.KeyStore

object AvRetentionSettings {
    private const val PREFS = "av_retention"
    private const val DAYS = "days"
    const val DEFAULT_DAYS = 14

    fun days(context: Context): Int = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getInt(DAYS, DEFAULT_DAYS).coerceIn(1, 3_650)

    fun setDays(context: Context, days: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(DAYS, days.coerceIn(1, 3_650)).apply()
    }
}

/** Serializes artifact finalization and destructive retention work inside this process. */
object MediaRetentionCoordinator {
    val lock = Any()
}

class MediaRetentionManager(
    context: Context,
    private val db: ObservationDb = ObservationStore.repository(context).db(),
    private val root: File = context.filesDir,
    private val keyDeleter: (String) -> Unit = ::deleteAndroidKey
) {
    fun register(asset: MediaAsset): Boolean = synchronized(MediaRetentionCoordinator.lock) {
        val retained = db.registerMediaAsset(asset)
        if (!retained) deleteFilesAndKey(asset, requireFilesDeleted = false)
        retained
    }

    fun setKeepForever(assetId: String, keepForever: Boolean): Boolean = synchronized(MediaRetentionCoordinator.lock) {
        db.setMediaKeepForever(assetId, keepForever)
    }

    fun setEventKeepForever(observationId: Long, keepForever: Boolean): Int = synchronized(MediaRetentionCoordinator.lock) {
        db.mediaAssets(observationId).count { db.setMediaKeepForever(it.id, keepForever) }
    }

    fun scrubEvent(observationId: Long, reason: String = "USER_SCRUB"): Int = synchronized(MediaRetentionCoordinator.lock) {
        db.mediaAssets(observationId).count { purge(it, reason, System.currentTimeMillis()) }
    }

    fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int = synchronized(MediaRetentionCoordinator.lock) {
        db.mediaAssets(limit = 100_000).filter { !it.keepForever && it.retentionUntilMs <= nowMs }
            .count { purge(it, "RETENTION_EXPIRED", nowMs) }
    }

    fun scrubAll(reason: String = "DELETE_ALL"): Int = synchronized(MediaRetentionCoordinator.lock) {
        db.mediaAssets(limit = 100_000).count { purge(it, reason, System.currentTimeMillis()) }
    }

    private fun purge(asset: MediaAsset, reason: String, nowMs: Long): Boolean {
        if (asset.status != MediaStatus.ACTIVE) return false
        val bytes = safeFile(asset.ciphertextRelativePath)?.takeIf { it.isFile }?.length() ?: 0L
        if (!deleteFilesAndKey(asset, requireFilesDeleted = true)) return false
        return db.markMediaPurged(asset.id, nowMs, reason, bytes)
    }

    private fun deleteFilesAndKey(asset: MediaAsset, requireFilesDeleted: Boolean): Boolean {
        val filesDeleted = listOf(asset.ciphertextRelativePath, asset.manifestRelativePath).all { relative ->
            val file = safeFile(relative) ?: return@all false
            !file.exists() || file.delete()
        }
        if (!filesDeleted && requireFilesDeleted) return false
        runCatching { keyDeleter(asset.keyAlias) }
        return filesDeleted
    }

    internal fun safeFile(relativePath: String): File? {
        if (relativePath.isBlank() || File(relativePath).isAbsolute) return null
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, relativePath).canonicalFile
        val prefix = canonicalRoot.path.trimEnd(File.separatorChar) + File.separator
        return candidate.takeIf { it.path.startsWith(prefix) }
    }

    companion object {
        private fun deleteAndroidKey(alias: String) {
            if (alias.isBlank()) return
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
