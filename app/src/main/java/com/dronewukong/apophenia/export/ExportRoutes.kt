package com.dronewukong.apophenia.export

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.OutputStream

object ExportRoutes {
    fun shareIntent(files: List<File>, uriFor: (File) -> Uri): Intent {
        require(files.isNotEmpty()) { "At least one export file is required" }
        val uris = ArrayList(files.map(uriFor))
        val mime = if (files.all { it.extension.equals("zip", true) }) "application/zip" else "application/octet-stream"
        val clip = ClipData("Apophenia export", arrayOf(mime), ClipData.Item(uris.first())).also { data ->
            uris.drop(1).forEach { data.addItem(ClipData.Item(it)) }
        }
        return Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mime
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun writeDocument(source: File, output: OutputStream): Long {
        require(source.isFile) { "Prepared export is missing" }
        return source.inputStream().use { input -> output.use { input.copyTo(it) } }
    }
}
