package com.dronewukong.apophenia.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates
import java.util.concurrent.ConcurrentHashMap

data class BluetoothDeviceSnapshot(
    val address: String,
    val rssiDbm: Int,
    val deviceClass: String,
    val nameCategory: String
)

object BluetoothSnapshotEncoder {
    private val allowedClasses = setOf(
        "input",
        "health",
        "audio",
        "manufacturer_specific",
        "service_advertiser",
        "connectable_unknown",
        "nonconnectable_unknown"
    )
    private val allowedNameCategories = setOf(
        "vehicle",
        "wearable",
        "audio",
        "personal_computer",
        "home_iot",
        "other_named",
        "unnamed"
    )

    fun samples(
        devices: List<BluetoothDeviceSnapshot>,
        hasher: DeviceIdentifierHasher,
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean
    ): List<ContextSample> {
        val captureId = observationId?.let { "event:$it:instant" }.orEmpty()
        val deduplicated = devices.distinctBy {
            it.address.filter(Char::isLetterOrDigit).uppercase()
        }
        val rows = deduplicated.map { device ->
            require(device.deviceClass in allowedClasses) { "Bluetooth class must be a coarse category" }
            require(device.nameCategory in allowedNameCategories) { "Bluetooth name must be reduced to a category" }
            val deviceHash = hasher.hash(DeviceIdentifierKind.MAC_ADDRESS, device.address)
            ContextSample(
                timestampMs = timestampMs,
                observationId = observationId,
                isControl = isControl,
                source = "android_bluetooth",
                metric = "bt_device_rssi_dbm",
                value = device.rssiDbm.toDouble(),
                unit = "dBm",
                metadata = listOf(
                    "device_hash=$deviceHash",
                    "device_class=${device.deviceClass}",
                    "name_category=${device.nameCategory}",
                    "identifier=locally_keyed_hash",
                    "window=instant"
                ).joinToString(";"),
                captureId = captureId
            )
        }.toMutableList()

        rows += ContextSample(
            timestampMs = timestampMs,
            observationId = observationId,
            isControl = isControl,
            source = "android_bluetooth",
            metric = "bt_nearby_count",
            value = deduplicated.size.toDouble(),
            unit = "count",
            metadata = "window=instant;aggregation=capture",
            captureId = captureId
        )
        deduplicated.maxOfOrNull { it.rssiDbm }?.let { strongest ->
            rows += ContextSample(
                timestampMs = timestampMs,
                observationId = observationId,
                isControl = isControl,
                source = "android_bluetooth",
                metric = "bt_rssi_max",
                value = strongest.toDouble(),
                unit = "dBm",
                metadata = "window=instant;aggregation=capture",
                captureId = captureId
            )
        }
        return rows
    }
}

object BluetoothDeviceClassifier {
    fun nameCategory(name: String?): String {
        val normalized = name?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return "unnamed"
        return when {
            listOf("car", "auto", "tesla", "ford", "toyota", "bmw", "obd").any(normalized::contains) -> "vehicle"
            listOf("watch", "band", "garmin", "fitbit", "wear").any(normalized::contains) -> "wearable"
            listOf("bud", "head", "audio", "speaker", "airpod").any(normalized::contains) -> "audio"
            listOf("phone", "pixel", "galaxy", "iphone", "laptop", "macbook").any(normalized::contains) -> "personal_computer"
            listOf("light", "bulb", "sensor", "thermo", "camera", "door", "lock").any(normalized::contains) -> "home_iot"
            else -> "other_named"
        }
    }

    fun advertisedClass(serviceUuids: List<String>, manufacturerDataPresent: Boolean, connectable: Boolean): String {
        val compact = serviceUuids.map { it.lowercase().replace("-", "") }
        return when {
            compact.any { it.contains("1812") } -> "input"
            compact.any { it.contains("180d") || it.contains("1808") || it.contains("1809") } -> "health"
            compact.any { it.contains("110b") || it.contains("110d") } -> "audio"
            manufacturerDataPresent -> "manufacturer_specific"
            compact.isNotEmpty() -> "service_advertiser"
            connectable -> "connectable_unknown"
            else -> "nonconnectable_unknown"
        }
    }
}

class BluetoothContextProvider(private val context: Context) {
    private val liveHasher by lazy {
        DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context.applicationContext))
    }

    fun collect(
        observationId: Long?,
        isControl: Boolean,
        windowMs: Long = DEFAULT_SCAN_WINDOW_MS
    ): List<ContextSample> {
        val gate = HardwareGates.Gate.LIVE_BLUETOOTH_CAPTURE
        if (!HardwareGates.isAuthorized(context, gate)) return emptyList()

        val now = System.currentTimeMillis()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return BluetoothSnapshotEncoder.samples(
                devices = simulationDevices,
                hasher = DeviceIdentifierHasher.withFixedKey(SIMULATION_HASH_KEY),
                timestampMs = now,
                observationId = observationId,
                isControl = isControl
            ).map { it.copy(source = "simulation/bluetooth") }
        }
        if (!HardwareGates.isCaptureEnabled(context, gate) || !hasPermission()) return emptyList()

        val devices = scan(windowMs) ?: return emptyList()
        return BluetoothSnapshotEncoder.samples(
            devices = devices,
            hasher = liveHasher,
            timestampMs = now,
            observationId = observationId,
            isControl = isControl
        )
    }

    private fun hasPermission(): Boolean {
        val location = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val scan = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        return location && scan
    }

    @SuppressLint("MissingPermission") // collect() checks permission; runCatching handles revocation during the scan.
    private fun scan(windowMs: Long): List<BluetoothDeviceSnapshot>? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) return null
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return null
        val adapter = manager.adapter ?: return null
        val scanner = runCatching { adapter.bluetoothLeScanner }.getOrNull() ?: return null
        val results = ConcurrentHashMap<String, BluetoothDeviceSnapshot>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                snapshot(result)?.let { results[it.address.uppercase()] = it }
            }

            override fun onBatchScanResults(batchResults: MutableList<ScanResult>) {
                batchResults.forEach { onScanResult(0, it) }
            }
        }

        return runCatching {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                callback
            )
            try {
                Thread.sleep(windowMs.coerceIn(100L, MAX_SCAN_WINDOW_MS))
            } finally {
                runCatching { scanner.stopScan(callback) }
            }
            results.values.sortedBy { it.address }
        }.getOrNull()
    }

    private fun snapshot(result: ScanResult): BluetoothDeviceSnapshot? {
        val address = runCatching { result.device.address }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val record = result.scanRecord
        val serviceUuids = record?.serviceUuids.orEmpty().map { it.uuid.toString() }
        val manufacturerDataPresent = (record?.manufacturerSpecificData?.size() ?: 0) > 0
        return BluetoothDeviceSnapshot(
            address = address,
            rssiDbm = result.rssi,
            deviceClass = BluetoothDeviceClassifier.advertisedClass(
                serviceUuids,
                manufacturerDataPresent,
                result.isConnectable
            ),
            nameCategory = BluetoothDeviceClassifier.nameCategory(record?.deviceName)
        )
    }

    companion object {
        private const val DEFAULT_SCAN_WINDOW_MS = 1_500L
        private const val MAX_SCAN_WINDOW_MS = 10_000L
        private val SIMULATION_HASH_KEY = ByteArray(32) { index -> (index * 7 + 3).toByte() }
        private val simulationDevices = listOf(
            BluetoothDeviceSnapshot("02:00:00:00:00:01", -52, "service_advertiser", "wearable"),
            BluetoothDeviceSnapshot("02:00:00:00:00:02", -71, "manufacturer_specific", "audio")
        )
    }
}
