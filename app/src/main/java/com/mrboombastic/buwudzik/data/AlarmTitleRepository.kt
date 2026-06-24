package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mrboombastic.buwudzik.utils.AppLogger

/**
 * Repository for storing alarm titles locally.
 * Titles are stored per-device by alarm ID since the device doesn't support titles natively.
 *
 * @param mac Device MAC address (used to namespace keys). When empty, it falls back to the
 *            legacy global namespace (migration only).
 */
class AlarmTitleRepository(context: Context, private val mac: String = "") {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alarm_titles_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AlarmTitleRepository"
        private const val LEGACY_PREFIX = "alarm_title_"

        /** Key prefix for a specific device: "alarm_title_58_2d_34_50_a0_81_<id>" */
        private fun devicePrefix(mac: String) =
            "alarm_title_${mac.lowercase().replace(":", "_")}_"

        /**
         * One-time migration: copies legacy un-namespaced keys to the per-device namespace.
         * Safe to call multiple times (idempotent).
         */
        fun migrateFromGlobal(context: Context, mac: String) {
            val prefs = context.getSharedPreferences("alarm_titles_prefs", Context.MODE_PRIVATE)
            val devPrefix = devicePrefix(mac)
            val legacyEntries = prefs.all
                .filter { (k, v) ->
                    // Legacy keys are "alarm_title_<int>" — numeric suffix only
                    if (!k.startsWith(LEGACY_PREFIX) || v !is String) return@filter false
                    val suffix = k.removePrefix(LEGACY_PREFIX)
                    suffix.toIntOrNull() != null
                }
            if (legacyEntries.isEmpty()) return
            AppLogger.i(TAG, "Migrating ${legacyEntries.size} alarm titles for $mac")
            prefs.edit {
                for ((k, v) in legacyEntries) {
                    val idPart = k.removePrefix(LEGACY_PREFIX)
                    // Only migrate purely numeric IDs (legacy keys were "alarm_title_<int>")
                    if (idPart.toIntOrNull() != null) {
                        putString("$devPrefix$idPart", v as String)
                        remove(k)
                    }
                }
            }
        }

        /** Removes all alarm title keys for this device. */
        fun clearNamespaceForMac(context: Context, mac: String) {
            val normalized = mac.normalizedBluetoothMac()
            if (normalized.isEmpty()) return
            val prefs = context.getSharedPreferences("alarm_titles_prefs", Context.MODE_PRIVATE)
            val devPrefix = devicePrefix(normalized)
            val keys = prefs.all.keys.filter { it.startsWith(devPrefix) }
            if (keys.isEmpty()) return
            prefs.edit { keys.forEach { remove(it) } }
        }
    }

    private val prefix: String
        get() = if (mac.isEmpty()) LEGACY_PREFIX else devicePrefix(mac)

    fun getTitle(alarmId: Int): String =
        prefs.getString("${prefix}$alarmId", "") ?: ""

    fun setTitle(alarmId: Int, title: String) {
        prefs.edit { putString("${prefix}$alarmId", title) }
    }

    fun deleteTitle(alarmId: Int) {
        prefs.edit { remove("${prefix}$alarmId") }
    }

    /** Returns a map of alarm ID → title for this device. */
    fun getAllTitles(): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(prefix) && value is String) {
                val idStr = key.removePrefix(prefix)
                val id = idStr.toIntOrNull() ?: continue
                result[id] = value
            }
        }
        return result
    }
}
