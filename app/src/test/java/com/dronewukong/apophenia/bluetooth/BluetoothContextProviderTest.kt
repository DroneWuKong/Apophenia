package com.dronewukong.apophenia.bluetooth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.HardwareGates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BluetoothContextProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        HardwareGates.clearAuthorizationsForTests(context)
        HardwareGates.setRuntimeMode(context, HardwareGates.RuntimeMode.SIMULATION)
    }

    @After
    fun tearDown() {
        HardwareGates.clearAuthorizationsForTests(context)
    }

    @Test
    fun gateOffProducesNoSimulationOrStoredRows() {
        assertTrue(BluetoothContextProvider(context).collect(9, false).isEmpty())
    }

    @Test
    fun authorizedSimulationUsesProductionEncodingShape() {
        HardwareGates.setAuthorized(
            context,
            HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE,
            enabled = true,
            proof = HardwareGates.ConsentProof.SingleConfirmation
        )

        val samples = BluetoothContextProvider(context).collect(9, false)
        val count = samples.single { it.metric == "bt_nearby_count" }
        val strongest = samples.single { it.metric == "bt_rssi_max" }
        val devices = samples.filter { it.metric == "bt_device_rssi_dbm" }

        assertEquals(2.0, count.value, 0.0)
        assertEquals(-52.0, strongest.value, 0.0)
        assertEquals(2, devices.size)
        assertTrue(samples.all { it.source == "simulation/bluetooth" })
        assertTrue(samples.all { it.captureId == "event:9:instant" })
        assertTrue(devices.all { it.metadata.contains("device_hash=idhash:v1:") })
    }

    @Test
    fun encoderPersistsHashesAndCategoriesButNeverRawIdentifiersOrNames() {
        val rawAddress = "AA:BB:CC:DD:EE:FF"
        val rawName = "Garage Camera"
        val samples = BluetoothSnapshotEncoder.samples(
            devices = listOf(
                BluetoothDeviceSnapshot(
                    address = rawAddress,
                    rssiDbm = -47,
                    deviceClass = "manufacturer_specific",
                    nameCategory = BluetoothDeviceClassifier.nameCategory(rawName)
                )
            ),
            hasher = DeviceIdentifierHasher.withFixedKey(ByteArray(32) { it.toByte() }),
            timestampMs = 123,
            observationId = 7,
            isControl = false
        )

        val serialized = samples.joinToString("|") { "${it.metric}:${it.value}:${it.metadata}" }
        assertFalse(serialized.contains(rawAddress, ignoreCase = true))
        assertFalse(serialized.contains(rawName, ignoreCase = true))
        assertTrue(serialized.contains("name_category=home_iot"))
        assertTrue(serialized.contains("device_class=manufacturer_specific"))
    }

    @Test
    fun classifierUsesOnlyCoarseAdvertisedCategories() {
        assertEquals("wearable", BluetoothDeviceClassifier.nameCategory("Garmin Forerunner"))
        assertEquals("unnamed", BluetoothDeviceClassifier.nameCategory(null))
        assertEquals(
            "health",
            BluetoothDeviceClassifier.advertisedClass(
                listOf("0000180d-0000-1000-8000-00805f9b34fb"),
                manufacturerDataPresent = false,
                connectable = true
            )
        )
    }

    @Test
    fun encoderRejectsUnreducedNameOrClassMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothSnapshotEncoder.samples(
                devices = listOf(
                    BluetoothDeviceSnapshot(
                        address = "AA:BB:CC:DD:EE:FF",
                        rssiDbm = -50,
                        deviceClass = "manufacturer_specific",
                        nameCategory = "Garage Camera"
                    )
                ),
                hasher = DeviceIdentifierHasher.withFixedKey(ByteArray(32) { it.toByte() }),
                timestampMs = 1,
                observationId = 1,
                isControl = false
            )
        }
    }
}
