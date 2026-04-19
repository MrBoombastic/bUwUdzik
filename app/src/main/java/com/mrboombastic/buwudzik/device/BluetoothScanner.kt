package com.mrboombastic.buwudzik.device

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import com.mrboombastic.buwudzik.data.DeviceProfileRepository
import com.mrboombastic.buwudzik.data.normalizedBluetoothMac
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow


data class SensorData(
    val temperature: Double,
    val humidity: Double,
    val battery: Int,
    val rssi: Int,
    val name: String?,
    val macAddress: String,
    val timestamp: Long = System.currentTimeMillis()
)

class BluetoothScanner(
    private val context: Context,
    private val deviceProfileRepository: DeviceProfileRepository
) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    private val scanner
        get() = adapter?.bluetoothLeScanner

    private val serviceUUID = ParcelUuid.fromString("0000fdcd-0000-1000-8000-00805f9b34fb")

    // Class-level cache to remember names across scan sessions
    private val nameCache = mutableMapOf<String, String>()

    @SuppressLint("MissingPermission")
    fun scan(
        targetAddress: String? = null, scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    ): Flow<SensorData> = callbackFlow {
        AppLogger.d(
            "BluetoothScanner",
            "Starting BLE Scan. Target: ${targetAddress ?: "All Devices"}. Mode: $scanMode."
        )

        if (!com.mrboombastic.buwudzik.ui.utils.BluetoothUtils.hasBluetoothPermissions(context)) {
            AppLogger.e("BluetoothScanner", "Missing Bluetooth permissions")
            close()
            return@callbackFlow
        }

        if (adapter?.isEnabled != true) {
            AppLogger.e("BluetoothScanner", "Bluetooth is disabled")
            close()
            return@callbackFlow
        }

        val leScanner = scanner
        if (leScanner == null) {
            AppLogger.e("BluetoothScanner", "BluetoothLeScanner is null")
            close()
            return@callbackFlow
        }

        // Validate MAC address before using it in a filter.
        if (targetAddress != null && !BluetoothAdapter.checkBluetoothAddress(targetAddress)) {
            AppLogger.e(
                "BluetoothScanner",
                "Bluetooth scanning aborted due to invalid MAC address format: $targetAddress"
            )
            close()
            return@callbackFlow
        }

        // Get the list of saved MAC addresses once at the start of the scan
        val savedMacs = deviceProfileRepository.getProfiles()
            .map { it.mac.normalizedBluetoothMac() }
            .toSet()

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return
                val mac = device.address.normalizedBluetoothMac()

                // Cache the name ONLY if the device is saved/known to the app
                if (mac in savedMacs) {
                    val recordName = result.scanRecord?.deviceName
                    if (!recordName.isNullOrEmpty()) {
                        nameCache[mac] = recordName
                    }
                }

                if (targetAddress != null && !mac.equals(
                        targetAddress.normalizedBluetoothMac(), ignoreCase = true
                    )
                ) return

                val displayName = nameCache[mac] ?: device.name
                val serviceData = result.scanRecord?.getServiceData(serviceUUID) ?: return

                try {
                    val sensorData =
                        parseCGD1(serviceData, result.rssi, displayName, device.address)
                    if (sensorData != null) trySend(sensorData)

                } catch (e: Exception) {
                    AppLogger.e("BluetoothScanner", "Error parsing data", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                AppLogger.e("BluetoothScanner", "Scan failed: $errorCode")
                close()
            }
        }

        val filters = listOf(
            ScanFilter.Builder()
                .apply {
                    // Set the device address
                    if (targetAddress != null) {
                        setDeviceAddress(targetAddress)
                    }
                }
                .setServiceData(serviceUUID, null)
                .build()
        )

        val settings = ScanSettings.Builder().setScanMode(scanMode).build()

        AppLogger.d(
            "BluetoothScanner",
            "Starting BLE Scanner with configured filters and settings."
        )

        try {
            leScanner.startScan(filters, settings, callback)
        } catch (e: Exception) {
            AppLogger.e("BluetoothScanner", "Error starting scan: ${e.message}", e)
            close()
            return@callbackFlow
        }

        awaitClose {
            AppLogger.d("BluetoothScanner", "Flow closing/cancelled. Stopping scan.")
            try {
                if (adapter.isEnabled) {
                    leScanner.stopScan(callback)
                }
            } catch (e: Exception) {
                AppLogger.e("BluetoothScanner", "Error stopping scan: ${e.message}", e)
            }
        }
    }

    private fun parseCGD1(
        serviceData: ByteArray, rssi: Int, name: String?, macAddress: String
    ): SensorData? {
        if (serviceData.size < 17) return null

        // Byte 1 must be 0x0C (CGD1 model ID)
        val deviceId = serviceData[1].toInt()
        if (deviceId != 0x0C) return null

        // MAC Address is at 2..7 (ignored)

        // Temperature: indexes 10-11 (Little Endian Int16)
        val tempLow = serviceData[10].toInt() and 0xFF
        val tempHigh = serviceData[11].toInt()
        val tempRaw = (tempHigh shl 8) or tempLow
        val temp = tempRaw.toShort() / 10.0

        // Humidity: indexes 12-13 (Little Endian UInt16)
        val humidLow = serviceData[12].toInt() and 0xFF
        val humidHigh = serviceData[13].toInt() and 0xFF
        val humidRaw = (humidHigh shl 8) or humidLow
        val humid = humidRaw / 10.0

        // Battery: Index 16
        val battery = serviceData[16].toInt() and 0xFF

        return SensorData(temp, humid, battery, rssi, name, macAddress)
    }

}
