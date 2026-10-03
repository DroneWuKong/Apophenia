package com.dronewukong.apophenia.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LanExportTest {
    private lateinit var context: Context
    private lateinit var settings: LanExportSettings
    private lateinit var source: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settings = LanExportSettings(context, SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES"))
        settings.clear()
        source = File(context.cacheDir, "lan-export-test.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
    }

    @After
    fun tearDown() {
        settings.clear()
        source.delete()
    }

    @Test
    fun localPolicyAcceptsPrivateLiteralsAndRejectsDnsOrPublicAddresses() {
        assertEquals("192.168.1.20", LocalNetworkPolicy.requireLocalEndpoint("http://192.168.1.20:8080/upload").host)
        assertEquals("127.0.0.1", LocalNetworkPolicy.requireLocalEndpoint("https://127.0.0.1/evidence").host)
        assertThrows(IllegalArgumentException::class.java) { LocalNetworkPolicy.requireLocalEndpoint("http://nas.local/upload") }
        assertThrows(IllegalArgumentException::class.java) { LocalNetworkPolicy.requireLocalEndpoint("https://8.8.8.8/upload") }
        assertThrows(IllegalArgumentException::class.java) { LocalNetworkPolicy.requireLocalEndpoint("https://0.0.0.0/upload") }
    }

    @Test
    fun credentialsRoundTripOnlyThroughEncryptedPreferenceFields() {
        settings.saveHttp("http://127.0.0.1:8080/upload", "operator", "correct horse")
        val destination = settings.destination() as LanDestination.Http
        assertEquals("operator", destination.credentials?.username)
        assertEquals("correct horse", destination.credentials?.password)
        val raw = context.getSharedPreferences("lan_export_v1", Context.MODE_PRIVATE).all.values.joinToString("|")
        assertFalse(raw.contains("correct horse"))
    }

    @Test
    fun gateOffPreventsAnyHttpConnection() {
        settings.saveHttp("http://127.0.0.1:8080/upload")
        val manager = LanExportManager(context, settings, authorized = { false }) { error("connection must not open") }
        assertThrows(IllegalArgumentException::class.java) { manager.push(source) }
    }

    @Test
    fun explicitHttpPushWritesExactBundleAndReportsResponse() {
        val received = AtomicReference<ByteArray>()
        val digestHeader = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange ->
            received.set(exchange.requestBody.use { it.readBytes() })
            digestHeader.set(exchange.requestHeaders.getFirst("X-Apophenia-SHA256"))
            exchange.sendResponseHeaders(201, -1)
            exchange.close()
        }
        server.start()
        try {
            settings.saveHttp("http://127.0.0.1:${server.address.port}/upload")
            val result = LanExportManager(context, settings, authorized = { true }).push(source)
            assertEquals(201, result.httpStatus)
            assertEquals(source.length(), result.bytesWritten)
            assertArrayEquals(source.readBytes(), received.get())
            assertNotNull(digestHeader.get())
        } finally {
            server.stop(0)
        }
    }
}
