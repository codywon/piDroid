package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超宽公式换行的回归测试（纯 JVM，不碰 Android / Compose）。
 *
 * 这一层最容易出两种事故，都必须被拦住：
 *  1. **把好公式切坏**：在 `\text{…}` 里、`>=` 中间、负号上断行，换行后每行都不是合法公式，
 *     KaTeX 直接画出红色错误；
 *  2. **丢掉内容**：断行时漏掉一个字符，用户看到的公式就是错的 —— 这在工程图纸里是灾难。
 *
 * 因此除了「该断的行数够」，每条用例还断言**拼回去与原文等价**（忽略空白）。
 */
class LatexFormulaWrapperTest {

    /** 截图里那条把字号缩到不可读的长公式：工程口径，含大量 CJK 标签。 */
    private val longFormula =
        "L = \\text{水平 } 61.87\\text{m} + \\text{垂直 } 0.5\\text{m} + \\text{横向 } 5.81\\text{m} " +
            "+ \\text{柜横向 } 4.5\\text{m} + \\text{柜下引 } 5.5\\text{m} + \\text{富余量 } 9.0\\text{m} " +
            "= 87.18\\text{m} < 90\\text{m}"

    /** 手机上一张公式卡片里真正能用的宽度（dp）与字号。 */
    private fun wrapOnPhone(latex: String, widthDp: Float = 290f, fontSizeDp: Float = 15f): String =
        LatexFormulaWrapper.wrap(latex, availableWidthDp = widthDp, fontSizeDp = fontSizeDp)

    private fun compact(text: String): String = text.filterNot { it.isWhitespace() }

    /** 还原换行结果：去掉包裹环境与行分隔符，拼回一条公式。 */
    private fun flatten(wrapped: String): String {
        val body = wrapped
            .removePrefix("\\begin{gathered}")
            .removeSuffix("\\end{gathered}")
        return compact(body.split(LatexFormulaWrapper.ROW_BREAK).joinToString("") { it.trim() })
    }

    @Test
    fun `short formula is left untouched`() {
        val short = "E = mc^2"
        assertEquals(short, wrapOnPhone(short))
        // 不存在断点时绝不能硬加一层 gathered
        assertFalse(wrapOnPhone(short).contains("gathered"))
    }

    @Test
    fun `long engineering formula is broken into rows without losing content`() {
        val wrapped = wrapOnPhone(longFormula)
        assertTrue("宽公式必须换行", wrapped.contains("\\begin{gathered}"))
        assertTrue(wrapped.contains("\\end{gathered}"))

        val rows = wrapped
            .removePrefix("\\begin{gathered}")
            .removeSuffix("\\end{gathered}")
            .split(LatexFormulaWrapper.ROW_BREAK)
            .map { it.trim() }
        assertTrue("至少要断成两行，实际 ${rows.size}", rows.size >= 2)
        assertTrue(rows.all { it.isNotEmpty() })
        // 内容一个字符都不能少
        assertEquals(compact(longFormula), flatten(wrapped))
    }

    @Test
    fun `breaks land after operators so rows never start with one`() {
        val wrapped = wrapOnPhone(longFormula)
        val rows = wrapped
            .removePrefix("\\begin{gathered}")
            .removeSuffix("\\end{gathered}")
            .split(LatexFormulaWrapper.ROW_BREAK)
            .map { it.trim() }
        // 除首行外，每行都不应以 + / = / , 开头（那是「运算符留在上一行末尾」的直接后果）
        rows.drop(1).forEach { row ->
            assertFalse("行首不应是运算符：$row", row.startsWith("+") || row.startsWith("=") || row.startsWith(","))
        }
        // 首行必须以断点运算符收尾，否则说明切在了操作数中间
        assertTrue(rows.first().endsWith("+"))
    }

    @Test
    fun `narrower card or bigger font produces at least as many rows`() {
        fun rowCount(width: Float, fontSize: Float): Int =
            wrapOnPhone(longFormula, width, fontSize)
                .split(LatexFormulaWrapper.ROW_BREAK)
                .size

        val base = rowCount(290f, 15f)
        val biggerFont = rowCount(290f, 19.5f)
        val narrower = rowCount(220f, 15f)
        assertTrue("字号变大后不能反而更少行", biggerFont >= base)
        assertTrue("卡片变窄后不能反而更少行", narrower >= base)
    }

