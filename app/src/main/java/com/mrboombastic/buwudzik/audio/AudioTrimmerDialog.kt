package com.mrboombastic.buwudzik.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrboombastic.buwudzik.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private const val MAX_OUTPUT_SIZE = 98_000 // 98KB limit for device

/**
 * Extract waveform amplitudes from audio file for visualization.
 * Uses fewer samples for longer files to keep performance good.
 */
suspend fun extractWaveform(context: Context, uri: Uri, targetSamples: Int = 150): FloatArray =
    withContext(Dispatchers.IO) {
        val amplitudes = FloatArray(targetSamples)

        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var audioTrackIdx = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIdx = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIdx < 0 || audioFormat == null) {
                return@withContext amplitudes
            }

            extractor.selectTrack(audioTrackIdx)

            val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val allSamples = mutableListOf<Short>()
            var inputDone = false
            var outputDone = false
            val maxSamplesToCollect = 300000 // Reduced limit for faster loading

            while (!outputDone && allSamples.size < maxSamplesToCollect) {
                if (!inputDone) {
                    val inputIdx = codec.dequeueInputBuffer(10000)
                    if (inputIdx >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIdx)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)

                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outputIdx >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }

                    if (info.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIdx)!!
                        val samples = ShortArray(info.size / 2)
                        outputBuffer.asShortBuffer().get(samples)

                        // Aggressive downsampling
                        val step = max(1, samples.size / 500)
                        for (i in samples.indices step step) {
                            if (allSamples.size < maxSamplesToCollect) {
                                allSamples.add(samples[i])
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIdx, false)
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            // Compute amplitudes for visualization
            if (allSamples.isNotEmpty()) {
                val samplesPerBucket = max(1, allSamples.size / targetSamples)
                for (i in 0 until targetSamples) {
                    val startIdx = i * samplesPerBucket
                    val endIdx = min(startIdx + samplesPerBucket, allSamples.size)
                    if (startIdx < allSamples.size) {
                        var maxAmp = 0
                        for (j in startIdx until endIdx) {
                            maxAmp = max(maxAmp, kotlin.math.abs(allSamples[j].toInt()))
                        }
                        amplitudes[i] = maxAmp / 32768f
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AudioTrimmer", "Error extracting waveform", e)
        }

        amplitudes
    }

/**
 * Get audio duration in milliseconds
 */
suspend fun getAudioDuration(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
    try {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                extractor.release()
                return@withContext durationUs / 1000
            }
        }
        extractor.release()
    } catch (e: Exception) {
        android.util.Log.e("AudioTrimmer", "Error getting duration", e)
    }
    0L
}

