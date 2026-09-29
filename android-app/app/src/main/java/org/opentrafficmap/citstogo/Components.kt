package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.ConnectionMode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.core.content.ContextCompat
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import org.opentrafficmap.citstogo.ui.DragConfirmDirection
import org.opentrafficmap.citstogo.ui.DragConfirmSlider

private val ACTIVITY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private const val MAX_ACTIVITY_HISTORY = 40
private const val CONFETTI_DURATION_MS = 1_600L

private fun confettiColors(context: android.content.Context): List<Color> = listOf(
    Color(ContextCompat.getColor(context, R.color.confetti_1)),
    Color(ContextCompat.getColor(context, R.color.confetti_2)),
    Color(ContextCompat.getColor(context, R.color.confetti_3)),
    Color(ContextCompat.getColor(context, R.color.confetti_4)),
    Color(ContextCompat.getColor(context, R.color.confetti_5)),
    Color(ContextCompat.getColor(context, R.color.confetti_6)),
)

@Composable
fun AppHeader(title: String, onOpenMenu: () -> Unit, subtitle: String? = null) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenMenu) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = "Open navigation menu",
                    tint = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                title,
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                modifier = Modifier.weight(1f),
            )
        }
        if (subtitle != null) {
            Text(
                subtitle,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                modifier = Modifier.padding(start = 44.dp, top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
fun HomeIcon(icon: HomeIcon, color: Color, modifier: Modifier = Modifier) {
    val image = when (icon) {
        HomeIcon.USB -> Icons.Rounded.Usb
        HomeIcon.BLUETOOTH -> Icons.Rounded.Bluetooth
        HomeIcon.RECORD -> Icons.Rounded.RadioButtonChecked
        HomeIcon.CAPTURE -> Icons.Rounded.Sensors
        HomeIcon.REPLAY -> Icons.Rounded.Replay
        HomeIcon.STOP -> Icons.Rounded.Stop
        HomeIcon.DISCOVERED -> Icons.Rounded.Groups
        HomeIcon.QUEUED -> Icons.AutoMirrored.Rounded.FormatListBulleted
        HomeIcon.DOCUMENT -> Icons.Rounded.Description
        HomeIcon.ERROR -> Icons.Rounded.WarningAmber
    }
    Icon(imageVector = image, contentDescription = null, tint = color, modifier = modifier.size(22.dp))
}

@Composable
fun StatusBand(
    status: BridgeStatus,
    connectionMode: ConnectionMode,
    packetsPerSecond: Double,
) {
    val context = LocalContext.current
    val active = status.running
    val containerColor = if (active) Color(ContextCompat.getColor(context, R.color.success_container)) else Color(ContextCompat.getColor(context, R.color.secondary_container))
    val borderColor = if (active) Color(ContextCompat.getColor(context, R.color.border_active)) else Color(ContextCompat.getColor(context, R.color.border))
    val contentColor = if (active) Color(ContextCompat.getColor(context, R.color.primary_variant)) else Color(ContextCompat.getColor(context, R.color.on_secondary_container))
    val modeLabel = when {
        status.replaying -> "Replay mode"
        status.pcapRecording -> "Recording PCAP"
        active -> "Live capture"
        else -> "Idle"
    }
    val transportLabel = when {
        status.replaying -> "PCAP source"
        status.usbState.startsWith("Connected:") -> "${connectionMode.label} connected"
        else -> connectionMode.label
    }
    val mqttLabel = if (status.mqttState.equals("Disabled", ignoreCase = true)) "MQTT off" else "MQTT ${status.mqttState.lowercase()}"

    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, borderColor, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(34.dp).background(if (active) Color(ContextCompat.getColor(context, R.color.success_container)) else Color(ContextCompat.getColor(context, R.color.secondary_container)), RoundedCornerShape(50)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(12.dp).background(if (active) Color(ContextCompat.getColor(context, R.color.on_primary_container)) else Color(ContextCompat.getColor(context, R.color.status_indicator_inactive)), RoundedCornerShape(50)))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (active) "Capture active" else "Capture stopped",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = contentColor,
                )
                Text(
                    "$modeLabel • $transportLabel • $mqttLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = .78f),
                )
            }
        }
    }
}

