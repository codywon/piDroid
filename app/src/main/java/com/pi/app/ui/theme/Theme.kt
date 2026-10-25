package com.pi.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalAppTheme = compositionLocalOf { AppTheme.LIGHT_PAPER }
val LocalTextPrimary = compositionLocalOf { Color(0xFF1F2328) }
val LocalTextSecondary = compositionLocalOf { Color(0xFF57606A) }
val LocalTextMuted = compositionLocalOf { Color(0xFF8C959F) }

// 经典 Termius 与极客色彩常量（用于静态样式回退）
val TermiusBlack = Color(0xFF0D1117)
val TermiusSurface = Color(0xFF161B22)
val TermiusSurfaceVariant = Color(0xFF21262D)
val TermiusBorder = Color(0xFF30363D)
val TermiusBlue = Color(0xFF58A6FF)
val TermiusGreen = Color(0xFF3FB950)
val TermiusRed = Color(0xFFF85149)
val TermiusTextPrimary = Color(0xFFF0F6FC)
val TermiusTextSecondary = Color(0xFF8B949E)
val TermiusTextMuted = Color(0xFF484F58)

val TermiusColorScheme = darkColorScheme(
    primary = TermiusBlue,
    onPrimary = Color(0xFF040D21),
    primaryContainer = Color(0xFF1F3556),
    onPrimaryContainer = Color(0xFFD2E5FF),
    secondary = TermiusGreen,
    onSecondary = Color(0xFF002206),
    secondaryContainer = Color(0xFF1B3D23),
    onSecondaryContainer = Color(0xFFB4F1BE),
    background = TermiusBlack,
    onBackground = TermiusTextPrimary,
    surface = TermiusSurface,
    onSurface = TermiusTextPrimary,
    surfaceVariant = TermiusSurfaceVariant,
    onSurfaceVariant = TermiusTextSecondary,
    outline = TermiusBorder,
    outlineVariant = Color(0xFF272D36),
    error = TermiusRed,
    onError = Color.White
)

@Composable
fun PiDroidTheme(
    theme: AppTheme = ThemeManager.currentTheme,
    content: @Composable () -> Unit
) {
    val dynamicColorScheme = if (theme.isDark) {
        darkColorScheme(
            primary = theme.primary,
            onPrimary = Color(0xFF040D21),
            primaryContainer = theme.primaryContainer,
            onPrimaryContainer = Color.White,
            secondary = theme.primary,
            onSecondary = Color.Black,
            background = theme.background,
            onBackground = theme.textPrimary,
            surface = theme.surfaceContainerLow,
            onSurface = theme.textPrimary,
            surfaceVariant = theme.surfaceContainer,
            onSurfaceVariant = theme.textSecondary,
            surfaceContainerLowest = theme.background,
            surfaceContainerLow = theme.surfaceContainerLow,
            surfaceContainer = theme.surfaceContainer,
            surfaceContainerHigh = theme.surfaceContainerHigh,
            surfaceContainerHighest = theme.surfaceContainerHigh,
            outline = theme.outline,
            outlineVariant = theme.surfaceContainerHigh,
            error = TermiusRed
        )
    } else {
        lightColorScheme(
            primary = theme.primary,
            onPrimary = Color.White,
            primaryContainer = theme.primaryContainer,
            onPrimaryContainer = theme.primary,
            secondary = theme.primary,
            onSecondary = Color.White,
            background = theme.background,
            onBackground = theme.textPrimary,
            surface = theme.surfaceContainerLow,
            onSurface = theme.textPrimary,
            surfaceVariant = theme.surfaceContainer,
            onSurfaceVariant = theme.textSecondary,
            surfaceContainerLowest = Color(0xFFFFFFFF),
            surfaceContainerLow = theme.surfaceContainerLow,
            surfaceContainer = theme.surfaceContainer,
            surfaceContainerHigh = theme.surfaceContainerHigh,
            surfaceContainerHighest = theme.surfaceContainerHigh,
            outline = theme.outline,
            outlineVariant = Color(0xFFE5EAF0),
            error = Color(0xFFCF222E)
        )
    }

    CompositionLocalProvider(
        LocalAppTheme provides theme,
        LocalTextPrimary provides theme.textPrimary,
        LocalTextSecondary provides theme.textSecondary,
        LocalTextMuted provides theme.textMuted
    ) {
        MaterialTheme(
            colorScheme = dynamicColorScheme,
            content = content
        )
    }
}
