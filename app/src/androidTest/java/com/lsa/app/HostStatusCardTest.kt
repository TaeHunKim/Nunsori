package com.lsa.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lsa.app.ml.HostState
import com.lsa.app.ui.settings.HostStatusCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Each connection state shows its message and the button that fixes it (no OndolSaem needed). */
@RunWith(AndroidJUnit4::class)
class HostStatusCardTest {
    @get:Rule val rule = createComposeRule()
    private val clicks = mutableListOf<String>()

    private fun show(status: HostState.Status) = rule.setContent {
        HostStatusCard(
            HostState(status),
            onInstall = { clicks += "install" }, onUpdateApp = { clicks += "updateApp" },
            onOpen = { clicks += "open" }, onCheck = { clicks += "check" },
        )
    }

    @Test fun anOlderHostAsksToUpdateOndolSaem() {
        show(HostState.Status.HOST_TOO_OLD)
        rule.onNodeWithText("OndolSaem is too old for this version of Nunsori. Update OndolSaem.").assertIsDisplayed()
        rule.onNodeWithText("Update OndolSaem").performClick()
        assertEquals(listOf("install"), clicks) // the install page also serves updates
    }

    @Test fun anOlderClientAsksToUpdateNunsori() {
        show(HostState.Status.CLIENT_TOO_OLD)
        rule.onNodeWithText("OndolSaem no longer supports this version of Nunsori. Update Nunsori.").assertIsDisplayed()
        rule.onNodeWithText("Update Nunsori").performClick()
        assertEquals(listOf("updateApp"), clicks)
    }

    @Test fun aMissingHostAsksToInstallIt() {
        show(HostState.Status.NOT_INSTALLED)
        rule.onNodeWithText("Install OndolSaem").performClick()
        rule.onNodeWithText("Check again").performClick()
        assertEquals(listOf("install", "check"), clicks)
    }
}
