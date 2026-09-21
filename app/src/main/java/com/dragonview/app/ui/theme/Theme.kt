// app/src/main/java/com/dragonview/app/ui/theme/Theme.kt
package com.dragonview.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = DragonPrimary,
    onPrimary = Color(0xFF680010),
    primaryContainer = DragonPrimaryContainer,
    onPrimaryContainer = Color(0xFF5B000D),
    secondary = DragonSecondary,
    onSecondary = Color(0xFF68000C),
    secondaryContainer = DragonSecondaryContainer,
    onSecondaryContainer = Color(0xFFFF9993),
    tertiary = DragonTertiary,
    onTertiary = Color(0xFF66031D),
    tertiaryContainer = DragonTertiaryContainer,
    onTertiaryContainer = Color(0xFF5B0017),
    background = DragonDarkBackground,
    onBackground = DragonDarkTextPrimary,
    surface = DragonDarkSurface,
    onSurface = DragonDarkTextPrimary,
    surfaceVariant = DragonDarkSurfaceVariant,
    onSurfaceVariant = DragonDarkTextSecondary,
    outline = DragonOutline,
    outlineVariant = DragonOutlineVariant
)

private val LightColorScheme = lightColorScheme(
    primary = DragonCrimson,
    onPrimary = DragonLightSurface,
    primaryContainer = DragonLightSurfaceContainer,
    onPrimaryContainer = DragonCrimsonDark,
    secondary = DragonFlame,
    onSecondary = DragonLightSurface,
    secondaryContainer = DragonLightSurfaceVariant,
    onSecondaryContainer = DragonCrimsonDark,
    tertiary = DragonFlame,
    background = DragonLightBackground,
    onBackground = DragonLightTextPrimary,
    surface = DragonLightSurface,
    onSurface = DragonLightTextPrimary,
    surfaceVariant = DragonLightSurfaceVariant,
    onSurfaceVariant = DragonLightTextSecondary,
    outline = DragonLightBorder
)

@Composable
fun DragonViewTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
