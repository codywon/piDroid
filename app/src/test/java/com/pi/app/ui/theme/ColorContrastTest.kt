package com.pi.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 色彩可访问性门禁（纯 JVM，不触碰 Android / Compose 运行时）。
 *
 * 断言对象是 [ThemePalette] 的原始 Int 值与 [ArgbContrast] 的公式：
 * 与生产代码（`AppTheme.onPrimary` 等）用的是**同一份实现**，因此这里绿了，
 * 生产侧就不可能出现「手写 4.63、实测 3.19」那种偏差。
 *
 * 阈值依据 WCAG 2.1，外加一条 WCAG 没写但设计必须有的：
 * - 1.4.3 正文 4.5:1（大字 3:1）           → textPrimary / textSecondary
 * - 1.4.11 非文本界面元素 3:1              → 星标、图标、弱化元数据、语义状态色
 * - 装饰性发丝线不属 1.4.11               → 只断言「可见但不抢戏」区间
 * - **层次分离度**（本文件自定义）：相邻两级文字的对比度倍数
 *   一级/二级 >= 1.6x、二级/弱化 >= 1.45x
 *
 * 最后一条是本项目踩过的坑：每个 Token 单独都"达标"，但把 254 处弱化文本推到 4.5:1 后，
 * 它与二级文本几乎同亮（分离度 2.10x -> 1.22x），整屏变吵、层次消失。
 * 所以这里必须同时锁「绝对可读」与「相对分离」。
 */
class ColorContrastTest {

    private data class Scheme(
        val name: String,
        val isDark: Boolean,
        val primary: Int,
        val primaryContainer: Int,
        val background: Int,
        val surfaceLow: Int,
        val surfaceContainer: Int,
        val surfaceHigh: Int,
        val outline: Int,
        val textPrimary: Int,
        val textSecondary: Int,
        val textMuted: Int,
        val success: Int,
        val successContainer: Int,
        val warning: Int,
        val warningContainer: Int,
        val error: Int,
        val errorContainer: Int,
        val onError: Int,
        val onErrorContainer: Int,
        val starActive: Int,
        val starInactive: Int,
        val featureThinking: Int,
        val info: Int,
        val pinActive: Int,
        val onSuccess: Int
    )

    private val lightScheme = Scheme(
        name = "LIGHT_PAPER",
        isDark = false,
        primary = ThemePalette.LightPrimary,
        primaryContainer = ThemePalette.LightPrimaryContainer,
        background = ThemePalette.LightBackground,
        surfaceLow = ThemePalette.LightSurfaceLow,
        surfaceContainer = ThemePalette.LightSurfaceContainer,
        surfaceHigh = ThemePalette.LightSurfaceHigh,
        outline = ThemePalette.LightOutline,
        textPrimary = ThemePalette.TextLightPrimary,
        textSecondary = ThemePalette.TextLightSecondary,
        textMuted = ThemePalette.TextLightMuted,
        success = ThemePalette.LightSuccess,
        successContainer = ThemePalette.LightSuccessContainer,
        warning = ThemePalette.LightWarning,
        warningContainer = ThemePalette.LightWarningContainer,
        error = ThemePalette.LightError,
        errorContainer = ThemePalette.LightErrorContainer,
        onError = ThemePalette.LightOnError,
        onErrorContainer = ThemePalette.LightOnErrorContainer,
        starActive = ThemePalette.LightStarActive,
        starInactive = ThemePalette.LightStarInactive,
        featureThinking = ThemePalette.LightFeatureThinking,
        info = ThemePalette.LightInfo,
        pinActive = ThemePalette.LightPinActive,
        onSuccess = ArgbContrast.bestOn(ThemePalette.LightSuccess)
    )

    private fun darkScheme(
        name: String,
        primary: Int,
        primaryContainer: Int,
        background: Int,
        surfaceLow: Int,
        surfaceContainer: Int,
        surfaceHigh: Int,
        outline: Int
    ) = Scheme(
        name = name,
        isDark = true,
        primary = primary,
        primaryContainer = primaryContainer,
        background = background,
        surfaceLow = surfaceLow,
        surfaceContainer = surfaceContainer,
        surfaceHigh = surfaceHigh,
        outline = outline,
        textPrimary = ThemePalette.TextDarkPrimary,
        textSecondary = ThemePalette.TextDarkSecondary,
        textMuted = ThemePalette.TextDarkMuted,
        success = ThemePalette.DarkSuccess,
        successContainer = ThemePalette.DarkSuccessContainer,
        warning = ThemePalette.DarkWarning,
        warningContainer = ThemePalette.DarkWarningContainer,
        error = ThemePalette.DarkError,
        errorContainer = ThemePalette.DarkErrorContainer,
        onError = ThemePalette.DarkOnError,
        onErrorContainer = ThemePalette.DarkOnErrorContainer,
        starActive = ThemePalette.DarkStarActive,
        starInactive = ThemePalette.DarkStarInactive,
        featureThinking = ThemePalette.DarkFeatureThinking,
        info = ThemePalette.DarkInfo,
        pinActive = ThemePalette.DarkPinActive,
        onSuccess = ArgbContrast.bestOn(ThemePalette.DarkSuccess)
    )

