package com.dronewukong.apophenia.garmin

import com.garmin.android.connectiq.IQDevice

object GarminDeviceSelector {
    /** Refreshes SDK device objects before selecting one; their status fields are not authoritative. */
    fun firstConnected(
        devices: List<IQDevice>,
        statusOf: (IQDevice) -> IQDevice.IQDeviceStatus
    ): IQDevice? {
        devices.forEach { device -> device.status = statusOf(device) }
        return devices.firstOrNull { it.status == IQDevice.IQDeviceStatus.CONNECTED }
    }
}
