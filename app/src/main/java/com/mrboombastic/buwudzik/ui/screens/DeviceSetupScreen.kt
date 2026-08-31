package com.mrboombastic.buwudzik.ui.screens


import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.IntentFilter
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.BluetoothStateReceiver
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.BatteryType
import com.mrboombastic.buwudzik.data.DeviceProfile
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.data.normalizedBluetoothMac
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.ui.components.StatusCard
import com.mrboombastic.buwudzik.ui.components.StatusType
import com.mrboombastic.buwudzik.ui.utils.AdaptiveScreen
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration.Companion.milliseconds

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

/**
 * Screen for discovering and selecting a CGD1 device.
 *
 * @param mode "setup" for first-launch flow (skip → home), "add" for adding extra devices.
 * @param viewModel Required when mode == "add" to call addDevice/setActiveDevice.
 */
@Composable
fun DeviceSetupScreen(
    navController: NavController,
    mode: String = "setup",
    viewModel: MainViewModel? = null
) {
    val context = LocalContext.current
    val invalidTokenMessage = stringResource(R.string.import_token_invalid)
    val scanner = remember {
        val repo = DeviceProfileRepository(context)
        BluetoothScanner(context, repo)
    }

    var isBluetoothEnabled by remember {
        mutableStateOf(BluetoothUtils.isBluetoothEnabled(context))
    }

    DisposableEffect(context) {
        val receiver = BluetoothStateReceiver { enabled ->
            isBluetoothEnabled = enabled
            viewModel?.updateBluetoothState(enabled)
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val currentEnabled = BluetoothUtils.isBluetoothEnabled(context)
        isBluetoothEnabled = currentEnabled
        viewModel?.updateBluetoothState(currentEnabled)

        onDispose { context.unregisterReceiver(receiver) }
    }

    DisposableEffect(mode, viewModel) {
        if (mode == "add") {
            viewModel?.stopScanning()
        }
        onDispose {
            if (mode == "add") {
                viewModel?.startScanning()
            }
        }
    }

    var isScanning by remember {
        mutableStateOf(
            isBluetoothEnabled && BluetoothUtils.hasBluetoothPermissions(context)
        )
    }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var selectedAddress by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRssi by rememberSaveable { mutableIntStateOf(0) }
    var optionalToken by rememberSaveable { mutableStateOf("") }
    val selectedDevice = selectedAddress?.let { address ->
        DiscoveredDevice(
            name = selectedName,
            address = address,
            rssi = selectedRssi
        )
    }

    val emptySavedProfiles =
        remember { MutableStateFlow<List<DeviceProfile>>(emptyList()) }
    val savedProfiles by (viewModel?.devices ?: emptySavedProfiles).collectAsState()
    val savedMacAddresses = remember(savedProfiles) {
        savedProfiles.map { it.mac.normalizedBluetoothMac() }.toSet()
    }

    val permissionsToRequest = remember {
        mutableListOf<String>().apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var hasBluetoothPermissions by remember {
        mutableStateOf(BluetoothUtils.hasBluetoothPermissions(context))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val hasPerms = BluetoothUtils.hasBluetoothPermissions(context)
        hasBluetoothPermissions = hasPerms
        val currentEnabled = BluetoothUtils.isBluetoothEnabled(context)
        isBluetoothEnabled = currentEnabled
        viewModel?.updateBluetoothState(currentEnabled)
    }

    LaunchedEffect(Unit) {
        hasBluetoothPermissions = BluetoothUtils.hasBluetoothPermissions(context)
        if (!hasBluetoothPermissions) {
            launcher.launch(permissionsToRequest)
        }
    }

    // Start scanning when permissions and Bluetooth are available; stop/cancel when unavailable
    LaunchedEffect(isBluetoothEnabled, hasBluetoothPermissions, selectedAddress) {
        isScanning =
            selectedAddress == null && hasBluetoothPermissions && isBluetoothEnabled
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            discoveredDevices.clear()
            try {
                performDeviceScan(scanner, discoveredDevices, savedMacAddresses)
            } finally {
                isScanning = false
            }
        }
    }

    fun onDeviceSelected(device: DiscoveredDevice) {
        isScanning = false
        selectedAddress = device.address.normalizedBluetoothMac()
        selectedName = device.name
        selectedRssi = device.rssi
        optionalToken = ""
    }

    fun addSelectedDevice(device: DiscoveredDevice) {
        val normalizedToken = normalizeAuthTokenInput(optionalToken)
        if (normalizedToken.isNotEmpty() && !isValidAuthTokenInput(normalizedToken)) {
            Toast.makeText(
                context,
                invalidTokenMessage,
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val profile = DeviceProfile(
            mac = device.address.normalizedBluetoothMac(),
            alias = device.name ?: device.address,
            batteryType = BatteryType.ALKALINE
        )

        if (normalizedToken.isNotEmpty()) {
            val tokenStorage = TokenStorage(context)
            tokenStorage.storeToken(
                profile.mac,
                tokenStorage.hexToBytes(normalizedToken)
            )
        }
        viewModel?.addDevice(profile, makeActive = true)
        if (normalizedToken.isNotEmpty()) {
            viewModel?.checkPairingStatus()
        }

        if (mode == "add" && viewModel != null) {
            if (navController.previousBackStackEntry?.destination?.route == "devices") {
                navController.popBackStack()
            } else {
                navController.navigate("home") {
                    popUpTo(0) { inclusive = true }
                }
            }
        } else {
            if (navController.previousBackStackEntry != null) {
                navController.popBackStack()
            } else {
                navController.navigate("home") {
                    popUpTo("setup") { inclusive = true }
                }
            }
        }
    }

    fun onSkip() {
        isScanning = false
        if (navController.previousBackStackEntry != null) {
            navController.popBackStack()
        } else {
            navController.navigate("home") {
                popUpTo("setup") { inclusive = true }
            }
        }
    }

    Scaffold { padding ->
        AdaptiveScreen(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            columnModifier = Modifier
                .padding(horizontal = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.setup_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (selectedDevice == null) {
                    when {
                        !isBluetoothEnabled -> {
                            StatusCard(
                                message = stringResource(R.string.setup_enable_bluetooth),
                                type = StatusType.ERROR
                            )
                        }

                        !hasBluetoothPermissions -> {
                            StatusCard(
                                message = stringResource(R.string.permissions_required),
                                type = StatusType.WARNING
                            )
                        }

                        isScanning -> {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.setup_scanning),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    if (discoveredDevices.isEmpty() && !isScanning &&
                        hasBluetoothPermissions && isBluetoothEnabled
                    ) {
                        StatusCard(
                            message = stringResource(R.string.setup_no_devices),
                            type = StatusType.INFO,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    discoveredDevices.forEach { device ->
                        DeviceCard(
                            device = device,
                            onClick = { onDeviceSelected(device) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.setup_selected_device),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    DeviceCard(
                        device = selectedDevice,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.setup_token_optional_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = optionalToken,
                        onValueChange = { optionalToken = it },
                        label = {
                            Text(stringResource(R.string.setup_token_optional_label))
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { addSelectedDevice(selectedDevice) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.setup_add_device))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            val mac = Uri.encode(
                                selectedDevice.address.normalizedBluetoothMac()
                            )
                            navController.navigate("device-import/$mac")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.setup_import_selected))
                    }
                    TextButton(
                        onClick = {
                            selectedAddress = null
                            selectedName = null
                            selectedRssi = 0
                            optionalToken = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.setup_choose_another))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (selectedDevice == null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (hasBluetoothPermissions && isBluetoothEnabled) {
                        OutlinedButton(
                            onClick = {
                                isScanning = true
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isScanning
                        ) {
                            Text(stringResource(R.string.setup_scan))
                        }
                    }

                    TextButton(
                        onClick = { onSkip() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.setup_skip))
                    }
                }
            }
        }
    }
}


@Composable
fun DeviceCard(
    device: DiscoveredDevice,
    onClick: (() -> Unit)?
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name ?: stringResource(R.string.setup_select_device),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                val signalPercentage = BluetoothUtils.rssiToPercentage(device.rssi)
                Text(
                    text = "$signalPercentage%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        signalPercentage > 70 -> MaterialTheme.colorScheme.primary
                        signalPercentage > 40 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                )
                Text(
                    text = "${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private suspend fun performDeviceScan(
    scanner: BluetoothScanner,
    devices: MutableList<DiscoveredDevice>,
    savedMacAddresses: Set<String>
) {
    try {
        kotlinx.coroutines.withTimeout(15000L.milliseconds) {
            scanner.scan(targetAddress = null).collect { sensorData ->
                val macKey = sensorData.macAddress.normalizedBluetoothMac()
                if (macKey in savedMacAddresses) {
                    return@collect
                }
                val existingIndex =
                    devices.indexOfFirst { it.address.normalizedBluetoothMac() == macKey }
                val device = DiscoveredDevice(
                    name = sensorData.name,
                    address = sensorData.macAddress,
                    rssi = sensorData.rssi
                )

                if (existingIndex >= 0) {
                    devices[existingIndex] = device
                } else {
                    devices.add(device)
                }

                AppLogger.d(
                    "DeviceSetupScreen",
                    "Found device: ${device.name} at ${device.address}"
                )
            }
        }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        AppLogger.d(
            "DeviceSetupScreen",
            "Scan timeout after 15 seconds - found ${devices.size} device(s)"
        )
    } catch (_: kotlinx.coroutines.CancellationException) {
        AppLogger.d("DeviceSetupScreen", "Scan cancelled (navigation or composition change)")
    } catch (e: Exception) {
        AppLogger.e("DeviceSetupScreen", "Scan error", e)
    }
}
