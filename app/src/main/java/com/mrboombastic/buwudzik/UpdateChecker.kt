package com.mrboombastic.buwudzik


import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.mrboombastic.buwudzik.utils.AppLogger
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.http.contentLength
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadURL: String
)

data class UpdateCheckResult(
    val updateAvailable: Boolean,
    val latestVersion: String,
    val currentVersion: String,
    val downloadUrl: String? = null
)

class UpdateChecker(private val context: Context) {

    companion object {
        private const val TAG = "UpdateChecker"
        private const val GITHUB_API_URL =
            "https://api.github.com/repos/MrBoombastic/bUwUdzik/releases/latest"
        private const val NOTIFICATION_CHANNEL_ID = "update_download_channel_v2"
        private const val NOTIFICATION_ID = 1001
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    /**
     * Check for updates without downloading.
     * Returns information about available updates.
     */
    suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release: GitHubRelease = client.get(GITHUB_API_URL).body()
            AppLogger.d(TAG, "Latest release: ${release.tagName}")

            val latestVersion = release.tagName.removePrefix("v")
            val currentVersion =
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

            val updateAvailable = isNewerVersion(latestVersion, currentVersion)
            val downloadUrl =
                release.assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadURL

            UpdateCheckResult(
                updateAvailable = updateAvailable,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                downloadUrl = downloadUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
            throw e
        }
    }

    /**
     * Download and install an update from the given URL.
     * Shows a notification with download progress.
     */
    suspend fun downloadAndInstall(url: String): Boolean = withContext(Dispatchers.IO) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            createNotificationChannel()

            val file = File(context.cacheDir, "clowock-update.apk")

            // Delete old APK if exists
            if (file.exists()) {
                file.delete()
            }

            AppLogger.d(TAG, "Starting download from: $url")

            client.prepareGet(url).execute { httpResponse ->
                val contentLength = httpResponse.contentLength() ?: -1L
                val channel = httpResponse.body<io.ktor.utils.io.ByteReadChannel>()

                var downloadedBytes = 0L
                val buffer = ByteArray(8192)

                file.outputStream().use { outputStream ->
                    while (!channel.isClosedForRead) {
                        val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (contentLength > 0) {
                                updateDownloadNotification(
                                    notificationManager,
                                    downloadedBytes,
                                    contentLength
                                )
                            }
                        }
                    }
                }
            }

            AppLogger.d(TAG, "Download complete, launching installer")
            showCompletionNotification(notificationManager)
            launchInstaller(file)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading or installing update", e)
            showErrorNotification(notificationManager)
            false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_download_channel_name),
            NotificationManager.IMPORTANCE_LOW // LOW to avoid sound spam during progress updates
        ).apply {
            description = context.getString(R.string.update_download_channel_desc)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateDownloadNotification(
        notificationManager: NotificationManager,
        downloadedBytes: Long,
        contentLength: Long
    ) {
        val progress = downloadedBytes.toInt()
        val totalBytes = contentLength.toInt()
        val progressPercent = (downloadedBytes * 100 / contentLength).toInt()
        val downloadedMB = downloadedBytes / 1024 / 1024
        val totalMB = contentLength / 1024 / 1024

        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(
                context.getString(
                    R.string.update_downloading_progress,
                    progressPercent,
                    downloadedMB.toInt(),
                    totalMB.toInt()
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            notification.style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(progress)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(totalBytes)))
        } else {
            notification.setProgress(totalBytes, progress, false)
        }

        notificationManager.notify(NOTIFICATION_ID, notification.build())
    }

    private fun showCompletionNotification(notificationManager: NotificationManager) {
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_download_complete))
            .setContentText(context.getString(R.string.update_download_complete_desc))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(notificationManager: NotificationManager) {
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(context.getString(R.string.update_download_error))
            .setContentText(context.getString(R.string.update_download_error_desc))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun launchInstaller(file: File) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
        try {
            val latest = latestVersion.split(".").map { it.toIntOrNull() ?: 0 }
            val current = currentVersion.split(".").map { it.toIntOrNull() ?: 0 }

            for (i in 0 until maxOf(latest.size, current.size)) {
                val latestPart = latest.getOrNull(i) ?: 0
                val currentPart = current.getOrNull(i) ?: 0
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "Failed to parse version names: latest=$latestVersion, current=$currentVersion",
                e
            )
            return false
        }
        return false
    }

    /**
     * Close the HTTP client when done.
     * Call this when the UpdateChecker is no longer needed.
     */
    fun close() {
        client.close()
    }
}



