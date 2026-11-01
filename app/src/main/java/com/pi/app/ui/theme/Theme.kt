package com.pi.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

val LocalAppTheme = compositionLocalOf { AppTheme.LIGHT_PAPER }
val LocalTextPrimary = compositionLocalOf { Color(ThemePalette.TextLightPrimary) }
val LocalTextSecondary = compositionLocalOf { Color(ThemePalette.TextLightSecondary) }

/**
 * 保留此 CompositionLocal 仅为对外接口稳定（当前全库 0 处读取，组件统一读
 * `LocalAppTheme.current.textMuted`）。默认值同步到修正后的弱化文本色，
 * 避免将来有人接上它时又拿到旧的 `#8C959F`（3.04:1）。
 */
val LocalTextMuted = compositionLocalOf { Color(ThemePalette.TextLightMuted) }

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

/**
 * LEGACY：未接线的固定深色方案（全库 0 处引用），保留仅为对外接口稳定。
 *
 * `error` 已改为引用 [ThemePalette.DarkError]，与 PiDroidTheme 里的 M3 `error` 槽位是同一个值——
 * 全局只允许存在一个 error 真源，此处不再用 `TermiusRed` 另起一套。
 */
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
    error = Color(ThemePalette.DarkError),
    onError = Color(ThemePalette.DarkOnError)
)

@Composable
fun PiDroidTheme(
    theme: AppTheme = ThemeManager.currentTheme,
    content: @Composable () -> Unit
) {
    val dynamicColorScheme = if (theme.isDark) {
        darkColorScheme(
            primary = theme.primary,
            // 按 primary 实际亮度取白 / 深墨；不再硬编码 Color(0xFF040D21)。
            // AMBER / EMERALD / ICE_BLUE / DARK_SLATE 四套亮主色都会得到深墨（7.66~11.60:1）。
            onPrimary = theme.onPrimary,
            primaryContainer = theme.primaryContainer,
            onPrimaryContainer = Color.White,
            secondary = theme.primary,
            onSecondary = theme.onPrimary,
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
            // error 四个槽位：唯一真源 ThemePalette，不再混用 TermiusRed(#F85149) 与 #CF222E
            error = Color(ThemePalette.DarkError),
            onError = Color(ThemePalette.DarkOnError),
            errorContainer = Color(ThemePalette.DarkErrorContainer),
            onErrorContainer = Color(ThemePalette.DarkOnErrorContainer)
        )
    } else {
        lightColorScheme(
            primary = theme.primary,
            onPrimary = theme.onPrimary,
            primaryContainer = theme.primaryContainer,
            onPrimaryContainer = theme.primary,
            secondary = theme.primary,
            onSecondary = theme.onPrimary,
            background = theme.background,
            onBackground = theme.textPrimary,
            surface = theme.surfaceContainerLow,
            onSurface = theme.textPrimary,
            surfaceVariant = theme.surfaceContainer,
            onSurfaceVariant = theme.textSecondary,
            surfaceContainerLowest = Color(ThemePalette.LightSurfaceLow),
            surfaceContainerLow = theme.surfaceContainerLow,
            surfaceContainer = theme.surfaceContainer,
            surfaceContainerHigh = theme.surfaceContainerHigh,
            surfaceContainerHighest = theme.surfaceContainerHigh,
            outline = theme.outline,
            outlineVariant = Color(0xFFE5EAF0),
            error = Color(ThemePalette.LightError),
            onError = Color(ThemePalette.LightOnError),
            errorContainer = Color(ThemePalette.LightErrorContainer),
            onErrorContainer = Color(ThemePalette.LightOnErrorContainer)
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            // 浅色主题下将状态栏与导航栏图标反色为黑色，深色主题下反色为白色
            insetsController.isAppearanceLightStatusBars = !theme.isDark
            insetsController.isAppearanceLightNavigationBars = !theme.isDark
        }
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
