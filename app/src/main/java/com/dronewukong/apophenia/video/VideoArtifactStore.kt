package com.dronewukong.apophenia.video

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dronewukong.apophenia.audio.AudioArtifactCrypto
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.media.AvRetentionSettings
import com.dronewukong.apophenia.media.MediaRetentionManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONArray
import org.json.JSONObject

class VideoArtifactStore(private val context: Context) {
    data class Artifact(val fileId: String, val ciphertextSha256: String, val retained: Boolean)

    fun persist(eventId: Long, eventAtMs: Long, streamId: String, lensTag: String, pre: List<VideoFrame>, post: List<VideoFrame>, retentionDays: Int = AvRetentionSettings.days(context)): Artifact {
        val directory = File(context.filesDir, "av/video").apply { mkdirs() }
        val safeStream = token(streamId)
        val fileId = "video-$eventId-$eventAtMs-$safeStream"
        val keyAlias = "apophenia_video_event_${eventId}_${eventAtMs}_$safeStream"
        val frameArchive = archive(pre, post)
        val encrypted = AudioArtifactCrypto.encrypt(eventKey(keyAlias), frameArchive)
        frameArchive.fill(0)
        val target = File(directory, "$fileId.mjpeg.aesgcm")
        val temporary = File(directory, "$fileId.tmp")
        temporary.writeBytes(encrypted.ciphertext)
        if (target.exists()) check(target.delete())
        check(temporary.renameTo(target)) { "Could not commit encrypted video stream" }
        val sha = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext).joinToString("") { "%02x".format(it) }
        val manifestFile = File(directory, "$fileId.json")
        val retentionUntilMs = eventAtMs + retentionDays * 86_400_000L
        manifestFile.writeText(
            JSONObject().put("schema", "apophenia.video.mjpeg.v1")
                .put("event_id", eventId).put("event_at_ms", eventAtMs)
                .put("stream", safeStream).put("lens", token(lensTag))
                .put("pre_frames", pre.size).put("post_frames", post.size)
                .put("post_excluded_from_predictors", true)
                .put("cipher", "AES-256-GCM").put("iv_base64", Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
                .put("key_alias", keyAlias).put("ciphertext_sha256", sha)
                .put("retention_until_ms", retentionUntilMs).toString(2)
        )
        val retained = MediaRetentionManager(context).register(
            MediaAsset(
                id = fileId, observationId = eventId, mediaType = MediaType.VIDEO, streamId = safeStream,
                createdAtMs = eventAtMs, retentionUntilMs = retentionUntilMs,
                ciphertextRelativePath = target.relativeTo(context.filesDir).invariantSeparatorsPath,
                manifestRelativePath = manifestFile.relativeTo(context.filesDir).invariantSeparatorsPath,
                keyAlias = keyAlias, ciphertextSha256 = sha, sizeBytes = target.length()
            )
        )
        return Artifact(fileId, sha, retained)
    }

    private fun archive(pre: List<VideoFrame>, post: List<VideoFrame>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            val index = JSONArray()
            (pre.map { "pre" to it } + post.map { "post" to it }).forEachIndexed { number, (phase, frame) ->
                val name = "${phase}_${number.toString().padStart(4, '0')}.jpg"
                zip.putNextEntry(ZipEntry(name))
                zip.write(frame.jpeg)
                zip.closeEntry()
                index.put(JSONObject().put("file", name).put("timestamp_ms", frame.timestampMs).put("phase", phase))
            }
            zip.putNextEntry(ZipEntry("frames.json"))
            zip.write(index.toString().encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun eventKey(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }

    private fun token(value: String): String = value.map { if (it.isLetterOrDigit() || it in "_-.") it else '_' }.joinToString("").take(80)
}
