package com.pi.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Termius 经典暗色/纯色硬核极客主题色彩
val TermiusBlack = Color(0xFF0D1117)          // 主背景深黑
val TermiusSurface = Color(0xFF161B22)        // 卡片表面
val TermiusSurfaceVariant = Color(0xFF21262D) // 次级表面/高亮卡片
val TermiusBorder = Color(0xFF30363D)         // 细腻分割边框
val TermiusBlue = Color(0xFF58A6FF)           // 终端高亮电光蓝 (Primary)
val TermiusGreen = Color(0xFF3FB950)          // 终端成功绿 (Accent)
val TermiusRed = Color(0xFFF85149)            // 警示红
val TermiusTextPrimary = Color(0xFFF0F6FC)    // 主文字白
val TermiusTextSecondary = Color(0xFF8B949E)  // 辅助说明灰
val TermiusTextMuted = Color(0xFF484F58)      // 占位与禁用灰

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
