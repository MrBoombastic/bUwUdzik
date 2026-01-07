package com.mrboombastic.buwudzik.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Repository for storing alarm titles locally.
 * Titles are stored by alarm ID since the device doesn't support titles natively.
 */
class AlarmTitleRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alarm_titles_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_PREFIX = "alarm_title_"
    }

    /**
     * Get the title for an alarm by its ID.
     */
    fun getTitle(alarmId: Int): String {
        return prefs.getString("$KEY_PREFIX$alarmId", "") ?: ""
    }

    /**
     * Set the title for an alarm by its ID.
     */
    fun setTitle(alarmId: Int, title: String) {
        prefs.edit { putString("$KEY_PREFIX$alarmId", title) }
    }

    /**
     * Delete the title for an alarm.
     */
    fun deleteTitle(alarmId: Int) {
        prefs.edit { remove("$KEY_PREFIX$alarmId") }
    }

    /**
     * Get all alarm titles.
     * Returns a map of Alarm ID to Title.
     */
    fun getAllTitles(): Map<Int, String> {
        val allEntries = prefs.all
        val titles = mutableMapOf<Int, String>()
        for ((key, value) in allEntries) {
            if (key.startsWith(KEY_PREFIX) && value is String) {
                try {
                    val id = key.substring(KEY_PREFIX.length).toInt()
                    titles[id] = value
                } catch (_: NumberFormatException) {
                    // Ignore invalid keys
                }
            }
        }
        return titles
    }
}
