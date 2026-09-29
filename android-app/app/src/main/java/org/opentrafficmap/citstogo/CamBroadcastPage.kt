package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun CamBroadcastPage(
    status: BridgeStatus,
    logLine: String,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    randomizationEnabled: Boolean,
    onRandomizationEnabledChange: (Boolean) -> Unit,
    randomizationIntervalSeconds: String,
    onRandomizationIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CamPanel(
            status = status,
            intervalMs = intervalMs,
            onIntervalChange = onIntervalChange,
            onConfigure = onConfigure,
        )
        CamPrivacyPanel(
            status = status,
            randomizationEnabled = randomizationEnabled,
            onRandomizationEnabledChange = onRandomizationEnabledChange,
            randomizationIntervalSeconds = randomizationIntervalSeconds,
            onRandomizationIntervalChange = onRandomizationIntervalChange,
        )
    }
    if (logLine.isNotBlank() || status.lastError.isNotBlank()) {
        EventLog(logLine, status.lastError)
    }
}

@Composable
private fun CamPanel(
    status: BridgeStatus,
    intervalMs: String,
    onIntervalChange: (String) -> Unit,
    onConfigure: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sensors,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "CAM broadcast",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            OutlinedTextField(
                value = intervalMs,
                onValueChange = onIntervalChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !status.camEnabled,
                label = { Text("Broadcast interval") },
                suffix = { Text("ms") },
                supportingText = { Text("Allowed CAM range: 100–1000 ms") },
            )

            Button(
                onClick = { onConfigure(!status.camEnabled) },
                enabled = status.running,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(12.dp),
                colors = if (status.camEnabled) {
                    ButtonDefaults.buttonColors(containerColor = Color(ContextCompat.getColor(context, R.color.error)))
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(
                    if (status.camEnabled) "Stop CAM broadcast" else "Start CAM broadcast",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CamPrivacyPanel(
    status: BridgeStatus,
    randomizationEnabled: Boolean,
    onRandomizationEnabledChange: (Boolean) -> Unit,
    randomizationIntervalSeconds: String,
    onRandomizationIntervalChange: (String) -> Unit,
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(ContextCompat.getColor(context, R.color.card)),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(ContextCompat.getColor(context, R.color.secondary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = null,
                        tint = if (randomizationEnabled) {
                            Color(ContextCompat.getColor(context, R.color.secondary))
                        } else {
                            Color(ContextCompat.getColor(context, R.color.disabled))
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "Address randomization",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Broadcast with a new random MAC address, station ID, and sequence counters",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
                Switch(
                    checked = randomizationEnabled,
                    onCheckedChange = onRandomizationEnabledChange,
                    enabled = !status.camEnabled,
                )
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            OutlinedTextField(
                value = randomizationIntervalSeconds,
                onValueChange = onRandomizationIntervalChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !status.camEnabled && randomizationEnabled,
                label = { Text("Randomize every") },
                placeholder = { Text("60") },
                suffix = { Text("s") },
                supportingText = { Text("Allowed range: 1–600 s") },
            )
        }
    }
}
