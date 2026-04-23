package com.mrboombastic.buwudzik

data class UpdateCheckResult(
    val updateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String? = null,
    val changelog: String? = null
)
