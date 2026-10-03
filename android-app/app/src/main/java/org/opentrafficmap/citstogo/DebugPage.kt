package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.ConnectionMode
import org.opentrafficmap.citstogo.protocol.FirmwareStatistics
import org.opentrafficmap.citstogo.bridge.BleDebugParameters
import org.opentrafficmap.citstogo.intersection.IntersectionDiagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale

@Composable
fun DebugPage(
    status: BridgeStatus,
    connectionMode: ConnectionMode,
) {
    val stats = status.firmwareStatistics
    val androidBle = status.bleDebugParameters
    val intersections = status.intersectionDiagnostics

    DebugSection("Android stream") {
        DebugRow("Connection", connectionMode.label)
        DebugRow("State", status.usbState)
        DebugRow("Received captures", status.packets.toString())
        DebugRow("Stream gaps", status.transportDropped.toString())
        DebugRow("Protocol errors", status.protocolErrors.toString())
        Text(
            "Stream gaps are inferred from CTG capture sequence numbers. A zero value means no loss was observed after the firmware assigned a capture sequence.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }

    DebugSection("Firmware rates (1 Hz)") {
        if (stats == null) {
            Text(
                if (status.running) "Waiting for firmware statistics…" else "Connect to the firmware to receive statistics.",
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            DebugRow("Eligible Wi-Fi RX", "${stats.wifiRxPacketsPerSecond} packets/s")
            DebugRow("Accepted captures", "${stats.capturedPacketsPerSecond} packets/s")
            DebugRow(
                "Pre-sequence loss",
                "${(stats.wifiRxPacketsPerSecond - stats.capturedPacketsPerSecond).coerceAtLeast(0L)} packets/s",
            )
            DebugRow("USB capture TX", "${stats.usbCapturePacketsPerSecond} packets/s")
            DebugRow("BLE capture TX", "${stats.bleCapturePacketsPerSecond} packets/s")
            DebugRow("USB stream", formatRateBytes(stats.usbBytesPerSecond))
            DebugRow("BLE stream", formatRateBytes(stats.bleBytesPerSecond))
            DebugRow("BLE notifications", "${stats.bleNotificationsPerSecond}/s")
            DebugRow("Sample period", "${stats.sampleMs} ms")
            DebugRow("Firmware uptime", formatDurationMs(stats.uptimeMs))
        }
    }

    DebugSection("Firmware drops and queues") {
        if (stats == null) {
            Text("No firmware statistics received yet.", color = MaterialTheme.colorScheme.secondary)
        } else {
            DebugRow("RX pool exhausted", stats.rxNoBufferTotal.toString())
            DebugRow("RX too large", stats.rxTooLargeTotal.toString())
            DebugRow("USB capture drops", stats.usbOutputDropsTotal.toString())
            DebugRow("BLE capture drops", stats.bleOutputDropsTotal.toString())
            DebugRow("USB partial writes", stats.usbPartialWriteDropsTotal.toString())
            DebugRow("BLE notify failures", stats.bleNotifyFailuresTotal.toString())
            DebugRow("BLE input drops", stats.bleInputDropsTotal.toString())
            DebugRow("USB output queue", "${stats.usbQueueDepth}/${stats.usbQueueCapacity}")
            DebugRow("BLE output queue", "${stats.bleQueueDepth}/${stats.bleQueueCapacity}")
            DebugRow("Eligible Wi-Fi RX total", stats.wifiRxPacketsTotal.toString())
            DebugRow("Accepted total", stats.capturedPacketsTotal.toString())
            DebugRow("USB capture total", stats.usbCapturePacketsTotal.toString())
            DebugRow("BLE capture total", stats.bleCapturePacketsTotal.toString())
        }
    }

    DebugSection("Intersection decoding") {
        if (intersections == null) {
            Text("No intersection diagnostics available yet.", color = MaterialTheme.colorScheme.secondary)
        } else {
            DebugRow("Frames inspected", intersections.framesInspected.toString())
            DebugRow("ITS packets extracted", intersections.itsPacketsExtracted.toString())
            DebugRow("Secured GN", intersections.securedGeoNetworkingFrames.toString())
            DebugRow("Secured ITS packets", intersections.securedItsPackets.toString())
            DebugRow("Malformed GeoNetworking", intersections.malformedGeoNetworkingFrames.toString())
            DebugRow("Other / unsupported GN", intersections.unsupportedGeoNetworkingFrames.toString())
            DebugRow("MAPEM decoded", "${intersections.mapemDecoded}/${intersections.mapemSeen}")
            DebugRow("MAPEM failures", intersections.mapemDecodeFailures.toString())
            DebugRow("SPATEM decoded", "${intersections.spatemDecoded}/${intersections.spatemSeen}")
            DebugRow("SPATEM failures", intersections.spatemDecodeFailures.toString())
            if (intersections.lastDecodeError.isNotBlank()) {
                Text(
                    "Last decode error: ${intersections.lastDecodeError}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (intersections.lastExtractionIssue.isNotBlank()) {
                Text(
                    "Last extraction issue: ${intersections.lastExtractionIssue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }

    DebugSection("Bluetooth parameters") {
        if (androidBle == null && stats?.bleConnected != true) {
            Text("Bluetooth is not connected.", color = MaterialTheme.colorScheme.secondary)
        }
        androidBle?.let { ble ->
            Text("Android", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (ble.deviceAddress.isNotBlank()) DebugRow("Device", ble.deviceAddress)
            DebugRow("ATT MTU", ble.mtu.toString())
            DebugRow("TX PHY", blePhyLabel(ble.txPhy))
            DebugRow("RX PHY", blePhyLabel(ble.rxPhy))
            DebugRow("High priority requested", yesNo(ble.highPriorityRequested))
            DebugRow("2M PHY requested", yesNo(ble.preferred2MPhyRequested))
            DebugRow("Queued Android notifications", ble.queuedNotifications.toString())
        }
        stats?.takeIf { it.bleConnected }?.let { fw ->
            if (androidBle != null) HorizontalDivider()
            Text("Firmware / NimBLE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            DebugRow("Connected", yesNo(fw.bleConnected))
            DebugRow("Secured", yesNo(fw.bleSecured))
            DebugRow("Notifications enabled", yesNo(fw.bleNotificationsEnabled))
            DebugRow("ATT MTU", fw.bleMtu.toString())
            DebugRow("Connection interval", fw.bleConnectionIntervalMs?.let { String.format(Locale.US, "%.2f ms", it) } ?: "—")
            DebugRow("Peripheral latency", fw.bleConnectionLatency.toString())
            DebugRow("Supervision timeout", fw.bleSupervisionTimeoutMs?.let { "$it ms" } ?: "—")
            DebugRow("TX PHY", blePhyLabel(fw.bleTxPhy))
            DebugRow("RX PHY", blePhyLabel(fw.bleRxPhy))
        }
    }
}

@Composable
private fun DebugSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun DebugRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun blePhyLabel(phy: Int): String = when (phy) {
    1 -> "LE 1M"
    2 -> "LE 2M"
    3 -> "LE Coded"
    0 -> "Unknown"
    else -> "Unknown ($phy)"
}

private fun yesNo(value: Boolean): String = if (value) "Yes" else "No"

private fun formatRateBytes(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1_000_000L -> String.format(Locale.US, "%.2f MB/s", bytesPerSecond / 1_000_000.0)
    bytesPerSecond >= 1_000L -> String.format(Locale.US, "%.1f kB/s", bytesPerSecond / 1_000.0)
    else -> "$bytesPerSecond B/s"
}

private fun formatDurationMs(ms: Long): String {
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
