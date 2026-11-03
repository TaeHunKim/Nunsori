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
            onOpen = { clicks += "open" }, onCheck = { clicks += "check" }, onApprove = { clicks += "approve" },
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

    @Test fun anUnknownKeyAsksForApprovalInOndolSaem() {
        show(HostState.Status.NOT_APPROVED)
        rule.onNodeWithText("Allow in OndolSaem").performClick()
        assertEquals(listOf("approve"), clicks)
    }

    @Test fun theFaceModelIsListedWithTheOthers() {
        val face = io.github.taehunkim.ondolsaem.ModelInfo().apply { category = "face"; id = "face-sface"; displayName = "SFace"; ready = true }
        rule.setContent {
            HostStatusCard(HostState(HostState.Status.CONNECTED, mapOf("face" to face), "0.3.0", 2), {}, {}, {}, {})
        }
        rule.onNodeWithText("Faces (People)").assertIsDisplayed()
        rule.onNodeWithText("SFace").assertIsDisplayed()
    }

    @Test fun anOlderOndolSaemSaysFacesNeedAnUpdate() {
        val emb = io.github.taehunkim.ondolsaem.ModelInfo().apply { category = "embedding"; id = "eg2-440m"; displayName = "EG2"; ready = true }
        rule.setContent {
            HostStatusCard(HostState(HostState.Status.CONNECTED, mapOf("embedding" to emb), "0.2.0", 1), {}, {}, {}, {})
        }
        rule.onNodeWithText("needs OndolSaem 0.3.0").assertIsDisplayed()
    }
}
