package com.mrboombastic.buwudzik

import android.content.Context

/**
 * No-op implementation for Google Play Store compliance.
 * This version does not contain any code for contacting GitHub.
 */
class UpdateChecker(private val context: Context) : UpdateManager {
    override suspend fun checkForUpdates(includePrerelease: Boolean): UpdateCheckResult {
        return UpdateCheckResult(
            updateAvailable = false,
            latestVersion = "0.0.0",
            currentVersion = "0.0.0"
        )
    }

    override suspend fun downloadAndInstall(url: String): Boolean {
        return false
    }

    override fun close() {
        // No-op
    }
}
