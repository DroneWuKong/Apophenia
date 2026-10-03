package com.dronewukong.apophenia.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.health.HealthConnectAccess
import com.dronewukong.apophenia.hardware.HardwareGates
import com.dronewukong.apophenia.mavlink.UsbMavlinkDevice
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var locationResult: ((Boolean, String) -> Unit)? = null
    private var notificationResult: ((Boolean, String) -> Unit)? = null
    private var radioResult: ((Boolean, String) -> Unit)? = null
    private var bluetoothResult: ((Boolean, String) -> Unit)? = null
    private var wifiResult: ((Boolean, String) -> Unit)? = null
    private var networkResult: ((Boolean, String) -> Unit)? = null
    private var vehicleBluetoothResult: ((Boolean, String) -> Unit)? = null
    private var audioResult: ((Boolean, String) -> Unit)? = null
    private var cameraResult: ((Boolean, String) -> Unit)? = null
    private var screenCaptureResult: ((Int, Intent?) -> Unit)? = null
    private var tier2Result: ((Boolean, String) -> Unit)? = null
    private var pendingTier2Gate: HardwareGates.Gate? = null
    private var healthResult: ((String) -> Unit)? = null
    private var requestedHealthPermissions: Set<String> = emptySet()
    private var usbPermissionReceiver: BroadcastReceiver? = null

    var permissionRevision by mutableIntStateOf(0)
        private set

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allowed = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true || grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        permissionRevision++
        locationResult?.invoke(
            allowed,
            if (allowed) "Location enabled. Weather will be attached to new observations."
            else "Location was not enabled. You can retry or open Android app settings."
        )
        locationResult = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRevision++
        notificationResult?.invoke(
            allowed,
            if (allowed) "Recorder notifications enabled."
            else "Notifications are off. The recorder can run, but Android may hide its status."
        )
        notificationResult = null
    }
    private val radioPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val allowed = hasRadioPermissions()
        permissionRevision++
        radioResult?.invoke(
            allowed,
            if (allowed) "Radio survey enabled. Snapshots contain aggregate signal data only."
            else "Radio access was not enabled. You can retry or open Android app settings."
        )
        radioResult = null
    }
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val allowed = hasBluetoothPermissions()
        permissionRevision++
        bluetoothResult?.invoke(
            allowed,
            if (allowed) "Bluetooth scan access enabled." else "Bluetooth scan access was not enabled."
        )
        bluetoothResult = null
    }
    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val allowed = hasWifiPermissions()
        permissionRevision++
        wifiResult?.invoke(
            allowed,
            if (allowed) "Wi-Fi scan access enabled." else "Wi-Fi scan access was not enabled."
        )
        wifiResult = null
    }
    private val networkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRevision++
        networkResult?.invoke(
            allowed,
            if (allowed) "Phone-state signal access enabled." else "Phone-state access was not enabled; basic connectivity still works."
        )
        networkResult = null
    }
    private val vehicleBluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRevision++
        vehicleBluetoothResult?.invoke(
            allowed,
            if (allowed) "Bluetooth adapter access enabled." else "Bluetooth adapter access was not enabled."
        )
        vehicleBluetoothResult = null
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRevision++
        audioResult?.invoke(
            allowed,
            if (allowed) "Microphone access enabled for the armed audio ring."
            else "Microphone access was not enabled; the audio ring remains stopped."
        )
        audioResult = null
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        permissionRevision++
        cameraResult?.invoke(
            allowed,
            if (allowed) "Camera access enabled for the armed video ring."
            else "Camera access was not enabled; camera rings remain stopped."
        )
        cameraResult = null
    }
    private val screenCapturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        permissionRevision++
        screenCaptureResult?.invoke(result.resultCode, result.data)
        screenCaptureResult = null
    }
    private val tier2Permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val gate = pendingTier2Gate
        val allowed = gate != null && hasTier2PlatformAccess(gate)
        permissionRevision++
        tier2Result?.invoke(
            allowed,
            if (allowed) "Android access granted for ${gate?.name}." else "Android access was not granted; the gate remains visible but captures no contents."
        )
        pendingTier2Gate = null
        tier2Result = null
    }
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        val coreGranted = granted.containsAll(HealthConnectAccess.readPermissions)
        permissionRevision++
        healthResult?.invoke(
            when {
                coreGranted -> "Health Connect connected. Read-only context is enabled."
                granted.isNotEmpty() -> "Health Connect partially connected. Available records will still be used."
                else -> "Health Connect access was not granted. Observation logging still works."
            }
        )
        requestedHealthPermissions = emptySet()
        healthResult = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GarminBridge.initialize(this)
        setContent { ApopheniaScreen(this) }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
    }

    override fun onDestroy() {
        usbPermissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbPermissionReceiver = null
        super.onDestroy()
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun hasRadioPermissions(): Boolean {
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        return location && nearby
    }

    fun hasBluetoothPermissions(): Boolean {
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        return location && nearby
    }

    fun hasWifiPermissions(): Boolean {
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        return location && nearby
    }

    fun hasNetworkSignalPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    fun hasVehicleBluetoothPermission(): Boolean = Build.VERSION.SDK_INT < 31 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun requestAudioPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasAudioPermission()) {
            onResult(true, "Microphone access is already enabled.")
            return
        }
        audioResult = onResult
        audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun requestCameraPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasCameraPermission()) {
            onResult(true, "Camera access is already enabled.")
            return
        }
        cameraResult = onResult
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    fun requestScreenCapture(onResult: (Int, Intent?) -> Unit) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            onResult(RESULT_CANCELED, null)
            return
        }
        screenCaptureResult = onResult
        screenCapturePermission.launch(manager.createScreenCaptureIntent())
    }

    fun requestVehicleBluetoothPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasVehicleBluetoothPermission()) {
            onResult(true, "Bluetooth adapter access is already enabled.")
            return
        }
        vehicleBluetoothResult = onResult
        vehicleBluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun usbMavlinkDevices(): List<UsbMavlinkDevice> {
        val manager = getSystemService(UsbManager::class.java) ?: return emptyList()
        return manager.deviceList.values.map { device ->
            val label = listOfNotNull(
                device.productName?.takeIf(String::isNotBlank),
                device.manufacturerName?.takeIf(String::isNotBlank)
            ).distinct().joinToString(" · ").ifBlank {
                "USB ${device.vendorId.toString(16).padStart(4, '0')}:${device.productId.toString(16).padStart(4, '0')}"
            }
            UsbMavlinkDevice(device.deviceId, label)
        }.sortedBy { it.displayName.lowercase() }
    }

    fun requestUsbMavlinkPermission(deviceId: Int, onResult: (Boolean, String) -> Unit) {
        val manager = getSystemService(UsbManager::class.java)
        val device = manager?.deviceList?.values?.firstOrNull { it.deviceId == deviceId }
        if (manager == null || device == null) {
            onResult(false, "USB device is no longer attached")
            return
        }
        if (manager.hasPermission(device)) {
            onResult(true, "USB device access is already enabled")
            return
        }
        usbPermissionReceiver?.let { runCatching { unregisterReceiver(it) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_MAVLINK_USB_PERMISSION) return
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                runCatching { unregisterReceiver(this) }
                usbPermissionReceiver = null
                onResult(granted, if (granted) "USB device access enabled" else "USB device access was not enabled")
            }
        }
        usbPermissionReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter(ACTION_MAVLINK_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val pending = PendingIntent.getBroadcast(
            this,
            deviceId,
            Intent(ACTION_MAVLINK_USB_PERMISSION).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        manager.requestPermission(device, pending)
    }

    fun hasTier2PlatformAccess(gate: HardwareGates.Gate): Boolean = when (gate) {
        HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE ->
            androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE ->
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        else -> true
    }

    fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun requestTier2PlatformAccess(
        gate: HardwareGates.Gate,
        onResult: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        if (hasTier2PlatformAccess(gate)) {
            onResult(true, "Android access is already granted for ${gate.name}.")
            return
        }
        if (gate == HardwareGates.Gate.LIVE_NOTIFICATION_CONTENTS_CAPTURE) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            onResult(false, "Opened Android Notification Access. Enable Apophenia, then return here.")
            return
        }
        val permission = when (gate) {
            HardwareGates.Gate.LIVE_CALENDAR_CONTENTS_CAPTURE -> Manifest.permission.READ_CALENDAR
            HardwareGates.Gate.LIVE_CONTACTS_CONTENTS_CAPTURE -> Manifest.permission.READ_CONTACTS
            HardwareGates.Gate.LIVE_MESSAGE_METADATA_CAPTURE -> Manifest.permission.READ_SMS
            else -> {
                onResult(true, "No additional Android permission is required.")
                return
            }
        }
        pendingTier2Gate = gate
        tier2Result = onResult
        tier2Permission.launch(arrayOf(permission))
    }

    fun requestUsageAccess(onResult: (String) -> Unit = {}) {
        if (hasUsageAccess()) {
            onResult("Usage access is already enabled.")
            return
        }
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        onResult("Opened Android Usage Access. Enable Apophenia to include the foreground app package.")
    }

    fun requestBluetoothPermissions(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasBluetoothPermissions()) {
            onResult(true, "Bluetooth scan access is already enabled.")
            return
        }
        bluetoothResult = onResult
        bluetoothPermission.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        }.toTypedArray())
    }

    fun requestWifiPermissions(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasWifiPermissions()) {
            onResult(true, "Wi-Fi scan access is already enabled.")
            return
        }
        wifiResult = onResult
        wifiPermission.launch(buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }.toTypedArray())
    }

    fun requestNetworkSignalPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasNetworkSignalPermission()) {
            onResult(true, "Phone-state signal access is already enabled.")
            return
        }
        networkResult = onResult
        networkPermission.launch(Manifest.permission.READ_PHONE_STATE)
    }

    fun requestRadioPermissions(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasRadioPermissions()) {
            onResult(true, "Radio access is already enabled.")
            return
        }
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_SCAN)
        }.toTypedArray()
        radioResult = onResult
        radioPermission.launch(permissions)
    }

    fun requestLocationPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasLocationPermission()) {
            onResult(true, "Location is already enabled. Weather will be attached to new observations.")
            return
        }
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val requestedBefore = prefs.getBoolean(KEY_LOCATION_REQUESTED, false)
        val permanentlyDenied = requestedBefore &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (permanentlyDenied) {
            openAppSettings()
            onResult(false, "Location is blocked in Android. Opened app settings so you can enable it.")
            return
        }
        prefs.edit().putBoolean(KEY_LOCATION_REQUESTED, true).apply()
        locationResult = onResult
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun requestNotificationPermission(onResult: (Boolean, String) -> Unit = { _, _ -> }) {
        if (hasNotificationPermission()) {
            onResult(true, "Recorder notifications are already enabled.")
            return
        }
        val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
        val requestedBefore = prefs.getBoolean(KEY_NOTIFICATION_REQUESTED, false)
        if (requestedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openAppSettings()
            onResult(false, "Notifications are blocked in Android. Opened app settings so you can enable them.")
            return
        }
        prefs.edit().putBoolean(KEY_NOTIFICATION_REQUESTED, true).apply()
        notificationResult = onResult
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestHealthPermissions(onResult: (String) -> Unit = {}) {
        when (HealthConnectAccess.sdkStatus(this)) {
            HealthConnectClient.SDK_AVAILABLE -> lifecycleScope.launch {
                runCatching {
                    val client = HealthConnectClient.getOrCreate(this@MainActivity)
                    val granted = client.permissionController.getGrantedPermissions()
                    if (granted.containsAll(HealthConnectAccess.readPermissions)) {
                        startActivity(HealthConnectClient.getHealthConnectManageDataIntent(this@MainActivity))
                        onResult("Health Connect is already connected. Opened Health Connect settings.")
                    } else {
                        requestedHealthPermissions = HealthConnectAccess.requestablePermissions(this@MainActivity)
                        healthResult = onResult
                        healthPermission.launch(requestedHealthPermissions)
                    }
                }.onFailure {
                    healthResult = null
                    requestedHealthPermissions = emptySet()
                    onResult("Health Connect could not open: ${it.message ?: "unknown error"}")
                }
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                openHealthConnectStore()
                onResult("Health Connect needs an update. Opened its store page.")
            }
            else -> onResult("Health Connect is not available on this device.")
        }
    }

    private fun openAppSettings() {
        startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openHealthConnectStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${HealthConnectAccess.PROVIDER_PACKAGE}"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${HealthConnectAccess.PROVIDER_PACKAGE}"))
        runCatching { startActivity(market) }.getOrElse { startActivity(web) }
    }

    fun shareExport(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Apophenia export"))
    }

    companion object {
        private const val PERMISSION_PREFS = "permission_requests"
        private const val KEY_LOCATION_REQUESTED = "location_requested"
        private const val KEY_NOTIFICATION_REQUESTED = "notification_requested"
        private const val ACTION_MAVLINK_USB_PERMISSION = "com.dronewukong.apophenia.MAVLINK_USB_PERMISSION"
    }
}
