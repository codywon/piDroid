package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 命令符号表的回归测试。
 *
 * 守护的线上故障（2026-11-01 用户截图）：行内公式 `$\rightarrow$` 被渲染成裸命令名
 * `rightarrow`，因为解析器的 `when` 分支没登记箭头，落到 `else` 把命令名当正文输出。
 *
 * 只测纯 JVM 逻辑：这个 symbol 表刻意不碰 Compose / Android 类型，才能在本机跑单元测试。
 */
class LatexSymbolsTest {

    @Test
    fun `arrows no longer leak as bare command names`() {
        assertEquals(" → ", LatexSymbols.resolve("rightarrow")?.text)
        assertEquals(" → ", LatexSymbols.resolve("to")?.text)
        assertEquals(" ← ", LatexSymbols.resolve("leftarrow")?.text)
        assertEquals(" ⇒ ", LatexSymbols.resolve("Rightarrow")?.text)
        assertEquals(" ↔ ", LatexSymbols.resolve("leftrightarrow")?.text)
        assertEquals(" ↦ ", LatexSymbols.resolve("mapsto")?.text)
    }

    @Test
    fun `relations and operators keep surrounding spaces`() {
        val times = LatexSymbols.resolve("times")
        assertNotNull(times)
        assertEquals(" × ", times!!.text)
        assertTrue(times.binary)
        assertEquals(" ≤ ", LatexSymbols.resolve("leq")?.text)
        assertEquals(" ≠ ", LatexSymbols.resolve("neq")?.text)
    }

    @Test
    fun `greek letters and big operators resolve without padding`() {
        assertEquals("Δ", LatexSymbols.resolve("Delta")?.text)
        assertEquals("δ", LatexSymbols.resolve("delta")?.text)
        assertEquals("Ω", LatexSymbols.resolve("Omega")?.text)
        assertEquals("∑", LatexSymbols.resolve("sum")?.text)
        assertEquals("∫", LatexSymbols.resolve("int")?.text)
        assertEquals("∞", LatexSymbols.resolve("infty")?.text)
        assertEquals("∂", LatexSymbols.resolve("partial")?.text)
        assertFalse(LatexSymbols.resolve("Delta")!!.binary)
    }

    @Test
    fun `unknown command stays unknown so the parser can drop it`() {
        // 关键约束：认不出来必须返回 null。返回命令名就等于把 `\foo` 印成 "foo"。
        assertNull(LatexSymbols.resolve("foo"))
        assertNull(LatexSymbols.resolve("unknowncmd"))
        assertNull(LatexSymbols.resolve(""))
    }

    @Test
    fun `layout only commands and functions are classified`() {
        assertTrue(LatexSymbols.isLayoutOnly("left"))
        assertTrue(LatexSymbols.isLayoutOnly("right"))
        assertTrue(LatexSymbols.isLayoutOnly("displaystyle"))
        assertFalse(LatexSymbols.isLayoutOnly("alpha"))

        assertTrue(LatexSymbols.isFunction("sin"))
        assertTrue(LatexSymbols.isFunction("log"))
        assertEquals("sin ", LatexSymbols.functionText("sin"))
        assertFalse(LatexSymbols.isFunction("sqrt"))
    }

    @Test
    fun `commands whose arguments must be swallowed are marked`() {
        assertTrue(LatexSymbols.takesDroppedArgument("label"))
        assertTrue(LatexSymbols.takesDroppedArgument("tag"))
        assertTrue(LatexSymbols.takesDroppedArgument("color"))
        assertFalse(LatexSymbols.takesDroppedArgument("text"))
    }
}
