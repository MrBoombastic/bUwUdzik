package com.mrboombastic.buwudzik.device

import com.mrboombastic.buwudzik.utils.AppLogger


import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
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

class BluetoothScanner(context: Context) {

    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter = bluetoothManager.adapter

    private val scanner
        get() = adapter?.bluetoothLeScanner

    private val serviceUUID = ParcelUuid.fromString("0000fdcd-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun scan(
        targetAddress: String? = null, scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY
    ): Flow<SensorData> = callbackFlow {
        AppLogger.d("BluetoothScanner", "Starting scan flow for target: $targetAddress")
        val leScanner = scanner
        if (leScanner == null) {
            Log.e("BluetoothScanner", "BluetoothLeScanner is null")
            close()
            return@callbackFlow
        }

        var cachedName: String? = null

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                val device = result?.device ?: return

                if (targetAddress != null && !device.address.equals(
                        targetAddress, ignoreCase = true
                    )
                ) return


                // Cache the name if available in this packet (e.g. Scan Response)
                val recordName = result.scanRecord?.deviceName
                if (!recordName.isNullOrEmpty()) cachedName = recordName


                val displayName = cachedName ?: device.name
                val serviceData = result.scanRecord?.getServiceData(serviceUUID) ?: return

                try {
                    val sensorData = parseCGD1(serviceData, result.rssi, displayName, device.address)
                    if (sensorData != null) trySend(sensorData)

                } catch (e: Exception) {
                    Log.e("BluetoothScanner", "Error parsing data", e)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e("BluetoothScanner", "Scan failed: $errorCode")
            }
        }

        val filters = emptyList<ScanFilter>().plus(
            ScanFilter.Builder()
                .setServiceData(serviceUUID, null)
                .build()
        )

        val settings = ScanSettings.Builder().setScanMode(scanMode).build()

        AppLogger.d("BluetoothScanner", "Invoking startScan...")
        leScanner.startScan(filters, settings, callback)

        awaitClose {
            AppLogger.d("BluetoothScanner", "Flow closing/cancelled. Stopping scan.")
            leScanner.stopScan(callback)
        }
    }

    private fun parseCGD1(serviceData: ByteArray, rssi: Int, name: String?, macAddress: String): SensorData? {
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


