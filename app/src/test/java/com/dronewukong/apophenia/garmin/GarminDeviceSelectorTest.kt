package com.dronewukong.apophenia.garmin

import com.garmin.android.connectiq.IQDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GarminDeviceSelectorTest {
    @Test
    fun refreshesStaleStatusesBeforeSelectingConnectedWatch() {
        val stale = IQDevice(1L, "Old watch").apply { status = IQDevice.IQDeviceStatus.CONNECTED }
        val epix = IQDevice(2L, "Epix Pro").apply { status = IQDevice.IQDeviceStatus.NOT_CONNECTED }

        val selected = GarminDeviceSelector.firstConnected(listOf(stale, epix)) {
            if (it.deviceIdentifier == epix.deviceIdentifier) IQDevice.IQDeviceStatus.CONNECTED
            else IQDevice.IQDeviceStatus.NOT_CONNECTED
        }

        assertEquals(epix.deviceIdentifier, selected?.deviceIdentifier)
        assertEquals(IQDevice.IQDeviceStatus.NOT_CONNECTED, stale.status)
        assertEquals(IQDevice.IQDeviceStatus.CONNECTED, epix.status)
    }

    @Test
    fun returnsNullWhenSdkReportsNoConnectedWatch() {
        val epix = IQDevice(2L, "Epix Pro")
        assertNull(GarminDeviceSelector.firstConnected(listOf(epix)) { IQDevice.IQDeviceStatus.NOT_CONNECTED })
    }
}
