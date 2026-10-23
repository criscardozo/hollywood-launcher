package dev.cardozo.hollywood.launcher.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val Background = Color(0xFF07080B)
val Foreground = Color(0xFFF2F3F5)
val Dim = Foreground.copy(alpha = 0.64f)
val Faint = Foreground.copy(alpha = 0.40f)
val JellyfinColor = Color(0xFFA95CE0)
val YouTubeColor = Color(0xFFFF2D2D)

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = Background,
            onSurface = Foreground,
            onBackground = Foreground,
        ),
        content = content,
    )
}
