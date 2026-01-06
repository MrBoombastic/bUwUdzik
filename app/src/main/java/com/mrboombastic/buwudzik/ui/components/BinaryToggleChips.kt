package com.mrboombastic.buwudzik.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A row with a label and two filter chips for binary selection (e.g., Celsius/Fahrenheit)
 */
@Composable
fun <T> BinaryToggleChips(
    label: String,
    value: T,
    option1: Pair<T, String>,
    option2: Pair<T, String>,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically, modifier = modifier
    ) {
        Text(label, modifier = Modifier.weight(1f))
        FilterChip(
            selected = value == option1.first,
            onClick = { onValueChange(option1.first) },
            label = { Text(option1.second) },
            enabled = enabled
        )
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = value == option2.first,
            onClick = { onValueChange(option2.first) },
            label = { Text(option2.second) },
            enabled = enabled
        )
    }
}

