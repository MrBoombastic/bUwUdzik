package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.mrboombastic.buwudzik.widget.SensorWidgetRefresher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stores app-level (non-device-specific) settings.
 */
class SettingsRepository(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SCAN_MODE = "scan_mode"
        const val DEFAULT_SCAN_MODE = android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED

        private const val KEY_LANGUAGE = "language"
        private const val KEY_UPDATE_INTERVAL = "update_interval"
        const val DEFAULT_LANGUAGE = "system"

        /**
         * Default widget update interval in minutes.
         * Supported intervals: 15, 30, 45, 60, 120, 240, 480, 720, 1440 minutes
         */
        const val DEFAULT_INTERVAL = 15L

        private const val KEY_SELECTED_APP = "selected_app_package"
        private const val KEY_THEME = "app_theme"
        const val DEFAULT_THEME = "system"

        private const val KEY_LAST_VERSION_CODE = "last_version_code"

        private const val KEY_RINGTONE_BASE_URL = "ringtone_base_url"
        const val DEFAULT_RINGTONE_BASE_URL = "https://qingplus.cleargrass.com/raw/rings"

        private const val KEY_SHOW_WIDGET_ERROR = "show_widget_error"
        const val DEFAULT_SHOW_WIDGET_ERROR = true

        private const val KEY_DEVICE_SHEET_SWIPE_HINT_SHOWN = "device_sheet_swipe_hint_shown"
        private const val KEY_AUTO_UPDATE_CHECK_ENABLED = "auto_update_check_enabled"
        private const val KEY_LAST_AUTO_UPDATE_CHECK_MS = "last_auto_update_check_ms"
    }

    /**
     * Updates all widgets to reflect setting changes.
     */
    suspend fun updateAllWidgets() = withContext(Dispatchers.IO) {
        SensorWidgetRefresher.updateAll(context)
    }

    var lastVersionCode: Int
        get() = prefs.getInt(KEY_LAST_VERSION_CODE, -1)
        set(value) {
            prefs.edit { putInt(KEY_LAST_VERSION_CODE, value) }
        }

    var ringtoneBaseUrl: String
        get() = prefs.getString(KEY_RINGTONE_BASE_URL, DEFAULT_RINGTONE_BASE_URL)
            ?: DEFAULT_RINGTONE_BASE_URL
        set(value) {
            val trimmed = value.trim().trimEnd('/')
            prefs.edit {
                putString(KEY_RINGTONE_BASE_URL, trimmed.ifEmpty { DEFAULT_RINGTONE_BASE_URL })
            }
        }

    var scanMode: Int
        get() = prefs.getInt(KEY_SCAN_MODE, DEFAULT_SCAN_MODE)
        set(value) {
            prefs.edit { putInt(KEY_SCAN_MODE, value) }
        }

    var language: String
        get() = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(value) {
            prefs.edit { putString(KEY_LANGUAGE, value) }
        }

    var updateInterval: Long
        get() = prefs.getLong(KEY_UPDATE_INTERVAL, DEFAULT_INTERVAL).coerceAtLeast(15)
        set(value) {
            prefs.edit { putLong(KEY_UPDATE_INTERVAL, value.coerceAtLeast(15)) }
        }

    var selectedAppPackage: String?
        get() = prefs.getString(KEY_SELECTED_APP, null)
        set(value) {
            prefs.edit { putString(KEY_SELECTED_APP, value) }
        }

    var theme: String
        get() = prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME
        set(value) {
            prefs.edit { putString(KEY_THEME, value) }
        }

    var showWidgetError: Boolean
        get() = prefs.getBoolean(KEY_SHOW_WIDGET_ERROR, DEFAULT_SHOW_WIDGET_ERROR)
        set(value) {
            prefs.edit { putBoolean(KEY_SHOW_WIDGET_ERROR, value) }
        }

    /** One-time coach mark on home: swipe / chip to open the device switcher sheet. */
    var deviceSheetSwipeHintShown: Boolean
        get() = prefs.getBoolean(KEY_DEVICE_SHEET_SWIPE_HINT_SHOWN, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DEVICE_SHEET_SWIPE_HINT_SHOWN, value) }
        }

    var autoUpdateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, true)
        set(value) {
            prefs.edit { putBoolean(KEY_AUTO_UPDATE_CHECK_ENABLED, value) }
        }

    var lastAutoUpdateCheckMs: Long
        get() = prefs.getLong(KEY_LAST_AUTO_UPDATE_CHECK_MS, 0L)
        set(value) {
            prefs.edit { putLong(KEY_LAST_AUTO_UPDATE_CHECK_MS, value.coerceAtLeast(0L)) }
        }
}
