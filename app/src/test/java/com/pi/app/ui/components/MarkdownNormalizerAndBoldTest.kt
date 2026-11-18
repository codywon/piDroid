package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式 Markdown 未闭合代码围栏保护与行内代码 `**` 防破坏的纯 JVM 回归测试。
 */
class MarkdownNormalizerAndBoldTest {

    @Test
    fun `streaming unclosed backtick fence preserves fence header and raw content`() {
        val streaming = "说明如下：\n\n```python\nstatus=`0'\nprint(\"**keep**\")"
        val normalized = CjkMarkdownNormalizer.normalize(streaming)

        assertTrue(
            "未闭合代码围栏首行不得被篡改为 ``'python",
            normalized.contains("```python\n")
        )
        assertEquals(streaming, normalized)
    }

    @Test
    fun `streaming single line unclosed fence header is untouched`() {
        val streaming = "请看代码：\n```python"
        val normalized = CjkMarkdownNormalizer.normalize(streaming)

        assertEquals(streaming, normalized)
    }

    @Test
    fun `streaming unclosed tilde fence is untouched`() {
        val streaming = "配置如下：\n~~~yaml\nkey: `raw'\nodd_stars: **stay"
        val normalized = CjkMarkdownNormalizer.normalize(streaming)

        assertEquals(streaming, normalized)
    }

    @Test
    fun `closed fence followed by normal prose still normalizes prose only`() {
        val markdown = "```python\nx = `1'\n```\n\n正文里的 ** 加粗 **"
        val normalized = CjkMarkdownNormalizer.normalize(markdown)

        assertTrue(normalized.startsWith("```python\nx = `1'\n```"))
        assertTrue(normalized.contains("**加粗**"))
    }

    @Test
    fun `fallback bold never pairs across inline code spans`() {
        // 模拟 `a ** b` 和 `c ** d` 两段行内代码区间，各自含有一个 **
        val text = "在 a ** b 和 c ** d 之间"
        val codeRanges = listOf(
            2 until 8,   // "a ** b"
            11 until 17  // "c ** d"
        )
        val replacements = findSafeFallbackBoldReplacements(text, codeRanges)

        assertTrue("绝不能跨行内代码区间配对 **...**", replacements.isEmpty())
    }

    @Test
    fun `invalid bold inside inline code does not swallow later valid bold`() {
        val text = "在 a ** b 之后 **重点**"
        val codeRanges = listOf(2 until 8) // "a ** b"
        val replacements = findSafeFallbackBoldReplacements(text, codeRanges)

        assertEquals(1, replacements.size)
        val rep = replacements.single()
        assertEquals("**重点**", text.substring(rep.matchStart, rep.matchEnd))
        assertEquals("重点", text.substring(rep.innerStart, rep.innerEnd))
    }

    @Test
    fun `fallback bold does not cross half of a link boundary but allows wrapping whole link`() {
        val crossText = "点击 这里** 详情 和 另一半**"
        val crossLink = listOf(3 until 7) // "这里**"
        assertTrue(
            "不得跨半条链接边界匹配",
            findSafeFallbackBoldReplacements(crossText, emptyList(), crossLink).isEmpty()
        )

        val wrappedText = "请 **点击链接** 查看"
        val wrappedLink = listOf(4 until 8) // "点击链接"
        val wrappedReplacements = findSafeFallbackBoldReplacements(wrappedText, emptyList(), wrappedLink)
        assertEquals(1, wrappedReplacements.size)
        val rep = wrappedReplacements.single()
        assertEquals("**点击链接**", wrappedText.substring(rep.matchStart, rep.matchEnd))
        assertEquals("点击链接", wrappedText.substring(rep.innerStart, rep.innerEnd))
    }
}
