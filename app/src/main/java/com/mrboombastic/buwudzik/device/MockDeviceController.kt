package com.mrboombastic.buwudzik.device

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

class MockDeviceController(context: Context) : DeviceController {
    private val tag = "MockDeviceController"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val tokenStorage = TokenStorage(context)

    private val _isBusy = MutableStateFlow(false)
    override val isBusy = _isBusy.asStateFlow()

    private val _disconnectionEvent = MutableStateFlow<DisconnectionReason?>(null)
    override val disconnectionEvent = _disconnectionEvent.asStateFlow()

    override var onSensorData: ((temperature: Float, humidity: Float) -> Unit)? = null
    override var onBatteryUpdate: ((battery: Int) -> Unit)? = null
    override var onRssiUpdate: ((rssi: Int) -> Unit)? = null
    override var onLastUpdated: ((timestamp: Long) -> Unit)? = null

    private var mockAlarms = mutableListOf<Alarm>()
    private var mockSettings = DeviceSettings(
        volume = 3,
        backlightDuration = 10,
        screenBrightness = 50,
        nightModeBrightness = 10,
        nightModeEnabled = true,
        firmwareVersion = "1.0.0-DEMO"
    )
    private var mockSensorJob: Job? = null
    private var currentMac: String? = null
    
    private var currentTemp = 22.0
    private var currentHum = 45.0
    private var currentBattery = 100
    private var currentRssi = -50

    override suspend fun connectAndAuthenticate(device: BluetoothDevice): Boolean {
        currentMac = device.address
        AppLogger.d(tag, "Mocking connection to demo device ${device.address}...")
        delay(1000.milliseconds)
        
        // Set up the initial mock state if not already done
        if (mockAlarms.isEmpty()) {
            setupMockData(device.address)
        }
        
        // Report initial values immediately after connect
        onSensorData?.invoke(currentTemp.toFloat(), currentHum.toFloat())
        onBatteryUpdate?.invoke(currentBattery)
        onRssiUpdate?.invoke(currentRssi)
        onLastUpdated?.invoke(System.currentTimeMillis())
        
        startMockSensorLoop()
        return true
    }

    override fun disconnect() {
        AppLogger.d(tag, "Mocking disconnection from device ${currentMac ?: "unknown"}...")
    }

    fun setupMockData(
        mac: String,
        alarmCount: Int = 3,
        initialTemp: Double = 22.0,
        initialHum: Double = 45.0,
        initialBattery: Int = 100,
        initialRssi: Int = -50
    ) {
        currentTemp = initialTemp
        currentHum = initialHum
        currentBattery = initialBattery
        currentRssi = initialRssi
        mockAlarms.clear()
        mockAlarms.addAll(List(alarmCount) { idx ->
            Alarm(
                id = idx,
                enabled = idx % 2 == 0,
                hour = 6 + idx * 2,
                minute = idx * 15 % 60,
                days = if (idx == 0) 0 else 0x1F,
                snooze = idx == 1,
                title = if (idx == 0) "Wake up" else ""
            )
        })

        if (!tokenStorage.isPaired(mac)) {
            val fakeToken = ByteArray(16) { 0xDE.toByte() }
            tokenStorage.storeToken(mac, fakeToken)
        }

        startMockSensorLoop()
    }

    private fun startMockSensorLoop() {
        mockSensorJob?.cancel()
        mockSensorJob = scope.launch {
            while (isActive) {
                currentTemp += (-5..5).random() / 10.0
                currentHum += (-10..10).random() / 10.0
                currentTemp = currentTemp.coerceIn(15.0, 30.0)
                currentHum = currentHum.coerceIn(20.0, 80.0)
                
                onSensorData?.invoke(currentTemp.toFloat(), currentHum.toFloat())
                onBatteryUpdate?.invoke(currentBattery)
                onRssiUpdate?.invoke(currentRssi)
                onLastUpdated?.invoke(System.currentTimeMillis())
                delay(5000.milliseconds)
            }
        }
    }

    override fun readRssi() {
        onRssiUpdate?.invoke(-40 - (0..10).random())
    }

    override suspend fun readAlarms(): List<Alarm> {
        delay(500.milliseconds)
        return mockAlarms.toList()
    }

    override suspend fun setAlarm(
        hour: Int,
        minute: Int,
        alarmId: Int,
        enable: Boolean,
        days: Int,
        snooze: Boolean
    ): Boolean {
        delay(300.milliseconds)
        val existing = mockAlarms.indexOfFirst { it.id == alarmId }
        val newAlarm = Alarm(alarmId, enable, hour, minute, days, snooze)
        if (existing >= 0) mockAlarms[existing] = newAlarm
        else mockAlarms.add(newAlarm)
        return true
    }

    override suspend fun deleteAlarm(alarmId: Int): Boolean {
        delay(300.milliseconds)
        mockAlarms.removeAll { it.id == alarmId }
        return true
    }

    override suspend fun readDeviceSettings(): DeviceSettings {
        delay(500.milliseconds)
        return mockSettings
    }

    override suspend fun writeDeviceSettings(settings: DeviceSettings): Boolean {
        delay(500.milliseconds)
        mockSettings = settings
        return true
    }

    override suspend fun readFirmwareVersion(): String = "1.0.0-DEMO"

    override fun isDevicePaired(macAddress: String): Boolean = true

    override fun clearDisconnectionEvent() {
        _disconnectionEvent.value = null
    }

    override fun close() {
        disconnect()
        scope.cancel()
    }

    override suspend fun uploadAudio(audioData: ByteArray, signature: ByteArray, onProgress: (Float) -> Unit): Boolean {
        for (i in 1..10) {
            delay(200.milliseconds)
            onProgress(i / 10f)
        }
        return true
    }

    override suspend fun stopAudioPreview(): Boolean = true

    override suspend fun previewRingtone(settings: DeviceSettings?): Boolean {
        delay(300.milliseconds)
        if (settings != null) {
            mockSettings = settings
        }
        return true
    }

    override suspend fun previewBrightness(brightness: Int): Boolean = true

    override suspend fun synchronizeTime(timestamp: Long?): Boolean = true

    override fun enqueueCommand(command: suspend () -> Unit) {
        scope.launch { command() }
    }
}
