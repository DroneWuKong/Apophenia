package com.dronewukong.apophenia.ingest

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.dronewukong.apophenia.data.ObservationCaptureRequest
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin

data class InboundShare(
    val request: ObservationCaptureRequest,
    val attachment: IncomingAttachment
)

object InboundShareParser {
    private const val MAX_SHARED_TEXT_CHARS = 1_000_000
    private const val MAX_NOTE_CHARS = 4_000
    private const val MAX_LABEL_CHARS = 160

    fun parse(context: Context, intent: Intent, receivedAtMs: Long): InboundShare {
        require(intent.action == Intent.ACTION_SEND) { "Unsupported share action" }
        val declaredType = intent.type.orEmpty().lowercase()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(MAX_SHARED_TEXT_CHARS)
        val stream = streamUri(intent)
        val isImage = declaredType.startsWith("image/") || stream?.let { context.contentResolver.getType(it)?.startsWith("image/") } == true
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)?.trim()?.take(MAX_LABEL_CHARS).orEmpty()
        val attachment = when {
            stream != null && isImage -> {
                val mime = context.contentResolver.getType(stream)?.takeIf { it.startsWith("image/") }
                    ?: declaredType.takeIf { it.startsWith("image/") }
                    ?: "image/*"
                IncomingAttachment.ContentUri(stream, mime, ObservationAttachmentStore.displayName(context, stream))
            }
            text != null -> IncomingAttachment.Text(text)
            else -> error("The share contains no text or image")
        }
        val note = when (attachment) {
            is IncomingAttachment.Text -> attachment.text.take(MAX_NOTE_CHARS)
            is IncomingAttachment.ContentUri -> text?.take(MAX_NOTE_CHARS).orEmpty()
        }
        return InboundShare(
            ObservationCaptureRequest(
                timestampMs = receivedAtMs,
                kind = ObservationKind.OBSERVATION,
                label = subject.ifBlank { if (attachment is IncomingAttachment.Text) "Shared text" else "Shared image" },
                note = note,
                origin = ObservationOrigin.EXTERNAL
            ),
            attachment
        )
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? = when {
        Build.VERSION.SDK_INT >= 33 -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
    } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
}
