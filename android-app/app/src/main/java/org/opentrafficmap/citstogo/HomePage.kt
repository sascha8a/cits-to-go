package org.opentrafficmap.citstogo

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.ConnectionMode

@Composable
fun HomePage(
    devices: List<UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    connectionMode: ConnectionMode,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    status: BridgeStatus,
    onRefresh: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
    onOpenDiscoveredStations: () -> Unit,
) {
    val packetsPerSecond = rememberPacketRate(status.packets, status.running)
    HomeStatusBanner(status)
    Text("Connection", fontSize = 20.sp, fontWeight = FontWeight.Medium)
    ConnectionTabs(
        selectedMode = connectionMode,
        locked = status.running || status.replaying,
        onModeChange = onConnectionModeChange,
        onRefresh = onRefresh,
    )
    ConnectionDeviceList(devices, selectedDeviceName, connectionMode, status, onSelectDevice)
    HomeActions(status, onStart, onStop, onStartPcap, onStopPcap, onStartReplay, onStopReplay)
    HomeMetrics(status, packetsPerSecond, onOpenDiscoveredStations)
}

@Composable
private fun HomeStatusBanner(status: BridgeStatus) {
    val context = LocalContext.current
    val detail = "${status.usbState.ifBlank { "Stopped" }} | MQTT ${status.mqttState.ifBlank { "Disabled" }}"
    val bannerColor = if (status.running) {
        Color(ContextCompat.getColor(context, R.color.success))
    } else {
        Color(ContextCompat.getColor(context, R.color.secondary_variant))
    }
    val bannerInk = if (status.running) {
        Color(ContextCompat.getColor(context, R.color.on_success))
    } else {
        Color(ContextCompat.getColor(context, R.color.on_secondary_variant))
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bannerColor),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 15.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                if (status.running) "Capture active" else "Capture stopped",
                color = bannerInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(detail, color = bannerInk.copy(alpha = .9f), fontSize = 14.sp)
        }
    }
}

