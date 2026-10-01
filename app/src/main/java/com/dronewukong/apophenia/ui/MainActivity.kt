package com.dronewukong.apophenia.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationRepository
import com.dronewukong.apophenia.garmin.GarminBridge

class MainActivity : ComponentActivity() {
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "apophenia" && uri.host == "log") {
            val label = uri.getQueryParameter("label") ?: "External observation"
            val note = uri.getQueryParameter("note") ?: ""
            ObservationRepository(this).log(ObservationKind.OBSERVATION, label, note)
        }
    }
}
