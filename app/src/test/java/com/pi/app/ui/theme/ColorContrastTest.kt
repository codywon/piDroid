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
 * 阈值依据 WCAG 2.1：
 * - 1.4.3 正文 4.5:1（大字 3:1）
 * - 1.4.11 非文本界面元素（可点击控件边界/图标）3:1
 * - 装饰性发丝线不属 1.4.11 范围，但仍要求 >= 1.5:1 才有可见性
 */
class ColorContrastTest {

    private data class Scheme(
        val name: String,
        val isDark: Boolean,
        val primary: Int,
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
        val featureThinking: Int
    )

    private val lightScheme = Scheme(
        name = "LIGHT_PAPER",
        isDark = false,
        primary = ThemePalette.LightPrimary,
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
        featureThinking = ThemePalette.LightFeatureThinking
    )

    private fun darkScheme(
        name: String,
        primary: Int,
        background: Int,
        surfaceLow: Int,
        surfaceContainer: Int,
        surfaceHigh: Int,
        outline: Int
    ) = Scheme(
        name = name,
        isDark = true,
        primary = primary,
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
        featureThinking = ThemePalette.DarkFeatureThinking
    )

    private val allSchemes: List<Scheme> = listOf(
        lightScheme,
        darkScheme("ICE_BLUE", ThemePalette.IceBluePrimary, ThemePalette.IceBlueBackground,
            ThemePalette.IceBlueSurfaceLow, ThemePalette.IceBlueSurfaceContainer,
            ThemePalette.IceBlueSurfaceHigh, ThemePalette.IceBlueOutline),
        darkScheme("EMERALD", ThemePalette.EmeraldPrimary, ThemePalette.EmeraldBackground,
            ThemePalette.EmeraldSurfaceLow, ThemePalette.EmeraldSurfaceContainer,
            ThemePalette.EmeraldSurfaceHigh, ThemePalette.EmeraldOutline),
        darkScheme("AMBER", ThemePalette.AmberPrimary, ThemePalette.AmberBackground,
            ThemePalette.AmberSurfaceLow, ThemePalette.AmberSurfaceContainer,
            ThemePalette.AmberSurfaceHigh, ThemePalette.AmberOutline),
        darkScheme("DARK_SLATE", ThemePalette.DarkSlatePrimary, ThemePalette.DarkSlateBackground,
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
     * ④ 弱化文本：只允许 background / surfaceContainerLow。
     * `surfaceContainerHigh` 是明确禁止的组合（实测 3.3~3.9:1），见 AppTheme.textMuted 的注释。
     */
    @Test
    fun textMutedMeetsAaOnAllowedSurfaces() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.textMuted/background", s.textMuted, s.background, 4.5)
            assertAtLeast("${s.name}.textMuted/surfaceContainerLow", s.textMuted, s.surfaceLow, 4.5)
        }
    }

    /** ⑤ 三级文字必须互相可区分：否则"弱化"语义塌陷（深色曾把 muted 设成与 secondary 同值） */
    @Test
    fun textTiersAreDistinguishable() {
        allSchemes.forEach { s ->
            assertTrue(
                "${s.name}: textMuted 与 textSecondary 同值，三级文字梯度塌成两级",
                s.textMuted != s.textSecondary
            )
            assertTrue(
                "${s.name}: textSecondary 与 textPrimary 同值",
                s.textSecondary != s.textPrimary
            )
            // 顺序：primary 比 secondary 更亮/更暗，但两者的对比度必须构成递减关系
            val primaryContrast = ArgbContrast.contrastRatio(s.textPrimary, s.surfaceLow)
            val secondaryContrast = ArgbContrast.contrastRatio(s.textSecondary, s.surfaceLow)
            val mutedContrast = ArgbContrast.contrastRatio(s.textMuted, s.surfaceLow)
            assertTrue(
                "${s.name}: 文字梯度不是递减（primary=$primaryContrast secondary=$secondaryContrast muted=$mutedContrast）",
                primaryContrast > secondaryContrast && secondaryContrast > mutedContrast
            )
        }
    }

    /** ⑥ 发丝线 / 卡片边框：装饰性但必须有可见性 */
    @Test
    fun outlineIsVisibleAgainstCard() {
        allSchemes.forEach { s ->
            assertAtLeast("${s.name}.outline/surfaceContainerLow", s.outline, s.surfaceLow, 1.5)
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

    /** ⑪ 公式自检：黑白极值必须是 21:1，同色必须是 1:1 */
    @Test
    fun contrastFormulaSanity() {
        val blackWhite = ArgbContrast.contrastRatio(0xFF000000.toInt(), ArgbContrast.WHITE)
        assertTrue("黑白对比度应为 21:1，实测 $blackWhite", kotlin.math.abs(blackWhite - 21.0) < 0.01)
        val sameColor = ArgbContrast.contrastRatio(0xFF0969DA.toInt(), 0xFF0969DA.toInt())
        assertTrue("同色对比度应为 1:1，实测 $sameColor", kotlin.math.abs(sameColor - 1.0) < 0.0001)
    }
}
