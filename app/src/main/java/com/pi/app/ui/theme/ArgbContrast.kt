package com.pi.app.ui.theme

/**
 * 全工程唯一的 WCAG 2.1 对比度实现（纯 Kotlin，无 Compose / Android 依赖）。
 *
 * 为什么必须只有这一份实现：
 * 1. 生产代码（[ThemePalette] → `AppTheme.onPrimary`）与纯 JVM 单测
 *    （`ColorContrastTest`）如果各写一份公式，测试就只是「复述自己的假设」，
 *    给出的是假绿。历史上设计稿手写「`#D97706` 星标 4.63:1」，按本公式实测只有 3.19:1。
 * 2. 入参刻意用 `Int`（0xAARRGGBB）而不是 `androidx.compose.ui.graphics.Color`，
 *    这样单测在 JVM 上跑的时候不会去初始化 Compose 图形类 / `android.graphics.*`，
 *    符合 AGENTS.md §3「单元测试不得依赖 Android 运行时」。
 */
object ArgbContrast {

    /** 深墨前景：压在亮主色（AMBER `#FBBF24`、EMERALD `#67DF70`、DARK_SLATE `#58A6FF`）上时用它 */
    val INK: Int = 0xFF040D21.toInt()

    /** 纯白前景 */
    val WHITE: Int = 0xFFFFFFFF.toInt()

    /** sRGB 单通道 → 线性光分量（WCAG 2.1 定义的 0.03928 分段点） */
    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** 相对亮度 L，取值 0.0 ~ 1.0 */
    fun luminance(argb: Int): Double {
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    /** 对比度 = (L亮 + 0.05) / (L暗 + 0.05)，取值 1.0 ~ 21.0 */
    fun contrastRatio(foreground: Int, background: Int): Double {
        val a = luminance(foreground)
        val b = luminance(background)
        val lighter = if (a > b) a else b
        val darker = if (a > b) b else a
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * 给定底色，返回对比度更高的前景（白 or 深墨）。
     *
     * 不再用「亮度 > 0.4 就选深色」这类魔数阈值：阈值与 WCAG 的黑白交叉点并不重合，
     * 直接比较两个候选的实际对比度才是可验证的判据。
     *
     * 实测（5 套主题的 primary）：
     * LIGHT_PAPER `#0969DA` → 白 5.19（深墨 3.73）
     * ICE_BLUE `#38BDF8` → 深墨 9.04（白 2.14）
     * EMERALD `#67DF70` → 深墨 11.40（白 1.70）
     * AMBER `#FBBF24` → 深墨 11.60（白 1.67）
     * DARK_SLATE `#58A6FF` → 深墨 7.66（白 2.53）
     */
    fun bestOn(background: Int): Int =
        if (contrastRatio(INK, background) >= contrastRatio(WHITE, background)) INK else WHITE
}
