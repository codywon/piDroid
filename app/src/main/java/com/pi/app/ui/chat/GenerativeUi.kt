package com.pi.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pi.app.data.model.ToolCallInfo
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 生成式 UI（`pi-generative-ui` 插件）承载层。
 *
 * 插件侧（pi-generative-ui，`EXTENSIONS/generative-ui/index.ts`）注册两个工具：
 *  - `visualize_read_me(modules)`：模型自用的设计指南，插件明确要求「不要向用户提及」；
 *  - `show_widget(title, widget_code, width, height, floating)`：把一段 HTML 片段或裸 SVG
 *    渲染出来，`widget_code` 是唯一的内容载体，`details` 里只回 `{title,width,height,isSVG}`。
 *
 * 容器形态**对标 claude.ai，而不是 pi-web**：pi-web 没有这条链路（它那份 `widgets` 是 TUI
 * 状态栏微件，与本工具无关），桌面端 pi 用 Glimpse 开独立原生窗口。手机上最接近的等价物是
 * 「会话内嵌可交互预览 + 点击全屏接管手势」，而不是「一行入口卡 + 手动全屏」——
 * 后者把「看见」变成了「去查找」。理由与边界见 `docs/DESIGN-generative-ui.md`。
 */
private const val SHOW_WIDGET_TOOL = "show_widget"

/** 插件内部的设计指南工具名：聊天流里不展示（插件本身要求对用户不可见）。 */
const val GENERATIVE_UI_READ_ME_TOOL = "visualize_read_me"

/** 内嵌缩略图高度：按声明宽高比等比缩放后的高度夹在这个区间。 */
private const val PREVIEW_MIN_DP = 120
private const val PREVIEW_MAX_DP = 460

/**
 * 一个可渲染的微组件。`width` / `height` 是模型声明的**桌面**尺寸（默认 800×600）。
 * 两处都用它做**等比缩放**的依据（见 [buildWidgetHtml]）：内嵌是一张看全局的缩略图，
 * 全屏是可放大查看大图，都不做窄屏回流。
 */
data class WidgetPayload(
    val title: String,
    val code: String,
    val width: Int = 800,
    val height: Int = 600
) {
    val isSvg: Boolean
        get() = code.trimStart().startsWith("<svg", ignoreCase = true)
}

/**
 * 从工具调用里认出 `show_widget` 并取出载荷；不是微组件调用时返回 null。
 *
 * `fields` 由 `PiApiClient.extractToolFields` 从 assistant message 的 `input/arguments`
 * 原样摊平，`widget_code` 是完整代码（历史回放路径可靠；实时 SSE 的
 * `tool_execution_start` 只带 `{toolCallId, toolName}`，不带入参 —— 插件在
 * `toolcall_end` 之后 pi 才把入参落进 assistant message）。
 */
fun showWidgetPayload(tool: ToolCallInfo): WidgetPayload? {
    if (!tool.toolName.equals(SHOW_WIDGET_TOOL, ignoreCase = true)) return null
    val fields = widgetFields(tool)
    val code = fields.fieldOf("widget_code")?.takeIf { it.isNotBlank() } ?: return null
    val title = fields.fieldOf("title")
        ?.replace('_', ' ')
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: "widget"
    return WidgetPayload(
        title = title,
        code = code,
        width = fields.intFieldOf("width", 800, 200, 4096),
        height = fields.intFieldOf("height", 600, 200, 4096)
    )
}

/**
 * 入参来源有两套，这里统一取：
 * 1. `fields`：历史回放路径（`PiApiClient.extractToolFields` 从 assistant message 摊平）；
 * 2. `command`：实时 SSE 路径只带 `{toolCallId, toolName}`，没有 `fields`，但 pi 会把入参
 *    序列化成一整个 JSON 放进 `command`，能解析就用它 —— 否则要等重新加载会话才能看到微组件。
 */
private fun widgetFields(tool: ToolCallInfo): List<Pair<String, String>> {
    if (tool.fields.isNotEmpty()) return tool.fields
    val raw = tool.command?.trim().orEmpty()
    if (!raw.startsWith("{")) return emptyList()
    val obj = runCatching {
        com.google.gson.JsonParser.parseString(raw).asJsonObject
    }.getOrNull() ?: return emptyList()
    return obj.entrySet().mapNotNull { (k, v) ->
        if (v == null || v.isJsonNull) null else k to (if (v.isJsonPrimitive) v.asString else v.toString())
    }
}

/** 是否为插件内部工具（读指南），聊天流里应当整条隐藏。 */
fun isGenerativeUiInternalTool(tool: ToolCallInfo): Boolean =
    tool.toolName.equals(GENERATIVE_UI_READ_ME_TOOL, ignoreCase = true)

private fun List<Pair<String, String>>.fieldOf(key: String): String? =
    firstOrNull { it.first.equals(key, ignoreCase = true) }?.second

private fun List<Pair<String, String>>.intFieldOf(
    key: String,
    fallback: Int,
    min: Int,
    max: Int
): Int {
    val raw = fieldOf(key) ?: return fallback
    val parsed = raw.trim().toDoubleOrNull()?.toInt() ?: return fallback
    return parsed.coerceIn(min, max)
}

