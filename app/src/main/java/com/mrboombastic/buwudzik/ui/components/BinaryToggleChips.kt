package com.mrboombastic.buwudzik.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

import com.mrboombastic.buwudzik.device.TempUnit

/**
 * A row with a label and two filter chips for binary selection (e.g., Celsius/Fahrenheit)
 */
@Composable
fun <T> BinaryToggleChips(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    onValueChange: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var selectedIndex by remember(value) { mutableIntStateOf(options.indexOfFirst { it.first == value }) }
    Row(
        verticalAlignment = Alignment.CenterVertically, modifier = modifier
    ) {
        Text(label, modifier = Modifier.weight(1f))

        SingleChoiceSegmentedButtonRow {
            options.forEachIndexed { index, label ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = options.size
                    ),
                    onClick = { onValueChange(label.first) },
                    selected = index == selectedIndex,
                    label = { Text(label.second) },
                    enabled = enabled
                )
            }
        }
    }
}


@Preview
@Composable
fun BinaryToggleChipsPreview() {
        BinaryToggleChips(
            label = "Temperature Unit",
            value = TempUnit.Celsius,
            options = listOf(TempUnit.Celsius to "C", TempUnit.Fahrenheit to "F"),
            onValueChange = {  },
            enabled = true
        )
}

@Preview
@Composable
fun DisabledBinaryToggleChipsPreview() {
    BinaryToggleChips(
        label = "Temperature Unit",
        value = TempUnit.Celsius,
        options = listOf(TempUnit.Celsius to "C", TempUnit.Fahrenheit to "F"),
        onValueChange = {  },
        enabled = false
    )
}

