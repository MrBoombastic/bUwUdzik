package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mrboombastic.buwudzik.utils.AppLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

private const val TAG = "DeviceProfileRepository"

/**
 * Manages the list of saved CGD1 device profiles and the currently active device.
 *
 * Storage:
 *  - "device_profiles_v1" – JSON array of [DeviceProfile] in settings_prefs
 *  - "active_device_mac" – MAC string of the selected device
 *
 * Migration: if the old single-device flat keys are present and no profiles exist yet,
 * they are automatically converted into a single DeviceProfile entry.
 */
class DeviceProfileRepository(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "settings_prefs"
        private const val KEY_PROFILES = "device_profiles_v1"
        private const val KEY_ACTIVE_MAC = "active_device_mac"

        // Legacy single-device keys (read-only during first migration)
        private const val LEGACY_KEY_MAC = "target_mac"
        private const val LEGACY_KEY_BATTERY = "battery_type"
        private const val LEGACY_KEY_SETUP = "setup_completed"

        private val json = Json { ignoreUnknownKeys = true }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Migration
    // -------------------------------------------------------------------------

    /**
     * Run once on first access. Promotes the old single-device settings into a profile
     * if no profiles exist yet.
     */
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_PROFILES)) return // already migrated

        val legacyMac = prefs.getString(LEGACY_KEY_MAC, "")?.trim() ?: ""
        if (legacyMac.isEmpty()) {
            // Nothing to migrate – write an empty list so we don't run again
            prefs.edit { putString(KEY_PROFILES, "[]") }
            return
        }

        AppLogger.i(TAG, "Migrating legacy single-device settings for $legacyMac")

        // Do not import legacy flat `battery_type`
        val profile = DeviceProfile(
            mac = legacyMac.uppercase(),
            alias = "Device 1",
            batteryType = DeviceProfile.DEFAULT_BATTERY_TYPE,
            addedAt = System.currentTimeMillis()
        )

        // Migrate per-device sub-repositories
        AlarmTitleRepository.migrateFromGlobal(context, profile.mac)
        SensorRepository.migrateFromGlobal(context, profile.mac)

        val profilesJson = json.encodeToString(listOf(profile))
        prefs.edit {
            putString(KEY_PROFILES, profilesJson)
            putString(KEY_ACTIVE_MAC, profile.mac)
            // Remove legacy flat keys
            remove(LEGACY_KEY_MAC)
            remove(LEGACY_KEY_BATTERY)
            remove(LEGACY_KEY_SETUP)
        }
        AppLogger.i(TAG, "Migration complete")
    }

    // -------------------------------------------------------------------------
    // Profile CRUD
    // -------------------------------------------------------------------------

    fun getProfiles(): List<DeviceProfile> {
        migrateIfNeeded()
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            json.decodeFromString<List<DeviceProfile>>(raw)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to parse profiles", e)
            emptyList()
        }
    }

    fun addOrUpdate(profile: DeviceProfile) {
        val current = getProfiles().toMutableList()
        val idx = current.indexOfFirst { it.mac == profile.mac }
        if (idx >= 0) {
            current[idx] = profile
        } else {
            current.add(profile)
        }
        saveProfiles(current)
    }

    fun remove(mac: String) {
        migrateIfNeeded()
        val updated = getProfiles().filter { it.mac != mac }
        saveProfiles(updated)
        // If this was the active device, clear active selection
        if (getActiveDeviceId() == mac) {
            setActiveDeviceId(null)
        }
    }

    fun getByMac(mac: String): DeviceProfile? = getProfiles().find { it.mac == mac }

    private fun saveProfiles(profiles: List<DeviceProfile>) {
        prefs.edit { putString(KEY_PROFILES, json.encodeToString(profiles)) }
    }

    // -------------------------------------------------------------------------
    // Active device
    // -------------------------------------------------------------------------

    fun getActiveDeviceId(): String? {
        migrateIfNeeded()
        return prefs.getString(KEY_ACTIVE_MAC, null)
    }

    fun setActiveDeviceId(mac: String?) {
        prefs.edit {
            if (mac == null) remove(KEY_ACTIVE_MAC) else putString(KEY_ACTIVE_MAC, mac)
        }
    }

    fun getActiveProfile(): DeviceProfile? {
        val mac = getActiveDeviceId() ?: return null
        return getByMac(mac)
    }

    // -------------------------------------------------------------------------
    // Reactive flows
    // -------------------------------------------------------------------------

    /** Emits the list of profiles whenever it changes. */
    val profilesFlow: Flow<List<DeviceProfile>> = callbackFlow {
        trySend(getProfiles())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PROFILES) trySend(getProfiles())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /** Emits the active device MAC whenever it changes. */
    val activeDeviceIdFlow: Flow<String?> = callbackFlow {
        trySend(getActiveDeviceId())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ACTIVE_MAC) trySend(getActiveDeviceId())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
