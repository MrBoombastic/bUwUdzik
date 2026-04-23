package com.mrboombastic.buwudzik.ui.utils

import android.annotation.SuppressLint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Maximum content width used on large screens (tablets, foldables).
 * Content won't stretch beyond this, keeping comfortable reading widths.
 */
val MAX_CONTENT_WIDTH: Dp = 600.dp

/**
 * Modifier that limits content width on large screens while filling all available width
 * on smaller screens. Center-aligned within the available space.
 *
 * Usage:
 *   Column(modifier = Modifier.adaptiveContentWidth()) { ... }
 */
fun Modifier.adaptiveContentWidth(maxWidth: Dp = MAX_CONTENT_WIDTH): Modifier =
    this
        .widthIn(max = maxWidth)
        .fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)

/**
 * A full-screen container that horizontally centers and width-caps its content column.
 * Drop-in replacement for a plain Column + fillMaxSize pattern on screens that should
 * look good on both phones and tablets.
 *
 * @param modifier Applied to the outer [Box] (usually .fillMaxSize() is already included).
 * @param columnModifier Applied to the inner content [Column].
 * @param maxWidth Maximum width of the content column.
 * @param content The screen content.
 */
@Composable
fun AdaptiveScreen(
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.fillMaxSize(),
    columnModifier: Modifier = Modifier,
    maxWidth: Dp = MAX_CONTENT_WIDTH,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = columnModifier
                .widthIn(max = maxWidth)
                .fillMaxWidth(),
            content = content
        )
    }
}
