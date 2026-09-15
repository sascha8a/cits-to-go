package org.opentrafficmap.citstogo

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
    HomeMetrics(status, packetsPerSecond)
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
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(detail, color = Color.White.copy(alpha = .9f), fontSize = 14.sp)
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
                    activeContentColor = Color.White,
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
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = Color.White,
            disabledContainerColor = Color(ContextCompat.getColor(context, R.color.divider)),
            disabledContentColor = Color(ContextCompat.getColor(context, R.color.disabled)),
        ),
    ) { Text(label, fontSize = 14.sp) }
}

@Composable
private fun HomeMetrics(status: BridgeStatus, packetsPerSecond: Double) {
    val metrics = listOf(
        Triple("Packets", status.packets, String.format(java.util.Locale.US, "%.1f", packetsPerSecond)),
        Triple("Published", status.mqttPublished, null),
        Triple("Queued", status.mqttQueued, null),
        Triple("PCAP", status.pcapPackets, null),
        Triple("Discovered", status.discoveredDevices, null),
        Triple("Truncated", status.truncated, null),
    )
    metrics.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (label, value, detail) -> HomeMetric(label, value, detail, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun HomeMetric(label: String, value: Long, detail: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp, vertical = 15.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                if (detail != null) {
                    Text("Packets/s", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    value.toString(),
                    modifier = Modifier.weight(1f),
                    fontSize = 22.sp,
                    lineHeight = 25.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (detail != null) {
                    Text(detail, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

private fun deviceName(device: UsbDevice): String =
    listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
