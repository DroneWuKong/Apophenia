package com.dronewukong.apophenia.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dronewukong.apophenia.data.ObservationDb
import com.dronewukong.apophenia.demo.DemoModeManager
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
        DemoModeManager.disable(compose.activity)
        ObservationDb(compose.activity).apply { deleteAllData(); close() }
        ObservationDb(compose.activity, DemoModeManager.DATABASE_NAME).apply { deleteAllData(); close() }
        compose.waitForIdle()
    }

    @After
    fun cleanUp() {
        DemoModeManager.disable(compose.activity)
        ObservationDb(compose.activity).apply { deleteAllData(); close() }
        ObservationDb(compose.activity, DemoModeManager.DATABASE_NAME).apply { deleteAllData(); close() }
    }

    @Test
    fun launchesAndLogsOneTapObservation() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("THAT WAS WEIRD").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Observation"))
        compose.onNodeWithText("Observation").assertIsDisplayed()
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
        compose.onNode(hasText("gates armed", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        scrollSettingsTo("Phone sensors")
        scrollSettingsTo("Location + weather")
        listOf(
            "Health Connect",
            "Total evidence + presets",
            "Bluetooth presence",
            "Wi-Fi presence",
            "Network state",
            "Device circumstances",
            "Tier-2 contents",
            "OBD-II drive session",
            "Native Automotive properties",
            "Encrypted evidence media",
            "Octopod observer",
            "Explicit LAN export"
        ).forEach(::scrollSettingsTo)
    }

    @Test
    fun hypothesisActionOpensStructuredPreregistration() {
        dismissContextIntroIfPresent()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Hypothesis"))
        compose.onNodeWithText("Hypothesis").performClick()
        compose.onNodeWithText("Record hypothesis").assertIsDisplayed()
        compose.onNodeWithText("Register the expectation before opening results. Once an eligible result is viewed, this registration locks.").assertIsDisplayed()
        compose.onNodeWithText("Exact context metric").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Expected direction").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun settingsOpenOmniprobeWithHonestEmptyState() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Settings").performClick()
        scrollSettingsTo("Omniprobe")
        compose.onNodeWithText("Open Omniprobe").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Every planned gate, every stored value, every explained gap").assertIsDisplayed()
        compose.onNodeWithText("No events yet. Log an observation, then return to inspect its circumstances.").assertIsDisplayed()
    }

    @Test
    fun demoModeLoadsIsolatedFixturesAndShowsPersistentBadge() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Settings").performClick()
        scrollSettingsTo("Demo mode")
        compose.onNodeWithContentDescription("Toggle demo mode").performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("DEMO DATA", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Demo data badge").assertIsDisplayed()
        compose.onNodeWithText("Timeline").performClick()
        compose.onNodeWithContentDescription("Demo data badge").assertIsDisplayed()
    }

    @Test
    fun dataOnlyExportShowsVerifiedManifestBeforeAnyRoute() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Settings").performClick()
        scrollSettingsTo("Prepare data-only export")
        compose.onNodeWithText("Prepare data-only export").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Manifest preview · Data-only").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Raw AV: no · Tier-2 contents: no").assertIsDisplayed()
        compose.onNodeWithText("data/apophenia-data.json").assertIsDisplayed()
        compose.onNodeWithText("Cancel + delete").performClick()
    }

    @Test
    fun rawSqliteSnapshotShowsCheckpointedPreviewBeforeAnyRoute() {
        dismissContextIntroIfPresent()
        compose.onNodeWithText("Settings").performClick()
        scrollSettingsTo("Prepare raw SQLite snapshot")
        compose.onNodeWithText("Prepare raw SQLite snapshot").performClick()
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithText("Raw SQLite preview").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("schema_version 9", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel + delete").performClick()
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
