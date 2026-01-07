package com.mrboombastic.buwudzik.ui.screens


import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.ResolveInfo
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.MainActivity
import com.mrboombastic.buwudzik.MainViewModel
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.UpdateCheckResult
import com.mrboombastic.buwudzik.UpdateChecker
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import com.mrboombastic.buwudzik.ui.components.SettingsDropdown
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.ThemeUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember { SettingsRepository(context) }

    // Save initial values to detect changes
    val initialMacAddress = remember { repository.targetMacAddress }
    val initialScanMode = remember { repository.scanMode }

    var macAddress by remember { mutableStateOf(repository.targetMacAddress) }
    var scanMode by remember { mutableIntStateOf(repository.scanMode) }
    var language by remember { mutableStateOf(repository.language) }
    var updateInterval by remember { mutableLongStateOf(repository.updateInterval) }
    var selectedAppPackage by remember { mutableStateOf(repository.selectedAppPackage) }
    var theme by remember { mutableStateOf(repository.theme) }

    var expandedWidgetAction by remember { mutableStateOf(false) }

    var installedApps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
    var selectedAppLabel by remember { mutableStateOf<String?>(null) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var versionName by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Load version name asynchronously to avoid blocking main thread
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                versionName = packageInfo.versionName
            } catch (_: Exception) {
                versionName = "N/A"
            }
        }
    }

    // Check Bluetooth status
    val isBluetoothEnabled = BluetoothUtils.isBluetoothEnabled(context)

    // Watch for MAC changes when returning from device setup screen
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Check if MAC was changed while we were away (e.g., from setup screen)
                val currentMac = repository.targetMacAddress
                if (currentMac != macAddress) {
                    macAddress = currentMac
                    // If MAC changed from initial value, restart scanning
                    if (currentMac != initialMacAddress) {
                        AppLogger.d(
                            "SettingsScreen",
                            "MAC changed from $initialMacAddress to $currentMac, restarting scan"
                        )
                        viewModel.restartScanning()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(expandedWidgetAction) {
        if (expandedWidgetAction && installedApps.isEmpty()) {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0)
                AppLogger.d("SettingsScreen", "Found ${apps.size} launcher apps")
                installedApps = apps.sortedBy { it.loadLabel(pm).toString().lowercase() }
            }
        }
    }

    LaunchedEffect(selectedAppPackage) {
        if (selectedAppPackage != null) {

            withContext(Dispatchers.IO) {
                try {
                    val pm = context.packageManager
                    val appInfo = pm.getApplicationInfo(selectedAppPackage!!, 0)
                    selectedAppLabel = pm.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    selectedAppLabel = selectedAppPackage
                }
            }
        } else {
            selectedAppLabel = null
        }
    }

    val scanModes = mapOf(
        ScanSettings.SCAN_MODE_LOW_POWER to stringResource(R.string.mode_low_power),
        ScanSettings.SCAN_MODE_BALANCED to stringResource(R.string.mode_balanced),
        ScanSettings.SCAN_MODE_LOW_LATENCY to stringResource(R.string.mode_low_latency)
    )

    val languages = mapOf(
        "system" to stringResource(R.string.language_system), "en" to "English", "pl" to "polski"
    )

    val intervals = mapOf(
        1L to stringResource(R.string.minutes_1),
        3L to stringResource(R.string.minutes_3),
        5L to stringResource(R.string.minutes_5),
        10L to stringResource(R.string.minutes_10),
        15L to stringResource(R.string.minutes_15),
        30L to stringResource(R.string.minutes_30),
        45L to stringResource(R.string.minutes_45),
        60L to stringResource(R.string.minutes_60),
        120L to stringResource(R.string.hours_2),
        240L to stringResource(R.string.hours_4),
        480L to stringResource(R.string.hours_8),
        720L to stringResource(R.string.hours_12),
        1440L to stringResource(R.string.hours_24)
    )

    val themes = mapOf(
        "system" to stringResource(R.string.theme_system),
        "light" to stringResource(R.string.theme_light),
        "dark" to stringResource(R.string.theme_dark)
    )



    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.settings_title)) }, navigationIcon = {
                BackNavigationButton(navController) {
                    // Ensure MAC is not empty before going back
                    val finalMac = macAddress.trim().ifEmpty { SettingsRepository.DEFAULT_MAC }
                    if (finalMac != repository.targetMacAddress) {
                        repository.targetMacAddress = finalMac
                    }

                    // Restart scanning if MAC or scan mode changed
                    if (finalMac != initialMacAddress || scanMode != initialScanMode) {
                        viewModel.restartScanning()
                    }
                    navController.popBackStack()
                }
            })
        }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // MAC Address with Scan Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = macAddress,
                    onValueChange = {
                        macAddress = it
                        // Don't save empty MAC - use default if cleared
                        val macToSave = it.trim().ifEmpty { SettingsRepository.DEFAULT_MAC }
                        repository.targetMacAddress = macToSave
                    },
                    label = { Text(stringResource(R.string.target_mac_label)) },
                    modifier = Modifier.weight(1f),
                    isError = macAddress.trim().isEmpty(),
                )

                FilledTonalIconButton(
                    onClick = {
                        // Mark setup as incomplete to show device selection
                        repository.isSetupCompleted = false
                        navController.navigate("setup")
                    }, modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.scan_devices_button)
                    )
                }
            }


            // Scan Mode
            Spacer(modifier = Modifier.height(12.dp))

            SettingsDropdown(
                value = scanMode,
                options = scanModes,
                label = stringResource(R.string.scan_mode_label),
                onValueChange = { mode ->
                    scanMode = mode
                    repository.scanMode = mode
                })
            Text(
                text = stringResource(R.string.scan_mode_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )

            // Language
            Spacer(modifier = Modifier.height(12.dp))

            SettingsDropdown(
                value = language,
                options = languages,
                label = stringResource(R.string.language_label),
                onValueChange = { code ->
                    language = code
                    repository.language = code
                    // Apply Language Immediately
                    val appLocale = if (code == "system") {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.forLanguageTags(code)
                    }
                    AppCompatDelegate.setApplicationLocales(appLocale)
                })

            // Theme
            Spacer(modifier = Modifier.height(12.dp))

            SettingsDropdown(
                value = theme,
                options = themes,
                label = stringResource(R.string.theme_label),
                onValueChange = { code ->
                    theme = code
                    repository.theme = code
                    ThemeUtils.applyTheme(code)
                })


            // Update Interval
            Spacer(modifier = Modifier.height(12.dp))

            SettingsDropdown(
                value = updateInterval,
                options = intervals,
                label = stringResource(R.string.widget_update_interval_label),
                onValueChange = { minutes ->
                    updateInterval = minutes
                    repository.updateInterval = minutes
                    // Reschedule updates immediately
                    MainActivity.scheduleUpdates(context, minutes)
                })


            // Widget Action
            Spacer(modifier = Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = expandedWidgetAction,
                onExpandedChange = { expandedWidgetAction = !expandedWidgetAction }) {
                OutlinedTextField(
                    value = selectedAppLabel ?: stringResource(R.string.default_app_label),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.widget_select_app_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedWidgetAction)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = expandedWidgetAction,
                    onDismissRequest = { expandedWidgetAction = false },
                    modifier = Modifier.height(300.dp)
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.default_app_label)) },
                        onClick = {
                            selectedAppPackage = null

                            repository.selectedAppPackage = null

                            expandedWidgetAction = false
                        })

                    installedApps.forEach { resolveInfo ->
                        val pm = context.packageManager
                        val label = resolveInfo.loadLabel(pm).toString()
                        val pkg = resolveInfo.activityInfo.packageName
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            selectedAppPackage = pkg

                            repository.selectedAppPackage = pkg

                            expandedWidgetAction = false
                        })
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bluetooth Status
            if (!isBluetoothEnabled) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.bluetooth_disabled),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Check for Updates
            Button(
                onClick = {
                    isCheckingUpdates = true
                    coroutineScope.launch {
                        try {
                            val updateChecker = UpdateChecker(appContext)
                            val result = updateChecker.checkForUpdates()
                            updateChecker.close()

                            withContext(Dispatchers.Main) {
                                isCheckingUpdates = false
                                if (result.updateAvailable) {
                                    updateResult = result
                                    showUpdateDialog = true
                                } else {
                                    Toast.makeText(
                                        appContext,
                                        appContext.getString(R.string.no_updates_available),
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("SettingsScreen", "Error checking for updates", e)
                            withContext(Dispatchers.Main) {
                                isCheckingUpdates = false
                                Toast.makeText(
                                    appContext,
                                    appContext.getString(R.string.update_error),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }, enabled = !isCheckingUpdates, modifier = Modifier.fillMaxWidth()
            ) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = if (isCheckingUpdates) stringResource(R.string.checking_updates)
                    else stringResource(R.string.check_updates_label)
                )
            }



            Spacer(modifier = Modifier.height(8.dp))

            // Import Device Button
            Button(
                onClick = { navController.navigate("device-import") },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.import_device_button))
            }

            // Update Available Dialog
            if (showUpdateDialog && updateResult != null) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = {
                        showUpdateDialog = false
                    },
                    title = { Text(stringResource(R.string.update_available_title)) },
                    text = {
                        Text(
                            stringResource(
                                R.string.update_available_message,
                                updateResult!!.currentVersion,
                                updateResult!!.latestVersion
                            )
                        )
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(
                            onClick = {
                                showUpdateDialog = false
                                val downloadUrl = updateResult?.downloadUrl
                                if (downloadUrl != null) {
                                    coroutineScope.launch {
                                        val updateChecker = UpdateChecker(appContext)
                                        updateChecker.downloadAndInstall(downloadUrl)
                                        updateChecker.close()
                                    }
                                }
                            }) {
                            Text(stringResource(R.string.download_update))
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(
                            onClick = { showUpdateDialog = false }) {
                            Text(stringResource(R.string.later))
                        }
                    })
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Info
            Text(
                stringResource(R.string.about_app_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(stringResource(R.string.version_label, versionName ?: "N/A"))
            Text(stringResource(R.string.author_label, "MrBoombastic"))

            // Easter Egg Button
            Spacer(modifier = Modifier.height(16.dp))
            val deviceConnected by viewModel.deviceConnected.collectAsState()
            if (deviceConnected) {
                val successMsg = stringResource(R.string.time_set_success)
                val failedMsg = stringResource(R.string.time_set_failed)
                val errorTemplate = stringResource(R.string.error_prefix)

                Button(
                    onClick = {
                        coroutineScope.launch {
                            try {
                                val cal = java.util.Calendar.getInstance()
                                cal.set(java.util.Calendar.HOUR_OF_DAY, 21)
                                cal.set(java.util.Calendar.MINUTE, 37)
                                cal.set(java.util.Calendar.SECOND, 0)
                                val timestamp = cal.timeInMillis / 1000
                                val success = viewModel.qpController.synchronizeTime(timestamp)
                                if (success) {
                                    Toast.makeText(
                                        appContext, successMsg, Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        appContext, failedMsg, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            } catch (e: Exception) {
                                Log.e("SettingsScreen", "Error setting time", e)
                                Toast.makeText(
                                    appContext,
                                    errorTemplate.format(e.message ?: "Unknown"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.set_time_2137))
                }
            }
        }
    }
}







