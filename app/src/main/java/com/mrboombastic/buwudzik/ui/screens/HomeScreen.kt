package com.mrboombastic.buwudzik.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.components.InstructionCard
import com.mrboombastic.buwudzik.ui.components.MenuTile
import com.mrboombastic.buwudzik.ui.components.NumberedStep
import com.mrboombastic.buwudzik.ui.components.SmallButton
import com.mrboombastic.buwudzik.ui.home.rememberDeviceSheetOverscrollConnection
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.adaptiveContentWidth
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

private const val TAG = "HomeScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, navController: NavController) {
    val context = LocalContext.current
    val sensorData by viewModel.sensorData.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()
    val devices by viewModel.devices.collectAsState()
    var showDeviceSheetSwipeHint by remember {
        mutableStateOf(!viewModel.deviceSheetSwipeHintAlreadyShown())
    }
    var deviceSwitcherOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val dashboardScroll = rememberScrollState()
    val densitySheetOverscroll = LocalDensity.current
    val sheetOverscrollThresholdPx = remember(densitySheetOverscroll) {
        with(densitySheetOverscroll) { 56.dp.toPx() }
    }
    val dashboardOpenSheetNested = rememberDeviceSheetOverscrollConnection(
        activeDevice = activeDevice,
        devices = devices,
        dashboardScroll = dashboardScroll,
        sheetOverscrollThresholdPx = sheetOverscrollThresholdPx,
        onOpenDeviceSheet = { deviceSwitcherOpen = true }
    )

    val permissionsToRequest = BluetoothUtils.BLUETOOTH_PERMISSIONS

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(), onResult = { perms ->
            val allGranted = perms.values.all { it }
            AppLogger.d(TAG, "Permissions result: $perms, All granted: $allGranted")
            viewModel.refreshBluetoothState()
            if (allGranted) {
                if (activeDevice != null) {
                    viewModel.startScanning()
                }
            } else {
                val deniedPerms = perms.filter { !it.value }.keys.joinToString(", ")
                AppLogger.w(TAG, "Permissions denied. Missing: $deniedPerms")
            }
        })

    LaunchedEffect(Unit) {
        viewModel.refreshBluetoothState()
        val allGranted = permissionsToRequest.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        AppLogger.d(TAG, "Initial permission check. All granted: $allGranted")
        if (!allGranted) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(activeDevice) {
        val allGranted = permissionsToRequest.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted && activeDevice != null) {
            viewModel.startScanning()
        }
    }

    LaunchedEffect(deviceSwitcherOpen) {
        if (deviceSwitcherOpen) {
            sheetState.partialExpand()
            if (showDeviceSheetSwipeHint) {
                viewModel.markDeviceSheetSwipeHintSeen()
                showDeviceSheetSwipeHint = false
            }
        }
    }

    BackHandler(enabled = deviceSwitcherOpen) {
        scope.launch {
            sheetState.hide()
            deviceSwitcherOpen = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            floatingActionButton = {
                FloatingActionButton(onClick = { navController.navigate("settings") }) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(R.string.settings_desc)
                    )
                }
            }) { padding ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                if (activeDevice != null) {
                    AssistChip(
                        onClick = { deviceSwitcherOpen = true },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.active_device_label,
                                    activeDevice!!.alias
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DevicesOther,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = 4.dp
                        )
                    )
                }

                if (activeDevice != null && showDeviceSheetSwipeHint) {
                    val dismissSwipeHint = {
                        viewModel.markDeviceSheetSwipeHintSeen()
                        showDeviceSheetSwipeHint = false
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.device_sheet_swipe_hint_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.device_sheet_swipe_hint_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            FilledTonalButton(
                                onClick = dismissSwipeHint,
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(stringResource(R.string.device_sheet_swipe_hint_got_it))
                            }
                        }
                    }
                }

                Dashboard(
                    sensorData = sensorData,
                    isBluetoothEnabled = isBluetoothEnabled,
                    hasActiveDevice = activeDevice != null,
                    navController = navController,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .let { m ->
                            if (activeDevice != null) {
                                // Top padding is manually applied to the chip above; we still need
                                // the bottom padding so scrollable content doesn't hide under the FAB.
                                m.padding(bottom = padding.calculateBottomPadding())
                            } else {
                                m.padding(padding)
                            }
                        },
                    scrollState = dashboardScroll,
                    openSheetNestedScroll = dashboardOpenSheetNested
                )
            }
        }



        if (deviceSwitcherOpen) {
            ModalBottomSheet(
                onDismissRequest = { deviceSwitcherOpen = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                dragHandle = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BottomSheetDefaults.DragHandle(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        )
                    }
                }
            ) {
                val density = LocalDensity.current
                val swipeUpExpandPx = remember(density) { with(density) { 56.dp.toPx() } }
                var sheetDragAccum by remember { mutableFloatStateOf(0f) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .pointerInput(sheetState.currentValue) {
                                detectVerticalDragGestures(
                                    onDragStart = { sheetDragAccum = 0f },
                                    onVerticalDrag = { _, dragAmount ->
                                        sheetDragAccum += dragAmount
                                        if (sheetState.currentValue == SheetValue.PartiallyExpanded &&
                                            sheetDragAccum <= -swipeUpExpandPx
                                        ) {
                                            sheetDragAccum = 0f
                                            scope.launch { sheetState.expand() }
                                        }
                                    }
                                )
                            },
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 4.dp,
                            bottom = 20.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            count = 1,
                            key = { "device_sheet_divider" }
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        items(devices.sortedBy { it.addedAt }, key = { it.mac }) { profile ->
                            val isActive = profile.mac == activeDevice?.mac
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                tonalElevation = if (isActive) 2.dp else 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isActive) {
                                            viewModel.setActiveDevice(profile.mac)
                                        }
                                        scope.launch {
                                            sheetState.hide()
                                            deviceSwitcherOpen = false
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(24.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profile.alias,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                        Text(
                                            text = profile.mac,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.75f
                                                )
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        items(
                            count = 1,
                            key = { "manage_devices_row" }
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp)
                                    .clickable {
                                        scope.launch {
                                            sheetState.hide()
                                            deviceSwitcherOpen = false
                                            navController.navigate("devices")
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 18.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = stringResource(R.string.manage_devices_label),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShareDeviceButton(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    SmallButton(
        title = stringResource(R.string.share_device_button),
        icon = Icons.Default.Share,
        onClick = { navController.navigate("device-sharing") },
        modifier = modifier
    )
}

@Composable
fun Dashboard(
    sensorData: SensorData?,
    isBluetoothEnabled: Boolean,
    hasActiveDevice: Boolean,
    navController: NavController,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    openSheetNestedScroll: NestedScrollConnection? = null
) {
    val scroll = scrollState ?: rememberScrollState()
    val deviceConnected by viewModel.deviceConnected.collectAsState()
    val deviceConnecting by viewModel.deviceConnecting.collectAsState()
    val isPaired by viewModel.isPaired.collectAsState()



    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxHeight()
            .adaptiveContentWidth()
            .let { m ->
                if (openSheetNestedScroll != null) m.nestedScroll(openSheetNestedScroll)
                else m
            }
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (!isBluetoothEnabled) {
            Text(
                text = stringResource(R.string.bluetooth_disabled),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        if (sensorData == null && !hasActiveDevice) {
            Text(
                text = stringResource(R.string.no_devices_message),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            MenuTile(
                title = stringResource(R.string.manage_devices_label),
                icon = Icons.Default.DevicesOther,
                onClick = { navController.navigate("devices") },
                modifier = Modifier.fillMaxWidth(0.9f),
                arrangementH = Arrangement.Center
            )
        } else if (!isPaired && hasActiveDevice) {
            // Real device selected but not paired: ALWAYS show pairing instructions
            if (deviceConnecting) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.connecting_to_device),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                InstructionCard(
                    icon = Icons.Default.PhonelinkSetup,
                    title = stringResource(R.string.setup_new_device),
                    subtitle = stringResource(R.string.pairing_subtitle),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    NumberedStep(
                        number = "1",
                        title = stringResource(R.string.pairing_step1_title),
                        description = stringResource(R.string.pairing_step1_desc)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedStep(
                        number = "2",
                        title = stringResource(R.string.pairing_step2_title),
                        description = stringResource(R.string.pairing_step2_desc)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedStep(
                        number = "3",
                        title = stringResource(R.string.pairing_step3_title),
                        description = stringResource(R.string.pairing_step3_desc)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                MenuTile(
                    title = stringResource(R.string.pair_and_connect),
                    icon = Icons.Default.PhonelinkSetup,
                    onClick = { viewModel.connectToDevice() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    arrangementH = Arrangement.Center
                )
            }
        } else if (deviceConnecting && hasActiveDevice) {
            // Paired device (or fake): GATT connect in progress
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.connecting_to_device),
                style = MaterialTheme.typography.bodyMedium
            )
        } else if (sensorData == null) {
            // We have a paired device but no broadcast data yet
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.scanning_status))
        } else {
            // Main Dashboard for paired (or fake) device with sensor data
            if (!sensorData.name.isNullOrEmpty()) {
                Text(
                    text = sensorData.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Text(
                text = "${String.format(LocalLocale.current.platformLocale, "%.1f", sensorData.temperature)}°C",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${String.format(LocalLocale.current.platformLocale, "%.1f", sensorData.humidity)}%",
                fontSize = 48.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.battery_label, sensorData.battery),
                    fontSize = 24.sp
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            val signalPercentage = BluetoothUtils.rssiToPercentage(sensorData.rssi)
            Text(
                text = stringResource(R.string.rssi_label, sensorData.rssi, signalPercentage),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val dateFormat = SimpleDateFormat("HH:mm:ss", LocalLocale.current.platformLocale)
            val timeString = dateFormat.format(Date(sensorData.timestamp))
            Text(
                text = stringResource(R.string.last_update_label, timeString),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (!deviceConnected) {
                // Not connected via GATT
                MenuTile(
                    title = stringResource(R.string.connect_to_device),
                    icon = Icons.Default.PhonelinkSetup,
                    onClick = { viewModel.connectToDevice() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    arrangementH = Arrangement.Center
                )

                Spacer(modifier = Modifier.height(8.dp))
                ShareDeviceButton(
                    navController = navController,
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            } else {
                // Connected via GATT
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MenuTile(
                        title = stringResource(R.string.manage_alarms_label),
                        icon = Icons.Default.Alarm,
                        onClick = { navController.navigate("alarms") })
                    MenuTile(
                        title = stringResource(R.string.device_settings_button),
                        icon = Icons.Default.Settings,
                        onClick = { navController.navigate("device-settings") })
                    MenuTile(
                        title = stringResource(R.string.disconnect),
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = { viewModel.disconnectFromDevice() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                ShareDeviceButton(
                    navController = navController,
                    modifier = Modifier.fillMaxWidth(0.9f)
                )
            }
        }
    }
}
