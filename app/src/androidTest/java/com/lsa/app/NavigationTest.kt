package com.lsa.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.lsa.app.data.prefs.SettingsStore
import com.lsa.app.indexing.AutoIndexMode
import kotlinx.coroutines.runBlocking
import org.junit.Before
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun skipOnboarding() {
        runBlocking { SettingsStore(ApplicationProvider.getApplicationContext()).completeOnboarding(AutoIndexMode.MANUAL, 180, true) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("How should your photos be indexed?").fetchSemanticsNodes().isEmpty() }
    }

    /** Drawer entries are clickable; the top-bar title with the same text is not. */
    private fun item(t: String) = rule.onNode(hasText(t) and hasClickAction())

    private fun openMenu() = rule.onNodeWithContentDescription("Open menu").performClick()

    @Test fun drawerShowsThreeMenusAndNavigates() {
        openMenu()
        item("Index").assertIsDisplayed()
        item("Search").assertIsDisplayed()
        item("Settings").assertIsDisplayed()
        item("Search").performClick()
        rule.onNodeWithText("Search your photos…").assertIsDisplayed()
        openMenu()
        item("Settings").performClick()
        rule.onNodeWithText("Folders to index").assertIsDisplayed()
    }
}