/**
 * 内嵌预览区高度：按模型声明的桌面宽高比换算到当前可用宽度，再夹进
 * [PREVIEW_MIN_DP]~[PREVIEW_MAX_DP]。800×600 在 360dp 宽的屏上约 250dp。
 */
/**
 * 内嵌缩略图的高度：**整张图按宽度等比缩进容器**之后的高度。
 *
 * 前提是页面按声明的桌面宽度布局（不是回流到手机宽度）—— 这样 `height` 才是可信的：
 * 它就是模型自己在那个宽度下排出来的高度。早期版本让内容回流到 360dp，
 * 而 360dp 下同样内容会变成两倍以上高度，于是"按声明比例算出来的框"永远装不下，
 * 真机表现就是只露一半。
 *
 * 单测钉住：800×600 在 336dp 宽容器里 → 252dp；1600×400 → 夹到下限；400×3000 → 夹到上限。
 */
internal fun previewHeightDp(payload: WidgetPayload, containerWidthDp: Int): Int {
    val ratio = payload.height.toFloat() / payload.width.toFloat().coerceAtLeast(1f)
    return (containerWidthDp * ratio).toInt().coerceIn(PREVIEW_MIN_DP, PREVIEW_MAX_DP)
}

/**
 * 等比缩放比：把声明的桌面宽度整体缩进容器宽度。
 *
 * 注意单位：WebView 在 `initial-scale=1` 时 1 CSS px == 1 dp，所以直接
 * `容器宽dp / 声明宽` 就是这个比例。
 */
internal fun widgetFitScale(payload: WidgetPayload, containerWidthDp: Int): Float =
    (containerWidthDp.toFloat() / payload.width.toFloat().coerceAtLeast(1f)).coerceIn(0.05f, 1f)

/** 控制台哨兵：页面里的运行错误会以这个前缀打到 `onConsoleMessage`，宿主据此降级。 */
private const val ERROR_SENTINEL = "__PI_ERR:"

/**
 * 一条色阶：SVG 里的 `class="c-teal"` 之类。
 *
 * 这些类名**不是模型自造的**，而是宿主必须注入的设计令牌。缺了它们，`<rect>` 会回落到
 * SVG 默认的 `fill: black`、`<text>` 也变黑 —— 真机上就是「整张图一堆黑块、字全看不见」。
 */
private data class SvgRamp(
    val cls: String,
    val fill: String,
    val stroke: String,
    val title: String,
    val subtitle: String
)

/**
 * 深色色阶：**逐字取自** `pi-generative-ui` 的 `svg-styles.ts`（那份是从 claude.ai 逆向提取的
 * 权威样式），填充用 800、描边用 400、标题 100、副标题 200。
 */
private val RAMPS_DARK = listOf(
    SvgRamp("purple", "#3C3489", "#7F77DD", "#CECBF6", "#AFA9EC"),
    SvgRamp("teal", "#085041", "#1D9E75", "#9FE1CB", "#5DCAA5"),
    SvgRamp("coral", "#712B13", "#D85A30", "#F5C4B3", "#F0997B"),
    SvgRamp("pink", "#72243E", "#D4537E", "#F4C0D1", "#ED93B1"),
    SvgRamp("gray", "#444441", "#888780", "#D3D1C7", "#B4B2A9"),
    SvgRamp("blue", "#0C447C", "#378ADD", "#B5D4F4", "#85B7EB"),
    SvgRamp("green", "#27500A", "#639922", "#C0DD97", "#97C459"),
    SvgRamp("amber", "#633806", "#BA7517", "#FAC775", "#EF9F27"),
    SvgRamp("red", "#791F1F", "#E24B4A", "#F7C1C1", "#F09595")
)

/**
 * 浅色色阶：参考实现只发深色一套（它的 Glimpse 外壳固定深色），所以这里按**同一份设计规范**
 * 推导 —— 规范原文是「浅色：50 填充 + 600 描边 + 800 标题 / 600 副标题」，
 * 色值取自 `visualize_read_me` 给出的 9 色阶表。
 */
private val RAMPS_LIGHT = listOf(
    SvgRamp("purple", "#EEEDFE", "#534AB7", "#3C3489", "#534AB7"),
    SvgRamp("teal", "#E1F5EE", "#0F6E56", "#085041", "#0F6E56"),
    SvgRamp("coral", "#FAECE7", "#993C1D", "#712B13", "#993C1D"),
    SvgRamp("pink", "#FBEAF0", "#993556", "#72243E", "#993556"),
    SvgRamp("gray", "#F1EFE8", "#5F5E5A", "#444441", "#5F5E5A"),
    SvgRamp("blue", "#E6F1FB", "#185FA5", "#0C447C", "#185FA5"),
    SvgRamp("green", "#EAF3DE", "#3B6D11", "#27500A", "#3B6D11"),
    SvgRamp("amber", "#FAEEDA", "#854F0B", "#633806", "#854F0B"),
    SvgRamp("red", "#FCEBEB", "#A32D2D", "#791F1F", "#A32D2D")
)

