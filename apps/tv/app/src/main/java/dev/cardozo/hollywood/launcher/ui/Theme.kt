package dev.cardozo.hollywood.launcher.ui

import android.widget.TextView
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.cardozo.hollywood.launcher.R

val Background = Color(0xFF07080B)
val Foreground = Color(0xFFF2F3F5)
val Dim = Foreground.copy(alpha = 0.64f)
val Faint = Foreground.copy(alpha = 0.40f)

/** One weight of the variable font; Compose needs each one it may be asked for spelled out. */
@OptIn(ExperimentalTextApi::class)
private fun redHat(weight: Int) = Font(
    R.font.red_hat_display, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Red Hat Display (SIL OFL, apps/tv/licenses): the launcher's only typeface, chosen in docs/mockups/fuentes.html. */
val RedHatDisplay = FontFamily(redHat(300), redHat(400), redHat(500), redHat(600), redHat(700), redHat(800), redHat(900))

/** The same face for plain views (the screensaver), which do not go through Compose. */
fun TextView.setRedHat(weight: Int) {
    typeface = resources.getFont(R.font.red_hat_display)
    fontVariationSettings = "'wght' $weight"
}

private val LauncherTypography = Typography().let { t ->
    Typography(
        displayLarge = t.displayLarge.copy(fontFamily = RedHatDisplay),
        displayMedium = t.displayMedium.copy(fontFamily = RedHatDisplay),
        displaySmall = t.displaySmall.copy(fontFamily = RedHatDisplay),
        headlineLarge = t.headlineLarge.copy(fontFamily = RedHatDisplay),
        headlineMedium = t.headlineMedium.copy(fontFamily = RedHatDisplay),
        headlineSmall = t.headlineSmall.copy(fontFamily = RedHatDisplay),
        titleLarge = t.titleLarge.copy(fontFamily = RedHatDisplay),
        titleMedium = t.titleMedium.copy(fontFamily = RedHatDisplay),
        titleSmall = t.titleSmall.copy(fontFamily = RedHatDisplay),
        bodyLarge = t.bodyLarge.copy(fontFamily = RedHatDisplay),
        bodyMedium = t.bodyMedium.copy(fontFamily = RedHatDisplay),
        bodySmall = t.bodySmall.copy(fontFamily = RedHatDisplay),
        labelLarge = t.labelLarge.copy(fontFamily = RedHatDisplay),
        labelMedium = t.labelMedium.copy(fontFamily = RedHatDisplay),
        labelSmall = t.labelSmall.copy(fontFamily = RedHatDisplay),
    )
}

@Composable
fun LauncherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = Background,
            onSurface = Foreground,
            onBackground = Foreground,
        ),
        typography = LauncherTypography,
        content = content,
    )
}
