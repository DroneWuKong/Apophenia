package com.dronewukong.apophenia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationRepository
import com.dronewukong.apophenia.garmin.GarminBridge
import com.dronewukong.apophenia.health.HealthConnectAccess

class MainActivity : ComponentActivity() {
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val healthPermission = registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        GarminBridge.initialize(this)
        setContent { ApopheniaScreen(this) }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleDeepLink(intent) }

    fun requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestHealthPermissions() {
        if (HealthConnectAccess.sdkStatus(this) == androidx.health.connect.client.HealthConnectClient.SDK_AVAILABLE) {
            healthPermission.launch(HealthConnectAccess.readPermissions)
        }
    }

    fun shareExport(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share Apophenia export"))
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "apophenia" && uri.host == "log") {
            val label = uri.getQueryParameter("label") ?: "External observation"
            val note = uri.getQueryParameter("note") ?: ""
            val capturedAt = System.currentTimeMillis()
            ObservationRepository(this).log(ObservationKind.OBSERVATION, label, note, timestampMs = capturedAt, origin = ObservationOrigin.EXTERNAL)
        }
    }
}
