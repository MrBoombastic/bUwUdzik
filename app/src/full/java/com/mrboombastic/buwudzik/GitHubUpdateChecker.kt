package com.mrboombastic.buwudzik

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
    val body: String? = null,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadURL: String
)

abstract class GitHubUpdateChecker(
    protected val context: Context,
    private val includePrerelease: Boolean,
    private val apkNameHint: String
) : UpdateManager {

    companion object {
        private const val TAG = "GitHubUpdateChecker"
        private const val GITHUB_API_LATEST_STABLE_URL =
            "https://api.github.com/repos/MrBoombastic/clOwOck/releases/latest"
        private const val GITHUB_API_RELEASES_URL =
            "https://api.github.com/repos/MrBoombastic/clOwOck/releases?per_page=100"

        private const val NOTIFICATION_CHANNEL_ID = "update_download_channel_v3"
        private const val NOTIFICATION_ID = 1001
        private const val INDETERMINATE_NOTIFY_INTERVAL_MS = 750L
        private const val INDETERMINATE_NOTIFY_MIN_BYTES = 256 * 1024L
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    override suspend fun checkForUpdates(): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val release = if (includePrerelease) {
                client.get(GITHUB_API_RELEASES_URL).body<List<GitHubRelease>>()
                    .firstOrNull { it.assets.isNotEmpty() }
                    ?: throw IllegalStateException("No GitHub releases with assets found")
            } else {
                client.get(GITHUB_API_LATEST_STABLE_URL).body()
            }
            AppLogger.d(
                TAG,
                "Latest ${if (includePrerelease) "canary" else "stable"} release: ${release.tagName} (prerelease=${release.prerelease})"
            )

            val latestVersion = release.tagName.removePrefix("v")
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val packageVersion = packageInfo.versionName ?: "0.0.0"
            val fakeCurrent = BuildConfig.UPDATE_CHECK_DEBUG_FAKE_VERSION
            val currentVersion =
                fakeCurrent.ifEmpty {
                    packageVersion
                }

            val updateAvailable = isNewerVersion(latestVersion, currentVersion)

            val downloadUrl = release.assets.firstOrNull { asset ->
                asset.name.endsWith(".apk") && asset.name.contains(apkNameHint, ignoreCase = true)
            }?.browserDownloadURL
                ?: release.assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadURL

            UpdateCheckResult(
                updateAvailable = updateAvailable,
                latestVersion = latestVersion,
                currentVersion = currentVersion,
                downloadUrl = downloadUrl,
                changelog = extractChangelogForDisplay(release.body)
            )
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error checking for updates", e)
            throw e
        }
    }

    override suspend fun downloadAndInstall(url: String): Boolean = withContext(Dispatchers.IO) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        try {
            createNotificationChannel()

            val file = File(context.cacheDir, "clowock-update.apk")

            if (file.exists()) {
                file.delete()
            }

            showDownloadStartingNotification(notificationManager)

            client.prepareGet(url).execute { httpResponse ->
                val contentLength = httpResponse.contentLength() ?: -1L
                val channel = httpResponse.body<io.ktor.utils.io.ByteReadChannel>()

                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                var lastIndeterminateNotifyBytes = 0L
                var lastIndeterminateNotifyTime = 0L

                if (contentLength > 0) {
                    updateDownloadNotification(notificationManager, 0L, contentLength)
                } else {
                    updateIndeterminateDownloadNotification(notificationManager, 0L)
                    lastIndeterminateNotifyTime = System.currentTimeMillis()
                }

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
                            } else {
                                val now = System.currentTimeMillis()
                                if (downloadedBytes - lastIndeterminateNotifyBytes >= INDETERMINATE_NOTIFY_MIN_BYTES ||
                                    now - lastIndeterminateNotifyTime >= INDETERMINATE_NOTIFY_INTERVAL_MS
                                ) {
                                    lastIndeterminateNotifyBytes = downloadedBytes
                                    lastIndeterminateNotifyTime = now
                                    updateIndeterminateDownloadNotification(
                                        notificationManager,
                                        downloadedBytes
                                    )
                                }
                            }
                        }
                    }
                }
            }

            showCompletionNotification(notificationManager)
            launchInstaller(file)
            true
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error downloading or installing update", e)
            showErrorNotification(notificationManager)
            false
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.update_download_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.update_download_channel_desc)
            setShowBadge(true)
        }
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun applyProgressVisibility(builder: Notification.Builder): Notification.Builder {
        builder.setCategory(Notification.CATEGORY_PROGRESS)
        builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        return builder
    }

    private fun showDownloadStartingNotification(notificationManager: NotificationManager) {
        val notification = applyProgressVisibility(createBaseNotificationBuilder())
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(context.getString(R.string.update_downloading_starting))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setOnlyAlertOnce(false)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateIndeterminateDownloadNotification(
        notificationManager: NotificationManager,
        downloadedBytes: Long
    ) {
        val downloadedMB = (downloadedBytes / 1024 / 1024).toInt().coerceAtLeast(0)
        val notification = applyProgressVisibility(createBaseNotificationBuilder())
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(
                context.getString(R.string.update_downloading_unknown_size, downloadedMB)
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateDownloadNotification(
        notificationManager: NotificationManager,
        downloadedBytes: Long,
        contentLength: Long
    ) {
        if (contentLength <= 0) return
        val safeDownloaded = downloadedBytes.coerceIn(0L, contentLength)
        val totalBytes = contentLength.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val progressForBar = if (contentLength <= Int.MAX_VALUE.toLong()) {
            safeDownloaded.toInt().coerceIn(0, totalBytes)
        } else {
            ((safeDownloaded * totalBytes.toLong()) / contentLength)
                .toInt()
                .coerceIn(0, totalBytes)
        }
        val progressPercent = ((safeDownloaded * 100L) / contentLength).toInt().coerceIn(0, 100)
        val downloadedMB = (safeDownloaded / (1024L * 1024L)).toInt().coerceAtLeast(0)
        val totalMB = (contentLength / (1024L * 1024L)).toInt().coerceAtLeast(0)

        val builder = applyProgressVisibility(createBaseNotificationBuilder())
            .setContentTitle(context.getString(R.string.update_downloading_title))
            .setContentText(
                context.getString(
                    R.string.update_downloading_progress,
                    progressPercent,
                    downloadedMB,
                    totalMB
                )
            )
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            builder.style = Notification.ProgressStyle()
                .setStyledByProgress(true)
                .setProgress(progressForBar)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(totalBytes)))
        } else {
            builder.setProgress(totalBytes, progressForBar, false)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun showCompletionNotification(notificationManager: NotificationManager) {
        val notification = createBaseNotificationBuilder()
            .setContentTitle(context.getString(R.string.update_download_complete))
            .setContentText(context.getString(R.string.update_download_complete_desc))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showErrorNotification(notificationManager: NotificationManager) {
        val notification = createBaseNotificationBuilder()
            .setContentTitle(context.getString(R.string.update_download_error))
            .setContentText(context.getString(R.string.update_download_error_desc))
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createBaseNotificationBuilder(): Notification.Builder {
        return Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
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
            val latest = parseVersion(latestVersion)
            val current = parseVersion(currentVersion)

            for (i in 0 until maxOf(latest.size, current.size)) {
                val latestPart = latest.getOrNull(i) ?: 0
                val currentPart = current.getOrNull(i) ?: 0
                if (latestPart > currentPart) return true
                if (latestPart < currentPart) return false
            }
        } catch (_: Exception) {
            return false
        }
        return false
    }

    private fun parseVersion(version: String): List<Int> {
        val parts = Regex("\\d+").findAll(version).map { it.value.toIntOrNull() ?: 0 }.toList()
        return parts.ifEmpty { listOf(0) }
    }

    private fun extractChangelogForDisplay(body: String?): String? {
        val normalized = body?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val lines = normalized.lines()
        val headingRegex = Regex("^\\s{0,3}#{1,6}\\s+.*$", RegexOption.IGNORE_CASE)
        val changelogHeadingRegex =
            Regex("^\\s{0,3}#{1,6}\\s+.*changelog.*$", RegexOption.IGNORE_CASE)

        val startIndex = lines.indexOfFirst { changelogHeadingRegex.matches(it) }
        if (startIndex < 0) return normalized

        val endExclusive = (startIndex + 1 until lines.size)
            .firstOrNull { headingRegex.matches(lines[it]) }
            ?: lines.size

        val section = lines
            .subList(startIndex + 1, endExclusive)
            .joinToString("\n")
            .trim()

        return section.ifEmpty { normalized }
    }

    override fun close() {
        client.close()
    }
}
