package com.dronewukong.apophenia.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class HomeContextProviderTest {
    @Test
    fun parsesOnlyAggregateHomeAndCameraContext() {
        val samples = HomeContextParser.parse(
            homeJson = """{"ok":true,"data":{"contacts":{"open":["Front"],"total":3},"motion":{"active":[],"total":2},"leak":{"wet":[],"total":1},"locks":{"unlocked":["Back"],"total":2},"lights":{"on":4,"total":9},"presence":{"home":["A","B"],"total":3},"temps":[{"name":"Kitchen","tempF":70},{"name":"Office","tempF":74}],"lowBatteries":[{"name":"Sensor","pct":12}]}}""",
            camerasJson = """{"ok":true,"data":{"cameras":[{"name":"Private name","connected":true,"enabled":true},{"name":"Other","connected":false,"enabled":true}]}}""",
            observationId = 7,
            isControl = false,
            timestampMs = 99
        )
        val values = samples.associate { it.metric to it.value }
        assertEquals(1.0, values["home_contacts_active"])
        assertEquals(2.0, values["home_presence_active"])
        assertEquals(72.0, values["home_temperature_avg_f"])
        assertEquals(1.0, values["home_cameras_connected"])
        assertFalse(samples.any { it.metadata.contains("Front") || it.metadata.contains("Private") })
        assertEquals(setOf("octopod"), samples.map { it.source }.toSet())
    }

    @Test
    fun malformedOrUnavailableTilesFailSoft() {
        assertEquals(emptyList<com.dronewukong.apophenia.data.ContextSample>(), HomeContextParser.parse("not-json", null, null, true, 1))
    }
}
