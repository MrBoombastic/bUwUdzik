package com.mrboombastic.buwudzik.data

import android.content.Context

/**
 * Debug-only dump of app-owned SharedPreferences (no fallbacks, no migration).
 * Used to compare profile MAC, token keys, and raw stored entries.
 */
object DebugSavedDataInspector {

    private val PREFS_NAMES = listOf(
        "QP_tokens",
        "settings_prefs",
        "sensor_prefs",
        "alarm_titles_prefs",
        "widget_prefs",
    )

    fun buildReport(context: Context, activeMac: String): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        sb.appendLine("=== Pairing hints ===")
        if (activeMac.isNotEmpty()) {
            val storage = TokenStorage(app)
            sb.appendLine("activeMac (repo): $activeMac")
            sb.appendLine("token pref key: ${storage.tokenPreferenceKey(activeMac)}")
            sb.appendLine("isPaired (lookup): ${storage.isPaired(activeMac)}")
        } else {
            sb.appendLine("(no active device MAC)")
        }
        sb.appendLine()

        for (name in PREFS_NAMES) {
            appendPrefsSection(app, name, sb)
        }
        return sb.toString().trimEnd()
    }

    private fun appendPrefsSection(context: Context, prefsName: String, out: StringBuilder) {
        val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        val all = prefs.all
        out.appendLine("=== $prefsName (${all.size} keys) ===")
        if (all.isEmpty()) {
            out.appendLine("(empty)")
            out.appendLine()
            return
        }
        for ((key, value) in all.entries.sortedBy { it.key }) {
            out.appendLine("$key = ${valueToDebugString(value)}")
        }
        out.appendLine()
    }

    private fun valueToDebugString(value: Any?): String = when (value) {
        null -> "null"
        is Set<*> -> value.joinToString(", ")
        else -> value.toString()
    }
}