    private val allSchemes: List<Scheme> = listOf(
        lightScheme,
        darkScheme("ICE_BLUE", ThemePalette.IceBluePrimary, ThemePalette.IceBluePrimaryContainer, ThemePalette.IceBlueBackground,
            ThemePalette.IceBlueSurfaceLow, ThemePalette.IceBlueSurfaceContainer,
            ThemePalette.IceBlueSurfaceHigh, ThemePalette.IceBlueOutline),
        darkScheme("EMERALD", ThemePalette.EmeraldPrimary, ThemePalette.EmeraldPrimaryContainer, ThemePalette.EmeraldBackground,
            ThemePalette.EmeraldSurfaceLow, ThemePalette.EmeraldSurfaceContainer,
            ThemePalette.EmeraldSurfaceHigh, ThemePalette.EmeraldOutline),
        darkScheme("AMBER", ThemePalette.AmberPrimary, ThemePalette.AmberPrimaryContainer, ThemePalette.AmberBackground,
            ThemePalette.AmberSurfaceLow, ThemePalette.AmberSurfaceContainer,
            ThemePalette.AmberSurfaceHigh, ThemePalette.AmberOutline),
        darkScheme("DARK_SLATE", ThemePalette.DarkSlatePrimary, ThemePalette.DarkSlatePrimaryContainer, ThemePalette.DarkSlateBackground,
            ThemePalette.DarkSlateSurfaceLow, ThemePalette.DarkSlateSurfaceContainer,
            ThemePalette.DarkSlateSurfaceHigh, ThemePalette.DarkSlateOutline)
    )

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)

    private fun assertAtLeast(label: String, fg: Int, bg: Int, min: Double) {
        val ratio = ArgbContrast.contrastRatio(fg, bg)
        assertTrue(
            "$label 对比度 ${fmt(ratio)}:1 低于要求的 ${fmt(min)}:1" +
                " (fg=#${Integer.toHexString(fg).uppercase()}, bg=#${Integer.toHexString(bg).uppercase()})",
            ratio >= min
        )
    }

    /** ① 主操作前景色：白 / 深墨里按实际对比度取优，5 套主题都必须过 AA */
    @Test
    fun onPrimaryMeetsAaForAllThemes() {
        allSchemes.forEach { s ->
            val onPrimary = ArgbContrast.bestOn(s.primary)
            assertAtLeast("${s.name}.onPrimary", onPrimary, s.primary, 4.5)
        }
    }

    /** ② 一级文本：四层表面都要 >= 7:1（AAA） */
    @Test
    fun textPrimaryMeetsAaaOnAllSurfaces() {
        allSchemes.forEach { s ->
            listOf(
                "background" to s.background,
                "surfaceContainerLow" to s.surfaceLow,
                "surfaceContainer" to s.surfaceContainer,
                "surfaceContainerHigh" to s.surfaceHigh
            ).forEach { (surfaceName, surface) ->
                assertAtLeast("${s.name}.textPrimary/$surfaceName", s.textPrimary, surface, 7.0)
            }
        }
    }

    /** ③ 二级文本：允许承接 background / surfaceContainerLow / surfaceContainer */
    @Test
    fun textSecondaryMeetsAaOnAllowedSurfaces() {
        allSchemes.forEach { s ->
            listOf(
                "background" to s.background,
                "surfaceContainerLow" to s.surfaceLow,
                "surfaceContainer" to s.surfaceContainer
            ).forEach { (surfaceName, surface) ->
                assertAtLeast("${s.name}.textSecondary/$surfaceName", s.textSecondary, surface, 4.5)
            }
        }
    }

    /**
     * ④ 弱化文本：元数据档，要求 >= 3.0（AA 大字 / 非文本档）。
     *
     * 不要求 4.5：这一级是时间戳、条数一类元数据；硬推到 4.5 会撞上二级文本，
     * 把层次分离度压到 1.2x（详见 [textTiersKeepSeparation]）。
     * `surfaceContainerHigh` 仍不在允许范围内（实测 3.15 浅 / 2.66 深）。
     */
    @Test
    fun textMutedStaysReadable() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.textMuted/background", s.textMuted, s.background, 3.0)
            assertAtLeast("${s.name}.textMuted/surfaceContainerLow", s.textMuted, s.surfaceLow, 3.0)
        }
    }

    /**
     * ⑤ 文字层次必须既"递减"又"分得开"。
     *
     * 1.8.19 首版只断言了单调递减，于是"primary 15.8 / secondary 6.4 / muted 5.3"也过了——
     * 数值上合法，观感上是一坨。现在加上**最小分离倍数**：二级/弱化 >= 1.45x。
     * （深色主题受卡片亮度限制，实测 1.49x，故阈值取 1.45 而非 1.6。）
     */
    @Test
    fun textTiersKeepSeparation() {
        allSchemes.forEach { s ->
            assertTrue(
                "${s.name}: textMuted 与 textSecondary 同值，三级文字梯度塌成两级",
                s.textMuted != s.textSecondary
            )
            val primaryContrast = ArgbContrast.contrastRatio(s.textPrimary, s.surfaceLow)
            val secondaryContrast = ArgbContrast.contrastRatio(s.textSecondary, s.surfaceLow)
            val mutedContrast = ArgbContrast.contrastRatio(s.textMuted, s.surfaceLow)

            val primaryToSecondary = primaryContrast / secondaryContrast
            val secondaryToMuted = secondaryContrast / mutedContrast

            assertTrue(
                "${s.name}: 一级/二级 分离度 ${fmt(primaryToSecondary)}x 低于 1.60x" +
                    " (primary=${fmt(primaryContrast)} secondary=${fmt(secondaryContrast)})",
                primaryToSecondary >= 1.6
            )
            assertTrue(
                "${s.name}: 二级/弱化 分离度 ${fmt(secondaryToMuted)}x 低于 1.45x，" +
                    "弱化文本已被推到与二级同亮 (secondary=${fmt(secondaryContrast)} muted=${fmt(mutedContrast)})",
                secondaryToMuted >= 1.45
            )
        }
    }

    /**
     * ⑥ 发丝线 / 卡片边框：装饰性，锁「可见但不抢戏」区间 [1.15, 2.0]。
     *
     * 上界是 1.8.19 首版的教训：把它当控件边界要求 1.5:1+ 后，156 个调用点一起变粗重，
     * 观感直接被判为"线条加深、排版变丑"。卡片靠背景落差区分，不需要抢眼的描边。
     */
    @Test
    fun outlineStaysLightButVisible() {
        allSchemes.forEach { s ->
            val ratio = ArgbContrast.contrastRatio(s.outline, s.surfaceLow)
            assertTrue(
                "${s.name}.outline 与卡片对比度 ${fmt(ratio)}:1 超出 [1.15, 2.00] 区间（太隐形或太抢戏）",
                ratio >= 1.15 && ratio <= 2.0
            )
        }
    }

    /** ⑦ 语义状态色：作为文本时 vs 卡片、作为前景时 vs 自身容器，都要过 AA */
    @Test
    fun statusColorsMeetAa() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.success/surfaceContainerLow", s.success, s.surfaceLow, 4.5)
            assertAtLeast("${s.name}.success/successContainer", s.success, s.successContainer, 4.5)
            assertAtLeast("${s.name}.warning/surfaceContainerLow", s.warning, s.surfaceLow, 4.5)
            assertAtLeast("${s.name}.warning/warningContainer", s.warning, s.warningContainer, 4.5)
            assertAtLeast("${s.name}.error/surfaceContainerLow", s.error, s.surfaceLow, 4.5)
            assertAtLeast("${s.name}.error/errorContainer", s.error, s.errorContainer, 4.5)
            assertAtLeast("${s.name}.onError/error", s.onError, s.error, 4.5)
            assertAtLeast("${s.name}.onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer, 4.5)
        }
    }

    /** ⑧ 星标：实心星是状态指示（4.5:1），空心星是可点击控件（>=3:1） */
    @Test
    fun starIconsMeetContrast() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.starActive/surfaceContainerLow", s.starActive, s.surfaceLow, 4.5)
            assertAtLeast("${s.name}.starInactive/surfaceContainerLow", s.starInactive, s.surfaceLow, 3.0)
        }
    }

    /** ⑨ 能力图标：作为图形信息需要可读 */
    @Test
    fun featureIconsMeetAa() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.featureThinking/surfaceContainerLow", s.featureThinking, s.surfaceLow, 4.5)
        }
    }

    /** ⑩ 品牌 logo：底块与 π 必须强对比（theme-invariant，5 套主题同一结果） */
    @Test
    fun brandLogoGlyphIsVisible() {
        val glyph = ArgbContrast.bestOn(ThemePalette.BrandLogoBackground)
        assertAtLeast("brand.logoGlyph", glyph, ThemePalette.BrandLogoBackground, 7.0)
    }

    /** ⑫ 链接色必须跟随主题（原先写死 #3B82F6，白底仅 3.68:1） */
    @Test
    fun linksUseThemePrimaryAndMeetAa() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.link/surfaceContainerLow", s.primary, s.surfaceLow, 4.5)
        }
    }

    /** ⑬ 代码块：浅色 Primer 基准 / 深色 Catppuccin，正文都要 >= 7:1 */
    @Test
    fun codeBlocksMeetContrast() {
        assertAtLeast("code.light.body", ThemePalette.TextLightPrimary, ThemePalette.CodeBgLight, 7.0)
        assertAtLeast("code.light.header", ThemePalette.TextLightSecondary, ThemePalette.CodeHeaderLight, 4.5)
        assertAtLeast("code.dark.body", ThemePalette.CodeTextDark, ThemePalette.CodeBgDark, 7.0)
        assertAtLeast("code.dark.header", ThemePalette.CodeMutedDark, ThemePalette.CodeHeaderDark, 4.5)
    }

    /** ⑭ Diff 行（浅色实底实字；深色沿用半透明叠色，用亮色字保证可读） */
    @Test
    fun diffLinesMeetContrast() {
        assertAtLeast("diff.add.light", ThemePalette.DiffAddFgLight, ThemePalette.DiffAddBgLight, 4.5)
        assertAtLeast("diff.del.light", ThemePalette.DiffDelFgLight, ThemePalette.DiffDelBgLight, 4.5)
        assertAtLeast("diff.hunk.light", ThemePalette.DiffHunkFgLight, ThemePalette.DiffHunkBgLight, 4.5)
        // 深色：16% 叠色后的近似底色
        assertAtLeast("diff.add.dark", 0xFF86EFAC.toInt(), 0xFF203834.toInt(), 4.5)
        assertAtLeast("diff.del.dark", 0xFFFCA5A5.toInt(), 0xFF3C2631.toInt(), 4.5)
    }

    /** ⑮ 流速徽章：色相对齐 pi-web，前景由 bestOn 决定（黄档白字曾只有 1.64:1） */
    @Test
    fun rateBadgesMeetContrast() {
        listOf(
            "fast" to ThemePalette.TpsFast,
            "normal" to ThemePalette.TpsNormal,
            "slow" to ThemePalette.TpsSlow,
            "lag" to ThemePalette.TpsLag
        ).forEach { (name, bg) ->
            assertAtLeast("tps.$name", ArgbContrast.bestOn(bg), bg, 4.5)
        }
    }

    /** ⑯ 未读 info / 置顶 pin / 成功按钮前景 / 用户气泡 */
    @Test
    fun infoPinSuccessAndBubbleMeetContrast() {
        allSchemes.forEach { s ->
            // info 原裸写 #0891B2，白底仅 3.68:1
            assertAtLeast("${s.name}.info/surfaceContainerLow", s.info, s.surfaceLow, 4.5)
            // pin 是非文本图标，WCAG 1.4.11 要求 3:1（原 #EAB308 白底仅 1.92:1）
            assertAtLeast("${s.name}.pinActive/surfaceContainerLow", s.pinActive, s.surfaceLow, 3.0)
            // 绿色通过按钮上的前景：浅色白字 5.02 / 深色深墨 11.40
            assertAtLeast("${s.name}.onSuccess/success", s.onSuccess, s.success, 4.5)
            // 用户气泡改用 primaryContainer 后，textPrimary 必须仍然可读
            assertAtLeast("${s.name}.textPrimary/primaryContainer", s.textPrimary, s.primaryContainer, 4.5)
        }
    }

    /**
     * ⑰ 沉浸层（theme-invariant）：文字压在遮罩上必须可读。
     * 这一层不随 5 套主题变化，所以单独断言一次即可，不必进 allSchemes。
     */
    @Test
    fun scrimLayerMeetsContrast() {
        assertAtLeast("scrim.onSurface/ScrimBase", ThemePalette.ScrimOnSurface, ThemePalette.ScrimBase, 7.0)
        assertAtLeast("scrim.onSurface/ScrimSurface", ThemePalette.ScrimOnSurface, ThemePalette.ScrimSurface, 4.5)
    }

    /** ⑪ 公式自检：黑白极值必须是 21:1，同色必须是 1:1 */
    @Test
    fun contrastFormulaSanity() {
        val blackWhite = ArgbContrast.contrastRatio(0xFF000000.toInt(), ArgbContrast.WHITE)
        assertTrue("黑白对比度应为 21:1，实测 $blackWhite", kotlin.math.abs(blackWhite - 21.0) < 0.01)
        val sameColor = ArgbContrast.contrastRatio(0xFF0969DA.toInt(), 0xFF0969DA.toInt())
        assertTrue("同色对比度应为 1:1，实测 $sameColor", kotlin.math.abs(sameColor - 1.0) < 0.0001)
    }
}
