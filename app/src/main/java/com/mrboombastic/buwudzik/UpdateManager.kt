package com.mrboombastic.buwudzik

import android.content.Context

data class UpdateCheckResult(
    val updateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String? = null,
    val changelog: String? = null
)

interface UpdateManager {
    suspend fun checkForUpdates(includePrerelease: Boolean): UpdateCheckResult
    suspend fun downloadAndInstall(url: String): Boolean
    fun close()

    companion object {
        fun create(context: Context): UpdateManager {
            return UpdateChecker(context)
        }
    }
}
