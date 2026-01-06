package com.mrboombastic.buwudzik.ui.screens

import com.mrboombastic.buwudzik.utils.AppLogger


import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.data.SettingsRepository

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val lastSeen: Long = System.currentTimeMillis()
)

@Composable
fun DeviceSetupScreen(navController: NavController) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val scanner = remember { BluetoothScanner(context) }
    val coroutineScope = rememberCoroutineScope()

    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val isBluetoothEnabled = bluetoothManager?.adapter?.isEnabled == true

    var isScanning by remember { mutableStateOf(false) }
    val discoveredDevices = remember { mutableStateListOf<DiscoveredDevice>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }

    val permissionsToRequest = remember {
        val perms = mutableListOf<String>()
        perms.add(Manifest.permission.BLUETOOTH_SCAN)
        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
        perms.toTypedArray()
    }

    var hasPermissions by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        hasPermissions = perms.values.all { it }
        if (hasPermissions && isBluetoothEnabled) {
            startDeviceScan(scanner, coroutineScope, discoveredDevices) { job, scanning ->
                scanJob = job
                isScanning = scanning
            }
        }
    }

    LaunchedEffect(Unit) {
        hasPermissions = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasPermissions) {
            launcher.launch(permissionsToRequest)
        } else if (isBluetoothEnabled) {
            startDeviceScan(scanner, coroutineScope, discoveredDevices) { job, scanning ->
                scanJob = job
                isScanning = scanning
            }
        }
    }

    // Cancel scan job when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            scanJob?.cancel()
            AppLogger.d("DeviceSetupScreen", "Screen disposed, scan job cancelled")
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

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

            Spacer(modifier = Modifier.height(32.dp))

            when {
                !isBluetoothEnabled -> {
                    Text(
                        text = stringResource(R.string.setup_enable_bluetooth),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
                !hasPermissions -> {
                    Text(
                        text = stringResource(R.string.permissions_required),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                }
                isScanning -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.setup_scanning),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (discoveredDevices.isEmpty() && !isScanning && hasPermissions && isBluetoothEnabled) {
                Text(
                    text = stringResource(R.string.setup_no_devices),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(discoveredDevices) { device ->
                    DeviceCard(
                        device = device,
                        onClick = {
                            scanJob?.cancel()
                            settingsRepository.targetMacAddress = device.address
                            settingsRepository.isSetupCompleted = true

                            // Navigate back or to home if this is initial setup
                            if (navController.previousBackStackEntry != null) {
                                navController.popBackStack()
                            } else {
                                navController.navigate("home") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasPermissions && isBluetoothEnabled) {
                    OutlinedButton(
                        onClick = {
                            scanJob?.cancel()
                            discoveredDevices.clear()
                            startDeviceScan(scanner, coroutineScope, discoveredDevices) { job, scanning ->
                                scanJob = job
                                isScanning = scanning
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !isScanning
                    ) {
                        Text(stringResource(R.string.setup_rescan))
                    }
                }

                TextButton(
                    onClick = {
                        scanJob?.cancel()
                        // Use default MAC address when skipping setup
                        settingsRepository.targetMacAddress = SettingsRepository.DEFAULT_MAC
                        settingsRepository.isSetupCompleted = true

                        // Navigate back or to home if this is initial setup
                        if (navController.previousBackStackEntry != null) {
                            navController.popBackStack()
                        } else {
                            navController.navigate("home") {
                                popUpTo("setup") { inclusive = true }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.setup_skip))
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DiscoveredDevice,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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

private fun startDeviceScan(
    scanner: BluetoothScanner,
    scope: kotlinx.coroutines.CoroutineScope,
    devices: MutableList<DiscoveredDevice>,
    onStateChange: (Job?, Boolean) -> Unit
) {
    val job = scope.launch {
        onStateChange(null, true)
        try {
            kotlinx.coroutines.withTimeout(15000L) { // Timeout after 15 seconds
                scanner.scan(targetAddress = null).collect { sensorData ->
                    val existingIndex = devices.indexOfFirst { it.address == sensorData.macAddress }
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

                    AppLogger.d("DeviceSetupScreen", "Found device: ${device.name} at ${device.address}")
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            AppLogger.d("DeviceSetupScreen", "Scan timeout after 15 seconds - found ${devices.size} device(s)")
        } catch (e: Exception) {
            Log.e("DeviceSetupScreen", "Scan error", e)
        } finally {
            onStateChange(null, false)
        }
    }
    onStateChange(job, true)
}






