package com.pi.app.ui.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 [CodeSyntaxHighlighter] 语法分词器的纯 JVM 单元测试。
 */
class CodeSyntaxHighlighterTest {

    @Test
    fun `python tokenization extracts docstrings keywords and function calls`() {
        val line1 = "\"\"\"Entry point for wechat_to_md MCP server.\"\"\""
        val tokens1 = CodeSyntaxHighlighter.tokenizeLine(line1, "python")
        assertEquals(1, tokens1.size)
        assertEquals(CodeTokenType.STRING, tokens1[0].type)
        assertEquals(line1, tokens1[0].text)

        val line2 = "from wechat_to_md.mcp_server import run_server"
        val tokens2 = CodeSyntaxHighlighter.tokenizeLine(line2, "python")
        assertTrue(tokens2.any { it.text == "from" && it.type == CodeTokenType.KEYWORD })
        assertTrue(tokens2.any { it.text == "import" && it.type == CodeTokenType.KEYWORD })
        assertTrue(tokens2.any { it.text == "run_server" && it.type == CodeTokenType.PLAIN_TEXT })

        val line3 = "    run_server()"
        val tokens3 = CodeSyntaxHighlighter.tokenizeLine(line3, "python")
        assertTrue(tokens3.any { it.text == "run_server" && it.type == CodeTokenType.FUNCTION_CALL })

        val line4 = "    # comment here"
        val tokens4 = CodeSyntaxHighlighter.tokenizeLine(line4, "python")
        assertTrue(tokens4.any { it.text == "# comment here" && it.type == CodeTokenType.COMMENT })
    }

    @Test
    fun `yaml tokenization highlights property keys, strings and comments`() {
        val line1 = "name: Build & Release πDroid APK"
        val tokens1 = CodeSyntaxHighlighter.tokenizeLine(line1, "yaml")
        assertEquals("name", tokens1[0].text)
        assertEquals(CodeTokenType.PROPERTY_KEY, tokens1[0].type)
        assertEquals(":", tokens1[1].text)
        assertEquals(CodeTokenType.PUNCTUATION, tokens1[1].type)

        val line2 = "  - 'v*'"
        val tokens2 = CodeSyntaxHighlighter.tokenizeLine(line2, "yaml")
        assertTrue(tokens2.any { it.text.contains("'v*'") })

        val line3 = "  timeout-minutes: 45"
        val tokens3 = CodeSyntaxHighlighter.tokenizeLine(line3, "yaml")
        assertEquals("timeout-minutes", tokens3.first { it.text == "timeout-minutes" }.text)

        val line4 = "# 同一 tag 的重复触发串行执行"
        val tokens4 = CodeSyntaxHighlighter.tokenizeLine(line4, "yaml")
        assertEquals(CodeTokenType.COMMENT, tokens4[0].type)
    }

    @Test
    fun `kotlin tokenization detects keywords numbers and comments`() {
        val line = "val maxBytes: Long = 1024L // limit"
        val tokens = CodeSyntaxHighlighter.tokenizeLine(line, "kotlin")
        assertTrue(tokens.any { it.text == "val" && it.type == CodeTokenType.KEYWORD })
        assertTrue(tokens.any { it.text == "1024L" && it.type == CodeTokenType.NUMBER })
        assertTrue(tokens.any { it.text == "// limit" && it.type == CodeTokenType.COMMENT })
    }

    @Test
    fun `resolveLanguage maps file extensions accurately`() {
        assertEquals("python", CodeSyntaxHighlighter.resolveLanguage("py"))
        assertEquals("yaml", CodeSyntaxHighlighter.resolveLanguage("yml"))
        assertEquals("yaml", CodeSyntaxHighlighter.resolveLanguage("yaml"))
        assertEquals("kotlin", CodeSyntaxHighlighter.resolveLanguage("kt"))
        assertEquals("kotlin", CodeSyntaxHighlighter.resolveLanguage("kts"))
        assertEquals("javascript", CodeSyntaxHighlighter.resolveLanguage("ts"))
        assertEquals("javascript", CodeSyntaxHighlighter.resolveLanguage("tsx"))
        assertEquals("json", CodeSyntaxHighlighter.resolveLanguage("json"))
        assertEquals("bash", CodeSyntaxHighlighter.resolveLanguage("sh"))
        assertEquals("text", CodeSyntaxHighlighter.resolveLanguage("unknown"))
    }
}
