package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.opentrafficmap.citstogo.FirmwareFlashingPhase
import org.opentrafficmap.citstogo.FirmwareFlashingState
import org.opentrafficmap.citstogo.FlashingPage

/** Covers the auto-stop flow: the running-receiver hint and the un-gated flash slider. */
class FlashingPageUiTest {
    @get:Rule val compose = createComposeRule()

    private val runningHint =
        "The receiver is running. It will be stopped automatically before flashing starts."

    private fun flashingState(
        phase: FirmwareFlashingPhase = FirmwareFlashingPhase.Ready,
        message: String = "ESP32-C5 USB interface detected. Pull the slider fully to flash.",
        deviceName: String? = "Espressif ESP32-C5",
    ) = FirmwareFlashingState(
        appVersion = "test",
        releaseTag = "v1.0.0",
        firmwareName = "firmware.bin",
        deviceName = deviceName,
        phase = phase,
        message = message,
    )

    private fun flashingPage(
        state: FirmwareFlashingState,
        bridgeRunning: Boolean,
        onFlash: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    FlashingPage(
                        state = state,
                        log = emptyList(),
                        bridgeRunning = bridgeRunning,
                        onRetryRelease = {},
                        onSelectRelease = {},
                        onChooseCustomFirmware = {},
                        onUseReleaseFirmware = {},
                        onFlash = onFlash,
                    )
                }
            }
        }
    }

    @Test
    fun runningReceiverHintShowsWhenReadyToFlash() {
        flashingPage(state = flashingState(), bridgeRunning = true)

        compose.onNodeWithText(runningHint).assertIsDisplayed()
    }

    @Test
    fun runningReceiverHintShowsWhileWaitingForDevice() {
        flashingPage(
            state = flashingState(
                phase = FirmwareFlashingPhase.WaitingForDevice,
                message = "Firmware release selected. Connect an ESP32-C5 over USB.",
                deviceName = null,
            ),
            bridgeRunning = true,
        )

        compose.onNodeWithText(runningHint).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sliderTriggersFlashWhileReceiverIsRunning() {
        var flashes = 0
        flashingPage(state = flashingState(), bridgeRunning = true, onFlash = { flashes++ })

        compose.onNodeWithTag("flash_receiver_slider").performScrollTo().performTouchInput {
            swipe(Offset(width * 0.02f, height / 2f), Offset(width * 0.98f, height / 2f), 500)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, flashes) }
    }

    @Test
    fun errorMessageWinsOverRunningHintAfterStopFailure() {
        flashingPage(
            state = flashingState(
                phase = FirmwareFlashingPhase.Error,
                message = "Could not stop the receiver. Stop it on the Home page and retry.",
            ),
            bridgeRunning = true,
        )

        compose.onNodeWithText("Could not stop the receiver. Stop it on the Home page and retry.")
            .assertIsDisplayed()
        compose.onNodeWithText(runningHint).assertDoesNotExist()
    }
}
