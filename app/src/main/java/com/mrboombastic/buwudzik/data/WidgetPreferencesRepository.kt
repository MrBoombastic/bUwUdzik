package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stores the appWidgetId → device MAC mapping so each widget instance knows
 * which device to display.
 */
class WidgetPreferencesRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "widget_prefs"
        private const val KEY_PREFIX = "widget_mac_"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getDeviceMacForWidget(appWidgetId: Int): String? =
        prefs.getString("$KEY_PREFIX$appWidgetId", null)

    fun setDeviceMacForWidget(appWidgetId: Int, mac: String) {
        prefs.edit { putString("$KEY_PREFIX$appWidgetId", mac) }
    }

    fun removeWidget(appWidgetId: Int) {
        prefs.edit { remove("$KEY_PREFIX$appWidgetId") }
    }

    /** Returns all stored (appWidgetId → MAC) pairs. */
    fun getAllWidgetMacs(): Map<Int, String> {
        return prefs.all
            .filter { (k, _) -> k.startsWith(KEY_PREFIX) }
            .mapNotNull { (k, v) ->
                val id = k.removePrefix(KEY_PREFIX).toIntOrNull() ?: return@mapNotNull null
                val mac = v as? String ?: return@mapNotNull null
                id to mac
            }
            .toMap()
    }
}