@Composable
private fun ConnectionTabs(
    selectedMode: ConnectionMode,
    locked: Boolean,
    onModeChange: (ConnectionMode) -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val buttonCount = ConnectionMode.entries.size
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        ConnectionMode.entries.forEachIndexed { index, mode ->
            val isSelected = mode == selectedMode
            val shape: Shape = when {
                buttonCount == 1 -> RoundedCornerShape(8.dp)
                index == 0 -> RoundedCornerShape(8.dp, 0.dp, 0.dp, 8.dp)
                index == buttonCount - 1 -> RoundedCornerShape(0.dp, 8.dp, 8.dp, 0.dp)
                else -> RoundedCornerShape(0.dp)
            }
            SegmentedButton(
                selected = isSelected,
                onClick = {
                    onModeChange(mode)
                    if (mode == ConnectionMode.USB) onRefresh()
                },
                enabled = !locked,
                shape = shape,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(ContextCompat.getColor(context, R.color.primary)),
                    activeContentColor = Color(ContextCompat.getColor(context, R.color.on_primary)),
                    inactiveContainerColor = Color.Transparent,
                    inactiveContentColor = Color(ContextCompat.getColor(context, R.color.secondary)),
                    disabledActiveContainerColor = Color(ContextCompat.getColor(context, R.color.divider)),
                    disabledActiveContentColor = Color(ContextCompat.getColor(context, R.color.disabled)),
                    disabledInactiveContainerColor = Color.Transparent,
                    disabledInactiveContentColor = Color(ContextCompat.getColor(context, R.color.disabled)),
                ),
                icon = {
                    Icon(
                        imageVector = if (mode == ConnectionMode.USB) Icons.Rounded.Usb else Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                    )
                },
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
private fun ConnectionDeviceList(
    devices: List<UsbDevice>,
    selectedDeviceName: String?,
    connectionMode: ConnectionMode,
    status: BridgeStatus,
    onSelectDevice: (String) -> Unit,
) {
    if (connectionMode == ConnectionMode.USB && !status.running && !status.replaying && devices.isNotEmpty() && selectedDeviceName == null) {
        devices.forEach { device ->
            TextButton(onClick = { onSelectDevice(device.deviceName) }, modifier = Modifier.fillMaxWidth()) {
                Text(deviceName(device))
            }
        }
    }
}

@Composable
private fun HomeActions(
    status: BridgeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
) {
    HomeActionButton(
        label = if (status.running) "Stop" else "Start",
        enabled = !status.replaying,
        onClick = if (status.running) onStop else onStart,
        stopping = status.running,
    )
    HomeActionButton(
        label = if (status.pcapRecording) "Stop recording" else "Record PCAP",
        enabled = status.pcapRecording || status.running,
        onClick = if (status.pcapRecording) onStopPcap else onStartPcap,
        stopping = status.pcapRecording,
        actionColor = true,
    )
    HomeActionButton(
        label = if (status.replaying) "Stop replay" else "Replay PCAP",
        enabled = status.replaying || !status.running,
        onClick = if (status.replaying) onStopReplay else onStartReplay,
        replay = true,
        stopping = status.replaying,
        actionColor = true,
    )
}

@Composable
private fun HomeActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    replay: Boolean = false,
    stopping: Boolean = false,
    actionColor: Boolean = false,
) {
    val context = LocalContext.current
    val containerColor = when {
        stopping -> Color(ContextCompat.getColor(context, R.color.error))
        actionColor -> Color(ContextCompat.getColor(context, R.color.info_variant))
        else -> MaterialTheme.colorScheme.primary
    }
    val contentColor = when {
        stopping -> Color(ContextCompat.getColor(context, R.color.on_error))
        actionColor -> Color(ContextCompat.getColor(context, R.color.on_info_variant))
        else -> Color(ContextCompat.getColor(context, R.color.on_primary))
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = Color(ContextCompat.getColor(context, R.color.divider)),
            disabledContentColor = Color(ContextCompat.getColor(context, R.color.disabled)),
        ),
    ) { Text(label, fontSize = 14.sp) }
}

@Composable
private fun HomeMetrics(
    status: BridgeStatus,
    packetsPerSecond: Double,
    onOpenDiscoveredStations: () -> Unit,
) {
    val metrics = listOf(
        HomeMetric("Packets", status.packets, String.format(java.util.Locale.US, "%.1f", packetsPerSecond)),
        HomeMetric("Published", status.mqttPublished),
        HomeMetric("Queued", status.mqttQueued),
        HomeMetric("PCAP", status.pcapPackets),
        HomeMetric("Discovered", status.discoveredDevices, onClick = onOpenDiscoveredStations),
        HomeMetric("Truncated", status.truncated),
    )
    metrics.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { metric -> HomeMetricCard(metric, Modifier.weight(1f)) }
        }
    }
}

/**
 * One tile of the home metric grid. [onClick] marks a tile that opens another page, which the card
 * then shows with a trailing arrow.
 */
private data class HomeMetric(
    val label: String,
    val value: Long,
    val detail: String? = null,
    val onClick: (() -> Unit)? = null,
)

@Composable
private fun HomeMetricCard(metric: HomeMetric, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clickable = metric.onClick != null
    Card(
        modifier = modifier.then(
            if (metric.onClick != null) Modifier.clickable(onClickLabel = "Show discovered stations", onClick = metric.onClick)
            else Modifier,
        ),
        shape = RoundedCornerShape(7.dp),
        border = if (clickable) BorderStroke(1.dp, Color(ContextCompat.getColor(context, R.color.border_active))) else null,
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metric.label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (metric.detail != null) {
                    Text("Packets/s", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                }
                if (clickable) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    metric.value.toString(),
                    modifier = Modifier.weight(1f),
                    fontSize = 22.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metric.detail != null) {
                    Text(metric.detail, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun deviceName(device: UsbDevice): String =
    listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
