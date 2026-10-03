package com.dronewukong.apophenia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var locationResult: ((Boolean, String) -> Unit)? = null
    private var notificationResult: ((Boolean, String) -> Unit)? = null
    private var healthResult: ((String) -> Unit)? = null
    private var requestedHealthPermissions: Set<String> = emptySet()

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

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

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
    }
}
