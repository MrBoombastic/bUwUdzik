package com.mrboombastic.buwudzik

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
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

    var expandedMode by remember { mutableStateOf(false) }
    var expandedLang by remember { mutableStateOf(false) }
    var expandedInterval by remember { mutableStateOf(false) }
    var expandedTheme by remember { mutableStateOf(false) }

    var showAppPicker by remember { mutableStateOf(false) }
    var installedApps by remember { mutableStateOf<List<ResolveInfo>>(emptyList()) }
    var selectedAppLabel by remember { mutableStateOf<String?>(null) }

    var isCheckingUpdates by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Check Bluetooth status
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val isBluetoothEnabled = bluetoothManager?.adapter?.isEnabled == true

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
                        Log.d("SettingsScreen", "MAC changed from $initialMacAddress to $currentMac, restarting scan")
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

    LaunchedEffect(showAppPicker) {
        if (showAppPicker && installedApps.isEmpty()) {
            withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).addCategory(Intent.CATEGORY_LAUNCHER)
                val apps = pm.queryIntentActivities(intent, 0)
                Log.d("SettingsScreen", "Found ${apps.size} launcher apps")
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

    val scanModes =
        mapOf(
            ScanSettings.SCAN_MODE_LOW_POWER to stringResource(R.string.mode_low_power),
            ScanSettings.SCAN_MODE_BALANCED to stringResource(R.string.mode_balanced),
            ScanSettings.SCAN_MODE_LOW_LATENCY to stringResource(R.string.mode_low_latency)
        )

    val languages = mapOf(
        "system" to stringResource(R.string.language_system),
        "en" to "English",
        "pl" to "polski"
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
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = {
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
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_desc)
                        )
                    }
                }
            )
        }
    ) { padding ->
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
                    supportingText = if (macAddress.trim().isEmpty()) {
                        { Text(stringResource(R.string.default_mac_label, SettingsRepository.DEFAULT_MAC)) }
                    } else null
                )

                FilledTonalIconButton(
                    onClick = {
                        // Mark setup as incomplete to show device selection
                        repository.isSetupCompleted = false
                        navController.navigate("setup")
                    },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = stringResource(R.string.scan_device_button)
                    )
                }
            }

            Text(
                text = stringResource(R.string.default_mac_label, SettingsRepository.DEFAULT_MAC),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, start = 8.dp, bottom = 16.dp)
            )

            // Scan Mode
            Text(
                stringResource(R.string.scan_mode_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expandedMode,
                onExpandedChange = { expandedMode = !expandedMode }
            ) {
                OutlinedTextField(
                    value = scanModes[scanMode] ?: "Unknown",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.select_mode_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedMode)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedMode,
                    onDismissRequest = { expandedMode = false }) {
                    scanModes.forEach { (mode, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                scanMode = mode
                                repository.scanMode = mode
                                expandedMode = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Language
            Text(
                stringResource(R.string.language_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expandedLang,
                onExpandedChange = { expandedLang = !expandedLang }
            ) {
                OutlinedTextField(
                    value = languages[language] ?: language,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.language_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedLang)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedLang,
                    onDismissRequest = { expandedLang = false }) {
                    languages.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                language = code
                                repository.language = code
                                expandedLang = false
                                // Apply Language Immediately
                                val appLocale = if (code == "system") {
                                    LocaleListCompat.getEmptyLocaleList()
                                } else {
                                    LocaleListCompat.forLanguageTags(code)
                                }
                                AppCompatDelegate.setApplicationLocales(appLocale)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Theme
            Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expandedTheme,
                onExpandedChange = { expandedTheme = !expandedTheme }
            ) {
                OutlinedTextField(
                    value = themes[theme] ?: theme,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.theme_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTheme)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedTheme,
                    onDismissRequest = { expandedTheme = false }) {
                    themes.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                theme = code
                                repository.theme = code
                                expandedTheme = false

                                val mode = when (code) {
                                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                                }
                                AppCompatDelegate.setDefaultNightMode(mode)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Update Interval
            Text(
                stringResource(R.string.update_interval_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = expandedInterval,
                onExpandedChange = { expandedInterval = !expandedInterval }
            ) {
                OutlinedTextField(
                    value = intervals[updateInterval] ?: "$updateInterval min",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.update_interval_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedInterval)
                    },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expandedInterval,
                    onDismissRequest = { expandedInterval = false }) {
                    intervals.forEach { (minutes, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                updateInterval = minutes
                                repository.updateInterval = minutes
                                expandedInterval = false
                                // Reschedule updates immediately
                                MainActivity.scheduleUpdates(context, minutes)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Widget Action
            Text(
                stringResource(R.string.widget_action_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedCard(
                onClick = { showAppPicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = selectedAppLabel ?: stringResource(R.string.default_app_label),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            if (showAppPicker) {
                AlertDialog(
                    onDismissRequest = { showAppPicker = false },
                    title = { Text(stringResource(R.string.select_app_label)) },
                    text = {
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            Text(
                                text = stringResource(R.string.default_app_label),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedAppPackage = null
                                        repository.selectedAppPackage = null
                                        showAppPicker = false
                                    }
                                    .padding(20.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            installedApps.forEach { resolveInfo ->
                                val pm = context.packageManager
                                val label = resolveInfo.loadLabel(pm).toString()
                                val pkg = resolveInfo.activityInfo.packageName
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedAppPackage = pkg
                                            repository.selectedAppPackage = pkg
                                            showAppPicker = false
                                        }
                                        .padding(20.dp),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAppPicker = false }) { Text("Cancel") }
                    }
                )
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
                    val appContext = context.applicationContext
                    coroutineScope.launch {
                        try {
                            val updateChecker = UpdateChecker(appContext)
                            val result = updateChecker.checkForUpdatesWithResult()
                            withContext(Dispatchers.Main) {
                                isCheckingUpdates = false
                                val message = if (result.updateAvailable) {
                                    appContext.getString(
                                        R.string.update_available,
                                        result.latestVersion
                                    )
                                } else {
                                    appContext.getString(R.string.no_updates_available)
                                }
                                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Log.e("Error while trying to update", e.toString())
                            withContext(Dispatchers.Main) {
                                isCheckingUpdates = false
                                val errorMessage = appContext.getString(R.string.update_error)
                                Toast.makeText(appContext, errorMessage, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                },
                enabled = !isCheckingUpdates,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = if (isCheckingUpdates)
                        stringResource(R.string.checking_updates)
                    else
                        stringResource(R.string.check_updates_label)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // App Info
            Text(
                stringResource(R.string.about_app_label),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionName: String? = packageInfo.versionName
            Text(stringResource(R.string.version_label, versionName ?: "N/A"))
            Text(stringResource(R.string.author_label, "MrBoombastic"))
        }
    }
}
