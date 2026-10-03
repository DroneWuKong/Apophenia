package com.dronewukong.apophenia.fieldkit

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class FieldKitContextProviderTest {
    private lateinit var context: Context
    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); HardwareGates.clearAuthorizationsForTests(context) }
    @After fun tearDown() { HardwareGates.clearAuthorizationsForTests(context) }

    @Test fun parserStoresThresholdsTriggersAndOnlyHashedDeviceIdentity() {
        val json = """{"device_id":"RAW-ESP32-ID","captured_at_ms":1000,"bands":[{"name":"915 MHz","rssi_dbm":-40,"threshold_dbm":-55,"crossed":true}],"triggers":[{"type":"rssi spike","band":"915 MHz"}]}"""
        val rows = FieldKitSnapshotParser.parse(json, 4, false, DeviceIdentifierHasher.withFixedKey(ByteArray(32) { 3 }), receivedAtMs = 1_100)
        assertTrue(rows.any { it.metric == "field_kit_threshold_crossed" && it.value == 1.0 })
        assertTrue(rows.any { it.metric == "field_kit_trigger" && it.metadata.contains("type=rssi_spike") })
        assertTrue(rows.all { it.metadata.contains("device_hash=idhash:v1:") })
        assertFalse(rows.joinToString().contains("RAW-ESP32-ID"))
    }

    @Test fun providerIsGateBoundAndSimulationUsesFullParser() {
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
        assertTrue(FieldKitContextProvider(context).collect(1, false).isEmpty())
        HardwareGates.setAuthorized(context, HardwareGates.Gate.LIVE_FIELD_KIT_CAPTURE, true, HardwareGates.ConsentProof.SingleConfirmation)
        val rows = FieldKitContextProvider(context).collect(1, false)
        assertEquals(1.0, rows.single { it.metric == "field_kit_threshold_crossing_count" }.value, 0.0)
        assertTrue(rows.all { it.source == "simulation/field_kit" })
    }
}
