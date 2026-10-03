package com.dronewukong.apophenia.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Observation").assertIsDisplayed()
        compose.onNodeWithText("THAT WAS WEIRD").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Sound / noise"))
        compose.onNodeWithText("Sound / noise").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("THAT WAS WEIRD"))
        compose.onNodeWithText("THAT WAS WEIRD").assertIsDisplayed().performClick()
        compose.onNodeWithText("Timeline").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithText("That was weird").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun logsVibeAndEgressWithoutLeavingTheCaptureSurface() {
        dismissContextIntroIfPresent()

        compose.onNodeWithText("Bad 🙁").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("FUCK THIS, I'M OUT").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Timeline").performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                compose.onNodeWithText("Bad 🙁").assertIsDisplayed()
                compose.onNodeWithText("FUCK THIS, I'M OUT").assertIsDisplayed()
                true
            }.getOrDefault(false)
        }
    }

    @Test
    fun settingsExposeOptionalContextPermissions() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Phone sensors").assertIsDisplayed()
        compose.onNodeWithText("Location + weather").assertIsDisplayed()
        listOf(
            "Health Connect",
            "Bluetooth presence",
            "Wi-Fi presence",
            "Network state",
            "Device circumstances",
            "Tier-2 contents",
            "Octopod observer"
        ).forEach(::scrollSettingsTo)
    }

    private fun dismissContextIntroIfPresent() {
        if (compose.onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("Not now").performClick()
        }
    }

    private fun scrollSettingsTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
        compose.onNodeWithText(text).assertIsDisplayed()
    }
}
