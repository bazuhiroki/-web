package me.bazu.shitsuji.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF1C2B3A)
private val Brass = Color(0xFFF2C14E)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EAF2),
    onPrimaryContainer = Ink,
    secondary = Brass,
)

private val DarkColors = darkColorScheme(
    primary = Brass,
    onPrimary = Ink,
    primaryContainer = Color(0xFF2B3B4C),
    onPrimaryContainer = Color.White,
    secondary = Brass,
)

@Composable
fun ShitsujiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
