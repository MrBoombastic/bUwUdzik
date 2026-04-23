package com.mrboombastic.buwudzik

interface UpdateManager {
    /**
     * Check for updates from GitHub (Stable or Canary depending on flavor).
     * Returns information about available updates.
     */
    suspend fun checkForUpdates(): UpdateCheckResult

    /**
     * Download and install an update from the given URL.
     * Shows a notification with download progress.
     */
    suspend fun downloadAndInstall(url: String): Boolean

    /**
     * Close any resources (like HTTP clients) when done.
     */
    fun close()

    companion object
}
