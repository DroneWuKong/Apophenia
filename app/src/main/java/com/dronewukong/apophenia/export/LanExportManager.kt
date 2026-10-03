package com.dronewukong.apophenia.export

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import com.dronewukong.apophenia.hardware.HardwareGates
import java.io.File
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest

data class LanPushResult(val destination: String, val bytesWritten: Long, val httpStatus: Int? = null)

class LanExportManager(
    context: Context,
    private val settings: LanExportSettings = LanExportSettings(context),
    private val authorized: () -> Boolean = { HardwareGates.isAuthorized(context, HardwareGates.Gate.LIVE_EXPORT_LAN) },
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    private val app = context.applicationContext

    fun push(bundle: File): LanPushResult {
        require(bundle.isFile) { "Prepared export is missing" }
        require(authorized()) { "LIVE_EXPORT_LAN gate is off" }
        return when (val destination = settings.destination() ?: error("No LAN destination is configured")) {
            is LanDestination.DocumentTree -> pushDocumentTree(bundle, destination)
            is LanDestination.Http -> pushHttp(bundle, destination)
        }
    }

    private fun pushDocumentTree(bundle: File, destination: LanDestination.DocumentTree): LanPushResult {
        val tree = Uri.parse(destination.uri)
        val persisted = app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        require(persisted) { "Android no longer grants write access to the configured network folder" }
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = DocumentsContract.createDocument(
            app.contentResolver,
            root,
            "application/zip",
            bundle.name
        ) ?: error("The document provider refused the export file")
        val written = app.contentResolver.openOutputStream(document, "w")?.let { ExportRoutes.writeDocument(bundle, it) }
            ?: error("The document provider did not return a writable stream")
        require(written == bundle.length()) { "Document provider wrote $written of ${bundle.length()} bytes" }
        return LanPushResult("document-tree", written)
    }

    private fun pushHttp(bundle: File, destination: LanDestination.Http): LanPushResult {
        val base = LocalNetworkPolicy.requireLocalEndpoint(destination.endpoint)
        val target = if (base.path.orEmpty().endsWith('/')) {
            URL(base.toString() + URLEncoder.encode(bundle.name, Charsets.UTF_8.name()).replace("+", "%20"))
        } else URL(base.toString())
        val connection = connectionFactory(target)
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.requestMethod = "PUT"
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(bundle.length())
        connection.setRequestProperty("Content-Type", "application/zip")
        connection.setRequestProperty("X-Apophenia-Filename", bundle.name)
        connection.setRequestProperty("X-Apophenia-SHA256", sha256(bundle))
        destination.credentials?.let { credentials ->
            val basic = Base64.encodeToString("${credentials.username}:${credentials.password}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            connection.setRequestProperty("Authorization", "Basic $basic")
        }
        try {
            val written = connection.outputStream.use { output -> bundle.inputStream().use { it.copyTo(output) } }
            require(written == bundle.length()) { "LAN stream wrote $written of ${bundle.length()} bytes" }
            val status = connection.responseCode
            require(status in 200..299) { "LAN endpoint returned HTTP $status" }
            return LanPushResult(target.toString(), written, status)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
