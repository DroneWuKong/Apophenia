package com.dronewukong.apophenia.phone

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedContent(
    val ciphertextBase64: String,
    val ivBase64: String,
    val keyAlias: String
)

/** AES-GCM envelope used exclusively by the Tier-2 content table. */
class SensitiveContentCipher(
    private val fixedKey: SecretKey? = null,
    private val alias: String = KEY_ALIAS
) {
    fun encrypt(plaintext: String, associatedData: String): EncryptedContent {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return EncryptedContent(
            ciphertextBase64 = Base64.encodeToString(
                cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            ),
            ivBase64 = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            keyAlias = if (fixedKey == null) alias else TEST_ALIAS
        )
    }

    fun decrypt(payload: EncryptedContent, associatedData: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(payload.ivBase64, Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(Base64.decode(payload.ciphertextBase64, Base64.NO_WRAP))
            .toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey = fixedKey ?: synchronized(this) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generateKey()
        }
    }

    companion object {
        const val KEY_ALIAS = "apophenia_tier2_content_v1"
        const val TEST_ALIAS = "in_memory_test_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