@Composable
fun AudioTrimmerDialog(
    uri: Uri, onConfirm: (startMs: Long, durationMs: Long) -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var waveform by remember { mutableStateOf<FloatArray?>(null) }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }

    // Selection start position in milliseconds
    var selectionStartMs by remember { mutableLongStateOf(0L) }

    // Playback state
    var isPlaying by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableFloatStateOf(0f) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    // Text input state
    var startTimeText by remember { mutableStateOf("0:00") }

    // Max duration based on 98KB limit at 8kHz
    val maxDurationMs =
        ((MAX_OUTPUT_SIZE - AudioConverter.PADDING_BOUNDARY).toFloat() / AudioConverter.SAMPLE_RATE * 1000).toLong()

    // User-adjustable duration (1s to max)
    var userDurationMs by remember { mutableLongStateOf(maxDurationMs) }

    // Calculate actual selection duration (capped by file length and user choice)
    val selectionDurationMs =
        min(userDurationMs, min(maxDurationMs, totalDurationMs - selectionStartMs)).coerceAtLeast(
            200L
        )
    val selectionEndMs = selectionStartMs + selectionDurationMs

    // Calculate size estimate
    val rawSize = (selectionDurationMs * AudioConverter.SAMPLE_RATE / 1000).toInt()
    val paddingNeeded = if (rawSize % AudioConverter.PADDING_BOUNDARY == 0) 0
    else AudioConverter.PADDING_BOUNDARY - (rawSize % AudioConverter.PADDING_BOUNDARY)
    val estimatedSize = rawSize + paddingNeeded
    val isValidSize = estimatedSize <= MAX_OUTPUT_SIZE && selectionDurationMs > 0

    // Cleanup media player on dispose
    DisposableEffect(Unit) {
        onDispose {
            mediaPlayer?.release()
            mediaPlayer = null
        }
    }

    // Load waveform and duration
    LaunchedEffect(uri) {
        isLoading = true
        totalDurationMs = getAudioDuration(context, uri)
        waveform = extractWaveform(context, uri, 150)
        isLoading = false
    }

    // Sync text input with selection
    LaunchedEffect(selectionStartMs) {
        startTimeText = formatTimeInput(selectionStartMs)
    }

    // Update playback position while playing
    LaunchedEffect(isPlaying) {
        if (isPlaying && mediaPlayer != null) {
            while (isActive && isPlaying && mediaPlayer?.isPlaying == true) {
                val mp = mediaPlayer ?: break
                val currentPos = mp.currentPosition.toLong()

                if (selectionDurationMs > 0) {
                    playbackPosition =
                        ((currentPos - selectionStartMs).toFloat() / selectionDurationMs).coerceIn(
                            0f, 1f
                        )
                }

                // Stop when reaching end of selection
                if (currentPos >= selectionEndMs) {
                    isPlaying = false
                    mp.pause()
                    playbackPosition = 0f
                }

                delay(50)
            }
        }
    }

    fun playPreview() {
        coroutineScope.launch {
            try {
                mediaPlayer?.release()

                val mp = MediaPlayer().apply {
                    setDataSource(context, uri)
                    prepare()
                    seekTo(selectionStartMs.toInt())
                    start()
                }
                mediaPlayer = mp
                isPlaying = true
                playbackPosition = 0f
            } catch (e: Exception) {
                android.util.Log.e("AudioTrimmer", "Error playing preview", e)
                isPlaying = false
            }
        }
    }

    fun stopPreview() {
        mediaPlayer?.pause()
        isPlaying = false
        playbackPosition = 0f
    }

    fun seekBy(deltaMs: Long) {
        stopPreview()
        val newStart = (selectionStartMs + deltaMs).coerceIn(
            0L, (totalDurationMs - selectionDurationMs).coerceAtLeast(0L)
        )
        selectionStartMs = newStart
    }

    fun parseAndSetStartTime(text: String) {
        val parsed = parseTimeInput(text)
        if (parsed != null) {
            selectionStartMs = parsed.coerceIn(
                0L, (totalDurationMs - min(selectionDurationMs, maxDurationMs)).coerceAtLeast(0L)
            )
        }
    }

    AlertDialog(onDismissRequest = onDismiss, title = {
        Text(stringResource(R.string.trim_audio_title))
    }, text = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(32.dp))
                Text(
                    text = stringResource(R.string.loading_audio),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                // Start time input row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.start_label) + ":",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    OutlinedTextField(
                        value = startTimeText,
                        onValueChange = { startTimeText = it },
                        modifier = Modifier.width(100.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(textAlign = TextAlign.Center)
                    )

                    // Apply button implicit on blur, but also on keyboard done

                    Row {
                        IconButton(onClick = { seekBy(-200) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "-"
                            )
                        }
                        IconButton(onClick = { seekBy(200) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = "+"
                            )
                        }
                    }
                }

                // Apply time input when focus lost
                LaunchedEffect(startTimeText) {
                    delay(500) // Debounce
                    parseAndSetStartTime(startTimeText)
                }

                Spacer(Modifier.height(12.dp))

                // Playback controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledIconButton(
                        onClick = { if (isPlaying) stopPreview() else playPreview() },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Stop" else "Play preview"
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Waveform with fixed selection window
                waveform?.let { wf ->
                    Text(
                        text = stringResource(R.string.drag_to_adjust_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))

                    ScrollableWaveformView(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        waveform = wf,
                        totalDurationMs = totalDurationMs,
                        selectionStartMs = selectionStartMs,
                        selectionDurationMs = selectionDurationMs,
                        playbackPositionFraction = if (isPlaying) playbackPosition else null,
                        onSelectionChange = { newStartMs ->
                            stopPreview()
                            selectionStartMs = newStartMs.coerceIn(
                                0L, (totalDurationMs - selectionDurationMs).coerceAtLeast(0L)
                            )
                        })
                }

                Spacer(Modifier.height(8.dp))

                // Time labels
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = formatTime(selectionStartMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = stringResource(R.string.duration_label) + ": " + formatTime(
                            selectionDurationMs
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTime(selectionEndMs),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Duration slider
                Text(
                    text = stringResource(R.string.fine_adjustment),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = userDurationMs.toFloat(),
                    onValueChange = { newDuration ->
                        stopPreview()
                        userDurationMs = newDuration.toLong()
                        // Clamp start position if needed
                        val maxStart = (totalDurationMs - userDurationMs).coerceAtLeast(0L)
                        if (selectionStartMs > maxStart) {
                            selectionStartMs = maxStart
                        }
                    },
                    valueRange = 200f..maxDurationMs.toFloat(),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Size info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isValidSize) MaterialTheme.colorScheme.surfaceVariant
                            else MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.estimated_size_label),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${estimatedSize / 1000} KB / ${MAX_OUTPUT_SIZE / 1000} KB",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isValidSize) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error
                        )
                    }

                    if (!isValidSize) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.file_too_large_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }, confirmButton = {
        Button(
            onClick = {
                stopPreview()
                onConfirm(selectionStartMs, selectionDurationMs)
            }, enabled = !isLoading && isValidSize
        ) {
            Text(stringResource(R.string.trim_and_upload))
        }
    }, dismissButton = {
        TextButton(onClick = {
            stopPreview()
            onDismiss()
        }) {
            Text(stringResource(R.string.cancel))
        }
    })
}

