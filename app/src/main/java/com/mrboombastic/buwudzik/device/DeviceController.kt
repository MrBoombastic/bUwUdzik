package com.mrboombastic.buwudzik.device

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.StateFlow

interface DeviceController {
    val isBusy: StateFlow<Boolean>
    val disconnectionEvent: StateFlow<DisconnectionReason?>
    
    var onSensorData: ((temperature: Float, humidity: Float) -> Unit)?
    var onRssiUpdate: ((rssi: Int) -> Unit)?
    var onLastUpdated: ((timestamp: Long) -> Unit)?

    suspend fun connectAndAuthenticate(device: BluetoothDevice): Boolean
    fun disconnect()
    fun readRssi()
    fun enqueueCommand(command: suspend () -> Unit)
    suspend fun readAlarms(): List<Alarm>
    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        alarmId: Int = 0,
        enable: Boolean = true,
        days: Int = 0,
        snooze: Boolean = false
    ): Boolean
    suspend fun deleteAlarm(alarmId: Int): Boolean
    suspend fun readDeviceSettings(): DeviceSettings
    suspend fun writeDeviceSettings(settings: DeviceSettings): Boolean
    suspend fun readFirmwareVersion(): String
    fun isDevicePaired(macAddress: String): Boolean
    fun clearDisconnectionEvent()
    fun close()
    
    // For audio upload (from BleDeviceController)
    suspend fun uploadAudio(audioData: ByteArray, signature: ByteArray, onProgress: (Float) -> Unit): Boolean
    suspend fun stopAudioPreview(): Boolean
    suspend fun previewRingtone(settings: DeviceSettings? = null): Boolean
    suspend fun previewBrightness(brightness: Int): Boolean
    suspend fun synchronizeTime(timestamp: Long? = null): Boolean
}