@Composable
fun ConfigPanel(
    devices: List<android.hardware.usb.UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    connectionMode: ConnectionMode,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    status: BridgeStatus,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val connectionText = when {
        status.replaying -> "Using replay file"
        status.usbState.startsWith("Connected:") -> "Receiver connected"
        status.running -> status.usbState.ifBlank { "Connecting…" }
        else -> "Receiver disconnected"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(36.dp).background(Color(ContextCompat.getColor(context, R.color.primary_container)), RoundedCornerShape(50)),
                    contentAlignment = Alignment.Center,
                ) {
                    HomeIcon(
                        if (connectionMode == ConnectionMode.BLUETOOTH) HomeIcon.BLUETOOTH else HomeIcon.USB,
                        Color(ContextCompat.getColor(context, R.color.primary)),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(connectionText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)
                }
                Row(
                    Modifier.background(Color(ContextCompat.getColor(context, R.color.surface_variant)), RoundedCornerShape(10.dp)).border(1.dp, Color(ContextCompat.getColor(context, R.color.divider)), RoundedCornerShape(10.dp)),
                ) {
                    ConnectionMode.entries.forEach { mode ->
                        val selected = mode == connectionMode
                        Button(
                            onClick = {
                                onConnectionModeChange(mode)
                                if (mode == ConnectionMode.USB) onRefresh()
                            },
                            enabled = !status.replaying,
                            colors = if (selected) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            } else {
                                ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = MaterialTheme.colorScheme.secondary,
                                )
                            },
                            shape = RoundedCornerShape(9.dp),
                            modifier = Modifier.height(48.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Text(mode.label, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (connectionMode == ConnectionMode.USB && !status.replaying && devices.isNotEmpty() && selectedDeviceName == null) {
                devices.forEach { device ->
                    OutlinedButton(
                        onClick = { onSelectDevice(device.deviceName) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(deviceLabel(device), maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
fun ActionControls(
    status: BridgeStatus,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (status.running) onStop else onStart,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(9.dp),
                    colors = if (status.running) {
                        ButtonDefaults.buttonColors(containerColor = Color(ContextCompat.getColor(context, R.color.error)))
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    HomeIcon(if (status.running) HomeIcon.STOP else HomeIcon.CAPTURE, Color(ContextCompat.getColor(context, R.color.on_error)), Modifier.size(18.dp))
                    Text(if (status.running) "Stop capture" else "Start capture", fontSize = 13.sp, modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedButton(
                    onClick = if (status.pcapRecording) onStopPcap else onStartPcap,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = status.pcapRecording || status.running,
                    shape = RoundedCornerShape(9.dp),
                ) {
                    HomeIcon(if (status.pcapRecording) HomeIcon.STOP else HomeIcon.RECORD, if (status.pcapRecording || status.running) MaterialTheme.colorScheme.secondary else Color(ContextCompat.getColor(context, R.color.disabled)), Modifier.size(18.dp))
                    Text(if (status.pcapRecording) "Stop recording" else "Start recording", fontSize = 13.sp, modifier = Modifier.padding(start = 7.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = if (status.replaying) onStopReplay else onStartReplay,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = status.replaying || !status.running,
                    shape = RoundedCornerShape(9.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(ContextCompat.getColor(context, R.color.info_container)),
                        contentColor = Color(ContextCompat.getColor(context, R.color.on_info_container)),
                        disabledContainerColor = Color(ContextCompat.getColor(context, R.color.secondary_container)),
                        disabledContentColor = Color(ContextCompat.getColor(context, R.color.disabled)),
                    ),
                ) {
                    HomeIcon(if (status.replaying) HomeIcon.STOP else HomeIcon.REPLAY, Color(ContextCompat.getColor(context, R.color.on_info_container)), Modifier.size(18.dp))
                    Text(if (status.replaying) "Stop replay" else "Replay PCAP", fontSize = 13.sp, modifier = Modifier.padding(start = 7.dp))
                }
                Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun Metrics(status: BridgeStatus, packetsPerSecond: Double) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Statistics", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(
                    modifier = Modifier.weight(1.4f).height(114.dp).border(1.dp, Color(ContextCompat.getColor(context, R.color.border_active)), RoundedCornerShape(10.dp)),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.success_container))),
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            MetricIcon(HomeIcon.CAPTURE, Modifier.size(32.dp), iconSize = 18.dp)
                            Text("Received", color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelLarge)
                        }
                        Text(
                            status.packets.toString(),
                            fontSize = 28.sp,
                            lineHeight = 30.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 42.dp),
                        )
                        Text(
                            String.format(Locale.US, "%.1f packets/s", packetsPerSecond),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 42.dp),
                        )
                    }
                }
                Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetricCard("Devices seen", status.discoveredDevices.toString(), HomeIcon.DISCOVERED, Modifier.weight(1f))
                        CompactMetricCard("Queued", status.mqttQueued.toString(), HomeIcon.QUEUED, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CompactMetricCard("Truncated", status.truncated.toString(), HomeIcon.DOCUMENT, Modifier.weight(1f))
                        CompactMetricCard("Errors", status.protocolErrors.toString(), HomeIcon.ERROR, Modifier.weight(1f), status.protocolErrors > 0)
                    }
                }
            }
        }
    }
}

@Composable
fun CompactMetricCard(
    label: String,
    value: String,
    icon: HomeIcon,
    modifier: Modifier = Modifier,
    error: Boolean = false,
) {
    val context = LocalContext.current
    Card(
        modifier = modifier.height(54.dp).border(1.dp, Color(ContextCompat.getColor(context, R.color.divider)), RoundedCornerShape(9.dp)),
        shape = RoundedCornerShape(9.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MetricIcon(icon, Modifier.size(22.dp), error, iconSize = 13.dp)
                Text(
                    label,
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 10.sp,
                    lineHeight = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 27.dp),
            )
        }
    }
}

@Composable
fun MetricIcon(
    icon: HomeIcon,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    iconSize: androidx.compose.ui.unit.Dp = 14.dp,
) {
    val context = LocalContext.current
    Box(
        modifier.background(Color(ContextCompat.getColor(context, R.color.primary_container)), RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        HomeIcon(icon, if (error) MaterialTheme.colorScheme.error else Color(ContextCompat.getColor(context, R.color.primary)), Modifier.size(iconSize))
    }
}

@Composable
fun LastPacketCard(summary: String) {
    if (summary.isBlank()) return
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(Color(ContextCompat.getColor(context, R.color.secondary_container)), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                HomeIcon(HomeIcon.DOCUMENT, MaterialTheme.colorScheme.secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Last packet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Text(formatLastPacketSummary(summary), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun EventLog(logLine: String, lastError: String) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (logLine.isNotBlank()) Text(logLine, style = MaterialTheme.typography.bodyMedium)
        if (lastError.isNotBlank()) Text(lastError, color = Color(ContextCompat.getColor(context, R.color.error)), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun RecentActivity(entries: List<ActivityLogEntry>) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visibleEntries = if (showAll) entries else entries.take(8)
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent activity", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (entries.size > 8) {
                    TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show recent" else "View all") }
                }
            }
            if (visibleEntries.isEmpty()) {
                Text(
                    "Connect a receiver and start capture, or replay a PCAP file to begin.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 2.dp, bottom = 5.dp),
                )
            } else {
                visibleEntries.forEachIndexed { index, entry ->
                    ActivityRow(entry)
                    if (index != visibleEntries.lastIndex) HorizontalDivider(color = Color(ContextCompat.getColor(context, R.color.divider)))
                }
            }
        }
    }
}

@Composable
fun ActivityRow(entry: ActivityLogEntry) {
    val context = LocalContext.current
    val levelColor = when (entry.level) {
        ActivityLevel.INFO -> Color(ContextCompat.getColor(context, R.color.success))
        ActivityLevel.WARN -> Color(ContextCompat.getColor(context, R.color.warning))
        ActivityLevel.ERROR -> Color(ContextCompat.getColor(context, R.color.error))
    }
    val levelBackground = when (entry.level) {
        ActivityLevel.INFO -> Color(ContextCompat.getColor(context, R.color.success_container))
        ActivityLevel.WARN -> Color(ContextCompat.getColor(context, R.color.warning_container))
        ActivityLevel.ERROR -> Color(ContextCompat.getColor(context, R.color.error_container))
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("●", color = levelColor, style = MaterialTheme.typography.labelSmall)
        Text(entry.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.width(64.dp))
        Text(
            entry.level.name,
            modifier = Modifier.background(levelBackground, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = levelColor,
            fontWeight = FontWeight.SemiBold,
        )
        Text(entry.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

@Composable
fun rememberPacketRate(packets: Long, running: Boolean): Double {
    val currentPackets by rememberUpdatedState(packets)
    var rate by remember { mutableStateOf(0.0) }
    val samples = remember { ArrayDeque<Pair<Long, Long>>() }
    LaunchedEffect(running) {
        samples.clear()
        rate = 0.0
        if (!running) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            samples.addLast(now to currentPackets)
            while (samples.size > 1 && now - samples.first().first > 3_000L) samples.removeFirst()
            if (samples.size > 1) {
                val oldest = samples.first()
                val elapsedSeconds = (now - oldest.first) / 1000.0
                val delta = (currentPackets - oldest.second).coerceAtLeast(0L)
                rate = if (elapsedSeconds > 0.0) delta / elapsedSeconds else 0.0
            }
            delay(500L)
        }
    }
    return rate
}

@Composable
fun TxApprovalSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val onDragStateChange = LocalSliderDragStateChange.current
    DragConfirmSlider(
        position = position,
        onPositionChange = onPositionChange,
        enabled = enabled,
        direction = DragConfirmDirection.LeftToRight,
        trackColor = if (enabled) Color(ContextCompat.getColor(context, R.color.error_container)) else Color(ContextCompat.getColor(context, R.color.error_container)),
        fillColor = Color(ContextCompat.getColor(context, R.color.error)),
        thumbRingColor = Color(ContextCompat.getColor(context, R.color.on_error)),
        onDragStateChange = onDragStateChange,
        modifier = modifier.height(96.dp),
    ) { center, _ ->
        val arrowLength = 22.dp.toPx()
        val arrowHead = 8.dp.toPx()
        val arrowStart = Offset(center.x - arrowLength / 2f, center.y)
        val arrowEnd = Offset(center.x + arrowLength / 2f, center.y)
        val ink = Color(ContextCompat.getColor(context, R.color.on_error))
        drawLine(ink, arrowStart, arrowEnd, 4.dp.toPx(), StrokeCap.Round)
        drawLine(ink, arrowEnd, Offset(arrowEnd.x - arrowHead, arrowEnd.y - arrowHead), 4.dp.toPx(), StrokeCap.Round)
        drawLine(ink, arrowEnd, Offset(arrowEnd.x - arrowHead, arrowEnd.y + arrowHead), 4.dp.toPx(), StrokeCap.Round)
    }
}

@Composable
fun ConfettiOverlay(run: Int) {
    if (run <= 0) return
    val context = LocalContext.current
    var progress by remember(run) { mutableStateOf(0f) }
    val colors = confettiColors(context)
    val confetti = remember(run) {
        List(72) { index ->
            ConfettiPiece(
                startX = ((index * 37) % 100) / 100f,
                drift = (((index * 17) % 41) - 20) / 100f,
                spin = ((index % 9) + 2).toFloat(),
                color = colors[index % colors.size],
            )
        }
    }
    LaunchedEffect(run) {
        val startMs = SystemClock.elapsedRealtime()
        do {
            progress = ((SystemClock.elapsedRealtime() - startMs) / CONFETTI_DURATION_MS.toFloat()).coerceIn(0f, 1f)
            delay(16L)
        } while (progress < 1f)
    }
    if (progress >= 1f) return
    androidx.compose.foundation.Canvas(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.safeDrawing),
    ) {
        val eased = 1f - (1f - progress) * (1f - progress)
        confetti.forEachIndexed { index, piece ->
            val x = size.width * (
                piece.startX +
                    piece.drift * eased +
                    sin((progress * piece.spin + index) * 2.1f) * 0.025f
                )
            val y = size.height * (-0.1f + eased * 1.15f) - ((index % 6) * 22.dp.toPx())
            if (y < -24.dp.toPx() || y > size.height + 24.dp.toPx()) return@forEachIndexed
            drawRoundRect(
                color = piece.color.copy(alpha = (1f - progress).coerceIn(0.2f, 1f)),
                topLeft = Offset(x, y),
                size = Size(8.dp.toPx(), 16.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

private data class ConfettiPiece(
    val startX: Float,
    val drift: Float,
    val spin: Float,
    val color: Color,
)

internal fun activityLevelFor(message: String): ActivityLevel {
    val lower = message.lowercase()
    return when {
        "error" in lower || "failed" in lower || "denied" in lower || "disconnected" in lower -> ActivityLevel.ERROR
        "warning" in lower || "truncated" in lower || "waiting" in lower || "required" in lower -> ActivityLevel.WARN
        else -> ActivityLevel.INFO
    }
}

internal fun humanizeActivityMessage(message: String): String = message
    .removePrefix("Packet #")
    .let { if (it != message && it.firstOrNull()?.isDigit() == true) "Packet #$it" else message }

private fun formatLastPacketSummary(summary: String): String = summary
    .replace(" 0MHz", " — MHz")
    .replace(" 0dBm", " — dBm")

private fun deviceLabel(device: android.hardware.usb.UsbDevice): String {
    val name = listOfNotNull(device.manufacturerName, device.productName).joinToString(" ").ifBlank { device.deviceName }
    return "$name  vid=%04x pid=%04x".format(device.vendorId, device.productId)
}

internal val LocalSliderDragStateChange = compositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
fun TxApprovalOverlay(
    state: TxApprovalPromptState,
    onGrantApproval: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == TxApprovalPromptState.Hidden) return
    val context = LocalContext.current
    var sliderPosition by rememberSaveable(state) {
        mutableStateOf(if (state == TxApprovalPromptState.Granting) 1f else 0f)
    }
    var submitted by rememberSaveable(state) { mutableStateOf(state == TxApprovalPromptState.Granting) }
    val granting = state == TxApprovalPromptState.Granting
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(ContextCompat.getColor(context, R.color.background)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .pointerInput(state) {
                awaitEachGesture {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("TX approval", style = MaterialTheme.typography.titleMedium)
                Text(
                    "You are responsible for any regulatory matters when transmitting data. Consult local legislation before transmitting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(ContextCompat.getColor(context, R.color.tertiary)),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (granting) {
                        "TX approval granted. Returning home..."
                    } else {
                        "Pull the red handle fully from left to right to approve TX."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                TextButton(
                    onClick = onDismiss,
                    enabled = !granting,
                ) {
                    Text("Cancel")
                }
            }
            TxApprovalSlider(
                position = sliderPosition,
                onPositionChange = {
                    sliderPosition = it
                    if (!submitted && it >= 0.995f) {
                        submitted = true
                        sliderPosition = 1f
                        onGrantApproval()
                    }
                },
                enabled = !granting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
