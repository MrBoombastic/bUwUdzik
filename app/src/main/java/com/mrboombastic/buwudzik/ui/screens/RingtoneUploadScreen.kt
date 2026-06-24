package com.mrboombastic.buwudzik.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.R
import com.mrboombastic.buwudzik.audio.AudioConverter
import com.mrboombastic.buwudzik.audio.AudioTrimmerDialog
import com.mrboombastic.buwudzik.audio.ChannelMode
import com.mrboombastic.buwudzik.data.SettingsRepository
import com.mrboombastic.buwudzik.device.BleConstants
import com.mrboombastic.buwudzik.ui.components.CustomSnackbarHost
import com.mrboombastic.buwudzik.ui.components.ProgressCard
import com.mrboombastic.buwudzik.ui.components.StandardTopBar
import com.mrboombastic.buwudzik.utils.AppLogger
import com.mrboombastic.buwudzik.utils.FileDownloadUtils
import com.mrboombastic.buwudzik.utils.TimeFormatUtils
import com.mrboombastic.buwudzik.viewmodels.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


data class OnlineRingtone(
    val name: String, val url: String, val signature: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as OnlineRingtone
        return name == other.name && url == other.url && signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + url.hashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RingtoneUploadScreen(navController: NavController, viewModel: MainViewModel) {
    val context = LocalContext.current
    val settings by viewModel.deviceSettings.collectAsState()
    val isBusy by viewModel.isBusy.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val repository = remember { SettingsRepository(context) }

    // Make ringtone base URL reactive to repository changes
    var ringtoneBaseUrl by remember { mutableStateOf(repository.ringtoneBaseUrl) }

    // Watch for lifecycle changes to refresh URL when returning from settings
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Refresh URL when returning to this screen
                val currentUrl = repository.ringtoneBaseUrl
                if (currentUrl != ringtoneBaseUrl) {
                    ringtoneBaseUrl = currentUrl
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var isUploading by remember { mutableStateOf(false) }
    var uploadProgress by remember { mutableFloatStateOf(0f) }
    var uploadStartTime by remember { mutableLongStateOf(0L) }
    var estimatedTimeRemaining by remember { mutableStateOf<Long?>(null) }
    var selectedCustomUri by remember { mutableStateOf<Uri?>(null) }
    var selectedOnlineRingtone by remember { mutableStateOf<OnlineRingtone?>(null) }
    var showAudioTrimmer by remember { mutableStateOf(false) }
    var trimStartMs by remember { mutableLongStateOf(0L) }
    var trimDurationMs by remember { mutableLongStateOf(10000L) }
    var isConverting by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    var isLoadingManifest by remember { mutableStateOf(false) }

    val audioConverter = remember { AudioConverter(context) }

    // Current ringtone from device
    val customRingtoneName = stringResource(R.string.custom_ringtone_name)
    val currentRingtoneName = settings?.getRingtoneName() ?: customRingtoneName
    val currentSignature = settings?.ringtoneSignature

    // Pre-capture string resources for use in coroutines
    val uploadCompleteText = stringResource(R.string.upload_complete)
    val uploadFailedText = stringResource(R.string.upload_failed)
    val downloadingText = stringResource(R.string.downloading_ringtone)
    val downloadingListText = stringResource(R.string.downloading_list)
    val noAudioText = stringResource(R.string.error_no_audio_selected)
    val downloadFailedText = stringResource(R.string.error_download_failed)
    val errorManifestLoadFailedText = stringResource(R.string.error_manifest_load_failed)

    fun showError(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = SnackbarDuration.Long
            )
        }
    }

    // Available online ringtones - now dynamically loaded from manifest
    var onlineRingtones by remember { mutableStateOf<List<OnlineRingtone>>(emptyList()) }

    // Load ringtones from manifest
    LaunchedEffect(ringtoneBaseUrl) {
        isLoadingManifest = true
        try {
            // Fetch and parse JSON manifest
            val manifestData = withContext(Dispatchers.IO) {
                FileDownloadUtils.downloadFile(ringtoneBaseUrl)
            }

            val manifestString = String(manifestData).trim()
            val json = org.json.JSONObject(manifestString)
            val discoveredRingtones = mutableListOf<OnlineRingtone>()

            json.keys().forEach { hexKey ->
                try {
                    val ringtoneObj = json.getJSONObject(hexKey)
                    val name = ringtoneObj.optString("name", "Ringtone")
                    val wavUrl = ringtoneObj.optString("wav", "")

                    if (wavUrl.isNotEmpty()) {
                        // Convert hex string to byte array
                        val signature =
                            hexKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        discoveredRingtones.add(
                            OnlineRingtone(
                                name = name,
                                url = wavUrl,
                                signature = signature
                            )
                        )
                    }
                } catch (e: Exception) {
                    AppLogger.w(
                        "RingtoneUpload",
                        "Failed to parse ringtone entry for $hexKey: ${e.message}"
                    )
                }
            }
            onlineRingtones = discoveredRingtones
            AppLogger.d(
                "RingtoneUpload",
                "Loaded ${discoveredRingtones.size} ringtones from JSON manifest"
            )
        } catch (e: Exception) {
            AppLogger.e(
                "RingtoneUpload",
                "Failed to load ringtones from $ringtoneBaseUrl: ${e.message}"
            )
            // Keep list empty on failure
            onlineRingtones = emptyList()
            showError(errorManifestLoadFailedText.format(e.message ?: ""))
        } finally {
            isLoadingManifest = false
        }
    }

    // File picker launcher
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedCustomUri = it
            selectedOnlineRingtone = null
            showAudioTrimmer = true // Show trimmer first for custom files
        }
    }


    fun convertAndUpload(targetSignature: ByteArray, channelMode: ChannelMode) {
        coroutineScope.launch {
            isUploading = true
            uploadProgress = 0f

            try {
                val pcmData: ByteArray

                if (selectedCustomUri != null) {
                    // Convert local file with trimming
                    isConverting = true
                    AppLogger.d(
                        "RingtoneUpload",
                        "Converting: start=${trimStartMs}ms, dur=${trimDurationMs}ms, mode=$channelMode"
                    )
                    val result = withContext(Dispatchers.IO) {
                        audioConverter.convertToPcm(
                            selectedCustomUri!!,
                            startMs = trimStartMs,
                            durationMs = trimDurationMs,
                            channelMode = channelMode
                        )
                    }
                    pcmData = audioConverter.addPadding(result.pcmData)

                    // Save debug copy
                    try {
                        val debugFile = File(context.cacheDir, "last_upload_debug.wav")
                        withContext(Dispatchers.IO) {
                            AudioConverter.saveAsWav(result.pcmData, debugFile)
                        }
                        AppLogger.d(
                            "RingtoneUpload", "Saved debug copy to ${debugFile.absolutePath}"
                        )
                    } catch (e: Exception) {
                        AppLogger.e("RingtoneUpload", "Failed to save debug copy", e)
                    }

                    isConverting = false
                } else {
                    showError(noAudioText)
                    isUploading = false
                    return@launch
                }

                // Upload to device
                uploadStartTime = System.currentTimeMillis()
                estimatedTimeRemaining = null

                val success = viewModel.deviceController.uploadAudio(
                    audioData = pcmData, signature = targetSignature
                ) { progress ->
                    uploadProgress = progress

                    // Calculate ETA
                    if (progress > 0.05f) { // Start calculating after 5% to get stable estimate
                        val elapsed = System.currentTimeMillis() - uploadStartTime
                        val totalEstimated = (elapsed / progress).toLong()
                        estimatedTimeRemaining = (totalEstimated - elapsed).coerceAtLeast(0)
                    }
                }

                if (success) {
                    snackbarHostState.showSnackbar(
                        message = uploadCompleteText, duration = SnackbarDuration.Short
                    )
                    // Refresh settings to get updated ringtone
                    viewModel.reloadDeviceSettings()
                } else {
                    showError(uploadFailedText)
                }
            } catch (e: Exception) {
                showError(e.message ?: uploadFailedText)
            } finally {
                isUploading = false
                isConverting = false
            }
        }
    }

    // Audio trimmer dialog for custom files
    if (showAudioTrimmer && selectedCustomUri != null) {
        AudioTrimmerDialog(
            uri = selectedCustomUri!!,
            onConfirm = { startMs, durationMs, channelMode ->
                trimStartMs = startMs
                trimDurationMs = durationMs
                showAudioTrimmer = false

                // Use online ringtone signature if selected, otherwise fallback to custom slot
                val targetSig =
                    selectedOnlineRingtone?.signature ?: BleConstants.getCustomSlotSignature(
                        currentSignature
                    )

                convertAndUpload(targetSig, channelMode)
            },
            onDismiss = {
                showAudioTrimmer = false
                // Clean up temp file if cancelled
                if (selectedCustomUri?.lastPathSegment == "temp_ringtone.wav") {
                    try {
                        File(context.cacheDir, "temp_ringtone.wav").delete()
                    } catch (e: Exception) {
                        AppLogger.e("RingtoneUpload", "Failed to delete temp file", e)
                    }
                }
                selectedCustomUri = null
            })
    }

    Scaffold(
        snackbarHost = { CustomSnackbarHost(snackbarHostState) },
        topBar = {
            StandardTopBar(
                title = stringResource(R.string.ringtone_upload_title),
                navController = navController,
                showProgress = isUploading || isBusy || isConverting || isDownloading || isLoadingManifest,
                navigationEnabled = !isUploading
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current ringtone info
            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.current_ringtone_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = currentRingtoneName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Upload progress
            if (isUploading || isDownloading || isLoadingManifest) {
                val title = when {
                    isLoadingManifest -> downloadingListText
                    isDownloading -> downloadingText
                    isConverting -> stringResource(R.string.converting_audio)
                    else -> stringResource(
                        R.string.uploading_progress,
                        (uploadProgress * 100).toInt()
                    )
                }
                val statusText = if (!isDownloading && !isConverting && !isLoadingManifest) {
                    estimatedTimeRemaining?.let { eta ->
                        stringResource(R.string.eta_label, TimeFormatUtils.formatTime(eta))
                    }
                } else {
                    null
                }
                ProgressCard(
                    title = title,
                    progress = if (isConverting || isDownloading || isLoadingManifest) 0f else uploadProgress,
                    statusText = statusText,
                    showPercent = false
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Custom file option
            Text(
                text = stringResource(R.string.custom_ringtone_header),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            OutlinedButton(
                onClick = { filePicker.launch("audio/*") },
                enabled = !isUploading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.AudioFile, contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(text = selectedCustomUri?.let {
                    stringResource(R.string.file_selected)
                } ?: stringResource(R.string.select_audio_file))
            }

            Text(
                text = stringResource(R.string.audio_format_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Online ringtones section
            Text(
                text = stringResource(R.string.online_ringtones_header),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            // Ringtone items or empty state
            if (onlineRingtones.isEmpty() && !isLoadingManifest) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.no_ringtones_available),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.check_repository_url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                return@Scaffold
            }
            // Ringtone items
            onlineRingtones.forEach { ringtone ->
                val isCurrentRingtone = currentSignature.safeContentEquals(ringtone.signature)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedOnlineRingtone == ringtone, onClick = {
                                if (!isCurrentRingtone && !isUploading && !isDownloading) {
                                    selectedOnlineRingtone = ringtone
                                    selectedCustomUri = null

                                    // Download and show trimmer instead of direct upload
                                    coroutineScope.launch {
                                        isDownloading = true
                                        try {
                                            var wavData = downloadPcmFile(ringtone.url)

                                            // Check if we got the JSON manifest instead of WAV
                                            // server might return the manifest for 404s or root queries
                                            val firstChar =
                                                if (wavData.isNotEmpty()) wavData[0].toInt()
                                                    .toChar() else ' '
                                            if (firstChar == '{') {
                                                try {
                                                    val jsonStr = String(wavData)
                                                    val json = org.json.JSONObject(jsonStr)
                                                    val sigHex =
                                                        ringtone.signature.joinToString("") {
                                                            "%02x".format(it)
                                                        }

                                                    val realUrl = if (json.has(sigHex)) {
                                                        // Assuming schema {"hex": {"wav": "url", ...}}
                                                        json.getJSONObject(sigHex)
                                                            .getString("wav")
                                                    } else {
                                                        null
                                                    }

                                                    if (realUrl != null) {
                                                        wavData = downloadPcmFile(realUrl)
                                                    } else {
                                                        throw IOException("Could not find ringtone entry in manifest for signature $sigHex")
                                                    }
                                                } catch (e: Exception) {
                                                    throw IOException(
                                                        "Failed to parse JSON manifest: ${e.message}. Content start: ${
                                                            String(
                                                                wavData.take(50).toByteArray()
                                                            )
                                                        }"
                                                    )
                                                }
                                            }

                                            // Validate WAV header
                                            val header = String(wavData.take(4).toByteArray())
                                            if (header != "RIFF") {
                                                // Log the first few bytes to see what we got (e.g. HTML error)
                                                val contentPreview =
                                                    String(wavData.take(100).toByteArray())
                                                throw IOException("Invalid WAV file. Header: '$header'. Content: '$contentPreview'")
                                            }

                                            // Save as WAV directly (remote file is .wav)
                                            val file =
                                                File(context.cacheDir, "temp_ringtone.wav")
                                            FileOutputStream(file).use { it.write(wavData) }
                                            selectedCustomUri = Uri.fromFile(file)
                                            showAudioTrimmer = true
                                        } catch (e: Exception) {
                                            showError(e.message ?: downloadFailedText)
                                            selectedOnlineRingtone = null
                                        } finally {
                                            isDownloading = false
                                        }
                                    }
                                }
                            }, role = Role.RadioButton
                        ), colors = CardDefaults.cardColors(
                        containerColor = when {
                            isCurrentRingtone -> MaterialTheme.colorScheme.secondaryContainer
                            selectedOnlineRingtone == ringtone -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = if (isCurrentRingtone) MaterialTheme.colorScheme.secondary
                            else MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = ringtone.name,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            if (isCurrentRingtone) {
                                Text(
                                    text = stringResource(R.string.current_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        if (!isCurrentRingtone) {
                            RadioButton(
                                selected = selectedOnlineRingtone == ringtone,
                                onClick = null,
                                enabled = !isUploading
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun downloadPcmFile(url: String): ByteArray =
    FileDownloadUtils.downloadFile(url)

private fun ByteArray?.safeContentEquals(other: ByteArray?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return this.contentEquals(other) // Now safe - calls Kotlin's built-in contentEquals
}