/**
 * 宿主必须注入的样式表。
 *
 * 微组件在 claude.ai 上是「跑在一个已经装好设计系统的沙箱里」，所以模型可以放心写
 * `class="c-teal"`、`class="t"`、`var(--color-text-primary)`、裸 `<button>`。
 * 我们这边如果不注入，这些全部失效：色阶回落成黑色矩形、文字看不见、表单控件变成
 * 浏览器默认样式 —— 真机截图里那张「黑块架构图」就是这么来的。
 *
 * 深色值逐字对齐 pi-generative-ui；浅色值按同一份规范推导（见 RAMPS_LIGHT）。
 */
private fun widgetTokens(dark: Boolean): String {
    val ramps = if (dark) RAMPS_DARK else RAMPS_LIGHT
    val vars = if (dark) {
        """
        --p: #e0e0e0; --s: #a0a0a0; --t: #b4b2a9; --bg2: #2a2a2a; --b: #404040;
        --color-text-primary: #e0e0e0;
        --color-text-secondary: #a0a0a0;
        --color-text-tertiary: #707070;
        --color-text-info: #85B7EB;
        --color-text-danger: #F09595;
        --color-text-success: #97C459;
        --color-text-warning: #EF9F27;
        --color-background-primary: #1a1a1a;
        --color-background-secondary: #2a2a2a;
        --color-background-tertiary: #111111;
        --color-background-info: #0C447C;
        --color-background-danger: #791F1F;
        --color-background-success: #27500A;
        --color-background-warning: #633806;
        --color-border-primary: rgba(255,255,255,0.4);
        --color-border-secondary: rgba(255,255,255,0.3);
        --color-border-tertiary: rgba(255,255,255,0.15);
        --color-border-info: #85B7EB;
        --color-border-danger: #F09595;
        --color-border-success: #97C459;
        --color-border-warning: #EF9F27;
        """.trimIndent()
    } else {
        """
        --p: #1F2328; --s: #57606A; --t: #8C959F; --bg2: #F6F8FA; --b: #D0D7DE;
        --color-text-primary: #1F2328;
        --color-text-secondary: #57606A;
        --color-text-tertiary: #8C959F;
        --color-text-info: #185FA5;
        --color-text-danger: #A32D2D;
        --color-text-success: #3B6D11;
        --color-text-warning: #854F0B;
        --color-background-primary: #FFFFFF;
        --color-background-secondary: #F6F8FA;
        --color-background-tertiary: #FFFFFF;
        --color-background-info: #E6F1FB;
        --color-background-danger: #FCEBEB;
        --color-background-success: #EAF3DE;
        --color-background-warning: #FAEEDA;
        --color-border-primary: rgba(0,0,0,0.4);
        --color-border-secondary: rgba(0,0,0,0.3);
        --color-border-tertiary: rgba(0,0,0,0.15);
        --color-border-info: #185FA5;
        --color-border-danger: #A32D2D;
        --color-border-success: #3B6D11;
        --color-border-warning: #854F0B;
        """.trimIndent()
    }

    val rampCss = ramps.joinToString("\n") { r ->
        """
        svg .c-${r.cls} > rect, svg .c-${r.cls} > circle, svg .c-${r.cls} > ellipse,
        svg rect.c-${r.cls}, svg circle.c-${r.cls}, svg ellipse.c-${r.cls} { fill: ${r.fill}; stroke: ${r.stroke}; stroke-width: 1; }
        svg .c-${r.cls} > .th, svg .c-${r.cls} > .t { fill: ${r.title}; }
        svg .c-${r.cls} > .ts { fill: ${r.subtitle}; }
        """.trimIndent()
    }

    return """
        :root {
          $vars
          --font-sans: system-ui, -apple-system, sans-serif;
          --font-serif: Georgia, serif;
          --font-mono: ui-monospace, monospace;
          --border-radius-md: 8px;
          --border-radius-lg: 12px;
          --border-radius-xl: 16px;
        }

        svg .t  { font-family: var(--font-sans); font-size: 14px; fill: var(--p); }
        svg .ts { font-family: var(--font-sans); font-size: 12px; fill: var(--s); }
        svg .th { font-family: var(--font-sans); font-size: 14px; font-weight: 500; fill: var(--p); }
        svg .box { fill: var(--bg2); stroke: var(--b); }
        svg .node { cursor: pointer; }
        svg .node:hover { opacity: 0.8; }
        svg .arr { stroke: var(--t); stroke-width: 1.5; fill: none; }
        svg .leader { stroke: var(--t); stroke-width: 0.5; stroke-dasharray: 4 3; fill: none; }

        $rampCss

        button {
          background: transparent;
          border: 0.5px solid var(--color-border-secondary);
          border-radius: var(--border-radius-md);
          color: var(--color-text-primary);
          padding: 6px 14px;
          font-size: 14px;
          cursor: pointer;
          font-family: var(--font-sans);
        }
        button:hover { background: var(--color-background-secondary); }
        button:active { transform: scale(0.98); }

        input[type="range"] {
          -webkit-appearance: none;
          height: 4px;
          background: var(--color-border-secondary);
          border-radius: 2px;
          outline: none;
        }
        input[type="range"]::-webkit-slider-thumb {
          -webkit-appearance: none;
          width: 18px;
          height: 18px;
          border-radius: 50%;
          background: var(--color-text-primary);
          cursor: pointer;
        }

        input[type="text"], input[type="number"], textarea, select {
          height: 36px;
          background: var(--color-background-primary);
          border: 0.5px solid var(--color-border-tertiary);
          border-radius: var(--border-radius-md);
          color: var(--color-text-primary);
          padding: 0 10px;
          font-size: 14px;
          font-family: var(--font-sans);
          outline: none;
        }
        input[type="text"]:focus, input[type="number"]:focus, textarea:focus, select:focus {
          border-color: var(--color-border-primary);
        }
    """.trimIndent()
}

