package com.dronewukong.apophenia.export

import android.content.Context
import com.dronewukong.apophenia.phone.EncryptedContent
import com.dronewukong.apophenia.phone.SensitiveContentCipher
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import javax.crypto.SecretKey
import org.json.JSONObject

enum class LanDestinationType { NONE, DOCUMENT_TREE, HTTP }

data class LanCredentials(val username: String, val password: String)

sealed interface LanDestination {
    data class DocumentTree(val uri: String) : LanDestination
    data class Http(val endpoint: String, val credentials: LanCredentials?) : LanDestination
}

data class LanExportConfiguration(
    val type: LanDestinationType,
    val summary: String,
    val endpoint: String = "",
    val username: String = ""
)

/** Persists only the destination descriptor in plaintext; optional HTTP credentials use Keystore AES-GCM. */
class LanExportSettings(
    context: Context,
    fixedKey: SecretKey? = null
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cipher = SensitiveContentCipher(fixedKey = fixedKey, alias = KEY_ALIAS)

    fun configuration(): LanExportConfiguration = when (prefs.getString(KEY_TYPE, null)) {
        LanDestinationType.DOCUMENT_TREE.name -> {
            val uri = prefs.getString(KEY_TREE_URI, "").orEmpty()
            if (uri.isBlank()) emptyConfiguration() else LanExportConfiguration(
                LanDestinationType.DOCUMENT_TREE,
                "Android document tree · SMB/NFS when supplied by an installed DocumentsProvider"
            )
        }
        LanDestinationType.HTTP.name -> {
            val endpoint = prefs.getString(KEY_HTTP_ENDPOINT, "").orEmpty()
            if (endpoint.isBlank()) emptyConfiguration() else LanExportConfiguration(
                LanDestinationType.HTTP,
                endpoint,
                endpoint
            )
        }
        else -> emptyConfiguration()
    }

    fun destination(): LanDestination? = when (configuration().type) {
        LanDestinationType.DOCUMENT_TREE -> prefs.getString(KEY_TREE_URI, null)?.takeIf(String::isNotBlank)?.let(LanDestination::DocumentTree)
        LanDestinationType.HTTP -> prefs.getString(KEY_HTTP_ENDPOINT, null)?.takeIf(String::isNotBlank)?.let {
            LanDestination.Http(LocalNetworkPolicy.requireLocalEndpoint(it).toString(), loadCredentials())
        }
        LanDestinationType.NONE -> null
    }

    fun saveDocumentTree(uri: String) {
        require(uri.isNotBlank()) { "A document-tree URI is required" }
        prefs.edit()
            .putString(KEY_TYPE, LanDestinationType.DOCUMENT_TREE.name)
            .putString(KEY_TREE_URI, uri)
            .remove(KEY_HTTP_ENDPOINT)
            .remove(KEY_CREDENTIAL_CIPHERTEXT)
            .remove(KEY_CREDENTIAL_IV)
            .apply()
    }

    fun saveHttp(endpoint: String, username: String = "", password: String = "") {
        val normalized = LocalNetworkPolicy.requireLocalEndpoint(endpoint).toString()
        require((username.isBlank() && password.isBlank()) || (username.isNotBlank() && password.isNotBlank())) {
            "Provide both HTTP username and password, or leave both blank"
        }
        val editor = prefs.edit()
            .putString(KEY_TYPE, LanDestinationType.HTTP.name)
            .putString(KEY_HTTP_ENDPOINT, normalized)
            .remove(KEY_TREE_URI)
        if (username.isBlank()) {
            editor.remove(KEY_CREDENTIAL_CIPHERTEXT).remove(KEY_CREDENTIAL_IV)
        } else {
            val encrypted = cipher.encrypt(
                JSONObject().put("username", username).put("password", password).toString(),
                CREDENTIAL_AAD
            )
            editor.putString(KEY_CREDENTIAL_CIPHERTEXT, encrypted.ciphertextBase64)
                .putString(KEY_CREDENTIAL_IV, encrypted.ivBase64)
        }
        editor.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun loadCredentials(): LanCredentials? {
        val ciphertext = prefs.getString(KEY_CREDENTIAL_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_CREDENTIAL_IV, null) ?: return null
        val decoded = JSONObject(cipher.decrypt(EncryptedContent(ciphertext, iv, KEY_ALIAS), CREDENTIAL_AAD))
        return LanCredentials(decoded.getString("username"), decoded.getString("password"))
    }

    private fun emptyConfiguration() = LanExportConfiguration(LanDestinationType.NONE, "Not configured")

    companion object {
        private const val PREFS = "lan_export_v1"
        private const val KEY_TYPE = "destination_type"
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_HTTP_ENDPOINT = "http_endpoint"
        private const val KEY_CREDENTIAL_CIPHERTEXT = "credential_ciphertext"
        private const val KEY_CREDENTIAL_IV = "credential_iv"
        private const val KEY_ALIAS = "apophenia_lan_credentials_v1"
        private const val CREDENTIAL_AAD = "apophenia.lan.credentials.v1"
    }
}

object LocalNetworkPolicy {
    /** Allows only literal loopback/private/link-local addresses, preventing DNS rebinding and public pushes. */
    fun requireLocalEndpoint(raw: String): URI {
        val uri = runCatching { URI(raw.trim()) }.getOrElse { throw IllegalArgumentException("Invalid LAN endpoint") }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "LAN endpoint must use http:// or https://" }
        require(uri.rawUserInfo == null) { "Put credentials in the protected credential fields, not the URL" }
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: throw IllegalArgumentException("LAN endpoint needs a literal IP address")
        val addressLiteral = host.substringBefore('%')
        require(isIpv4Literal(addressLiteral) || isIpv6Literal(addressLiteral)) { "LAN endpoint must use a literal local IP address; hostnames are not accepted" }
        val address = InetAddress.getByName(addressLiteral)
        val uniqueLocalV6 = address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc
        require(!address.isAnyLocalAddress && (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocalV6)) {
            "LAN endpoint must be loopback, private, unique-local, or link-local"
        }
        require(uri.fragment == null) { "LAN endpoint cannot contain a fragment" }
        return uri
    }

    private fun isIpv4Literal(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && part.toIntOrNull() in 0..255 }
    }

    private fun isIpv6Literal(value: String): Boolean = ':' in value && value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}
