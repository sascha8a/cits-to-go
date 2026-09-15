package org.opentrafficmap.citstogo

import org.opentrafficmap.citstogo.AppPage
import org.opentrafficmap.citstogo.ActivityLogEntry
import org.opentrafficmap.citstogo.ActivityLevel
import org.opentrafficmap.citstogo.DevicePosition
import org.opentrafficmap.citstogo.FirmwareFlashingState
import org.opentrafficmap.citstogo.FlashingPage
import org.opentrafficmap.citstogo.flashing.FirmwareFlashLogEntry
import org.opentrafficmap.citstogo.flashing.FirmwareRelease
import org.opentrafficmap.citstogo.intersection.IntersectionSnapshot
import org.opentrafficmap.citstogo.IntersectionSortMode
import org.opentrafficmap.citstogo.TxApprovalPromptState
import org.opentrafficmap.citstogo.BuildConfig

import org.opentrafficmap.citstogo.bridge.BridgeStatus
import org.opentrafficmap.citstogo.bridge.ConnectionMode
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import org.opentrafficmap.citstogo.srem.SremProfile

@Composable
fun CitsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(ContextCompat.getColor(context, R.color.primary)),
            secondary = Color(ContextCompat.getColor(context, R.color.secondary)),
            tertiary = Color(ContextCompat.getColor(context, R.color.tertiary)),
            surface = Color(ContextCompat.getColor(context, R.color.surface)),
            background = Color(ContextCompat.getColor(context, R.color.background)),
        ),
        content = content,
    )
}

