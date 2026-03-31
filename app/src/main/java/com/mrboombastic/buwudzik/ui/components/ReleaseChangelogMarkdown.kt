package com.mrboombastic.buwudzik.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Renders GitHub release notes (Markdown) with Material 3 styling.
 * Headings use compact dialog-friendly sizes (not display-scale defaults).
 */
@Composable
fun ReleaseChangelogMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
) {
    val body = MaterialTheme.typography.bodyLarge
    Markdown(
        content = markdown,
        modifier = modifier,
        typography = markdownTypography(
            h1 = MaterialTheme.typography.titleMedium,
            h2 = MaterialTheme.typography.titleSmall,
            h3 = body.copy(fontWeight = FontWeight.SemiBold),
            h4 = body.copy(fontWeight = FontWeight.Medium),
            h5 = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            h6 = MaterialTheme.typography.bodyMedium,
        ),
    )
}
