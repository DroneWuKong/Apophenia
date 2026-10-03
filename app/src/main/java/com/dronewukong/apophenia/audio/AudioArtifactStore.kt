package com.dronewukong.apophenia.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.media.AvRetentionSettings
import com.dronewukong.apophenia.media.MediaRetentionManager
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class EncryptedAudio(val ciphertext: ByteArray, val iv: ByteArray)

object AudioArtifactCrypto {
    fun encrypt(key: SecretKey, plaintext: ByteArray): EncryptedAudio {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return EncryptedAudio(cipher.doFinal(plaintext), cipher.iv)
    }

    fun decrypt(key: SecretKey, encrypted: EncryptedAudio): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.iv))
        return cipher.doFinal(encrypted.ciphertext)
    }
}

class AudioArtifactStore(private val context: Context) {
    data class Artifact(val fileId: String, val keyAlias: String, val ciphertextSha256: String, val retained: Boolean)

    fun persist(
        eventId: Long,
        eventAtMs: Long,
        sampleRateHz: Int,
        prePcm: ByteArray,
        postPcm: ByteArray,
        retentionDays: Int = AvRetentionSettings.days(context)
    ): Artifact {
        val directory = File(context.filesDir, "av/audio").apply { mkdirs() }
        val fileId = "audio-$eventId-$eventAtMs"
        val keyAlias = "apophenia_audio_event_${eventId}_$eventAtMs"
        val key = eventKey(keyAlias)
        val combined = ByteArray(prePcm.size + postPcm.size).also {
            prePcm.copyInto(it)
            postPcm.copyInto(it, prePcm.size)
        }
        val encrypted = AudioArtifactCrypto.encrypt(key, combined)
        val ciphertextFile = File(directory, "$fileId.pcm.aesgcm")
        val temporary = File(directory, "$fileId.tmp")
        temporary.writeBytes(encrypted.ciphertext)
        if (ciphertextFile.exists()) check(ciphertextFile.delete()) { "Could not replace encrypted audio artifact" }
        check(temporary.renameTo(ciphertextFile)) { "Could not commit encrypted audio artifact" }
        val sha = MessageDigest.getInstance("SHA-256").digest(encrypted.ciphertext).joinToString("") { "%02x".format(it) }
        val manifestFile = File(directory, "$fileId.json")
        val retentionUntilMs = eventAtMs + retentionDays * 86_400_000L
        manifestFile.writeText(
            JSONObject()
                .put("schema", "apophenia.audio.pcm.v1")
                .put("event_id", eventId)
                .put("event_at_ms", eventAtMs)
                .put("sample_rate_hz", sampleRateHz)
                .put("channels", 1)
                .put("encoding", "PCM_16BIT_LE")
                .put("pre_bytes", prePcm.size)
                .put("post_bytes", postPcm.size)
                .put("post_excluded_from_predictors", true)
                .put("cipher", "AES-256-GCM")
                .put("iv_base64", Base64.encodeToString(encrypted.iv, Base64.NO_WRAP))
                .put("key_alias", keyAlias)
                .put("ciphertext_sha256", sha)
                .put("retention_until_ms", retentionUntilMs)
                .toString(2)
        )
        combined.fill(0)
        val retained = MediaRetentionManager(context).register(
            MediaAsset(
                id = fileId, observationId = eventId, mediaType = MediaType.AUDIO, streamId = "microphone",
                createdAtMs = eventAtMs, retentionUntilMs = retentionUntilMs,
                ciphertextRelativePath = ciphertextFile.relativeTo(context.filesDir).invariantSeparatorsPath,
                manifestRelativePath = manifestFile.relativeTo(context.filesDir).invariantSeparatorsPath,
                keyAlias = keyAlias, ciphertextSha256 = sha, sizeBytes = ciphertextFile.length()
            )
        )
        return Artifact(fileId, keyAlias, sha, retained)
    }

    private fun eventKey(alias: String): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
