package com.pi.app.network

import com.google.gson.JsonParser
import com.pi.app.data.model.ExtensionUiDialog
import org.junit.Assert
import org.junit.Test

/**
 * `extension_ui_request` 的字段解析。
 *
 * 这些用例守的是两件事：
 * 1. 阻塞式请求（扩展在等用户回答，包括 session_start 阶段）必须能被完整还原，
 *    否则会话会一直停在原地而界面上什么都没有；
 * 2. 通知式请求不能被当成对话框弹出来 —— 用户没法「回答」一条 notify。
 */
class ExtensionUiParsingTest {

    private fun dialog(json: String): ExtensionUiDialog? =
        ExtensionUiParsing.of(JsonParser.parseString(json).asJsonObject)

    @Test
    fun `a select request carries its options`() {
        val d = dialog(
            """
            {"type":"extension_ui_request","id":"u1","method":"select",
             "title":"选择环境","options":["dev","prod"]}
            """.trimIndent()
        )

        Assert.assertNotNull(d)
        Assert.assertEquals(ExtensionUiDialog.METHOD_SELECT, d!!.method)
        Assert.assertEquals("选择环境", d.title)
        Assert.assertEquals(listOf("dev", "prod"), d.options)
        Assert.assertTrue(d.isBlocking)
    }

    @Test
    fun `a confirm request keeps its message`() {
        val d = dialog(
            """{"type":"extension_ui_request","id":"u2","method":"confirm","title":"确认","message":"要覆盖吗？"}"""
        )

        Assert.assertEquals("要覆盖吗？", d!!.message)
        Assert.assertTrue(d.isBlocking)
    }

    @Test
    fun `an editor request keeps its prefill and is blocking`() {
        val d = dialog(
            """{"type":"extension_ui_request","id":"u3","method":"editor","title":"改一下","prefill":"旧内容"}"""
        )

        Assert.assertEquals("旧内容", d!!.prefill)
        Assert.assertTrue(d.isBlocking)
    }

    @Test
    fun `an input request keeps its placeholder`() {
        val d = dialog("""{"type":"extension_ui_request","id":"u4","method":"input","placeholder":"名称"}""")

        Assert.assertEquals("名称", d!!.placeholder)
        Assert.assertTrue(d.isBlocking)
    }

    @Test
    fun `a custom panel blocks until the extension closes it`() {
        val open = dialog("""{"type":"extension_ui_request","id":"u5","method":"custom","lines":["a","b"]}""")
        val closed = dialog("""{"type":"extension_ui_request","id":"u6","method":"custom","lines":["a"],"closed":true}""")

        Assert.assertEquals(listOf("a", "b"), open!!.lines)
        Assert.assertTrue(open.isBlocking)
        Assert.assertFalse(closed!!.isBlocking)
    }

    @Test
    fun `a notify request is not a dialog`() {
        val d = dialog(
            """{"type":"extension_ui_request","id":"u7","method":"notify","message":"做完了","notifyType":"warning"}"""
        )

        Assert.assertEquals("做完了", d!!.message)
        Assert.assertEquals("warning", d.notifyType)
        Assert.assertFalse(d.isBlocking)
    }

    @Test
    fun `status and editor text pushes are not dialogs`() {
        val status = dialog("""{"type":"extension_ui_request","id":"u8","method":"setStatus","statusKey":"k","statusText":"跑着"}""")
        val text = dialog("""{"type":"extension_ui_request","id":"u9","method":"set_editor_text","text":"塞进输入框"}""")

        Assert.assertFalse(status!!.isBlocking)
        Assert.assertFalse(text!!.isBlocking)
    }

    @Test
    fun `a request without an id cannot be answered and is dropped`() {
        Assert.assertNull(dialog("""{"type":"extension_ui_request","method":"select","title":"x","options":[]}"""))
        Assert.assertNull(dialog("""{"type":"extension_ui_request","id":"u10"}"""))
    }

    @Test
    fun `non string entries are skipped instead of failing the whole request`() {
        val d = dialog("""{"type":"extension_ui_request","id":"u11","method":"select","options":["a",1,null,"b"]}""")

        Assert.assertEquals(listOf("a", "b"), d!!.options)
    }

    @Test
    fun `a string closed flag is not treated as closed`() {
        // 只有显式布尔 true 才算关掉：把 "true" 这种字符串当真会让阻塞面板被当成已关闭。
        val d = dialog("""{"type":"extension_ui_request","id":"u12","method":"custom","lines":[],"closed":"true"}""")

        Assert.assertTrue(d!!.isBlocking)
    }
}
