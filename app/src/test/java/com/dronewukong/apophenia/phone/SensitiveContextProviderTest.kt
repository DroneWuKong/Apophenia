package com.dronewukong.apophenia.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SensitiveContextProviderTest {
    private lateinit var context: Context
    private val cipher = SensitiveContentCipher(
        SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After
    fun tearDown() = HardwareGates.clearAuthorizationsForTests(context)

    @Test
    fun gateOffProducesNoSensitiveRows() {
        assertTrue(SensitiveContextProvider(context, cipher).collect(4, false).isEmpty())
    }

    @Test
    fun authorizedSimulationEncryptsBeforeReturningRows() {
        val gates = listOf(
            HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE,
            HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE
        )
        gates.forEach { gate ->
            assertEquals(
                HardwareGates.AuthorizationResult.ENABLED,
                HardwareGates.setAuthorized(
                    context,
                    gate,
                    enabled = true,
                    proof = HardwareGates.ConsentProof.TypedGateName(gate.name)
                )
            )
        }

        val rows = SensitiveContextProvider(context, cipher).collect(4, false)

        assertEquals(setOf("notification_contents", "message_metadata"), rows.map { it.contentType }.toSet())
        rows.forEach { row ->
            assertFalse(row.ciphertextBase64.contains("tier2-simulation"))
            val plaintext = cipher.decrypt(
                EncryptedContent(row.ciphertextBase64, row.ivBase64, row.keyAlias),
                SensitiveContextProvider.associatedData(row.source, row.contentType, row.captureId)
            )
            assertTrue(plaintext.contains("tier2-simulation"))
            assertEquals("event:4:instant", row.captureId)
        }
    }

    @Test
    fun changingAssociatedDataRefusesDecryption() {
        val encrypted = cipher.encrypt("secret", "event:1")
        assertTrue(runCatching { cipher.decrypt(encrypted, "event:2") }.isFailure)
    }
}