@Composable
fun CitsApp(
    devices: List<android.hardware.usb.UsbDevice>,
    selectedDeviceName: String?,
    onSelectDevice: (String) -> Unit,
    connectionMode: ConnectionMode,
    onConnectionModeChange: (ConnectionMode) -> Unit,
    mqttUri: String,
    onMqttUriChange: (String) -> Unit,
    nodeId: String,
    onNodeIdChange: (String) -> Unit,
    maxQueueLength: String,
    onMaxQueueLengthChange: (String) -> Unit,
    maxQueueAgeSeconds: String,
    onMaxQueueAgeSecondsChange: (String) -> Unit,
    status: BridgeStatus,
    logLine: String,
    intersectionSnapshots: List<IntersectionSnapshot>,
    intersectionSortMode: IntersectionSortMode,
    onIntersectionSortModeChange: (IntersectionSortMode) -> Unit,
    currentPosition: DevicePosition?,
    bluetoothEnrollmentRunning: Boolean,
    bluetoothEnrollmentMessage: String,
    bluetoothEnrollmentError: Boolean,
    onRefresh: () -> Unit,
    onEnrollBluetooth: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onStartPcap: () -> Unit,
    onStopPcap: () -> Unit,
    onStartReplay: () -> Unit,
    onStopReplay: () -> Unit,
    camIntervalMs: String,
    onCamIntervalChange: (String) -> Unit,
    onConfigureCam: (Boolean) -> Unit,
    sremProfile: SremProfile,
    onSremProfileChange: (SremProfile) -> Unit,
    txApproved: Boolean,
    debugMenuEnabled: Boolean,
    onDebugMenuEnabledChange: (Boolean) -> Unit,
    txApprovalPromptState: TxApprovalPromptState,
    onGrantTxApproval: () -> Unit,
    onDismissTxApproval: () -> Unit,
    onFinishTxApproval: () -> Unit,
    onRevokeTxApproval: () -> Unit,
    onSendSrem: (IntersectionSnapshot, Int, Int) -> Unit,
    onIntersectionLocationActiveChange: (Boolean) -> Unit,
    flashingState: FirmwareFlashingState,
    flashingLog: List<FirmwareFlashLogEntry>,
    onFlashingPageActive: (Boolean) -> Unit,
    onRetryFirmwareRelease: () -> Unit,
    onSelectFirmwareRelease: (FirmwareRelease) -> Unit,
    onChooseCustomFirmware: () -> Unit,
    onUseReleaseFirmware: () -> Unit,
    onFlashFirmware: () -> Unit,
    onSaveSettings: (String, String, String, String, SremProfile) -> Unit,
) {
    val context = LocalContext.current
    var selectedPage by rememberSaveable { mutableStateOf(AppPage.Home) }
    var confettiRun by rememberSaveable { mutableStateOf(0) }
    var sliderDragging by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val recentActivity = remember { mutableStateListOf<ActivityLogEntry>() }
    var lastActivityLogLine by remember { mutableStateOf("") }
    var lastActivityError by remember { mutableStateOf("") }
    
    LaunchedEffect(logLine, status.lastError) {
        fun append(message: String, forcedLevel: ActivityLevel? = null) {
            val trimmed = message.trim()
            if (trimmed.isBlank() || trimmed.startsWith("Packet ", ignoreCase = true)) return
            val level = forcedLevel ?: activityLevelFor(trimmed)
            recentActivity.add(
                0,
                ActivityLogEntry(
                    timestamp = java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
                    level = level,
                    message = humanizeActivityMessage(trimmed),
                ),
            )
            while (recentActivity.size > 40) recentActivity.removeAt(recentActivity.lastIndex)
        }
        if (logLine != lastActivityLogLine) {
            append(logLine)
            lastActivityLogLine = logLine
        }
        if (status.lastError != lastActivityError) {
            append(status.lastError, ActivityLevel.ERROR)
            lastActivityError = status.lastError
        }
    }
    
    val visiblePages = remember(txApproved, debugMenuEnabled) {
        AppPage.entries.filter { it.visible(txApproved, debugMenuEnabled) }
    }
    
    LaunchedEffect(txApproved, debugMenuEnabled) {
        if (selectedPage !in visiblePages) selectedPage = AppPage.Home
    }
    
    LaunchedEffect(selectedPage) {
        onIntersectionLocationActiveChange(selectedPage == AppPage.IntersectionView)
        onFlashingPageActive(selectedPage == AppPage.Flashing)
    }
    
    LaunchedEffect(txApprovalPromptState) {
        if (txApprovalPromptState != TxApprovalPromptState.Hidden) {
            drawerState.close()
        }
        if (txApprovalPromptState == TxApprovalPromptState.Granting) {
            delay(500L)
            selectedPage = AppPage.Home
            onFinishTxApproval()
            confettiRun += 1
        }
    }

    CompositionLocalProvider(LocalSliderDragStateChange provides { sliderDragging = it }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    gesturesEnabled = !sliderDragging && txApprovalPromptState == TxApprovalPromptState.Hidden,
                    drawerContent = {
                        ModalDrawerSheet(
                            drawerContainerColor = MaterialTheme.colorScheme.surface,
                            drawerContentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Text(
                                "C-ITS to go",
                                modifier = Modifier.padding(20.dp),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            visiblePages.forEach { page ->
                                NavigationDrawerItem(
                                    label = { Text(page.title) },
                                    selected = page == selectedPage,
                                    onClick = {
                                        selectedPage = page
                                        scope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(horizontal = 12.dp),
                                    colors = NavigationDrawerItemDefaults.colors(
                                        selectedContainerColor = Color(ContextCompat.getColor(context, R.color.primary_container)),
                                        unselectedContainerColor = MaterialTheme.colorScheme.surface,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        unselectedTextColor = MaterialTheme.colorScheme.secondary,
                                    ),
                                )
                            }
                        }
                    },
                ) {
                    val mainScrollState = rememberScrollState()
                    val contentModifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(if (selectedPage == AppPage.Home) 16.dp else 20.dp)
                        .then(
                            if (selectedPage == AppPage.IntersectionView) {
                                Modifier
                            } else {
                                Modifier.verticalScroll(mainScrollState)
                            },
                        )
                    Column(
                        modifier = contentModifier,
                        verticalArrangement = Arrangement.spacedBy(if (selectedPage == AppPage.Home) 10.dp else 14.dp),
                    ) {
                        val headerSubtitle = when (selectedPage) {
                            AppPage.Settings, AppPage.About -> "Version ${BuildConfig.VERSION_NAME}"
                            else -> null
                        }
                        AppHeader(
                            title = selectedPage.title,
                            onOpenMenu = { scope.launch { drawerState.open() } },
                            subtitle = headerSubtitle,
                        )
                        when (selectedPage) {
                            AppPage.Home -> HomePage(
                                devices = devices,
                                selectedDeviceName = selectedDeviceName,
                                onSelectDevice = onSelectDevice,
                                connectionMode = connectionMode,
                                onConnectionModeChange = onConnectionModeChange,
                                status = status,
                                onRefresh = onRefresh,
                                onStart = onStart,
                                onStop = onStop,
                                onStartPcap = onStartPcap,
                                onStopPcap = onStopPcap,
                                onStartReplay = onStartReplay,
                                onStopReplay = onStopReplay,
                            )
                            AppPage.CamBroadcast -> CamBroadcastPage(
                                status = status,
                                logLine = logLine,
                                intervalMs = camIntervalMs,
                                onIntervalChange = onCamIntervalChange,
                                onConfigure = onConfigureCam,
                            )
                            AppPage.IntersectionView -> IntersectionViewPage(
                                snapshots = intersectionSnapshots,
                                sortMode = intersectionSortMode,
                                onSortModeChange = onIntersectionSortModeChange,
                                status = status,
                                currentPosition = currentPosition,
                                txApproved = txApproved,
                                sremProfile = sremProfile,
                                onSendSrem = onSendSrem,
                                modifier = Modifier.weight(1f),
                            )
                            AppPage.Flashing -> FlashingPage(
                                state = flashingState,
                                log = flashingLog,
                                bridgeRunning = status.running,
                                onRetryRelease = onRetryFirmwareRelease,
                                onSelectRelease = onSelectFirmwareRelease,
                                onChooseCustomFirmware = onChooseCustomFirmware,
                                onUseReleaseFirmware = onUseReleaseFirmware,
                                onFlash = onFlashFirmware,
                            )
                            AppPage.Settings -> SettingsPage(
                                mqttUri = mqttUri,
                                nodeId = nodeId,
                                maxQueueLength = maxQueueLength,
                                maxQueueAgeSeconds = maxQueueAgeSeconds,
                                txApproved = txApproved,
                                sremProfile = sremProfile,
                                bridgeRunning = status.running,
                                bluetoothEnrollmentRunning = bluetoothEnrollmentRunning,
                                bluetoothEnrollmentMessage = bluetoothEnrollmentMessage,
                                bluetoothEnrollmentError = bluetoothEnrollmentError,
                                debugMenuEnabled = debugMenuEnabled,
                                onDebugMenuEnabledChange = onDebugMenuEnabledChange,
                                onEnrollBluetooth = onEnrollBluetooth,
                                onRevokeTxApproval = onRevokeTxApproval,
                                onSave = { updatedMqttUri, updatedNodeId, updatedMaxQueueLength, updatedMaxQueueAgeSeconds, updatedSremProfile ->
                                    onMqttUriChange(updatedMqttUri)
                                    onNodeIdChange(updatedNodeId)
                                    onMaxQueueLengthChange(updatedMaxQueueLength)
                                    onMaxQueueAgeSecondsChange(updatedMaxQueueAgeSeconds)
                                    onSremProfileChange(updatedSremProfile)
                                    onSaveSettings(
                                        updatedMqttUri,
                                        updatedNodeId,
                                        updatedMaxQueueLength,
                                        updatedMaxQueueAgeSeconds,
                                        updatedSremProfile,
                                    )
                                },
                            )
                            AppPage.Debug -> DebugPage(
                                status = status,
                                connectionMode = connectionMode,
                            )
                            AppPage.About -> AboutPage()
                        }
                    }
                }
                TxApprovalOverlay(
                    state = txApprovalPromptState,
                    onGrantApproval = onGrantTxApproval,
                    onDismiss = onDismissTxApproval,
                )
                ConfettiOverlay(run = confettiRun)
            }
        }
    }
}
