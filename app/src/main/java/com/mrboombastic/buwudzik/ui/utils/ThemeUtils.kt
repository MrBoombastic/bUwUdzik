package com.mrboombastic.buwudzik.ui.utils

import androidx.appcompat.app.AppCompatDelegate

/**
 * Theme-related utility functions
 */
object ThemeUtils {

    /**
     * Convert theme string to AppCompat night mode constant
     */
    fun themeToNightMode(theme: String): Int = when (theme) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * Apply theme immediately
     */
    fun applyTheme(theme: String) {
        AppCompatDelegate.setDefaultNightMode(themeToNightMode(theme))
    }
}

