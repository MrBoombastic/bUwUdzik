package com.mrboombastic.buwudzik.ui.utils

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import com.mrboombastic.buwudzik.data.BatteryType

/**
 * Bluetooth-related utility functions to avoid code duplication
 */
object BluetoothUtils {

    /**
     * Required Bluetooth permissions for scanning and connecting
     */
    val BLUETOOTH_PERMISSIONS = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
    )

    /**
     * Check if Bluetooth is currently enabled
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bluetoothManager?.adapter?.isEnabled == true
    }

    /**
     * Check if all required Bluetooth permissions are granted
     */
    fun hasBluetoothPermissions(context: Context): Boolean {
        return BLUETOOTH_PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Convert RSSI (dBm) to signal strength percentage
     * Range: -90 dBm (0%) to -40 dBm (100%)
     */
    fun rssiToPercentage(rssi: Int): Int = when {
        rssi >= -40 -> 100
        rssi <= -90 -> 0
        else -> ((rssi + 90) * 100) / 50
    }

    /**
     * Correct battery percentage based on a battery type.
     * Alkaline (default): Use raw percentage (0-100)
     * NiMH: Scale 80% to 100% roughly (as 1.2V nominal reads lower on 1.5V curve)
     */
    fun correctBatteryLevel(rawPercentage: Int, batteryType: String): Int {
        return if (batteryType == BatteryType.NIMH) {
            // Simple heuristic: NiMH fully charged (~1.4V) might read as ~80% on a device expecting 1.6V+
            // Scale up by 1.25x, capped at 100%
            minOf(100, (rawPercentage * 1.25).toInt())
        } else {
            rawPercentage
        }
    }
}

