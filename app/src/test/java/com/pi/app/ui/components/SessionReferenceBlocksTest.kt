package com.pi.app.ui.components

import org.junit.Assert
import org.junit.Test

/**
 * `<session-reference>` 块解析。
 *
 * 这些用例守的是「一段被引用的会话必须完整地变成一张折叠卡」：
 * 属性要能还原（上游做了 HTML 转义）、正文不能被吞、找不到闭合标签时宁可原样保留。
 */
class SessionReferenceBlocksTest {

    /**
     * 与上游 `buildReferenceBlock()` 逐字一致的块：
     * 一行英文说明 + 同一个句子里带路径，然后空行，然后正文。
     */
    private fun block(attrs: String, body: String): String = buildString {
        append("<session-reference ").append(attrs).append(">\n")
        append("Quoted from an earlier conversation for background only; it is not part of this request. ")
        append("The whole session is in D:\\work\\s.jsonl\n")
        append("\n")
        append(body).append("\n")
        append("</session-reference>")
    }

    @Test
    fun `a message without a reference stays one markdown segment`() {
        val segments = SessionReferenceBlocks.split("就一句话")

        Assert.assertEquals(1, segments.size)
        Assert.assertEquals("就一句话", (segments.single() as MessageSegment.Markdown).text)
    }

    @Test
    fun `a lone reference block becomes one quoted session`() {
        val text = block(
            "title=\"重构导出中心\" id=\"s-1\" path=\"D:\\work\\s.jsonl\" messages=\"12\" omitted=\"3\"",
            "用户：帮我看看\n\n助手：好的"
        )

        val segments = SessionReferenceBlocks.split(text)

        Assert.assertEquals(1, segments.size)
        val quote = (segments.single() as MessageSegment.Reference).quote
        Assert.assertEquals("重构导出中心", quote.title)
        Assert.assertEquals("s-1", quote.id)
        Assert.assertEquals("D:\\work\\s.jsonl", quote.path)
        Assert.assertEquals(12, quote.messageCount ?: -1)
        Assert.assertEquals(3, quote.omittedMessages ?: -1)
        // 上游那行英文说明（含路径那句）是给模型的，界面上不再重复
        Assert.assertEquals("用户：帮我看看\n\n助手：好的", quote.body)
    }

    @Test
    fun `text around the block is preserved in order`() {
        val text = "先看这个：" + block("title=\"A\" id=\"a\"", "正文A") + "然后继续"

        val segments = SessionReferenceBlocks.split(text)

        Assert.assertEquals(3, segments.size)
        Assert.assertEquals("先看这个：", (segments[0] as MessageSegment.Markdown).text)
        Assert.assertEquals("A", (segments[1] as MessageSegment.Reference).quote.title)
        Assert.assertEquals("然后继续", (segments[2] as MessageSegment.Markdown).text)
    }

    @Test
    fun `two references both survive`() {
        val text = block("title=\"A\" id=\"a\"", "正文A") + block("title=\"B\" id=\"b\"", "正文B")

        val quotes = SessionReferenceBlocks.split(text).filterIsInstance<MessageSegment.Reference>()

        Assert.assertEquals(listOf("A", "B"), quotes.map { it.quote.title })
    }

    @Test
    fun `escaped attributes are decoded`() {
        val text = block(
            "title=\"a &quot;quoted&quot; &amp; b\" id=\"s&lt;2&gt;\" path=\"D:\\w\\s.jsonl\"",
            "x"
        )

        val quote = (SessionReferenceBlocks.split(text).single() as MessageSegment.Reference).quote

        Assert.assertEquals("a \"quoted\" & b", quote.title)
        Assert.assertEquals("s<2>", quote.id)
        Assert.assertEquals("D:\\w\\s.jsonl", quote.path)
    }

    @Test
    fun `an unnamed session falls back to a readable label`() {
        // 上游对未命名会话写 title=""，折叠头不能显示一对空引号
        val text = block("title=\"\" id=\"s-9\"", "x")

        val quote = (SessionReferenceBlocks.split(text).single() as MessageSegment.Reference).quote

        Assert.assertEquals("未命名会话", quote.displayTitle)
    }

    @Test
    fun `an unclosed block is left as text instead of being swallowed`() {
        val text = "<session-reference title=\"A\" id=\"a\">\n没有被闭合"

        val segments = SessionReferenceBlocks.split(text)

        Assert.assertEquals(1, segments.size)
        Assert.assertTrue(segments.single() is MessageSegment.Markdown)
    }

    @Test
    fun `the note line is kept when it is not the first thing in the body`() {
        // 正文自己以英文开头时不能乱删
        val text = block("title=\"A\" id=\"a\"", "Quoted from memory, not from pi.\n\n其余正文")

        val quote = (SessionReferenceBlocks.split(text).single() as MessageSegment.Reference).quote

        Assert.assertTrue(quote.body.startsWith("Quoted from memory"))
    }

    @Test
    fun `plain text rendering names the quoted session`() {
        val text = "看：" + block("title=\"A\" id=\"a\"", "很长很长的正文")

        val plain = SessionReferenceBlocks.toPlainText(text)

        Assert.assertEquals("看： 〔引用会话：A〕", plain)
    }

    @Test
    fun `blank input yields no segments`() {
        Assert.assertTrue(SessionReferenceBlocks.split("   ").isEmpty())
    }
}
