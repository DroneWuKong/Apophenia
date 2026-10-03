package com.dronewukong.apophenia.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pManager
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ContextSample
import com.dronewukong.apophenia.hardware.HardwareGates
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in, instantaneous Wi-Fi Direct and NFC capability/presence-adjacent state. */
class AuxiliaryPresenceProvider(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val now = System.currentTimeMillis()
        if (HardwareGates.runtimeMode == HardwareGates.RuntimeMode.SIMULATION) {
            return buildList {
                if (authorized(HardwareGates.Gate.LIVE_WIFI_P2P_CAPTURE)) {
                    add(sample(now, observationId, isControl, "simulation/wifi_p2p", "wifi_p2p_supported", 1.0, "bool"))
                    add(sample(now, observationId, isControl, "simulation/wifi_p2p", "wifi_p2p_group_formed", 1.0, "bool"))
                    add(sample(now, observationId, isControl, "simulation/wifi_p2p", "wifi_p2p_group_owner", 0.0, "bool"))
                }
                if (authorized(HardwareGates.Gate.LIVE_NFC_CAPTURE)) {
                    add(sample(now, observationId, isControl, "simulation/nfc", "nfc_hardware_present", 1.0, "bool"))
                    add(sample(now, observationId, isControl, "simulation/nfc", "nfc_adapter_enabled", 1.0, "bool", "tag_presence=foreground_only"))
                }
            }
        }
        return buildList {
            if (authorized(HardwareGates.Gate.LIVE_WIFI_P2P_CAPTURE)) addAll(wifiP2p(now, observationId, isControl))
            if (authorized(HardwareGates.Gate.LIVE_NFC_CAPTURE)) addAll(nfc(now, observationId, isControl))
        }
    }

    @SuppressLint("MissingPermission")
    private fun wifiP2p(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> = buildList {
        val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        add(sample(now, observationId, isControl, "android_wifi_p2p", "wifi_p2p_supported", if (supported) 1.0 else 0.0, "bool"))
        if (!supported || !hasNearbyPermission()) return@buildList
        val manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return@buildList
        val channel = runCatching { manager.initialize(context, Looper.getMainLooper(), null) }.getOrNull() ?: return@buildList
        val latch = CountDownLatch(1)
        var formed: Boolean? = null
        var owner: Boolean? = null
        runCatching {
            manager.requestConnectionInfo(channel) { info ->
                formed = info?.groupFormed == true
                owner = info?.isGroupOwner == true
                latch.countDown()
            }
            latch.await(750, TimeUnit.MILLISECONDS)
        }
        formed?.let { add(sample(now, observationId, isControl, "android_wifi_p2p", "wifi_p2p_group_formed", if (it) 1.0 else 0.0, "bool")) }
        owner?.let { add(sample(now, observationId, isControl, "android_wifi_p2p", "wifi_p2p_group_owner", if (it) 1.0 else 0.0, "bool")) }
    }

    private fun nfc(now: Long, observationId: Long?, isControl: Boolean): List<ContextSample> = buildList {
        val adapter = runCatching { NfcAdapter.getDefaultAdapter(context) }.getOrNull()
        add(sample(now, observationId, isControl, "android_nfc", "nfc_hardware_present", if (adapter != null) 1.0 else 0.0, "bool"))
        if (adapter != null) {
            add(
                sample(
                    now,
                    observationId,
                    isControl,
                    "android_nfc",
                    "nfc_adapter_enabled",
                    if (runCatching { adapter.isEnabled }.getOrDefault(false)) 1.0 else 0.0,
                    "bool",
                    "tag_presence=foreground_only;no_background_polling=true"
                )
            )
        }
    }

    private fun sample(
        now: Long,
        observationId: Long?,
        isControl: Boolean,
        source: String,
        metric: String,
        value: Double,
        unit: String,
        metadata: String = ""
    ) = ContextSample(
        timestampMs = now,
        observationId = observationId,
        isControl = isControl,
        source = source,
        metric = metric,
        value = value,
        unit = unit,
        metadata = listOf(metadata, "window=instant").filter(String::isNotBlank).joinToString(";"),
        captureId = observationId?.let { "event:$it:instant" }.orEmpty()
    )

    private fun authorized(gate: HardwareGates.Gate): Boolean = HardwareGates.isAuthorized(context, gate)

    private fun hasNearbyPermission(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
}
