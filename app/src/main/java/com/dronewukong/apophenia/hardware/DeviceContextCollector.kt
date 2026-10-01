package com.dronewukong.apophenia.hardware

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import com.dronewukong.apophenia.data.ContextSample

class DeviceContextCollector(private val context: Context) {
    fun collect(observationId: Long?, isControl: Boolean): List<ContextSample> {
        val now = System.currentTimeMillis()
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val networkCode = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 2.0
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> 1.0
            else -> 0.0
        }
        fun s(metric: String, value: Double, unit: String) = ContextSample(timestampMs=now, observationId=observationId, isControl=isControl, source="device", metric=metric, value=value, unit=unit)
        return listOf(
            s("battery_pct", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toDouble(), "%"),
            s("battery_current_ma", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble()/1000.0, "mA"),
            s("screen_interactive", if (pm.isInteractive) 1.0 else 0.0, "bool"),
            s("network_type", networkCode, "enum")
        )
    }
}
