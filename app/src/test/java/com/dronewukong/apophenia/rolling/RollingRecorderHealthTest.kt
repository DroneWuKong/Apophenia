package com.dronewukong.apophenia.rolling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RollingRecorderHealthTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RollingRecorderHealth.clear(context)
    }

    @After
    fun tearDown() = RollingRecorderHealth.clear(context)

    @Test
    fun recordsStartHeartbeatsCountsAndFailures() {
        RollingRecorderHealth.recordStarted(context, 100L)
        RollingRecorderHealth.recordBatch(context, "sensor", 3, 200L)
        RollingRecorderHealth.recordBatch(context, "device", 2, 300L)
        RollingRecorderHealth.recordFailure(context, IllegalStateException("collector failed"), 400L)

        val status = RollingRecorderHealth.snapshot(context)
        assertEquals(100L, status.lastStartAtMs)
        assertEquals(300L, status.lastSampleAtMs)
        assertEquals(200L, status.lastSensorSampleAtMs)
        assertEquals(300L, status.lastDeviceSampleAtMs)
        assertEquals(5L, status.capturedSampleCount)
        assertEquals(1L, status.failureCount)
        assertTrue(status.lastError.contains("collector failed"))
    }
}
