package com.dronewukong.apophenia.widget

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.dronewukong.apophenia.data.Observation
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.data.ObservationKind
import com.dronewukong.apophenia.data.ObservationOrigin
import com.dronewukong.apophenia.data.ObservationStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ObservationWidgetProviderTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        )
    }

    @After
    fun tearDown() {
        ObservationStore.resetForTests()
        context.deleteDatabase("apophenia.db")
    }

    @Test
    fun widgetTapLogsTimestampFirstVibeWithoutNavigation() {
        val before = System.currentTimeMillis()
        ObservationWidgetProvider().onReceive(
            context,
            Intent(context, ObservationWidgetProvider::class.java)
                .setAction(ObservationWidgetProvider.ACTION_VIBE)
                .putExtra(ObservationWidgetProvider.EXTRA_VIBE_RATING, 3)
                .putExtra(ObservationWidgetProvider.EXTRA_EGRESS, false)
        )
        val after = System.currentTimeMillis()

        val stored = awaitObservation()
        assertTrue(stored.timestampMs in before..after)
        assertEquals(ObservationKind.VIBE, stored.kind)
        assertEquals("Bad 🙁", stored.label)
        assertEquals(3, stored.vibeRating)
        assertFalse(stored.egress)
        assertEquals(ObservationOrigin.WIDGET, stored.origin)
    }

    @Test
    fun widgetEgressIsDistinctVibeFive() {
        ObservationWidgetProvider().onReceive(
            context,
            Intent(context, ObservationWidgetProvider::class.java)
                .setAction(ObservationWidgetProvider.ACTION_VIBE)
                .putExtra(ObservationWidgetProvider.EXTRA_VIBE_RATING, 5)
                .putExtra(ObservationWidgetProvider.EXTRA_EGRESS, true)
        )

        val stored = awaitObservation()
        assertEquals("NOPE, I'M OUT", stored.label)
        assertEquals(5, stored.vibeRating)
        assertTrue(stored.egress)
    }

    private fun awaitObservation(): Observation {
        repeat(100) {
            ObservationDb(context).use { db -> db.observations().singleOrNull()?.let { return it } }
            Thread.sleep(10)
        }
        error("Widget observation was not stored")
    }
}
