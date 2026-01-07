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
import android.util.Log
import androidx.annotation.RequiresPermission
import com.mrboombastic.buwudzik.data.TokenStorage
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
 * Reason for BLE disconnection
 */
sealed class DisconnectionReason(val message: String) {
    data object DeviceTerminated : DisconnectionReason("Device terminated connection")
    data object ConnectionTimeout : DisconnectionReason("Connection timeout")
    data object LinkLost : DisconnectionReason("Link lost")
    data object UserRequested : DisconnectionReason("User requested disconnect")
    data class Unknown(val status: Int) : DisconnectionReason("Disconnected (status: $status)")

    companion object {
        fun fromGattStatus(status: Int): DisconnectionReason = when (status) {
            0 -> UserRequested // GATT_SUCCESS - normal disconnect
            8 -> ConnectionTimeout // GATT_CONN_TIMEOUT
            19 -> DeviceTerminated // GATT_CONN_TERMINATE_PEER_USER
            22 -> LinkLost // GATT_CONN_TERMINATE_LOCAL_HOST
            else -> Unknown(status)
        }
    }
}

data class DeviceSettings(
    val tempUnit: TempUnit = TempUnit.Celsius,
    val timeFormat: TimeFormat = TimeFormat.H24,
    val language: Language = Language.English,
    val volume: Int = 3,
    val timezoneOffset: Int = 10, // Unit = 6 min
    val timezoneSign: Boolean = true, // 1 = Pos, 0 = Neg
    val nightModeBrightness: Int = 10,
    val backlightDuration: Int = 60,
    val screenBrightness: Int = 100,
    val nightStartHour: Int = 22,
    val nightStartMinute: Int = 0,
    val nightEndHour: Int = 7,
    val nightEndMinute: Int = 0,
    val nightModeEnabled: Boolean = true,
    val masterAlarmDisabled: Boolean = true,
    val firmwareVersion: String = "",
    val ringtoneSignature: ByteArray = byteArrayOf(
        0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DeviceSettings
        return tempUnit == other.tempUnit && timeFormat == other.timeFormat && language == other.language && volume == other.volume && timezoneOffset == other.timezoneOffset && timezoneSign == other.timezoneSign && nightModeBrightness == other.nightModeBrightness && backlightDuration == other.backlightDuration && screenBrightness == other.screenBrightness && nightStartHour == other.nightStartHour && nightStartMinute == other.nightStartMinute && nightEndHour == other.nightEndHour && nightEndMinute == other.nightEndMinute && nightModeEnabled == other.nightModeEnabled && masterAlarmDisabled == other.masterAlarmDisabled && firmwareVersion == other.firmwareVersion && ringtoneSignature.contentEquals(
            other.ringtoneSignature
        )
    }

    override fun hashCode(): Int {
        var result = tempUnit.hashCode()
        result = 31 * result + timeFormat.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + volume
        result = 31 * result + timezoneOffset
        result = 31 * result + timezoneSign.hashCode()
        result = 31 * result + nightModeBrightness
        result = 31 * result + backlightDuration
        result = 31 * result + screenBrightness
        result = 31 * result + nightStartHour
        result = 31 * result + nightStartMinute
        result = 31 * result + nightEndHour
        result = 31 * result + nightEndMinute
        result = 31 * result + nightModeEnabled.hashCode()
        result = 31 * result + masterAlarmDisabled.hashCode()
        result = 31 * result + firmwareVersion.hashCode()
        result = 31 * result + ringtoneSignature.contentHashCode()
        return result
    }

    fun getRingtoneName(): String {
        // Check for custom slots first
        QPController.getCustomSlotName(ringtoneSignature)?.let { return it }
        // Then check standard ringtones
        return QPController.RINGTONE_SIGNATURES.entries.find {
            it.value.contentEquals(
                ringtoneSignature
            )
        }?.key ?: "Unknown"
    }
}

enum class TempUnit { Celsius, Fahrenheit }
enum class TimeFormat { H24, H12 }
enum class Language { Chinese, English }

/**
 * Controller for QP CGD1 device via BLE GATT
 * Maintains a persistent connection with the device
 *
 * Note: All Bluetooth operations require BLUETOOTH_CONNECT permission.
 * Permission is checked at the UI layer before any operations are performed.
 */
