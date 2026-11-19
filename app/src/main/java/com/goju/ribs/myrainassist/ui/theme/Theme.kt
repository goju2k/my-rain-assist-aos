package com.goju.ribs.myrainassist.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Fixed brand colors. Wallpaper-based dynamic color turned dialogs a washed-out grey on some phones.
private val LightColorScheme = lightColorScheme(
    primary = RainBlue,
    onPrimary = Color.White,
    primaryContainer = RainBlueContainer,
    onPrimaryContainer = RainBlueContainerDark,
    secondary = RainBlueDark,
    onSecondary = Color.White,
    tertiary = RainSky,
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceLightTint,
    onSurfaceVariant = OnSurfaceVariantLight,
    surfaceContainerLowest = SurfaceLight,
    surfaceContainerLow = SurfaceLight,
    surfaceContainer = SurfaceLight,
    surfaceContainerHigh = SurfaceLight,
    surfaceContainerHighest = SurfaceLightTint,
)

private val DarkColorScheme = darkColorScheme(
    primary = RainBlueLight,
    onPrimary = RainBlueContainerDark,
    primaryContainer = RainBlueContainerDark,
    onPrimaryContainer = RainBlueContainer,
    secondary = RainBlueLight,
    tertiary = RainSky,
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceDarkTint,
    onSurfaceVariant = OnSurfaceVariantDark,
    surfaceContainerLowest = SurfaceDark,
    surfaceContainerLow = SurfaceDark,
    surfaceContainer = SurfaceDarkTint,
    surfaceContainerHigh = SurfaceDarkTint,
    surfaceContainerHighest = SurfaceDarkTint,
)

@Composable
fun MyRainAssistTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content,
    )
}
