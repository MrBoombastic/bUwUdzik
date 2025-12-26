package com.mrboombastic.buwudzik

import android.Manifest
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainViewModel(
    private val scanner: BluetoothScanner, private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _sensorData = MutableStateFlow<SensorData?>(null)
    val sensorData: StateFlow<SensorData?> = _sensorData.asStateFlow()

    private var scanJob: Job? = null

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

}

class MainViewModelFactory(
    private val scanner: BluetoothScanner, private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST") return MainViewModel(scanner, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class MainActivity : AppCompatActivity() {
    private lateinit var scanner: BluetoothScanner
    private lateinit var settingsRepository: SettingsRepository

    companion object {
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
                Log.d("MainActivity", "Scheduling Alarm for $intervalMinutes min")
                val intervalMillis = intervalMinutes * 60 * 1000
                val triggerAt = System.currentTimeMillis() + intervalMillis
                // Use setRepeating for simplicity, though imprecise on modern Android.
                // For <15m updates, this is the standard "best effort" without FG service.
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP, triggerAt, intervalMillis, pendingIntent
                )
            } else {
                Log.d("MainActivity", "Scheduling WorkManager for $intervalMinutes min")
                val workRequest = PeriodicWorkRequestBuilder<SensorUpdateWorker>(
                    intervalMinutes, TimeUnit.MINUTES
                ).build()

                workManager.enqueueUniquePeriodicWork(
                    "SensorUpdateWork", ExistingPeriodicWorkPolicy.UPDATE, workRequest
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        scanner = BluetoothScanner(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)

        // Apply Language
        val lang = settingsRepository.language
        val appLocale = if (lang == "system") {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(lang)
        }
        AppCompatDelegate.setApplicationLocales(appLocale)

        // Apply Theme
        val themeMode = when (settingsRepository.theme) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(themeMode)

        // Schedule Worker or Alarm
        scheduleUpdates(applicationContext, settingsRepository.updateInterval)

        val viewModel: MainViewModel by viewModels {
            MainViewModelFactory(scanner, settingsRepository)
        }

        setContent {
            BuwudzikTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val startDestination = if (settingsRepository.isSetupCompleted) "home" else "setup"

                    NavHost(navController = navController, startDestination = startDestination) {
                        composable("setup") { DeviceSetupScreen(navController) }
                        composable("home") { HomeScreen(viewModel, navController) }
                        composable("settings") { SettingsScreen(navController, viewModel) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, navController: NavController) {
    val context = LocalContext.current
    val sensorData by viewModel.sensorData.collectAsState()

    // Check Bluetooth status
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val isBluetoothEnabled = bluetoothManager?.adapter?.isEnabled == true

    // Permissions handling
    val permissionsToRequest = remember {
        val perms = mutableListOf<String>()
        perms.add(Manifest.permission.BLUETOOTH_SCAN)
        perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.toTypedArray()
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(), onResult = { perms ->
            val allGranted = perms.values.all { it }
            Log.d(
                "MainActivity", "Permissions result: $perms, All Granted: $allGranted"
            )
            if (allGranted) {
                viewModel.startScanning()
            } else {
                Toast.makeText(
                    context, R.string.permissions_required, Toast.LENGTH_LONG
                ).show()
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
            launcher.launch(permissionsToRequest)
        }
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
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(padding)
            )
        }
    }
}

@Composable
fun Dashboard(sensorData: SensorData?, isBluetoothEnabled: Boolean, modifier: Modifier = Modifier) {
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
                text = "${sensorData.temperature}°C",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "${sensorData.humidity}%",
                fontSize = 48.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stringResource(R.string.battery_label, sensorData.battery), fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            val signalPercentage = when {
                sensorData.rssi >= -35 -> 100
                sensorData.rssi <= -100 -> 0
                else -> ((sensorData.rssi + 100) * 100) / 65
            }
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
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DashboardPreview() {
    BuwudzikTheme {
        Dashboard(
            sensorData = SensorData(
                temperature = 23.5,
                humidity = 45.2,
                battery = 88,
                rssi = -55,
                name = "Qingping Alarm Clock",
                macAddress = "21:37:13:37:04:20",
                timestamp = System.currentTimeMillis()
            ),
            isBluetoothEnabled = true,
            modifier = Modifier.fillMaxSize()
        )
    }
}
