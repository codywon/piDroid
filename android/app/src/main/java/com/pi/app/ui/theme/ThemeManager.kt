package com.pi.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

/**
 * 专业级硬核极客主题配色模型 (参考 Termius 架构，彻底剔除紫色，默认浅色)
 */
enum class AppTheme(
    val id: String,
    val titleZh: String,
    val titleEn: String,
    val isDark: Boolean,
    val primary: Color,
    val primaryContainer: Color,
    val background: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val outline: Color,
    val previewGradient: List<Color>
) {
    LIGHT_PAPER(
        id = "light_paper",
        titleZh = "纯白明眸",
        titleEn = "Paper Light (默认)",
        isDark = false,
        primary = Color(0xFF0969DA),
        primaryContainer = Color(0xFFDDF4FF),
        background = Color(0xFFF6F8FA),
        surfaceContainerLow = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFF3F6F9),
        surfaceContainerHigh = Color(0xFFEAECEF),
        outline = Color(0xFFE1E6EB),
        previewGradient = listOf(Color(0xFF0969DA), Color(0xFF54A0FF))
    ),
    ICE_BLUE(
        id = "ice_blue",
        titleZh = "极客冰蓝",
        titleEn = "Cyber Ice Blue",
        isDark = true,
        primary = Color(0xFF38BDF8),
        primaryContainer = Color(0xFF0E436B),
        background = Color(0xFF0B1320),
        surfaceContainerLow = Color(0xFF111C2D),
        surfaceContainer = Color(0xFF162338),
        surfaceContainerHigh = Color(0xFF21324E),
        outline = Color(0xFF2E466E),
        previewGradient = listOf(Color(0xFF38BDF8), Color(0xFF0284C7))
    ),
    EMERALD(
        id = "emerald",
        titleZh = "黑曜石翡翠",
        titleEn = "Obsidian Emerald",
        isDark = true,
        primary = Color(0xFF67DF70),
        primaryContainer = Color(0xFF1B4D20),
        background = Color(0xFF0B141C),
        surfaceContainerLow = Color(0xFF141C24),
        surfaceContainer = Color(0xFF182028),
        surfaceContainerHigh = Color(0xFF222B33),
        outline = Color(0xFF3E4A3C),
        previewGradient = listOf(Color(0xFF67DF70), Color(0xFF3FB950))
    ),
    AMBER(
        id = "amber",
        titleZh = "复古琥珀",
        titleEn = "Cyber Amber",
        isDark = true,
        primary = Color(0xFFFBBF24),
        primaryContainer = Color(0xFF5B3E05),
        background = Color(0xFF14120C),
        surfaceContainerLow = Color(0xFF1F1A12),
        surfaceContainer = Color(0xFF292318),
        surfaceContainerHigh = Color(0xFF383021),
        outline = Color(0xFF52452D),
        previewGradient = listOf(Color(0xFFFBBF24), Color(0xFFD97706))
    ),
    DARK_SLATE(
        id = "dark_slate",
        titleZh = "暗夜极客",
        titleEn = "Termius Slate",
        isDark = true,
        primary = Color(0xFF58A6FF),
        primaryContainer = Color(0xFF1F3556),
        background = Color(0xFF0D1117),
        surfaceContainerLow = Color(0xFF161B22),
        surfaceContainer = Color(0xFF21262D),
        surfaceContainerHigh = Color(0xFF30363D),
        outline = Color(0xFF30363D),
        previewGradient = listOf(Color(0xFF58A6FF), Color(0xFF1F6FEB))
    );

    val textPrimary: Color get() = if (isDark) Color(0xFFF0F6FC) else Color(0xFF1F2328)
    val textSecondary: Color get() = if (isDark) Color(0xFF8B949E) else Color(0xFF57606A)
    val textMuted: Color get() = if (isDark) Color(0xFF484F58) else Color(0xFF8C959F)

    companion object {
        fun fromId(id: String): AppTheme = entries.find { it.id == id } ?: LIGHT_PAPER
    }
}

/**
 * 主题管理中心 (支持持久化与即时热响应)
 */
object ThemeManager {
    private const val PREFS_NAME = "pidroid_theme_prefs"
    private const val KEY_THEME = "current_theme_id"

    private var prefs: SharedPreferences? = null

    // 默认初始主题为 LIGHT_PAPER (纯白明眸浅色)
    var currentTheme by mutableStateOf(AppTheme.LIGHT_PAPER)
        private set

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val savedId = prefs?.getString(KEY_THEME, AppTheme.LIGHT_PAPER.id) ?: AppTheme.LIGHT_PAPER.id
            currentTheme = AppTheme.fromId(savedId)
        }
    }

    fun setTheme(theme: AppTheme) {
        currentTheme = theme
        prefs?.edit()?.putString(KEY_THEME, theme.id)?.apply()
    }
}