/**
 * Scrollable waveform view where the user drags to position a fixed-width selection window.
 */
@Composable
private fun ScrollableWaveformView(
    modifier: Modifier = Modifier,
    waveform: FloatArray,
    totalDurationMs: Long,
    selectionStartMs: Long,
    selectionDurationMs: Long,
    playbackPositionFraction: Float? = null,
    onSelectionChange: (newStartMs: Long) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val playheadColor = MaterialTheme.colorScheme.tertiary
    MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)

    LocalDensity.current

    // Use rememberUpdatedState to get current value in gesture callbacks
    val currentSelectionStartMs by androidx.compose.runtime.rememberUpdatedState(selectionStartMs)

    Box(
        modifier = modifier
            .background(backgroundColor)
            .pointerInput(totalDurationMs, selectionDurationMs) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dragStartMs = currentSelectionStartMs
                    val startX = down.position.x

                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break

                        if (change.pressed) {
                            val dragX = change.position.x
                            val deltaX = dragX - startX
                            // Convert drag pixels to time (positive = drag right = move selection later)
                            val msPerPx = totalDurationMs.toFloat() / size.width
                            val deltaMs = (deltaX * msPerPx).toLong()
                            val newStart = (dragStartMs + deltaMs).coerceIn(
                                0L, (totalDurationMs - selectionDurationMs).coerceAtLeast(0L)
                            )
                            onSelectionChange(newStart)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val barWidth = size.width / waveform.size
            val centerY = size.height / 2

            // Selection bounds as fractions
            val selectionStartFraction =
                if (totalDurationMs > 0) selectionStartMs.toFloat() / totalDurationMs else 0f
            val selectionEndFraction =
                if (totalDurationMs > 0) (selectionStartMs + selectionDurationMs).toFloat() / totalDurationMs else 1f

            // Draw waveform bars
            waveform.forEachIndexed { index, amplitude ->
                val x = index * barWidth
                val normalizedPosition = index.toFloat() / waveform.size

                val isSelected = normalizedPosition in selectionStartFraction..selectionEndFraction
                val barColor = if (isSelected) selectedColor else unselectedColor

                // Amplify for visibility
                val barHeight = (amplitude * size.height * 1.5f).coerceIn(4f, size.height * 0.9f)

                drawRect(
                    color = barColor,
                    topLeft = Offset(x, centerY - barHeight / 2),
                    size = Size(barWidth * 0.85f, barHeight)
                )
            }

            val startX = selectionStartFraction * size.width
            val endX = selectionEndFraction * size.width

            // Darken outside areas
            drawRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(0f, 0f),
                size = Size(startX, size.height)
            )
            drawRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(endX, 0f),
                size = Size(size.width - endX, size.height)
            )

            // Selection border
            drawRect(
                color = primaryColor,
                topLeft = Offset(startX, 0f),
                size = Size(endX - startX, size.height),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
            )

            // Draw playhead if playing
            playbackPositionFraction?.let { pos ->
                val playheadX = startX + (endX - startX) * pos
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, size.height),
                    strokeWidth = 4f
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return if (minutes > 0) {
        String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, tenths)
    } else {
        String.format(Locale.getDefault(), "%d.%d s", seconds, tenths)
    }
}

private fun formatTimeInput(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val tenths = (ms % 1000) / 100
    return String.format(Locale.getDefault(), "%d:%02d.%d", minutes, seconds, tenths)
}

private fun parseTimeInput(text: String): Long? {
    return try {
        val parts = text.split(":")
        when (parts.size) {
            1 -> {
                // Just seconds
                (parts[0].toDouble() * 1000).toLong()
            }

            2 -> {
                // Minutes:seconds
                val minutes = parts[0].toLongOrNull() ?: 0
                val seconds = parts[1].toDoubleOrNull() ?: 0.0
                (minutes * 60 * 1000 + seconds * 1000).toLong()
            }

            else -> null
        }
    } catch (_: Exception) {
        null
    }
}
