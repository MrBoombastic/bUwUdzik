package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils

class SensorRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("sensor_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TEMP = "temp"
        private const val KEY_HUMIDITY = "humidity"
        private const val KEY_BATTERY = "battery"
        private const val KEY_RSSI = "rssi"
        private const val KEY_NAME = "name"
        private const val KEY_MAC_ADDRESS = "mac_address"
        private const val KEY_TIMESTAMP = "timestamp"
    }

    /**
     * Saves sensor data with battery level correction applied.
     * This is the single point where battery correction happens (DRY).
     * @return The corrected SensorData for UI display consistency.
     */
    fun saveSensorData(data: SensorData): SensorData {
        val settingsRepo = SettingsRepository(context)
        val correctedBattery = BluetoothUtils.correctBatteryLevel(
            data.battery, settingsRepo.batteryType
        )
        val correctedData = data.copy(battery = correctedBattery)
        
        prefs.edit().apply {
            putFloat(KEY_TEMP, correctedData.temperature.toFloat())
            putFloat(KEY_HUMIDITY, correctedData.humidity.toFloat())
            putInt(KEY_BATTERY, correctedData.battery)
            putInt(KEY_RSSI, correctedData.rssi)
            putString(KEY_NAME, correctedData.name)
            putString(KEY_MAC_ADDRESS, correctedData.macAddress)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }

        return correctedData
    }

    fun getSensorData(): SensorData? {
        if (!prefs.contains(KEY_TEMP)) return null
        val temp = prefs.getFloat(KEY_TEMP, 0f).toDouble()
        val humidity = prefs.getFloat(KEY_HUMIDITY, 0f).toDouble()
        val battery = prefs.getInt(KEY_BATTERY, 0)
        val rssi = prefs.getInt(KEY_RSSI, 0)
        val name = prefs.getString(KEY_NAME, null)
        val macAddress = prefs.getString(KEY_MAC_ADDRESS, "Unknown") ?: "Unknown"
        val timestamp = prefs.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        return SensorData(temp, humidity, battery, rssi, name, macAddress, timestamp)
    }

    fun getLastUpdateTimestamp(): Long {
        return prefs.getLong(KEY_TIMESTAMP, 0)
    }
}
