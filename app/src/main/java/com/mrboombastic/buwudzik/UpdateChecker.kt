package com.mrboombastic.buwudzik

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String, val browserDownloadURL: String
)

data class UpdateCheckResult(
    val updateAvailable: Boolean, val latestVersion: String, val downloadUrl: String? = null
)

class UpdateChecker(private val context: Context) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    suspend fun checkForUpdatesWithResult(): UpdateCheckResult {
        // bruh moment for the hardcoded url
        val release: GitHubRelease =
            client.get("https://api.github.com/repos/MrBoombastic/bUwUdzik/releases/latest").body()
        Log.d("UpdateChecker", "Latest release: $release")
        val latestVersion = release.tagName.removePrefix("v")
        val currentVersion =
            context.packageManager.getPackageInfo(context.packageName, 0).versionName

        val updateAvailable = isNewerVersion(latestVersion, currentVersion)
        if (updateAvailable) {
            val asset = release.assets.firstOrNull { it.name.endsWith(".apk") }
            asset?.let {
                downloadAndInstall(it.browserDownloadURL)
            }
            return UpdateCheckResult(
                updateAvailable = true,
                latestVersion = latestVersion,
                downloadUrl = asset?.browserDownloadURL
            )
        }

        return UpdateCheckResult(
            updateAvailable = false, latestVersion = latestVersion
        )
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String?): Boolean {
        if (currentVersion == null) return true
        try {
            val latest = latestVersion.split(".").map { it.toInt() }
            val current = currentVersion.split(".").map { it.toInt() }

            for (i in 0 until maxOf(latest.size, current.size)) {
                val latestPart = latest.getOrNull(i) ?: 0
                val currentPart = current.getOrNull(i) ?: 0
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
        } catch (e: NumberFormatException) {
            Log.e("UpdateChecker", "Failed to parse version names", e)
            return false // Or handle as a special case
        }
        return false
    }

    private suspend fun downloadAndInstall(url: String) {
        withContext(Dispatchers.IO) {
            try {
                val response: HttpResponse = client.get(url)
                val bytes = response.body<ByteArray>()
                val file = File(context.cacheDir, "update.apk")
                file.writeBytes(bytes)

                val intent = Intent(Intent.ACTION_VIEW)
                val uri =
                    FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                intent.setDataAndType(uri, "application/vnd.android.package-archive")
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e("UpdateChecker", "Error downloading or installing update", e)
            }
        }
    }
}

