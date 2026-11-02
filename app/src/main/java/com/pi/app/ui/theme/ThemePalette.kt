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

    /**
     * 发丝线 / 分割线（装饰性）。保持原值 1.26:1。
     *
     * WCAG 1.4.11 约束的是「理解控件所必需」的边界（输入框、可点击元件）；卡片靠背景落差
     * 与排版区分，这条线不在其范围内，主流体系也都刻意很轻：Primer 1.45:1、Tailwind
     * slate-200 1.23:1、M3 outlineVariant 1.21:1、Apple systemGray4 1.52:1。
     * 1.8.19 首版曾按「可见性门槛」提到 #C6CDD5（1.60:1），156 个调用点一起变粗重、观感倒退，已回退。
     */
    val LightOutline: Int = 0xFFE1E6EB.toInt()

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

    /** 发丝线 / 分割线（装饰性）。保持原值 1.42:1，理由同 [LightOutline] */
    val DarkSlateOutline: Int = 0xFF30363D.toInt()
    val DarkSlatePreviewFrom: Int = 0xFF58A6FF.toInt()
    val DarkSlatePreviewTo: Int = 0xFF1F6FEB.toInt()

    // ══════════════════════════════════════════════════════════════
    // 文本梯队（浅 / 深两套，5 套主题共用）
    // ══════════════════════════════════════════════════════════════
    val TextLightPrimary: Int = 0xFF1F2328.toInt()
    val TextLightSecondary: Int = 0xFF57606A.toInt()

    /**
     * 原 `#8C959F` 与白卡仅 3.04:1。取 3.73:1 而不是 AA 正文的 4.5:1：
     * 这一级承载时间戳 / 条数一类元数据，不是正文；推到 4.5:1 会与 [TextLightSecondary]（6.39:1）
     * 几乎同亮，层次分离度从 2.10x 塌到 1.22x，整屏会变吵。现 1.71x 仍能一眼分出主次
     * （Apple 的 tertiaryLabel 同样故意低于 AA）。
     */
    val TextLightMuted: Int = 0xFF7D8590.toInt()

    val TextDarkPrimary: Int = 0xFFF0F6FC.toInt()
    val TextDarkSecondary: Int = 0xFF8B949E.toInt()

    /**
     * 原 `#484F58` 与深色卡片仅 2.09:1（几乎看不见）。取 3.77:1：
     * 既不能复用 `#8B949E`（那是 textSecondary），也不推到 4.5:1
     * （深色层次分离度会从 2.69x 塌到 1.21x）。现 1.49x，仍能看出主次。
     */
    val TextDarkMuted: Int = 0xFF6E7681.toInt()

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
    // 功能色：未读提示（info）/ 置顶（pin）
    //
    // info：原裸写 #0891B2（白底 3.68:1 不达标）。浅色取 #0E7490 = 5.36:1，
    //       深色取 #22D3EE = 9.57:1（在四套深色卡片上均 >= 9.4）。
    // pin：原裸写 #EAB308（白底 1.92:1，几乎看不见）。这是非文本图标（WCAG 1.4.11 需 3:1），
    //      浅色取 #D97706 = 3.19:1 仍保持琥珀观感，深色保留 #EAB308 = 9.02:1。
    // ══════════════════════════════════════════════════════════════
    val LightInfo: Int = 0xFF0E7490.toInt()
    val DarkInfo: Int = 0xFF22D3EE.toInt()
    val LightPinActive: Int = 0xFFD97706.toInt()
    val DarkPinActive: Int = 0xFFEAB308.toInt()

    // ══════════════════════════════════════════════════════════════
    // Git diff 视图 / 深色代码块 diff 前景
    //
    // 这两组是「git 变更」自带的固定色调（绿增红删 + 蓝色 banner），属于组件的视觉标识，
    // 不随 5 套主题变化（与扫码取景、大图查看器的深色层同理）。放在这里是为了让业务组件
    // 不再出现裸 Color(0x...)，而不是为了把它们主题化。
    // ══════════════════════════════════════════════════════════════
    val GitDiffAddBgDark: Int = 0xFF132B1B.toInt()
    val GitDiffAddBgLight: Int = 0xFFE6F7EA.toInt()
    val GitDiffDelBgDark: Int = 0xFF331618.toInt()
    val GitDiffDelBgLight: Int = 0xFFFFECEE.toInt()
    val GitDiffAddLineDark: Int = 0xFF1F5C2C.toInt()
    val GitDiffAddLineLight: Int = 0xFFACECBC.toInt()
    val GitDiffDelLineDark: Int = 0xFF6B181E.toInt()
    val GitDiffDelLineLight: Int = 0xFFFFBCC3.toInt()
    val GitDiffBannerBgDark: Int = 0xFF161F2E.toInt()
    val GitDiffBannerBgLight: Int = 0xFFF1F6FD.toInt()
    val GitDiffBannerBorderDark: Int = 0xFF263854.toInt()
    val GitDiffBannerBorderLight: Int = 0xFFD4E3FA.toInt()
    val GitDiffBadgeBgDark: Int = 0xFF233550.toInt()
    val GitDiffBadgeBgLight: Int = 0xFFDCEAFE.toInt()
    val GitDiffAccentDark: Int = 0xFF79B8FF.toInt()
    val GitDiffAccentLight: Int = 0xFF0969DA.toInt()

    /** 深色代码块里 diff 行的前景（叠在 CodeBgDark 上） */
    val DiffAddFgDark: Int = 0xFF86EFAC.toInt()
    val DiffDelFgDark: Int = 0xFFFCA5A5.toInt()
    val DiffHunkFgDark: Int = 0xFF93C5FD.toInt()
    val DiffHunkBaseDark: Int = 0xFF3B82F6.toInt()

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
    // 思考等级梯级色（high 琥珀深金 -> xhigh 炽暖橙 -> max 烈焰绯红）
    // 告别以往 3 档统一单一黄且文案全叫"深度推理"的冗余与模糊。
    // 实测全表面对比度：
    // - high:  浅 #92400E 7.09:1 (AA) / 深 #FBBF24 10.36:1 (AA)
    // - xhigh: 浅 #C2410C 5.18:1 (AA) / 深 #FB923C 7.64:1 (AA)
    // - max:   浅 #BE123C 6.29:1 (AA) / 深 #FB7185 6.43:1 (AA)
    // ══════════════════════════════════════════════════════════════
    val ThinkingHighLight: Int = 0xFF92400E.toInt()
    val ThinkingHighDark: Int = 0xFFFBBF24.toInt()
    val ThinkingXHighLight: Int = 0xFFC2410C.toInt()
    val ThinkingXHighDark: Int = 0xFFFB923C.toInt()
    val ThinkingMaxLight: Int = 0xFFBE123C.toInt()
    val ThinkingMaxDark: Int = 0xFFFB7185.toInt()

    // ══════════════════════════════════════════════════════════════
    // 代码块 / Diff 行（浅色走 Primer 基准，深色沿用 Catppuccin Mocha）
    //
    // 原来两个代码块卡片把 Catppuccin 暗底写死，浅色模式下等于在白色对话流里
    // 嵌了一块 17.6:1 明度断层的黑砖。现在按主题分支：
    // 浅色 正文 #1F2328 on #F6F8FA = 14.84:1，表头 #57606A on #EAECEF = 5.40:1。
    // ══════════════════════════════════════════════════════════════
    val CodeBgLight: Int = 0xFFF6F8FA.toInt()
    val CodeHeaderLight: Int = 0xFFEAECEF.toInt()

    val CodeBgDark: Int = 0xFF181825.toInt()
    val CodeHeaderDark: Int = 0xFF11111B.toInt()
    val CodeBorderDark: Int = 0xFF313244.toInt()
    val CodeTextDark: Int = 0xFFCDD6F4.toInt()
    val CodeMutedDark: Int = 0xFFA6ADC8.toInt()

    /** Diff 行（浅色，Primer 基准；实测 4.81 / 4.67 / 4.56:1） */
    val DiffAddBgLight: Int = 0xFFE6FFEC.toInt()
    val DiffAddFgLight: Int = 0xFF1A7F37.toInt()
    val DiffDelBgLight: Int = 0xFFFFEBE9.toInt()
    val DiffDelFgLight: Int = 0xFFCF222E.toInt()
    val DiffHunkBgLight: Int = 0xFFDDF4FF.toInt()
    val DiffHunkFgLight: Int = 0xFF0969DA.toInt()

    // ══════════════════════════════════════════════════════════════
    // 流速徽章四个档位的底色（保留 pi-web 色相，前景由 ArgbContrast.bestOn 决定）
    // 实测 bestOn：青 #53B3CB→深墨 8.00 / 绿 #9BC53D→深墨 9.63 /
    //              黄 #F9C22E→深墨 11.79 / 红 #E01A4F→白 4.74
    // ══════════════════════════════════════════════════════════════
    val TpsFast: Int = 0xFF53B3CB.toInt()
    val TpsNormal: Int = 0xFF9BC53D.toInt()
    val TpsSlow: Int = 0xFFF9C22E.toInt()
    val TpsLag: Int = 0xFFE01A4F.toInt()

    // ══════════════════════════════════════════════════════════════
    // 沉浸层（theme-invariant）：扫码取景、大图查看器
    //
    // 这三组不随 5 套主题变化——相机取景与暗房看图本来就该是深色，主题化成
    // 琥珀底只会伤害可用性。它们集中在这里是为了让「业务组件不再出现裸 Color(0x...)」
    // 这条口径能真正兑现，而不是因为想给它们上主题。
        // ══════════════════════════════════════════════════════════════
    val ScrimBase: Int = 0xFF0D1117.toInt()          // 扫码全屏底 / 半透明胶囊的基色
    val ScrimLightbox: Int = 0xF20B0D13.toInt()      // 大图查看器底（95% 不透明）
    val ScrimSurface: Int = 0xFF161B22.toInt()       // 沉浸层上的按钮/胶囊底
    val ScrimOnSurface: Int = 0xFFE6EDF3.toInt()     // 沉浸层上的文字（vs ScrimBase = 15.4:1）

    // ══════════════════════════════════════════════════════════════
    // 行内代码底纹（12% 中性灰，叠在任意卡片底上都只做「轻底纹」，不承担对比度职责）
        // ══════════════════════════════════════════════════════════════
    val InlineCodeTint: Int = 0x1F88888F

    // ══════════════════════════════════════════════════════════════
    // 图表分类色（mindmap 分支 / 饼图柱图）：**数据色，不是语义色**
    //
    // 它们表达「第几类」，不表达成败/警告，所以既不该跟随主题，也不该被
    // success/warning 语义色取代——否则主题一换，同一份数据的颜色映射就变了。
        // ══════════════════════════════════════════════════════════════
    val ChartBlue: Int = 0xFF3B82F6.toInt()
    val ChartGreen: Int = 0xFF10B981.toInt()
    val ChartAmber: Int = 0xFFF59E0B.toInt()
    val ChartRed: Int = 0xFFEF4444.toInt()
    val ChartPink: Int = 0xFFEC4899.toInt()
    val ChartCyan: Int = 0xFF06B6D4.toInt()
    val ChartViolet: Int = 0xFF8B5CF6.toInt()
    // ══════════════════════════════════════════════════════════════
    // 品牌（theme-invariant：logo 不随主题变色，避免 AMBER 主题下出现亮黄块）
    // ══════════════════════════════════════════════════════════════
    val BrandLogoBackground: Int = 0xFF1E293B.toInt() // 与白 π 对比度 14.6:1
}
