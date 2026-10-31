package com.movietrack.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object MT {
    val Bg = Color(0xFF121826)
    val Surface = Color(0xFF1B2232)
    val Surface2 = Color(0xFF242C3F)
    val Accent = Color(0xFF4F8CFF)
    val Text = Color(0xFFE7E9EE)
    val Muted = Color(0xFF9AA3B2)
    val Ad = Color(0xFFFB923C)
    val Aud = Color(0xFFB794FF)
    val Sub = Color(0xFF5FD3E8)
    val Ok = Color(0xFF4ADE80)
    val Danger = Color(0xFFF87171)
}

@Composable
fun MovieTrackTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = MT.Accent,
            onPrimary = Color.White,
            background = MT.Bg,
            onBackground = MT.Text,
            surface = MT.Surface,
            onSurface = MT.Text,
            surfaceVariant = MT.Surface2,
            onSurfaceVariant = MT.Muted,
            secondaryContainer = Color(0xFF1B2740),
            onSecondaryContainer = MT.Accent,
            surfaceContainer = MT.Surface,
            surfaceContainerHigh = MT.Surface2,
            error = MT.Danger,
        ),
        content = content,
    )
}
