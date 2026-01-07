package com.mrboombastic.buwudzik


import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.Alarm
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.device.DeviceSettings
import com.mrboombastic.buwudzik.device.QPController
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.screens.AlarmManagementScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceImportScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSettingsScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSetupScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSharingScreen
import com.mrboombastic.buwudzik.ui.screens.RingtoneUploadScreen
import com.mrboombastic.buwudzik.ui.screens.SettingsScreen
import com.mrboombastic.buwudzik.ui.theme.BuwudzikTheme
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.ThemeUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.widget.SensorUpdateReceiver
import com.mrboombastic.buwudzik.widget.SensorUpdateWorker
import kotlinx.coroutines.Dispatchers
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
    private val alarmTitleRepository = AlarmTitleRepository(applicationContext)

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private val _deviceConnected = MutableStateFlow(false)
    val deviceConnected: StateFlow<Boolean> = _deviceConnected.asStateFlow()

    private val _deviceConnecting = MutableStateFlow(false)
    val deviceConnecting: StateFlow<Boolean> = _deviceConnecting.asStateFlow()

    private val _alarms = MutableStateFlow<List<Alarm>>(emptyList())
    val alarms: StateFlow<List<Alarm>> = _alarms.asStateFlow()

    private val _deviceSettings = MutableStateFlow<DeviceSettings?>(null)
    val deviceSettings: StateFlow<DeviceSettings?> = _deviceSettings.asStateFlow()

    private val _isBluetoothEnabled = MutableStateFlow(false)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isPaired = MutableStateFlow(false)
    val isPaired: StateFlow<Boolean> = _isPaired.asStateFlow()

    val qpController = QPController(applicationContext)

    // Expose disconnection event from controller
    val disconnectionEvent = qpController.disconnectionEvent

    fun clearDisconnectionEvent() {
        qpController.clearDisconnectionEvent()
    }

    /**
     * Check pairing status and update state
     */
    fun checkPairingStatus() {
        val mac = settingsRepository.targetMacAddress
        _isPaired.value = if (mac.isNotEmpty()) qpController.isDevicePaired(mac) else false
    }

    /**
     * Remove pairing (stored token) for the current target device
     */
    fun unpairDevice() {
        val mac = settingsRepository.targetMacAddress
        if (mac.isNotEmpty()) {
            qpController.unpairDevice(mac)
            checkPairingStatus()
        }
    }

    /**
     * Handle unexpected device disconnection - reset state but don't manually disconnect
     */
    fun handleUnexpectedDisconnect() {
        rssiPollJob?.cancel()
        rssiPollJob = null
        _deviceConnected.value = false
        _deviceConnecting.value = false
        AppLogger.d("MainViewModel", "Handled unexpected disconnect, starting scan")
        startScanning()
    }

    private var scanJob: Job? = null
    private var rssiPollJob: Job? = null
    private var connectionJob: Job? = null

    init {
        _isBluetoothEnabled.value = BluetoothUtils.isBluetoothEnabled(applicationContext)
        checkPairingStatus()
    }

    fun updateBluetoothState(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (enabled) {
            startScanning()
        } else {
            // scanJob automatically cancels or fails, but good to be explicit
            scanJob?.cancel()
            _deviceConnected.value = false
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) {
            AppLogger.d("MainViewModel", "Scan already active, ignoring start request.")
            return
        }

        val targetMac = settingsRepository.targetMacAddress
        val scanMode = settingsRepository.scanMode
        AppLogger.d("MainViewModel", "Starting scanning flow for $targetMac with mode $scanMode...")
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanner.scan(targetMac, scanMode).collect { data ->
                AppLogger.d("MainViewModel", "Received data: $data")
                val correctedData = sensorRepository.saveSensorData(data)
                _sensorData.value = correctedData
            }
        }
    }

    fun restartScanning() {
        AppLogger.d("MainViewModel", "Restarting scan...")
        scanJob?.cancel()
        scanJob = null
        _sensorData.value = null
        startScanning()
    }

    fun connectToDevice(reloadAlarms: Boolean = true) {
        if (_deviceConnecting.value || _deviceConnected.value) return

        val targetMac = settingsRepository.targetMacAddress
        if (targetMac.isEmpty()) {
            Log.e("MainViewModel", "No target MAC address configured")
            return
        }

        _deviceConnecting.value = true
        // Stop scanning before connecting
        scanJob?.cancel()

        // Cancel any previous connection attempt
        connectionJob?.cancel()

        connectionJob = viewModelScope.launch {
            try {
                // Get BluetoothDevice
                val bluetoothManager =
                    applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val device = adapter.getRemoteDevice(targetMac)

                val success = qpController.connectAndAuthenticate(device)
                if (success) {
                    _deviceConnected.value = true
                    checkPairingStatus()

                    // Setup real-time updates
                    qpController.onSensorData = { temperature, humidity ->
                        val currentData = _sensorData.value
                        val targetMac = settingsRepository.targetMacAddress

                        _sensorData.value = currentData?.copy(
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

                    qpController.onRssiUpdate = { rssi ->
                        _sensorData.value = _sensorData.value?.copy(rssi = rssi)
                    }

                    // Poll for RSSI
                    rssiPollJob?.cancel()
                    rssiPollJob = viewModelScope.launch {
                        while (true) {
                            qpController.readRssi()
                            delay(5000) // Poll every 5 seconds
                        }
                    }

                    if (reloadAlarms) {
                        AppLogger.d(
                            "MainViewModel", "Clock connected, reading alarms and settings..."
                        )
                        launch {
                            try {
                                val deviceAlarms = qpController.readAlarms()
                                val alarmsWithTitles = deviceAlarms.map { alarm ->
                                    alarm.copy(title = alarmTitleRepository.getTitle(alarm.id))
                                }
                                _alarms.value = alarmsWithTitles
                                AppLogger.d(
                                    "MainViewModel", "Loaded ${alarmsWithTitles.size} alarms"
                                )
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error loading alarms", e)
                            }

                            delay(200) // Small gap to avoid BLE race conditions

                            try {
                                val settings = qpController.readDeviceSettings()
                                _deviceSettings.value = settings
                                AppLogger.d("MainViewModel", "Loaded device settings: $settings")
                            } catch (e: Exception) {
                                Log.e("MainViewModel", "Error loading settings", e)
                            }

                            delay(200)

                            try {
                                val version = qpController.readFirmwareVersion()
                                _deviceSettings.value =
                                    _deviceSettings.value?.copy(firmwareVersion = version)
                                AppLogger.d("MainViewModel", "Loaded firmware version: $version")
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
                _deviceConnected.value = false
                startScanning() // Restart scanning on error
            } finally {
                if (reloadAlarms) {
                    _deviceConnecting.value = false
                }
            }
        }
    }

    fun reloadAlarms() {
        viewModelScope.launch {
            try {
                AppLogger.d("MainViewModel", "Reloading alarms...")
                val deviceAlarms = qpController.readAlarms()
                val alarmsWithTitles = deviceAlarms.map { alarm ->
                    alarm.copy(title = alarmTitleRepository.getTitle(alarm.id))
                }
                _alarms.value = alarmsWithTitles
                AppLogger.d("MainViewModel", "Reloaded ${alarmsWithTitles.size} alarms")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reloading alarms", e)
            }
        }
    }

    fun updateAlarm(alarm: Alarm, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                qpController.setAlarm(
                    hour = alarm.hour,
                    minute = alarm.minute,
                    alarmId = alarm.id,
                    enable = alarm.enabled,
                    days = alarm.days,
                    snooze = alarm.snooze
                )
                // Save title locally
                alarmTitleRepository.setTitle(alarm.id, alarm.title)
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
                qpController.deleteAlarm(alarmId)
                // Delete title locally
                alarmTitleRepository.deleteTitle(alarmId)
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
                qpController.writeDeviceSettings(settings)
                _deviceSettings.value = settings.copy(firmwareVersion = currentVersion)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error updating settings", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun reloadDeviceSettings() {
        viewModelScope.launch {
            try {
                AppLogger.d("MainViewModel", "Reloading device settings...")
                val settings = qpController.readDeviceSettings()
                val currentVersion = _deviceSettings.value?.firmwareVersion ?: ""
                _deviceSettings.value = settings.copy(firmwareVersion = currentVersion)
                AppLogger.d("MainViewModel", "Reloaded device settings")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error reloading device settings", e)
            }
        }
    }

    fun disconnectFromDevice() {
        rssiPollJob?.cancel()
        rssiPollJob = null
        connectionJob?.cancel()
        connectionJob = null
        qpController.disconnect()
        _deviceConnected.value = false
        AppLogger.d("MainViewModel", "Disconnected from clock, restarting scan.")
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
                AppLogger.d(TAG, "Scheduling AlarmManager for $intervalMinutes min intervals")
                val intervalMillis = intervalMinutes * 60 * 1000
                val triggerAt = System.currentTimeMillis() + intervalMillis
                // Use setRepeating for simplicity, though imprecise on modern Android.
                // For <15m updates, this is the standard "best effort" without FG service.
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP, triggerAt, intervalMillis, pendingIntent
                )
            } else {
                AppLogger.d(TAG, "Scheduling WorkManager for $intervalMinutes min intervals")

                // Use flex time for battery optimization (run anytime within last 5 min of interval)
                val flexMinutes = minOf(5L, intervalMinutes / 3)

                val workRequest = PeriodicWorkRequestBuilder<SensorUpdateWorker>(
                    intervalMinutes, TimeUnit.MINUTES, flexMinutes, TimeUnit.MINUTES
                ).setInitialDelay(1, TimeUnit.MINUTES) // Small delay to avoid immediate trigger
                    .build()

                workManager.enqueueUniquePeriodicWork(
                    "SensorUpdateWork", ExistingPeriodicWorkPolicy.UPDATE, workRequest
                )

                AppLogger.d(
                    TAG,
                    "WorkManager scheduled with ${intervalMinutes}min interval, ${flexMinutes}min flex"
                )
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
                    @Suppress("UNCHECKED_CAST") return MainViewModel(
                        scanner, settingsRepository, applicationContext
                    ) as T
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

                    // Handle disconnection events
                    val disconnectionEvent by viewModel.disconnectionEvent.collectAsState()
                    val snackbarHostState = remember { SnackbarHostState() }
                    val disconnectedMessage = stringResource(R.string.device_disconnected)

                    LaunchedEffect(disconnectionEvent) {
                        disconnectionEvent?.let { reason ->
                            // Reset connection state FIRST
                            viewModel.handleUnexpectedDisconnect()

                            // Navigate to home screen
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }
                            // Show snackbar with reason
                            snackbarHostState.showSnackbar(
                                message = "$disconnectedMessage: ${reason.message}",
                                duration = SnackbarDuration.Long
                            )
                            // Clear the event
                            viewModel.clearDisconnectionEvent()
                        }
                    }

                    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter") Scaffold(
                        snackbarHost = { SnackbarHost(snackbarHostState) }) { _ ->
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            enterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            exitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> -fullWidth / 4 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(150))
                            },
                            popEnterTransition = {
                                slideInHorizontally(
                                    initialOffsetX = { fullWidth -> -fullWidth / 4 },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeIn(animationSpec = tween(300))
                            },
                            popExitTransition = {
                                slideOutHorizontally(
                                    targetOffsetX = { fullWidth -> fullWidth },
                                    animationSpec = tween(300, easing = FastOutSlowInEasing)
                                ) + fadeOut(animationSpec = tween(150))
                            }) {
                            composable("setup") { DeviceSetupScreen(navController) }
                            composable("home") { HomeScreen(viewModel, navController) }
                            composable("settings") {
                                // Handle system back to properly navigate back or to home
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                SettingsScreen(navController, viewModel)
                            }
                            composable("alarms") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                AlarmManagementScreen(navController, viewModel)
                            }
                            composable("device-settings") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceSettingsScreen(
                                    navController, viewModel
                                )
                            }
                            composable("ringtone-upload") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                RingtoneUploadScreen(
                                    navController, viewModel
                                )
                            }
                            composable("device-sharing") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceSharingScreen(navController)
                            }
                            composable("device-import") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceImportScreen(navController, viewModel)
                            }
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
        AppLogger.d("HomeScreen", "Bluetooth enable request result: ${result.resultCode}")
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
            AppLogger.d(
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
        AppLogger.d("MainActivity", "Initial permission check. All granted: $allGranted")
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
                    }) {
                    Text(stringResource(R.string.turn_on_bluetooth))
                }
            },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) })
    }

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
            horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()
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
    val deviceConnected by viewModel.deviceConnected.collectAsState()
    val deviceConnecting by viewModel.deviceConnecting.collectAsState()
    val isPaired by viewModel.isPaired.collectAsState()
    var showUnpairDialog by remember { mutableStateOf(false) }

    // Unpair confirmation dialog
    @Suppress("AssignedValueIsNeverRead") if (showUnpairDialog) {
        AlertDialog(
            onDismissRequest = { showUnpairDialog = false },
            title = { Text(stringResource(R.string.unpair_confirm_title)) },
            text = { Text(stringResource(R.string.unpair_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnpairDialog = false
                        viewModel.disconnectFromDevice()
                        viewModel.unpairDevice()
                    }, colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.unpair_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnpairDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            })
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
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

            if (deviceConnecting) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.connecting_to_device),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (!deviceConnected) {
                if (!isPaired) {
                    ElevatedCard(
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        ),
                        modifier = Modifier
                            .fillMaxWidth(0.92f)
                            .padding(bottom = 24.dp),
                        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            // Header with icon
                            Icon(
                                Icons.Default.PhonelinkSetup,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                stringResource(R.string.setup_new_device),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.pairing_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(24.dp))

                            // Step 1
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "1",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.pairing_step1_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.pairing_step1_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Step 2
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "2",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.pairing_step2_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.pairing_step2_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Step 3
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            "3",
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        stringResource(R.string.pairing_step3_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        stringResource(R.string.pairing_step3_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { viewModel.connectToDevice() },
                    modifier = Modifier.fillMaxWidth(0.8f)
                ) {
                    Text(
                        if (isPaired) stringResource(R.string.connect_to_device) else stringResource(
                            R.string.pair_and_connect
                        )
                    )
                }

                if (isPaired) {
                    TextButton(
                        onClick = { navController.navigate("device-sharing") },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.share_device_button))
                    }
                }

                if (isPaired) {
                    @Suppress("AssignedValueIsNeverRead") TextButton(
                        onClick = { showUnpairDialog = true },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.unpair_device))
                    }
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

                    if (isPaired) {
                        @Suppress("AssignedValueIsNeverRead") MenuTile(
                            title = stringResource(R.string.unpair_device),
                            icon = Icons.Default.Warning,
                            onClick = { showUnpairDialog = true },
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    }
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
            containerColor = containerColor, contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon, contentDescription = null, modifier = Modifier.size(28.dp)
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






