package com.mrboombastic.buwudzik.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mrboombastic.buwudzik.R

/**
 * Reusable time picker dialog with standard OK/Cancel buttons
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleTimePickerDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title:String,
    timePickerState: TimePickerState
) {
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        title = { Text(
                text = title,
                modifier = Modifier.padding(bottom = 20.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }) {
            TimePicker(state = timePickerState)
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SimpleTimePickerDialogPreview() {
    val state = TimePickerState(initialHour = 12, initialMinute = 0, is24Hour = true)
    SimpleTimePickerDialog(onDismiss = {}, onConfirm = {}, title = "Start time", timePickerState = state)
}