package com.mrboombastic.buwudzik.viewmodels

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.icu.util.TimeZone
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrboombastic.buwudzik.data.AlarmTitleRepository
import com.mrboombastic.buwudzik.data.SensorRepository
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.Alarm
import com.mrboombastic.buwudzik.device.BluetoothScanner
import com.mrboombastic.buwudzik.device.DeviceSettings
import com.mrboombastic.buwudzik.device.QPController
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.widget.SensorGlanceWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

class MainViewModel(
    private val scanner: BluetoothScanner,
    private val settingsRepository: SettingsRepository,
    private val applicationContext: Context
) : ViewModel() {

    private val sensorRepository = SensorRepository(applicationContext)
    private val alarmTitleRepository = AlarmTitleRepository(applicationContext)

    private val _sensorData = MutableStateFlow(sensorRepository.getSensorData())
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

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    fun clearConnectionError() {
        _connectionError.value = null
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
        AppLogger.d(TAG, "Handled unexpected disconnect, starting scan")
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
            rssiPollJob?.cancel()
            connectionJob?.cancel()
            _deviceConnected.value = false
            _deviceConnecting.value = false
        }
    }

    fun startScanning() {
        if (scanJob?.isActive == true) {
            AppLogger.v(TAG, "Scan already active, ignoring start request.")
            return
        }

        val targetMac = settingsRepository.targetMacAddress
        val scanMode = settingsRepository.scanMode
        AppLogger.d(TAG, "Starting scanning flow for $targetMac with mode $scanMode...")
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            scanner.scan(targetMac, scanMode).collect { data ->
                AppLogger.d(TAG, "Received data: $data")
                val correctedData = sensorRepository.saveSensorData(data)
                _sensorData.value = correctedData
                // Update Glance widget with fresh data
                try {
                    SensorGlanceWidget().updateAll(applicationContext)
                } catch (e: Exception) {
                    AppLogger.d(TAG, "Widget update skipped: ${e.message}")
                }
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

        val targetMac = settingsRepository.targetMacAddress
        if (targetMac.isEmpty()) {
            AppLogger.e(TAG, "No target MAC address configured")
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
                    _connectionError.value = null
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
                        while (isActive && _deviceConnected.value) {
                            try {
                                qpController.readRssi()
                            } catch (e: Exception) {
                                AppLogger.w(TAG, "RSSI poll failed: ${e.message}", e)
                            }
                            delay(QPController.DELAY_RSSI_POLL) // Poll every 5 seconds
                        }
                    }

                    if (reloadAlarms) {
                        AppLogger.d(
                            TAG, "Clock connected, reading alarms and settings..."
                        )
                        launch {
                            try {
                                val deviceAlarms = qpController.readAlarms()
                                val alarmsWithTitles = deviceAlarms.map { alarm ->
                                    alarm.copy(title = alarmTitleRepository.getTitle(alarm.id))
                                }
                                _alarms.value = alarmsWithTitles
                                AppLogger.d(
                                    TAG, "Loaded ${alarmsWithTitles.size} alarms"
                                )
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error loading alarms", e)
                            }

                            delay(QPController.DELAY_BLE_OPERATION) // Small gap to avoid BLE race conditions

                            try {
                                val settings = qpController.readDeviceSettings()
                                _deviceSettings.value = settings
                                AppLogger.d(TAG, "Loaded device settings: $settings")

                                // Check if timezone needs sync (e.g. DST change)
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
                                _deviceSettings.value =
                                    _deviceSettings.value?.copy(firmwareVersion = version)
                                AppLogger.d(TAG, "Loaded firmware version: $version")
                            } catch (e: Exception) {
                                AppLogger.e(TAG, "Error loading firmware version", e)
                            }
                        }
                    }
                } else {
                    AppLogger.e(TAG, "Failed to connect to clock")
                    startScanning() // Restart scanning if connection fails
                }
            } catch (e: Exception) {
                AppLogger.e(
                    TAG,
                    "Error connecting to clock ($targetMac): ${e.message}",
                    e
                )
                _deviceConnected.value = false
                _connectionError.value = e.message ?: "Connection failed"
                // Do NOT clear disconnection event here, let the UI handle it via LaunchedEffect
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
                // Delay to ensure previous write (like setAlarm) is fully processed by the device
                delay(QPController.DELAY_ALARM_RELOAD)
                AppLogger.d(TAG, "Reloading alarms...")
                val deviceAlarms = qpController.readAlarms()
                val alarmsWithTitles = deviceAlarms.map { alarm ->
                    alarm.copy(title = alarmTitleRepository.getTitle(alarm.id))
                }
                _alarms.value = alarmsWithTitles
                AppLogger.d(TAG, "Reloaded ${alarmsWithTitles.size} alarms")
            } catch (e: Exception) {
                AppLogger.e(TAG, "Error reloading alarms", e)
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
                AppLogger.e(TAG, "Error updating alarm", e)
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
        rssiPollJob?.cancel()
        rssiPollJob = null
        connectionJob?.cancel()
        connectionJob = null
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
