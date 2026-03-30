package com.mrboombastic.buwudzik.viewmodels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

class MainViewModel(
    private val scanner: BluetoothScanner,
    private val settingsRepository: SettingsRepository,
    val deviceProfileRepository: DeviceProfileRepository,
    private val applicationContext: Context
) : ViewModel() {

    // -------------------------------------------------------------------------
    // Device list & active device
    // -------------------------------------------------------------------------

    val devices: StateFlow<List<DeviceProfile>> = deviceProfileRepository.profilesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, deviceProfileRepository.getProfiles())

    val activeDevice: StateFlow<DeviceProfile?> = combine(
        deviceProfileRepository.profilesFlow,
        deviceProfileRepository.activeDeviceIdFlow
    ) { profiles, activeMac ->
        profiles.find { it.mac == activeMac }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, deviceProfileRepository.getActiveProfile())

    fun addDevice(profile: DeviceProfile) {
        deviceProfileRepository.addOrUpdate(profile)
    }

    fun removeDevice(mac: String) {
        deviceProfileRepository.remove(mac)
    }

    fun deviceSheetSwipeHintAlreadyShown(): Boolean =
        settingsRepository.deviceSheetSwipeHintShown

    fun markDeviceSheetSwipeHintSeen() {
        settingsRepository.deviceSheetSwipeHintShown = true
    }

    fun setActiveDevice(mac: String) {
        val profile = deviceProfileRepository.getByMac(mac) ?: return
        deviceProfileRepository.setActiveDeviceId(profile.mac)
        stopAll()
        _deviceConnected.value = false
        _deviceConnecting.value = false
        _sensorData.value =
            SensorRepository(
                applicationContext,
                profile.mac,
                deviceProfileRepository
            ).getSensorData()
        _alarms.value = emptyList()
        _deviceSettings.value = null
        startScanning()
    }

    fun updateDeviceAlias(mac: String, newAlias: String) {
        val profile = deviceProfileRepository.getByMac(mac) ?: return
        deviceProfileRepository.addOrUpdate(profile.copy(alias = newAlias.trim()))
    }

    fun updateDeviceBatteryType(mac: String, batteryType: String) {
        val normalizedMac = mac.normalizedBluetoothMac()
        deviceProfileRepository.getByMac(normalizedMac)?.let { profile ->
            deviceProfileRepository.addOrUpdate(profile.copy(batteryType = batteryType))
        }
        val updated =
            SensorRepository(applicationContext, normalizedMac, deviceProfileRepository)
                .reapplyBatteryCorrection()
        if (normalizedMac == activeMac.normalizedBluetoothMac()) {
            updated?.let { _sensorData.value = it }
        }
        viewModelScope.launch {
            SensorWidgetRefresher.updateAll(applicationContext)
        }
    }

    // -------------------------------------------------------------------------
    // Sensor / connection state
    // -------------------------------------------------------------------------

    private val activeMac: String
        get() = deviceProfileRepository.getActiveDeviceId() ?: ""

    /** Per-active-device sensor repository. */
    private fun sensorRepo(): SensorRepository =
        SensorRepository(applicationContext, activeMac, deviceProfileRepository)

    /** Per-active-device alarm title repository. */
    private fun alarmTitleRepo(): AlarmTitleRepository =
        AlarmTitleRepository(applicationContext, activeMac)

    private val _sensorData = MutableStateFlow(sensorRepo().getSensorData())
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
        _isPaired.value = if (mac.isNotEmpty()) qpController.isDevicePaired(mac) else false
    }

    fun unpairDevice() {
        val mac = activeMac
        if (mac.isNotEmpty()) {
            qpController.unpairDevice(mac)
            checkPairingStatus()
            // Drop stale BLE/UI cache so the home screen shows pair flow, not last readings
            _sensorData.value = null
            _alarms.value = emptyList()
            _deviceSettings.value = null
            _connectionError.value = null
        }
    }

    fun handleUnexpectedDisconnect() {
        rssiPollJob?.cancel()
        rssiPollJob = null
        _deviceConnected.value = false
        _deviceConnecting.value = false
        AppLogger.d(TAG, "Handled unexpected disconnect, starting scan")
        startScanning()
    }

    private var scanJob: Job? = null
    private var rssiPollJob: Job? = null
    private var connectionJob: Job? = null

    init {
        _isBluetoothEnabled.value = BluetoothUtils.isBluetoothEnabled(applicationContext)
        checkPairingStatus()
        viewModelScope.launch {
            activeDevice.collect { profile ->
                if (profile == null) {
                    stopAll()
                    clearPerDeviceUiState()
                }
            }
        }
    }

    /** Reset cached device UI when there is no selected profile (e.g. all devices removed). */
    private fun clearPerDeviceUiState() {
        _sensorData.value = null
        _alarms.value = emptyList()
        _deviceSettings.value = null
        _deviceConnected.value = false
        _deviceConnecting.value = false
        _connectionError.value = null
        checkPairingStatus()
    }

    fun updateBluetoothState(enabled: Boolean) {
        _isBluetoothEnabled.value = enabled
        if (enabled) {
            startScanning()
        } else {
            scanJob?.cancel()
            rssiPollJob?.cancel()
            connectionJob?.cancel()
            _deviceConnected.value = false
            _deviceConnecting.value = false
        }
    }

    private fun stopAll() {
        scanJob?.cancel(); scanJob = null
        rssiPollJob?.cancel(); rssiPollJob = null
        connectionJob?.cancel(); connectionJob = null
        if (_deviceConnected.value) {
            qpController.disconnect()
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) {
            AppLogger.v(TAG, "Scan already active, ignoring start request.")
            return
        }

        val mac = activeMac
        if (mac.isEmpty()) {
            AppLogger.d(TAG, "No active device – skipping scan")
            return
        }

        val scanMode = settingsRepository.scanMode
        AppLogger.d(TAG, "Starting scanning flow for $mac with mode $scanMode...")
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanner.scan(mac, scanMode).collect { data ->
                AppLogger.d(TAG, "Received data: $data")
                val correctedData = sensorRepo().saveSensorData(data)
                _sensorData.value = correctedData
                SensorWidgetRefresher.updateAll(applicationContext)
            }
        }
    }

    fun restartScanning() {
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
                    startScanning()
                }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error connecting to clock ($targetMac): ${e.message}", e)
                _deviceConnected.value = false
                _connectionError.value = e.message ?: "Connection failed"
                startScanning()
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
        rssiPollJob?.cancel(); rssiPollJob = null
        connectionJob?.cancel(); connectionJob = null
        qpController.disconnect()
        _deviceConnected.value = false
        AppLogger.d(TAG, "Disconnected from clock, restarting scan.")
        startScanning()
    }

    override fun onCleared() {
        super.onCleared()
        qpController.close()
    }
}
