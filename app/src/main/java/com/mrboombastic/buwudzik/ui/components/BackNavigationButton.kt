package com.mrboombastic.buwudzik.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.R

@Composable
fun BackNavigationButton(
    navController: NavController,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    // Track navigation state to prevent double-clicks
    var isNavigating by remember { mutableStateOf(false) }

    val handleClick: () -> Unit = {
        if (!isNavigating) {
            isNavigating = true
            onClick?.invoke() ?: navController.popBackStack()
        }
    }

    IconButton(
        onClick = handleClick,
        enabled = enabled && !isNavigating
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back_desc)
        )
    }
}

