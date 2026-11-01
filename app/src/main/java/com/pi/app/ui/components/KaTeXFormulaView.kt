package com.pi.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * KaTeX 公式视图：把一条 LaTeX 交给内置的 KaTeX（`assets/libs/katex/`）真排版。
 *
 * 为什么是 WebView 而不是纯 Compose 排版：
 *  - 上游 pi-web 用的就是 `katex` + `remark-math`，这里必须视觉 1:1；
 *  - 公式里常带中文（`\text{总放线长度 }`），KaTeX 能退回系统 CJK 字体，
 *    而 JLaTeXMath 这类纯原生引擎会直接画出豆腐块。
 *
 * 三条口径：
 *  1. **离线**：CSS/JS/字体全部由 `shouldInterceptRequest` 从 assets 回吐，
 *     页面里没有任何真实网络请求，也不允许导航；
 *  2. **无桥**：不开 `addJavascriptInterface`，页面拿不到任何与应用通信的能力；
 *  3. **失败必降级**：KaTeX 缺失 / 脚本报错 / 主文档加载失败一律回调 [fallback]，
 *     由调用方渲染原生近似版本，绝不留白板。
 */
@Composable
internal fun KaTeXFormulaView(
    latex: String,
    displayMode: Boolean,
    fontSizeSp: Float,
    textColor: Color,
    modifier: Modifier = Modifier,
    fallback: @Composable () -> Unit
) {
    val colorHex = remember(textColor) { cssHex(textColor) }

    // 流式输出时 latex 每个 token 都在变，直接跟着重载 WebView 会把主线程打满。
    // 内容稳定 140ms 后才真正排版（与流式 token 间隔同量级，肉眼看不出延迟）。
    var stableLatex by remember { mutableStateOf(latex) }
    LaunchedEffect(latex) {
        if (stableLatex != latex) {
            delay(140)
            stableLatex = latex
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val widthDp = maxWidth.value.toInt().coerceAtLeast(1)
        // 把所有排版相关的输入压成一个 key：尺寸/失败态/HTML 只有在「公式或排版环境变了」时
        // 才该重置，旋屏（widthDp 参与）也要重算一次缩放，否则横向被裁。
        val renderKey = "$stableLatex|$colorHex|$fontSizeSp|$displayMode|$widthDp"
        // 尺寸未知（0f）= 正在排版，此时叠一层原生近似版顶着，避免卡片高度跳一下就空。
        var heightDp by remember(renderKey) { mutableStateOf(0f) }
        var failed by remember(renderKey) { mutableStateOf(false) }

        val html = remember(renderKey) {
            KaTeXPageTemplate.build(
                latex = stableLatex,
                displayMode = displayMode,
                fontSizePx = fontSizeSp,
                colorHex = colorHex
            ) + "\n<!--viewport:$widthDp-->"
        }

        if (failed) {
            fallback()
        } else {
            Box(modifier = Modifier.fillMaxWidth()) {
                if (heightDp <= 0f) {
                    fallback()
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (heightDp > 0f) heightDp.dp else 1.dp),
                    factory = { ctx ->
                        createFormulaWebView(
                            context = ctx,
                            html = html,
                            onMeasured = { measured ->
                                if (measured > 0f) {
                                    heightDp = measured
                                    failed = false
                                }
                            },
                            onFail = { failed = true }
                        )
                    },
                    onRelease = { view ->
                        runCatching {
                            view.stopLoading()
                            // 先换上默认回调：销毁过程中的报错不能再打回 Compose 状态
                            view.webViewClient = WebViewClient()
                            view.destroy()
                        }
                    },
                    update = { view ->
                        val loaded = view.tag as? String
                        if (loaded != html) {
                            view.tag = html
                            view.loadDataWithBaseURL(
                                "https://${KaTeXPageTemplate.ASSET_HOST}/",
                                html,
                                "text/html",
                                "utf-8",
                                null
                            )
                        }
                    }
                )
            }
        }
    }
}

private fun cssHex(color: Color): String {
    val argb = color.toArgb()
    return "#%06X".format(0xFFFFFF and argb)
}

