package com.mrboombastic.buwudzik.device

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import androidx.annotation.RequiresPermission
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.device.BleConstants.Command
import com.mrboombastic.buwudzik.device.BleConstants.Flags
import com.mrboombastic.buwudzik.device.BleConstants.Header
import com.mrboombastic.buwudzik.device.BleConstants.Status
import com.mrboombastic.buwudzik.device.BleConstants.UUID_AUTH_NOTIFY
import com.mrboombastic.buwudzik.device.BleConstants.UUID_AUTH_WRITE
import com.mrboombastic.buwudzik.device.BleConstants.UUID_CLIENT_CHARACTERISTIC_CONFIG
import com.mrboombastic.buwudzik.device.BleConstants.UUID_DATA_NOTIFY
import com.mrboombastic.buwudzik.device.BleConstants.UUID_DATA_WRITE
import com.mrboombastic.buwudzik.device.BleConstants.UUID_SENSOR_NOTIFY
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Controller for QP CGD1 device via BLE GATT
 * Maintains a persistent connection with the device
 */
@SuppressLint("MissingPermission")
class BleDeviceController(private val context: Context) : DeviceController {

    private val commandConsumerJob = SupervisorJob()
    private val commandConsumerScope = CoroutineScope(Dispatchers.Default + commandConsumerJob)
    
