package com.ozet.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun OzetTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFF8CC4D6),
            onPrimary = Color(0xFF00252F),
            background = Color(0xFF121416),
            onBackground = Color(0xFFE6E6E6),
            surface = Color(0xFF1B1E21),
            onSurface = Color(0xFFE6E6E6),
            surfaceVariant = Color(0xFF262A2E),
            onSurfaceVariant = Color(0xFFB8C0C4),
            secondaryContainer = Color(0xFF2D4650),
            onSecondaryContainer = Color(0xFFD8EEF5),
            outline = Color(0xFF5A6368)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1F4E5F),
            onPrimary = Color.White,
            background = Color(0xFFF7F5F0),
            onBackground = Color(0xFF1B1B1B),
            surface = Color.White,
            onSurface = Color(0xFF1B1B1B),
            surfaceVariant = Color(0xFFECE9E1),
            onSurfaceVariant = Color(0xFF4A4F52),
            secondaryContainer = Color(0xFFD6E7ED),
            onSecondaryContainer = Color(0xFF0F3441),
            outline = Color(0xFF8A9195)
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}