/**
 * 把 `widget_code` 包成可加载的完整文档。
 *
 * `widget_code` 按插件约定是**片段**（没有 DOCTYPE/html/head/body），所以这里补
 * viewport、基础样式、错误哨兵与 `#root` 容器。
 *
 * **两种模式都用「声明的桌面宽度」布局，再整体等比缩放**，差别只在是否允许滚动。
 * 这一条被真机反复推翻过两次，最终定论是：
 *
 * - **内嵌（`fullscreen = false`）**：一张**看全局的缩略图**。超长图靠高度夹紧保护。
 * - **全屏（`fullscreen = true`）**：同一布局占满整屏，可滚、可双指放大看细节。
 *
 * 为什么不让内容回流到手机宽度：模型常用横排多栏（主卡 + 24 小时侧栏）。回流到 360dp 时
 * 侧栏会被压成一条竖排文字，**同时内容高度膨胀到声明值的两倍以上** —— 于是无论给多高的框
 * 都会裁掉一部分（真机上就是「只显示一半」）。而按声明宽度布局时，`payload.height` 就是
 * 模型自己在该宽度下排出来的高度，等比缩放后整张图自然完整可见。
 *
 * 缩放的关键是 WebView 的 `loadWithOverviewMode = true`：设为 false 时它按声明宽度布局
 * 却**不缩出去**，真机表现就是只看到左边一半。
 *
 * 滚动：内嵌必须 `overflow: hidden`（纵向手势要还给列表），全屏必须 `auto`
 * —— 早期两处都写死 `hidden`，导致全屏页超出视口的内容**既不能滚也不能缩**。
 *
 * `#root` 容器有两个用途：一是归一化结构（内嵌预览与全屏页共用同一份 DOM 形状），
 * 二是让宿主能用一个固定表达式判断「页面是不是白板」并测量实际内容高度。
 *
 * 脚本顺序不需要手工调度：这段 HTML 是**整篇文档加载**的，浏览器解析时会按文档顺序
 * 同步执行 `<script>`，普通外链脚本（无 async/defer）还会阻塞解析直到下载执行完毕 ——
 * 所以 `Chart` 一定在内联脚本之前就位。参考实现里那套 `runScripts()` 顺序调度
 * 是为「流式追加片段」准备的，这里用不着。
 */
