package com.pi.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LaTeX 解析器对非 BMP UTF-16 代理对字符（如数学斜体 `𝑖`、`𝑥`）切分的纯 JVM 回归测试。
 */
class LatexMathSurrogatePairTest {

    private fun assertNoLoneSurrogates(text: String, context: String) {
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            assertFalse("$context 不得出现孤立低位代理项", Character.isLowSurrogate(ch))
            if (Character.isHighSurrogate(ch)) {
                assertTrue(
                    "$context 高位代理项后必须紧跟低位代理项",
                    i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
                )
                i += 2
            } else {
                i += 1
            }
        }
    }

    private fun tokenText(token: LatexMathParser.MathToken): String = when (token) {
        is LatexMathParser.MathToken.Text -> token.content
        is LatexMathParser.MathToken.Variable -> token.name
        is LatexMathParser.MathToken.Operator -> token.symbol
        is LatexMathParser.MathToken.Superscript -> token.content
        is LatexMathParser.MathToken.Subscript -> token.content
    }

    @Test
    fun `subscript with non-BMP mathematical italic keeps complete surrogate pair`() {
        val tokens = LatexMathParser.tokenizeAndParseFormula("x_𝑖")

        tokens.forEach { assertNoLoneSurrogates(tokenText(it), "x_𝑖 token=$it") }
        val sub = tokens.filterIsInstance<LatexMathParser.MathToken.Subscript>().single()
        assertEquals("\uD835\uDC56", sub.content)
        assertEquals("𝑖", sub.content)
    }

    @Test
    fun `accent command without braces keeps complete non-BMP surrogate pair`() {
        val tokens = LatexMathParser.tokenizeAndParseFormula("\\hat 𝑥")

        tokens.forEach { assertNoLoneSurrogates(tokenText(it), "\\hat 𝑥 token=$it") }
        val text = tokens.filterIsInstance<LatexMathParser.MathToken.Text>().single()
        assertEquals("\uD835\uDC65\u0302", text.content)
        assertEquals("𝑥\u0302", text.content)
    }

    @Test
    fun `mixed non-BMP base variable and script produce no broken surrogates`() {
        val tokens = LatexMathParser.tokenizeAndParseFormula("𝑥_𝑖 + \\vec 𝑣")

        assertTrue(tokens.isNotEmpty())
        tokens.forEach { assertNoLoneSurrogates(tokenText(it), "𝑥_𝑖 + \\vec 𝑣 token=$it") }
        val sub = tokens.filterIsInstance<LatexMathParser.MathToken.Subscript>().single()
        assertEquals("𝑖", sub.content)
    }
}
