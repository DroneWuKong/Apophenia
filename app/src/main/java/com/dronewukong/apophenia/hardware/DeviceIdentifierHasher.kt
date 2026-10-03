package com.dronewukong.apophenia.hardware

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

enum class DeviceIdentifierKind {
    MAC_ADDRESS,
    WIFI_BSSID,
    ADAPTER_SYSID
}

data class IdentifierHashKey(
    val generation: Int,
    val secretKey: SecretKey
)

interface IdentifierHashKeyStore {
    fun current(): IdentifierHashKey
    fun rotate(): IdentifierHashKey
}

/** Returns stable, non-reversible identifiers without exposing the raw value to persistence. */
class DeviceIdentifierHasher(
    private val keyStore: IdentifierHashKeyStore
) {
    fun hash(kind: DeviceIdentifierKind, rawIdentifier: String): String {
        val normalized = normalize(kind, rawIdentifier)
        require(normalized.isNotEmpty()) { "Identifier must not be blank" }
        val key = keyStore.current()
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key.secretKey)
        val payload = "${kind.name}:$normalized".toByteArray(Charsets.UTF_8)
        val digest = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload))
        return "idhash:v${key.generation}:$digest"
    }

    fun rotateSalt(): Int = keyStore.rotate().generation

    private fun normalize(kind: DeviceIdentifierKind, rawIdentifier: String): String = when (kind) {
        DeviceIdentifierKind.MAC_ADDRESS,
        DeviceIdentifierKind.WIFI_BSSID -> rawIdentifier.filter(Char::isLetterOrDigit).uppercase()
        DeviceIdentifierKind.ADAPTER_SYSID -> rawIdentifier.trim().lowercase()
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"

        fun withFixedKey(key: ByteArray, generation: Int = 1): DeviceIdentifierHasher {
            require(key.size >= 32) { "Identifier hash keys must be at least 256 bits" }
            return DeviceIdentifierHasher(
                object : IdentifierHashKeyStore {
                    private var current = IdentifierHashKey(generation, SecretKeySpec(key.copyOf(), HMAC_ALGORITHM))

                    override fun current(): IdentifierHashKey = current

                    override fun rotate(): IdentifierHashKey {
                        val replacement = ByteArray(32).also(SecureRandom()::nextBytes)
                        current = IdentifierHashKey(
                            current.generation + 1,
                            SecretKeySpec(replacement, HMAC_ALGORITHM)
                        )
                        return current
                    }
                }
            )
        }
    }
}

/** Android Keystore-backed HMAC material. Key rotation deletes the prior local key. */
class AndroidIdentifierHashKeyStore(context: Context) : IdentifierHashKeyStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val androidKeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    @Synchronized
    override fun current(): IdentifierHashKey {
        val storedGeneration = preferences.getInt(KEY_GENERATION, 1).coerceAtLeast(1)
        val storedAlias = alias(storedGeneration)
        (androidKeyStore.getKey(storedAlias, null) as? SecretKey)?.let { existing ->
            if (!preferences.getBoolean(KEY_INITIALIZED, false)) {
                check(preferences.edit().putBoolean(KEY_INITIALIZED, true).commit()) {
                    "Unable to persist identifier hash key state"
                }
            }
            return IdentifierHashKey(storedGeneration, existing)
        }

        // A restored preference file without its non-exportable Keystore entry must not reuse the
        // old generation label, because its hashes are no longer comparable with transferred rows.
        val generation = if (preferences.getBoolean(KEY_INITIALIZED, false)) {
            storedGeneration + 1
        } else {
            storedGeneration
        }
        val newKey = loadExistingOrCreate(generation)
        if (!preferences.edit()
                .putInt(KEY_GENERATION, generation)
                .putBoolean(KEY_INITIALIZED, true)
                .commit()
        ) {
            androidKeyStore.deleteEntry(alias(generation))
            error("Unable to persist identifier hash key state")
        }
        return IdentifierHashKey(generation, newKey)
    }

    @Synchronized
    override fun rotate(): IdentifierHashKey {
        val oldGeneration = current().generation
        val oldAlias = alias(oldGeneration)
        val newGeneration = oldGeneration + 1
        val newAlias = alias(newGeneration)
        val newKey = loadExistingOrCreate(newGeneration)
        if (!preferences.edit().putInt(KEY_GENERATION, newGeneration).commit()) {
            if (androidKeyStore.containsAlias(newAlias)) androidKeyStore.deleteEntry(newAlias)
            error("Unable to persist identifier hash key generation")
        }
        if (androidKeyStore.containsAlias(oldAlias)) androidKeyStore.deleteEntry(oldAlias)
        return IdentifierHashKey(newGeneration, newKey)
    }

    private fun loadExistingOrCreate(generation: Int): SecretKey {
        val alias = alias(generation)
        (androidKeyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEY_STORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                        alias,
                        KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                    )
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build()
                )
            }
            .generateKey()
    }

    private fun alias(generation: Int) = "$ALIAS_PREFIX$generation"

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val PREFS = "identifier_hash_keys"
        private const val KEY_GENERATION = "generation"
        private const val KEY_INITIALIZED = "initialized"
        private const val ALIAS_PREFIX = "apophenia.identifier-hash.v"
    }
}
