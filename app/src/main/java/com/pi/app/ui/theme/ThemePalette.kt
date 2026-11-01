package com.pi.app.ui.theme

/**
 * 全工程唯一的色板（纯 Kotlin `Int` ARGB，无 Compose / Android 依赖）。
 *
 * 存在的理由：让「颜色数值」与「Compose 类型」解耦，从而
 * 1. 生产代码（[ThemeManager] / [AppTheme] / PiDroidTheme）与纯 JVM 单测共用同一份数值；
 * 2. 单测里不需要 import `androidx.compose.ui.graphics.Color`，在 CI 的 JVM 环境中零风险；
 * 3. 消灭「业务组件里散落 `Color(0x...)`」的双轨制：任何新色值只能加到这里，
 *    并由 `ColorContrastTest` 逐个断言 WCAG 等级。
 *
 * 所有数值都用 WCAG 2.1 公式实测过（见 `ColorContrastTest`），**不要手写估计值**。
 */
object ThemePalette {

    // ══════════════════════════════════════════════════════════════
    // LIGHT_PAPER（默认浅色）
    // ══════════════════════════════════════════════════════════════
    val LightPrimary: Int = 0xFF0969DA.toInt()
    val LightPrimaryContainer: Int = 0xFFDDF4FF.toInt()
    val LightBackground: Int = 0xFFF6F8FA.toInt()
    val LightSurfaceLow: Int = 0xFFFFFFFF.toInt()
    val LightSurfaceContainer: Int = 0xFFF3F6F9.toInt()
    val LightSurfaceHigh: Int = 0xFFEAECEF.toInt()

    /** 发丝线 / 分割线。原 `#E1E6EB` 与卡片只有 1.26:1（等于没有边框），改为 1.60:1 */
    val LightOutline: Int = 0xFFC6CDD5.toInt()

    val LightPreviewFrom: Int = 0xFF0969DA.toInt()
    val LightPreviewTo: Int = 0xFF54A0FF.toInt()

    // ══════════════════════════════════════════════════════════════
    // ICE_BLUE（深色）
    // ══════════════════════════════════════════════════════════════
    val IceBluePrimary: Int = 0xFF38BDF8.toInt()
    val IceBluePrimaryContainer: Int = 0xFF0E436B.toInt()
    val IceBlueBackground: Int = 0xFF0B1320.toInt()
    val IceBlueSurfaceLow: Int = 0xFF111C2D.toInt()
    val IceBlueSurfaceContainer: Int = 0xFF162338.toInt()
    val IceBlueSurfaceHigh: Int = 0xFF21324E.toInt()
    val IceBlueOutline: Int = 0xFF2E466E.toInt()
    val IceBluePreviewFrom: Int = 0xFF38BDF8.toInt()
    val IceBluePreviewTo: Int = 0xFF0284C7.toInt()

    // ══════════════════════════════════════════════════════════════
    // EMERALD（深色）
    // ══════════════════════════════════════════════════════════════
    val EmeraldPrimary: Int = 0xFF67DF70.toInt()
    val EmeraldPrimaryContainer: Int = 0xFF1B4D20.toInt()
    val EmeraldBackground: Int = 0xFF0B141C.toInt()
    val EmeraldSurfaceLow: Int = 0xFF141C24.toInt()
    val EmeraldSurfaceContainer: Int = 0xFF182028.toInt()
    val EmeraldSurfaceHigh: Int = 0xFF222B33.toInt()
    val EmeraldOutline: Int = 0xFF3E4A3C.toInt()
    val EmeraldPreviewFrom: Int = 0xFF67DF70.toInt()
    val EmeraldPreviewTo: Int = 0xFF3FB950.toInt()

    // ══════════════════════════════════════════════════════════════
    // AMBER（深色）
    // ══════════════════════════════════════════════════════════════
    val AmberPrimary: Int = 0xFFFBBF24.toInt()
    val AmberPrimaryContainer: Int = 0xFF5B3E05.toInt()
    val AmberBackground: Int = 0xFF14120C.toInt()
    val AmberSurfaceLow: Int = 0xFF1F1A12.toInt()
    val AmberSurfaceContainer: Int = 0xFF292318.toInt()
    val AmberSurfaceHigh: Int = 0xFF383021.toInt()
    val AmberOutline: Int = 0xFF52452D.toInt()
    val AmberPreviewFrom: Int = 0xFFFBBF24.toInt()
    val AmberPreviewTo: Int = 0xFFD97706.toInt()

    // ══════════════════════════════════════════════════════════════
    // DARK_SLATE（深色）
    // ══════════════════════════════════════════════════════════════
    val DarkSlatePrimary: Int = 0xFF58A6FF.toInt()
    val DarkSlatePrimaryContainer: Int = 0xFF1F3556.toInt()
    val DarkSlateBackground: Int = 0xFF0D1117.toInt()
    val DarkSlateSurfaceLow: Int = 0xFF161B22.toInt()
    val DarkSlateSurfaceContainer: Int = 0xFF21262D.toInt()
    val DarkSlateSurfaceHigh: Int = 0xFF30363D.toInt()

