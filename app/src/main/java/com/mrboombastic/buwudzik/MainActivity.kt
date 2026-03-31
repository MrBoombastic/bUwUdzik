package com.mrboombastic.buwudzik


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.ui.components.CustomSnackbarHost
import com.mrboombastic.buwudzik.ui.screens.AlarmManagementScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceImportScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceListScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSettingsScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSetupScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSharingScreen
import com.mrboombastic.buwudzik.ui.screens.HomeScreen
import com.mrboombastic.buwudzik.ui.screens.RingtoneUploadScreen
import com.mrboombastic.buwudzik.ui.screens.SettingsScreen
import com.mrboombastic.buwudzik.ui.theme.BuwudzikTheme
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.NavigationAnimations
import com.mrboombastic.buwudzik.ui.utils.ThemeUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
import com.mrboombastic.buwudzik.widget.WidgetUpdateScheduler


class MainActivity : AppCompatActivity() {
    private lateinit var scanner: BluetoothScanner
    private lateinit var settingsRepository: SettingsRepository
    private var mainViewModel: MainViewModel? = null

    override fun onPause() {
        super.onPause()
        // Stop BLE scanning when app goes to background
        mainViewModel?.stopScanning()
    }

    override fun onResume() {
        super.onResume()
        // Resume BLE scan only when a device is configured and permissions are granted
        val vm = mainViewModel ?: return
        if (BluetoothUtils.hasBluetoothPermissions(this) &&
            vm.activeMac.isNotEmpty()
        ) {
            vm.startScanning()
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /**
         * Schedule periodic widget updates using AlarmManager.
         * This provides reliable updates even with aggressive battery optimization.
         * Call this when the app starts or when widgets are enabled.
         */
        fun scheduleUpdates(context: Context, intervalMinutes: Long) {
            AppLogger.d(TAG, "Scheduling AlarmManager for $intervalMinutes min intervals")
            WidgetUpdateScheduler.scheduleUpdates(
                context,
                intervalMinutes
            )
        }

        /**
         * Force reschedule updates with a new interval.
         * Call this when user changes the update interval in settings.
         */
        fun rescheduleUpdates(context: Context, intervalMinutes: Long) {
            AppLogger.d(TAG, "Rescheduling AlarmManager for $intervalMinutes min intervals")
            // Cancel existing alarms and schedule new one with updated interval
            WidgetUpdateScheduler.cancelUpdates(context)
            WidgetUpdateScheduler.scheduleUpdates(
                context,
                intervalMinutes
            )
        }
    }

    private fun clearCacheIfUpdated() {
        try {
            val packageInfo =
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            val currentVersionCode = packageInfo.longVersionCode.toInt()

            if (settingsRepository.lastVersionCode != currentVersionCode) {
                AppLogger.i(
                    "MainActivity",
                    "App updated from ${settingsRepository.lastVersionCode} to $currentVersionCode. Clearing cache..."
                )

                applicationContext.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                settingsRepository.lastVersionCode = currentVersionCode
            }
        } catch (e: Exception) {
            AppLogger.e("MainActivity", "Failed to check version or clear cache", e)
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
        if (BluetoothUtils.hasBluetoothPermissions(applicationContext)) {
            scheduleUpdates(applicationContext, settingsRepository.updateInterval)
        }

        val deviceProfileRepository = DeviceProfileRepository(applicationContext)

        val viewModel: MainViewModel by viewModels {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST") return MainViewModel(
                        scanner, settingsRepository, deviceProfileRepository, applicationContext
                    ) as T
                }
            }
        }
        mainViewModel = viewModel

