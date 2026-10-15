package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.opentrafficmap.citstogo.SettingsPage
import org.opentrafficmap.citstogo.ThemeMode
import org.opentrafficmap.citstogo.TxApprovalPromptState
import org.opentrafficmap.citstogo.TxApprovalScreen
import org.opentrafficmap.citstogo.livemap.MapHistoryDays
import org.opentrafficmap.citstogo.livemap.MapStationTimeout
import org.opentrafficmap.citstogo.srem.SremProfile

/** Covers the reworked TX unlock flow: confirmation-gated slider and the settings TX card. */
class TxApprovalUiTest {
    @get:Rule val compose = createComposeRule()

    private fun swipeRight(tag: String, startFraction: Float = 0.02f) {
        compose.onNodeWithTag(tag).performTouchInput {
            swipe(Offset(width * startFraction, height / 2f), Offset(width * 0.98f, height / 2f), 500)
        }
        compose.waitForIdle()
    }

    @Test
    fun approvalSliderStaysLockedUntilRegulationIsConfirmed() {
        var grants = 0
        compose.setContent {
            MaterialTheme {
                TxApprovalScreen(
                    state = TxApprovalPromptState.Ready,
                    onGrantApproval = { grants++ },
                    onDismiss = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.onNodeWithText("Regulation").assertIsDisplayed()
        compose.onNodeWithText("Implications").assertIsDisplayed()
        compose.onNodeWithText("What approval enables").assertDoesNotExist()

        swipeRight("tx_approval_slider")
        compose.runOnIdle { assertEquals(0, grants) }

        compose.onNodeWithTag("tx_approval_confirm").performClick()
        swipeRight("tx_approval_slider")
        compose.runOnIdle { assertEquals(1, grants) }
    }

    @Test
    fun settingsTxCardShowsUnlockNoteAndRevokes() {
        var revoked = 0
        compose.setContent { settingsPage(txApproved = true, onRevokeTxApproval = { revoked++ }) }

        compose.onNodeWithText("TX Transmission").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("shake this phone firmly", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // onNodeWithText fails on duplicates, proving revoke moved out of the debug card.
        compose.onNodeWithText("Revoke TX Approval").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, revoked) }
    }

    @Test
    fun settingsTxCardDisablesRevokeWhenApprovalInactive() {
        compose.setContent { settingsPage(txApproved = false) }

        compose.onNodeWithText("TX Approval not active").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Revoke TX Approval").assertDoesNotExist()
    }

    @Composable
    private fun settingsPage(
        txApproved: Boolean,
        onRevokeTxApproval: () -> Unit = {},
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsPage(
                mqttUri = "mqtt://localhost:1883",
                mqttEnabled = false,
                onMqttEnabledChange = {},
                nodeId = "node-1",
                maxQueueLength = "100",
                maxQueueAgeSeconds = "0.2",
                mapStationTimeoutSeconds = MapStationTimeout.DEFAULT_SECONDS,
                mapHistoryDays = MapHistoryDays.ALL,
                txApproved = txApproved,
                sremProfile = SremProfile.PEDESTRIAN,
                bridgeRunning = false,
                bluetoothEnrollmentRunning = false,
                bluetoothEnrollmentMessage = "",
                bluetoothEnrollmentError = false,
                debugMenuEnabled = false,
                onDebugMenuEnabledChange = {},
                themeMode = ThemeMode.System,
                onThemeModeChange = {},
                displayAlwaysOnEnabled = false,
                onDisplayAlwaysOnEnabledChange = {},
                stationDiscoveryNotificationEnabled = false,
                onStationDiscoveryNotificationChange = {},
                appUpdateNotificationEnabled = false,
                onAppUpdateNotificationChange = {},
                onEnrollBluetooth = {},
                onRevokeTxApproval = onRevokeTxApproval,
                onSave = { _, _, _, _, _, _, _ -> },
            )
        }
    }
}
