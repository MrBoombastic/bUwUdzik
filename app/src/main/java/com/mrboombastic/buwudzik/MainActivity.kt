package com.mrboombastic.buwudzik


import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DevicesOther
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.components.CustomSnackbarHost
import com.mrboombastic.buwudzik.ui.components.InstructionCard
import com.mrboombastic.buwudzik.ui.components.MenuTile
import com.mrboombastic.buwudzik.ui.components.NumberedStep
import com.mrboombastic.buwudzik.ui.components.SmallButton
import com.mrboombastic.buwudzik.ui.screens.AlarmManagementScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceImportScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceListScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSettingsScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSetupScreen
import com.mrboombastic.buwudzik.ui.screens.DeviceSharingScreen
import com.mrboombastic.buwudzik.ui.screens.RingtoneUploadScreen
import com.mrboombastic.buwudzik.ui.screens.SettingsScreen
import com.mrboombastic.buwudzik.ui.theme.BuwudzikTheme
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.ui.utils.NavigationAnimations
import com.mrboombastic.buwudzik.ui.utils.ThemeUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
import com.mrboombastic.buwudzik.widget.WidgetUpdateScheduler
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


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
            vm.deviceProfileRepository.getActiveDeviceId() != null
        ) {
            vm.startScanning()
        }
    }

    companion object {
        private const val TAG = "MainActivity"

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: MainViewModel, navController: NavController) {
    val context = LocalContext.current
    val sensorData by viewModel.sensorData.collectAsState()
    val isBluetoothEnabled by viewModel.isBluetoothEnabled.collectAsState()
    val activeDevice by viewModel.activeDevice.collectAsState()
    val devices by viewModel.devices.collectAsState()
    var showDeviceSheetSwipeHint by remember {
        mutableStateOf(!viewModel.deviceSheetSwipeHintAlreadyShown())
    }
    var deviceSwitcherOpen by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    val dashboardScroll = rememberScrollState()
    val homeOverscrollAccum = remember { mutableFloatStateOf(0f) }
    val densitySheetOverscroll = LocalDensity.current
    val sheetOverscrollThresholdPx = remember(densitySheetOverscroll) {
        with(densitySheetOverscroll) { 56.dp.toPx() }
    }
    val openDeviceSheetLatest = rememberUpdatedState(
        newValue = { deviceSwitcherOpen = true }
    )
    val dashboardOpenSheetNested = remember(
        activeDevice,
        sheetOverscrollThresholdPx,
        dashboardScroll
    ) {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset {
                if (activeDevice == null) {
                    homeOverscrollAccum.floatValue = 0f
                    return Offset.Zero
                }
                if (dashboardScroll.maxValue <= 0) {
                    homeOverscrollAccum.floatValue = 0f
                    return Offset.Zero
                }
                if (dashboardScroll.canScrollForward) {
                    homeOverscrollAccum.floatValue = 0f
                    return Offset.Zero
                }
                if (available.y < 0f) {
                    homeOverscrollAccum.floatValue += -available.y
                    if (homeOverscrollAccum.floatValue >= sheetOverscrollThresholdPx) {
                        homeOverscrollAccum.floatValue = 0f
                        openDeviceSheetLatest.value.invoke()
                    }
                }
                return Offset.Zero
            }
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
                if (viewModel.deviceProfileRepository.getActiveDeviceId() != null) {
                    viewModel.startScanning()
                }
            } else {
                val deniedPerms = perms.filter { !it.value }.keys.joinToString(", ")
                val message = "$permissionsRequiredMessage\nMissing: $deniedPerms"
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        })

    LaunchedEffect(Unit) {
        val allGranted = permissionsToRequest.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        AppLogger.d("MainActivity", "Initial permission check. All granted: $allGranted")
        if (!allGranted) {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    LaunchedEffect(activeDevice) {
        val allGranted = permissionsToRequest.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted && activeDevice != null) {
            viewModel.startScanning()
        }
    }


    LaunchedEffect(deviceSwitcherOpen) {
        if (deviceSwitcherOpen) {
            sheetState.partialExpand()
            if (showDeviceSheetSwipeHint) {
                viewModel.markDeviceSheetSwipeHintSeen()
                showDeviceSheetSwipeHint = false
            }
        }
    }

    BackHandler(enabled = deviceSwitcherOpen) {
        scope.launch {
            sheetState.hide()
            deviceSwitcherOpen = false
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                if (activeDevice != null) {
                    AssistChip(
                        onClick = { deviceSwitcherOpen = true },
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.active_device_label,
                                    activeDevice!!.alias
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DevicesOther,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.padding(
                            top = padding.calculateTopPadding(),
                            bottom = 4.dp
                        )
                    )
                }

                if (activeDevice != null && showDeviceSheetSwipeHint) {
                    val dismissSwipeHint = {
                        viewModel.markDeviceSheetSwipeHintSeen()
                        showDeviceSheetSwipeHint = false
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        tonalElevation = 2.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.device_sheet_swipe_hint_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.device_sheet_swipe_hint_body),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            TextButton(onClick = dismissSwipeHint) {
                                Text(stringResource(R.string.device_sheet_swipe_hint_got_it))
                            }
                        }
                    }
                }

                Dashboard(
                    sensorData = sensorData,
                    isBluetoothEnabled = isBluetoothEnabled,
                    hasActiveDevice = activeDevice != null,
                    navController = navController,
                    viewModel = viewModel,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .let { if (activeDevice != null) it else it.padding(padding) },
                    scrollState = dashboardScroll,
                    openSheetNestedScroll = dashboardOpenSheetNested
                )
            }
        }

        if (activeDevice != null) {
            val densityForSwipe = LocalDensity.current
            val homeSwipeOpenPx = remember(densityForSwipe) {
                with(densityForSwipe) { 56.dp.toPx() }
            }
            var homeSwipeAccum by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(0.55f)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = 8.dp)
                    .height(88.dp)
                    .pointerInput(homeSwipeOpenPx) {
                        detectVerticalDragGestures(
                            onDragStart = { homeSwipeAccum = 0f },
                            onVerticalDrag = { _, dragAmount ->
                                homeSwipeAccum += dragAmount
                                if (homeSwipeAccum <= -homeSwipeOpenPx) {
                                    homeSwipeAccum = 0f
                                    deviceSwitcherOpen = true
                                }
                            }
                        )
                    }
            )
        }

        if (deviceSwitcherOpen) {
            ModalBottomSheet(
                onDismissRequest = { deviceSwitcherOpen = false },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 2.dp,
                scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f),
                dragHandle = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BottomSheetDefaults.DragHandle(
                            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                        )
                    }
                }
            ) {
                val density = LocalDensity.current
                val swipeUpExpandPx = remember(density) { with(density) { 56.dp.toPx() } }
                var sheetDragAccum by remember { mutableFloatStateOf(0f) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .pointerInput(sheetState.currentValue) {
                                detectVerticalDragGestures(
                                    onDragStart = { sheetDragAccum = 0f },
                                    onVerticalDrag = { _, dragAmount ->
                                        sheetDragAccum += dragAmount
                                        if (sheetState.currentValue == SheetValue.PartiallyExpanded &&
                                            sheetDragAccum <= -swipeUpExpandPx
                                        ) {
                                            sheetDragAccum = 0f
                                            scope.launch { sheetState.expand() }
                                        }
                                    }
                                )
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.DevicesOther,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(26.dp)
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.switch_device_sheet_title),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .clickable {
                                    scope.launch {
                                        sheetState.hide()
                                        deviceSwitcherOpen = false
                                        navController.navigate("devices")
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.manage_devices_label),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(devices.sortedBy { it.addedAt }, key = { it.mac }) { profile ->
                            val isActive = profile.mac == activeDevice?.mac
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                tonalElevation = if (isActive) 2.dp else 0.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (!isActive) {
                                            viewModel.setActiveDevice(profile.mac)
                                        }
                                        scope.launch {
                                            sheetState.hide()
                                            deviceSwitcherOpen = false
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    if (isActive) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.size(24.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = profile.alias,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        )
                                        Text(
                                            text = profile.mac,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer.copy(
                                                    alpha = 0.75f
                                                )
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ShareAndUnpairButtons(
    navController: NavController,
    onUnpairClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SmallButton(
            title = stringResource(R.string.share_device_button),
            icon = Icons.Default.Share,
            onClick = { navController.navigate("device-sharing") },
            modifier = Modifier.weight(1f)
        )

        SmallButton(
            title = stringResource(R.string.unpair_device),
            icon = Icons.Default.Delete,
            onClick = onUnpairClick,
            contentColor = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun Dashboard(
    sensorData: SensorData?,
    isBluetoothEnabled: Boolean,
    hasActiveDevice: Boolean,
    navController: NavController,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    scrollState: ScrollState? = null,
    openSheetNestedScroll: NestedScrollConnection? = null
) {
    val scroll = scrollState ?: rememberScrollState()
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
            .let { m ->
                if (openSheetNestedScroll != null) m.nestedScroll(openSheetNestedScroll)
                else m
            }
            .verticalScroll(scroll),
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

        if (sensorData == null && !hasActiveDevice) {
            Text(
                text = stringResource(R.string.no_devices_message),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.tap_plus_to_add),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            MenuTile(
                title = stringResource(R.string.manage_devices_label),
                icon = Icons.Default.DevicesOther,
                onClick = { navController.navigate("devices") },
                modifier = Modifier.fillMaxWidth(0.9f),
                arrangementH = Arrangement.Center
            )
        } else if (sensorData == null && !isPaired) {
            // Forgot device or no token: show re-pair flow instead of passive-scan spinner only
            if (deviceConnecting) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.connecting_to_device),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                InstructionCard(
                    icon = Icons.Default.PhonelinkSetup,
                    title = stringResource(R.string.setup_new_device),
                    subtitle = stringResource(R.string.pairing_subtitle),
                    modifier = Modifier.padding(bottom = 24.dp)
                ) {
                    NumberedStep(
                        number = "1",
                        title = stringResource(R.string.pairing_step1_title),
                        description = stringResource(R.string.pairing_step1_desc)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedStep(
                        number = "2",
                        title = stringResource(R.string.pairing_step2_title),
                        description = stringResource(R.string.pairing_step2_desc)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    NumberedStep(
                        number = "3",
                        title = stringResource(R.string.pairing_step3_title),
                        description = stringResource(R.string.pairing_step3_desc)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                MenuTile(
                    title = stringResource(R.string.pair_and_connect),
                    icon = Icons.Default.PhonelinkSetup,
                    onClick = { viewModel.connectToDevice() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    arrangementH = Arrangement.Center
                )
            }
        } else if (sensorData == null) {
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
                    InstructionCard(
                        icon = Icons.Default.PhonelinkSetup,
                        title = stringResource(R.string.setup_new_device),
                        subtitle = stringResource(R.string.pairing_subtitle),
                        modifier = Modifier.padding(bottom = 24.dp)
                    ) {
                        NumberedStep(
                            number = "1",
                            title = stringResource(R.string.pairing_step1_title),
                            description = stringResource(R.string.pairing_step1_desc)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        NumberedStep(
                            number = "2",
                            title = stringResource(R.string.pairing_step2_title),
                            description = stringResource(R.string.pairing_step2_desc)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        NumberedStep(
                            number = "3",
                            title = stringResource(R.string.pairing_step3_title),
                            description = stringResource(R.string.pairing_step3_desc)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Big Connect/Pair button
                MenuTile(
                    title = if (isPaired) stringResource(R.string.connect_to_device) else stringResource(
                        R.string.pair_and_connect
                    ),
                    icon = Icons.Default.PhonelinkSetup,
                    onClick = { viewModel.connectToDevice() },
                    modifier = Modifier.fillMaxWidth(0.9f),
                    arrangementH = Arrangement.Center
                )

                // Small buttons for Share and Unpair
                @Suppress("AssignedValueIsNeverRead")
                if (isPaired) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ShareAndUnpairButtons(
                        navController = navController,
                        onUnpairClick = { showUnpairDialog = true },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
                // Big buttons for main actions
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
                }

                // Small buttons for Share and Unpair
                @Suppress("AssignedValueIsNeverRead")
                if (isPaired) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ShareAndUnpairButtons(
                        navController = navController,
                        onUnpairClick = { showUnpairDialog = true },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    )
                }
            }
        }
    }
}

