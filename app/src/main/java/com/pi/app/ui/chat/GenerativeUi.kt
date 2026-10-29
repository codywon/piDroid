package com.pi.app.ui.chat

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.pi.app.data.model.ToolCallInfo
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 生成式 UI（`pi-generative-ui` 插件）承载层。
 *
 * 插件侧（pi-generative-ui 0.3.x，`EXTENSIONS/generative-ui/index.ts`）注册两个工具：
 *  - `visualize_read_me(modules)`：模型自用的设计指南，插件明确要求「不要向用户提及」；
 *  - `show_widget(title, widget_code, width, height, floating)`：把一段 HTML 片段或裸 SVG
 *    渲染出来，`widget_code` 是唯一的内容载体，`details` 里只回 `{title,width,height,isSVG}`。
 *
 * 桌面端 pi 用 Glimpse 开一个**独立原生窗口**；`pi-web` 里没有这条链路
 * （它那份 `widgets` 是 TUI 状态栏微件，与本工具无关），所以这里没有可 1:1 复刻的容器形态，
 * 按 `docs/DESIGN-artifact-viewer.md` 已定的口径裁量：**手机用独立全屏页承载**，理由是
 *  1) WebView 不参与 Compose 的 nested scroll，塞进 LazyColumn 会抢走整列滚动手势；
 *  2) `widget_code` 常见 5~50KB，直接铺在气泡里能把一条消息撑成两三屏；
 *  3) 桌面窗口的语义是「另一个可交互表面」，全屏页是手机上最接近的等价物。
 */
private const val SHOW_WIDGET_TOOL = "show_widget"

/** 插件内部的设计指南工具名：聊天流里不展示（插件本身要求对用户不可见）。 */
const val GENERATIVE_UI_READ_ME_TOOL = "visualize_read_me"

/**
 * 一个可渲染的微组件。`width` / `height` 是模型声明的**桌面**尺寸（默认 800×600），
 * 手机端只作提示展示，实际渲染按设备宽度回流（见 [buildWidgetHtml]）。
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
 * 把 `widget_code` 包成可加载的完整文档。
 *
 * `widget_code` 按插件约定是**片段**（没有 DOCTYPE/html/head/body），所以这里补
 * viewport 与基础样式。viewport 用 `width=device-width` 而不是模型声明的桌面宽度：
 * 桌面窗口的等价物在手机上就是手机宽度的窗口，`width:100%` / flex 类的微组件会自然回流；
 * 确实写死宽度的内容仍可双指缩放（`user-scalable=yes`）。
 */
fun buildWidgetHtml(payload: WidgetPayload, backgroundHex: String, foregroundHex: String): String {
    val body = if (payload.isSvg) {
        // 裸 SVG 单独居中，并限制在视口内（等价于桌面窗口里的预览态）
        """
        <style>
          html, body { display: flex; align-items: center; justify-content: center; min-height: 100vh; }
          svg { max-width: 100%; max-height: 100vh; height: auto; width: auto; }
        </style>
        ${payload.code}
        """.trimIndent()
    } else {
        payload.code
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes">
        <style>
          html, body {
            margin: 0; padding: 0; background: $backgroundHex; color: $foregroundHex;
            -webkit-text-size-adjust: 100%;
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
          }
          body { min-height: 100vh; box-sizing: border-box; }
          img, svg, canvas, video { max-width: 100%; }
          ::-webkit-scrollbar { width: 0; height: 0; }
        </style>
        </head>
        <body>
        $body
        </body>
        </html>
    """.trimIndent()
}

/**
 * 聊天流里的一行入口卡：标题 + 状态/尺寸 + 打开。
 *
 * 刻意只保留「一行主体 + 一行状态」，不写任何解释性副标题 —— 见
 * `CHANGELOG 1.7.48 / docs` 里关于「说明性标签过载」的整改口径。
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
        modifier = Modifier.fillMaxWidth().clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 9.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(theme.primary.copy(alpha = 0.12f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.OpenInFull,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = theme.primary
                )
            }
            Spacer(modifier = Modifier.width(9.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = payload.title,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isRunning) "生成中…" else "${payload.width}×${payload.height}",
                    fontSize = 10.5.sp,
                    color = theme.textMuted,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Icon(
                Icons.Default.OpenInFull,
                contentDescription = "全屏查看",
                modifier = Modifier.size(15.dp),
                tint = theme.primary
            )
            Spacer(modifier = Modifier.width(4.dp))
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
        WidgetWebView(
            payload = payload,
            modifier = Modifier.fillMaxSize().padding(innerPadding)
        )
    }
}

/**
 * 微组件 WebView。
 *
 * 安全口径：开启 JS（微组件依赖 Canvas / Chart.js），但**不注入任何 JS 桥**、
 * 关闭文件与 content 访问、所有跳转一律交给系统浏览器 —— 页面内容来自模型输出，
 * 不给它任何与应用通信的能力。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WidgetWebView(payload: WidgetPayload, modifier: Modifier = Modifier) {
    val theme = LocalAppTheme.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val html = remember(payload.code, theme.isDark) {
        buildWidgetHtml(
            payload = payload,
            backgroundHex = if (theme.isDark) "#0D1117" else "#FFFFFF",
            foregroundHex = if (theme.isDark) "#F0F6FC" else "#1F2328"
        )
    }

    val holder = remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = false
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    allowFileAccess = false
                    allowContentAccess = false
                    mediaPlaybackRequiresUserGesture = true
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        // 微组件里的外链一律走系统浏览器，不在卡片里接管导航
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url)
                                )
                            )
                        }
                        return true
                    }
                }
                holder.value = this
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            holder.value?.apply {
                stopLoading()
                loadUrl("about:blank")
                destroy()
            }
            holder.value = null
        }
    }
}
