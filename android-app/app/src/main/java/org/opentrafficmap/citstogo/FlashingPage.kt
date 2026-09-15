package org.opentrafficmap.citstogo

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.opentrafficmap.citstogo.flashing.FirmwareFlashLog
import org.opentrafficmap.citstogo.flashing.FirmwareFlashLogEntry
import org.opentrafficmap.citstogo.flashing.FirmwareRelease
import org.opentrafficmap.citstogo.flashing.formatFirmwareSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashingPage(
    state: FirmwareFlashingState,
    log: List<FirmwareFlashLogEntry>,
    bridgeRunning: Boolean,
    onRetryRelease: () -> Unit,
    onSelectRelease: (FirmwareRelease) -> Unit,
    onChooseCustomFirmware: () -> Unit,
    onUseReleaseFirmware: () -> Unit,
    onFlash: () -> Unit,
) {
    val context = LocalContext.current
    val lookupRunning = state.phase == FirmwareFlashingPhase.LoadingRelease
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(ContextCompat.getColor(context, R.color.card)), RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("ESP32-C5 firmware", style = MaterialTheme.typography.titleMedium)
        if (!state.customFirmware) {
            FirmwareReleaseDropdown(
                releases = state.availableReleases,
                selectedTag = state.releaseTag,
                loading = lookupRunning,
                enabled = !state.busy,
                onSelect = onSelectRelease,
                onRefresh = onRetryRelease,
            )
        }
        FlashingDetail("App version", state.appVersion)
        FlashingDetail(
            "Source",
            when {
                state.customFirmware -> "Custom file"
                state.releaseTag != null -> state.releaseTag
                lookupRunning -> "Loading…"
                state.phase == FirmwareFlashingPhase.Error -> "Not found"
                else -> "Not set"
            },
        )
        FlashingDetail(
            "Firmware",
            when {
                state.firmwareName != null -> state.firmwareName
                lookupRunning -> "Loading…"
                state.phase == FirmwareFlashingPhase.Error -> "Unavailable"
                else -> "Not set"
            },
        )
        FlashingDetail("Device", state.deviceName ?: "Waiting for ESP32-C5…")
        Text(
            if (bridgeRunning && state.phase == FirmwareFlashingPhase.Ready) {
                "Stop the receiver on the Home page before flashing."
            } else {
                state.message
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when (state.phase) {
                FirmwareFlashingPhase.Error -> Color(ContextCompat.getColor(context, R.color.error))
                FirmwareFlashingPhase.Complete -> Color(ContextCompat.getColor(context, R.color.success_variant))
                else -> MaterialTheme.colorScheme.secondary
            },
        )
        if (state.busy || state.phase == FirmwareFlashingPhase.Complete) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        if (state.phase == FirmwareFlashingPhase.Error && state.releaseTag == null && !state.customFirmware) {
            Button(onClick = onRetryRelease, modifier = Modifier.fillMaxWidth()) {
                Text("Reload release list")
            }
        }
        OutlinedButton(
            onClick = onChooseCustomFirmware,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.customFirmware) "Choose another firmware.bin" else "Choose custom firmware.bin")
        }
        if (state.customFirmware) {
            TextButton(
                onClick = onUseReleaseFirmware,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use release firmware instead")
            }
        }
    }
    if (state.phase == FirmwareFlashingPhase.Ready ||
        (state.phase == FirmwareFlashingPhase.Error && state.firmwareName != null && state.deviceName != null)
    ) {
        var position by rememberSaveable(state.deviceName, state.message) { mutableStateOf(0f) }
        var submitted by rememberSaveable(state.deviceName, state.message) { mutableStateOf(false) }
        Text(
            "Put the ESP32-C5 into boot mode before flashing: hold the BOOT button while connecting or resetting the board, and keep it held until flashing starts. Keep USB connected until verification finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(ContextCompat.getColor(context, R.color.tertiary)),
            fontWeight = FontWeight.SemiBold,
        )
        TxApprovalSlider(
            position = position,
            onPositionChange = {
                position = it
                if (!submitted && it >= 0.995f) {
                    submitted = true
                    position = 1f
                    onFlash()
                }
            },
            enabled = !bridgeRunning,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    FlashingLogCard(log)
}

@Composable
private fun FlashingDetail(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, modifier = Modifier.width(88.dp), fontWeight = FontWeight.SemiBold)
        Text(value, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FirmwareReleaseDropdown(
    releases: List<FirmwareRelease>,
    selectedTag: String?,
    loading: Boolean,
    enabled: Boolean,
    onSelect: (FirmwareRelease) -> Unit,
    onRefresh: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = releases.firstOrNull { it.tag == selectedTag }
    val selectable = enabled && releases.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Firmware release", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExposedDropdownMenuBox(
                expanded = expanded && selectable,
                onExpandedChange = { if (selectable) expanded = it },
                modifier = Modifier.weight(1f),
            ) {
                OutlinedTextField(
                    value = when {
                        selected != null -> selected.tag
                        loading && releases.isEmpty() -> "Loading releases…"
                        releases.isEmpty() -> "No releases available"
                        else -> "Select a release"
                    },
                    onValueChange = {},
                    readOnly = true,
                    enabled = selectable,
                    label = { Text("Version") },
                    supportingText = selected?.let {
                        { Text("${formatFirmwareSize(it.firmwareSize)} · ${it.firmwareName}") }
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && selectable) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = selectable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded && selectable, onDismissRequest = { expanded = false }) {
                    releases.forEach { release ->
                        DropdownMenuItem(
                            text = { Text("${release.tag}  ·  ${formatFirmwareSize(release.firmwareSize)}") },
                            onClick = {
                                expanded = false
                                onSelect(release)
                            },
                            trailingIcon = {
                                if (release.tag == selectedTag) Icon(Icons.Rounded.Check, contentDescription = "Selected")
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onRefresh, enabled = !loading) {
                Icon(Icons.Rounded.Refresh, contentDescription = "Reload release list")
            }
        }
    }
}

@Composable
private fun FlashingLogCard(log: List<FirmwareFlashLogEntry>) {
    val context = LocalContext.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val visible = if (showAll) log else log.take(6)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(ContextCompat.getColor(context, R.color.card))),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Flashing log",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (log.size > 6) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show recent" else "View all (${log.size})")
                    }
                }
            }
            if (log.isEmpty()) {
                Text(
                    "No flashing activity yet. Open this page to look up a firmware release.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            } else {
                val entries = FirmwareFlashLog.toActivityEntries(visible)
                entries.forEachIndexed { index, entry ->
                    ActivityRow(entry)
                    if (index != entries.lastIndex) {
                        HorizontalDivider(color = Color(ContextCompat.getColor(context, R.color.divider)))
                    }
                }
            }
        }
    }
}
