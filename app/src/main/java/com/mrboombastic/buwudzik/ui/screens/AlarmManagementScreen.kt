package com.mrboombastic.buwudzik.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.MainViewModel
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.device.Alarm
import com.mrboombastic.buwudzik.ui.components.BackNavigationButton
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmManagementScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val isBluetoothEnabled = BluetoothUtils.isBluetoothEnabled(context)
    val hasPermissions = remember { BluetoothUtils.hasBluetoothPermissions(context) }

    val alarms by viewModel.alarms.collectAsState()
    val deviceConnected by viewModel.deviceConnected.collectAsState()
    val deviceSettings by viewModel.deviceSettings.collectAsState()
    var statusMessage by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var selectedAlarm by remember { mutableStateOf<Alarm?>(null) }

    // Pre-fetch string resources for use in callbacks
    val updatingAlarmMsg = stringResource(R.string.updating_alarm_msg)
    val alarmUpdatedMsg = stringResource(R.string.alarm_updated_msg)
    val deletingAlarmMsg = stringResource(R.string.deleting_alarm_msg)
    val alarmDeletedMsg = stringResource(R.string.alarm_deleted_msg)
    val errorPrefixMsg = stringResource(R.string.error_prefix)
    val savingSettingsMsg = stringResource(R.string.updating_label)

    // Auto-clear messages
    LaunchedEffect(statusMessage, errorMessage) {
        if (statusMessage.isNotEmpty() || errorMessage.isNotEmpty()) {
            kotlinx.coroutines.delay(2000)
            statusMessage = ""
            errorMessage = ""
        }
    }

    // Edit alarm dialog
    selectedAlarm?.let { alarm ->
        @Suppress("AssignedValueIsNeverRead") AlarmEditDialog(
            alarm = alarm,
            onDismiss = { selectedAlarm = null },
            onSave = { updatedAlarm ->
                coroutineScope.launch {
                    isUpdating = true
                    errorMessage = ""
                    statusMessage = String.format(updatingAlarmMsg, updatedAlarm.id + 1)

                    viewModel.updateAlarm(updatedAlarm) { result ->
                        if (result.isSuccess) {
                            statusMessage = String.format(alarmUpdatedMsg, updatedAlarm.id + 1)
                            selectedAlarm = null
                        } else {
                            errorMessage = String.format(
                                errorPrefixMsg, result.exceptionOrNull()?.message ?: "Unknown"
                            )
                        }
                        isUpdating = false
                    }
                }
            },
            onDelete = {
                coroutineScope.launch {
                    isUpdating = true
                    errorMessage = ""
                    statusMessage = String.format(deletingAlarmMsg, alarm.id + 1)

                    viewModel.deleteAlarm(alarm.id) { result ->
                        if (result.isSuccess) {
                            statusMessage = String.format(alarmDeletedMsg, alarm.id + 1)
                            selectedAlarm = null
                        } else {
                            errorMessage = String.format(
                                errorPrefixMsg, result.exceptionOrNull()?.message ?: "Unknown"
                            )
                        }
                        isUpdating = false
                    }
                }
            })
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.alarm_management_title)) },
            navigationIcon = {
                BackNavigationButton(navController)
            })
    }, floatingActionButton = {
        if (deviceConnected && hasPermissions) {
            FloatingActionButton(onClick = {
                if (!isUpdating) {
                    if (alarms.size < 16) {
                        // Create new alarm with next available ID
                        // Find first unused ID just in case, though usually simple append works
                        val usedIds = alarms.map { it.id }.toSet()
                        val newId = (0..15).firstOrNull { !usedIds.contains(it) } ?: alarms.size

                        @Suppress("AssignedValueIsNeverRead")
                        if (newId < 16) {
                            selectedAlarm = Alarm(
                                id = newId,
                                enabled = true,
                                hour = 8,
                                minute = 0,
                                days = 0,
                                snooze = false
                            )
                        } else {
                            Toast.makeText(context, R.string.max_alarms_message, Toast.LENGTH_SHORT)
                                .show()
                        }
                    } else {
                        Toast.makeText(context, R.string.max_alarms_message, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }) {
                Icon(
                    Icons.Default.Add, contentDescription = stringResource(R.string.add_alarm_desc)
                )
            }
        }
    }, snackbarHost = {
        // Status messages as snackbar overlay - doesn't affect layout
        if (isUpdating || statusMessage.isNotEmpty() || errorMessage.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    color = when {
                        errorMessage.isNotEmpty() -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 6.dp,
                    shadowElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp), strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = when {
                                errorMessage.isNotEmpty() -> errorMessage
                                statusMessage.isNotEmpty() -> statusMessage
                                isUpdating -> stringResource(R.string.updating_label)
                                else -> ""
                            }, style = MaterialTheme.typography.bodyMedium, color = when {
                                errorMessage.isNotEmpty() -> MaterialTheme.colorScheme.onErrorContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                    }
                }
            }
        }
    }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when {
                !isBluetoothEnabled -> {
                    Text(
                        text = stringResource(R.string.enable_bluetooth_msg),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                !hasPermissions -> {
                    Text(
                        text = stringResource(R.string.bluetooth_perms_msg),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                else -> {
                    if (!deviceConnected) {
                        Text(
                            text = stringResource(R.string.connect_first_msg),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp)
                        )
                    } else {
                        // Master Alarm Switch
                        deviceSettings?.let { settings ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = stringResource(R.string.master_alarm_label),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Switch(
                                        checked = !settings.masterAlarmDisabled,
                                        onCheckedChange = { enabled ->
                                            val newSettings =
                                                settings.copy(masterAlarmDisabled = !enabled)
                                            coroutineScope.launch {
                                                isUpdating = true
                                                statusMessage = savingSettingsMsg
                                                viewModel.updateDeviceSettings(newSettings) { result ->
                                                    isUpdating = false
                                                    if (result.isFailure) {
                                                        errorMessage = String.format(
                                                            errorPrefixMsg,
                                                            result.exceptionOrNull()?.message
                                                                ?: "Unknown"
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        enabled = !isUpdating
                                    )
                                }
                            }
                        }

                        if (deviceSettings?.masterAlarmDisabled == false) {
                            if (alarms.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.no_alarms_msg),
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.padding(16.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(alarms) { alarm ->
                                        @Suppress("AssignedValueIsNeverRead") AlarmCard(
                                            alarm = alarm,
                                            enabled = !isUpdating,
                                            onToggle = { enabled ->
                                                coroutineScope.launch {
                                                    isUpdating = true
                                                    errorMessage = ""
                                                    statusMessage = String.format(
                                                        updatingAlarmMsg, alarm.id + 1
                                                    )
                                                    val updatedAlarm = alarm.copy(enabled = enabled)
                                                    viewModel.updateAlarm(updatedAlarm) { result ->
                                                        if (result.isSuccess) {
                                                            statusMessage = String.format(
                                                                alarmUpdatedMsg, alarm.id + 1
                                                            )
                                                        } else {
                                                            errorMessage = String.format(
                                                                errorPrefixMsg,
                                                                result.exceptionOrNull()?.message
                                                                    ?: "Unknown"
                                                            )
                                                        }
                                                        isUpdating = false
                                                    }
                                                }
                                            },
                                            onEdit = { selectedAlarm = alarm })
                                    }
                                }
                            }
                        } else if (deviceSettings != null) {
                            // Message shown when alarms are globally disabled
                            Spacer(modifier = Modifier.height(32.dp))
                            Text(
                                text = stringResource(R.string.master_alarm_off_msg),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmCard(alarm: Alarm, enabled: Boolean, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    // Pre-fetch day strings to avoid using context.getString() in getDaysList()
    val dayOnce = stringResource(R.string.day_once)
    val dayMon = stringResource(R.string.day_mon)
    val dayTue = stringResource(R.string.day_tue)
    val dayWed = stringResource(R.string.day_wed)
    val dayThu = stringResource(R.string.day_thu)
    val dayFri = stringResource(R.string.day_fri)
    val daySat = stringResource(R.string.day_sat)
    val daySun = stringResource(R.string.day_sun)

    val daysText = remember(alarm.days) {
        if (alarm.days == 0) {
            dayOnce
        } else {
            buildList {
                if (alarm.days and 0x01 != 0) add(dayMon)
                if (alarm.days and 0x02 != 0) add(dayTue)
                if (alarm.days and 0x04 != 0) add(dayWed)
                if (alarm.days and 0x08 != 0) add(dayThu)
                if (alarm.days and 0x10 != 0) add(dayFri)
                if (alarm.days and 0x20 != 0) add(daySat)
                if (alarm.days and 0x40 != 0) add(daySun)
            }.joinToString(", ")
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onEdit,
        enabled = enabled
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // Show title if present
                if (alarm.title.isNotBlank()) {
                    Text(
                        text = alarm.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = alarm.getTimeString(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = daysText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (alarm.snooze) {
                    Text(
                        text = stringResource(R.string.snooze_enabled_msg),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Switch(
                checked = alarm.enabled, onCheckedChange = onToggle, enabled = enabled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditDialog(
    alarm: Alarm, onDismiss: () -> Unit, onSave: (Alarm) -> Unit, onDelete: () -> Unit
) {
    var selectedHour by remember { mutableIntStateOf(alarm.hour) }
    var selectedMinute by remember { mutableIntStateOf(alarm.minute) }
    var selectedDays by remember { mutableIntStateOf(alarm.days) }
    var snoozeEnabled by remember { mutableStateOf(alarm.snooze) }
    var alarmTitle by remember { mutableStateOf(alarm.title) }
    var showTimePicker by remember { mutableStateOf(false) }


    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = selectedHour, initialMinute = selectedMinute, is24Hour = true
        )

        @Suppress("AssignedValueIsNeverRead") AlertDialog(onDismissRequest = {
            showTimePicker = false
        }, title = { Text(stringResource(R.string.select_time_title)) }, text = {
            TimePicker(state = timePickerState)
        }, confirmButton = {
            Button(onClick = {
                selectedHour = timePickerState.hour
                selectedMinute = timePickerState.minute
                showTimePicker = false
            }) {
                Text(stringResource(R.string.ok))
            }
        }, dismissButton = {
            TextButton(onClick = {
                showTimePicker = false
            }) {
                Text(stringResource(R.string.cancel))
            }
        })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_alarm_title, alarm.id + 1)) },
        text = {
            Column {
                // Time display with click to edit
                @Suppress("AssignedValueIsNeverRead") OutlinedButton(
                    onClick = {
                        showTimePicker = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    Text(
                        text = String.format(
                            Locale.getDefault(), "%02d:%02d", selectedHour, selectedMinute
                        ), style = MaterialTheme.typography.headlineLarge
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Title input field
                OutlinedTextField(
                    value = alarmTitle,
                    onValueChange = { alarmTitle = it },
                    label = { Text(stringResource(R.string.alarm_title_label)) },
                    placeholder = { Text(stringResource(R.string.alarm_title_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.repeat_on_label),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                // Day selection chips
                val days = listOf(
                    stringResource(R.string.day_mon),
                    stringResource(R.string.day_tue),
                    stringResource(R.string.day_wed),
                    stringResource(R.string.day_thu),
                    stringResource(R.string.day_fri),
                    stringResource(R.string.day_sat),
                    stringResource(R.string.day_sun)
                )
                val daysInRows = days.chunked(4)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    daysInRows.forEach { rowDays ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            rowDays.forEach { day ->
                                val index = days.indexOf(day)
                                val isSelected = (selectedDays and (1 shl index)) != 0
                                FilterChip(
                                    selected = isSelected, onClick = {
                                        selectedDays = if (isSelected) {
                                            selectedDays and (1 shl index).inv()
                                        } else {
                                            selectedDays or (1 shl index)
                                        }
                                    }, label = {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(day, fontSize = 14.sp)
                                        }
                                    }, modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                )
                            }
                            // Add spacers to fill the row if it's not full
                            if (rowDays.size < 4) {
                                repeat(4 - rowDays.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.snooze_label),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = snoozeEnabled, onCheckedChange = { snoozeEnabled = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    alarm.copy(
                        hour = selectedHour,
                        minute = selectedMinute,
                        days = selectedDays,
                        snooze = snoozeEnabled,
                        title = alarmTitle.trim()
                    )
                )
            }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_alarm_desc),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        })
}


