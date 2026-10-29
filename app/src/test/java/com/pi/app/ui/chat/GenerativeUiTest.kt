package com.pi.app.ui.chat

import com.pi.app.data.model.ToolCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 生成式 UI（`show_widget`）载荷解析回归测试。
 *
 * 守护的口径来自插件源码 `pi-generative-ui/.pi/extensions/generative-ui/index.ts`：
 *  - 工具名固定为 `show_widget`，参数 `title` / `widget_code` / `width` / `height`；
 *  - `title` 是 snake_case，展示时要换成空格；
 *  - `width` / `height` 缺省 800×600；
 *  - `visualize_read_me` 是内部步骤，聊天流里不展示。
 */
class GenerativeUiTest {

    private fun tool(name: String, vararg fields: Pair<String, String>) = ToolCallInfo(
        toolName = name,
        fields = fields.toList()
    )

    @Test
    fun `non widget tool returns null`() {
        assertNull(showWidgetPayload(tool("bash", "command" to "ls")))
        assertNull(showWidgetPayload(tool("visualize_read_me", "modules" to "[\"chart\"]")))
    }

    @Test
    fun `widget without code returns null`() {
        assertNull(showWidgetPayload(tool("show_widget", "title" to "empty_chart")))
        assertNull(showWidgetPayload(tool("show_widget", "widget_code" to "   ")))
    }

    @Test
    fun `parses title code and default size`() {
        val payload = showWidgetPayload(
            tool("show_widget", "widget_code" to "<div>hi</div>", "title" to "sales_overview")
        )
        assertEquals("sales overview", payload?.title)
        assertEquals("<div>hi</div>", payload?.code)
        assertEquals(800, payload?.width)
        assertEquals(600, payload?.height)
        assertFalse(payload!!.isSvg)
    }

    @Test
    fun `parses explicit size and clamps out of range values`() {
        val ok = showWidgetPayload(
            tool("show_widget", "widget_code" to "<svg/>", "width" to "760", "height" to "730")
        )
        assertEquals(760, ok?.width)
        assertEquals(730, ok?.height)
        assertTrue(ok!!.isSvg)

        val clamped = showWidgetPayload(
            tool("show_widget", "widget_code" to "<div/>", "width" to "99999", "height" to "1")
        )
        assertEquals(4096, clamped?.width)
        assertEquals(200, clamped?.height)
    }

    @Test
    fun `svg detection tolerates leading whitespace`() {
        val payload = showWidgetPayload(
            tool("show_widget", "widget_code" to "\n  <svg viewBox=\"0 0 10 10\"></svg>")
        )
        assertTrue(payload!!.isSvg)
    }

    @Test
    fun `internal read me tool is recognized`() {
        assertTrue(isGenerativeUiInternalTool(tool("visualize_read_me")))
        assertTrue(isGenerativeUiInternalTool(tool("VISUALIZE_READ_ME")))
        assertFalse(isGenerativeUiInternalTool(tool("show_widget")))
        assertFalse(isGenerativeUiInternalTool(tool("bash")))
    }

    @Test
    fun `falls back to json command when fields are empty`() {
        val payload = showWidgetPayload(
            ToolCallInfo(
                toolName = "show_widget",
                command = """{"widget_code":"<div>x</div>","title":"from_stream","width":640,"height":480}"""
            )
        )
        assertEquals("from stream", payload?.title)
        assertEquals(640, payload?.width)
        assertEquals(480, payload?.height)
        assertEquals("<div>x</div>", payload?.code)
    }

    @Test
    fun `non json command does not crash`() {
        assertNull(showWidgetPayload(ToolCallInfo(toolName = "show_widget", command = "<div>oops</div>")))
        assertNull(showWidgetPayload(ToolCallInfo(toolName = "show_widget", command = "{not json")))
        assertNull(showWidgetPayload(ToolCallInfo(toolName = "show_widget", command = null)))
    }

    @Test
    fun `html wrapper adds viewport and keeps fragment untouched`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload(title = "t", code = "<div id=\"root\">x</div>"),
            backgroundHex = "#0D1117",
            foregroundHex = "#F0F6FC"
        )
        assertTrue(html.contains("width=device-width"))
        assertTrue(html.contains("<div id=\"root\">x</div>"))
        assertTrue(html.contains("#0D1117"))
        assertTrue(html.contains("<body>"))
    }

    @Test
    fun `html wrapper centers raw svg`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload(title = "t", code = "<svg></svg>"),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328"
        )
        assertTrue(html.contains("justify-content: center"))
        assertTrue(html.contains("<svg></svg>"))
    }
}
