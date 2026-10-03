package com.dronewukong.apophenia.radio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates

object RadioContextSettings {
    private const val PREFS = "radio_context"
    private const val ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, enabled).apply()
}

data class WifiSignal(val frequencyMhz: Int, val rssiDbm: Int)
data class BleSignal(val rssiDbm: Int)
data class CellSignal(val technology: String, val rssiDbm: Int?, val registered: Boolean)
data class RadioMetric(val name: String, val value: Double, val unit: String)

/** Converts one radio snapshot into aggregate metrics. Device and network identifiers never leave memory. */
object RadioSnapshotAggregator {
    fun aggregate(
        wifi: List<WifiSignal>,
        ble: List<BleSignal>,
        cells: List<CellSignal>,
        includeBle: Boolean = true
    ): List<RadioMetric> {
        val out = mutableListOf<RadioMetric>()
        fun signals(prefix: String, levels: List<Int>) {
            out += RadioMetric("${prefix}_count", levels.size.toDouble(), "count")
            if (levels.isNotEmpty()) {
                out += RadioMetric("${prefix}_rssi_mean_dbm", levels.average(), "dBm")
                out += RadioMetric("${prefix}_rssi_max_dbm", levels.max().toDouble(), "dBm")
            }
        }

        signals("radio_wifi_ap", wifi.map { it.rssiDbm })
        out += RadioMetric("radio_wifi_2g_count", wifi.count { it.frequencyMhz in 2_400..2_500 }.toDouble(), "count")
        out += RadioMetric("radio_wifi_5g_count", wifi.count { it.frequencyMhz in 4_900..5_895 }.toDouble(), "count")
        out += RadioMetric("radio_wifi_6g_count", wifi.count { it.frequencyMhz in 5_925..7_125 }.toDouble(), "count")
        if (includeBle) signals("radio_ble_advertiser", ble.map { it.rssiDbm })
        out += RadioMetric("radio_cell_count", cells.size.toDouble(), "count")
        out += RadioMetric("radio_cell_registered_count", cells.count { it.registered }.toDouble(), "count")
        cells.groupingBy { it.technology.lowercase() }.eachCount().toSortedMap().forEach { (technology, count) ->
            out += RadioMetric("radio_cell_${technology}_count", count.toDouble(), "count")
        }
        val cellLevels = cells.mapNotNull { it.rssiDbm }.filter { it in -160..-1 }
        if (cellLevels.isNotEmpty()) {
            out += RadioMetric("radio_cell_rssi_mean_dbm", cellLevels.average(), "dBm")
            out += RadioMetric("radio_cell_rssi_max_dbm", cellLevels.max().toDouble(), "dBm")
        }
        return out
    }
}

class RadioContextProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean, force: Boolean = false): List<ContextSample> {
        if (!force && !RadioContextSettings.isEnabled(context)) return emptyList()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return toSamples(
                observationId,
                isControl,
                RadioSnapshotAggregator.aggregate(
                    wifi = listOf(WifiSignal(2_437, -54), WifiSignal(5_180, -68), WifiSignal(5_955, -72)),
                    ble = emptyList(),
                    cells = listOf(CellSignal("nr", -93, true), CellSignal("lte", -105, false)),
                    includeBle = false
                ),
                source = "simulation/radio"
            )
        }
        val wifi = collectWifi()
        val cells = collectCells()
        if (wifi.isEmpty() && cells.isEmpty()) return emptyList()
        return toSamples(
            observationId,
            isControl,
            RadioSnapshotAggregator.aggregate(wifi, emptyList(), cells, includeBle = false),
            "android_radio"
        )
    }

    private fun collectWifi(): List<WifiSignal> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return runCatching {
            @Suppress("DEPRECATION")
            manager.startScan()
            @Suppress("DEPRECATION")
            manager.scanResults.map { WifiSignal(it.frequency, it.level) }
        }.getOrDefault(emptyList())
    }

    private fun collectCells(): List<CellSignal> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS)) return emptyList()
        val manager = context.getSystemService(TelephonyManager::class.java) ?: return emptyList()
        return runCatching { manager.allCellInfo.orEmpty().map(::cellSignal) }.getOrDefault(emptyList())
    }

    private fun cellSignal(cell: CellInfo): CellSignal {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (cell) {
                is CellInfoNr -> return CellSignal("nr", cell.cellSignalStrength.dbm, cell.isRegistered)
                is CellInfoTdscdma -> return CellSignal("tdscdma", cell.cellSignalStrength.dbm, cell.isRegistered)
            }
        }
        return when (cell) {
            is CellInfoLte -> CellSignal("lte", cell.cellSignalStrength.dbm, cell.isRegistered)
            is CellInfoWcdma -> CellSignal("wcdma", cell.cellSignalStrength.dbm, cell.isRegistered)
            is CellInfoGsm -> CellSignal("gsm", cell.cellSignalStrength.dbm, cell.isRegistered)
            is CellInfoCdma -> CellSignal("cdma", cell.cellSignalStrength.dbm, cell.isRegistered)
            else -> CellSignal("other", null, cell.isRegistered)
        }
    }

    private fun toSamples(
        observationId: Long?,
        isControl: Boolean,
        metrics: List<RadioMetric>,
        source: String
    ): List<ContextSample> {
        val now = System.currentTimeMillis()
        return metrics.map { metric ->
            ContextSample(
                timestampMs = now,
                observationId = observationId,
                isControl = isControl,
                source = source,
                metric = metric.name,
                value = metric.value,
                unit = metric.unit,
                metadata = "privacy=aggregate;window=instant;identifiers=discarded"
            )
        }
    }
}
