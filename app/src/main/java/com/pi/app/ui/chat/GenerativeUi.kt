package com.pi.app.ui.chat

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pi.app.data.model.ToolCallInfo
import com.pi.app.ui.theme.LocalAppTheme
import kotlin.math.abs

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
            // 用实测容器宽度算缩放比与缩略图高度：整张图按宽度等比缩进来，
            // 高度 = 声明高度 × 缩放比 —— 看的是全局，不是被裁掉的一段。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val containerWidthDp = maxWidth.value.toInt().coerceAtLeast(1)
                val previewHeight = remember(payload.width, payload.height, containerWidthDp) {
                    previewHeightDp(payload, containerWidthDp)
                }
                val scale = remember(payload.width, containerWidthDp) {
                    widgetFitScale(payload, containerWidthDp)
                }
                when {
                    // 生成中不挂 WebView。
                    //
                    // 生成期间列表一直往下自动补位（回合还在跑），卡片正好在视口边界上反复进出组合，
                    // 而 `LazyColumn` 会 dispose 离开视口的 item —— WebView 于是被反复销毁、重建、
                    // 重新加载页面，用户看到的就是「出图的时候画面一直在闪」。
                    // 等工具调用跑完（isRunning = false）再挂一次，那时列表已经不再自动滚动。
                    isRunning -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("生成中…", fontSize = 11.5.sp, color = theme.textMuted)
                    }

                    else -> WidgetCanvas(
                        payload = payload,
                        fullscreen = false,
                        scale = scale,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(previewHeight.dp)
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
                // 全屏：页面自己滚动，不再拦截纵向手势
                fullscreen = true,
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
@Composable
private fun WidgetCanvas(
    payload: WidgetPayload,
    fullscreen: Boolean,
    /** 等比缩放比：由调用方用**实测容器宽度**算好传进来（见 [widgetFitScale]）。 */
    scale: Float,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current

    // 重试计数参与 key：点「重试」时重建 WebView，而不是复用已经报错的那个实例。
    var retry by remember(payload.code) { mutableStateOf(0) }
    var failed by remember(payload.code, retry) { mutableStateOf(false) }

    val html = remember(payload.code, theme.isDark, payload.isSvg, fullscreen, scale) {
        buildWidgetHtml(
            payload = payload,
            backgroundHex = if (theme.isDark) "#0D1117" else "#FFFFFF",
            foregroundHex = if (theme.isDark) "#F0F6FC" else "#1F2328",
            fullscreen = fullscreen,
            initialScale = scale
        )
    }

    val snapshotKey = remember(payload.code, theme.isDark) {
        "widget:${payload.code.hashCode()}:${if (theme.isDark) "d" else "l"}"
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

    // 用��可观察的持有者：之前写成 `mutableStateOf` 并在 factory 里回写，会让每次
    // WebView 创建额外触发一轮重组，正好撞在页面加载的首帧上（闪动的直接来源之一）。
    val holder = remember(snapshotKey, retry) { java.util.concurrent.atomic.AtomicReference<WebView?>(null) }
    // `AndroidView.update` 每次重组都会跑，而重载页面会丢掉页内交互态并重新拉 CDN。
    // 用非可观察的持有者记一份「已加载内容指纹」，只在内容真的换了时才重新加载。
    val loadedKey = remember(snapshotKey, retry) { java.util.concurrent.atomic.AtomicReference<String?>(null) }
    // 高度只测一次：反复回填会让 WebView 视口高度变化，触发微组件自身的响应式 JS 重排（振荡）
    // 销毁标志：用于区分「页面加载完成」与「销毁时清场加载」
    val disposed = remember(snapshotKey, retry) { java.util.concurrent.atomic.AtomicBoolean(false) }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WidgetWebView(ctx, allowVerticalScroll = fullscreen).apply {
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
                    // 页面自带深浅色背景，画布留透明才能在加载期间透出快照
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                            val text = msg?.message() ?: return false
                            if (!text.contains(ERROR_SENTINEL)) return false
                            failed = true
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            // 只接管真正的网页跳转；`about:blank`（销毁时清理）这类内部导航不能外抛
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
                                WebResourceResponse(
                                    "application/javascript",
                                    "UTF-8",
                                    context.assets.open(asset)
                                )
                            }.getOrNull()
                        }

                        override fun onReceivedError(
                            view: WebView?,
                            request: WebResourceRequest?,
                            error: WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true) failed = true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            // 只跳过「销毁时清场」的那次加载，用显式标志判断。
                            //
                            // 不能用 `url == "about:blank"` 判断：`loadDataWithBaseURL(null, …)`
                            // 加载出来的页面 URL **本身就是 about:blank**，那样会把正常的
                            // 白板检测与高度测量一起跳过（真机上表现为预览区高度永远停在估算值）。
                            if (disposed.get()) return
                            // 白板检测：`#root` 里一个节点都没有就当渲染失败。
                            //
                            // 高度不再测量：页面按声明的桌面宽度布局后，`payload.height` 就是可信的
                            // 内容高度（模型自己在该宽度下排出来的），等比缩放即可，
                            // 不需要再靠 `getBoundingClientRect` 回填 —— 那条路会随 item 回收反复
                            // 改视口高度，正是抖动的来源。
                            view?.evaluateJavascript(
                                "(function(){var r=document.getElementById('root');" +
                                    "return r ? r.children.length : 0;})()"
                            ) { raw ->
                                val n = raw?.trim('"')?.toIntOrNull() ?: 0
                                if (n == 0) failed = true
                            }
                        }
                    }
                    holder.set(this)
                }
            },
            update = { webView ->
                val key = html.hashCode().toString() + "#" + retry
                if (loadedKey.getAndSet(key) != key) {
                    webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                }
            }
        )
    }

    DisposableEffect(snapshotKey, retry) {
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
