package com.pi.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat

val LocalAppTheme = compositionLocalOf { AppTheme.LIGHT_PAPER }

/**
 * 会话正文的 [Density]：在系统字体缩放之上再乘上用户设定的「聊天正文字号」倍数。
 *
 * 为什么用 fontScale 覆盖而不是逐处改字号：正文里是一个字号阶梯（正文 13.5 / 代码 12 / 标题 16…），
 * 逐个乘一遍会漏、且行高与间距的比例容易走样；只换 fontScale 则所有 `sp` 等比放大，
 * `dp`（间距、圆角、图标）保持不动，版式不会散。
 *
 * 注意：WebView 里的 CSS px 不吃 fontScale，公式要自己把 sp 换算成 dp 再传进去
 * （见 MathFormulaBlockCard）。
 */
@Composable
fun rememberChatContentDensity(): Density {
    val base = LocalDensity.current
    val scale = ThemeManager.chatFontScale
    return remember(base, scale) { Density(base.density, base.fontScale * scale) }
}

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
            // error 四个槽位：唯一真源 ThemePalette（原实现 dark 用 #F85149、light 用 #CF222E，且三个槽位未定义）
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
        LocalAppTheme provides theme
    ) {
        MaterialTheme(
            colorScheme = dynamicColorScheme,
            content = content
        )
    }
}