fun buildWidgetHtml(
    payload: WidgetPayload,
    backgroundHex: String,
    foregroundHex: String,
    fullscreen: Boolean = false,
    /** 当前是深色主题吗：决定注入哪一套设计令牌（色阶 / CSS 变量）。 */
    dark: Boolean = false,
    /**
     * 全屏态的缩放比：把 `payload.width` 的布局缩进屏幕宽度。
     *
     * 只给 `width=<声明宽度>` 是不够的 —— WebView 会按 900px 布局**但不缩出去**，
     * 结果只看到左边一半（真机表现：右侧「未来 24 小时」整块被切掉）。
     * 必须把初始缩放比显式写进 viewport，让整张图按比例缩进屏宽，细节靠双指放大。
     */
    initialScale: Float? = null
): String {
    val body = if (payload.isSvg) {
        """
        <style>
          #root { display: flex; align-items: center; justify-content: center; min-height: calc(100vh - 16px); }
          #root svg { max-width: 100%; max-height: calc(100vh - 16px); height: auto; width: auto; }
        </style>
        ${payload.code}
        """.trimIndent()
    } else {
        payload.code
    }

    // 两种模式都按**声明的桌面宽度**布局，再整体等比缩放：
    //  - 内嵌 → 一张看全局的缩略图；
    //  - 全屏 → 同一布局，但占满整屏、可滚可双指放大。
    //
    // 不再让内容回流到手机宽度。回流会让横排多栏的微组件（主卡 + 24 小时侧栏）
    // 在 360dp 下被压成一条竖排文字，同时内容高度膨胀到声明值的两倍以上 ——
    // 无论给多高的框都会裁掉一部分。
    val scale = initialScale?.takeIf { it > 0f && it.isFinite() } ?: 1f
    val viewport = "width=${payload.width}, " +
        "initial-scale=$scale, maximum-scale=5.0, user-scalable=yes"
    val overflow = if (fullscreen) "auto" else "hidden"

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="$viewport">
        <script>
        (function () {
          function report(m) { try { console.log('$ERROR_SENTINEL' + m); } catch (e) {} }
          window.addEventListener('error', function (e) {
            var t = e && e.target;
            if (t && t.tagName === 'SCRIPT') { report('script failed: ' + (t.src || '')); return; }
            report((e && e.message) || 'script error');
          }, true);
          window.addEventListener('unhandledrejection', function (e) {
            var r = e && e.reason;
            report('unhandled: ' + ((r && r.message) || r));
          });
        })();
        </script>
        <style>
        ${widgetTokens(dark)}
        </style>
        <style>
          html, body {
            margin: 0; padding: 8px; background: $backgroundHex; color: $foregroundHex;
            -webkit-text-size-adjust: 100%;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
            overflow: $overflow;
          }
          body { min-height: calc(100vh - 16px); box-sizing: border-box; }
          img, svg, canvas, video { max-width: 100%; }
          ::-webkit-scrollbar { width: 0; height: 0; }
        </style>
        </head>
        <body>
        <div id="root">
        $body
        </div>
        </body>
        </html>
    """.trimIndent()
}

/**
 * 内嵌缩略图缓存（key = 内容哈希 + 深浅色）。
 *
 * 内嵌态**不保留活 WebView**：渲染一次、抓成位图、之后只显示这张图。
 * 这样滚动、流式补字、列表回收都不会再引起重排或重绘 —— 位图不会闪。
 * 代价是缩略图不能交互，交互统一放到全屏（那才是「看大图」的地方）。
 */
internal object WidgetThumbs {
    private const val MAX_ENTRIES = 3
    /** 抓图宽度上限：太大吃堆，太小字发虚。 */
    private const val MAX_WIDTH_PX = 1080

    private val entries = object : LinkedHashMap<String, Bitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(key: String): Bitmap? = entries[key]

    @Synchronized
    fun put(key: String, bitmap: Bitmap) {
        entries[key] = bitmap
    }

    /** 把已绘制的 WebView 抓成位图；空白（还没画出来）时返回 null，由调用方重试。 */
    fun capture(view: android.view.View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(raw))
            if (isBlank(raw)) {
                raw.recycle()
                return null
            }
            if (w <= MAX_WIDTH_PX) {
                raw
            } else {
                val outH = (h.toLong() * MAX_WIDTH_PX / w).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(raw, MAX_WIDTH_PX, outH, true)
                raw.recycle()
                scaled
            }
        }.getOrNull()
    }

    /** 抽样一圈像素，全同即视为「还没画出来」。 */
    private fun isBlank(bmp: Bitmap): Boolean {
        val stepX = (bmp.width / 6).coerceAtLeast(1)
        val stepY = (bmp.height / 8).coerceAtLeast(1)
        val first = bmp.getPixel(bmp.width / 2, bmp.height / 2)
        var y = stepY / 2
        while (y < bmp.height) {
            var x = stepX / 2
            while (x < bmp.width) {
                if (bmp.getPixel(x, y) != first) return false
                x += stepX
            }
            y += stepY
        }
        return true
    }
}

/** 本地化的第三方库：命中的 CDN 请求直接由 assets 回吐，弱网/离线也能秒开。 */
private val LOCAL_LIB_ASSETS = mapOf(
    "chart.umd.js" to "libs/chart.umd.js",
    "chart.umd.min.js" to "libs/chart.umd.js",
    "chart.min.js" to "libs/chart.umd.js",
    "chart.js" to "libs/chart.umd.js"
)

/** 只对这几家 CDN 做本地化替换，其余请求照常走网络。 */
private val CDN_HOSTS = setOf(
    "cdnjs.cloudflare.com",
    "cdn.jsdelivr.net",
    "unpkg.com",
    "esm.sh"
)

/**
 * 微组件的 WebView。
 *
 * **内嵌态（`allowVerticalScroll = false`）：永不抢占纵向滚动手势。**
 *
 * 背景：`WebView` 不参与 Compose 的 nested scroll（见 `docs/DESIGN-artifact-viewer.md`），
 * 直接塞进 `LazyColumn` 会把整列滚动抢走。但那条约束针对的是**需要自己纵向滚动的长内容**
 * （docx、代码、长图）；微组件页面已设 `overflow: hidden`，内嵌态根本不需要纵向滚动，
 * 内容装不下就走全屏。
 *
 * 于是规则可以简化成「按主轴归属」：
 *  - `DOWN`：先声明独占，避免列表一开始就把手势抢走（否则滑块拖到一半被打断）；
 *  - 首个明显纵向的 `MOVE`：主动交还，列表继续滚，widget 一动不动；
 *  - `UP` / `CANCEL`：复位。
 *
 * **全屏态（`allowVerticalScroll = true`）：完全不干预**，页面 `overflow: auto` 自己滚。
 * 早期全屏也传了 false，结果内容超出视口时**既不能滚也不能缩**，右边就直接看不到 ——
 * 这也是真机反馈「点全屏后右边内容没显示」的直接原因。
 */
private class WidgetWebView(
    context: Context,
    private val allowVerticalScroll: Boolean
) : WebView(context) {

    private var downX = 0f
    private var downY = 0f
    private var released = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!allowVerticalScroll) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    released = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!released) {
                        val dx = abs(event.x - downX)
                        val dy = abs(event.y - downY)
                        // 纵向占优且超过触摸抖动阈值 → 判定为「用户在滚列表」
                        if (dy > dx && dy > VERTICAL_SLOP_PX) {
                            released = true
                            parent?.requestDisallowInterceptTouchEvent(false)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return super.onTouchEvent(event)
    }

    private companion object {
        /** 8dp @ mdpi 的近似值：转换在 Compose 侧按 density 生成，这里是保守下限。 */
        const val VERTICAL_SLOP_PX = 16f
    }
}

/**
 * 会话内的微组件卡片：标题行（⛶ 进全屏）+ 预览区。
 *
 * 预览区高度**不信任模型声明的 `height`**。声明值是它在**桌面宽度**下的高度（例如
 * 900×880）；手机按 360dp 回流后，同样的内容会变成 1400dp 级高度。按声明比例换算出来的
 * 框必然装不下 —— 真机上看到的就是「只显示一半」。
 *
 * 所以：先用声明宽高比算一个**初始估算**，页面加载完后回填**实测高度**，
 * 夹在 `[180dp, min(0.6×屏高, 480dp)]`：
 *  - 下限保证小卡片不至于扁成一条；
 *  - 上限保证一张图不会把会话撑成好几屏（否则换成「必须滚很久才能越过一个 widget」）。
 *
 * 预览区只吃「主轴为横向 / 点击 / 控件拖拽」的手势，纵向拖拽还给列表；
 * 全屏入口放在标题行右侧，**不做「点预览区就全屏」** —— 那会让页内点击变成切换页面。
 */
@Composable
fun WidgetToolCallCard(
    payload: WidgetPayload,
    isRunning: Boolean,
    onOpen: () -> Unit
) {
    val theme = LocalAppTheme.current

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(1.dp, theme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen() }
                    .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = payload.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (isRunning) "生成中…" else "${payload.width}×${payload.height}",
                    fontSize = 10.5.sp,
                    color = theme.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
                IconButton(onClick = onOpen, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.OpenInFull,
                        contentDescription = "全屏查看",
                        modifier = Modifier.size(15.dp),
                        tint = theme.primary
                    )
                }
            }
            HorizontalDivider(color = theme.outline.copy(alpha = 0.6f))
            // 实测容器宽度 → 等比缩放比。缩略图高度由「实测内容高度 × 缩放比」决定，
            // 不用模型声明的 height（模型经常少报，照它裁就是「底部差一截」）。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val containerWidthDp = maxWidth.value.toInt().coerceAtLeast(1)
                val initialHeight = remember(payload.width, payload.height, containerWidthDp) {
                    previewHeightDp(payload, containerWidthDp)
                }
                val scale = remember(payload.width, containerWidthDp) {
                    widgetFitScale(payload, containerWidthDp)
                }
                if (isRunning) {
                    // 生成中不挂 WebView：生成期列表一直在自动补位，卡片反复进出组合，
                    // WebView 会跟着反复销毁重建。
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(initialHeight.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("生成中…", fontSize = 11.5.sp, color = theme.textMuted)
                    }
                } else {
                    WidgetThumbnail(
                        payload = payload,
                        fitScale = scale,
                        initialHeightDp = initialHeight,
                        onOpen = onOpen
                    )
                }
            }
        }
    }
}

/**
 * 微组件全屏查看页：`Scaffold` + `TopAppBar`，与
 * `docs/DESIGN-artifact-viewer.md` 里文件查看器的容器决策一致（手机全屏页，不用半屏 sheet）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetViewerScreen(
    payload: WidgetPayload,
    onClose: () -> Unit
) {
    val theme = LocalAppTheme.current
    BackHandler { onClose() }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = payload.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = "返回",
                            tint = theme.textPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    Text(
                        text = "${payload.width}×${payload.height}",
                        fontSize = 11.sp,
                        color = theme.textMuted,
                        modifier = Modifier.padding(end = 14.dp)
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.surfaceContainerLow)
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 与内嵌同一套等比缩放：先看全局，再用双指放大看细节
            val containerWidthDp = maxWidth.value.toInt().coerceAtLeast(1)
            val scale = remember(payload.width, containerWidthDp) {
                widgetFitScale(payload, containerWidthDp)
            }
            WidgetCanvas(
                payload = payload,
                scale = scale,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 渲染微组件的 WebView 画布，带三条保险：
 *
 *  1. **失败自动降级**：页面里的 `window.onerror` / `unhandledrejection` / 外链脚本加载失败
 *     都会打上 `__PI_ERR:` 前缀，宿主收到就切源码视图 —— 白屏是不可接受的最低线；
 *  2. **离线可用**：常见 CDN 的 Chart.js 由 `assets/libs/` 回吐，不依赖网络；
 *  3. **离屏快照**：销毁前抓一帧，滑回来先贴图再重建，不闪白。
 *
 * 安全口径：开启 JS（微组件依赖 Canvas / Chart.js），但**不注入任何 JS 桥**、
 * 关闭文件与 content 访问、所有跳转一律交给系统浏览器 —— 页面内容来自模型输出，
 * 不给它任何与应用通信的能力。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createWidgetWebView(
    context: Context,
    allowVerticalScroll: Boolean,
    isDisposed: () -> Boolean,
    onFail: () -> Unit,
    onContentHeightCss: ((Float) -> Unit)? = null
): WidgetWebView = WidgetWebView(context, allowVerticalScroll = allowVerticalScroll).apply {
    layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        // 必须为 true：配合 viewport 里的声明宽度，WebView 会把整页缩放到视口宽度。
        // 设为 false 时它按声明宽度布局却**不缩出去**，真机表现就是只看到左边一半。
        loadWithOverviewMode = true
        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        cacheMode = WebSettings.LOAD_NO_CACHE
        allowFileAccess = false
        allowContentAccess = false
        mediaPlaybackRequiresUserGesture = true
    }
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
            val text = msg?.message() ?: return false
            if (!text.contains(ERROR_SENTINEL)) return false
            onFail()
            return true
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            val url = request?.url?.toString() ?: return false
            // 只接管真正的网页跳转；about:blank（销毁时清理）这类内部导航不能外抛
            if (!url.startsWith("http://") && !url.startsWith("https://")) return false
            // 微组件里的外链一律走系统浏览器，不在卡片里接管导航
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                )
            }
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            val host = url.host?.lowercase() ?: return null
            if (host !in CDN_HOSTS) return null
            val name = url.lastPathSegment?.lowercase() ?: return null
            val asset = LOCAL_LIB_ASSETS[name] ?: return null
            return runCatching {
                WebResourceResponse("application/javascript", "UTF-8", context.assets.open(asset))
            }.getOrNull()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) onFail()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // 只跳过「销毁时清场」的那次加载，用显式标志判断。
            //
            // 不能用 url == "about:blank" 判断：loadDataWithBaseURL(null, …) 加载出来的
            // 页面 URL 本身就是 about:blank，那样会把正常的测量一起跳过。
            if (isDisposed()) return
            // 一次脚本往返同时拿两样东西：
            //  - #root 子节点数 → 白板检测；
            //  - 真实内容高度（CSS px）→ 内嵌缩略图的高度。
            //
            // 为什么必须实测而不是信 payload.height：模型经常少报（真机上声明 900x900、
            // 实际内容却高出几十像素），照声明裁剪就是看到的「底部差一截」。
            view?.evaluateJavascript(
                "(function(){var r=document.getElementById('root');" +
                    "if(!r)return '0|0';" +
                    "var h=Math.max(document.body?document.body.scrollHeight:0," +
                    "r.getBoundingClientRect().height);" +
                    "return r.children.length+'|'+Math.ceil(h);})()"
            ) { raw ->
                val parsed = raw?.trim('"') ?: return@evaluateJavascript
                val parts = parsed.split('|')
                val n = parts.getOrNull(0)?.toIntOrNull() ?: 0
                if (n == 0) {
                    onFail()
                    return@evaluateJavascript
                }
                parts.getOrNull(1)?.toFloatOrNull()
                    ?.takeIf { it > 0f }
                    ?.let { onContentHeightCss?.invoke(it) }
            }
        }
    }
}

/**
 * 全屏查看用的 WebView：活实例、可滚、可双指放大。
 *
 * 与内嵌的区别只有两点：不拦纵向手势、页面 overflow: auto。
 */
@Composable
private fun WidgetCanvas(
    payload: WidgetPayload,
    scale: Float,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current

    var retry by remember(payload.code) { mutableStateOf(0) }
    var failed by remember(payload.code, retry) { mutableStateOf(false) }

    val html = remember(payload.code, theme.isDark, payload.isSvg, scale) {
        buildWidgetHtml(
            payload = payload,
            backgroundHex = if (theme.isDark) "#0D1117" else "#FFFFFF",
            foregroundHex = if (theme.isDark) "#F0F6FC" else "#1F2328",
            fullscreen = true,
            dark = theme.isDark,
            initialScale = scale
        )
    }

    if (failed) {
        WidgetSourceFallback(
            payload = payload,
            modifier = modifier,
            onRetry = {
                failed = false
                retry++
            }
        )
        return
    }

    val holder = remember(payload.code, retry) {
        java.util.concurrent.atomic.AtomicReference<WebView?>(null)
    }
    val loadedKey = remember(payload.code, retry) {
        java.util.concurrent.atomic.AtomicReference<String?>(null)
    }
    val disposed = remember(payload.code, retry) {
        java.util.concurrent.atomic.AtomicBoolean(false)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            createWidgetWebView(
                context = ctx,
                allowVerticalScroll = true,
                isDisposed = { disposed.get() },
                onFail = { failed = true }
            ).also { holder.set(it) }
        },
        update = { webView ->
            val key = html.hashCode().toString() + "#" + retry
            if (loadedKey.getAndSet(key) != key) {
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    )

    DisposableEffect(payload.code, retry) {
        onDispose {
            disposed.set(true)
            holder.get()?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
            holder.set(null)
        }
    }
}

/**
 * 会话内的内嵌缩略图。
 *
 * **渲染一次 → 抓成位图 → 之后只显示静态图**。这是把「频闪」从结构上消灭掉：
 * 位图不参与 WebView 的布局与重绘，流式补字、列表滚动、item 回收都不会让它动一下；
 * 每张 widget 也不再常驻一个 Chromium 实例。
 *
 * 高度用**实测内容高度 × 缩放比**，不信模型声明的 height —— 模型经常少报，
 * 照声明裁剪就是真机上看到的「底部差一截」。
 *
 * 抓图失败（少数机型首帧拿不到像素）时退回活 WebView，行为等同旧版，不会白屏。
 */
@Composable
private fun WidgetThumbnail(
    payload: WidgetPayload,
    fitScale: Float,
    initialHeightDp: Int,
    onOpen: () -> Unit
) {
    val theme = LocalAppTheme.current
    val thumbKey = remember(payload.code, theme.isDark) {
        "thumb:${payload.code.hashCode()}:${if (theme.isDark) "d" else "l"}"
    }
    var bitmap by remember(thumbKey) { mutableStateOf(WidgetThumbs.get(thumbKey)) }
    var measuredCss by remember(thumbKey) { mutableStateOf(0f) }
    var failed by remember(thumbKey) { mutableStateOf(false) }
    var retry by remember(thumbKey) { mutableStateOf(0) }

    val heightDp = remember(measuredCss, fitScale, initialHeightDp) {
        if (measuredCss > 0f) {
            (measuredCss * fitScale).roundToInt().coerceIn(PREVIEW_MIN_DP, PREVIEW_MAX_DP)
        } else {
            initialHeightDp
        }
    }

    val ready = bitmap
    if (ready != null) {
        Image(
            bitmap = ready.asImageBitmap(),
            contentDescription = payload.title,
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(ready.width.toFloat() / ready.height.toFloat())
                .clickable { onOpen() }
        )
        return
    }

    if (failed) {
        WidgetSourceFallback(
            payload = payload,
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp),
            onRetry = {
                failed = false
                retry++
            }
        )
        return
    }

    val disposed = remember(thumbKey, retry) { java.util.concurrent.atomic.AtomicBoolean(false) }
    val webRef = remember(thumbKey, retry) { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }
    val loadedKey = remember(thumbKey, retry) { java.util.concurrent.atomic.AtomicReference<String?>(null) }

    val html = remember(payload.code, theme.isDark, payload.isSvg, fitScale) {
        buildWidgetHtml(
            payload = payload,
            backgroundHex = if (theme.isDark) "#0D1117" else "#FFFFFF",
            foregroundHex = if (theme.isDark) "#F0F6FC" else "#1F2328",
            fullscreen = false,
            dark = theme.isDark,
            initialScale = fitScale
        )
    }

    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(heightDp.dp),
        factory = { ctx ->
            createWidgetWebView(
                context = ctx,
                allowVerticalScroll = false,
                isDisposed = { disposed.get() },
                onFail = { failed = true },
                onContentHeightCss = { h -> if (measuredCss <= 0f) measuredCss = h }
            ).also { webRef.set(it) }
        },
        update = { webView ->
            val key = html.hashCode().toString() + "#" + retry
            if (loadedKey.getAndSet(key) != key) {
                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    )

    // 抓图：必须先拿到实测高度，否则抓下来的是「按估计高度裁剪」的残缺版，
    // 缓存住就再也不会纠正。实测高度到达会让 heightDp 变化 → 本 effect 重启 → 重新抓。
    LaunchedEffect(thumbKey, heightDp, retry) {
        if (measuredCss <= 0f) return@LaunchedEffect
        repeat(4) {
            delay(if (it == 0) 320L else 220L)
            val wv = webRef.get() ?: return@LaunchedEffect
            val shot = WidgetThumbs.capture(wv)
            if (shot != null) {
                WidgetThumbs.put(thumbKey, shot)
                bitmap = shot
                return@LaunchedEffect
            }
        }
    }

    DisposableEffect(thumbKey, retry) {
        onDispose {
            disposed.set(true)
            webRef.get()?.let { wv ->
                runCatching {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    wv.destroy()
                }
            }
            webRef.set(null)
        }
    }
}

/**
 * 渲染失败时的降级视图：源码块 + 重试。
 *
 * 不给说明性副标题 —— 失败原因对用户没有可操作性，能做的只有「以另一种形式把内容给他」
 * 和「再试一次」（说明性标签的口径见 `docs/DESIGN-labels.md`）。
 */
@Composable
private fun WidgetSourceFallback(
    payload: WidgetPayload,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit
) {
    val theme = LocalAppTheme.current
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(theme.surfaceContainer)
                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "无法渲染，已显示源码",
                fontSize = 11.sp,
                color = theme.textMuted,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "重试",
                    tint = theme.primary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(rememberScrollState())
        ) {
            Text(
                text = payload.code,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = theme.textSecondary,
                softWrap = false,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}
