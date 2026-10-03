package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.data.MediaAsset
import com.dronewukong.apophenia.data.MediaType
import com.dronewukong.apophenia.data.SensitiveContextRecord
import com.dronewukong.apophenia.media.MediaEvidenceReader
import com.dronewukong.apophenia.phone.EncryptedContent
import com.dronewukong.apophenia.phone.SensitiveContentCipher
import com.dronewukong.apophenia.phone.SensitiveContextProvider
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

interface ExportEvidenceMaterializer {
    fun sensitivePayloads(records: List<SensitiveContextRecord>): List<ExportPayload>
    fun mediaPayloads(assets: List<MediaAsset>): List<ExportPayload>
}

/** Materializes deliberately exported protected content into portable, unencrypted formats. */
class AndroidExportEvidenceMaterializer(context: Context) : ExportEvidenceMaterializer {
    private val cipher = SensitiveContentCipher()
    private val media = MediaEvidenceReader(context)

    override fun sensitivePayloads(records: List<SensitiveContextRecord>): List<ExportPayload> {
        if (records.isEmpty()) return emptyList()
        val rows = JSONArray()
        records.sortedBy { it.id }.forEach { record ->
            val plaintext = cipher.decrypt(
                EncryptedContent(record.ciphertextBase64, record.ivBase64, record.keyAlias),
                SensitiveContextProvider.associatedData(record.source, record.contentType, record.captureId)
            )
            val content: Any = runCatching { JSONObject(plaintext) }.getOrElse { plaintext }
            rows.put(
                JSONObject()
                    .put("id", record.id)
                    .put("timestampMs", record.timestampMs)
                    .put("observationId", record.observationId)
                    .put("isControl", record.isControl)
                    .put("source", record.source)
                    .put("contentType", record.contentType)
                    .put("captureId", record.captureId)
                    .put("content", content)
            )
        }
        val root = JSONObject()
            .put("schema", "apophenia.tier2.export.v1")
            .put("warning", "Explicit full-evidence export; contents are plaintext inside this bundle.")
            .put("records", rows)
        return listOf(
            ExportPayload(
                path = "tier2/contents.json",
                bytes = root.toString(2).toByteArray(Charsets.UTF_8),
                containsTier2Contents = true
            )
        )
    }

    override fun mediaPayloads(assets: List<MediaAsset>): List<ExportPayload> = buildList {
        assets.sortedWith(compareBy<MediaAsset> { it.observationId }.thenBy { it.id }).forEach { asset ->
            val base = "av/event-${asset.observationId}/${safeToken(asset.id)}"
            when (asset.mediaType) {
                MediaType.AUDIO -> {
                    val evidence = media.loadAudio(asset)
                    try {
                        add(
                            ExportPayload(
                                path = "$base.wav",
                                bytes = wav(evidence.pcm, evidence.sampleRateHz),
                                containsRawAv = true
                            )
                        )
                        add(
                            ExportPayload(
                                path = "$base.json",
                                bytes = JSONObject()
                                    .put("schema", "apophenia.export.audio.v1")
                                    .put("assetId", asset.id)
                                    .put("observationId", asset.observationId)
                                    .put("streamId", asset.streamId)
                                    .put("sampleRateHz", evidence.sampleRateHz)
                                    .put("preBytes", evidence.preBytes)
                                    .put("postBytes", evidence.postBytes)
                                    .put("postExcludedFromPredictors", true)
                                    .put("sourceEncryption", "Android Keystore AES-256-GCM")
                                    .put("keyMaterialExported", false)
                                    .toString(2).toByteArray(Charsets.UTF_8),
                                containsRawAv = true
                            )
                        )
                    } finally {
                        evidence.pcm.fill(0)
                    }
                }
                MediaType.VIDEO -> {
                    val frames = media.loadVideo(asset)
                    val index = JSONArray()
                    frames.forEachIndexed { number, frame ->
                        val name = "${number.toString().padStart(4, '0')}-${safeToken(frame.name)}"
                        add(ExportPayload("$base/$name", frame.jpeg, containsRawAv = true))
                        index.put(JSONObject().put("file", name).put("sourceFile", frame.name).put("phase", frame.phase).put("timestampMs", frame.timestampMs))
                    }
                    add(
                        ExportPayload(
                            path = "$base/index.json",
                            bytes = JSONObject()
                                .put("schema", "apophenia.export.video-frames.v1")
                                .put("assetId", asset.id)
                                .put("observationId", asset.observationId)
                                .put("streamId", asset.streamId)
                                .put("frames", index)
                                .put("postExcludedFromPredictors", true)
                                .put("sourceEncryption", "Android Keystore AES-256-GCM")
                                .put("keyMaterialExported", false)
                                .toString(2).toByteArray(Charsets.UTF_8),
                            containsRawAv = true
                        )
                    )
                }
            }
        }
    }

    private fun wav(pcm: ByteArray, sampleRateHz: Int): ByteArray {
        require(sampleRateHz in 8_000..192_000) { "Unsupported audio sample rate" }
        val output = ByteArrayOutputStream(44 + pcm.size)
        output.write("RIFF".toByteArray(Charsets.US_ASCII))
        output.write(le32(36 + pcm.size))
        output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        output.write(le32(16))
        output.write(le16(1))
        output.write(le16(1))
        output.write(le32(sampleRateHz))
        output.write(le32(sampleRateHz * 2))
        output.write(le16(2))
        output.write(le16(16))
        output.write("data".toByteArray(Charsets.US_ASCII))
        output.write(le32(pcm.size))
        output.write(pcm)
        return output.toByteArray()
    }

    private fun le16(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
    private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun safeToken(value: String): String = value
        .map { if (it.isLetterOrDigit() || it in "_-." ) it else '_' }
        .joinToString("")
        .take(100)
        .ifBlank { "unnamed" }
}
