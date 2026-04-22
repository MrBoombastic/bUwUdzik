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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
 *
 * Note: All Bluetooth operations require BLUETOOTH_CONNECT permission.
 * Permission is checked at the UI layer before any operations are performed.
 */
@SuppressLint("MissingPermission")
class QPController(private val context: Context) {

    // Separate job for command consumer that won't be canceled on disconnect
    private val commandConsumerJob = SupervisorJob()
    private val commandConsumerScope = CoroutineScope(Dispatchers.Default + commandConsumerJob)
    
    // Job for device-specific operations that can be canceled on disconnect
    private val deviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + deviceJob)
    
    private val gattMutex = Mutex()
    private val commandChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    init {
        // Launch command consumer on a separate scope that won't be canceled
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
        private const val TAG = "QPController"

        // Timeout constants (in milliseconds)
        private const val TIMEOUT_AUTHENTICATION = 30000L
        private const val TIMEOUT_OPERATION = 5000L

        // Delay constants (in milliseconds)
        const val DELAY_POST_AUTH = 500L
        const val DELAY_BLE_OPERATION = 200L
        const val DELAY_ALARM_RELOAD = 300L
        const val DELAY_ALARM_COMPLETION = 1000L
        const val DELAY_RSSI_POLL = 5000L
        const val DELAY_PACKET_WRITE = 20L

        // Audio upload constants
        private const val AUDIO_PACKET_SIZE = 128
        private const val AUDIO_PACKETS_PER_BLOCK = 4
        private const val AUDIO_ACK_WAIT_ITERATIONS = 50
        private const val AUDIO_INIT_ACK_WAIT_ITERATIONS = 20
        private const val AUDIO_ACK_WAIT_DELAY = 100L

        const val MOCK_MAC = "DE:AD:BE:EF:CA:FE"
    }

    // Token storage for persistence
    private val tokenStorage = TokenStorage(context)

    // Mock state for demo device (only used if currentDeviceMac == MOCK_MAC)
    private val mockAlarms = mutableListOf<Alarm>().apply {
        add(Alarm(0, true, 7, 30, 0x1F, false))
        add(Alarm(1, false, 9, 0, 0x60, true))
    }
    private var mockSettings = DeviceSettings(
        volume = 3,
        backlightDuration = 10,
        screenBrightness = 50,
        nightModeBrightness = 10,
        nightModeEnabled = true,
        firmwareVersion = "1.0.0-DEMO"
    )

    fun setupMockState(
        temperature: Double,
        humidity: Double,
        alarmCount: Int
    ) {
        mockAlarms.clear()
        mockAlarms.addAll(List(alarmCount) { idx ->
            Alarm(
                id = idx,
                enabled = idx % 2 == 0,
                hour = 6 + idx * 2,
                minute = idx * 15 % 60,
                days = if (idx == 0) 0 else 0x1F, // once / weekdays
                snooze = idx == 1,
                title = if (idx == 0) "Wake up" else ""
            )
        })
        mockSensorTemp = temperature
        mockSensorHum = humidity
    }
    
    private var mockSensorTemp = 22.0
    private var mockSensorHum = 45.0

    // Current device being connected to
    private var currentDeviceMac: String? = null
    private var currentToken: ByteArray? = null

    // Build auth packets dynamically from the current token
    private fun buildAuthInitPacket(): ByteArray {
        val token = currentToken ?: throw IllegalStateException("No token set")
        return byteArrayOf(Header.AUTH, Command.AUTH_INIT.toByte()) + token
    }

    private fun buildAuthConfirmPacket(): ByteArray {
        val token = currentToken ?: throw IllegalStateException("No token set")
        return byteArrayOf(Header.AUTH, Command.AUTH_CONFIRM.toByte()) + token
    }

    /**
     * True when this app has completed auth with the clock (stored token). These devices are not
     * Android-bonded; the pairing state is app-local only.
     */
    fun isDevicePaired(macAddress: String): Boolean = 
        macAddress == MOCK_MAC || tokenStorage.isPaired(macAddress)

    /**
     * Prepare token for connection. If a device is already paired, use a stored token.
     * Otherwise, generate a new random token for pairing (without storing yet).
     */
    private fun prepareTokenForDevice(macAddress: String): ByteArray {
        if (macAddress == MOCK_MAC) return byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val existingToken = tokenStorage.getToken(macAddress)
        return if (existingToken != null) {
            AppLogger.d(TAG, "Using stored token for $macAddress")
            isPendingPairing = false
            existingToken
        } else {
            AppLogger.d(TAG, "Generating new token for $macAddress (fresh pairing)")
            isPendingPairing = true // Mark that we need to store token after successful auth
            tokenStorage.generateToken() // Generate but don't store yet
        }
    }

    private var gatt: BluetoothGatt? = null
    private var isAuthenticated = false
    private var isConnected = false
    private var isPendingPairing = false // Track if we need to store token after successful auth

    // Pending operations
    private var connectContinuation: Continuation<Boolean>? = null
    private val pendingAckContinuations = mutableMapOf<Int, Continuation<Boolean>>()
    private var alarmReadContinuation: Continuation<List<Alarm>>? = null
    private var deviceSettingsReadContinuation: Continuation<DeviceSettings>? = null
    private var firmwareVersionReadContinuation: Continuation<String>? = null

    private val alarmBuffer = mutableListOf<Alarm>()
    private var alarmCompletionJob: kotlinx.coroutines.Job? = null
    private var sensorNotificationContinuation: Continuation<Boolean>? = null
    private var lastSettingsPacket: ByteArray? = null
    private var pendingAuthWriteChar: BluetoothGattCharacteristic? = null // For two-step auth
    private var authInitAckReceived = false // Set when 04 ff 01 received, cleared after 11 02 sent

    // Track which characteristic we're waiting for descriptor write
    private var pendingAuthWrite: BluetoothGattCharacteristic? = null
    private var pendingDataCommand: ByteArray? = null

    // Track which notifications are already enabled to avoid GATT_BUSY errors
    private val enabledNotifications = mutableSetOf<UUID>()

    // Write completion for audio upload - using CompletableDeferred for thread safety
    private var writeCompleteDeferred: CompletableDeferred<Boolean>? = null

    /**
     * Live temperature / humidity from GATT **sensor notify** (short binary packet; no battery).
     * Battery % still comes from BLE **advertising** parsed in [com.mrboombastic.buwudzik.device.BluetoothScanner]
     * and cached by [com.mrboombastic.buwudzik.data.SensorRepository].
     */
    var onSensorData: ((temperature: Float, humidity: Float) -> Unit)? = null
    var onRssiUpdate: ((rssi: Int) -> Unit)? = null
    var onLastUpdated: ((timestamp: Long) -> Unit)? = null

    // Disconnection event with reason
    private val _disconnectionEvent = MutableStateFlow<DisconnectionReason?>(null)
    val disconnectionEvent = _disconnectionEvent.asStateFlow()

    /**
     * Clear the disconnection event after handling it
     */
    fun clearDisconnectionEvent() {
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

        // Emit disconnection event with reason if status is provided
        status?.let {
            _disconnectionEvent.value = DisconnectionReason.fromGattStatus(it)
        }

        // Handle continuations
        val error = Exception("Disconnected")

        connectContinuation?.let {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
            connectContinuation = null
        }

        val acks = pendingAckContinuations.values.toList()
        pendingAckContinuations.clear()
        acks.forEach {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
        }

        alarmReadContinuation?.let {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
            alarmReadContinuation = null
        }

        deviceSettingsReadContinuation?.let {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
            deviceSettingsReadContinuation = null
        }

        firmwareVersionReadContinuation?.let {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
            firmwareVersionReadContinuation = null
        }

        sensorNotificationContinuation?.let {
            try {
                it.resumeWithException(error)
            } catch (_: Exception) {
            }
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
        AppLogger.d(
            TAG,
            "Received ACK for command '$cmdName' (ID: ${cmdId.toHexString()}). Status: ${status.toHexString()}"
        )

        // Handle audio upload ACKs
        if (cmdId == Command.AUDIO_BLOCK || cmdId == Command.AUDIO_INIT) {
            handleUploadAck(value)
        }

        if (status == Status.SUCCESS || status == Status.ALARM_STILL_SUCCESS || (cmdId == Command.AUTH_INIT && status == Status.AUTH_INIT_SUCCESS)) {
            // CMD_AUTH_INIT success, mark that we need to send Auth Confirm
            if (cmdId == Command.AUTH_INIT) {
                AppLogger.d(
                    TAG, "Auth Init ACK received, will send Auth Confirm after write completes"
                )
                authInitAckReceived = true
                // Don't write here - wait for onCharacteristicWrite callback
            } else if (cmdId == Command.AUTH_CONFIRM) {
                AppLogger.d(
                    TAG,
                    "Authentication seems successful, but syncing time will tell the truth"
                )
                isAuthenticated = true
                pendingAuthWriteChar = null
                // Store token only after successful authentication
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
            val errorSuffix = if (status == Status.AUTH_INIT_SUCCESS) {
                " $authHint"
            } else ""
            AppLogger.e(
                TAG,
                "[$characteristicUuid] $cmdName failed with status $status$errorSuffix (Full: ${value.toHexString()})"
            )
            pendingAckContinuations.remove(cmdId)
                ?.resumeWithException(Exception("$cmdName failed: $status$errorSuffix"))
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray
        ) {
            AppLogger.d(
                TAG, "onCharacteristicChanged ${characteristic.uuid}: ${value.toHexString()}"
            )

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
                    // Check for audio upload ACKs first (04 ff 08/10 XX)
                    if (value.size >= 3 && value[0] == Header.ACK[0] && value[1] == Header.ACK[1]) {
                        val cmdId = value[2].toInt() and 0xFF
                        // Handle audio upload ACKs
                        if (cmdId == Command.AUDIO_BLOCK || cmdId == Command.AUDIO_INIT) {
                            handleUploadAck(value)
                        }
                        // Also handle as regular ACK if size >= 4
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
                                AppLogger.d(
                                    TAG,
                                    "Parsed alarm #$currentIndex: ${alarm.getTimeString()} enabled=$enabled days=$days"
                                )
                            } else {
                                AppLogger.d(TAG, "Empty alarm slot #$currentIndex")
                            }

                            highestIndexSeen = currentIndex
                            offset += BleConstants.Alarm.ENTRY_LENGTH
                            currentIndex++
                        }

                        if (highestIndexSeen >= BleConstants.Alarm.TOTAL_SLOTS - 1) {
                            AppLogger.d(
                                TAG,
                                "Received all 16 alarm slots (up to index 15), returning ${alarmBuffer.size} alarms"
                            )
                            alarmReadContinuation?.resume(alarmBuffer.toList())
                            alarmReadContinuation = null
                            alarmBuffer.clear()
                            alarmCompletionJob?.cancel()
                            alarmCompletionJob = null
                        } else {
                            alarmCompletionJob?.cancel()
                            alarmCompletionJob = scope.launch {
                                delay(DELAY_ALARM_COMPLETION)
                                AppLogger.d(
                                    TAG,
                                    "Timeout waiting for more packets, returning ${alarmBuffer.size} alarms"
                                )
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
                            val tzOffset =
                                value[BleConstants.Settings.INDEX_TZ_OFFSET].toInt() and 0xFF
                            val duration =
                                value[BleConstants.Settings.INDEX_BACKLIGHT_DUR].toInt() and 0xFF
                            val packedBrightness =
                                value[BleConstants.Settings.INDEX_PACKED_BRIGHTNESS].toInt() and 0xFF
                            val screenBri = (packedBrightness shr 4) * 10
                            val nightBri = (packedBrightness and 0x0F) * 10
                            val tzSign = value[BleConstants.Settings.INDEX_TZ_SIGN].toInt() == 1
                            val nightModeEnabled =
                                value[BleConstants.Settings.INDEX_NIGHT_MODE_EN].toInt() == 1

                            // Parse ringtone signature from bytes 16-19
                            val ringtoneSig =
                                if (value.size >= BleConstants.Settings.INDEX_RINGTONE_SIG + 4) {
                                    byteArrayOf(
                                        value[BleConstants.Settings.INDEX_RINGTONE_SIG],
                                        value[BleConstants.Settings.INDEX_RINGTONE_SIG + 1],
                                        value[BleConstants.Settings.INDEX_RINGTONE_SIG + 2],
                                        value[BleConstants.Settings.INDEX_RINGTONE_SIG + 3]
                                    )
                            } else {
                                byteArrayOf(
                                    0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
                                )
                            }

                            val settings = DeviceSettings(
                                tempUnit = if (flags and Flags.TEMP_UNIT_F != 0) TempUnit.Fahrenheit else TempUnit.Celsius,
                                timeFormat = if (flags and Flags.TIME_FORMAT_12H != 0) TimeFormat.H12 else TimeFormat.H24,
                                language = if (flags and Flags.LANG_ENGLISH != 0) Language.English else Language.Chinese,
                                volume = volume,
                                timeZone = createTimeZone(tzOffset,tzSign),
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
                    } else {
                        AppLogger.d(TAG, "Unhandled data packet: ${value.toHexString()}")
                    }
                }

                UUID_SENSOR_NOTIFY -> {
                    if (value.size >= 5 && value[0] == Header.SENSOR_DATA) {
                        val tempRaw =
                            (value[2].toInt() and 0xFF shl 8) or (value[1].toInt() and 0xFF)
                        val humRaw =
                            (value[4].toInt() and 0xFF shl 8) or (value[3].toInt() and 0xFF)

                        val temperature = tempRaw / 100.0f
                        val humidity = humRaw / 100.0f

                        AppLogger.d(TAG, "Sensor data: Temp=$temperature °C, Hum=$humidity %")
                        onSensorData?.invoke(temperature, humidity)
                        onLastUpdated?.invoke(System.currentTimeMillis())
                    } else {
                        AppLogger.w(TAG, "Invalid sensor data packet: ${value.toHexString()}")
                    }
                }

            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int
        ) {
            AppLogger.d(TAG, "onCharacteristicWrite ${characteristic?.uuid} status=$status")
            val deferred = writeCompleteDeferred
            writeCompleteDeferred = null

            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Write failed for ${characteristic?.uuid} with status $status")
                deferred?.complete(false)
                return
            }
            deferred?.complete(true)

            // Check if we need to send Auth Confirm after Auth Init write completes
            if (authInitAckReceived && characteristic?.uuid == UUID_AUTH_WRITE) {
                authInitAckReceived = false // Clear flag
                AppLogger.d(
                    TAG, "Auth Init write complete, now sending Auth Confirm (11 02)..."
                )
                pendingAuthWriteChar?.let { char ->
                    gatt?.writeCharacteristic(
                        char,
                        buildAuthConfirmPacket(),
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int
        ) {
            AppLogger.d(
                TAG, "onDescriptorWrite status=$status for ${descriptor?.characteristic?.uuid}"
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                AppLogger.e(TAG, "Enable notification failed: $status")
                when (descriptor?.characteristic?.uuid) {
                    UUID_AUTH_NOTIFY -> {
                        pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                            ?.resumeWithException(Exception("Enable auth notification failed: $status"))
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
                        AppLogger.d(
                            TAG, "Descriptor write complete, sending Auth Init (11 01)..."
                        )
                        pendingAuthWriteChar = char // Save for the second step
                        gatt?.writeCharacteristic(
                            char,
                            buildAuthInitPacket(),
                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                        pendingAuthWrite = null
                    }
                }

                UUID_DATA_NOTIFY -> {
                    pendingDataCommand?.let { cmd ->
                        AppLogger.d(
                            TAG,
                            "Descriptor write complete, now sending data command: ${cmd.toHexString()}..."
                        )
                        val dataService =
                            gatt?.services?.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                        dataWriteChar?.let { char ->
                            gatt.writeCharacteristic(
                                char, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                            )
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
            } else {
                AppLogger.w(TAG, "Failed to read RSSI, status: $status")
            }
        }
    }

    suspend fun connectMockDevice(mac: String): Boolean {
        currentDeviceMac = mac
        isAuthenticated = true
        startMockSensorLoop()
        return true
    }

    @Suppress("SameReturnValue")
    suspend fun connectAndAuthenticate(device: BluetoothDevice): Boolean {
        // Prepare a token for this device (generate new if fresh pairing, use stored if already paired)
        val macAddress = device.address
        currentDeviceMac = macAddress

        if (macAddress == MOCK_MAC) {
            AppLogger.d(TAG, "Mocking connection to demo device...")
            delay(1000) // Simulate connection delay
            isConnected = true
            isAuthenticated = true
            return true
        }

        currentToken = prepareTokenForDevice(macAddress)
        AppLogger.d(TAG, "Token prepared for $macAddress: ${currentToken?.toHexString()}")

        connect(device)

        if (!isAuthenticated) {
            try {
                withTimeout(TIMEOUT_AUTHENTICATION) {
                    authenticate()
                }
                delay(DELAY_POST_AUTH) // Brief pause after auth to ensure stability
            } catch (e: Exception) {
                AppLogger.e(TAG, "Authentication failed or timed out", e)
                throw e // Propagate detailed error
            }
        }

        try {
            withTimeout(TIMEOUT_AUTHENTICATION) {
                synchronizeTime()
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Time synchronization failed or timed out", e)
            throw e // Stricter handling: failure during time sync is fatal
        }

        try {
            enableSensorNotifications()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to enable sensor notifications", e)
            throw e // Propagate sensor notification failure
        }

        return true
    }

    fun readRssi() {
        if (currentDeviceMac == MOCK_MAC) {
            onRssiUpdate?.invoke(-40 - (0..10).random())
            return
        }
        try {
            if (gatt?.readRemoteRssi() == false) {
                AppLogger.w(TAG, "Failed to start RSSI read")
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "RSSI read exception", e)
        }
    }

    private suspend fun connect(device: BluetoothDevice): Boolean =
        suspendCancellableCoroutine { continuation ->
            AppLogger.d(TAG, "Connecting to device: ${device.address}")
            connectContinuation = continuation

            gatt = device.connectGatt(context, false, gattCallback)

            continuation.invokeOnCancellation {
                connectContinuation = null
            }
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

                val authService =
                    currentGatt.services.find { it.getCharacteristic(UUID_AUTH_NOTIFY) != null }
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
                AppLogger.d(TAG, "Enabling auth notifications...")

                descriptor?.let {
                    val status = currentGatt.writeDescriptor(
                        it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                        continuation.resumeWithException(Exception("writeDescriptor failed for auth: $status"))
                        pendingAuthWrite = null
                    }
                } ?: run {
                    pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                    continuation.resumeWithException(Exception("Auth descriptor not found"))
                    pendingAuthWrite = null
                }

                continuation.invokeOnCancellation {
                    pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                    pendingAuthWrite = null
                }
            }
        }
    }

    suspend fun synchronizeTime(timestamp: Long = System.currentTimeMillis() / 1000): Boolean {
        if (currentDeviceMac == MOCK_MAC) {
            val date = java.util.Date(timestamp * 1000)
            AppLogger.d(TAG, "[MOCK] Synchronizing time to: $date (Unix: $timestamp)")
            return true
        }
        return gattMutex.withLock {
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

                    val date = java.util.Date(timestamp * 1000)
                    AppLogger.d(TAG, "Synchronizing time to: $date (Unix: $timestamp)")
                    pendingAckContinuations[Command.TIME_SYNC] = continuation

                    val command = byteArrayOf(
                        Header.TIME,
                        Command.TIME_SYNC.toByte(),
                        (timestamp and 0xFF).toByte(),
                        ((timestamp shr 8) and 0xFF).toByte(),
                        ((timestamp shr 16) and 0xFF).toByte(),
                        ((timestamp shr 24) and 0xFF).toByte()
                    )

                    val authService =
                        currentGatt.services.find { it.getCharacteristic(UUID_AUTH_WRITE) != null }
                    val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)

                    if (authWriteChar == null) {
                        pendingAckContinuations.remove(Command.TIME_SYNC)
                        continuation.resumeWithException(Exception("Auth write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }

                    val status = currentGatt.writeCharacteristic(
                        authWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(Command.TIME_SYNC)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for time sync: $status"))
                    }

                    continuation.invokeOnCancellation {
                        pendingAckContinuations.remove(Command.TIME_SYNC)
                    }
                }
            }
        }
    }

    suspend fun readDeviceSettings(): DeviceSettings {
        if (currentDeviceMac == MOCK_MAC) return mockSettings
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

                AppLogger.d(TAG, "Reading device settings...")
                deviceSettingsReadContinuation = continuation

                val dataService =
                    currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)

                if (dataWriteChar == null || dataNotifyChar == null) {
                    continuation.resumeWithException(Exception("Data characteristics not found"))
                    return@suspendCancellableCoroutine
                }

                val command = byteArrayOf(Header.GET_DATA, Command.GET_SETTINGS.toByte())

                // Check if notifications already enabled
                if (enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                    AppLogger.d(TAG, "Data notifications already enabled, sending command directly...")
                    val status = currentGatt.writeCharacteristic(
                        dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        deviceSettingsReadContinuation?.resumeWithException(Exception("writeCharacteristic failed for settings: $status"))
                        deviceSettingsReadContinuation = null
                    }
                } else {
                    // Need to enable notifications first
                    currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                    val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)

                    pendingDataCommand = command
                    AppLogger.d(TAG, "Enabling data notifications for settings...")

                    descriptor?.let {
                        val status = currentGatt.writeDescriptor(
                            it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            deviceSettingsReadContinuation?.resumeWithException(Exception("writeDescriptor failed for settings: $status"))
                            deviceSettingsReadContinuation = null
                            pendingDataCommand = null
                        } else {
                            enabledNotifications.add(UUID_DATA_NOTIFY)
                        }
                    } ?: run {
                        deviceSettingsReadContinuation?.resumeWithException(Exception("Data descriptor not found"))
                        deviceSettingsReadContinuation = null
                        pendingDataCommand = null
                    }
                }

                continuation.invokeOnCancellation {
                    deviceSettingsReadContinuation = null
                    pendingDataCommand = null
                }
            }
        }
    }

    suspend fun readFirmwareVersion(): String {
        if (currentDeviceMac == MOCK_MAC) return "1.0.0-DEMO"
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

                AppLogger.d(TAG, "Reading firmware version...")
                firmwareVersionReadContinuation = continuation

                val authService =
                    currentGatt.services.find { it.getCharacteristic(UUID_AUTH_WRITE) != null }
                val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)

                if (authWriteChar == null) {
                    firmwareVersionReadContinuation = null
                    continuation.resumeWithException(Exception("Auth write characteristic not found"))
                    return@suspendCancellableCoroutine
                }

                val command = byteArrayOf(Header.GET_DATA, Command.GET_FIRMWARE.toByte())
                val status = currentGatt.writeCharacteristic(
                    authWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    firmwareVersionReadContinuation = null
                    continuation.resumeWithException(Exception("writeCharacteristic failed for firmware read: $status"))
                }

                continuation.invokeOnCancellation {
                    firmwareVersionReadContinuation = null
                }
            }
        }
    }

    suspend fun writeDeviceSettings(settings: DeviceSettings): Boolean = gattMutex.withLock {
        writeDeviceSettingsInternal(settings)
    }


    private suspend fun writeCharacteristicWithRetry(
        characteristic: BluetoothGattCharacteristic, value: ByteArray, retryCount: Int = 3
    ): Boolean {
        val currentGatt = gatt ?: return false

        repeat(retryCount) { attempt ->
            val result = currentGatt.writeCharacteristic(
                characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (result == BluetoothGatt.GATT_SUCCESS) return true

            AppLogger.w(
                TAG,
                "writeCharacteristic failed (attempt ${attempt + 1}/$retryCount) with status $result, retrying..."
            )
            delay(100 * (attempt + 1).toLong())
        }
        return false
    }

    private suspend fun writeDeviceSettingsInternal(settings: DeviceSettings): Boolean =
        withContext(NonCancellable) {
            if (currentDeviceMac == MOCK_MAC) {
                mockSettings = settings
                return@withContext true
            }
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

                    // Use the last packet as a template to preserve unknown/header bytes, fallback to defaults
                    val payload = lastSettingsPacket?.copyOf() ?: ByteArray(20).apply {
                        this[0] = Header.SET_SETTINGS
                        this[3] = Header.SETTINGS_FIXED_BYTE_3
                        this[4] = Header.SETTINGS_FIXED_BYTE_4
                    }

                    // Always ensure Header and Sub-command are correct for Set Settings
                    payload[0] = Header.SET_SETTINGS
                    payload[1] = Command.SET_SETTINGS.toByte()

                    // Update Volume
                    payload[BleConstants.Settings.INDEX_VOLUME] =
                        settings.volume.coerceIn(1, 5).toByte()

                    // Update Flags bit by bit to preserve unknown bits
                    var flags =
                        payload.getOrNull(BleConstants.Settings.INDEX_FLAGS)?.toInt()?.and(0xFF)
                            ?: 0
                    flags =
                        if (settings.language == Language.English) flags or Flags.LANG_ENGLISH else flags and Flags.LANG_ENGLISH.inv()
                    flags =
                        if (settings.timeFormat == TimeFormat.H12) flags or Flags.TIME_FORMAT_12H else flags and Flags.TIME_FORMAT_12H.inv()
                    flags =
                        if (settings.tempUnit == TempUnit.Fahrenheit) flags or Flags.TEMP_UNIT_F else flags and Flags.TEMP_UNIT_F.inv()
                    flags =
                        if (settings.masterAlarmDisabled) flags or Flags.MASTER_ALARM_DISABLE else flags and Flags.MASTER_ALARM_DISABLE.inv()
                    payload[BleConstants.Settings.INDEX_FLAGS] = flags.toByte()

                    // Update Timezone, Duration and Packed Brightness
                    payload[BleConstants.Settings.INDEX_TZ_OFFSET] =
                        settings.timeZone.encodeOffset()
                    payload[BleConstants.Settings.INDEX_BACKLIGHT_DUR] =
                        settings.backlightDuration.toByte()
                    payload[BleConstants.Settings.INDEX_PACKED_BRIGHTNESS] =
                        (((settings.screenBrightness / 10).coerceIn(
                        0, 15
                    ) shl 4) or (settings.nightModeBrightness / 10).coerceIn(0, 15)).toByte()

                    // Night Mode Schedule
                    if (settings.nightModeEnabled) {
                        payload[BleConstants.Settings.INDEX_NIGHT_START_H] =
                            settings.nightStartHour.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_START_M] =
                            settings.nightStartMinute.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_END_H] =
                            settings.nightEndHour.toByte()
                        payload[BleConstants.Settings.INDEX_NIGHT_END_M] =
                            settings.nightEndMinute.toByte()
                    } else {
                        // Fix: Hardware often ignores the enabled bit, so set a minimal 1-min window
                        AppLogger.d(TAG, "Night Mode disabled: forcing schedule to 00:00 - 00:01")
                        payload[BleConstants.Settings.INDEX_NIGHT_START_H] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_START_M] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_END_H] = 0
                        payload[BleConstants.Settings.INDEX_NIGHT_END_M] = 1
                    }

                    // Metadata bits
                    payload[BleConstants.Settings.INDEX_TZ_SIGN] =
                        settings.timeZone.encodeOffsetSign()
                    payload[BleConstants.Settings.INDEX_NIGHT_MODE_EN] =
                        (if (settings.nightModeEnabled) 1 else 0).toByte()

                    // Update Ringtone Signature
                    val sig = settings.ringtoneSignature
                    if (sig.size >= 4) {
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG] = sig[0]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 1] = sig[1]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 2] = sig[2]
                        payload[BleConstants.Settings.INDEX_RINGTONE_SIG + 3] = sig[3]
                    }

                    val dataService =
                        currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                    if (dataWriteChar == null) {
                        pendingAckContinuations.remove(Command.SET_SETTINGS)
                        continuation.resumeWithException(Exception("Data write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }

                    AppLogger.d(TAG, "Sending write settings command: ${payload.toHexString()}")

                    scope.launch {
                        val started = writeCharacteristicWithRetry(dataWriteChar, payload)
                        if (!started) {
                            pendingAckContinuations.remove(Command.SET_SETTINGS)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for settings"))
                        }
                    }
                }
            }
        }

    fun enqueueCommand(action: suspend () -> Unit): Boolean {
        val result = commandChannel.trySend(action)
        if (result.isFailure) {
            AppLogger.w(TAG, "Failed to enqueue command: channel closed or full")
            return false
        }
        return true
    }

    suspend fun setImmediateBrightness(percentage: Int): Boolean = gattMutex.withLock {
        if (currentDeviceMac == MOCK_MAC) return true
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

                val value = (percentage / 10).coerceIn(0, 10).toByte()
                val command =
                    byteArrayOf(Header.BRIGHTNESS, Command.PREVIEW_BRIGHTNESS.toByte(), value)

                val dataService =
                    currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                if (dataWriteChar == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                pendingAckContinuations[Command.PREVIEW_BRIGHTNESS] = continuation
                AppLogger.d(TAG, "Immediate brightness update: $percentage% (value: $value)")

                scope.launch {
                    val started = writeCharacteristicWithRetry(dataWriteChar, command)
                    if (!started) {
                        pendingAckContinuations.remove(Command.PREVIEW_BRIGHTNESS)
                        continuation.resume(false)
                    }
                }
            }
        }
    }


    suspend fun enableSensorNotifications(): Boolean {
        if (currentDeviceMac == MOCK_MAC) {
            startMockSensorLoop()
            return true
        }
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

                AppLogger.d(TAG, "Enabling sensor notifications...")
                sensorNotificationContinuation = continuation

                val sensorService =
                    currentGatt.services.find { it.getCharacteristic(UUID_SENSOR_NOTIFY) != null }
                val sensorNotifyChar = sensorService?.getCharacteristic(UUID_SENSOR_NOTIFY)

                if (sensorNotifyChar == null) {
                    sensorNotificationContinuation?.resumeWithException(Exception("Sensor characteristic not found"))
                    sensorNotificationContinuation = null
                    return@suspendCancellableCoroutine
                }

                currentGatt.setCharacteristicNotification(sensorNotifyChar, true)
                val descriptor = sensorNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)

                descriptor?.let {
                    val status = currentGatt.writeDescriptor(
                        it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        sensorNotificationContinuation?.resumeWithException(Exception("writeDescriptor failed for sensor: $status"))
                        sensorNotificationContinuation = null
                    }
                } ?: run {
                    sensorNotificationContinuation = null
                }

                continuation.invokeOnCancellation {
                    sensorNotificationContinuation = null
                }
            }
        }
    }

    private var mockSensorJob: kotlinx.coroutines.Job? = null

    private fun startMockSensorLoop() {
        mockSensorJob?.cancel()
        mockSensorJob = scope.launch {
            while (isActive) {
                mockSensorTemp += (-5..5).random() / 10.0
                mockSensorHum += (-10..10).random() / 10.0
                mockSensorTemp = mockSensorTemp.coerceIn(15.0, 30.0)
                mockSensorHum = mockSensorHum.coerceIn(20.0, 80.0)
                
                onSensorData?.invoke(mockSensorTemp.toFloat(), mockSensorHum.toFloat())
                delay(5000)
            }
        }
    }

    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        alarmId: Int = 0,
        enable: Boolean = true,
        days: Int = 0,
        snooze: Boolean = false
    ): Boolean {
        if (currentDeviceMac == MOCK_MAC) {
            val existing = mockAlarms.indexOfFirst { it.id == alarmId }
            val newAlarm = Alarm(alarmId, enable, hour, minute, days, snooze)
            if (existing >= 0) mockAlarms[existing] = newAlarm
            else mockAlarms.add(newAlarm)
            return true
        }
        return gattMutex.withLock {
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

                    val command = byteArrayOf(
                        Header.SET_ALARM,
                        Command.SET_ALARM.toByte(),
                        alarmId.toByte(),
                        if (enable) 0x01.toByte() else 0x00.toByte(),
                        hour.toByte(),
                        minute.toByte(),
                        days.toByte(),
                        if (snooze) 0x01.toByte() else 0x00.toByte()
                    )

                    val dataService =
                        currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                    val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)

                    if (dataWriteChar == null || dataNotifyChar == null) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }

                    // Ensure data notifications are enabled to receive ACK
                    if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                        currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                        val descriptor =
                            dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let {
                            currentGatt.writeDescriptor(
                                it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            )
                        }
                        enabledNotifications.add(UUID_DATA_NOTIFY)
                    }

                    AppLogger.d(
                        TAG,
                        "Setting alarm #$alarmId to ${hour}:${minute}, snooze=$snooze, days=$days, command=${
                            command.joinToString(" ") { "%02x".format(it) }
                        }")
                    val status = currentGatt.writeCharacteristic(
                        dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for alarm: $status"))
                    }
                }
            }
        }
        }
    }

    suspend fun deleteAlarm(alarmId: Int): Boolean {
        if (currentDeviceMac == MOCK_MAC) {
            mockAlarms.removeAll { it.id == alarmId }
            return true
        }
        return gattMutex.withLock {
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

                        val command = byteArrayOf(
                            Header.SET_ALARM,
                            Command.SET_ALARM.toByte(),
                            alarmId.toByte(),
                            0xFF.toByte(),
                            0xFF.toByte(),
                            0xFF.toByte(),
                            0xFF.toByte(),
                            0xFF.toByte()
                        )

                        val dataService =
                            currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                        val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)

                        if (dataWriteChar == null || dataNotifyChar == null) {
                            pendingAckContinuations.remove(Command.SET_ALARM)
                            continuation.resumeWithException(Exception("Data characteristics not found"))
                            return@suspendCancellableCoroutine
                        }

                        // Ensure data notifications are enabled to receive ACK
                        if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                            currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                            val descriptor =
                                dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                            descriptor?.let {
                                currentGatt.writeDescriptor(
                                    it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                )
                            }
                            enabledNotifications.add(UUID_DATA_NOTIFY)
                        }

                        AppLogger.d(TAG, "Deleting alarm #$alarmId")
                        val status = currentGatt.writeCharacteristic(
                            dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        )
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            pendingAckContinuations.remove(Command.SET_ALARM)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for alarm delete: $status"))
                        }
                    }
                }
            }
        }
    }

    suspend fun readAlarms(): List<Alarm> {
        if (currentDeviceMac == MOCK_MAC) return mockAlarms.toList()
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

                AppLogger.d(TAG, "Reading alarms...")
                alarmReadContinuation = continuation
                alarmBuffer.clear()

                val dataService =
                    currentGatt.services.find { it.getCharacteristic(UUID_DATA_NOTIFY) != null }
                val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                if (dataNotifyChar == null || dataWriteChar == null) {
                    alarmReadContinuation?.resumeWithException(Exception("Data characteristics not found"))
                    alarmReadContinuation = null
                    return@suspendCancellableCoroutine
                }

                val command = byteArrayOf(Header.GET_DATA, Command.GET_ALARMS.toByte())

                // Check if notifications already enabled
                if (enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                    AppLogger.d(TAG, "Data notifications already enabled, sending command directly...")
                    val status = currentGatt.writeCharacteristic(
                        dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        alarmReadContinuation?.resumeWithException(Exception("writeCharacteristic failed for alarms: $status"))
                        alarmReadContinuation = null
                    }
                } else {
                    // Need to enable notifications first
                    currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                    val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)

                    pendingDataCommand = command
                    AppLogger.d(TAG, "Enabling data notifications for alarms...")

                    descriptor?.let {
                        val status = currentGatt.writeDescriptor(
                            it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            alarmReadContinuation?.resumeWithException(Exception("writeDescriptor failed for alarms: $status"))
                            alarmReadContinuation = null
                            pendingDataCommand = null
                        } else {
                            enabledNotifications.add(UUID_DATA_NOTIFY)
                        }
                    } ?: run {
                        alarmReadContinuation?.resumeWithException(Exception("Data descriptor not found"))
                        alarmReadContinuation = null
                        pendingDataCommand = null
                    }
                }

                continuation.invokeOnCancellation {
                    alarmReadContinuation = null
                    alarmBuffer.clear()
                    pendingDataCommand = null
                }
            }
        }
    }

    fun disconnect() {
        // Set state flags first to prevent command consumer from processing commands during teardown
        clearConnectionState()
        deviceJob.cancelChildren()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        AppLogger.d(TAG, "Disconnected and closed GATT")
    }

    /**
     * Clean up all resources and cancel all jobs.
     * Should be called when QPController is no longer needed.
     */
    fun close() {
        disconnect()
        commandChannel.close()
        commandConsumerJob.cancel()
        deviceJob.cancel()
        AppLogger.d(TAG, "QPController closed and all jobs canceled")
    }



    suspend fun previewRingtone(settings: DeviceSettings? = null): Boolean {
        if (currentDeviceMac == MOCK_MAC) return true
        return gattMutex.withLock {
            val command = if (settings != null) {
                val vol = settings.volume.coerceIn(1, 5).toByte()
                AppLogger.d(TAG, "Previewing ringtone with volume $vol")
                byteArrayOf(Header.RINGTONE_V2, Command.PREVIEW_RINGTONE.toByte(), vol)
            } else {
                AppLogger.d(TAG, "Previewing ringtone with default/current volume")
                byteArrayOf(Header.RINGTONE_V1, Command.PREVIEW_RINGTONE.toByte())
            }

            suspendCancellableCoroutine { continuation ->
                val currentGatt = gatt ?: run {
                    continuation.resumeWithException(Exception("GATT not connected"))
                    return@suspendCancellableCoroutine
                }
                if (!isAuthenticated) {
                    continuation.resumeWithException(Exception("Not authenticated"))
                    return@suspendCancellableCoroutine
                }

                pendingAckContinuations[Command.PREVIEW_RINGTONE] = continuation

                val dataService =
                    currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                if (dataWriteChar == null) {
                    pendingAckContinuations.remove(Command.PREVIEW_RINGTONE)
                    continuation.resumeWithException(Exception("Data write characteristic not found"))
                    return@suspendCancellableCoroutine
                }

                scope.launch {
                    val started = writeCharacteristicWithRetry(dataWriteChar, command)
                    if (!started) {
                        pendingAckContinuations.remove(Command.PREVIEW_RINGTONE)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for command"))
                    }
                }
            }
        }
    }

    private var uploadAckReceived = false
    private var uploadInitAckReceived = false

    /**
     * Upload custom ringtone audio to the device.
     *
     * @param pcmData PCM audio data (8-bit unsigned, 8kHz, mono)
     * @param targetSignature The ringtone slot signature to overwrite (must be different from current)
     * @param onProgress Progress callback (0.0 to 1.0)
     */
    suspend fun uploadRingtone(
        pcmData: ByteArray, targetSignature: ByteArray, onProgress: (Float) -> Unit
    ): Boolean {
        // Don't use gattMutex here - it causes blocking issues
        val currentGatt = gatt ?: run {
            AppLogger.e(TAG, "GATT not connected")
            return false
        }
        if (!isAuthenticated) {
            AppLogger.e(TAG, "Not authenticated")
            return false
        }

        val dataService =
            currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
        val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)

        if (dataWriteChar == null || dataNotifyChar == null) {
            AppLogger.e(TAG, "Data characteristics not found")
            return false
        }

        // Enable notifications if not already enabled
        if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
            currentGatt.setCharacteristicNotification(dataNotifyChar, true)
            val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.let {
                currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            delay(DELAY_ALARM_RELOAD)
            enabledNotifications.add(UUID_DATA_NOTIFY)
        }

        AppLogger.d(TAG, "=== AUDIO UPLOAD START ===")
        AppLogger.d(TAG, "Audio size: ${pcmData.size} bytes")
        AppLogger.d(TAG, "Target signature: ${targetSignature.toHexString()}")

        // 1. Send Init
        val sizeBytes = pcmData.size
        val initPayload = byteArrayOf(
            Header.AUDIO_INIT,
            Command.AUDIO_INIT.toByte(),
            (sizeBytes and 0xFF).toByte(),
            ((sizeBytes shr 8) and 0xFF).toByte(),
            ((sizeBytes shr 16) and 0xFF).toByte(),
            targetSignature[0],
            targetSignature[1],
            targetSignature[2],
            targetSignature[3]
        )

        AppLogger.d(TAG, "Sending Init: ${initPayload.toHexString()}")
        uploadInitAckReceived = false

        // Write an init packet and wait for callback (like original writeChar with withResponse=true)
        val initSuccess = writeCharAndWait(dataWriteChar, initPayload)
        if (!initSuccess) {
            AppLogger.e(TAG, "Failed to send init command")
            return false
        }

        // Wait for init response
        repeat(AUDIO_INIT_ACK_WAIT_ITERATIONS) {
            if (uploadInitAckReceived) return@repeat
            delay(AUDIO_ACK_WAIT_DELAY)
        }

        if (!uploadInitAckReceived) {
            AppLogger.e(TAG, "No Init response received")
            return false
        }

        // 2. Send audio data in packets
        // Protocol: 4 packets per block, wait for ACK after last packet
        val packetSize = AUDIO_PACKET_SIZE
        val packetsPerBlock = AUDIO_PACKETS_PER_BLOCK
        val blockSize = packetSize * packetsPerBlock
        var offset = 0
        var blockNum = 0
        val totalBlocks = (pcmData.size + blockSize - 1) / blockSize
        var totalPacketsSent = 0

        AppLogger.d(
            TAG,
            "Starting Audio Upload. Plan: Total size=${pcmData.size} bytes. Blocks: $totalBlocks. Packet size: $packetSize bytes."
        )

        while (offset < pcmData.size) {
            // Send 4 packets (one block = 512 bytes of audio)
            for (pktIdx in 0 until packetsPerBlock) {
                if (offset >= pcmData.size) break

                val remaining = pcmData.size - offset
                val audioLen = minOf(packetSize, remaining)
                val audioChunk = pcmData.copyOfRange(offset, offset + audioLen)

                // Pad to packetSize if needed (use 0xFF for silence like official app)
                val paddedAudio = if (audioChunk.size < packetSize) {
                    val padding = packetSize - audioChunk.size
                    AppLogger.d(
                        TAG,
                        "Padding final packet $totalPacketsSent. Data: ${audioChunk.size} bytes. Padding: $padding bytes."
                    )
                    audioChunk + ByteArray(padding) { 0xFF.toByte() }
                } else {
                    audioChunk
                }

                // Packet format: Header + 128 bytes audio
                val packet =
                    byteArrayOf(Header.AUDIO_PACKET, Command.AUDIO_BLOCK.toByte()) + paddedAudio
                val isLastInBlock =
                    (pktIdx == packetsPerBlock - 1) || (offset + audioLen >= pcmData.size)

                AppLogger.d(
                    TAG,
                    "Block $blockNum, Pkt $pktIdx: offset=$offset, audioLen=$audioLen, packetLen=${packet.size}, isLast=$isLastInBlock"
                )

                if (isLastInBlock) {
                    // Last packet in block - wait for writing callback, then wait for device ACK
                    uploadAckReceived = false
                    val writeSuccess = writeCharAndWait(dataWriteChar, packet)
                    if (!writeSuccess) {
                        AppLogger.w(TAG, "Write failed for block $blockNum last packet")
                    }

                    // Wait for block ACK from a device
                    repeat(AUDIO_ACK_WAIT_ITERATIONS) {
                        if (uploadAckReceived) return@repeat
                        delay(AUDIO_ACK_WAIT_DELAY)
                    }

                    if (!uploadAckReceived) {
                        AppLogger.w(TAG, "No ACK for block $blockNum, packet $pktIdx")
                    }
                } else {
                    // Regular packet - write with callback, then small delay
                    val writeSuccess = writeCharAndWait(dataWriteChar, packet)
                    if (!writeSuccess) {
                        AppLogger.w(TAG, "Write failed for block $blockNum, packet $pktIdx")
                    }
                    delay(DELAY_PACKET_WRITE)
                }

                offset += audioLen
                totalPacketsSent++
            }

            blockNum++
            val progress = minOf(1.0f, offset.toFloat() / pcmData.size)
            onProgress(progress)

            AppLogger.d(
                TAG,
                "Block $blockNum complete: offset=$offset/${pcmData.size}, totalPackets=$totalPacketsSent"
            )

            if (blockNum % 10 == 0 || offset >= pcmData.size) {
                AppLogger.d(
                    TAG, "Progress: ${(progress * 100).toInt()}% (block $blockNum/$totalBlocks)"
                )
            }
        }

        AppLogger.d(TAG, "=== UPLOAD COMPLETE === Total packets sent: $totalPacketsSent")
        return true
    }

    /**
     * Write characteristic and wait for onCharacteristicWrite callback.
     * Matches original BluetoothController.writeChar(withResponse=true) behavior.
     */
    private suspend fun writeCharAndWait(
        characteristic: BluetoothGattCharacteristic, data: ByteArray
    ): Boolean {
        val currentGatt = gatt ?: return false

        val deferred = CompletableDeferred<Boolean>()
        writeCompleteDeferred = deferred

        val result = currentGatt.writeCharacteristic(
            characteristic, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) == android.bluetooth.BluetoothStatusCodes.SUCCESS

        if (!result) {
            writeCompleteDeferred = null
            return false
        }

        // Wait for callback with timeout
        return try {
            withTimeout(TIMEOUT_OPERATION) {
                deferred.await()
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Write callback timeout: ${e.message}")
            false
        } finally {
            writeCompleteDeferred = null
        }
    }


    // Handle upload ACKs in the notification handler
    internal fun handleUploadAck(value: ByteArray) {
        if (value.size >= 3 && value[0] == Header.ACK[0] && value[1] == Header.ACK[1]) {
            val cmdId = value[2].toInt() and 0xFF
            val status = if (value.size >= 4) value[3].toInt() and 0xFF else 0

            when (cmdId) {
                Command.AUDIO_INIT -> {
                    AppLogger.d(TAG, "Init ACK received (status: $status)")
                    uploadInitAckReceived = true
                }

                Command.AUDIO_BLOCK -> {
                    AppLogger.d(TAG, "Audio block ACK received (status: $status)")
                    uploadAckReceived = true
                }
            }
        }
    }
}