/** 让页面在当前 viewport 下重新量一次，并回报 `ok|width|height`。 */
private const val FIT_SCRIPT =
    "(function(){if(!window.__PI_MATH_FIT)return '0|0|0';" +
        "try{window.__PI_MATH_FIT();}catch(e){return '0|0|0';}" +
        "return window.__PI_MATH_DIMS();})()"

/** 解析 `ok|w|h`；`ok != 1` 或尺寸非法返回 null。 */
private fun parseDims(raw: String?): Float? {
    val text = raw?.trim()?.trim('"') ?: return null
    val parts = text.split('|')
    if (parts.size < 3) return null
    if (parts[0].trim() != "1") return null
    val height = parts[2].trim().toFloatOrNull() ?: return null
    return if (height > 0f) height else null
}

@SuppressLint("SetJavaScriptEnabled")
private fun createFormulaWebView(
    context: Context,
    html: String,
    onMeasured: (Float) -> Unit,
    onFail: () -> Unit
): WebView {
    val web = WebView(context)
    var settled = false
    web.tag = html

    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = false
        // viewport 用 device-width，1 CSS px == 1 dp；缩放交给页面里的 transform 自己算。
        useWideViewPort = true
        loadWithOverviewMode = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        textZoom = 100
        cacheMode = WebSettings.LOAD_NO_CACHE
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
        mediaPlaybackRequiresUserGesture = true
    }
    web.setBackgroundColor(AndroidColor.TRANSPARENT)
    web.isVerticalScrollBarEnabled = false
    web.isHorizontalScrollBarEnabled = false
    web.overScrollMode = View.OVER_SCROLL_NEVER
    web.setPadding(0, 0, 0, 0)

    fun report(attempt: Int) {
        // 卡片滑出屏幕后 WebView 可能已经 destroy，这里一律用 runCatching 包住：
        // 已销毁实例上调 evaluateJavascript 会抛 IllegalStateException。
        runCatching {
            web.evaluateJavascript(FIT_SCRIPT) { raw ->
                val height = parseDims(raw)
                if (height != null) {
                    settled = true
                    onMeasured(height)
                    // 字体是异步加载的：首批字形落盘后高度会差几个像素，再校准一次。
                    if (attempt == 0) {
                        runCatching { web.postDelayed({ report(1) }, 120) }
                    }
                } else if (!settled) {
                    // 量不出尺寸 = KaTeX 没跑起来（脚本缺失/报错），交给原生兜底。
                    if (attempt >= 1) onFail() else runCatching { web.postDelayed({ report(1) }, 120) }
                }
            }
        }
    }

    web.webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? = openKatexAsset(context, request?.url)

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean {
            // 只放行假域名（由 shouldInterceptRequest 回吐 assets）的导航；
            // 任何外链都不开，页面本身也不该产生跳转。
            val url = request?.url?.toString() ?: return true
            return !url.startsWith("https://${KaTeXPageTemplate.ASSET_HOST}/")
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isMainFrame == true) onFail()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            view ?: return
            report(0)
        }
    }

    web.loadDataWithBaseURL(
        "https://${KaTeXPageTemplate.ASSET_HOST}/",
        html,
        "text/html",
        "utf-8",
        null
    )
    return web
}

/**
 * 把 `https://pi-katex.local/…` 映射到 `assets/libs/katex/…`。
 *
 * 用假域名而不是 `file:///android_asset/`：后者需要打开 `allowFileAccess`，
 * 页面内容来自模型输出，不给它任何读文件的能力。
 */
private fun openKatexAsset(context: Context, url: Uri?): WebResourceResponse? {
    val target = url ?: return null
    val host = target.host?.lowercase() ?: return null
    if (host != KaTeXPageTemplate.ASSET_HOST) return null
    val path = target.path ?: return null
    if (!path.startsWith("/")) return null
    val mime = when {
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        else -> return null
    }
    return runCatching {
        WebResourceResponse(mime, null, context.assets.open("libs/katex$path"))
    }.getOrNull()
}