@SuppressLint("MissingPermission")
class QPController(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val gattMutex = Mutex()
    private val commandChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    init {
        scope.launch {
            for (command in commandChannel) {
                if (!isAuthenticated) {
                    Log.w(TAG, "Device not authenticated, skipping queued command")
                    continue
                }
                _isBusy.value = true
                try {
                    command()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing queued command", e)
                } finally {
                    _isBusy.value = false
                }
            }
        }
    }

    companion object {
        private const val TAG = "QPController"

        // UUIDs for QP CGD1
        private val UUID_AUTH_WRITE = UUID.fromString("00000001-0000-1000-8000-00805f9b34fb")
        private val UUID_AUTH_NOTIFY = UUID.fromString("00000002-0000-1000-8000-00805f9b34fb")
        private val UUID_DATA_WRITE = UUID.fromString("0000000b-0000-1000-8000-00805f9b34fb")
        private val UUID_DATA_NOTIFY = UUID.fromString("0000000c-0000-1000-8000-00805f9b34fb")
        private val UUID_SENSOR_NOTIFY = UUID.fromString("00000100-0000-1000-8000-00805f9b34fb")
        private val UUID_CLIENT_CHARACTERISTIC_CONFIG =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Known ringtone signatures from https://qingplus.cleargrass.com/raw/rings
        val RINGTONE_SIGNATURES = mapOf(
            "Beep" to byteArrayOf(0xfd.toByte(), 0xc3.toByte(), 0x66.toByte(), 0xa5.toByte()),
            "Digital Ringtone" to byteArrayOf(
                0x09.toByte(), 0x61.toByte(), 0xbb.toByte(), 0x77.toByte()
            ),
            "Digital Ringtone 2" to byteArrayOf(
                0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
            ),
            "Cuckoo" to byteArrayOf(0xea.toByte(), 0x2d.toByte(), 0x4c.toByte(), 0x02.toByte()),
            "Telephone" to byteArrayOf(0x79.toByte(), 0x1b.toByte(), 0xac.toByte(), 0xb3.toByte()),
            "Exotic Guitar" to byteArrayOf(
                0x1d.toByte(), 0x01.toByte(), 0x9f.toByte(), 0xd6.toByte()
            ),
            "Lively Piano" to byteArrayOf(
                0x6e.toByte(), 0x70.toByte(), 0xb6.toByte(), 0x59.toByte()
            ),
            "Story Piano" to byteArrayOf(
                0x8f.toByte(), 0x00.toByte(), 0x48.toByte(), 0x86.toByte()
            ),
            "Forest Piano" to byteArrayOf(
                0x26.toByte(), 0x52.toByte(), 0x25.toByte(), 0x19.toByte()
            )
        )

        // Custom ringtone slots with alternating IDs (dead/beef)
        val CUSTOM_RINGTONE_SLOT_1 =
            byteArrayOf(0xde.toByte(), 0xad.toByte(), 0xde.toByte(), 0xad.toByte()) // dead
        val CUSTOM_RINGTONE_SLOT_2 =
            byteArrayOf(0xbe.toByte(), 0xef.toByte(), 0xbe.toByte(), 0xef.toByte()) // beef

        /**
         * Get the appropriate custom slot to use (alternates between dead and beef based on current)
         */
        fun getCustomSlotSignature(currentSignature: ByteArray?): ByteArray {
            return if (currentSignature?.contentEquals(CUSTOM_RINGTONE_SLOT_1) == true) {
                CUSTOM_RINGTONE_SLOT_2 // Current is dead, use beef
            } else {
                CUSTOM_RINGTONE_SLOT_1 // Current is beef or other, use dead
            }
        }


        /**
         * Get custom slot name for display
         */
        fun getCustomSlotName(signature: ByteArray): String? {
            return when {
                signature.contentEquals(CUSTOM_RINGTONE_SLOT_1) -> "Custom" // doesn't matter for end user
                signature.contentEquals(CUSTOM_RINGTONE_SLOT_2) -> "Custom"
                else -> null
            }
        }

    }

    // Token storage for persistence
    private val tokenStorage = TokenStorage(context)

    // Current device being connected to
    private var currentDeviceMac: String? = null
    private var currentToken: ByteArray? = null

    // Build auth packets dynamically from current token
    private fun buildAuthInitPacket(): ByteArray {
        val token = currentToken ?: throw IllegalStateException("No token set")
        return byteArrayOf(0x11.toByte(), 0x01.toByte()) + token
    }

    private fun buildAuthConfirmPacket(): ByteArray {
        val token = currentToken ?: throw IllegalStateException("No token set")
        return byteArrayOf(0x11.toByte(), 0x02.toByte()) + token
    }

    /**
     * Check if a device is already paired (has stored token).
     */
    fun isDevicePaired(macAddress: String): Boolean = tokenStorage.isPaired(macAddress)

    /**
     * Remove pairing (stored token) for a device.
     */
    fun unpairDevice(macAddress: String) {
        tokenStorage.removeToken(macAddress)
    }

    /**
     * Prepare token for connection. If device is already paired, use stored token.
     * Otherwise, generate a new random token for pairing (without storing yet).
     */
    private fun prepareTokenForDevice(macAddress: String): ByteArray {
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

    // Sensor stream callback
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

    private fun handleAckNotification(value: ByteArray) {
        if (value.size >= 4 && value[0] == 0x04.toByte() && value[1] == 0xff.toByte()) {
            val cmdId = value[2].toInt() and 0xFF
            val status = value[3].toInt() and 0xFF

            val cmdName = when (cmdId) {
                0x01 -> "Auth Init"
                0x02 -> "Auth Confirm"
                0x03 -> "Brightness Preview"
                0x04 -> "Preview Ringtone"
                0x05 -> "Alarm"
                0x08 -> "Audio Block"
                0x09 -> "Time Sync"
                0x10 -> "Audio Init"
                else -> "Cmd $cmdId"
            }
            AppLogger.d(
                TAG,
                "Received ACK for command '$cmdName' (ID: ${cmdId.toHexString()}). Status: ${status.toHexString()}"
            )

            // Handle audio upload ACKs
            if (cmdId == 0x08 || cmdId == 0x10) {
                handleUploadAck(value)
            }

            if (status == 0x00 || status == 0x09 || (cmdId == 0x01 && status == 0x02)) {
                // cmdId 0x01 = Auth Init success, mark that we need to send Auth Confirm
                if (cmdId == 0x01) {
                    AppLogger.d(
                        TAG, "Auth Init ACK received, will send Auth Confirm after write completes"
                    )
                    authInitAckReceived = true
                    // Don't write here - wait for onCharacteristicWrite callback
                } else if (cmdId == 0x02) {
                    AppLogger.d(TAG, "Authentication successful! Device is now authenticated.")
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
                Log.e(TAG, "$cmdName failed with status $status")
                pendingAckContinuations.remove(cmdId)
                    ?.resumeWithException(Exception("$cmdName failed: $status"))
            }
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
                    isConnected = false
                    isAuthenticated = false
                    enabledNotifications.clear()
                    AppLogger.d(TAG, "Disconnected from GATT server (status: $status)")

                    // Emit disconnection event with reason
                    _disconnectionEvent.value = DisconnectionReason.fromGattStatus(status)

                    connectContinuation?.resumeWithException(Exception("Disconnected"))
                    connectContinuation = null

                    val continuations = pendingAckContinuations.values.toList()
                    pendingAckContinuations.clear()
                    continuations.forEach { it.resumeWithException(Exception("Disconnected")) }

                    alarmReadContinuation?.resumeWithException(Exception("Disconnected"))
                    alarmReadContinuation = null

                    deviceSettingsReadContinuation?.resumeWithException(Exception("Disconnected"))
                    deviceSettingsReadContinuation = null

                    firmwareVersionReadContinuation?.resumeWithException(Exception("Disconnected"))
                    firmwareVersionReadContinuation = null

                    _isBusy.value = false
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
                Log.e(TAG, "Service discovery failed: $status")
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
                    if (value.isNotEmpty() && value[0] == 0x0b.toByte()) {
                        try {
                            val length = if (value.size > 1) value[1].toInt() and 0xFF else 0
                            val version = String(value, 2, minOf(length, value.size - 2))
                            AppLogger.d(TAG, "Received firmware version: $version")
                            firmwareVersionReadContinuation?.resume(version)
                            firmwareVersionReadContinuation = null
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse firmware version", e)
                            firmwareVersionReadContinuation?.resumeWithException(e)
                            firmwareVersionReadContinuation = null
                        }
                    } else {
                        handleAckNotification(value)
                    }
                }

                UUID_DATA_NOTIFY -> {
                    // Check for audio upload ACKs first (04 ff 08/10 XX)
                    if (value.size >= 3 && value[0] == 0x04.toByte() && value[1] == 0xff.toByte()) {
                        val cmdId = value[2].toInt() and 0xFF
                        // Handle audio upload ACKs
                        if (cmdId == 0x08 || cmdId == 0x10) {
                            handleUploadAck(value)
                        }
                        // Also handle as regular ACK if size >= 4
                        if (value.size >= 4) {
                            handleAckNotification(value)
                        }
                    } else if (value.size >= 3 && value[0] == 0x11.toByte() && value[1] == 0x06.toByte()) {
                        val baseIndex = value[2].toInt() and 0xFF
                        AppLogger.d(TAG, "Parsing alarms packet starting at index $baseIndex")

                        var offset = 3
                        var currentIndex = baseIndex
                        var highestIndexSeen = currentIndex

                        while (offset + 5 <= value.size) {
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
                            offset += 5
                            currentIndex++
                        }

                        if (highestIndexSeen >= 15) {
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
                                delay(1000)
                                AppLogger.d(
                                    TAG,
                                    "Timeout waiting for more packets, returning ${alarmBuffer.size} alarms"
                                )
                                alarmReadContinuation?.resume(alarmBuffer.toList())
                                alarmReadContinuation = null
                                alarmBuffer.clear()
                            }
                        }
                    } else if (value.size >= 15 && value[0] == 0x13.toByte() && (value[1] == 0x01.toByte() || value[1] == 0x02.toByte())) {
                        AppLogger.d(TAG, "Received device settings packet: ${value.toHexString()}")
                        lastSettingsPacket = value.copyOf()
                        try {
                            val volume = value[2].toInt() and 0xFF
                            val flags = value[5].toInt()
                            val tzOffset = value[6].toInt() and 0xFF
                            val duration = value[7].toInt() and 0xFF
                            val packedBrightness = value[8].toInt() and 0xFF
                            val screenBri = (packedBrightness shr 4) * 10
                            val nightBri = (packedBrightness and 0x0F) * 10
                            val tzSign = value[13].toInt() == 1
                            val nightModeEnabled = value[14].toInt() == 1

                            // Parse ringtone signature from bytes 16-19
                            val ringtoneSig = if (value.size >= 20) {
                                byteArrayOf(value[16], value[17], value[18], value[19])
                            } else {
                                byteArrayOf(
                                    0xba.toByte(), 0x2c.toByte(), 0x2c.toByte(), 0x8c.toByte()
                                )
                            }

                            val settings = DeviceSettings(
                                tempUnit = if (flags and 0x04 != 0) TempUnit.Fahrenheit else TempUnit.Celsius,
                                timeFormat = if (flags and 0x02 != 0) TimeFormat.H12 else TimeFormat.H24,
                                language = if (flags and 0x01 != 0) Language.English else Language.Chinese,
                                volume = volume,
                                timezoneOffset = tzOffset,
                                timezoneSign = tzSign,
                                nightModeBrightness = nightBri,
                                backlightDuration = duration,
                                screenBrightness = screenBri,
                                nightStartHour = value[9].toInt() and 0xFF,
                                nightStartMinute = value[10].toInt() and 0xFF,
                                nightEndHour = value[11].toInt() and 0xFF,
                                nightEndMinute = value[12].toInt() and 0xFF,
                                nightModeEnabled = nightModeEnabled,
                                masterAlarmDisabled = (flags and 0x10) != 0,
                                ringtoneSignature = ringtoneSig
                            )
                            deviceSettingsReadContinuation?.resume(settings)
                            deviceSettingsReadContinuation = null
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse device settings", e)
                            deviceSettingsReadContinuation?.resumeWithException(e)
                            deviceSettingsReadContinuation = null
                        }
                    } else {
                        AppLogger.d(TAG, "Unhandled data packet: ${value.toHexString()}")
                    }
                }

                UUID_SENSOR_NOTIFY -> {
                    if (value.size >= 5 && value[0] == 0x00.toByte()) {
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
                        Log.w(TAG, "Invalid sensor data packet: ${value.toHexString()}")
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
                Log.e(TAG, "Write failed for ${characteristic?.uuid} with status $status")
                deferred?.complete(false)
            } else {
                deferred?.complete(true)

                // Check if we need to send Auth Confirm (11 02) after Auth Init write completes
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
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWrite(
            gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int
        ) {
            AppLogger.d(
                TAG, "onDescriptorWrite status=$status for ${descriptor?.characteristic?.uuid}"
            )
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.d(TAG, "Notification enabled for ${descriptor?.characteristic?.uuid}")

                when (descriptor?.characteristic?.uuid) {
                    UUID_AUTH_NOTIFY -> {
                        pendingAuthWrite?.let { char ->
                            AppLogger.d(
                                TAG, "Descriptor write complete, sending Auth Init (11 01)..."
                            )
                            pendingAuthWriteChar = char // Save for second step
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
            } else {
                Log.e(TAG, "Enable notification failed: $status")
                when (descriptor?.characteristic?.uuid) {
                    UUID_AUTH_NOTIFY -> {
                        pendingAckContinuations.remove(0x02)
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
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                AppLogger.d(TAG, "Read RSSI: $rssi")
                onRssiUpdate?.invoke(rssi)
            } else {
                Log.w(TAG, "Failed to read RSSI, status: $status")
            }
        }
    }

    @Suppress("SameReturnValue")
    suspend fun connectAndAuthenticate(device: BluetoothDevice): Boolean {
        // Prepare token for this device (generate new if fresh pairing, use stored if already paired)
        val macAddress = device.address
        currentDeviceMac = macAddress
        currentToken = prepareTokenForDevice(macAddress)
        AppLogger.d(TAG, "Token prepared for $macAddress: ${currentToken?.toHexString()}")

        if (!isConnected) {
            connect(device)
        }

        if (!isAuthenticated) {
            authenticate()
            delay(500) // Brief pause after auth to ensure stability
        }

        synchronizeTime()
        enableSensorNotifications()

        return true
    }

    fun readRssi() {
        if (gatt?.readRemoteRssi() == false) {
            Log.w(TAG, "Failed to start RSSI read")
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
                pendingAckContinuations[0x02] = continuation

                val authService =
                    currentGatt.services.find { it.getCharacteristic(UUID_AUTH_NOTIFY) != null }
                val authNotifyChar = authService?.getCharacteristic(UUID_AUTH_NOTIFY)
                val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)

                if (authNotifyChar == null || authWriteChar == null) {
                    pendingAckContinuations.remove(0x02)
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
                        pendingAckContinuations.remove(0x02)
                        continuation.resumeWithException(Exception("writeDescriptor failed for auth: $status"))
                        pendingAuthWrite = null
                    }
                } ?: run {
                    pendingAckContinuations.remove(0x02)
                    continuation.resumeWithException(Exception("Auth descriptor not found"))
                    pendingAuthWrite = null
                }
            }
        }
    }

    suspend fun synchronizeTime(timestamp: Long = System.currentTimeMillis() / 1000): Boolean =
        gattMutex.withLock {
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
                    pendingAckContinuations[0x09] = continuation

                    val command = byteArrayOf(
                        0x05.toByte(),
                        0x09.toByte(),
                        (timestamp and 0xFF).toByte(),
                        ((timestamp shr 8) and 0xFF).toByte(),
                        ((timestamp shr 16) and 0xFF).toByte(),
                        ((timestamp shr 24) and 0xFF).toByte()
                    )

                    val authService =
                        currentGatt.services.find { it.getCharacteristic(UUID_AUTH_WRITE) != null }
                    val authWriteChar = authService?.getCharacteristic(UUID_AUTH_WRITE)

                    if (authWriteChar == null) {
                        pendingAckContinuations.remove(0x09)
                        continuation.resumeWithException(Exception("Auth write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }

                    val status = currentGatt.writeCharacteristic(
                        authWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(0x09)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for time sync: $status"))
                    }
                }
            }
        }

    suspend fun readDeviceSettings(): DeviceSettings = gattMutex.withLock {
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

            val command = byteArrayOf(0x01, 0x02)

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

    suspend fun readFirmwareVersion(): String = gattMutex.withLock {
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

            val command = byteArrayOf(0x01.toByte(), 0x0d.toByte())
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
            if (result == BluetoothGatt.GATT_SUCCESS) return true // wait, writeCharacteristic returns Int status in new API

            Log.w(
                TAG,
                "writeCharacteristic failed (attempt ${attempt + 1}/$retryCount) with status $result, retrying..."
            )
            delay(100 * (attempt + 1).toLong())
        }
        return false
    }

    private suspend fun writeDeviceSettingsInternal(settings: DeviceSettings): Boolean =
        withContext(NonCancellable) {
            withTimeout(5000) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }
                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }

                    pendingAckContinuations[0x01] = continuation

                    // Use last packet as template to preserve unknown/header bytes, fallback to defaults
                    val payload = lastSettingsPacket?.copyOf() ?: ByteArray(20).apply {
                        this[0] = 0x13.toByte()
                        this[3] = 0x58.toByte()
                        this[4] = 0x02.toByte()
                    }

                    // Always ensure Command ID and Sub-command are correct for Set Settings
                    payload[0] = 0x13.toByte()
                    payload[1] = 0x01.toByte()

                    // Update Volume
                    payload[2] = settings.volume.coerceIn(1, 5).toByte()

                    // Update Flags bit by bit to preserve unknown bits
                    var flags = payload.getOrNull(5)?.toInt()?.and(0xFF) ?: 0
                    flags =
                        if (settings.language == Language.English) flags or 0x01 else flags and 0x01.inv()
                    flags =
                        if (settings.timeFormat == TimeFormat.H12) flags or 0x02 else flags and 0x02.inv()
                    flags =
                        if (settings.tempUnit == TempUnit.Fahrenheit) flags or 0x04 else flags and 0x04.inv()
                    flags =
                        if (settings.masterAlarmDisabled) flags or 0x10 else flags and 0x10.inv()
                    payload[5] = flags.toByte()

                    // Update Timezone, Duration and Packed Brightness
                    payload[6] = settings.timezoneOffset.toByte()
                    payload[7] = settings.backlightDuration.toByte()
                    payload[8] = (((settings.screenBrightness / 10).coerceIn(
                        0, 15
                    ) shl 4) or (settings.nightModeBrightness / 10).coerceIn(0, 15)).toByte()

                    // Night Mode Schedule
                    if (settings.nightModeEnabled) {
                        payload[9] = settings.nightStartHour.toByte()
                        payload[10] = settings.nightStartMinute.toByte()
                        payload[11] = settings.nightEndHour.toByte()
                        payload[12] = settings.nightEndMinute.toByte()
                    } else {
                        // Fix: Hardware often ignores the enable bit, so set a minimal 1-min window
                        AppLogger.d(TAG, "Night Mode disabled: forcing schedule to 00:00 - 00:01")
                        payload[9] = 0
                        payload[10] = 0
                        payload[11] = 0
                        payload[12] = 1
                    }

                    // Metadata bits
                    payload[13] = (if (settings.timezoneSign) 1 else 0).toByte()
                    payload[14] = (if (settings.nightModeEnabled) 1 else 0).toByte()

                    // Update Ringtone Signature
                    val sig = settings.ringtoneSignature
                    if (sig.size >= 4) {
                        payload[16] = sig[0]
                        payload[17] = sig[1]
                        payload[18] = sig[2]
                        payload[19] = sig[3]
                    }

                    val dataService =
                        currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                    val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                    if (dataWriteChar == null) {
                        pendingAckContinuations.remove(0x01)
                        continuation.resumeWithException(Exception("Data write characteristic not found"))
                        return@suspendCancellableCoroutine
                    }

                    AppLogger.d(TAG, "Sending write settings command: ${payload.toHexString()}")

                    scope.launch {
                        val started = writeCharacteristicWithRetry(dataWriteChar, payload)
                        if (!started) {
                            pendingAckContinuations.remove(0x01)
                            continuation.resumeWithException(Exception("writeCharacteristic failed for settings"))
                        }
                    }
                }
            }
        }

    fun enqueueCommand(action: suspend () -> Unit) {
        commandChannel.trySend(action)
    }

    suspend fun setDaytimeBrightnessImmediate(percentage: Int): Boolean =
        setImmediateBrightness(percentage)

    suspend fun setNightBrightnessImmediate(percentage: Int): Boolean =
        setImmediateBrightness(percentage)

    suspend fun setImmediateBrightness(percentage: Int): Boolean = gattMutex.withLock {
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
                val command = byteArrayOf(0x02.toByte(), 0x03.toByte(), value)

                val dataService =
                    currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
                val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

                if (dataWriteChar == null) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                pendingAckContinuations[0x03] = continuation
                AppLogger.d(TAG, "Immediate brightness update: $percentage% (value: $value)")

                scope.launch {
                    val started = writeCharacteristicWithRetry(dataWriteChar, command)
                    if (!started) {
                        pendingAckContinuations.remove(0x03)
                        continuation.resume(false)
                    }
                }
            }
        }
    }

    suspend fun enableSensorNotifications(): Boolean = gattMutex.withLock {
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

    suspend fun setAlarm(
        hour: Int,
        minute: Int,
        alarmId: Int = 0,
        enable: Boolean = true,
        days: Int = 0,
        snooze: Boolean = false
    ): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(5000) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }

                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }

                    pendingAckContinuations[0x05] = continuation

                    val command = byteArrayOf(
                        0x07.toByte(),
                        0x05.toByte(),
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
                        pendingAckContinuations.remove(0x05)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }

                    // Ensure data notifications are enabled to receive ACK
                    if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                        currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                        val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let {
                            currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                        enabledNotifications.add(UUID_DATA_NOTIFY)
                    }

                    AppLogger.d(TAG, "Setting alarm #$alarmId to ${hour}:${minute}, snooze=$snooze, days=$days, command=${command.joinToString(" ") { "%02x".format(it) }}")
                    val status = currentGatt.writeCharacteristic(
                        dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(0x05)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for alarm: $status"))
                    }
                }
            }
        }
    }

    suspend fun deleteAlarm(alarmId: Int): Boolean = gattMutex.withLock {
        withContext(NonCancellable) {
            withTimeout(5000) {
                suspendCancellableCoroutine { continuation ->
                    val currentGatt = gatt ?: run {
                        continuation.resumeWithException(Exception("GATT not connected"))
                        return@suspendCancellableCoroutine
                    }

                    if (!isAuthenticated) {
                        continuation.resumeWithException(Exception("Not authenticated"))
                        return@suspendCancellableCoroutine
                    }

                    pendingAckContinuations[0x05] = continuation

                    val command = byteArrayOf(
                        0x07.toByte(),
                        0x05.toByte(),
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
                        pendingAckContinuations.remove(0x05)
                        continuation.resumeWithException(Exception("Data characteristics not found"))
                        return@suspendCancellableCoroutine
                    }

                    // Ensure data notifications are enabled to receive ACK
                    if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
                        currentGatt.setCharacteristicNotification(dataNotifyChar, true)
                        val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
                        descriptor?.let {
                            currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                        enabledNotifications.add(UUID_DATA_NOTIFY)
                    }

                    AppLogger.d(TAG, "Deleting alarm #$alarmId")
                    val status = currentGatt.writeCharacteristic(
                        dataWriteChar, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        pendingAckContinuations.remove(0x05)
                        continuation.resumeWithException(Exception("writeCharacteristic failed for alarm delete: $status"))
                    }
                }
            }
        }
    }

    suspend fun readAlarms(): List<Alarm> = gattMutex.withLock {
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

            val command = byteArrayOf(0x01, 0x06)

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

    fun disconnect() {
        scope.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        isConnected = false
        isAuthenticated = false
        AppLogger.d(TAG, "Disconnected and closed GATT")
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(" ") { "%02x".format(it) }
    }

    suspend fun previewRingtone(settings: DeviceSettings? = null): Boolean = gattMutex.withLock {
        val command = if (settings != null) {
            val vol = settings.volume.coerceIn(1, 5).toByte()
            AppLogger.d(TAG, "Previewing ringtone with volume $vol")
            byteArrayOf(0x02, 0x04, vol)
        } else {
            AppLogger.d(TAG, "Previewing ringtone with default/current volume")
            byteArrayOf(0x01, 0x04)
        }
        writeToDataCharacteristic(command, 0x04)
    }

    private suspend fun writeToDataCharacteristic(
        command: ByteArray, @Suppress("SameParameterValue") ackId: Int
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val currentGatt = gatt ?: run {
            continuation.resumeWithException(Exception("GATT not connected"))
            return@suspendCancellableCoroutine
        }
        if (!isAuthenticated) {
            continuation.resumeWithException(Exception("Not authenticated"))
            return@suspendCancellableCoroutine
        }

        pendingAckContinuations[ackId] = continuation

        val dataService =
            currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)

        if (dataWriteChar == null) {
            pendingAckContinuations.remove(ackId)
            continuation.resumeWithException(Exception("Data write characteristic not found"))
            return@suspendCancellableCoroutine
        }

        scope.launch {
            val started = writeCharacteristicWithRetry(dataWriteChar, command)
            if (!started) {
                pendingAckContinuations.remove(ackId)
                continuation.resumeWithException(Exception("writeCharacteristic failed for command"))
            }
        }
    }

    // ==================== AUDIO UPLOAD ====================

    private var uploadAckReceived = false
    private var uploadInitAckReceived = false

    /**
     * Upload custom ringtone audio to the device.
     * Based on original BluetoothController.kt from QingpingUploader.
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
            Log.e(TAG, "GATT not connected")
            return false
        }
        if (!isAuthenticated) {
            Log.e(TAG, "Not authenticated")
            return false
        }

        val dataService =
            currentGatt.services.find { it.getCharacteristic(UUID_DATA_WRITE) != null }
        val dataWriteChar = dataService?.getCharacteristic(UUID_DATA_WRITE)
        val dataNotifyChar = dataService?.getCharacteristic(UUID_DATA_NOTIFY)

        if (dataWriteChar == null || dataNotifyChar == null) {
            Log.e(TAG, "Data characteristics not found")
            return false
        }

        // Enable notifications if not already enabled
        if (!enabledNotifications.contains(UUID_DATA_NOTIFY)) {
            currentGatt.setCharacteristicNotification(dataNotifyChar, true)
            val descriptor = dataNotifyChar.getDescriptor(UUID_CLIENT_CHARACTERISTIC_CONFIG)
            descriptor?.let {
                currentGatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
            delay(300)
            enabledNotifications.add(UUID_DATA_NOTIFY)
        }

        AppLogger.d(TAG, "=== AUDIO UPLOAD START ===")
        AppLogger.d(TAG, "Audio size: ${pcmData.size} bytes")
        AppLogger.d(TAG, "Target signature: ${targetSignature.toHexString()}")

        // 1. Send Init: 08 10 + length(3B LE) + ringKey(4B)
        val sizeBytes = pcmData.size
        val initPayload = byteArrayOf(
            0x08,
            0x10,
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

        // Write init packet and wait for callback (like original writeChar with withResponse=true)
        val initSuccess = writeCharAndWait(dataWriteChar, initPayload)
        if (!initSuccess) {
            Log.e(TAG, "Failed to send init command")
            return false
        }

        // Wait for init response (04 ff 10 XX)
        repeat(20) {
            if (uploadInitAckReceived) return@repeat
            delay(100)
        }

        if (!uploadInitAckReceived) {
            Log.e(TAG, "No Init response received")
            return false
        }

        // 2. Send audio data in packets
        // Protocol: 4 packets per block, wait for ACK after last packet
        val packetSize = 128
        val packetsPerBlock = 4
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

                // Packet format: 81 08 + 128 bytes audio
                val packet = byteArrayOf(0x81.toByte(), 0x08.toByte()) + paddedAudio
                val isLastInBlock =
                    (pktIdx == packetsPerBlock - 1) || (offset + audioLen >= pcmData.size)

                AppLogger.d(
                    TAG,
                    "Block $blockNum, Pkt $pktIdx: offset=$offset, audioLen=$audioLen, packetLen=${packet.size}, isLast=$isLastInBlock"
                )

                if (isLastInBlock) {
                    // Last packet in block - wait for write callback then wait for device ACK
                    uploadAckReceived = false
                    val writeSuccess = writeCharAndWait(dataWriteChar, packet)
                    if (!writeSuccess) {
                        Log.w(TAG, "Write failed for block $blockNum last packet")
                    }

                    // Wait for block ACK from device (04 ff 08 XX)
                    repeat(50) {
                        if (uploadAckReceived) return@repeat
                        delay(100)
                    }

                    if (!uploadAckReceived) {
                        Log.w(TAG, "No ACK for block $blockNum, packet $pktIdx")
                    }
                } else {
                    // Regular packet - write with callback, then small delay
                    val writeSuccess = writeCharAndWait(dataWriteChar, packet)
                    if (!writeSuccess) {
                        Log.w(TAG, "Write failed for block $blockNum, packet $pktIdx")
                    }
                    delay(20)
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
            withTimeout(5000) {
                deferred.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Write callback timeout: ${e.message}")
            false
        } finally {
            writeCompleteDeferred = null
        }
    }


    // Handle upload ACKs in the notification handler
    internal fun handleUploadAck(value: ByteArray) {
        if (value.size >= 3 && value[0] == 0x04.toByte() && value[1] == 0xff.toByte()) {
            val cmdId = value[2].toInt() and 0xFF
            val status = if (value.size >= 4) value[3].toInt() and 0xFF else 0

            when (cmdId) {
                0x10 -> {
                    AppLogger.d(TAG, "Init ACK received (status: $status)")
                    uploadInitAckReceived = true
                }

                0x08 -> {
                    AppLogger.d(TAG, "Audio block ACK received (status: $status)")
                    uploadAckReceived = true
                }
            }
        }
    }
}


