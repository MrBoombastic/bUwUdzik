package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mrboombastic.buwudzik.data.SensorRepository.Companion.prefix
import com.mrboombastic.buwudzik.device.SensorData
import com.mrboombastic.buwudzik.ui.utils.BluetoothUtils
import com.mrboombastic.buwudzik.utils.AppLogger
import java.util.Locale

/**
 * Repository for caching the latest sensor reading.
 * Keys are namespaced per-device using the MAC address, so each device
 * has its own independent cached reading.
 *
 * Battery: [saveSensorData] applies [BluetoothUtils.correctBatteryLevel] using the profile’s
 * [DeviceProfile.batteryType]. Raw advertising values often omit battery (e.g. 0xFF); those are
 * ignored so a previous valid reading is kept. While **connected**, GATT notifications carry only
 * temperature and humidity. Connected sensor updates preserve the last raw battery received from
 * advertising so an already corrected display value is never corrected a second time.
 *
 * @param mac Device MAC address (used to namespace keys).
 * @param deviceProfileRepository Used for battery-type correction (shared instance avoids extra allocations).
 */
class SensorRepository(
    private val context: Context,
    private val mac: String = "",
    private val deviceProfileRepository: DeviceProfileRepository = DeviceProfileRepository(context)
) {

    companion object {
        private const val TAG = "SensorRepository"
        private const val PREFS_NAME = "sensor_prefs"

        // Legacy (non-namespaced) key names
        private const val LEGACY_KEY_TEMP = "temp"
        private const val LEGACY_KEY_HUMIDITY = "humidity"
        private const val LEGACY_KEY_BATTERY = "battery"
        private const val LEGACY_KEY_RSSI = "rssi"
        private const val LEGACY_KEY_NAME = "name"
        private const val LEGACY_KEY_MAC = "mac_address"
        private const val LEGACY_KEY_TIMESTAMP = "timestamp"
        private const val LEGACY_KEY_HAS_ERROR = "has_error"
        private const val LEGACY_KEY_IS_LOADING = "is_loading"

        internal const val KEY_BATTERY_RAW = "battery_raw"

        private fun prefix(mac: String): String =
            if (mac.isEmpty()) "" else "${mac.lowercase(Locale.ROOT).replace(":", "_")}_"

        /**
         * One-time migration: copies legacy un-namespaced sensor keys to the per-device
         * namespace. Safe to call multiple times (idempotent).
         */
        fun migrateFromGlobal(context: Context, mac: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val p = prefix(mac)
            // Only migrate if legacy keys exist and target keys are absent
            if (!prefs.contains(LEGACY_KEY_TEMP)) return
            if (prefs.contains("${p}temp")) return  // already migrated
            AppLogger.i(TAG, "Migrating sensor data for $mac")
            prefs.edit {
                if (prefs.contains(LEGACY_KEY_TEMP))
                    putFloat("${p}temp", prefs.getFloat(LEGACY_KEY_TEMP, 0f))
                if (prefs.contains(LEGACY_KEY_HUMIDITY))
                    putFloat("${p}humidity", prefs.getFloat(LEGACY_KEY_HUMIDITY, 0f))
                if (prefs.contains(LEGACY_KEY_BATTERY)) {
                    val b = prefs.getInt(LEGACY_KEY_BATTERY, 0)
                    putInt("${p}battery", b)
                    putInt("${p}$KEY_BATTERY_RAW", b)
                }
                if (prefs.contains(LEGACY_KEY_RSSI))
                    putInt("${p}rssi", prefs.getInt(LEGACY_KEY_RSSI, 0))
                if (prefs.contains(LEGACY_KEY_NAME))
                    putString("${p}name", prefs.getString(LEGACY_KEY_NAME, null))
                if (prefs.contains(LEGACY_KEY_MAC))
                    putString("${p}mac_address", prefs.getString(LEGACY_KEY_MAC, null))
                if (prefs.contains(LEGACY_KEY_TIMESTAMP))
                    putLong("${p}timestamp", prefs.getLong(LEGACY_KEY_TIMESTAMP, 0L))
                if (prefs.contains(LEGACY_KEY_HAS_ERROR))
                    putBoolean("${p}has_error", prefs.getBoolean(LEGACY_KEY_HAS_ERROR, false))
                if (prefs.contains(LEGACY_KEY_IS_LOADING))
                    putBoolean("${p}is_loading", prefs.getBoolean(LEGACY_KEY_IS_LOADING, false))
                // Remove legacy flat keys
                remove(LEGACY_KEY_TEMP); remove(LEGACY_KEY_HUMIDITY); remove(LEGACY_KEY_BATTERY)
                remove(LEGACY_KEY_RSSI); remove(LEGACY_KEY_NAME); remove(LEGACY_KEY_MAC)
                remove(LEGACY_KEY_TIMESTAMP); remove(LEGACY_KEY_HAS_ERROR); remove(
                LEGACY_KEY_IS_LOADING
            )
            }
        }

        /** Removes all sensor_prefs keys namespaced to this device (see [prefix]). */
        fun clearNamespaceForMac(context: Context, mac: String) {
            val normalized = mac.normalizedBluetoothMac()
            if (normalized.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val p = prefix(normalized)
            val keys = prefs.all.keys.filter { it.startsWith(p) }
            if (keys.isEmpty()) return
            prefs.edit { keys.forEach { remove(it) } }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val p: String get() = prefix(mac)

    private fun profileMacKey(): String = mac.uppercase(Locale.ROOT)

    private fun resolveBatteryType(): String {
        return if (mac.isNotEmpty()) {
            deviceProfileRepository.getByMac(profileMacKey())?.batteryType
                ?: BatteryType.ALKALINE
        } else {
            deviceProfileRepository.getActiveProfile()?.batteryType ?: BatteryType.ALKALINE
        }
    }

    /** Last persisted raw (0–100) or corrected display value as fallback for legacy rows. */
    private fun readPersistedRawBattery(): Int {
        return when {
            prefs.contains("${p}$KEY_BATTERY_RAW") ->
                prefs.getInt("${p}$KEY_BATTERY_RAW", 0).coerceIn(0, 100)

            prefs.contains("${p}battery") ->
                prefs.getInt("${p}battery", 0).coerceIn(0, 100)

            else -> 0
        }
    }

    /**
     * Saves sensor data with battery level correction applied.
     * Raw device percentage is stored separately, so changing a battery type can re-run correction.
     * @return The corrected SensorData for UI display consistency.
     */
    fun saveSensorData(data: SensorData): SensorData {
        // Values outside 0–100 (e.g. advert 0xFF) are treated as unknown; keep last good raw.
        val rawBattery = when (val inc = data.battery) {
            in 0..100 -> inc
            else -> {
                AppLogger.d(TAG, "Ignoring invalid battery $inc, keeping stored value")
                readPersistedRawBattery()
            }
        }
        return persistSensorData(data, rawBattery)
    }

    /**
     * Saves a connected temperature/humidity update without treating its display battery as raw.
     */
    fun saveConnectedSensorData(data: SensorData): SensorData =
        persistSensorData(data, readPersistedRawBattery())

    private fun persistSensorData(data: SensorData, rawBattery: Int): SensorData {
        val correctedBattery = BluetoothUtils.correctBatteryLevel(rawBattery, resolveBatteryType())
        val correctedData = data.copy(battery = correctedBattery)

        prefs.edit(commit = true) {
            putFloat("${p}temp", correctedData.temperature.toFloat())
            putFloat("${p}humidity", correctedData.humidity.toFloat())
            putInt("${p}$KEY_BATTERY_RAW", rawBattery)
            putInt("${p}battery", correctedData.battery)
            putInt("${p}rssi", correctedData.rssi)
            putString("${p}name", correctedData.name)
            putString("${p}mac_address", correctedData.macAddress)
            putLong("${p}timestamp", System.currentTimeMillis())
            putBoolean("${p}has_error", false)
        }

        return correctedData
    }

    /**
     * Re-applies [BluetoothUtils.correctBatteryLevel] using stored raw % and current battery type.
     * Call after the user changes alkaline/NiMH so the UI matches without waiting for a new scan.
     */
    fun reapplyBatteryCorrection(): SensorData? {
        if (!prefs.contains("${p}temp")) return null
        val raw = readPersistedRawBattery()
        val corrected = BluetoothUtils.correctBatteryLevel(raw, resolveBatteryType())
        prefs.edit(commit = true) { putInt("${p}battery", corrected) }
        return getSensorData()
    }

    fun getSensorData(): SensorData? {
        if (!prefs.contains("${p}temp")) return null
        val temp = prefs.getFloat("${p}temp", 0f).toDouble()
        val humidity = prefs.getFloat("${p}humidity", 0f).toDouble()
        val battery = prefs.getInt("${p}battery", 0)
        val rssi = prefs.getInt("${p}rssi", 0)
        val name = prefs.getString("${p}name", null)
        val macAddress = prefs.getString("${p}mac_address", "Unknown") ?: "Unknown"
        val timestamp = prefs.getLong("${p}timestamp", System.currentTimeMillis())
        return SensorData(temp, humidity, battery, rssi, name, macAddress, timestamp)
    }

    fun getLastUpdateTimestamp(): Long = prefs.getLong("${p}timestamp", 0)

    fun hasUpdateError(): Boolean = prefs.getBoolean("${p}has_error", false)

    fun setUpdateError(hasError: Boolean) {
        prefs.edit(commit = true) { putBoolean("${p}has_error", hasError) }
    }

    fun isLoading(): Boolean = prefs.getBoolean("${p}is_loading", false)

    fun setLoading(loading: Boolean) {
        prefs.edit(commit = true) { putBoolean("${p}is_loading", loading) }
    }
}
