package com.dronewukong.apophenia.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import com.dronewukong.apophenia.audio.AudioArtifactCrypto
import com.dronewukong.apophenia.audio.EncryptedAudio
import com.dronewukong.apophenia.data.MediaAsset
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import javax.crypto.SecretKey
import org.json.JSONObject

data class AudioEvidence(val pcm: ByteArray, val sampleRateHz: Int, val preBytes: Int, val postBytes: Int)
data class VideoEvidenceFrame(val name: String, val phase: String, val jpeg: ByteArray)

/** Decrypts only into memory; no plaintext media is written to disk. */
class MediaEvidenceReader(private val context: Context) {
    fun loadAudio(asset: MediaAsset): AudioEvidence {
        val manifest = manifest(asset)
        val plaintext = decrypt(asset, manifest)
        return AudioEvidence(
            pcm = plaintext,
            sampleRateHz = manifest.getInt("sample_rate_hz"),
            preBytes = manifest.getInt("pre_bytes"),
            postBytes = manifest.getInt("post_bytes")
        )
    }

    fun loadVideo(asset: MediaAsset): List<VideoEvidenceFrame> {
        val manifest = manifest(asset)
        val plaintext = decrypt(asset, manifest)
        return try {
            val frames = mutableListOf<VideoEvidenceFrame>()
            ZipInputStream(ByteArrayInputStream(plaintext)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory && entry.name.matches(Regex("(pre|post)_\\d{4}\\.jpg"))) {
                        val bytes = zip.readBytes()
                        check(bytes.size <= 5_000_000) { "Evidence frame exceeds the 5 MB safety bound" }
                        frames += VideoEvidenceFrame(entry.name, entry.name.substringBefore('_'), bytes)
                    }
                    zip.closeEntry()
                    check(frames.size <= 300) { "Evidence archive exceeds the 300-frame safety bound" }
                }
            }
            frames
        } finally { plaintext.fill(0) }
    }

    fun playAudio(evidence: AudioEvidence): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(evidence.sampleRateHz).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(evidence.pcm.size.coerceAtLeast(AudioTrack.getMinBufferSize(evidence.sampleRateHz, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        check(track.write(evidence.pcm, 0, evidence.pcm.size) == evidence.pcm.size) { "Could not load decrypted audio into the in-memory player" }
        track.play()
        return track
    }

    private fun manifest(asset: MediaAsset): JSONObject {
        val file = MediaRetentionManager(context).safeFile(asset.manifestRelativePath)
            ?: error("Unsafe media manifest path")
        check(file.isFile) { "Media manifest is missing" }
        return JSONObject(file.readText())
    }

    private fun decrypt(asset: MediaAsset, manifest: JSONObject): ByteArray {
        val file = MediaRetentionManager(context).safeFile(asset.ciphertextRelativePath)
            ?: error("Unsafe media ciphertext path")
        check(file.isFile) { "Encrypted media is missing" }
        val ciphertext = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(ciphertext).joinToString("") { "%02x".format(it) }
        check(digest == asset.ciphertextSha256 && digest == manifest.getString("ciphertext_sha256")) { "Encrypted media hash mismatch" }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = store.getKey(asset.keyAlias, null) as? SecretKey ?: error("Media decryption key is missing")
        return AudioArtifactCrypto.decrypt(
            key,
            EncryptedAudio(ciphertext, Base64.decode(manifest.getString("iv_base64"), Base64.NO_WRAP))
        )
    }
}
