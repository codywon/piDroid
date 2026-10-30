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
        assertTrue(html.contains("initial-scale="))
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
     * 缩略图高度 = 容器宽 ×（声明高 / 声明宽），再夹进 120~460dp。
     * 800×600 是插件默认尺寸，在 336dp 宽容器里约 252dp。
     */
    @Test
    fun `preview height follows declared aspect ratio`() {
        assertEquals(252, previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 336))
    }

    @Test
    fun `preview height is clamped at both ends`() {
        assertEquals(120, previewHeightDp(WidgetPayload("t", "<b/>", 1600, 400), 336))
        assertEquals(460, previewHeightDp(WidgetPayload("t", "<b/>", 400, 3000), 336))
    }

    @Test
    fun `preview height tracks container width and survives zero width`() {
        val wide = previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 336)
        val narrow = previewHeightDp(WidgetPayload("t", "<b/>", 800, 600), 240)
        assertTrue(wide > narrow)
        // 模型写出 width=0 时不能除零崩掉
        assertTrue(previewHeightDp(WidgetPayload("t", "<b/>", 0, 600), 336) in 120..460)
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

    /**
     * 内嵌也**不再**回流到手机宽度，而是按声明宽度布局 + 整体等比缩放。
     *
     * 真机回归：回流到 360dp 时，一个 900px 设计、横排两栏的天气微组件会被压成竖排文字，
     * 内容高度还膨胀到声明值的两倍以上 —— 无论给多高的框都会裁掉一部分。
     * 现在的语义是「一张看全局的缩略图」：整张图缩进来，点开再看大图。
     */
    @Test
    fun `inline scales the declared width instead of reflowing`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<div>x</div>", width = 900, height = 920),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            initialScale = 387f / 900f
        )
        assertTrue(html.contains("width=900"))
        assertFalse(html.contains("width=device-width"))
        assertTrue(html.contains("initial-scale=0.43"))
    }

    /** 缩略图高度 = 声明高度 × 缩放比，且两端有夹紧（防超高/超扁）。 */
    @Test
    fun `thumbnail height follows the fit scale`() {
        // 900×920 缩进 387dp 宽 → 387×920/900 = 395.6 → 取整 395
        assertEquals(395, previewHeightDp(WidgetPayload("t", "<b/>", 900, 920), 387))
        // 超高 → 夹到上限，避免一张图把会话撑成几屏
        assertEquals(460, previewHeightDp(WidgetPayload("t", "<b/>", 900, 4000), 387))
        // 超扁 → 夹到下限，避免缩成一条线
        assertEquals(120, previewHeightDp(WidgetPayload("t", "<b/>", 4000, 200), 387))
        assertEquals(460, previewHeightDp(WidgetPayload("t", "<b/>", 200, 3000), 387))
    }

    /** 缩放比就是「容器宽 / 声明宽」，异常输入不外溢。 */
    @Test
    fun `fit scale is container width over declared width`() {
        assertEquals(0.43f, widgetFitScale(WidgetPayload("t", "<b/>", 900, 920), 387), 0.001f)
        assertEquals(1f, widgetFitScale(WidgetPayload("t", "<b/>", 320, 600), 900), 0f)
        assertTrue(widgetFitScale(WidgetPayload("t", "<b/>", 0, 600), 387) <= 1f)
    }

    // ── 设计令牌与 SVG 色阶注入 ─────────────────────────────────────

    /**
     * SVG 架构图依赖宿主注入的 `c-teal` / `c-purple` / `box` 样式。
     * 若未注入，W3C 规范默认无 fill 的 rect 回落为纯黑 (#000000)，直接变成黑块。
     */
    @Test
    fun `light mode injects light color ramps and tokens`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<svg><g class=\"c-teal\"><rect/><text class=\"th\">hi</text></g></svg>"),
            backgroundHex = "#FFFFFF",
            foregroundHex = "#1F2328",
            dark = false
        )
        // 浅色模式：薄荷绿 50 填充 (#E1F5EE) + 深绿 800 文字 (#085041) + 浅灰网关框 (#F6F8FA)
        assertTrue(html.contains(".c-teal > rect"))
        assertTrue(html.contains("#E1F5EE"))
        assertTrue(html.contains("#085041"))
        assertTrue(html.contains(".box { fill: var(--bg2);"))
        assertTrue(html.contains("--bg2: #F6F8FA;"))
    }

    @Test
    fun `dark mode injects dark color ramps and tokens matching pi-generative-ui`() {
        val html = buildWidgetHtml(
            payload = WidgetPayload("t", "<svg><g class=\"c-purple\"><rect/><text class=\"th\">hi</text></g></svg>"),
            backgroundHex = "#0D1117",
            foregroundHex = "#F0F6FC",
            dark = true
        )
        // 深色模式：深紫 800 填充 (#3C3489) + 紫粉 100 文字 (#CECBF6) + 暗炭灰网关框 (#2a2a2a)
        assertTrue(html.contains(".c-purple > rect"))
        assertTrue(html.contains("#3C3489"))
        assertTrue(html.contains("#CECBF6"))
        assertTrue(html.contains("--bg2: #2a2a2a;"))
    }
}
