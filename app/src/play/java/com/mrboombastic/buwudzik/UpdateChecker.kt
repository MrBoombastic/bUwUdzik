package com.mrboombastic.buwudzik

/**
 * No-op implementation for Google Play Store compliance.
 * This version does not contain any code for contacting GitHub or installing APKs.
 */
class UpdateChecker : UpdateManager {
    override suspend fun checkForUpdates(): UpdateCheckResult {
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

fun UpdateManager.Companion.create(): UpdateManager {
    return UpdateChecker()
}
