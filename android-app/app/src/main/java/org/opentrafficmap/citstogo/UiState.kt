package org.opentrafficmap.citstogo

import android.location.Location
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import kotlin.math.roundToLong
import org.opentrafficmap.citstogo.flashing.FirmwareRelease

enum class FirmwareFlashingPhase {
    LoadingRelease,
    WaitingForDevice,
    Ready,
    Downloading,
    Flashing,
    Complete,
    Error,
}

data class FirmwareFlashingState(
    val appVersion: String,
    val releaseTag: String? = null,
    val firmwareName: String? = null,
    val customFirmware: Boolean = false,
    val deviceName: String? = null,
    val availableReleases: List<FirmwareRelease> = emptyList(),
    val phase: FirmwareFlashingPhase = FirmwareFlashingPhase.LoadingRelease,
    val message: String = "Loading the list of firmware releases…",
    val progress: Float = 0f,
) {
    val busy: Boolean get() = phase == FirmwareFlashingPhase.Downloading || phase == FirmwareFlashingPhase.Flashing

    companion object {
        fun initial(appVersion: String) = FirmwareFlashingState(appVersion = appVersion)
    }
}

data class DevicePosition(
    val latitudeE7: Int,
    val longitudeE7: Int,
    val accuracyM: Float?,
    val speedMetersPerSecond: Float?,
    val heading: Int,
    val timeMs: Long,
) {
    companion object {
        fun from(location: Location): DevicePosition = DevicePosition(
            latitudeE7 = (location.latitude * 10_000_000.0).toInt(),
            longitudeE7 = (location.longitude * 10_000_000.0).toInt(),
            accuracyM = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedMetersPerSecond = location.takeIf { it.hasSpeed() && it.speed >= 0.5f }?.speed,
            heading = location.takeIf { it.hasBearing() }
                ?.let { (it.bearing.mod(360f) * 10f).roundToLong().toInt().coerceIn(0, 3_600) }
                ?: 0,
            timeMs = location.time,
        )
    }
}

enum class TxApprovalPromptState {
    Hidden,
    Ready,
    Granting,
}

enum class AppPage(val title: String) {
    Home("Home"),
    CamBroadcast("CAM Broadcast"),
    IntersectionView("Intersection View"),
    Flashing("Flashing"),
    Settings("Settings"),
    Debug("Debug"),
    About("About"),
    ;

    fun visible(txApproved: Boolean, debugMenuEnabled: Boolean): Boolean = when (this) {
        AppPage.CamBroadcast -> txApproved
        AppPage.Debug -> debugMenuEnabled
        else -> true
    }
}

enum class IntersectionSortMode(val label: String) {
    FirstReceived("first received"),
    Distance("distance"),
    ;

    companion object {
        fun fromPreference(value: String?): IntersectionSortMode =
            entries.firstOrNull { it.name == value } ?: FirstReceived
    }
}

enum class SremRequestUiState(
    val label: String,
    val detail: String,
    val colorResId: Int,
) {
    SelectFirst("Select inbound lane", "Tap any MAPEM lane in the intersection view.", R.color.secondary),
    SelectSecond("Select connected outbound lane", "Lanes without a declared local connection are dimmed.", R.color.primary),
    NotReady("Cannot request yet", "Start capture, approve TX, and wait for a fresh location.", R.color.warning),
    Ready("Slide left to request green", "The request will be sent as an SREM.", R.color.primary),
    Queued("Request queued", "Waiting for firmware transmit acknowledgement.", R.color.info),
    Transmitted("SREM transmitted", "Waiting for response or signal change.", R.color.info),
    Acknowledged("Request acknowledged", "The controller reported that it received the request.", R.color.info),
    Processing("Controller processing", "The controller is processing the request.", R.color.info),
    WatchOtherTraffic("Watch other traffic", "The controller granted limited priority with caution.", R.color.warning),
    Granted("Request granted", "Waiting for the requested movement to become active.", R.color.success),
    WalkActive("Requested movement active", "SPATEM reports a permitted movement phase.", R.color.success),
    Rejected("Request rejected", "The controller rejected this request.", R.color.error),
    UnknownResponse("Response unclear", "The controller response could not be classified.", R.color.warning),
    Failed("Request failed", "The SREM could not be transmitted.", R.color.error),
    TimedOut("No response observed", "No matching signal change was seen in time.", R.color.warning),
}

fun SremRequestUiState.color(context: android.content.Context): Color = Color(ContextCompat.getColor(context, colorResId))

enum class ActivityLevel { INFO, WARN, ERROR }

data class ActivityLogEntry(
    val timestamp: String,
    val level: ActivityLevel,
    val message: String,
)

enum class HomeIcon { USB, BLUETOOTH, RECORD, CAPTURE, REPLAY, STOP, DISCOVERED, QUEUED, DOCUMENT, ERROR }
