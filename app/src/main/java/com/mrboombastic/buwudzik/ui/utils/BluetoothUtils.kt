package com.mrboombastic.buwudzik.ui.utils

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

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
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Convert RSSI (dBm) to signal strength percentage
     * Range: -100 dBm (0%) to -31 dBm (100%)
     */
    fun rssiToPercentage(rssi: Int): Int = when {
        rssi >= -31 -> 100
        rssi <= -100 -> 0
        else -> ((rssi + 100) * 100) / 69 //nice
    }

    /**
     * Correct battery percentage based on battery type.
     * Alkaline (default): Use raw percentage (0-100)
     * NiMH: Scale 80% to 100% roughly (as 1.2V nominal reads lower on 1.5V curve)
     */
    fun correctBatteryLevel(rawPercentage: Int, batteryType: String): Int {
        return if (batteryType == "nimh") {
            // Simple heuristic: NiMH fully charged (~1.4V) might read as ~80% on a device expecting 1.6V+
            // Scale up by 1.25x, capped at 100%
            minOf(100, (rawPercentage * 1.25).toInt())
        } else {
            rawPercentage
        }
    }
}

