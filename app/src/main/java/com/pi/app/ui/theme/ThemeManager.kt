package com.pi.app.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 专业级硬核极客主题配色模型 (参考 Termius 架构，彻底剔除紫色，默认浅色)
 *
 * 所有原始色值统一来自 [ThemePalette]（纯 Kotlin Int），本枚举只负责「语义映射」。
 * 这样 `ColorContrastTest` 能在纯 JVM 环境中断言同一份数值，而无需加载 Compose 图形类。
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
        primary = Color(ThemePalette.LightPrimary),
        primaryContainer = Color(ThemePalette.LightPrimaryContainer),
        background = Color(ThemePalette.LightBackground),
        surfaceContainerLow = Color(ThemePalette.LightSurfaceLow),
        surfaceContainer = Color(ThemePalette.LightSurfaceContainer),
        surfaceContainerHigh = Color(ThemePalette.LightSurfaceHigh),
        outline = Color(ThemePalette.LightOutline),
        previewGradient = listOf(Color(ThemePalette.LightPreviewFrom), Color(ThemePalette.LightPreviewTo))
    ),
    ICE_BLUE(
        id = "ice_blue",
        titleZh = "极客冰蓝",
        titleEn = "Cyber Ice Blue",
        isDark = true,
        primary = Color(ThemePalette.IceBluePrimary),
        primaryContainer = Color(ThemePalette.IceBluePrimaryContainer),
        background = Color(ThemePalette.IceBlueBackground),
        surfaceContainerLow = Color(ThemePalette.IceBlueSurfaceLow),
        surfaceContainer = Color(ThemePalette.IceBlueSurfaceContainer),
        surfaceContainerHigh = Color(ThemePalette.IceBlueSurfaceHigh),
        outline = Color(ThemePalette.IceBlueOutline),
        previewGradient = listOf(Color(ThemePalette.IceBluePreviewFrom), Color(ThemePalette.IceBluePreviewTo))
    ),
    EMERALD(
        id = "emerald",
        titleZh = "黑曜石翡翠",
        titleEn = "Obsidian Emerald",
        isDark = true,
        primary = Color(ThemePalette.EmeraldPrimary),
        primaryContainer = Color(ThemePalette.EmeraldPrimaryContainer),
        background = Color(ThemePalette.EmeraldBackground),
        surfaceContainerLow = Color(ThemePalette.EmeraldSurfaceLow),
        surfaceContainer = Color(ThemePalette.EmeraldSurfaceContainer),
        surfaceContainerHigh = Color(ThemePalette.EmeraldSurfaceHigh),
        outline = Color(ThemePalette.EmeraldOutline),
        previewGradient = listOf(Color(ThemePalette.EmeraldPreviewFrom), Color(ThemePalette.EmeraldPreviewTo))
    ),
    AMBER(
        id = "amber",
        titleZh = "复古琥珀",
        titleEn = "Cyber Amber",
        isDark = true,
        primary = Color(ThemePalette.AmberPrimary),
        primaryContainer = Color(ThemePalette.AmberPrimaryContainer),
        background = Color(ThemePalette.AmberBackground),
        surfaceContainerLow = Color(ThemePalette.AmberSurfaceLow),
        surfaceContainer = Color(ThemePalette.AmberSurfaceContainer),
        surfaceContainerHigh = Color(ThemePalette.AmberSurfaceHigh),
        outline = Color(ThemePalette.AmberOutline),
        previewGradient = listOf(Color(ThemePalette.AmberPreviewFrom), Color(ThemePalette.AmberPreviewTo))
    ),
    DARK_SLATE(
        id = "dark_slate",
        titleZh = "暗夜极客",
        titleEn = "Dark Slate",
        isDark = true,
        primary = Color(ThemePalette.DarkSlatePrimary),
        primaryContainer = Color(ThemePalette.DarkSlatePrimaryContainer),
        background = Color(ThemePalette.DarkSlateBackground),
        surfaceContainerLow = Color(ThemePalette.DarkSlateSurfaceLow),
        surfaceContainer = Color(ThemePalette.DarkSlateSurfaceContainer),
        surfaceContainerHigh = Color(ThemePalette.DarkSlateSurfaceHigh),
        outline = Color(ThemePalette.DarkSlateOutline),
        previewGradient = listOf(Color(ThemePalette.DarkSlatePreviewFrom), Color(ThemePalette.DarkSlatePreviewTo))
    );

    // ── 文本梯队 ────────────────────────────────────────────────
    val textPrimary: Color
        get() = Color(if (isDark) ThemePalette.TextDarkPrimary else ThemePalette.TextLightPrimary)
    val textSecondary: Color
        get() = Color(if (isDark) ThemePalette.TextDarkSecondary else ThemePalette.TextLightSecondary)

    /**
     * 弱化说明文本（时间戳、消息条数、工作区名等，全项目 254 处调用点）。
     *
     * 修复前后实测（vs `surfaceContainerLow`）：
     * 浅色 `#8C959F` 3.04:1 → `#7D8590` **3.73:1**
     * 深色 `#484F58` 2.09:1 → `#6E7681` **3.77:1**
     *
     * 为什么停在 ~3.7:1 而不是 AA 正文的 4.5:1：
     * 这一级是元数据（时间戳、条数）而非正文，且**层次分离度**比单点达标更重要——
     * 推到 4.5:1 后它与 [textSecondary] 几乎同亮，分离度从 2.10x/2.69x 塌到 1.22x/1.21x，
     * 整屏会变吵、变平（1.8.19 首版即如此，被用户判为"排版不如以前"）。现为 1.71x/1.49x。
     *
     * 约束：优先画在 `background` / `surfaceContainerLow` 上。
     * `surfaceContainerHigh`（嵌套胶囊/徽章底）实测只有 3.15（浅）/ 2.66（深），
     * 那里请改用 `textPrimary`。
     */
    val textMuted: Color
        get() = Color(if (isDark) ThemePalette.TextDarkMuted else ThemePalette.TextLightMuted)

    // ── 主操作前景色（动态） ─────────────────────────────────────
    /**
     * 压在 [primary] 上的前景色。**禁止**调用点再写 `contentColor = Color.White`：
     * AMBER 白字仅 1.67:1、EMERALD 1.70:1、ICE_BLUE 2.14:1、DARK_SLATE 2.53:1，
     * 全部低于 WCAG AA。这里按实际对比度在白 / 深墨之间取优，实测最低 5.19:1（LIGHT_PAPER）。
     */
    val onPrimary: Color
        get() = Color(ArgbContrast.bestOn(primary.toArgb()))

    // ── 语义状态色（M3 没有 success / warning 槽位，故只在本层定义一次）──
    val success: Color
        get() = Color(if (isDark) ThemePalette.DarkSuccess else ThemePalette.LightSuccess)
    val successContainer: Color
        get() = Color(if (isDark) ThemePalette.DarkSuccessContainer else ThemePalette.LightSuccessContainer)
    val warning: Color
        get() = Color(if (isDark) ThemePalette.DarkWarning else ThemePalette.LightWarning)
    val warningContainer: Color
        get() = Color(if (isDark) ThemePalette.DarkWarningContainer else ThemePalette.LightWarningContainer)

    // ── 功能色 ──────────────────────────────────────────────────
    /** 已设为默认的实心星标 */
    val starActive: Color
        get() = Color(if (isDark) ThemePalette.DarkStarActive else ThemePalette.LightStarActive)

    /** 未设为默认的空心星标：可点击控件，实色且 >= 3:1（不可用 alpha 预混） */
    val starInactive: Color
        get() = Color(if (isDark) ThemePalette.DarkStarInactive else ThemePalette.LightStarInactive)

    /** 「支持思考推理」能力图标（替代原先裸写的紫色常量） */
    val featureThinking: Color
        get() = Color(if (isDark) ThemePalette.DarkFeatureThinking else ThemePalette.LightFeatureThinking)

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