    /** 原 `#30363D` 与卡片仅 1.42:1，改为 1.76:1 */
    val DarkSlateOutline: Int = 0xFF3D444D.toInt()
    val DarkSlatePreviewFrom: Int = 0xFF58A6FF.toInt()
    val DarkSlatePreviewTo: Int = 0xFF1F6FEB.toInt()

    // ══════════════════════════════════════════════════════════════
    // 文本梯队（浅 / 深两套，5 套主题共用）
    // ══════════════════════════════════════════════════════════════
    val TextLightPrimary: Int = 0xFF1F2328.toInt()
    val TextLightSecondary: Int = 0xFF57606A.toInt()

    /** 原 `#8C959F` 与白卡仅 3.04:1（低于 AA 4.5），改为 5.25:1 */
    val TextLightMuted: Int = 0xFF656D76.toInt()

    val TextDarkPrimary: Int = 0xFFF0F6FC.toInt()
    val TextDarkSecondary: Int = 0xFF8B949E.toInt()

    /**
     * 原 `#484F58` 与深色卡片仅 2.08:1（几乎看不见）。
     * 不能直接复用 `#8B949E`（那就是 textSecondary，三级梯度会塌成两级），
     * 取 Primer dark `fg.muted` = 4.59~4.64:1，与 secondary 仍可区分。
     */
    val TextDarkMuted: Int = 0xFF7D8590.toInt()

    // ══════════════════════════════════════════════════════════════
    // 语义色：Success / Warning（M3 无对应槽位，故只在本层定义一次）
    // ══════════════════════════════════════════════════════════════
    val LightSuccess: Int = 0xFF15803D.toInt()
    val LightSuccessContainer: Int = 0xFFDCFCE7.toInt()
    val DarkSuccess: Int = 0xFF4ADE80.toInt()
    val DarkSuccessContainer: Int = 0xFF12301C.toInt()

    val LightWarning: Int = 0xFF92400E.toInt() // 原散落 #F59E0B/#D97706/#D29922/#B45309，白底仅 2.2~3.2:1
    val LightWarningContainer: Int = 0xFFFEF3C7.toInt()
    val DarkWarning: Int = 0xFFFBBF24.toInt()
    val DarkWarningContainer: Int = 0xFF3A2A0A.toInt()

    // ══════════════════════════════════════════════════════════════
    // 语义色：Error —— 唯一真源。
    // 只由 PiDroidTheme 写进 Material3 ColorScheme 的 error/onError/errorContainer/
    // onErrorContainer 四个槽位；不再另设 AppTheme.error / PiSemantics.error，
    // 否则「同一概念两处定义」必然分叉（原实现 dark 用 TermiusRed、light 用 #CF222E）。
    // ══════════════════════════════════════════════════════════════
    val LightError: Int = 0xFFB91C1C.toInt()
    val LightOnError: Int = 0xFFFFFFFF.toInt()
    val LightErrorContainer: Int = 0xFFFEE2E2.toInt()
    val LightOnErrorContainer: Int = 0xFF7F1D1D.toInt()

    val DarkError: Int = 0xFFF87171.toInt()
    val DarkOnError: Int = 0xFF2A0A0A.toInt()
    val DarkErrorContainer: Int = 0xFF3A1518.toInt()
    val DarkOnErrorContainer: Int = 0xFFFFB4AB.toInt()

    // ══════════════════════════════════════════════════════════════
    // 功能色：星标 / 能力图标
    // ══════════════════════════════════════════════════════════════

    /** 已设为默认的实心星。浅色原 `#D97706` 实测仅 3.19:1，压到 5.02:1 */
    val LightStarActive: Int = 0xFFB45309.toInt()
    val DarkStarActive: Int = 0xFFFBBF24.toInt()

    /** 未设为默认的空心星：可点击控件，按 WCAG 1.4.11 需 >= 3:1，必须是实色 */
    val LightStarInactive: Int = 0xFF8C959F.toInt() // 3.04:1
    val DarkStarInactive: Int = 0xFF6E7681.toInt() // 3.72:1

    /** 「支持思考推理」能力图标。原裸写 `#8B5CF6` 与主题无关且深色偏暗 */
    val LightFeatureThinking: Int = 0xFF7C3AED.toInt()
    val DarkFeatureThinking: Int = 0xFFA78BFA.toInt()

    // ══════════════════════════════════════════════════════════════
    // 品牌（theme-invariant：logo 不随主题变色，避免 AMBER 主题下出现亮黄块）
    // ══════════════════════════════════════════════════════════════
    val BrandLogoBackground: Int = 0xFF1E293B.toInt() // 与白 π 对比度 14.6:1
}
