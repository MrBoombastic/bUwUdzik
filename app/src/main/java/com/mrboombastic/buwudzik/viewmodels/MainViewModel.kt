package com.mrboombastic.buwudzik.viewmodels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.icu.util.TimeZone
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.DeviceLocalDataCleaner
import com.mrboombastic.buwudzik.data.DeviceProfile
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.data.normalizedBluetoothMac
import com.mrboombastic.buwudzik.device.Alarm
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.device.DeviceSettings
import com.mrboombastic.buwudzik.device.QPController
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.widget.SensorWidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(
    private val scanner: BluetoothScanner,
    private val settingsRepository: SettingsRepository,
    private val deviceProfileRepository: DeviceProfileRepository,
    private val applicationContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        const val FAKE_MAC = "DE:AD:BE:EF:CA:FE"
    }

    private fun sensorRepo() = SensorRepository(
        applicationContext,
        deviceProfileRepository.getActiveDeviceId() ?: "",
        deviceProfileRepository
    )

    private fun alarmTitleRepo() = AlarmTitleRepository(
        applicationContext,
        deviceProfileRepository.getActiveDeviceId() ?: ""
    )

    val activeDevice: StateFlow<DeviceProfile?> = deviceProfileRepository.activeProfile
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = deviceProfileRepository.getActiveProfile()
        )

    val activeMac: String
        get() = deviceProfileRepository.getActiveDeviceId() ?: ""

    val devices: StateFlow<List<DeviceProfile>> = deviceProfileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setActiveDevice(mac: String) {
        if (activeMac == mac) {
            checkPairingStatus()
            return
        }
        AppLogger.d(TAG, "Setting active device to $mac")
        deviceProfileRepository.setActiveDeviceId(mac)
    }

    fun addDevice(profile: DeviceProfile, makeActive: Boolean = false) {
        deviceProfileRepository.addOrUpdate(profile)
        if (makeActive) {
            setActiveDevice(profile.mac)
        }
    }

    fun removeDevice(mac: String) {
        DeviceLocalDataCleaner.wipeAllLocalStateForDevice(applicationContext, mac)
        deviceProfileRepository.remove(mac)
        viewModelScope.launch { SensorWidgetRefresher.updateAll(applicationContext) }
    }

    fun updateDeviceAlias(mac: String, alias: String) {
        val profile = deviceProfileRepository.getByMac(mac)
        if (profile != null) {
            deviceProfileRepository.addOrUpdate(profile.copy(alias = alias))
        }
    }

    fun updateDeviceBatteryType(mac: String, batteryType: String) {
        val profile = deviceProfileRepository.getByMac(mac)
        if (profile != null) {
            deviceProfileRepository.addOrUpdate(profile.copy(batteryType = batteryType))
            // Recompute cached battery immediately so UI reflects battery-type change without waiting for next scan.
            if (activeMac == mac) {
                sensorRepo().reapplyBatteryCorrection()?.let { corrected ->
                    _sensorData.value = corrected
                }
            }
        }
    }

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

    val disconnectionEvent = qpController.disconnectionEvent

    fun clearDisconnectionEvent() = qpController.clearDisconnectionEvent()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    fun clearConnectionError() {
        _connectionError.value = null
    }

    fun checkPairingStatus() {
        val mac = activeMac
        _isPaired.value = mac == FAKE_MAC || (mac.isNotEmpty() && qpController.isDevicePaired(mac))
    }

    fun deviceSheetSwipeHintAlreadyShown(): Boolean = settingsRepository.deviceSheetSwipeHintShown

    fun markDeviceSheetSwipeHintSeen() {
        settingsRepository.deviceSheetSwipeHintShown = true
    }

    fun handleUnexpectedDisconnect() {
        rssiPollJob?.cancel()
        rssiPollJob = null
        _deviceConnected.value = false
        _deviceConnecting.value = false
        AppLogger.d(TAG, "Handled unexpected disconnect, restarting scan")
        // Always restart: connect failures often call startScanning() first; a second start is ignored
        // while the job is still winding down, leaving no active collection.
        restartScanning()
    }

    private var scanJob: Job? = null
    private var rssiPollJob: Job? = null
    private var connectionJob: Job? = null

    init {
        _isBluetoothEnabled.value = BluetoothUtils.isBluetoothEnabled(applicationContext)
        deviceProfileRepository.activeDeviceIdFlow
            .distinctUntilChanged()
            .onEach { mac ->
                AppLogger.d(TAG, "Active device changed to $mac, resetting UI and connections")
                stopActiveConnection()
                updateActiveDeviceUiState()
                if (mac != null && !mac.equals(
                        FAKE_MAC,
                        ignoreCase = true
                    ) && _isBluetoothEnabled.value
                ) {
                    startScanning()
                }
            }
            .launchIn(viewModelScope)
    }

    /** Reset cached device UI when there is no selected profile (e.g. all devices removed). */
    private fun updateActiveDeviceUiState() {
        if (activeMac.equals(FAKE_MAC, ignoreCase = true)) {
            AppLogger.d(TAG, "Fake device selected, initializing mock state")
            initializeMockState()
            return
        }
        _sensorData.value = if (activeMac.isNotEmpty()) sensorRepo().getSensorData() else null
        _alarms.value = emptyList()
        _deviceSettings.value = null
        _deviceConnected.value = false
        _deviceConnecting.value = false
        _connectionError.value = null
        checkPairingStatus()
    }

    fun updateBluetoothState(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (enabled && !activeMac.equals(FAKE_MAC, ignoreCase = true)) {
            startScanning()
        } else {
            scanJob?.cancel()
            stopActiveConnection()
        }
    }

    private fun stopActiveConnection() {
        rssiPollJob?.cancel(); rssiPollJob = null
        connectionJob?.cancel(); connectionJob = null
        scanJob?.cancel(); scanJob = null
        qpController.disconnect()
        _deviceConnected.value = false
        _deviceConnecting.value = false
    }

    fun startScanning() {
        if (activeMac.equals(FAKE_MAC, ignoreCase = true)) {
            AppLogger.d(TAG, "Fake device active, skipping scan.")
            return
        }

        if (scanJob?.isActive == true) {
            AppLogger.v(TAG, "Scan already active, ignoring start request.")
            return
        }

        val scanMode = settingsRepository.scanMode
        AppLogger.d(TAG, "Starting global scanning flow with mode $scanMode...")
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanner.scan(null, scanMode).collect { data ->
                val mac = data.macAddress.normalizedBluetoothMac()
                val profile = deviceProfileRepository.getByMac(mac)

                if (profile != null) {
                    AppLogger.d(TAG, "Received data for saved device $mac: $data")
                    val repo = SensorRepository(applicationContext, mac, deviceProfileRepository)
                    val correctedData = repo.saveSensorData(data)

                    if (mac == activeMac.normalizedBluetoothMac()) {
                        _sensorData.value = correctedData
                    }

                    SensorWidgetRefresher.updateAll(applicationContext)
                }
            }
        }
    }

    fun restartScanning() {
        if (activeMac.equals(FAKE_MAC, ignoreCase = true)) return
        AppLogger.d(TAG, "Restarting scan...")
        scanJob?.cancel()
        scanJob = null
        _sensorData.value = null
        startScanning()
    }

    fun stopScanning() {
        AppLogger.d(TAG, "Stopping scan (app going to background)...")
        scanJob?.cancel()
        scanJob = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(reloadAlarms: Boolean = true) {
        if (_deviceConnecting.value || _deviceConnected.value) return

        val targetMac = activeMac
        if (targetMac.isEmpty()) {
            AppLogger.e(TAG, "No active device MAC configured")
            return
        }

        _deviceConnecting.value = true
        scanJob?.cancel()
        connectionJob?.cancel()

        connectionJob = viewModelScope.launch {
            try {
                val bluetoothManager =
                    applicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = bluetoothManager.adapter
                val device = adapter.getRemoteDevice(targetMac)

                val success = qpController.connectAndAuthenticate(device)
                if (success) {
                    _deviceConnected.value = true
                    _connectionError.value = null
                    checkPairingStatus()

                    attachLiveSensorCallbacks()

                    rssiPollJob?.cancel()
                    rssiPollJob = viewModelScope.launch {
                        while (isActive && _deviceConnected.value) {
                            try {
                                qpController.readRssi()
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "RSSI poll failed: ${e.message}", e)
                            }
                            delay(QPController.DELAY_RSSI_POLL)
                        }
                    }

                    if (reloadAlarms) {
                        launch { loadDeviceMetadataAfterConnect() }
                    }
                } else {
                    AppLogger.e(TAG, "Failed to connect to clock")
                    restartScanning()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error connecting to clock ($targetMac): ${e.message}", e)
                _deviceConnected.value = false
                _connectionError.value = e.message ?: "Connection failed"
                restartScanning()
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
                delay(QPController.DELAY_ALARM_RELOAD)
                AppLogger.d(TAG, "Reloading alarms...")
                _alarms.value = fetchAlarmsWithTitles()
                AppLogger.d(TAG, "Reloaded ${_alarms.value.size} alarms")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reloading alarms", e)
            }
        }
    }

    private suspend fun fetchAlarmsWithTitles(): List<Alarm> {
        val deviceAlarms = qpController.readAlarms()
        val titleRepo = alarmTitleRepo()
        return deviceAlarms.map { alarm ->
            alarm.copy(title = titleRepo.getTitle(alarm.id))
        }
    }

    private suspend fun loadDeviceMetadataAfterConnect() {
        AppLogger.d(TAG, "Clock connected, reading alarms and settings...")
        try {
            val alarmsWithTitles = fetchAlarmsWithTitles()
            _alarms.value = alarmsWithTitles
            AppLogger.d(TAG, "Loaded ${alarmsWithTitles.size} alarms")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading alarms", e)
        }

        delay(QPController.DELAY_BLE_OPERATION)

        try {
            val settings = qpController.readDeviceSettings()
            _deviceSettings.value = settings
            AppLogger.d(TAG, "Loaded device settings: $settings")

            // Check if the timezone needs sync (e.g. DST change)
            val currentPhoneTz = TimeZone.getDefault()
            val now = System.currentTimeMillis()
            if (settings.timeZone.getOffset(now) != currentPhoneTz.getOffset(now)) {
                AppLogger.d(TAG, "Device timezone offset differs from phone, auto-syncing...")
                val updatedSettings = settings.copy(timeZone = currentPhoneTz)
                qpController.writeDeviceSettings(updatedSettings)
                _deviceSettings.value = updatedSettings
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading settings", e)
        }

        delay(QPController.DELAY_BLE_OPERATION)

        try {
            val version = qpController.readFirmwareVersion()
            _deviceSettings.value = _deviceSettings.value?.copy(firmwareVersion = version)
            AppLogger.d(TAG, "Loaded firmware version: $version")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error loading firmware version", e)
        }
    }

    private fun attachLiveSensorCallbacks() {
        qpController.onSensorData = { temperature, humidity ->
            val currentData = _sensorData.value
            val mac = activeMac
            val batteryFromStore = sensorRepo().getSensorData()?.battery
            val resolvedBattery = batteryFromStore ?: currentData?.battery ?: 0
            _sensorData.value = currentData?.copy(
                temperature = temperature.toDouble(),
                humidity = humidity.toDouble(),
                battery = resolvedBattery,
                timestamp = System.currentTimeMillis()
            ) ?: SensorData(
                name = "clOwOck",
                macAddress = mac,
                temperature = temperature.toDouble(),
                humidity = humidity.toDouble(),
                battery = resolvedBattery,
                rssi = 0,
                timestamp = System.currentTimeMillis()
            )
        }
        qpController.onRssiUpdate = { rssi ->
            _sensorData.value = _sensorData.value?.copy(rssi = rssi)
        }
    }

    fun updateAlarm(alarm: Alarm, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                if (activeMac.equals(FAKE_MAC, ignoreCase = true)) {
                    // Update controller mock state
                    qpController.setAlarm(
                        alarm.hour,
                        alarm.minute,
                        alarm.id,
                        alarm.enabled,
                        alarm.days,
                        alarm.snooze
                    )
                    // Refresh local flow
                    reloadAlarms()
                    onResult(Result.success(Unit))
                    return@launch
                }

                qpController.setAlarm(
                    hour = alarm.hour,
                    minute = alarm.minute,
                    alarmId = alarm.id,
                    enable = alarm.enabled,
                    days = alarm.days,
                    snooze = alarm.snooze
                )
                alarmTitleRepo().setTitle(alarm.id, alarm.title)
                reloadAlarms()
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error updating alarm", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun deleteAlarm(alarmId: Int, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                if (activeMac.equals(FAKE_MAC, ignoreCase = true)) {
                    qpController.deleteAlarm(alarmId)
                    reloadAlarms()
                    onResult(Result.success(Unit))
                    return@launch
                }

                qpController.deleteAlarm(alarmId)
                alarmTitleRepo().deleteTitle(alarmId)
                reloadAlarms()
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error deleting alarm", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun updateDeviceSettings(settings: DeviceSettings, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch {
            try {
                if (activeMac.equals(FAKE_MAC, ignoreCase = true)) {
                    // Simulate fake settings update
                    _deviceSettings.value = settings
                    onResult(Result.success(Unit))
                    return@launch
                }

                val currentVersion = _deviceSettings.value?.firmwareVersion ?: ""
                qpController.writeDeviceSettings(settings)
                _deviceSettings.value = settings.copy(firmwareVersion = currentVersion)
                onResult(Result.success(Unit))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error updating settings", e)
                onResult(Result.failure(e))
            }
        }
    }

    fun reloadDeviceSettings() {
        viewModelScope.launch {
            try {
                AppLogger.d(TAG, "Reloading device settings...")
                val settings = qpController.readDeviceSettings()
                val currentVersion = _deviceSettings.value?.firmwareVersion ?: ""
                _deviceSettings.value = settings.copy(firmwareVersion = currentVersion)
                AppLogger.d(TAG, "Reloaded device settings")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reloading device settings", e)
            }
        }
    }

    fun disconnectFromDevice() {
        stopActiveConnection()
        AppLogger.d(TAG, "Disconnected from clock, restarting scan.")
        startScanning()
    }

    // -------------------------------------------------------------------------
    // Debug / fake-device injection (debug builds only)
    // -------------------------------------------------------------------------

    /**
     * Injects a fully fake clock device into the UI, bypassing BLE entirely.
     * Useful for testing layouts, sensor display, alarms, and settings screens
     * without physical hardware.
     *
     * Call [clearFakeDevice] to tear down the fake state.
     */
    fun injectFakeDevice(
        name: String = "Fake clOwOck",
        mac: String = FAKE_MAC,
        temperature: Double = 21.5,
        humidity: Double = 55.0,
        battery: Int = 72,
        rssi: Int = -65,
        alarmCount: Int = 3,
    ) {
        initializeMockState(name, mac, temperature, humidity, alarmCount, battery, rssi)
    }

    private fun initializeMockState(
        name: String = "Fake clOwOck",
        mac: String = FAKE_MAC,
        temperature: Double = 21.5,
        humidity: Double = 55.0,
        alarmCount: Int = 3,
        battery: Int = 72,
        rssi: Int = -65
    ) {
        scanJob?.cancel()
        scanJob = null
        stopActiveConnection()

        qpController.setupMockDevice(mac, temperature, humidity, alarmCount)

        val profile = DeviceProfile(mac, name)
        addDevice(profile, makeActive = true)

        viewModelScope.launch {
            _deviceConnecting.value = true
            // Mock connection is instantaneous
            _deviceConnected.value = true
            _connectionError.value = null
            _isPaired.value = true
            checkPairingStatus()

            attachLiveSensorCallbacks()

            // Trigger manual callback for the initial values
            qpController.onSensorData?.invoke(temperature.toFloat(), humidity.toFloat())
            _sensorData.value = SensorData(
                name = name,
                macAddress = mac,
                temperature = temperature,
                humidity = humidity,
                battery = battery,
                rssi = rssi,
                timestamp = System.currentTimeMillis()
            )

            launch { loadDeviceMetadataAfterConnect() }
            _deviceConnecting.value = false
        }
        AppLogger.d(TAG, "Mock state initialized for $name @ $mac")
    }

    /**
     * Removes the fake device state and resumes normal BLE scanning.
     */
    fun clearFakeDevice() {
        _sensorData.value = null
        _deviceConnected.value = false
        _isPaired.value = false
        _alarms.value = emptyList()
        _deviceSettings.value = null
        AppLogger.d(TAG, "Fake device cleared, restarting scan")

        if (!activeMac.equals(FAKE_MAC, ignoreCase = true)) {
            startScanning()
        }
    }

    override fun onCleared() {
        super.onCleared()
        qpController.close()
    }
}
