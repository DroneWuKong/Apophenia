package com.dronewukong.apophenia.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dronewukong.apophenia.data.ObservationDb
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearData() {
        ObservationDb(compose.activity).apply { deleteAllData(); close() }
    }

    @After
    fun cleanUp() {
        ObservationDb(compose.activity).apply { deleteAllData(); close() }
    }

    @Test
    fun launchesAndLogsOneTapObservation() {
        compose.onNodeWithText("THAT WAS WEIRD").assertIsDisplayed().performClick()
        compose.onNodeWithText("Timeline").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithText("That was weird").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }
}
