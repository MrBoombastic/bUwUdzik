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
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    /**
     * Check if Bluetooth is currently enabled
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
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
     * Range: -100 dBm (0%) to -35 dBm (100%)
     */
    fun rssiToPercentage(rssi: Int): Int = when {
        rssi >= -35 -> 100
        rssi <= -100 -> 0
        else -> ((rssi + 100) * 100) / 65
    }
}

