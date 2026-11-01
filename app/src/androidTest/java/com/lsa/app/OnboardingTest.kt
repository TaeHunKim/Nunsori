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
    private val title = "How should your photos be indexed?"

    @Before fun showAgain() {
        runBlocking { store.clearOnboardingForTest() }
        rule.waitUntil(5_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun mustChooseBeforeContinuing() {
        rule.onNodeWithText(title).assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Manual only").performClick()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        assertEquals(AutoIndexMode.MANUAL, runBlocking { store.autoMode.first() })
        assertTrue(runBlocking { store.onboardingDone.first() })
    }

    @Test fun scheduledShowsTimeAndChargingOptions() {
        rule.onNodeWithText("At a set time").performClick()
        rule.onNodeWithText("Only while charging").assertIsDisplayed()
        rule.onNodeWithText("Change").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty() }
        assertEquals(AutoIndexMode.SCHEDULED, runBlocking { store.autoMode.first() })
        assertTrue(runBlocking { store.scheduleChargingOnly.first() })
    }
}
