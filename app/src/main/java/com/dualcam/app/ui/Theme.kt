package com.dualcam.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DualCamColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    secondary = Color(0xFF81C995),
    background = Color.Black,
    surface = Color(0xFF0B1220),
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun DualCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DualCamColors,
        content = content,
    )
}
