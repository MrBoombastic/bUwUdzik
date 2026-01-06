package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import com.mrboombastic.buwudzik.device.SensorData

class SensorRepository(context: Context) {

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

    fun saveSensorData(data: SensorData) {
        prefs.edit().apply {
            putFloat(KEY_TEMP, data.temperature.toFloat())
            putFloat(KEY_HUMIDITY, data.humidity.toFloat())
            putInt(KEY_BATTERY, data.battery)
            putInt(KEY_RSSI, data.rssi)
            putString(KEY_NAME, data.name)
            putString(KEY_MAC_ADDRESS, data.macAddress)
            putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
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
