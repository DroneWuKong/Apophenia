package com.dronewukong.apophenia.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates

data class NetworkStateSnapshot(
    val connected: Boolean,
    val validated: Boolean,
    val metered: Boolean,
    val transport: String,
    val carrier: String,
    val networkType: String,
    val roaming: Boolean,
    val signalDbm: Int?
)

object NetworkStateEncoder {
    fun samples(
        state: NetworkStateSnapshot,
        timestampMs: Long,
        observationId: Long?,
        isControl: Boolean
    ): List<ContextSample> {
        val captureId = observationId?.let { "event:$it:instant" }.orEmpty()
        val metadata = listOf(
            "transport=${state.transport.sanitize()}",
            "carrier=${state.carrier.sanitize()}",
            "network_type=${state.networkType.sanitize()}",
            "window=instant"
        ).joinToString(";")
        return buildList {
            fun addMetric(metric: String, value: Double, unit: String = "bool") {
                add(
                    ContextSample(
                        timestampMs = timestampMs,
                        observationId = observationId,
                        isControl = isControl,
                        source = "android_network",
                        metric = metric,
                        value = value,
                        unit = unit,
                        metadata = metadata,
                        captureId = captureId
                    )
                )
            }
            addMetric("network_connected", if (state.connected) 1.0 else 0.0)
            addMetric("network_validated", if (state.validated) 1.0 else 0.0)
            addMetric("network_metered", if (state.metered) 1.0 else 0.0)
            addMetric("network_roaming", if (state.roaming) 1.0 else 0.0)
            state.signalDbm?.let { addMetric("network_signal_dbm", it.toDouble(), "dBm") }
        }
    }

    private fun String.sanitize(): String = replace(';', '_').replace('=', '_').take(120)
}

class NetworkStateProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val gate = HardwareGates.Gate.LIVE_NETWORK_STATE_CAPTURE
        if (!HardwareGates.isAuthorized(context, gate)) return emptyList()
        val now = System.currentTimeMillis()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return NetworkStateEncoder.samples(
                NetworkStateSnapshot(true, true, false, "cellular", "simulation_carrier", "5g_nr", false, -91),
                now,
                observationId,
                isControl
            ).map { it.copy(source = "simulation/network") }
        }
        if (!HardwareGates.isCaptureEnabled(context, gate)) return emptyList()
        return NetworkStateEncoder.samples(snapshot(), now, observationId, isControl)
    }

    @SuppressLint("MissingPermission") // All permission-sensitive reads are checked and fail soft.
    private fun snapshot(): NetworkStateSnapshot {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity?.activeNetwork
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val canReadPhone = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val signal = if (Build.VERSION.SDK_INT >= 29 && canReadPhone) {
            runCatching { telephony?.signalStrength?.cellSignalStrengths?.maxOfOrNull { it.dbm } }.getOrNull()
        } else {
            null
        }
        val transport = when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ethernet"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> "vpn"
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true -> "bluetooth"
            else -> "none"
        }
        return NetworkStateSnapshot(
            connected = capabilities != null,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = connectivity?.isActiveNetworkMetered ?: false,
            transport = transport,
            carrier = runCatching { telephony?.networkOperatorName.orEmpty() }.getOrDefault(""),
            networkType = networkTypeName(if (canReadPhone) runCatching { telephony?.dataNetworkType ?: 0 }.getOrDefault(0) else 0),
            roaming = runCatching { telephony?.isNetworkRoaming ?: false }.getOrDefault(false),
            signalDbm = signal?.takeIf { it in -160..-1 }
        )
    }

    private fun networkTypeName(type: Int): String = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5g_nr"
        TelephonyManager.NETWORK_TYPE_LTE -> "lte"
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_UMTS -> "3g"
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_GSM,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT -> "2g"
        0 -> "unknown"
        else -> "other_$type"
    }
}