    @Test
    fun `a formula that already fits is not wrapped even if it is long`() {
        // 平板 / 横屏：可用宽度足够，就不该人为断行
        assertFalse(wrapOnPhone(longFormula, widthDp = 2000f).contains("gathered"))
    }

    @Test
    fun `unsafe structures are never touched`() {
        // 已有多行环境
        val aligned = "\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}"
        assertEquals(aligned, wrapOnPhone("$aligned + q + q + q + q + q + q + q + q + q + q + q + q + q + q"))
        // 成对括号：从中间切开会让每行括号都不配平
        val leftRight = "\\left( a + b + c + d + e + f + g + h + i + j + k + l \\right) = z"
        assertEquals(leftRight, wrapOnPhone(leftRight))
        // 花括号不配平：拒绝换行，交给 KaTeX 自己报错，而不是切出一堆坏公式
        val unbalanced = "\\frac{a + b + c + d + e + f + g + h + i + j + k + l}{m"
        assertEquals(unbalanced, wrapOnPhone(unbalanced))
    }

    @Test
    fun `no break point means no wrapping`() {
        val single = "\\text{" + "很长的中文标签".repeat(12) + "}"
        assertEquals(single, wrapOnPhone(single))
    }

    @Test
    fun `operators inside text and numbers are not break points`() {
        // \text{} 里的加号受花括号保护
        val inText = LatexFormulaWrapper.splitAtTopLevelBreaks("a = \\text{长 + 宽}")
        assertEquals(2, inText.size)
        assertEquals("a = ", inText[0])
        assertEquals("\\text{长 + 宽}", inText[1])

        // 数字里的负号、指数里的减号都不能切
        assertEquals(1, LatexFormulaWrapper.splitAtTopLevelBreaks("x -1 e-5").size)
        // 两侧带空格的减号才是二元减号
        val minus = LatexFormulaWrapper.splitAtTopLevelBreaks("a - b - c")
        assertEquals(listOf("a - ", "b - ", "c"), minus)

        // 比较运算符不能被切成半个
        assertEquals(1, LatexFormulaWrapper.splitAtTopLevelBreaks("a >= b").size)
        assertEquals(1, LatexFormulaWrapper.splitAtTopLevelBreaks("a <= b").size)
        assertEquals(1, LatexFormulaWrapper.splitAtTopLevelBreaks("a != b").size)
    }

    @Test
    fun `trailing operator does not create an empty last row`() {
        val pieces = LatexFormulaWrapper.splitAtTopLevelBreaks("a + b +")
        assertEquals(listOf("a + ", "b +"), pieces)
    }

    @Test
    fun `invalid metrics disable wrapping instead of guessing`() {
        assertFalse(wrapOnPhone(longFormula, widthDp = 0f).contains("gathered"))
        assertFalse(wrapOnPhone(longFormula, widthDp = Float.NaN).contains("gathered"))
        assertFalse(wrapOnPhone(longFormula, fontSizeDp = 0f).contains("gathered"))
        assertFalse(wrapOnPhone(longFormula, fontSizeDp = Float.NaN).contains("gathered"))
    }

    @Test
    fun `row budget follows the em width of the card`() {
        // 300dp / 15dp = 20em 一行；1em ≈ 2 权重单位，再打 8% 余量
        val budget = LatexFormulaWrapper.rowBudgetWeight(300f, 15f)!!
        assertEquals(36.8, budget, 0.01)
        // 字号翻倍，一行能装的权重单位减半（这就是「调大字号后行数变多」的来源）
        assertEquals(budget / 2.0, LatexFormulaWrapper.rowBudgetWeight(300f, 30f)!!, 0.01)
    }

    @Test
    fun `cjk characters weigh twice a latin letter`() {
        assertEquals(4.0, LatexFormulaWrapper.weightOf("中文"), 0.001)
        assertEquals(4.0, LatexFormulaWrapper.weightOf("abcd"), 0.001)
        assertTrue(LatexFormulaWrapper.weightOf("中文") > LatexFormulaWrapper.weightOf("ab"))
    }
}
