package com.mrboombastic.buwudzik.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Slider with debounced preview functionality
 * Wraps LabeledSlider and adds debouncing for immediate preview updates
 *
 * @param label Slider label
 * @param value Current value
 * @param onValueChange Called when value changes (not debounced)
 * @param onValueChangeFinished Called when user finishes changing value
 * @param modifier Optional modifier
 * @param enabled Whether slider is enabled
 * @param onPreview Called with debouncing for preview updates (optional)
 * @param valueRange Range of values
 * @param steps Number of steps
 * @param debounceMs Debounce delay in milliseconds for preview
 */
@Composable
fun PreviewSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onPreview: (suspend (Float) -> Unit)? = null,
    valueRange: ClosedFloatingPointRange<Float> = 0f..100f,
    steps: Int = 0,
    debounceMs: Long = 200
) {
    val coroutineScope = rememberCoroutineScope()
    var previewJob by remember { mutableStateOf<Job?>(null) }

    LabeledSlider(
        label = label,
        value = value,
        enabled = enabled,
        onValueChange = { newValue ->
            onValueChange(newValue)
            // Debounced preview
            onPreview?.let { preview ->
                previewJob?.cancel()
                previewJob = coroutineScope.launch {
                    delay(debounceMs.milliseconds)
                    preview(newValue)
                }
            }
        },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        modifier = modifier
    )
}
