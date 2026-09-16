package org.opentrafficmap.citstogo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.opentrafficmap.citstogo.srem.SremProfile

@Composable
fun SettingsPage(
    mqttUri: String,
    nodeId: String,
    maxQueueLength: String,
    maxQueueAgeSeconds: String,
    txApproved: Boolean,
    sremProfile: SremProfile,
    bridgeRunning: Boolean,
    bluetoothEnrollmentRunning: Boolean,
    bluetoothEnrollmentMessage: String,
    bluetoothEnrollmentError: Boolean,
    debugMenuEnabled: Boolean,
    onDebugMenuEnabledChange: (Boolean) -> Unit,
    stationDiscoveryNotificationEnabled: Boolean,
    onStationDiscoveryNotificationChange: (Boolean) -> Unit,
    appUpdateNotificationEnabled: Boolean,
    onAppUpdateNotificationChange: (Boolean) -> Unit,
    onEnrollBluetooth: () -> Unit,
    onRevokeTxApproval: () -> Unit,
    onSave: (String, String, String, String, SremProfile) -> Unit,
) {
    val context = LocalContext.current
    var draftMqttUri by rememberSaveable(mqttUri) { mutableStateOf(mqttUri) }
    var draftNodeId by rememberSaveable(nodeId) { mutableStateOf(nodeId) }
    var draftMaxQueueLength by rememberSaveable(maxQueueLength) { mutableStateOf(maxQueueLength) }
    var draftMaxQueueAgeSeconds by rememberSaveable(maxQueueAgeSeconds) { mutableStateOf(maxQueueAgeSeconds) }
    var draftSremProfile by rememberSaveable(sremProfile) { mutableStateOf(sremProfile) }

    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MqttCard(
            mqttUri = draftMqttUri,
            onMqttUriChange = { draftMqttUri = it },
            nodeId = draftNodeId,
            onNodeIdChange = { draftNodeId = it },
            maxQueueLength = draftMaxQueueLength,
            onMaxQueueLengthChange = { draftMaxQueueLength = it },
            maxQueueAgeSeconds = draftMaxQueueAgeSeconds,
            onMaxQueueAgeSecondsChange = { draftMaxQueueAgeSeconds = it },
        )

        VehicleTypeCard(
            sremProfile = draftSremProfile,
            onSremProfileChange = { draftSremProfile = it },
        )

        BluetoothCard(
            bridgeRunning = bridgeRunning,
            bluetoothEnrollmentRunning = bluetoothEnrollmentRunning,
            bluetoothEnrollmentMessage = bluetoothEnrollmentMessage,
            bluetoothEnrollmentError = bluetoothEnrollmentError,
            onEnrollBluetooth = onEnrollBluetooth,
        )

        NotificationsCard(
            stationDiscoveryNotificationEnabled = stationDiscoveryNotificationEnabled,
            onStationDiscoveryNotificationChange = onStationDiscoveryNotificationChange,
            appUpdateNotificationEnabled = appUpdateNotificationEnabled,
            onAppUpdateNotificationChange = onAppUpdateNotificationChange,
        )

        DebugCard(
            debugMenuEnabled = debugMenuEnabled,
            onDebugMenuEnabledChange = onDebugMenuEnabledChange,
            txApproved = txApproved,
            onRevokeTxApproval = onRevokeTxApproval,
        )

        Button(
            onClick = {
                onSave(
                    draftMqttUri,
                    draftNodeId,
                    draftMaxQueueLength,
                    draftMaxQueueAgeSeconds,
                    draftSremProfile,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                "Save",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun MqttCard(
    mqttUri: String,
    onMqttUriChange: (String) -> Unit,
    nodeId: String,
    onNodeIdChange: (String) -> Unit,
    maxQueueLength: String,
    onMaxQueueLengthChange: (String) -> Unit,
    maxQueueAgeSeconds: String,
    onMaxQueueAgeSecondsChange: (String) -> Unit,
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
                        imageVector = Icons.Rounded.Cloud,
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
                        "MQTT",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Connect to the broker and identify this node",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            OutlinedTextField(
                value = mqttUri,
                onValueChange = onMqttUriChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Broker URL") },
                placeholder = { Text("mqtt://broker.example:1883") },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = "Broker link",
                        tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            OutlinedTextField(
                value = nodeId,
                onValueChange = onNodeIdChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Node ID") },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Computer,
                        contentDescription = "Node ID computer",
                        tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                        modifier = Modifier.size(20.dp),
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = maxQueueLength,
                    onValueChange = onMaxQueueLengthChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Max queue length") },
                    placeholder = { Text("100") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Storage,
                            contentDescription = "Queue length storage",
                            tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )

                OutlinedTextField(
                    value = maxQueueAgeSeconds,
                    onValueChange = onMaxQueueAgeSecondsChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("Max queue age") },
                    placeholder = { Text("0.2") },
                    suffix = { Text("s") },
                    trailingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.Schedule,
                            contentDescription = "Queue age schedule",
                            tint = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun VehicleTypeCard(
    sremProfile: SremProfile,
    onSremProfileChange: (SremProfile) -> Unit,
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
                        imageVector = Icons.AutoMirrored.Rounded.DirectionsWalk,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.secondary)),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "Vehicle Type",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Used for CAM broadcasts and SREM requests",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        val index = SremProfile.entries.indexOf(sremProfile)
                        val newIndex = (index - 1 + SremProfile.entries.size) % SremProfile.entries.size
                        onSremProfileChange(SremProfile.entries[newIndex])
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = "Previous",
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(28.dp),
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .height(48.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(ContextCompat.getColor(context, R.color.primary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        sremProfile.displayName,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.primary)),
                        fontSize = 15.sp,
                    )
                }

                IconButton(
                    onClick = {
                        val index = SremProfile.entries.indexOf(sremProfile)
                        val newIndex = (index + 1) % SremProfile.entries.size
                        onSremProfileChange(SremProfile.entries[newIndex])
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "Next",
                        tint = Color(ContextCompat.getColor(context, R.color.primary)),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                SremProfile.entries.forEach { profile ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (profile == sremProfile) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (profile == sremProfile) {
                                    Color(ContextCompat.getColor(context, R.color.primary))
                                } else {
                                    Color(ContextCompat.getColor(context, R.color.border))
                                },
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun BluetoothCard(
    bridgeRunning: Boolean,
    bluetoothEnrollmentRunning: Boolean,
    bluetoothEnrollmentMessage: String,
    bluetoothEnrollmentError: Boolean,
    onEnrollBluetooth: () -> Unit,
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
                        imageVector = Icons.Rounded.Bluetooth,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.secondary)),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "Bluetooth",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Enrollment uses USB once. Afterwards the Bluetooth bond secures normal connections.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Button(
                onClick = onEnrollBluetooth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = !bridgeRunning && !bluetoothEnrollmentRunning,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(ContextCompat.getColor(context, R.color.primary_container)),
                    contentColor = Color(ContextCompat.getColor(context, R.color.primary)),
                ),
            ) {
                Text(
                    if (bluetoothEnrollmentRunning) "Enrolling…" else "Enroll this phone for Bluetooth",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            if (bridgeRunning) {
                StatusMessageRow(
                    message = "Stop the active connection before enrollment.",
                    isError = false,
                    icon = Icons.Rounded.Info,
                )
            } else if (bluetoothEnrollmentMessage.isNotBlank()) {
                StatusMessageRow(
                    message = bluetoothEnrollmentMessage,
                    isError = bluetoothEnrollmentError,
                    icon = Icons.Rounded.Info,
                )
            }
        }
    }
}

@Composable
private fun NotificationsCard(
    stationDiscoveryNotificationEnabled: Boolean,
    onStationDiscoveryNotificationChange: (Boolean) -> Unit,
    appUpdateNotificationEnabled: Boolean,
    onAppUpdateNotificationChange: (Boolean) -> Unit,
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
                        imageVector = Icons.Rounded.Notifications,
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
                        "Notifications",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Choose when the app alerts you outside the foreground.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            NotificationToggleRow(
                title = "Station discovery",
                description = "Notify when a new C-ITS station is discovered.",
                checked = stationDiscoveryNotificationEnabled,
                onCheckedChange = onStationDiscoveryNotificationChange,
            )

            NotificationToggleRow(
                title = "App updates",
                description = "Notify when a new release is available on Codeberg.",
                checked = appUpdateNotificationEnabled,
                onCheckedChange = onAppUpdateNotificationChange,
            )
        }
    }
}

@Composable
private fun NotificationToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = Color(ContextCompat.getColor(context, R.color.on_surface)),
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun DebugCard(
    debugMenuEnabled: Boolean,
    onDebugMenuEnabledChange: (Boolean) -> Unit,
    txApproved: Boolean,
    onRevokeTxApproval: () -> Unit,
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
                        .background(Color(ContextCompat.getColor(context, R.color.tertiary_container))),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Build,
                        contentDescription = null,
                        tint = Color(ContextCompat.getColor(context, R.color.tertiary)),
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        "Debug menu",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface)),
                    )
                    Text(
                        "Show firmware and transport diagnostics in the navigation drawer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(ContextCompat.getColor(context, R.color.on_surface_variant)),
                    )
                }
                Switch(
                    checked = debugMenuEnabled,
                    onCheckedChange = onDebugMenuEnabledChange,
                )
            }

            HorizontalDivider(
                color = Color(ContextCompat.getColor(context, R.color.divider)),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            Button(
                onClick = onRevokeTxApproval,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                enabled = txApproved,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (txApproved) {
                        Color(ContextCompat.getColor(context, R.color.error_light))
                    } else {
                        Color(ContextCompat.getColor(context, R.color.divider))
                    },
                    contentColor = if (txApproved) {
                        Color(ContextCompat.getColor(context, R.color.error))
                    } else {
                        Color(ContextCompat.getColor(context, R.color.disabled))
                    },
                ),
            ) {
                Text(
                    if (txApproved) "Revoke TX Approval" else "TX Approval not active",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatusMessageRow(
    message: String,
    isError: Boolean,
    icon: ImageVector,
) {
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isError) {
                Color(ContextCompat.getColor(context, R.color.error))
            } else {
                Color(ContextCompat.getColor(context, R.color.info))
            },
            modifier = Modifier.size(18.dp),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                Color(ContextCompat.getColor(context, R.color.error))
            } else {
                Color(ContextCompat.getColor(context, R.color.info))
            },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
