package com.mrboombastic.buwudzik

import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.mrboombastic.buwudzik.ui.theme.BuwudzikTheme
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.ThemeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainViewModel(
    private val scanner: BluetoothScanner,
    private val settingsRepository: SettingsRepository,
    private val applicationContext: Context
) : ViewModel() {

    private val sensorRepository = SensorRepository(applicationContext)

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _clockConnected = MutableStateFlow(false)
    val clockConnected: StateFlow<Boolean> = _clockConnected.asStateFlow()

    private val _clockConnecting = MutableStateFlow(false)
    val clockConnecting: StateFlow<Boolean> = _clockConnecting.asStateFlow()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _deviceSettings = MutableStateFlow<DeviceSettings?>(null)
    val deviceSettings: StateFlow<DeviceSettings?> = _deviceSettings.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    val clockController = QingpingController(applicationContext)

    private var scanJob: Job? = null
    private var rssiPollJob: Job? = null

    init {
        _isBluetoothEnabled.value = BluetoothUtils.isBluetoothEnabled(applicationContext)
    }

    fun updateBluetoothState(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (enabled) {
            startScanning()
        } else {
            // scanJob automatically cancels or fails, but good to be explicit
            scanJob?.cancel()
            _clockConnected.value = false
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) {
            Log.d("MainViewModel", "Scan already active, ignoring start request.")
            return
        }

        val targetMac = settingsRepository.targetMacAddress
        val scanMode = settingsRepository.scanMode
        Log.d("MainViewModel", "Starting scanning flow for $targetMac with mode $scanMode...")
        scanJob = viewModelScope.launch {
            scanner.scan(targetMac, scanMode).collect { data ->
                Log.d("MainViewModel", "Received data: $data")
                _sensorData.value = data
                sensorRepository.saveSensorData(data)
            }
        }
    }

    fun restartScanning() {
        Log.d("MainViewModel", "Restarting scan...")
        scanJob?.cancel()
        scanJob = null
        _sensorData.value = null
        startScanning()
    }

    fun connectToClock(reloadAlarms: Boolean = true) {
        scanJob?.cancel()
        viewModelScope.launch {
            try {
                if (reloadAlarms) {
                    _clockConnecting.value = true
                    _clockConnected.value = false
                }
                Log.d("MainViewModel", "Starting clock connection...")

                val targetMac = settingsRepository.targetMacAddress
                val bluetoothManager =
                    applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter = bluetoothManager?.adapter
                val device = adapter?.getRemoteDevice(targetMac)

                if (device == null) {
                    Log.e("MainViewModel", "Device not found: $targetMac")
                    return@launch
                }

                val connected = clockController.connectAndAuthenticate(device)
                if (connected) {
                    _clockConnected.value = true
                    scanJob?.cancel()

                    // Setup real-time updates
                    clockController.onSensorData = { temperature, humidity ->
                        _sensorData.value = _sensorData.value?.copy(
                            temperature = temperature.toDouble(),
                            humidity = humidity.toDouble(),
                            timestamp = System.currentTimeMillis()
                        ) ?: SensorData(
                            name = "bUwUdzik",
                            macAddress = targetMac,
                            temperature = temperature.toDouble(),
                            humidity = humidity.toDouble(),
                            battery = 0,
                            rssi = 0,
                            timestamp = System.currentTimeMillis()
                        )
                    }

                    clockController.onRssiUpdate = { rssi ->
                        _sensorData.value = _sensorData.value?.copy(rssi = rssi)
                    }

                    // Poll for RSSI
                    rssiPollJob?.cancel()
                    rssiPollJob = viewModelScope.launch {
                        while (true) {
                            clockController.readRssi()
                            delay(5000) // Poll every 5 seconds
                        }
                    }

                    if (reloadAlarms) {
                        Log.d("MainViewModel", "Clock connected, reading alarms and settings...")
                        launch {
                            try {
                                val alarms = clockController.readAlarms()
                                _alarms.value = alarms
                                Log.d("MainViewModel", "Loaded ${alarms.size} alarms")
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error loading alarms", e)
                            }

                            delay(200) // Small gap to avoid BLE race conditions

                            try {
                                val settings = clockController.readDeviceSettings()
                                _deviceSettings.value = settings
                                Log.d("MainViewModel", "Loaded device settings: $settings")
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error loading settings", e)
                            }

                            delay(200)

                            try {
                                val version = clockController.readFirmwareVersion()
                                _deviceSettings.value =
                                    _deviceSettings.value?.copy(firmwareVersion = version)
                                Log.d("MainViewModel", "Loaded firmware version: $version")
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error loading firmware version", e)
                            }
                        }
                    }
                } else {
                    Log.e("MainViewModel", "Failed to connect to clock")
                    startScanning() // Restart scanning if connection fails
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error connecting to clock", e)
                _clockConnected.value = false
                startScanning() // Restart scanning on error
            } finally {
                if (reloadAlarms) {
                    _clockConnecting.value = false
                }
            }
        }
    }

    fun reloadAlarms() {
        viewModelScope.launch {
            try {
                Log.d("MainViewModel", "Reloading alarms...")
                val alarms = clockController.readAlarms()
                _alarms.value = alarms
                Log.d("MainViewModel", "Reloaded ${alarms.size} alarms")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reloading alarms", e)
            }
        }
    }

    fun updateAlarm(alarm: Alarm, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                clockController.setAlarm(
                    hour = alarm.hour,
                    minute = alarm.minute,
                    alarmId = alarm.id,
                    enable = alarm.enabled,
                    days = alarm.days,
                    snooze = alarm.snooze
                )
                reloadAlarms()
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating alarm", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun deleteAlarm(alarmId: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                clockController.deleteAlarm(alarmId)
                reloadAlarms()
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error deleting alarm", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun updateDeviceSettings(settings: DeviceSettings, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                val currentVersion = _deviceSettings.value?.firmwareVersion ?: ""
                clockController.writeDeviceSettings(settings)
                _deviceSettings.value = settings.copy(firmwareVersion = currentVersion)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating settings", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun disconnectFromClock() {
        rssiPollJob?.cancel()
        rssiPollJob = null
        clockController.disconnect()
        _clockConnected.value = false
        Log.d("MainViewModel", "Disconnected from clock, restarting scan.")
        startScanning()
    }

}


class MainActivity : AppCompatActivity() {
    private lateinit var scanner: BluetoothScanner
    private lateinit var settingsRepository: SettingsRepository

    companion object {
        private const val TAG = "MainActivity"

        fun scheduleUpdates(context: Context, intervalMinutes: Long) {
            val workManager = WorkManager.getInstance(context)
            val alarmManager = context.getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, SensorUpdateReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Always cancel both first to ensure no duplicates
            workManager.cancelUniqueWork("SensorUpdateWork")
            alarmManager.cancel(pendingIntent)

            if (intervalMinutes < 15) {
                Log.d(TAG, "Scheduling AlarmManager for $intervalMinutes min intervals")
                val intervalMillis = intervalMinutes * 60 * 1000
                val triggerAt = System.currentTimeMillis() + intervalMillis
                // Use setRepeating for simplicity, though imprecise on modern Android.
                // For <15m updates, this is the standard "best effort" without FG service.
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP, triggerAt, intervalMillis, pendingIntent
                )
            } else {
                Log.d(TAG, "Scheduling WorkManager for $intervalMinutes min intervals")

                // Use flex time for battery optimization (run anytime within last 5 min of interval)
                val flexMinutes = minOf(5L, intervalMinutes / 3)

                val workRequest = PeriodicWorkRequestBuilder<SensorUpdateWorker>(
                    intervalMinutes, TimeUnit.MINUTES,
                    flexMinutes, TimeUnit.MINUTES
                )
                    .setInitialDelay(1, TimeUnit.MINUTES) // Small delay to avoid immediate trigger
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    "SensorUpdateWork", ExistingPeriodicWorkPolicy.UPDATE, workRequest
                )

                Log.d(TAG, "WorkManager scheduled with ${intervalMinutes}min interval, ${flexMinutes}min flex")
            }
        }
    }

    private fun clearCacheIfUpdated() {
        try {
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            val currentVersionCode = packageInfo.longVersionCode.toInt()

            if (settingsRepository.lastVersionCode != currentVersionCode) {
                Log.i(
                    "MainActivity",
                    "App updated from ${settingsRepository.lastVersionCode} to $currentVersionCode. Clearing cache..."
                )

                applicationContext.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                settingsRepository.lastVersionCode = currentVersionCode
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to check version or clear cache", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scanner = BluetoothScanner(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)

        clearCacheIfUpdated()

        // Apply Language
        val lang = settingsRepository.language
        val appLocale = if (lang == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)

        // Apply Theme
        AppCompatDelegate.setDefaultNightMode(ThemeUtils.themeToNightMode(settingsRepository.theme))

        // Schedule Worker or Alarm
        scheduleUpdates(applicationContext, settingsRepository.updateInterval)

        val viewModel: MainViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return MainViewModel(scanner, settingsRepository, applicationContext) as T
                }
            }
        }

        setContent {
            BuwudzikTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val startDestination =
                        if (settingsRepository.isSetupCompleted) "home" else "setup"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("setup") { DeviceSetupScreen(navController) }
                        composable("home") { HomeScreen(viewModel, navController) }
                        composable("settings") { SettingsScreen(navController, viewModel) }
                        composable("alarms") { AlarmManagementScreen(navController, viewModel) }
                        composable("device-settings") {
                            DeviceSettingsScreen(
                                navController,
                                viewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: MainViewModel, navController: NavController) {
    val context = LocalContext.current
    val sensorData by viewModel.sensorData.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()

    // Bluetooth Enable Launcher
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // We can check result.resultCode, but the receiver will update the state anyway
        Log.d("HomeScreen", "Bluetooth enable request result: ${result.resultCode}")
    }

    // Register Receiver
    DisposableEffect(context) {
        val receiver = BluetoothStateReceiver { enabled ->
            viewModel.updateBluetoothState(enabled)
        }
        val filter =
            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
        context.registerReceiver(receiver, filter)
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Permissions handling
    val permissionsToRequest = BluetoothUtils.BLUETOOTH_PERMISSIONS
    val permissionsRequiredMessage = stringResource(R.string.permissions_required)

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(), onResult = { perms ->
            val allGranted = perms.values.all { it }
            Log.d(
                "MainActivity", "Permissions result: $perms, All Granted: $allGranted"
            )
            if (allGranted) {
                viewModel.startScanning()
            } else {
                val deniedPerms = perms.filter { !it.value }.keys.joinToString(", ")
                val message = "$permissionsRequiredMessage\nMissing: $deniedPerms"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        })

    LaunchedEffect(Unit) {
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d("MainActivity", "Initial permission check. All granted: $allGranted")
        if (allGranted) {
            viewModel.startScanning()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    // Bluetooth Disabled Alert
    if (!isBluetoothEnabled) {
        AlertDialog(
            onDismissRequest = { /* Prevent dismissal to enforce requirement */ },
            title = { Text(stringResource(R.string.bluetooth_required_title)) },
            text = { Text(stringResource(R.string.bluetooth_required_desc)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val intent =
                            Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
                        enableBluetoothLauncher.launch(intent)
                    }
                ) {
                    Text(stringResource(R.string.turn_on_bluetooth))
                }
            },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { navController.navigate("settings") }) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings_desc)
                )
            }
        }
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            Dashboard(
                sensorData = sensorData,
                isBluetoothEnabled = isBluetoothEnabled,
                navController = navController,
                viewModel = viewModel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(padding)
            )
        }
    }
}

