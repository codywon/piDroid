package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelRefParserTest {
    // 真实响应（GET /api/sessions/{id}/context?tail=1，实测 1898 字节里真正要的就这几项）
    private val real = """
        {"context":{"messages":[],"entryIds":[],"thinkingLevel":"max",
          "model":{"provider":"deepseek","modelId":"deepseek-flash"}},"tail":1,"before":null}
    """.trimIndent()

    @Test
    fun `reads provider and model id from the real context payload`() {
        val ref = SessionModelRefParser.parse(real)!!
        assertEquals("deepseek", ref.provider)
        assertEquals("deepseek-flash", ref.modelId)
        assertEquals("max", ref.thinkingLevel)
    }

    @Test
    fun `same model id under another provider is read verbatim`() {
        val ref = SessionModelRefParser.parse(
            """{"context":{"model":{"provider":"codywon","modelId":"deepseek-flash"}}}"""
        )!!
        assertEquals("codywon", ref.provider)
        assertEquals("deepseek-flash", ref.modelId)
        assertNull(ref.thinkingLevel)
    }

    @Test
    fun `model id falls back to the id field`() {
        val ref = SessionModelRefParser.parse("""{"model":{"id":"gpt-6.1-sol","provider":"codywon"}}""")!!
        assertEquals("codywon", ref.provider)
        assertEquals("gpt-6.1-sol", ref.modelId)
    }

    @Test
    fun `half an identity is rejected so the ui is never half-updated`() {
        assertNull(SessionModelRefParser.parse("""{"context":{"model":{"provider":"deepseek"}}}"""))
        assertNull(SessionModelRefParser.parse("""{"context":{"model":{"modelId":"deepseek-flash"}}}"""))
        assertNull(SessionModelRefParser.parse("""{"context":{"model":{"provider":"","modelId":""}}}"""))
        assertNull(SessionModelRefParser.parse("""{"context":{"messages":[]}}"""))
    }

    @Test
    fun `garbage never throws`() {
        assertNull(SessionModelRefParser.parse(null))
        assertNull(SessionModelRefParser.parse(""))
        assertNull(SessionModelRefParser.parse("<html>502 Bad Gateway</html>"))
        assertNull(SessionModelRefParser.parse("[]"))
    }
}
