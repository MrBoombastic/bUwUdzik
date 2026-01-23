package com.mrboombastic.buwudzik.ui.screens

import android.icu.util.TimeZone
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.MainViewModel
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.Language
import com.mrboombastic.buwudzik.device.TempUnit
import com.mrboombastic.buwudzik.device.TimeFormat
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import com.mrboombastic.buwudzik.ui.components.BinaryToggleChips
import com.mrboombastic.buwudzik.ui.components.SettingsDropdown
import com.mrboombastic.buwudzik.ui.components.TimePickerDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository = remember { SettingsRepository(context) }
    var batteryType by remember { mutableStateOf(repository.batteryType) }

    val settings by viewModel.deviceSettings.collectAsState()
    val isBusy by viewModel.qpController.isBusy.collectAsState()
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // UI Lock state - only lock during the final save operation
    val isUiEnabled = !isSaving

    // Time picker states
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    var immediateUpdateJob by remember { mutableStateOf<Job?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Effect to show snackbar when errorMessage changes
    @Suppress("AssignedValueIsNeverRead") LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it, duration = SnackbarDuration.Long
            )
            errorMessage = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }, topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.device_settings_title)) },
            navigationIcon = {
                BackNavigationButton(navController, enabled = isUiEnabled)
            },
            actions = {
                if (isSaving || isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .size(24.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
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
            if (settings == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val currentSettings = settings!!

                // State variables
                var tempUnit by remember { mutableStateOf(currentSettings.tempUnit) }
                var timeFormat by remember { mutableStateOf(currentSettings.timeFormat) }
                var language by remember { mutableStateOf(currentSettings.language) }
                var volume by remember { mutableFloatStateOf(currentSettings.volume.toFloat()) }

                var screenBrightness by remember { mutableFloatStateOf(currentSettings.screenBrightness.toFloat()) }
                var backlightDuration by remember { mutableFloatStateOf(currentSettings.backlightDuration.toFloat()) }
                var nightModeBrightness by remember { mutableFloatStateOf(currentSettings.nightModeBrightness.toFloat()) }

                var nightModeEnabled by remember { mutableStateOf(currentSettings.nightModeEnabled) }
                var nightStartHour by remember { mutableIntStateOf(currentSettings.nightStartHour) }
                var nightStartMinute by remember { mutableIntStateOf(currentSettings.nightStartMinute) }
                var nightEndHour by remember { mutableIntStateOf(currentSettings.nightEndHour) }
                var nightEndMinute by remember { mutableIntStateOf(currentSettings.nightEndMinute) }

                // Define save function using current state
                @Suppress("AssignedValueIsNeverRead")
                val saveSettings = {
                    val newSettings = currentSettings.copy(
                        tempUnit = tempUnit,
                        timeFormat = timeFormat,
                        language = language,
                        volume = volume.toInt(),
                        timeZone = TimeZone.getDefault(),
                        nightModeBrightness = nightModeBrightness.toInt(),
                        backlightDuration = backlightDuration.toInt(),
                        screenBrightness = screenBrightness.toInt(),
                        nightStartHour = nightStartHour,
                        nightStartMinute = nightStartMinute,
                        nightEndHour = nightEndHour,
                        nightEndMinute = nightEndMinute,
                        nightModeEnabled = nightModeEnabled
                    )
                    isSaving = true
                    viewModel.updateDeviceSettings(newSettings) { result ->
                        isSaving = false
                        if (result.isFailure) {
                            val ex = result.exceptionOrNull()
                            errorMessage =
                                if (ex is kotlinx.coroutines.TimeoutCancellationException) {
                                    appContext.getString(R.string.device_timeout_message)
                                } else {
                                    appContext.getString(
                                        R.string.save_failed_message, ex?.message ?: "Unknown"
                                    )
                                }
                        }
                    }
                }

                // Update local state when settings change from external source
                LaunchedEffect(currentSettings) {
                    tempUnit = currentSettings.tempUnit
                    timeFormat = currentSettings.timeFormat
                    language = currentSettings.language
                    volume = currentSettings.volume.toFloat()
                    screenBrightness = currentSettings.screenBrightness.toFloat()
                    backlightDuration = currentSettings.backlightDuration.toFloat()
                    nightModeBrightness = currentSettings.nightModeBrightness.toFloat()
                    nightModeEnabled = currentSettings.nightModeEnabled
                    nightStartHour = currentSettings.nightStartHour
                    nightStartMinute = currentSettings.nightStartMinute
                    nightEndHour = currentSettings.nightEndHour
                    nightEndMinute = currentSettings.nightEndMinute
                }

                // Local Settings Section
                Text(
                    stringResource(R.string.local_settings_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.local_settings_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))

                val batteryTypes = mapOf(
                    "alkaline" to stringResource(R.string.battery_alkaline),
                    "nimh" to stringResource(R.string.battery_nimh)
                )

                SettingsDropdown(
                    value = batteryType,
                    options = batteryTypes,
                    label = stringResource(R.string.battery_type_label),
                    onValueChange = { type ->
                        batteryType = type
                        repository.batteryType = type
                        // Force refresh of sensor data correction if needed, but repository update is enough for next scan
                    })

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                // General Settings Section
                Text(
                    stringResource(R.string.general_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                // Language
                BinaryToggleChips(
                    label = stringResource(R.string.language_header),
                    value = language,
                    option1 = Language.English to stringResource(R.string.lang_english),
                    option2 = Language.Chinese to stringResource(R.string.lang_chinese),
                    onValueChange = { language = it; saveSettings() },
                    enabled = isUiEnabled
                )
                Spacer(Modifier.height(8.dp))

                // Temperature Unit
                BinaryToggleChips(
                    label = stringResource(R.string.temp_unit_header),
                    value = tempUnit,
                    option1 = TempUnit.Celsius to stringResource(R.string.unit_c),
                    option2 = TempUnit.Fahrenheit to stringResource(R.string.unit_f),
                    onValueChange = { tempUnit = it; saveSettings() },
                    enabled = isUiEnabled
                )
                Spacer(Modifier.height(8.dp))

                // Time Format
                BinaryToggleChips(
                    label = stringResource(R.string.time_format_header),
                    value = timeFormat,
                    option1 = TimeFormat.H24 to stringResource(R.string.format_24h),
                    option2 = TimeFormat.H12 to stringResource(R.string.format_12h),
                    onValueChange = { timeFormat = it; saveSettings() },
                    enabled = isUiEnabled
                )

                Spacer(Modifier.height(16.dp))

                // Volume
                Text(stringResource(R.string.alert_volume_label, volume.toInt()))
                Slider(
                    value = volume, enabled = isUiEnabled, onValueChange = {
                        volume = it
                        // Immediate update with debouncing
                        immediateUpdateJob?.cancel()
                        immediateUpdateJob = coroutineScope.launch {
                            delay(500) // Debounce to avoid spamming BLE commands
                            val tempSettings = currentSettings.copy(volume = it.toInt())
                            viewModel.qpController.enqueueCommand {
                                viewModel.qpController.previewRingtone(tempSettings)
                            }
                        }
                    }, onValueChangeFinished = {
                        saveSettings()
                    }, valueRange = 1f..5f, steps = 3
                )

                Spacer(Modifier.height(8.dp))

                // Ringtone Upload
                OutlinedCard(
                    onClick = { navController.navigate("ringtone-upload") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isUiEnabled
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ringtone_upload_button),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = currentSettings.getRingtoneName()
                                    ?: stringResource(R.string.custom_ringtone_name),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                // Display Settings Section
                Text(
                    stringResource(R.string.display_header),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))

                // Screen Brightness
                Text(stringResource(R.string.day_brightness_label, screenBrightness.toInt()))
                Slider(
                    value = screenBrightness, enabled = isUiEnabled, onValueChange = {
                        screenBrightness = it
                        // Immediate update with debouncing
                        immediateUpdateJob?.cancel()
                        immediateUpdateJob = coroutineScope.launch {
                            delay(200) // Lower debounce for smoother brightness preview
                            viewModel.qpController.enqueueCommand {
                                viewModel.qpController.setDaytimeBrightnessImmediate(it.toInt())
                            }
                        }
                    }, onValueChangeFinished = {
                        saveSettings()
                    }, valueRange = 0f..100f, steps = 9 // Firmware uses nibble / 10
                )
                Spacer(Modifier.height(8.dp))

                // Backlight Duration
                Text(stringResource(R.string.backlight_duration_label, backlightDuration.toInt()))

                Slider(
                    value = backlightDuration,
                    enabled = isUiEnabled,
                    onValueChange = { backlightDuration = it },
                    onValueChangeFinished = { saveSettings() },
                    valueRange = 0f..60f,
                    steps = 59
                )

                HorizontalDivider(Modifier.padding(vertical = 16.dp))

                // Night Mode Section
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.night_mode_header),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = nightModeEnabled, onCheckedChange = {
                            nightModeEnabled = it
                            saveSettings()
                        }, enabled = isUiEnabled
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (nightModeEnabled) {
                    // Night Mode Brightness
                    Text(
                        stringResource(
                            R.string.night_brightness_label, nightModeBrightness.toInt()
                        )
                    )
                    Slider(
                        value = nightModeBrightness, enabled = isUiEnabled, onValueChange = {
                            nightModeBrightness = it
                            // Immediate update with debouncing
                            immediateUpdateJob?.cancel()
                            immediateUpdateJob = coroutineScope.launch {
                                delay(200) // Lower debounce for smoother brightness preview
                                viewModel.qpController.enqueueCommand {
                                    viewModel.qpController.setNightBrightnessImmediate(it.toInt())
                                }
                            }
                        }, onValueChangeFinished = {
                            saveSettings()
                        }, valueRange = 0f..100f, steps = 9 // Firmware uses nibble / 10
                    )
                    Spacer(Modifier.height(8.dp))

                    // Schedule
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Start Time
                        OutlinedCard(
                            onClick = { showStartTimePicker = true },
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 8.dp),
                            enabled = isUiEnabled
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.start_time_label),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    String.format(
                                        Locale.getDefault(),
                                        "%02d:%02d",
                                        nightStartHour,
                                        nightStartMinute
                                    ), style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }

                        // End Time
                        OutlinedCard(
                            onClick = { showEndTimePicker = true },
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 8.dp),
                            enabled = isUiEnabled
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    stringResource(R.string.end_time_label),
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    String.format(
                                        Locale.getDefault(),
                                        "%02d:%02d",
                                        nightEndHour,
                                        nightEndMinute
                                    ), style = MaterialTheme.typography.titleLarge
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // Time Picker Dialogs
                if (showStartTimePicker) {
                    val pickerState = rememberTimePickerState(
                        initialHour = nightStartHour,
                        initialMinute = nightStartMinute,
                        is24Hour = true
                    )
                    TimePickerDialog(onDismiss = { showStartTimePicker = false }, onConfirm = {
                        nightStartHour = pickerState.hour
                        nightStartMinute = pickerState.minute
                        showStartTimePicker = false
                        saveSettings()
                    }, content = { TimePicker(state = pickerState) })
                }

                if (showEndTimePicker) {
                    val pickerState = rememberTimePickerState(
                        initialHour = nightEndHour, initialMinute = nightEndMinute, is24Hour = true
                    )
                    TimePickerDialog(onDismiss = { showEndTimePicker = false }, onConfirm = {
                        nightEndHour = pickerState.hour
                        nightEndMinute = pickerState.minute
                        showEndTimePicker = false
                        saveSettings()
                    }, content = { TimePicker(state = pickerState) })
                }

                // Firmware Version
                if (currentSettings.firmwareVersion.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 16.dp))
                    Text(
                        text = stringResource(
                            R.string.firmware_version_fmt, currentSettings.firmwareVersion
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}




