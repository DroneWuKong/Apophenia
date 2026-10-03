package com.dronewukong.apophenia.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.AndroidIdentifierHashKeyStore
import com.dronewukong.apophenia.hardware.DeviceIdentifierHasher
import com.dronewukong.apophenia.hardware.DeviceIdentifierKind
import com.dronewukong.apophenia.hardware.HardwareGates

data class WifiAccessPointSnapshot(
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int
)

object WifiSnapshotEncoder {
    fun band(frequencyMhz: Int): String = when (frequencyMhz) {
        in 2_400..2_500 -> "2.4GHz"
        in 4_900..5_895 -> "5GHz"
        in 5_925..7_125 -> "6GHz"
        else -> "other"
    }

    fun samples(
        accessPoints: List<WifiAccessPointSnapshot>,
        hasher: DeviceIdentifierHasher,
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean
    ): List<ContextSample> {
        val captureId = observationId?.let { "event:$it:instant" }.orEmpty()
        val rows = accessPoints
            .filter { it.bssid.isNotBlank() }
            .distinctBy { it.bssid.filter(Char::isLetterOrDigit).uppercase() }
        val samples = rows.map { ap ->
            ContextSample(
                timestampMs = timestampMs,
                observationId = observationId,
                isControl = isControl,
                source = "android_wifi",
                metric = "wifi_ap_rssi_dbm",
                value = ap.rssiDbm.toDouble(),
                unit = "dBm",
                metadata = listOf(
                    "bssid_hash=${hasher.hash(DeviceIdentifierKind.WIFI_BSSID, ap.bssid)}",
                    "band=${band(ap.frequencyMhz)}",
                    "frequency_mhz=${ap.frequencyMhz}",
                    "identifier=locally_keyed_hash",
                    "window=instant"
                ).joinToString(";"),
                captureId = captureId
            )
        }.toMutableList()
        samples += ContextSample(
            timestampMs = timestampMs,
            observationId = observationId,
            isControl = isControl,
            source = "android_wifi",
            metric = "wifi_visible_count",
            value = rows.size.toDouble(),
            unit = "count",
            metadata = "window=instant;aggregation=capture",
            captureId = captureId
        )
        rows.maxOfOrNull { it.rssiDbm }?.let { strongest ->
            samples += ContextSample(
                timestampMs = timestampMs,
                observationId = observationId,
                isControl = isControl,
                source = "android_wifi",
                metric = "wifi_rssi_max",
                value = strongest.toDouble(),
                unit = "dBm",
                metadata = "window=instant;aggregation=capture",
                captureId = captureId
            )
        }
        return samples
    }
}

class WifiContextProvider(private val context: Context) {
    private val liveHasher by lazy {
        DeviceIdentifierHasher(AndroidIdentifierHashKeyStore(context.applicationContext))
    }

    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val gate = HardwareGates.Gate.LIVE_WIFI_CAPTURE
        if (!HardwareGates.isAuthorized(context, gate)) return emptyList()
        val now = System.currentTimeMillis()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return WifiSnapshotEncoder.samples(
                simulationAccessPoints,
                DeviceIdentifierHasher.withFixedKey(SIMULATION_HASH_KEY),
                now,
                observationId,
                isControl
            ).map { it.copy(source = "simulation/wifi") }
        }
        if (!HardwareGates.isCaptureEnabled(context, gate) || !hasPermission()) return emptyList()
        val accessPoints = scan() ?: return emptyList()
        return WifiSnapshotEncoder.samples(accessPoints, liveHasher, now, observationId, isControl)
    }

    private fun hasPermission(): Boolean {
        val location = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        return location && nearby
    }

    @SuppressLint("MissingPermission") // collect() checks permissions and catches revocation races.
    private fun scan(): List<WifiAccessPointSnapshot>? {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) return null
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            manager.startScan()
            @Suppress("DEPRECATION")
            manager.scanResults.map { result ->
                WifiAccessPointSnapshot(result.BSSID.orEmpty(), result.level, result.frequency)
            }
        }.getOrNull()
    }

    companion object {
        private val SIMULATION_HASH_KEY = ByteArray(32) { index -> (index * 5 + 9).toByte() }
        private val simulationAccessPoints = listOf(
            WifiAccessPointSnapshot("02:10:20:30:40:50", -44, 2_437),
            WifiAccessPointSnapshot("02:10:20:30:40:51", -67, 5_180),
            WifiAccessPointSnapshot("02:10:20:30:40:52", -72, 5_955)
        )
    }
}