@Composable
fun Dashboard(
    sensorData: SensorData?,
    isBluetoothEnabled: Boolean,
    navController: NavController,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val clockConnected by viewModel.clockConnected.collectAsState()
    val clockConnecting by viewModel.clockConnecting.collectAsState()

    Column(
        modifier = modifier.padding(16.dp),
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

        if (sensorData == null) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.scanning_status))
        } else {
            if (!sensorData.name.isNullOrEmpty()) {
                Text(
                    text = sensorData.name,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Text(
                text = "${String.format(Locale.getDefault(), "%.1f", sensorData.temperature)}°C",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${String.format(Locale.getDefault(), "%.1f", sensorData.humidity)}%",
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
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            val timeString = dateFormat.format(Date(sensorData.timestamp))
            Text(
                text = stringResource(R.string.last_update_label, timeString),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (clockConnecting) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.connecting_to_clock),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (!clockConnected) {
                Button(
                    onClick = { viewModel.connectToClock() },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(stringResource(R.string.connect_to_clock))
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(0.9f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MenuTile(
                        title = stringResource(R.string.manage_alarms_label),
                        icon = Icons.Default.Alarm,
                        onClick = { navController.navigate("alarms") }
                    )
                    MenuTile(
                        title = stringResource(R.string.device_settings_button),
                        icon = Icons.Default.Settings,
                        onClick = { navController.navigate("device-settings") }
                    )
                    MenuTile(
                        title = stringResource(R.string.disconnect),
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        onClick = { viewModel.disconnectFromClock() },
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun MenuTile(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(20.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