    private val deviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + deviceJob)
    
    private val gattMutex = Mutex()
    private val commandChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _isBusy = MutableStateFlow(false)
    override val isBusy = _isBusy.asStateFlow()

    init {
        commandConsumerScope.launch {
            for (command in commandChannel) {
                if (!isAuthenticated) {
                    AppLogger.w(TAG, "Device not authenticated, skipping queued command")
                    continue
                }
                _isBusy.value = true
                try {
                    command()
                } catch (e: Exception) {
                    AppLogger.e(TAG, "Error executing queued command", e)
                } finally {
                    _isBusy.value = false
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleDeviceController"

        private const val TIMEOUT_AUTHENTICATION = 30000L
        private const val TIMEOUT_OPERATION = 5000L

        const val DELAY_POST_AUTH = 500L
        const val DELAY_BLE_OPERATION = 200L
        const val DELAY_ALARM_RELOAD = 300L
        const val DELAY_ALARM_COMPLETION = 1000L
        const val DELAY_RSSI_POLL = 5000L
        const val DELAY_PACKET_WRITE = 20L

        private const val AUDIO_PACKET_SIZE = 128
        private const val AUDIO_PACKETS_PER_BLOCK = 4
        private const val AUDIO_ACK_WAIT_ITERATIONS = 50
        private const val AUDIO_INIT_ACK_WAIT_ITERATIONS = 20
        private const val AUDIO_ACK_WAIT_DELAY = 100L
    }
    
    private val tokenStorage = TokenStorage(context)
    private var currentDeviceMac: String? = null
    private var currentToken: ByteArray? = null
    private var gatt: BluetoothGatt? = null
    private var isAuthenticated = false
    private var isConnected = false
    private var isPendingPairing = false

    // Pending operations
    private var connectContinuation: Continuation<Boolean>? = null
    private val pendingAckContinuations = mutableMapOf<Int, Continuation<Boolean>>()
    private var alarmReadContinuation: Continuation<List<Alarm>>? = null
    private var deviceSettingsReadContinuation: Continuation<DeviceSettings>? = null
    private var firmwareVersionReadContinuation: Continuation<String>? = null
    private var sensorNotificationContinuation: Continuation<Boolean>? = null

    private val alarmBuffer = mutableListOf<Alarm>()
    private var alarmCompletionJob: Job? = null
    private var lastSettingsPacket: ByteArray? = null
    private var pendingAuthWriteChar: BluetoothGattCharacteristic? = null
    private var authInitAckReceived = false
    private var pendingAuthWrite: BluetoothGattCharacteristic? = null
    private var pendingDataCommand: ByteArray? = null
    private val enabledNotifications = mutableSetOf<UUID>()
    private var writeCompleteDeferred: CompletableDeferred<Boolean>? = null

    override var onSensorData: ((temperature: Float, humidity: Float) -> Unit)? = null
    override var onBatteryUpdate: ((battery: Int) -> Unit)? = null
    override var onRssiUpdate: ((rssi: Int) -> Unit)? = null
    override var onLastUpdated: ((timestamp: Long) -> Unit)? = null

    private val _disconnectionEvent = MutableStateFlow<DisconnectionReason?>(null)
    override val disconnectionEvent = _disconnectionEvent.asStateFlow()

    override fun clearDisconnectionEvent() {
        _disconnectionEvent.value = null
    }

    private fun clearConnectionState(status: Int? = null) {
        AppLogger.d(TAG, "Clearing connection state (GATT status: $status)")
        isConnected = false
        isAuthenticated = false
        enabledNotifications.clear()
        authInitAckReceived = false
        alarmBuffer.clear()
        lastSettingsPacket = null
        pendingAuthWriteChar = null
        pendingAuthWrite = null
        pendingDataCommand = null
        alarmCompletionJob?.cancel()
        alarmCompletionJob = null

        status?.let {
            _disconnectionEvent.value = DisconnectionReason.fromGattStatus(it)
        }

        val error = Exception("Disconnected")
        connectContinuation?.let {
            try { it.resumeWithException(error) } catch (_: Exception) {}
            connectContinuation = null
        }

        val acks = pendingAckContinuations.values.toList()
        pendingAckContinuations.clear()
        acks.forEach {
            try { it.resumeWithException(error) } catch (_: Exception) {}
        }

        alarmReadContinuation?.let {
            try { it.resumeWithException(error) } catch (_: Exception) {}
            alarmReadContinuation = null
        }

        deviceSettingsReadContinuation?.let {
            try { it.resumeWithException(error) } catch (_: Exception) {}
            deviceSettingsReadContinuation = null
        }

        firmwareVersionReadContinuation?.let {
            try { it.resumeWithException(error) } catch (_: Exception) {}
            firmwareVersionReadContinuation = null
        }

        sensorNotificationContinuation?.let {
            try { it.resumeWithException(error) } catch (_: Exception) {}
            sensorNotificationContinuation = null
        }

        _isBusy.value = false
    }

    private fun handleAckNotification(value: ByteArray, characteristicUuid: UUID) {
        if (value.size < 4 || value[0] != Header.ACK[0] || value[1] != Header.ACK[1]) {
            AppLogger.d(TAG, "[$characteristicUuid] Unhandled notification: ${value.toHexString()}")
            return
        }
        val cmdId = value[2].toInt() and 0xFF
        val status = value[3].toInt() and 0xFF

        val cmdName = when (cmdId) {
            Command.AUTH_INIT -> "Auth Init"
            Command.AUTH_CONFIRM -> "Auth Confirm"
            Command.PREVIEW_BRIGHTNESS -> "Brightness Preview"
            Command.PREVIEW_RINGTONE -> "Preview Ringtone"
            Command.SET_ALARM -> "Alarm"
            Command.AUDIO_BLOCK -> "Audio Block"
            Command.TIME_SYNC -> "Time Sync"
            Command.AUDIO_INIT -> "Audio Init"
            else -> "Cmd $cmdId"
        }
        val authHint = context.getString(com.mrboombastic.buwudzik.R.string.auth_hint)
        AppLogger.d(TAG, "Received ACK for command '$cmdName' (ID: ${cmdId.toHexString()}). Status: ${status.toHexString()}")

        if (cmdId == Command.AUDIO_BLOCK || cmdId == Command.AUDIO_INIT) {
            handleUploadAck(value)
        }

        if (status == Status.SUCCESS || status == Status.ALARM_STILL_SUCCESS || (cmdId == Command.AUTH_INIT && status == Status.AUTH_INIT_SUCCESS)) {
            if (cmdId == Command.AUTH_INIT) {
                AppLogger.d(TAG, "Auth Init ACK received, will send Auth Confirm after write completes")
                authInitAckReceived = true
            } else if (cmdId == Command.AUTH_CONFIRM) {
                AppLogger.d(TAG, "Authentication seems successful, but syncing time will tell the truth")
                isAuthenticated = true
                pendingAuthWriteChar = null
                if (isPendingPairing) {
                    currentDeviceMac?.let { mac ->
                        currentToken?.let { token ->
                            tokenStorage.storeToken(mac, token)
                            AppLogger.d(TAG, "Token stored for $mac after successful pairing")
                        }
                    }
                    isPendingPairing = false
                }
            }
            pendingAckContinuations.remove(cmdId)?.resume(true)
        } else {
            val errorSuffix = if (status == Status.AUTH_INIT_SUCCESS) " $authHint" else ""
            AppLogger.e(TAG, "[$characteristicUuid] $cmdName failed with status $status$errorSuffix (Full: ${value.toHexString()})")
            pendingAckContinuations.remove(cmdId)?.resumeWithException(Exception("$cmdName failed: $status$errorSuffix"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            AppLogger.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    isConnected = true
                    AppLogger.d(TAG, "Connected to GATT server, discovering services...")
                    gatt?.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    AppLogger.d(TAG, "Disconnected from GATT server (status: $status)")
                    clearConnectionState(status)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            AppLogger.d(TAG, "onServicesDiscovered status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.d(TAG, "Services discovered successfully")
                connectContinuation?.resume(true)
                connectContinuation = null
            } else {
                AppLogger.e(TAG, "Service discovery failed: $status")
                connectContinuation?.resumeWithException(Exception("Service discovery failed"))
                connectContinuation = null
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            AppLogger.d(TAG, "onCharacteristicChanged ${characteristic.uuid}: ${value.toHexString()}")

            when (characteristic.uuid) {
                UUID_AUTH_NOTIFY -> {
                    if (value.isNotEmpty() && value[0] == Header.FIRMWARE_DATA) {
                        try {
                            val length = if (value.size > 1) value[1].toInt() and 0xFF else 0
                            val version = String(value, 2, minOf(length, value.size - 2))
                            AppLogger.d(TAG, "Received firmware version: $version")
                            firmwareVersionReadContinuation?.resume(version)
                            firmwareVersionReadContinuation = null
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed to parse firmware version", e)
                            firmwareVersionReadContinuation?.resumeWithException(e)
                            firmwareVersionReadContinuation = null
                        }
                    } else {
                        handleAckNotification(value, characteristic.uuid)
                    }
                }

                UUID_DATA_NOTIFY -> {
                    if (value.size >= 3 && value[0] == Header.ACK[0] && value[1] == Header.ACK[1]) {
                        val cmdId = value[2].toInt() and 0xFF
                        if (cmdId == Command.AUDIO_BLOCK || cmdId == Command.AUDIO_INIT) {
                            handleUploadAck(value)
                        }
                        if (value.size >= 4) {
                            handleAckNotification(value, characteristic.uuid)
                        }
                    } else if (value.size >= 3 && value[0] == Header.ALARM_DATA[0] && value[1] == Header.ALARM_DATA[1]) {
                        val baseIndex = value[2].toInt() and 0xFF
                        AppLogger.d(TAG, "Parsing alarms packet starting at index $baseIndex")

                        var offset = BleConstants.Alarm.START_OFFSET
                        var currentIndex = baseIndex
                        var highestIndexSeen = currentIndex

                        while (offset + BleConstants.Alarm.ENTRY_LENGTH <= value.size) {
                            val enabled = value[offset].toInt() and 0xFF == 1
                            val hour = value[offset + 1].toInt() and 0xFF
                            val minute = value[offset + 2].toInt() and 0xFF
                            val days = value[offset + 3].toInt() and 0xFF
                            val snooze = value[offset + 4].toInt() and 0xFF == 1

                            if (hour != 255 && minute != 255) {
                                val alarm = Alarm(currentIndex, enabled, hour, minute, days, snooze)
                                alarmBuffer.add(alarm)
                                AppLogger.d(TAG, "Parsed alarm #$currentIndex: ${alarm.getTimeString()} enabled=$enabled days=$days")
                            } else {
                                AppLogger.d(TAG, "Empty alarm slot #$currentIndex")
                            }

                            highestIndexSeen = currentIndex
                            offset += BleConstants.Alarm.ENTRY_LENGTH
                            currentIndex++
                        }

                        if (highestIndexSeen >= BleConstants.Alarm.TOTAL_SLOTS - 1) {
                            AppLogger.d(TAG, "Received all 16 alarm slots (up to index 15), returning ${alarmBuffer.size} alarms")
                            alarmReadContinuation?.resume(alarmBuffer.toList())
                            alarmReadContinuation = null
                            alarmBuffer.clear()
                            alarmCompletionJob?.cancel()
                            alarmCompletionJob = null
                        } else {
                            alarmCompletionJob?.cancel()
                            alarmCompletionJob = scope.launch {
                                delay(DELAY_ALARM_COMPLETION)
                                AppLogger.d(TAG, "Timeout waiting for more packets, returning ${alarmBuffer.size} alarms")
                                alarmReadContinuation?.resume(alarmBuffer.toList())
                                alarmReadContinuation = null
                                alarmBuffer.clear()
                            }
                        }
                    } else if (value.size >= BleConstants.Settings.MIN_PAYLOAD_SIZE && value[0] == Header.SET_SETTINGS && (value[1] == Header.SETTINGS_DATA_V1[1] || value[1] == Header.SETTINGS_DATA_V2[1])) {
                        AppLogger.d(TAG, "Received device settings packet: ${value.toHexString()}")
                        lastSettingsPacket = value.copyOf()
                        try {
                            val volume = value[BleConstants.Settings.INDEX_VOLUME].toInt() and 0xFF
                            val flags = value[BleConstants.Settings.INDEX_FLAGS].toInt()
                            val tzOffset = value[BleConstants.Settings.INDEX_TZ_OFFSET].toInt() and 0xFF
                            val duration = value[BleConstants.Settings.INDEX_BACKLIGHT_DUR].toInt() and 0xFF
                            val packedBrightness = value[BleConstants.Settings.INDEX_PACKED_BRIGHTNESS].toInt() and 0xFF
                            val screenBri = (packedBrightness shr 4) * 10
                            val nightBri = (packedBrightness and 0x0F) * 10
                            val tzSign = value[BleConstants.Settings.INDEX_TZ_SIGN].toInt() == 1
                            val nightModeEnabled = value[BleConstants.Settings.INDEX_NIGHT_MODE_EN].toInt() == 1

                            val ringtoneSig = if (value.size >= BleConstants.Settings.INDEX_RINGTONE_SIG + 4) {
                                byteArrayOf(value[BleConstants.Settings.INDEX_RINGTONE_SIG], value[BleConstants.Settings.INDEX_RINGTONE_SIG + 1], value[BleConstants.Settings.INDEX_RINGTONE_SIG + 2], value[BleConstants.Settings.INDEX_RINGTONE_SIG + 3])
                            } else {
                                byteArrayOf(0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte())
                            }

                            val settings = DeviceSettings(
                                tempUnit = if (flags and Flags.TEMP_UNIT_F != 0) TempUnit.Fahrenheit else TempUnit.Celsius,
                                timeFormat = if (flags and Flags.TIME_FORMAT_12H != 0) TimeFormat.H12 else TimeFormat.H24,
                                language = if (flags and Flags.LANG_ENGLISH != 0) Language.English else Language.Chinese,
                                volume = volume,
                                timeZone = createTimeZone(tzOffset, tzSign),
                                nightModeBrightness = nightBri,
                                backlightDuration = duration,
                                screenBrightness = screenBri,
                                nightStartHour = value[BleConstants.Settings.INDEX_NIGHT_START_H].toInt() and 0xFF,
                                nightStartMinute = value[BleConstants.Settings.INDEX_NIGHT_START_M].toInt() and 0xFF,
                                nightEndHour = value[BleConstants.Settings.INDEX_NIGHT_END_H].toInt() and 0xFF,
                                nightEndMinute = value[BleConstants.Settings.INDEX_NIGHT_END_M].toInt() and 0xFF,
                                nightModeEnabled = nightModeEnabled,
                                masterAlarmDisabled = (flags and Flags.MASTER_ALARM_DISABLE) != 0,
                                ringtoneSignature = ringtoneSig
                            )
                            deviceSettingsReadContinuation?.resume(settings)
                            deviceSettingsReadContinuation = null
                        } catch (e: Exception) {
                            AppLogger.e(TAG, "Failed to parse device settings", e)
                            deviceSettingsReadContinuation?.resumeWithException(e)
                            deviceSettingsReadContinuation = null
                        }
                    }
                }

                UUID_SENSOR_NOTIFY -> {
                    if (value.size >= 5 && value[0] == Header.SENSOR_DATA) {
                        val tempRaw = (value[2].toInt() and 0xFF shl 8) or (value[1].toInt() and 0xFF)
                        val humRaw = (value[4].toInt() and 0xFF shl 8) or (value[3].toInt() and 0xFF)
                        val temperature = tempRaw / 100.0f
                        val humidity = humRaw / 100.0f
                        AppLogger.d(TAG, "Sensor data: Temp=$temperature \u00b0C, Hum=$humidity %")
                        onSensorData?.invoke(temperature, humidity)
                        onLastUpdated?.invoke(System.currentTimeMillis())
                    }
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            AppLogger.d(TAG, "onCharacteristicWrite ${characteristic?.uuid} status=$status")
            val deferred = writeCompleteDeferred
            writeCompleteDeferred = null
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Write failed for ${characteristic?.uuid} with status $status")
                deferred?.complete(false)
                return
            }
            deferred?.complete(true)

            if (authInitAckReceived && characteristic?.uuid == UUID_AUTH_WRITE) {
                authInitAckReceived = false
                AppLogger.d(TAG, "Auth Init write complete, now sending Auth Confirm (11 02)...")
                pendingAuthWriteChar?.let { char ->
                    gatt?.writeCharacteristic(char, buildAuthConfirmPacket(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            AppLogger.d(TAG, "onDescriptorWrite status=$status for ${descriptor?.characteristic?.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Enable notification failed: $status")
                when (descriptor?.characteristic?.uuid) {
                    UUID_AUTH_NOTIFY -> {
                        pendingAckContinuations.remove(Command.AUTH_CONFIRM)?.resumeWithException(Exception("Enable auth notification failed: $status"))
                        pendingAuthWrite = null
                    }
                    UUID_DATA_NOTIFY -> {
                        alarmReadContinuation?.resumeWithException(Exception("Enable data notification failed: $status"))
                        alarmReadContinuation = null
                        pendingDataCommand = null
                    }
                    UUID_SENSOR_NOTIFY -> {
                        sensorNotificationContinuation?.resumeWithException(Exception("Enable sensor notification failed: $status"))
                        sensorNotificationContinuation = null
                    }
                }
                return
            }
            AppLogger.d(TAG, "Notification enabled for ${descriptor?.characteristic?.uuid}")

            when (descriptor?.characteristic?.uuid) {
                UUID_AUTH_NOTIFY -> {
                    pendingAuthWrite?.let { char ->
                        AppLogger.d(TAG, "Descriptor write complete, sending Auth Init (11 01)...")
                        pendingAuthWriteChar = char
                        gatt?.writeCharacteristic(char, buildAuthInitPacket(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        pendingAuthWrite = null
                    }
                }
                UUID_DATA_NOTIFY -> {
                    pendingDataCommand?.let { cmd ->
                        AppLogger.d(TAG, "Descriptor write complete, now sending data command: ${cmd.toHexString()}...")
                        val dataService = gatt?.services?.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                        dataWriteChar?.let { char ->
                            gatt.writeCharacteristic(char, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        }
                        pendingDataCommand = null
                    }
                }
                UUID_SENSOR_NOTIFY -> {
                    sensorNotificationContinuation?.resume(true)
                    sensorNotificationContinuation = null
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.d(TAG, "Read RSSI: $rssi")
                onRssiUpdate?.invoke(rssi)
            }
        }
    }

    override suspend fun connectAndAuthenticate(device: BluetoothDevice): Boolean {
        val macAddress = device.address
        currentDeviceMac = macAddress
        currentToken = prepareTokenForDevice(macAddress)
        AppLogger.d(TAG, "Token prepared for $macAddress: ${currentToken?.toHexString()}")

        connect(device)

        if (!isAuthenticated) {
            withTimeout(TIMEOUT_AUTHENTICATION) { authenticate() }
            delay(DELAY_POST_AUTH)
        }
        withTimeout(TIMEOUT_AUTHENTICATION) { synchronizeTime() }
        enableSensorNotifications()
        return true
    }

    private suspend fun authenticate(): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            suspendCancellableCoroutine { continuation ->
                val currentGatt = gatt ?: run {
                    continuation.resumeWithException(Exception("GATT not connected"))
                    return@suspendCancellableCoroutine
                }
                AppLogger.d(TAG, "Starting authentication...")
                pendingAckContinuations[Command.AUTH_CONFIRM] = continuation
                val authService = currentGatt.services.find { it.getCharacteristic(UUID_AUTH_NOTIFY) != null }
                val authNotifyChar = authService?.getCharacteristic(UUID_AUTH_NOTIFY)
                val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)
                if (authNotifyChar == null || authWriteChar == null) {
                    pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                    continuation.resumeWithException(Exception("Auth characteristics not found"))
                    return@suspendCancellableCoroutine
                }
                currentGatt.setCharacteristicNotification(authNotifyChar, true)
                val descriptor = authNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                pendingAuthWrite = authWriteChar
                descriptor?.let {
                    currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                continuation.invokeOnCancellation {
                    pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                    pendingAuthWrite = null
                }
            }
        }
    }

    override suspend fun synchronizeTime(timestamp: Long?): Boolean = gattMutex.withLock {
        val finalTimestamp = timestamp ?: (System.currentTimeMillis() / 1000)
        withContext(NonCancellable) {
            suspendCancellableCoroutine { continuation ->
                val currentGatt = gatt ?: run {
                    continuation.resumeWithException(Exception("GATT not connected"))
                    return@suspendCancellableCoroutine
                }
                if (!isAuthenticated) {
                    continuation.resumeWithException(Exception("Not authenticated"))
                    return@suspendCancellableCoroutine
                }
                pendingAckContinuations[Command.TIME_SYNC] = continuation
                val command = byteArrayOf(Header.TIME, Command.TIME_SYNC.toByte(), (finalTimestamp and 0xFF).toByte(), ((finalTimestamp shr 8) and 0xFF).toByte(), ((finalTimestamp shr 16) and 0xFF).toByte(), ((finalTimestamp shr 24) and 0xFF).toByte())
                val authService = currentGatt.services.find { it.getCharacteristic(UUID_AUTH_WRITE) != null }
                val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)
                if (authWriteChar == null) {
                    pendingAckContinuations.remove(Command.TIME_SYNC)
                    continuation.resumeWithException(Exception("Auth write characteristic not found"))
                    return@suspendCancellableCoroutine
                }
                currentGatt.writeCharacteristic(authWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.TIME_SYNC) }
            }
        }
    }

    override suspend fun readDeviceSettings(): DeviceSettings = gattMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val currentGatt = gatt ?: run {
                continuation.resumeWithException(Exception("GATT not connected"))
                return@suspendCancellableCoroutine
            }
            if (!isAuthenticated) {
                continuation.resumeWithException(Exception("Not authenticated"))
                return@suspendCancellableCoroutine
            }
            deviceSettingsReadContinuation = continuation
            val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
            val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
            val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
            if (dataWriteChar == null || dataNotifyChar == null) {
                continuation.resumeWithException(Exception("Data characteristics not found"))
                return@suspendCancellableCoroutine
            }
            val command = byteArrayOf(Header.GET_DATA, Command.GET_SETTINGS.toByte())
            if (enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                currentGatt.writeCharacteristic(dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                pendingDataCommand = command
                descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                enabledNotifications.add(UUID_DATA_NOTIFY)
            }
            continuation.invokeOnCancellation {
                deviceSettingsReadContinuation = null
                pendingDataCommand = null
            }
        }
    }

    override suspend fun readFirmwareVersion(): String = gattMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val currentGatt = gatt ?: run {
                continuation.resumeWithException(Exception("GATT not connected"))
                return@suspendCancellableCoroutine
            }
            if (!isAuthenticated) {
                continuation.resumeWithException(Exception("Not authenticated"))
                return@suspendCancellableCoroutine
            }
            firmwareVersionReadContinuation = continuation
            val authService = currentGatt.services.find { it.getCharacteristic(UUID_AUTH_WRITE) != null }
            val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)
            if (authWriteChar == null) {
                firmwareVersionReadContinuation = null
                continuation.resumeWithException(Exception("Auth write characteristic not found"))
                return@suspendCancellableCoroutine
            }
            val command = byteArrayOf(Header.GET_DATA, Command.GET_FIRMWARE.toByte())
            currentGatt.writeCharacteristic(authWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            continuation.invokeOnCancellation { firmwareVersionReadContinuation = null }
        }
    }

    override suspend fun writeDeviceSettings(settings: DeviceSettings): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }
                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }
                    pendingAckContinuations[Command.SET_SETTINGS] = continuation
                    val payload = lastSettingsPacket?.copyOf() ?: ByteArray(20).apply {
                        this[0] = Header.SET_SETTINGS
                        this[3] = Header.SETTINGS_FIXED_BYTE_3
                        this[4] = Header.SETTINGS_FIXED_BYTE_4
                    }
                    payload[0] = Header.SET_SETTINGS
                    payload[1] = Command.SET_SETTINGS.toByte()
                    payload[BleConstants.Settings.INDEX_VOLUME] = settings.volume.coerceIn(1, 5).toByte()
                    var flags = payload.getOrNull(BleConstants.Settings.INDEX_FLAGS)?.toInt()?.and(0xFF) ?: 0
                    flags = if (settings.language == Language.English) flags or Flags.LANG_ENGLISH else flags and Flags.LANG_ENGLISH.inv()
                    flags = if (settings.timeFormat == TimeFormat.H12) flags or Flags.TIME_FORMAT_12H else flags and Flags.TIME_FORMAT_12H.inv()
                    flags = if (settings.tempUnit == TempUnit.Fahrenheit) flags or Flags.TEMP_UNIT_F else flags and Flags.TEMP_UNIT_F.inv()
                    flags = if (settings.masterAlarmDisabled) flags or Flags.MASTER_ALARM_DISABLE else flags and Flags.MASTER_ALARM_DISABLE.inv()
                    payload[BleConstants.Settings.INDEX_FLAGS] = flags.toByte()
                    payload[BleConstants.Settings.INDEX_TZ_OFFSET] = settings.timeZone.encodeOffset()
                    payload[BleConstants.Settings.INDEX_BACKLIGHT_DUR] = settings.backlightDuration.toByte()
                    payload[BleConstants.Settings.INDEX_PACKED_BRIGHTNESS] = (((settings.screenBrightness / 10).coerceIn(0, 15) shl 4) or (settings.nightModeBrightness / 10).coerceIn(0, 15)).toByte()
                    if (settings.nightModeEnabled) {
                        payload[BleConstants.Settings.INDEX_NIGHT_START_H] = settings.nightStartHour.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_START_M] = settings.nightStartMinute.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_END_H] = settings.nightEndHour.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_END_M] = settings.nightEndMinute.toByte()
                    } else {
                        payload[BleConstants.Settings.INDEX_NIGHT_START_H] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_START_M] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_END_H] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_END_M] = 1
                    }
                    payload[BleConstants.Settings.INDEX_TZ_SIGN] = settings.timeZone.encodeOffsetSign()
                    payload[BleConstants.Settings.INDEX_NIGHT_MODE_EN] = (if (settings.nightModeEnabled) 1 else 0).toByte()
                    val sig = settings.ringtoneSignature
                    if (sig.size >= 4) {
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG] = sig[0]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 1] = sig[1]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 2] = sig[2]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 3] = sig[3]
                    }
                    val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                    if (dataWriteChar == null) {
                        pendingAckContinuations.remove(Command.SET_SETTINGS)
                        continuation.resumeWithException(Exception("Data write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }
                    scope.launch {
                        if (!writeCharacteristicWithRetry(dataWriteChar, payload)) {
                            pendingAckContinuations.remove(Command.SET_SETTINGS)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for settings"))
                        }
                    }
                }
            }
        }
    }

    override fun readRssi() {
        try { gatt?.readRemoteRssi() } catch (_: Exception) {}
    }

    override fun enqueueCommand(command: suspend () -> Unit) {
        commandChannel.trySend(command)
    }

    override suspend fun readAlarms(): List<Alarm> = gattMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val currentGatt = gatt ?: run {
                continuation.resumeWithException(Exception("GATT not connected"))
                return@suspendCancellableCoroutine
            }
            if (!isAuthenticated) {
                continuation.resumeWithException(Exception("Not authenticated"))
                return@suspendCancellableCoroutine
            }
            alarmReadContinuation = continuation
            alarmBuffer.clear()
            val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_NOTIFY) != null }
            val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
            val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
            if (dataNotifyChar == null || dataWriteChar == null) {
                alarmReadContinuation?.resumeWithException(Exception("Data characteristics not found"))
                alarmReadContinuation = null
                return@suspendCancellableCoroutine
            }
            val command = byteArrayOf(Header.GET_DATA, Command.GET_ALARMS.toByte())
            if (enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                currentGatt.writeCharacteristic(dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                pendingDataCommand = command
                descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                enabledNotifications.add(UUID_DATA_NOTIFY)
            }
            continuation.invokeOnCancellation {
                alarmReadContinuation = null
                alarmBuffer.clear()
                pendingDataCommand = null
            }
        }
    }

    override suspend fun setAlarm(hour: Int, minute: Int, alarmId: Int, enable: Boolean, days: Int, snooze: Boolean): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }
                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }
                    pendingAckContinuations[Command.SET_ALARM] = continuation
                    val command = byteArrayOf(Header.SET_ALARM, Command.SET_ALARM.toByte(), alarmId.toByte(), if (enable) 0x01.toByte() else 0x00.toByte(), hour.toByte(), minute.toByte(), days.toByte(), if (snooze) 0x01.toByte() else 0x00.toByte())
                    val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                    val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
                    if (dataWriteChar == null || dataNotifyChar == null) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }
                    if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                        currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                        val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                        enabledNotifications.add(UUID_DATA_NOTIFY)
                    }
                    currentGatt.writeCharacteristic(dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }
            }
        }
    }

    override suspend fun deleteAlarm(alarmId: Int): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }
                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }
                    pendingAckContinuations[Command.SET_ALARM] = continuation
                    val command = byteArrayOf(Header.SET_ALARM, Command.SET_ALARM.toByte(), alarmId.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
                    val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                    val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
                    if (dataWriteChar == null || dataNotifyChar == null) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }
                    if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                        currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                        val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
                        enabledNotifications.add(UUID_DATA_NOTIFY)
                    }
                    currentGatt.writeCharacteristic(dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                }
            }
        }
    }

    override suspend fun previewBrightness(brightness: Int): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            suspendCancellableCoroutine { continuation ->
                val currentGatt = gatt ?: run {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                if (!isAuthenticated) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                val value = (brightness / 10).coerceIn(0, 10).toByte()
                val command = byteArrayOf(Header.BRIGHTNESS, Command.PREVIEW_BRIGHTNESS.toByte(), value)
                val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                if (dataWriteChar == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }
                pendingAckContinuations[Command.PREVIEW_BRIGHTNESS] = continuation
                scope.launch {
                    if (!writeCharacteristicWithRetry(dataWriteChar, command)) {
                        pendingAckContinuations.remove(Command.PREVIEW_BRIGHTNESS)
                        continuation.resume(false)
                    }
                }
            }
        }
    }

    override suspend fun previewRingtone(settings: DeviceSettings?): Boolean {
        return gattMutex.withLock {
            suspendCancellableCoroutine { continuation ->
                val currentGatt = gatt ?: run {
                    continuation.resumeWithException(Exception("GATT not connected"))
                    return@suspendCancellableCoroutine
                }
                if (!isAuthenticated) {
                    continuation.resumeWithException(Exception("Not authenticated"))
                    return@suspendCancellableCoroutine
                }
                
                val command = if (settings != null) {
                    byteArrayOf(Header.RINGTONE_V2, Command.PREVIEW_RINGTONE.toByte(), settings.volume.toByte())
                } else {
                    byteArrayOf(Header.RINGTONE_V1, Command.PREVIEW_RINGTONE.toByte())
                }
                
                val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                if (dataWriteChar == null) {
                    continuation.resumeWithException(Exception("Data write characteristic not found"))
                    return@suspendCancellableCoroutine
                }
                pendingAckContinuations[Command.PREVIEW_RINGTONE] = continuation
                scope.launch {
                    if (!writeCharacteristicWithRetry(dataWriteChar, command)) {
                        pendingAckContinuations.remove(Command.PREVIEW_RINGTONE)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for command"))
                    }
                }
            }
        }
    }

    override suspend fun uploadAudio(audioData: ByteArray, signature: ByteArray, onProgress: (Float) -> Unit): Boolean {
        // Renamed from uploadRingtone
        val currentGatt = gatt ?: return false
        if (!isAuthenticated) return false
        val dataService = currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
        val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
        if (dataWriteChar == null || dataNotifyChar == null) return false

        if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
            currentGatt.setCharacteristicNotification(dataNotifyChar, true)
            val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            delay(DELAY_ALARM_RELOAD)
            enabledNotifications.add(UUID_DATA_NOTIFY)
        }

        // Send Init
        val targetSignature = signature
        val sizeBytes = audioData.size
        val initPayload = byteArrayOf(Header.AUDIO_INIT, Command.AUDIO_INIT.toByte(), (sizeBytes and 0xFF).toByte(), ((sizeBytes shr 8) and 0xFF).toByte(), ((sizeBytes shr 16) and 0xFF).toByte(), targetSignature[0], targetSignature[1], targetSignature[2], targetSignature[3])
        
        uploadInitAckReceived = false
        if (!writeCharAndWait(dataWriteChar, initPayload)) return false
        repeat(AUDIO_INIT_ACK_WAIT_ITERATIONS) { if (!uploadInitAckReceived) delay(AUDIO_ACK_WAIT_DELAY) }
        if (!uploadInitAckReceived) return false

        val packetSize = AUDIO_PACKET_SIZE
        val packetsPerBlock = AUDIO_PACKETS_PER_BLOCK
        var offset = 0
        while (offset < audioData.size) {
            for (pktIdx in 0 until packetsPerBlock) {
                if (offset >= audioData.size) break
                val remaining = audioData.size - offset
                val audioLen = minOf(packetSize, remaining)
                val audioChunk = audioData.copyOfRange(offset, offset + audioLen)
                val paddedAudio = if (audioChunk.size < packetSize) audioChunk + ByteArray(packetSize - audioChunk.size) { 0xFF.toByte() } else audioChunk
                val packet = byteArrayOf(Header.AUDIO_PACKET, Command.AUDIO_BLOCK.toByte()) + paddedAudio
                val isLastInBlock = (pktIdx == packetsPerBlock - 1) || (offset + audioLen >= audioData.size)

                if (isLastInBlock) {
                    uploadAckReceived = false
                    writeCharAndWait(dataWriteChar, packet)
                    repeat(AUDIO_ACK_WAIT_ITERATIONS) { if (!uploadAckReceived) delay(AUDIO_ACK_WAIT_DELAY) }
                } else {
                    writeCharAndWait(dataWriteChar, packet)
                    delay(DELAY_PACKET_WRITE)
                }
                offset += audioLen
            }
            onProgress(minOf(1.0f, offset.toFloat() / audioData.size))
        }
        return true
    }

    override suspend fun stopAudioPreview(): Boolean = true

    override fun disconnect() {
        clearConnectionState()
        deviceJob.cancelChildren()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    override fun isDevicePaired(macAddress: String): Boolean = tokenStorage.isPaired(macAddress)

    override fun close() {
        disconnect()
        commandChannel.close()
        commandConsumerJob.cancel()
        deviceJob.cancel()
    }

    private suspend fun writeCharAndWait(characteristic: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val currentGatt = gatt ?: return false
        val deferred = CompletableDeferred<Boolean>()
        writeCompleteDeferred = deferred
        if (currentGatt.writeCharacteristic(characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) != android.bluetooth.BluetoothStatusCodes.SUCCESS) {
            writeCompleteDeferred = null
            return false
        }
        return try { withTimeout(TIMEOUT_OPERATION) { deferred.await() } } catch (_: Exception) { false } finally { writeCompleteDeferred = null }
    }

    private suspend fun writeCharacteristicWithRetry(characteristic: BluetoothGattCharacteristic, value: ByteArray, retryCount: Int = 3): Boolean {
        val currentGatt = gatt ?: return false
        repeat(retryCount) { attempt ->
            if (currentGatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS) return true
            delay(100 * (attempt + 1).toLong())
        }
        return false
    }

    private fun handleUploadAck(value: ByteArray) {
        if (value.size >= 3 && value[0] == Header.ACK[0] && value[1] == Header.ACK[1]) {
            val cmdId = value[2].toInt() and 0xFF
            when (cmdId) {
                Command.AUDIO_INIT -> uploadInitAckReceived = true
                Command.AUDIO_BLOCK -> uploadAckReceived = true
            }
        }
    }

    private var uploadAckReceived = false
    private var uploadInitAckReceived = false

    private fun buildAuthInitPacket(): ByteArray = byteArrayOf(Header.AUTH, Command.AUTH_INIT.toByte()) + (currentToken ?: throw IllegalStateException("No token"))
    private fun buildAuthConfirmPacket(): ByteArray = byteArrayOf(Header.AUTH, Command.AUTH_CONFIRM.toByte()) + (currentToken ?: throw IllegalStateException("No token"))

    private fun prepareTokenForDevice(macAddress: String): ByteArray {
        val existingToken = tokenStorage.getToken(macAddress)
        return if (existingToken != null) {
            isPendingPairing = false
            existingToken
        } else {
            isPendingPairing = true
            tokenStorage.generateToken()
        }
    }

    private suspend fun connect(device: BluetoothDevice): Boolean = suspendCancellableCoroutine { continuation ->
        connectContinuation = continuation
        gatt = device.connectGatt(context, false, gattCallback)
        continuation.invokeOnCancellation { connectContinuation = null }
    }

    private suspend fun enableSensorNotifications(): Boolean = gattMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val currentGatt = gatt ?: run {
                continuation.resumeWithException(Exception("GATT not connected"))
                return@suspendCancellableCoroutine
            }
            if (!isAuthenticated) {
                continuation.resumeWithException(Exception("Not authenticated"))
                return@suspendCancellableCoroutine
            }
            sensorNotificationContinuation = continuation
            val sensorService = currentGatt.services.find { it.getCharacteristic(UUID_SENSOR_NOTIFY) != null }
            val sensorNotifyChar = sensorService?.getCharacteristic(UUID_SENSOR_NOTIFY)
            if (sensorNotifyChar == null) {
                sensorNotificationContinuation?.resumeWithException(Exception("Sensor characteristic not found"))
                sensorNotificationContinuation = null
                return@suspendCancellableCoroutine
            }
            currentGatt.setCharacteristicNotification(sensorNotifyChar, true)
            val descriptor = sensorNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.let { currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            continuation.invokeOnCancellation { sensorNotificationContinuation = null }
        }
    }
}
