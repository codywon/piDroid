package com.pi.app.network

import com.google.gson.JsonParser
import com.pi.app.data.model.MediaKind
import com.pi.app.data.model.MediaLoadPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果 / 消息内容的媒体解析回归测试。
 *
 * 守护的线上故障：pi-web 带 `deferMedia=1` 时把工具结果图换成
 * `{type:"image", source:{type:"url", media_type, url:"/api/sessions/..."}}`，
 * 旧实现只读顶层 `url` / `image_url.url`，看不到 `source.url`，
 * 于是延迟图附件被整体丢弃——bash 产出的图、或路径不在工作区的图永远不显示，
 * 只能靠 `read` 工具入参里的文件路径兜底。
 */
class MediaAttachmentParsingTest {

    private fun parse(contentJson: String, outputText: String? = null) =
        MediaAttachmentParsing.fromContent(JsonParser.parseString(contentJson), outputText)

    @Test
    fun `延迟图 source_url 能被解析出来`() {
        val media = parse(
            """
            [{"type":"image","source":{"type":"url","media_type":"image/png",
              "url":"/api/sessions/s1/entries/e1/tool-result-image?blockIndex=2"}}]
            """.trimIndent()
        )
        assertEquals(1, media.size)
        assertEquals(MediaKind.IMAGE, media[0].kind)
        assertEquals("image/png", media[0].mimeType)
        assertEquals(
            "/api/sessions/s1/entries/e1/tool-result-image?blockIndex=2",
            media[0].url
        )
        assertNull("延迟图不该带 base64", media[0].dataBase64)
    }

    @Test
    fun `内联 base64 图仍然解析且剥掉 data 前缀`() {
        val media = parse(
            """[{"type":"image","mimeType":"image/png","data":"data:image/png;base64,iVBORw0KGgo="}]"""
        )
        assertEquals(1, media.size)
        assertEquals("iVBORw0KGgo=", media[0].dataBase64)
        assertNull(media[0].url)
    }

    @Test
    fun `image_url 对象形态兼容`() {
        val media = parse("""[{"type":"image_url","image_url":{"url":"http://h/a.png"}}]""")
        assertEquals(1, media.size)
        assertEquals("http://h/a.png", media[0].url)
    }

    @Test
    fun `纯文本块不产出附件`() {
        val media = parse("""[{"type":"text","text":"done"}]""")
        assertTrue(media.isEmpty())
    }

    @Test
    fun `PDF 与 SVG 走各自 kind`() {
        val pdf = parse("""[{"type":"document","source":{"type":"url","media_type":"application/pdf","url":"/api/x"}}]""")
        assertEquals(MediaKind.PDF, pdf[0].kind)
        val svg = parse("""[{"type":"image","source":{"type":"url","media_type":"image/svg+xml","url":"/api/y"}}]""")
        assertEquals(MediaKind.SVG, svg[0].kind)
    }

    @Test
    fun `输出文本里的内联 SVG 仍然被识别`() {
        val media = parse(
            """[{"type":"text","text":"ok"}]""",
            "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect/></svg>"
        )
        assertTrue(media.any { it.kind == MediaKind.SVG && it.rawContent?.contains("<svg") == true })
    }

    @Test
    fun `工具附件 id 稳定_同一工具再次调用时失效`() {
        val deferred =
            """[{"type":"image","source":{"type":"url","media_type":"image/png","url":"/api/x"}}]"""
        val first = MediaLoadPolicy.toolIdPrefix("call_1")!!
        val again = MediaLoadPolicy.toolIdPrefix("call_1")!!
        val second = MediaLoadPolicy.toolIdPrefix("call_2")!!

        val a = MediaAttachmentParsing.fromContent(JsonParser.parseString(deferred), null, first)
        val b = MediaAttachmentParsing.fromContent(JsonParser.parseString(deferred), null, again)
        val c = MediaAttachmentParsing.fromContent(JsonParser.parseString(deferred), null, second)

        assertEquals("tcid:call_1#img0", a[0].id)
        assertEquals("同一工具调用每次解析都得到同一个 id", a[0].id, b[0].id)
        assertTrue("工具再调用一次必须换 id（磁盘缓存随之失效）", a[0].id != c[0].id)
    }

    @Test
    fun `没有工具 id 时退回随机 id`() {
        val json = JsonParser.parseString(
            """[{"type":"image","source":{"type":"url","media_type":"image/png","url":"/api/x"}}]"""
        )
        val a = MediaAttachmentParsing.fromContent(json, null, null)
        val b = MediaAttachmentParsing.fromContent(json, null, null)
        assertTrue(a[0].id.isNotBlank())
        assertTrue(a[0].id != b[0].id)
    }
}
