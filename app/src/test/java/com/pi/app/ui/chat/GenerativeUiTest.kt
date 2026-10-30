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

    // ── 内嵌预览区高度 ─────────────────────────────────────────────

    /**
     * 预览区高度按模型声明的桌面宽高比换算，再夹进 180~320dp。
     * 800×600 是插件默认尺寸，在 360dp 宽的屏上应当约 250dp —— 太小看不清，
     * 太大就把「一眼看到」变成「又占一屏」。
     */
    @Test
    fun `preview height follows declared aspect ratio`() {
        assertEquals(252, previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 336))
    }

    @Test
    fun `preview height is clamped at both ends`() {
        assertEquals(180, previewHeightDp(WidgetPayload("t", "<b/>", 1600, 400), 336))
        assertEquals(320, previewHeightDp(WidgetPayload("t", "<b/>", 400, 3000), 336))
    }

    @Test
    fun `preview height tracks container width and survives zero width`() {
        val wide = previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 336)
        val narrow = previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 240)
        assertTrue(wide > narrow)
        // 模型写出 width=0 时不能除零崩掉
        assertTrue(previewHeightDp(WidgetPayload("t", "<b/>", 0, 600), 336) in 180..320)
    }

    // ── 渲染失败检测的钩子 ─────────────────────────────────────────

    /**
     * 白屏是不可接受的最低线：外壳必须内置错误哨兵与 `#root` 容器，
     * 宿主才能靠一个固定表达式判断「是不是什么都没画出来」并降级成源码视图。
     */
    @Test
    fun `html wrapper carries error sentinel and root container`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>"),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328"
        )
        assertTrue(html.contains("id=\"root\""))
        assertTrue(html.contains("__PI_ERR:"))
        // 外链脚本加载失败不走 window.onerror，必须靠捕获阶段监听 target 补上
        assertTrue(html.contains("tagName === 'SCRIPT'"))
        assertTrue(html.contains("unhandledrejection"))
    }

    /** 内嵌态永不自滚：纵向手势全部还给外层 LazyColumn。 */
    @Test
    fun `html wrapper disables page scrolling`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>"),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328"
        )
        assertTrue(html.contains("overflow: hidden"))
    }

    // ── 全屏模式 ────────────────────────────────────────────────

    /**
     * 全屏必须按**模型声明的桌面宽度**布局，而不是手机宽度。
     *
     * 真机回归：一个 900×880 的天气微组件用横排两栏（主卡 + 24 小时侧栏），
     * 在 360dp 宽下侧栏被压成一条竖排文字，右侧内容等于看不到。
     * 按声明宽度布局后由 WebView 等比缩放到屏宽，桌面布局得以完整还原。
     */
    @Test
    fun `fullscreen lays out at the declared desktop width`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", width = 900, height = 880),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            fullscreen = true
        )
        assertTrue(html.contains("width=900"))
        assertTrue(!html.contains("width=device-width"))
    }

    /**
     * 全屏必须可滚。两个地方都得放开：页面 `overflow`，以及宿主不再拦截手势。
     * 早期两处都写死 hidden / 拦截，导致超出视口的内容既不能滚也不能缩。
     */
    @Test
    fun `fullscreen allows scrolling while inline does not`() {
        val payload = WidgetPayload("t", "<div>x</div>")
        val inline = buildWidgetHtml(payload, "#FFFFFF", "#1F2328")
        val full = buildWidgetHtml(payload, "#FFFFFF", "#1F2328", fullscreen = true)
        assertTrue(inline.contains("overflow: hidden"))
        assertTrue(full.contains("overflow: auto"))
        assertFalse(full.contains("overflow: hidden"))
    }

    /**
     * 全屏必须把初始缩放比写进 viewport。
     *
     * 只给 `width=900` 是不够的：WebView 会按 900px 布局**但不缩出去**，
     * 真机表现就是只看到左边一半（右侧「未来 24 小时」整块被切掉）。
     */
    @Test
    fun `fullscreen writes the fit-to-width initial scale`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", width = 900, height = 900),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            fullscreen = true,
            initialScale = 411f / 900f
        )
        assertTrue(html.contains("initial-scale=0.4566"))
        assertTrue(html.contains("width=900"))
    }

    /** 缩放比异常（0 / NaN）时退回 1.0，不生成非法 viewport。 */
    @Test
    fun `invalid initial scale falls back to one`() {
        val zero = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", 900, 900),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            fullscreen = true,
            initialScale = 0f
        )
        assertTrue(zero.contains("initial-scale=1.0"))
        val nan = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", 900, 900),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            fullscreen = true,
            initialScale = Float.NaN
        )
        assertTrue(nan.contains("initial-scale=1.0"))
    }

    /** 内嵌仍然要回流到手机宽度（控件得大到能点、能拖）。 */
    @Test
    fun `inline keeps device width viewport`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", width = 900, height = 880),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328"
        )
        assertTrue(html.contains("width=device-width"))
    }
}
