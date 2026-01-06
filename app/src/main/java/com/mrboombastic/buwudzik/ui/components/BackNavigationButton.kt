package com.mrboombastic.buwudzik.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.mrboombastic.buwudzik.R

@Composable
fun BackNavigationButton(
    navController: NavController, enabled: Boolean = true, onClick: (() -> Unit)? = null
) {
    IconButton(
        onClick = {
            if (onClick != null) {
                onClick.invoke()
            } else {
                // Only pop if there's somewhere to go back to
                if (navController.previousBackStackEntry != null) {
                    navController.popBackStack()
                } else {
                    // Navigate to home if there's nothing to pop
                    navController.navigate("home") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            }
        }, enabled = enabled
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back_desc)
        )
    }
}
