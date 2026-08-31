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
import android.os.Build
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
import com.mrboombastic.buwudzik.device.BleConstants.UUID_DEVICE_SERVICE
import com.mrboombastic.buwudzik.device.BleConstants.UUID_SENSOR_NOTIFY
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlin.time.Duration.Companion.milliseconds

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
    private val _busyCount = java.util.concurrent.atomic.AtomicInteger(0)
    private val _isBusy = MutableStateFlow(false)
    override val isBusy = _isBusy.asStateFlow()

    private inline fun <T> withBusy(block: () -> T): T {
        _busyCount.incrementAndGet()
        _isBusy.value = true
        return try {
            block()
        } finally {
            if (_busyCount.decrementAndGet() == 0) {
                _isBusy.value = false
            }
        }
    }

    init {
        commandConsumerScope.launch {
            for (command in commandChannel) {
                if (!isAuthenticated) {
                    AppLogger.w(TAG, "Device not authenticated, skipping queued command")
                    continue
                }
                withBusy {
                    try {
                        command()
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Error executing queued command", e)
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "BleDeviceController"

        private const val TIMEOUT_AUTHENTICATION = 30000L
        private const val TIMEOUT_OPERATION = 5000L
        private const val TIMEOUT_ALARM_READ = 8000L

        private const val RETRY_GATT_BUSY = 5
        private const val DELAY_GATT_BUSY_RETRY = 100L

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
    private var authInitWriteCompleted = false
    private var authConfirmSent = false
    private var pendingAuthWrite: BluetoothGattCharacteristic? = null
    private var pendingDataCommand: ByteArray? = null
    private val enabledNotifications = mutableSetOf<UUID>()
    private var writeCompleteDeferred: CompletableDeferred<Boolean>? = null

    override var onSensorData: ((temperature: Float, humidity: Float) -> Unit)? = null
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
        authInitWriteCompleted = false
        authConfirmSent = false
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

        _busyCount.set(0)
        _isBusy.value = false
    }

    private fun handleAckNotification(value: ByteArray, characteristicUuid: UUID) {
        val ack = parseBleAck(value)
        if (ack == null) {
            AppLogger.d(TAG, "[$characteristicUuid] Unhandled notification: ${value.toHexString()}")
            return
        }
        val cmdId = ack.command
        val status = ack.status
        val isAuthNotification = characteristicUuid == UUID_AUTH_NOTIFY
        val authConfirmResult = ack.firstPayloadByte?.takeIf {
            isAuthNotification && cmdId == Command.AUTH_CONFIRM
        }
        val isAuthConfirm = isAuthNotification && cmdId == Command.AUTH_CONFIRM
        val commandSucceeded = if (isAuthConfirm) ack.isSuccessfulAuthConfirm() else {
            status == Status.SUCCESS
        }

        val cmdName = when (cmdId) {
            Command.AUTH_INIT -> if (isAuthNotification) "Auth Init" else "Set Settings"
            Command.AUTH_CONFIRM -> if (isAuthNotification) "Auth Confirm" else "Read Settings"
            Command.PREVIEW_BRIGHTNESS -> "Brightness Preview"
            Command.PREVIEW_RINGTONE -> "Preview Ringtone"
            Command.SET_ALARM -> "Alarm"
            Command.AUDIO_BLOCK -> "Audio Block"
            Command.TIME_SYNC -> "Time Sync"
            Command.AUDIO_INIT -> "Audio Init"
            else -> "Cmd $cmdId"
        }
        AppLogger.d(
            TAG,
            "Received ACK for command '$cmdName' (ID: ${cmdId.toHexString()}). " +
                    "Length: ${ack.payloadSize}. Status: ${status.toHexString()}" +
                    (authConfirmResult?.let { ". Result: ${it.toHexString()}" } ?: "")
        )

        if (cmdId == Command.AUDIO_BLOCK || cmdId == Command.AUDIO_INIT) {
            handleUploadAck(value)
        }

        if (commandSucceeded) {
            if (isAuthNotification && cmdId == Command.AUTH_INIT) {
                AppLogger.d(TAG, "Auth Init ACK received, will send Auth Confirm after write completes")
                authInitAckReceived = true
                maybeSendAuthConfirm()
            } else if (isAuthNotification && cmdId == Command.AUTH_CONFIRM) {
                AppLogger.d(TAG, "Authentication seems successful, but syncing time will tell the truth")
                isAuthenticated = true
                pendingAuthWriteChar = null
            }
            pendingAckContinuations.remove(cmdId)?.resume(true)
        } else {
            isAuthenticated = false
            pendingAuthWriteChar = null

            val isAuthenticationCommand =
                isAuthNotification &&
                        (cmdId == Command.AUTH_INIT || cmdId == Command.AUTH_CONFIRM)
            val tokenRejected =
                isAuthConfirm &&
                        authConfirmResult != null &&
                        authConfirmResult != Status.SUCCESS
            val pairingModeRequired = tokenRejected && isPendingPairing
            val errorSuffix = if (isAuthenticationCommand && !pairingModeRequired) {
                " " + context.getString(com.mrboombastic.buwudzik.R.string.auth_hint)
            } else {
                ""
            }
            val failureCode = if (tokenRejected) authConfirmResult else status
            val failureMessage = if (pairingModeRequired) {
                context.getString(com.mrboombastic.buwudzik.R.string.pairing_mode_required)
            } else if (tokenRejected) {
                currentDeviceMac?.let(tokenStorage::removeToken)
                context.getString(com.mrboombastic.buwudzik.R.string.auth_token_rejected)
            } else {
                "$cmdName failed: $failureCode"
            }
            AppLogger.e(
                TAG,
                "[$characteristicUuid] $cmdName failed with code $failureCode$errorSuffix " +
                        "(Full: ${value.toHexString()})"
            )
            pendingAckContinuations.remove(cmdId)
                ?.resumeWithException(Exception("$failureMessage$errorSuffix"))
        }
    }


    private fun maybeSendAuthConfirm() {
        if (!authInitAckReceived || !authInitWriteCompleted || authConfirmSent) return
        val currentGatt = gatt ?: return
        val characteristic = pendingAuthWriteChar ?: return

        authConfirmSent = true
        AppLogger.d(TAG, "Auth Init write and ACK complete; sending Auth Confirm (11 02)...")
        if (!writeCharacteristicCompat(currentGatt, characteristic, buildAuthConfirmPacket())) {
            authConfirmSent = false
            pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                ?.resumeWithException(Exception("writeCharacteristic failed for Auth Confirm"))
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

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic == null) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            onCharacteristicChangedCompat(characteristic, value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onCharacteristicChangedCompat(characteristic, value)
        }

        private fun onCharacteristicChangedCompat(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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
                                delay(DELAY_ALARM_COMPLETION.milliseconds)
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
                        val tempRaw = littleEndianUInt16(value, 1).toShort().toInt()
                        val humRaw = littleEndianUInt16(value, 3)
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

            if (characteristic?.uuid == UUID_AUTH_WRITE && pendingAuthWriteChar != null && !authConfirmSent) {
                authInitWriteCompleted = true
                maybeSendAuthConfirm()
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
                        val error = Exception("Enable data notification failed: $status")
                        alarmReadContinuation?.resumeWithException(error)
                        alarmReadContinuation = null
                        deviceSettingsReadContinuation?.resumeWithException(error)
                        deviceSettingsReadContinuation = null
                        pendingAckContinuations.remove(Command.SET_ALARM)?.resumeWithException(error)
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
                        authInitAckReceived = false
                        authInitWriteCompleted = false
                        authConfirmSent = false
                        gatt?.let { writeCharacteristicCompat(it, char, buildAuthInitPacket()) }
                        pendingAuthWrite = null
                    }
                }
                UUID_DATA_NOTIFY -> {
                    enabledNotifications.add(UUID_DATA_NOTIFY)
                    pendingDataCommand?.let { cmd ->
                        AppLogger.d(TAG, "Descriptor write complete, now sending data command: ${cmd.toHexString()}...")
                        val dataService = gatt?.services?.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
                        dataWriteChar?.let { char ->
                            writeCharacteristicCompat(gatt, char, cmd)
                        }
                        pendingDataCommand = null
                    }
                }
                UUID_SENSOR_NOTIFY -> {
                    enabledNotifications.add(UUID_SENSOR_NOTIFY)
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

    override suspend fun connectAndAuthenticate(device: BluetoothDevice) {
        val macAddress = device.address
        currentDeviceMac = macAddress
        currentToken = prepareTokenForDevice(macAddress)
        AppLogger.d(TAG, "Authentication token prepared for $macAddress")

        connect(device)

        if (!isAuthenticated) {
            withTimeout(TIMEOUT_AUTHENTICATION.milliseconds) { authenticate() }
            delay(DELAY_POST_AUTH.milliseconds)
        }
        val timeSynchronized =
            withTimeout(TIMEOUT_AUTHENTICATION.milliseconds) { synchronizeTime() }
        if (!timeSynchronized) throw Exception("Time synchronization failed after authentication")

        if (isPendingPairing) {
            val token = currentToken ?: throw IllegalStateException("No token after authentication")
            tokenStorage.storeToken(macAddress, token)
            isPendingPairing = false
            AppLogger.d(
                TAG,
                "Token stored for $macAddress after authenticated time synchronization"
            )
        }

        // Live sensor updates are a bonus: a hiccup here must not invalidate a link that
        // is already authenticated and time synchronized.
        try {
            enableSensorNotifications()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not enable sensor notifications: ${e.message}", e)
        }
    }

    private suspend fun authenticate(): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_AUTHENTICATION.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }
                    AppLogger.d(TAG, "Starting authentication...")
                    pendingAckContinuations[Command.AUTH_CONFIRM] = continuation
                    val authNotifyChar = currentGatt.findDeviceCharacteristic(UUID_AUTH_NOTIFY)
                    val authWriteChar = currentGatt.findDeviceCharacteristic(UUID_AUTH_WRITE)
                    if (authNotifyChar == null || authWriteChar == null) {
                        pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                        continuation.resumeWithException(Exception("Auth characteristics not found"))
                        return@suspendCancellableCoroutine
                    }
                    currentGatt.setCharacteristicNotification(authNotifyChar, true)
                    val descriptor = authNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                    pendingAuthWrite = authWriteChar
                    descriptor?.let {
                        writeDescriptorCompat(
                            currentGatt,
                            it,
                            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        )
                    }
                    continuation.invokeOnCancellation {
                        pendingAckContinuations.remove(Command.AUTH_CONFIRM)
                        pendingAuthWrite = null
                    }
                }
            }
        }
    }

    override suspend fun synchronizeTime(timestamp: Long?): Boolean = gattMutex.withLock {
        val finalTimestamp = timestamp ?: (System.currentTimeMillis() / 1000)
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_AUTHENTICATION.milliseconds) {
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
                    val authWriteChar = currentGatt.findDeviceCharacteristic(UUID_AUTH_WRITE)
                    if (authWriteChar == null) {
                        pendingAckContinuations.remove(Command.TIME_SYNC)
                        continuation.resumeWithException(Exception("Auth write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }
                    writeCharacteristicCompat(currentGatt, authWriteChar, command)
                    continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.TIME_SYNC) }
                }
            }
        }
    }

    override suspend fun readDeviceSettings(): DeviceSettings = gattMutex.withLock {
        withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
                val dataNotifyChar = currentGatt.findDeviceCharacteristic(UUID_DATA_NOTIFY)
                if (dataWriteChar == null || dataNotifyChar == null) {
                    deviceSettingsReadContinuation = null
                    continuation.resumeWithException(Exception("Data characteristics not found"))
                    return@suspendCancellableCoroutine
                }
                val command = byteArrayOf(Header.GET_DATA, Command.GET_SETTINGS.toByte())
                scope.launch {
                    if (!sendDataCommand(currentGatt, dataWriteChar, dataNotifyChar, command) && continuation.isActive) {
                        deviceSettingsReadContinuation = null
                        continuation.resumeWithException(Exception("writeCharacteristic failed for settings"))
                    }
                }
                continuation.invokeOnCancellation {
                    deviceSettingsReadContinuation = null
                    pendingDataCommand = null
                }
            }
        }
    }

    override suspend fun readFirmwareVersion(): String = gattMutex.withLock {
        withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                val authWriteChar = currentGatt.findDeviceCharacteristic(UUID_AUTH_WRITE)
                if (authWriteChar == null) {
                    firmwareVersionReadContinuation = null
                    continuation.resumeWithException(Exception("Auth write characteristic not found"))
                    return@suspendCancellableCoroutine
                }
                val command = byteArrayOf(Header.GET_DATA, Command.GET_FIRMWARE.toByte())
                scope.launch {
                    if (!writeCharacteristicWithRetry(authWriteChar, command, RETRY_GATT_BUSY) && continuation.isActive) {
                        firmwareVersionReadContinuation = null
                        continuation.resumeWithException(Exception("writeCharacteristic failed for firmware version"))
                    }
                }
                continuation.invokeOnCancellation { firmwareVersionReadContinuation = null }
            }
        }
    }

    override suspend fun writeDeviceSettings(settings: DeviceSettings): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
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
        // Android accepts a single GATT operation at a time; polling RSSI in the middle of a long
        // transfer (e.g. a ringtone upload) makes the concurrent write fail with "GATT busy".
        if (gattMutex.isLocked) {
            AppLogger.d(TAG, "Skipping RSSI read, another GATT operation is in progress")
            return
        }
        try { gatt?.readRemoteRssi() } catch (_: Exception) {}
    }

    override fun enqueueCommand(command: suspend () -> Unit) {
        commandChannel.trySend(command)
    }

    override suspend fun readAlarms(): List<Alarm> = gattMutex.withLock {
        try {
            withTimeout(TIMEOUT_ALARM_READ.milliseconds) {
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
                    val dataNotifyChar = currentGatt.findDeviceCharacteristic(UUID_DATA_NOTIFY)
                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
                    if (dataNotifyChar == null || dataWriteChar == null) {
                        alarmReadContinuation?.resumeWithException(Exception("Data characteristics not found"))
                        alarmReadContinuation = null
                        return@suspendCancellableCoroutine
                    }
                    val command = byteArrayOf(Header.GET_DATA, Command.GET_ALARMS.toByte())
                    scope.launch {
                        if (!sendDataCommand(currentGatt, dataWriteChar, dataNotifyChar, command) && continuation.isActive) {
                            alarmReadContinuation = null
                            continuation.resumeWithException(Exception("writeCharacteristic failed for alarms"))
                        }
                    }
                    continuation.invokeOnCancellation {
                        alarmReadContinuation = null
                        alarmBuffer.clear()
                        pendingDataCommand = null
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            AppLogger.w(TAG, "Timeout while reading alarms, assuming no alarms are set")
            alarmReadContinuation = null
            alarmCompletionJob?.cancel()
            alarmCompletionJob = null
            pendingDataCommand = null
            val alarms = alarmBuffer.toList()
            alarmBuffer.clear()
            alarms
        }
    }

    override suspend fun setAlarm(hour: Int, minute: Int, alarmId: Int, enable: Boolean, days: Int, snooze: Boolean): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
                    val dataNotifyChar = currentGatt.findDeviceCharacteristic(UUID_DATA_NOTIFY)
                    if (dataWriteChar == null || dataNotifyChar == null) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }
                    scope.launch {
                        if (!sendDataCommand(currentGatt, dataWriteChar, dataNotifyChar, command) && continuation.isActive) {
                            pendingAckContinuations.remove(Command.SET_ALARM)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for alarm"))
                        }
                    }
                    continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.SET_ALARM) }
                }
            }
        }
    }

    override suspend fun deleteAlarm(alarmId: Int): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
                    val dataNotifyChar = currentGatt.findDeviceCharacteristic(UUID_DATA_NOTIFY)
                    if (dataWriteChar == null || dataNotifyChar == null) {
                        pendingAckContinuations.remove(Command.SET_ALARM)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }
                    scope.launch {
                        if (!sendDataCommand(currentGatt, dataWriteChar, dataNotifyChar, command) && continuation.isActive) {
                            pendingAckContinuations.remove(Command.SET_ALARM)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for alarm"))
                        }
                    }
                    continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.SET_ALARM) }
                }
            }
        }
    }

    override suspend fun previewBrightness(brightness: Int): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
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
                    continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.PREVIEW_BRIGHTNESS) }
                }
            }
        }
    }

    override suspend fun previewRingtone(settings: DeviceSettings?): Boolean {
        return gattMutex.withLock {
            withTimeout(TIMEOUT_OPERATION.milliseconds) {
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

                    val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
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
                    continuation.invokeOnCancellation { pendingAckContinuations.remove(Command.PREVIEW_RINGTONE) }
                }
            }
        }
    }

    /**
     * Uploads a ringtone. Holds [gattMutex] for the whole transfer, so alarm/settings reads issued
     * meanwhile wait their turn instead of colliding with it on the single Android GATT queue.
     */
    override suspend fun uploadAudio(audioData: ByteArray, signature: ByteArray, onProgress: (Float) -> Unit): Boolean =
        gattMutex.withLock {
            withBusy {
                performAudioUpload(audioData, signature, onProgress)
            }
        }

    private suspend fun performAudioUpload(audioData: ByteArray, signature: ByteArray, onProgress: (Float) -> Unit): Boolean {
        val currentGatt = gatt ?: run {
            AppLogger.e(TAG, "Audio upload aborted: GATT not connected")
            return false
        }
        if (!isAuthenticated) {
            AppLogger.e(TAG, "Audio upload aborted: device not authenticated")
            return false
        }
        val dataWriteChar = currentGatt.findDeviceCharacteristic(UUID_DATA_WRITE)
        val dataNotifyChar = currentGatt.findDeviceCharacteristic(UUID_DATA_NOTIFY)
        if (dataWriteChar == null || dataNotifyChar == null) {
            AppLogger.e(TAG, "Audio upload aborted: data characteristics not found")
            return false
        }
        AppLogger.d(TAG, "Starting audio upload: ${audioData.size} bytes, signature ${signature.toHexString()}")

        if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
            currentGatt.setCharacteristicNotification(dataNotifyChar, true)
            val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            if (descriptor != null && !writeDescriptorWithRetry(currentGatt, descriptor)) {
                AppLogger.e(TAG, "Audio upload aborted: could not enable data notifications")
                return false
            }
            delay(DELAY_ALARM_RELOAD.milliseconds)
        }

        // Send Init
        val targetSignature = signature
        val sizeBytes = audioData.size
        val initPayload = byteArrayOf(Header.AUDIO_INIT, Command.AUDIO_INIT.toByte(), (sizeBytes and 0xFF).toByte(), ((sizeBytes shr 8) and 0xFF).toByte(), ((sizeBytes shr 16) and 0xFF).toByte(), targetSignature[0], targetSignature[1], targetSignature[2], targetSignature[3])

        uploadInitAckStatus = null
        if (!writeCharAndWait(dataWriteChar, initPayload)) {
            AppLogger.e(TAG, "Audio upload aborted: writing Audio Init failed")
            return false
        }
        val initStatus = awaitUploadAck(AUDIO_INIT_ACK_WAIT_ITERATIONS) { uploadInitAckStatus }
        if (!isSuccessfulUploadStatus(initStatus)) {
            AppLogger.e(TAG, "Audio upload aborted: Audio Init ACK status ${initStatus?.toHexString() ?: "timeout"}")
            return false
        }

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
                    uploadBlockAckStatus = null
                    if (!writeCharAndWait(dataWriteChar, packet)) {
                        AppLogger.e(TAG, "Audio upload aborted: writing block packet failed at offset $offset")
                        return false
                    }
                    val blockStatus = awaitUploadAck(AUDIO_ACK_WAIT_ITERATIONS) { uploadBlockAckStatus }
                    if (!isSuccessfulUploadStatus(blockStatus)) {
                        AppLogger.e(TAG, "Audio upload aborted at offset $offset: block ACK status ${blockStatus?.toHexString() ?: "timeout"}")
                        return false
                    }
                } else {
                    if (!writeCharAndWait(dataWriteChar, packet)) {
                        AppLogger.e(TAG, "Audio upload aborted: writing packet failed at offset $offset")
                        return false
                    }
                    delay(DELAY_PACKET_WRITE.milliseconds)
                }
                offset += audioLen
            }
            onProgress(minOf(1.0f, offset.toFloat() / audioData.size))
        }
        AppLogger.d(TAG, "Audio upload finished successfully (${audioData.size} bytes)")
        return true
    }

    private suspend fun writeDescriptorWithRetry(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        retryCount: Int = RETRY_GATT_BUSY
    ): Boolean {
        repeat(retryCount) { attempt ->
            if (writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) return true
            AppLogger.w(TAG, "GATT busy, retrying notification request (attempt ${attempt + 1})")
            delay((DELAY_GATT_BUSY_RETRY * (attempt + 1)).milliseconds)
        }
        return false
    }

    private suspend fun awaitUploadAck(iterations: Int, status: () -> Int?): Int? {
        repeat(iterations) {
            status()?.let { return it }
            delay(AUDIO_ACK_WAIT_DELAY.milliseconds)
        }
        return status()
    }

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
        if (!writeCharacteristicCompat(currentGatt, characteristic, data)) {
            writeCompleteDeferred = null
            return false
        }
        return try {
            withTimeout(TIMEOUT_OPERATION.milliseconds) { deferred.await() }
        } catch (_: Exception) {
            false
        } finally {
            writeCompleteDeferred = null
        }
    }

    private suspend fun writeCharacteristicWithRetry(characteristic: BluetoothGattCharacteristic, value: ByteArray, retryCount: Int = 3): Boolean {
        val currentGatt = gatt ?: return false
        repeat(retryCount) { attempt ->
            if (writeCharacteristicCompat(currentGatt, characteristic, value)) return true
            delay((100 * (attempt + 1).toLong()).milliseconds)
        }
        return false
    }

    private fun handleUploadAck(value: ByteArray) {
        val ack = parseBleAck(value) ?: return
        when (ack.command) {
            Command.AUDIO_INIT -> uploadInitAckStatus = ack.status
            Command.AUDIO_BLOCK -> uploadBlockAckStatus = ack.status
        }
    }

    private fun isSuccessfulUploadStatus(status: Int?): Boolean = status == Status.SUCCESS

    @Volatile
    private var uploadBlockAckStatus: Int? = null

    @Volatile
    private var uploadInitAckStatus: Int? = null

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

    private suspend fun connect(device: BluetoothDevice): Boolean {
        val existingGatt = gatt
        if (existingGatt != null) {
            // Opening a second client for a link that is still up leaves the old one dangling: it
            // keeps delivering notifications while every new operation collides with it.
            if (isConnected && existingGatt.device?.address == device.address) {
                AppLogger.d(TAG, "Reusing the live GATT connection to ${device.address}")
                return true
            }
            AppLogger.d(TAG, "Closing stale GATT client before connecting to ${device.address}")
            disconnect()
        }
        return suspendCancellableCoroutine { continuation ->
            connectContinuation = continuation
            gatt = device.connectGatt(context, false, gattCallback)
            continuation.invokeOnCancellation { connectContinuation = null }
        }
    }

    private suspend fun enableSensorNotifications(): Boolean {
        // Already streaming (e.g. the link was reused on reconnect): a second request would only
        // add another write to the queue.
        if (enabledNotifications.contains(UUID_SENSOR_NOTIFY)) return true
        return enableSensorNotificationsLocked()
    }

    private suspend fun enableSensorNotificationsLocked(): Boolean = gattMutex.withLock {
        withTimeout(TIMEOUT_OPERATION.milliseconds) {
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
                val sensorNotifyChar = currentGatt.findDeviceCharacteristic(UUID_SENSOR_NOTIFY)
                if (sensorNotifyChar == null) {
                    sensorNotificationContinuation?.resumeWithException(Exception("Sensor characteristic not found"))
                    sensorNotificationContinuation = null
                    return@suspendCancellableCoroutine
                }
                currentGatt.setCharacteristicNotification(sensorNotifyChar, true)
                val descriptor = sensorNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                if (descriptor == null) {
                    // Nothing to wait for: without a CCC descriptor the local enable is all there is.
                    sensorNotificationContinuation = null
                    continuation.resume(true)
                    return@suspendCancellableCoroutine
                }
                if (!writeDescriptorCompat(currentGatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    // The write never reached the stack, so onDescriptorWrite will not arrive;
                    // failing right away beats blocking the GATT queue until the timeout.
                    sensorNotificationContinuation = null
                    continuation.resumeWithException(Exception("GATT busy, sensor notification request rejected"))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { sensorNotificationContinuation = null }
            }
        }
    }


    /**
     * Sends a command on the data characteristic, enabling its notifications first when needed.
     *
     * The command is deferred until [onDescriptorWrite] confirms the notification, because the
     * Android GATT queue accepts only one operation at a time and would silently drop the write.
     */
    private suspend fun sendDataCommand(
        gatt: BluetoothGatt,
        dataWriteChar: BluetoothGattCharacteristic,
        dataNotifyChar: BluetoothGattCharacteristic,
        command: ByteArray,
        retryCount: Int = RETRY_GATT_BUSY
    ): Boolean {
        if (enabledNotifications.contains(UUID_DATA_NOTIFY)) {
            return writeCharacteristicWithRetry(dataWriteChar, command, retryCount)
        }
        gatt.setCharacteristicNotification(dataNotifyChar, true)
        val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            ?: return writeCharacteristicWithRetry(dataWriteChar, command, retryCount)
        pendingDataCommand = command
        repeat(retryCount) { attempt ->
            if (writeDescriptorCompat(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) return true
            AppLogger.w(TAG, "GATT busy, retrying data notification request (attempt ${attempt + 1})")
            delay((DELAY_GATT_BUSY_RETRY * (attempt + 1)).milliseconds)
        }
        AppLogger.e(TAG, "Failed to request data notifications")
        pendingDataCommand = null
        return false
    }

    private fun BluetoothGatt.findDeviceCharacteristic(uuid: UUID): BluetoothGattCharacteristic? =
        getService(UUID_DEVICE_SERVICE)?.getCharacteristic(uuid)
            ?: services.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    private fun littleEndianUInt16(value: ByteArray, offset: Int): Int =
        (value[offset].toInt() and 0xff) or ((value[offset + 1].toInt() and 0xff) shl 8)

    private fun writeCharacteristicCompat(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = value
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptorCompat(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray
    ): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                value
            ) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }
}
