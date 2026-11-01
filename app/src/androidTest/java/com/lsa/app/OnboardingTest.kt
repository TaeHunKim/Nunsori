package com.lsa.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.AutoIndexMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val store = SettingsStore(ApplicationProvider.getApplicationContext())
    private val filesTitle = "What should be indexed?"
    private val modelTitle = "Choose a search model"
    private val autoTitle = "When should files be indexed?"

    @Before fun showAgain() {
        runBlocking { store.clearOnboardingForTest() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(filesTitle).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitGone(text: String) = rule.waitUntil(5_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty() }

    private fun toAutoStep() {
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText(modelTitle).assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText(autoTitle).assertIsDisplayed()
    }

    @Test fun threeStepsLeadToTheAutoIndexChoice() {
        rule.onNodeWithText("Step 1 of 3").assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsNotEnabled()
        rule.onNodeWithText("Folders to index").assertIsDisplayed()
        rule.onNodeWithText("Next").performClick()
        rule.onNodeWithText("Step 2 of 3").assertIsDisplayed()
        rule.onNodeWithText("Embedding model").assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsEnabled().performClick()
        rule.onNodeWithText(filesTitle).assertIsDisplayed()
        toAutoStep()
        rule.onNodeWithText("You can change all of this later in Settings.").assertIsDisplayed()
    }

    @Test fun mustChooseAnAutoIndexModeBeforeFinishing() {
        toAutoStep()
        rule.onNodeWithText("Finish").assertIsNotEnabled()
        rule.onNodeWithText("Manual only").performClick()
        rule.onNodeWithText("Finish").assertIsEnabled().performClick()
        waitGone(autoTitle)
        assertEquals(AutoIndexMode.MANUAL, runBlocking { store.autoMode.first() })
        assertTrue(runBlocking { store.onboardingDone.first() })
    }

    @Test fun scheduledShowsTimeAndChargingOptions() {
        toAutoStep()
        rule.onNodeWithText("At a set time").performClick()
        rule.onNodeWithText("Only while charging").assertIsDisplayed()
        rule.onNodeWithText("Change").assertIsDisplayed()
        rule.onNodeWithText("Finish").assertIsEnabled().performClick()
        waitGone(autoTitle)
        assertEquals(AutoIndexMode.SCHEDULED, runBlocking { store.autoMode.first() })
        assertTrue(runBlocking { store.scheduleChargingOnly.first() })
    }
}
