package com.dronewukong.apophenia.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RadioSnapshotAggregatorTest {
    @Test
    fun aggregatesBandsAndSignalsWithoutIdentifiers() {
        val metrics = RadioSnapshotAggregator.aggregate(
            wifi = listOf(WifiSignal(2_437, -40), WifiSignal(5_180, -60), WifiSignal(5_955, -80)),
            ble = listOf(BleSignal(-50), BleSignal(-70)),
            cells = listOf(CellSignal("NR", -95, true), CellSignal("LTE", -105, false))
        ).associateBy { it.name }

        assertEquals(3.0, metrics.getValue("radio_wifi_ap_count").value, 0.0)
        assertEquals(1.0, metrics.getValue("radio_wifi_2g_count").value, 0.0)
        assertEquals(1.0, metrics.getValue("radio_wifi_5g_count").value, 0.0)
        assertEquals(1.0, metrics.getValue("radio_wifi_6g_count").value, 0.0)
        assertEquals(-60.0, metrics.getValue("radio_wifi_ap_rssi_mean_dbm").value, 0.0)
        assertEquals(2.0, metrics.getValue("radio_ble_advertiser_count").value, 0.0)
        assertEquals(1.0, metrics.getValue("radio_cell_nr_count").value, 0.0)
        assertFalse(metrics.keys.any { it.contains("ssid") || it.contains("address") || it.contains("cell_id") })
    }

    @Test
    fun emptySnapshotStillReportsComparableCounts() {
        val metrics = RadioSnapshotAggregator.aggregate(emptyList(), emptyList(), emptyList()).associateBy { it.name }
        assertEquals(0.0, metrics.getValue("radio_wifi_ap_count").value, 0.0)
        assertEquals(0.0, metrics.getValue("radio_ble_advertiser_count").value, 0.0)
        assertEquals(0.0, metrics.getValue("radio_cell_count").value, 0.0)
    }
}
