package com.azizjon.network.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF305D74),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E7F6),
    secondary = Color(0xFF5A5D72),
    tertiary = Color(0xFF6E5675),
    background = Color(0xFFF7F7FA),
    surface = Color(0xFFFDFBFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF96CEE9),
    primaryContainer = Color(0xFF164B61),
    secondary = Color(0xFFC2C4DD),
    tertiary = Color(0xFFDCBCE1),
)

@Composable
fun NetworkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