        setContent {
            BuwudzikTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    LocalContext.current
                    val resources = LocalResources.current
                    val deviceProfileRepo = DeviceProfileRepository(applicationContext)
                    val startDestination =
                        if (deviceProfileRepo.getActiveDeviceId() != null) "home" else "setup"
                    var startupUpdateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
                    var showStartupUpdateDialog by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()

                    // Handle disconnection events
                    val disconnectionEvent by viewModel.disconnectionEvent.collectAsState()
                    val snackbarHostState = remember { SnackbarHostState() }

                    // Register Receiver Globally
                    val context = LocalContext.current
                    DisposableEffect(context) {
                        val receiver = BluetoothStateReceiver { enabled ->
                            viewModel.updateBluetoothState(enabled)
                        }
                        val filter =
                            android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                        ContextCompat.registerReceiver(
                            context,
                            receiver,
                            filter,
                            ContextCompat.RECEIVER_NOT_EXPORTED
                        )
                        onDispose {
                            context.unregisterReceiver(receiver)
                        }
                    }

                    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
                    val enableBluetoothLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { }

                    if (!isBluetoothEnabled) {
                        AlertDialog(
                            onDismissRequest = { },
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


                    LaunchedEffect(disconnectionEvent) {
                        disconnectionEvent?.let { reason ->
                            // Reset connection state FIRST
                            viewModel.handleUnexpectedDisconnect()

                            // Navigate to home screen
                            navController.navigate("home") {
                                popUpTo("home") { inclusive = true }
                            }

                            val reasonMessage = reason.getMessage(resources)
                            val reasonHint = reason.getHint(resources)

                            val fullMessage = if (reasonHint != null) {
                                "$reasonMessage $reasonHint"
                            } else {
                                reasonMessage
                            }

                            // Show snackbar with reason
                            snackbarHostState.showSnackbar(
                                message = fullMessage,
                                duration = SnackbarDuration.Long
                            )
                            // Clear the event
                            viewModel.clearDisconnectionEvent()
                        }
                    }

                    // Handle connection errors (diagnostic hints)
                    val connectionError by viewModel.connectionError.collectAsState()
                    val okText = stringResource(android.R.string.ok)
                    LaunchedEffect(connectionError) {
                        connectionError?.let { error ->
                            // Avoid showing generic "Disconnected" message if we already have a specific DisconnectionEvent
                            val isGenericDisconnect =
                                error.trim() == "Disconnected" || error.startsWith("Disconnected (status")
                            if (!isGenericDisconnect) {
                                snackbarHostState.showSnackbar(
                                    message = error,
                                    duration = SnackbarDuration.Long,
                                    actionLabel = okText
                                )
                            }
                            viewModel.clearConnectionError()
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (!settingsRepository.autoUpdateCheckEnabled) return@LaunchedEffect
                        val now = System.currentTimeMillis()
                        if (now - settingsRepository.lastAutoUpdateCheckMs < AUTO_UPDATE_CHECK_INTERVAL_MS) {
                            return@LaunchedEffect
                        }
                        // Enforce at-most-once-per-day attempts even if request fails.
                        settingsRepository.lastAutoUpdateCheckMs = now
                        try {
                            val updateChecker = UpdateChecker(applicationContext)
                            val result = try {
                                updateChecker.checkForUpdates()
                            } finally {
                                updateChecker.close()
                            }
                            if (result.updateAvailable) {
                                startupUpdateResult = result
                                showStartupUpdateDialog = true
                            }
                        } catch (e: Exception) {
                            AppLogger.w(TAG, "Automatic update check failed: ${e.message}", e)
                        }
                    }

                    if (showStartupUpdateDialog && startupUpdateResult != null) {
                        AlertDialog(
                            onDismissRequest = { showStartupUpdateDialog = false },
                            title = { Text(stringResource(R.string.update_available_title)) },
                            text = {
                                val update = startupUpdateResult!!
                                Text(
                                    stringResource(
                                        R.string.update_available_message,
                                        update.currentVersion,
                                        update.latestVersion
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        showStartupUpdateDialog = false
                                        val downloadUrl = startupUpdateResult?.downloadUrl ?: return@TextButton
                                        scope.launch {
                                            val updateChecker = UpdateChecker(applicationContext)
                                            updateChecker.downloadAndInstall(downloadUrl)
                                            updateChecker.close()
                                        }
                                    }
                                ) {
                                    Text(stringResource(R.string.download_update))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showStartupUpdateDialog = false }) {
                                    Text(stringResource(R.string.later))
                                }
                            },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) }
                        )
                    }

                    @SuppressLint("UnusedMaterial3ScaffoldPaddingParameter") Scaffold(
                        snackbarHost = { CustomSnackbarHost(snackbarHostState) }) { _ ->
                        NavHost(
                            navController = navController,
                            startDestination = startDestination,
                            enterTransition = NavigationAnimations.enterTransition(),
                            exitTransition = NavigationAnimations.exitTransition(),
                            popEnterTransition = NavigationAnimations.popEnterTransition(),
                            popExitTransition = NavigationAnimations.popExitTransition()
                        ) {
                            composable("setup") {
                                DeviceSetupScreen(
                                    navController,
                                    mode = "setup",
                                    viewModel = viewModel
                                )
                            }
                            composable("home") { HomeScreen(viewModel, navController) }
                            composable("settings") {
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
                                DeviceSettingsScreen(navController, viewModel)
                            }
                            composable("ringtone-upload") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                RingtoneUploadScreen(navController, viewModel)
                            }
                            composable("device-sharing") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceSharingScreen(
                                    navController = navController,
                                    viewModel = viewModel,
                                    preselectedMac = null
                                )
                            }
                            composable("device-sharing/{mac}") { backStackEntry ->
                                val mac = backStackEntry.arguments?.getString("mac")
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceSharingScreen(
                                    navController = navController,
                                    viewModel = viewModel,
                                    preselectedMac = mac
                                )
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
                            composable("devices") {
                                BackHandler {
                                    if (!navController.popBackStack()) {
                                        navController.navigate("home") {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                }
                                DeviceListScreen(navController, viewModel)
                            }
                            composable("device-add") {
                                DeviceSetupScreen(
                                    navController = navController,
                                    mode = "add",
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
