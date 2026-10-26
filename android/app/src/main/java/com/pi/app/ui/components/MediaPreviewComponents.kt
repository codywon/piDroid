package com.pi.app.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.LruCache
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaKind
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 提供当前网关连接上下文，以便在工具卡片或 Markdown 中直接按路径加载远程工作区图片/PDF/SVG
 */
data class MediaGatewayConfig(
    val serverUrl: String = "",
    val authToken: String = "",
    val cwd: String = ""
)

val LocalMediaGatewayConfig = compositionLocalOf { MediaGatewayConfig() }

private object MediaByteCache {
    private val byteCache = LruCache<String, ByteArray>(32)
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun get(key: String): ByteArray? = byteCache.get(key)
    fun put(key: String, bytes: ByteArray) {
        if (bytes.isNotEmpty() && bytes.size <= 15 * 1024 * 1024) {
            byteCache.put(key, bytes)
        }
    }
}

/**
 * 解析并加载 MediaAttachment 的原始字节（支持 Base64、data URI、HTTP URL、以及远程工作区相对/绝对文件路径）
 */
suspend fun resolveMediaBytes(
    attachment: MediaAttachment,
    gateway: MediaGatewayConfig
): ByteArray? = withContext(Dispatchers.IO) {
    val cacheKey = buildString {
        append(attachment.id)
        append("|")
        append(attachment.filePath ?: "")
        append("|")
        append(attachment.dataBase64?.take(64) ?: "")
    }
    MediaByteCache.get(cacheKey)?.let { return@withContext it }

    // 1. Base64 数据直接解码
    val b64 = attachment.dataBase64?.trim()
    if (!b64.isNullOrBlank()) {
        try {
            val cleanB64 = b64.substringAfter("base64,", b64).replace("\n", "").replace("\r", "").trim()
            val decoded = Base64.decode(cleanB64, Base64.DEFAULT)
            if (decoded != null && decoded.isNotEmpty()) {
                MediaByteCache.put(cacheKey, decoded)
                return@withContext decoded
            }
        } catch (_: Exception) {}
    }

    // 2. data:... URI 解码
    val pathOrUrl = attachment.filePath?.trim() ?: ""
    if (pathOrUrl.startsWith("data:")) {
        try {
            if (pathOrUrl.contains(";base64,")) {
                val clean = pathOrUrl.substringAfter(";base64,").trim()
                val decoded = Base64.decode(clean, Base64.DEFAULT)
                if (decoded != null && decoded.isNotEmpty()) {
                    MediaByteCache.put(cacheKey, decoded)
                    return@withContext decoded
                }
            } else {
                val rawPayload = java.net.URLDecoder.decode(pathOrUrl.substringAfter(","), "UTF-8")
                val bytes = rawPayload.toByteArray(Charsets.UTF_8)
                MediaByteCache.put(cacheKey, bytes)
                return@withContext bytes
            }
        } catch (_: Exception) {}
    }

    // 3. 远程 URL 或工作区文件路径通过网关拉取
    if (pathOrUrl.isNotBlank()) {
        val candidateUrls = mutableListOf<String>()
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            candidateUrls.add(pathOrUrl)
        } else if (gateway.serverUrl.isNotBlank()) {
            val base = gateway.serverUrl.trim().trimEnd('/')
            val encPath = URLEncoder.encode(pathOrUrl, "UTF-8")
            val encCwd = URLEncoder.encode(gateway.cwd, "UTF-8")
            candidateUrls.add("$base/api/files/raw?path=$encPath&cwd=$encCwd")
            candidateUrls.add("$base/api/files?path=$encPath&cwd=$encCwd")
            if (!pathOrUrl.startsWith("/")) {
                candidateUrls.add("$base/$pathOrUrl")
            }
        }

        for (url in candidateUrls) {
            try {
                val reqBuilder = Request.Builder().url(url).get()
                if (gateway.authToken.isNotBlank()) {
                    reqBuilder.header("Authorization", "Bearer ${gateway.authToken.trim()}")
                }
                MediaByteCache.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val bytes = resp.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            MediaByteCache.put(cacheKey, bytes)
                            return@withContext bytes
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    null
}

/**
 * 多媒体附件画廊列表：渲染工具输出或消息中包含的所有图片、SVG、PDF、脑图附件
 */
@Composable
fun MediaAttachmentGallery(
    mediaList: List<MediaAttachment>,
    modifier: Modifier = Modifier,
    inToolCard: Boolean = false
) {
    if (mediaList.isEmpty()) return
    var activeFullscreenItem by remember { mutableStateOf<MediaAttachment?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        mediaList.forEach { item ->
            MediaAttachmentCard(
                attachment = item,
                inToolCard = inToolCard,
                onOpenFullscreen = { activeFullscreenItem = item }
            )
        }
    }

    activeFullscreenItem?.let { item ->
        FullscreenMediaLightboxDialog(
            attachment = item,
            onDismiss = { activeFullscreenItem = null }
        )
    }
}

@Composable
fun MediaAttachmentCard(
    attachment: MediaAttachment,
    inToolCard: Boolean = false,
    onOpenFullscreen: () -> Unit
) {
    when (attachment.kind) {
        MediaKind.IMAGE -> ImageThumbnailCard(
            attachment = attachment,
            inToolCard = inToolCard,
            onClick = onOpenFullscreen
        )
        MediaKind.SVG -> SvgPreviewCard(
            attachment = attachment,
            onOpenFullscreen = onOpenFullscreen
        )
        MediaKind.PDF -> PdfThumbnailCard(
            attachment = attachment,
            onOpenFullscreen = onOpenFullscreen
        )
        MediaKind.DIAGRAM -> {
            MermaidDiagramCard(
                code = attachment.rawContent ?: "",
                language = "mermaid"
            )
        }
    }
}

/**
 * 1:1 复刻 pi-web 的图片缩略图渲染组件：
 * - 在 read xxx.png 工具卡片内：直接居中展示高清缩略图，带悬停角标，点击全屏查看并支持双指缩放
 * - 在普通消息内：带精致边框与文件名标题栏
 */
@Composable
private fun ImageThumbnailCard(
    attachment: MediaAttachment,
    inToolCard: Boolean,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    val gateway = LocalMediaGatewayConfig.current
    var imageBitmap by remember(attachment.id, attachment.dataBase64, attachment.filePath, gateway) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var resolutionLabel by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }

    LaunchedEffect(attachment.id, attachment.dataBase64, attachment.filePath, gateway) {
        isLoading = true
        loadError = false
        val bytes = resolveMediaBytes(attachment, gateway)
        if (bytes != null && bytes.isNotEmpty()) {
            val decoded = withContext(Dispatchers.IO) {
                try {
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    bmp?.let { it.asImageBitmap() to "${it.width}×${it.height}" }
                } catch (_: Exception) {
                    null
                }
            }
            if (decoded != null) {
                imageBitmap = decoded.first
                resolutionLabel = decoded.second
            } else {
                loadError = true
            }
        } else {
            loadError = true
        }
        isLoading = false
    }

    val containerBg = if (inToolCard) {
        Color.Transparent
    } else {
        theme.surfaceContainerLow
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerBg,
        border = if (inToolCard) null else BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = imageBitmap != null) { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (inToolCard) 4.dp else 8.dp),
            contentAlignment = Alignment.Center
        ) {
            when {
                imageBitmap != null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = imageBitmap!!,
                            contentDescription = attachment.title.ifBlank { "图片预览" },
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 90.dp, max = 260.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.35f)), RoundedCornerShape(8.dp))
                        )
                    }
                }
                isLoading -> {
                    Row(
                        modifier = Modifier.padding(vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = theme.primary
                        )
                        Text(
                            text = "正在加载图像 ${attachment.title}...",
                            fontSize = 11.5.sp,
                            color = theme.textSecondary
                        )
                    }
                }
                loadError -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.surfaceContainer.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = theme.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = attachment.filePath ?: attachment.title.ifBlank { "图像文件" },
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * SVG 矢量图内联预览卡片 + 源码切换 + 全屏查看
 */
@Composable
fun SvgPreviewCard(
    attachment: MediaAttachment,
    onOpenFullscreen: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val gateway = LocalMediaGatewayConfig.current
    var svgMarkup by remember(attachment.id, attachment.rawContent, attachment.dataBase64, attachment.filePath) {
        mutableStateOf(attachment.rawContent ?: "")
    }
    var showRawCode by remember { mutableStateOf(false) }

    LaunchedEffect(attachment.id, attachment.rawContent, attachment.dataBase64, attachment.filePath, gateway) {
        if (svgMarkup.isBlank()) {
            val bytes = resolveMediaBytes(attachment, gateway)
            if (bytes != null && bytes.isNotEmpty()) {
                svgMarkup = String(bytes, Charsets.UTF_8)
            }
        }
    }

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
                    .background(theme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Polyline,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = attachment.title.ifBlank { "SVG 矢量图" },
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = theme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.clickable { showRawCode = !showRawCode }
                    ) {
                        Text(
                            text = if (showRawCode) "图形预览" else "源码",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = theme.surfaceContainerHigh,
                        modifier = Modifier.clickable(enabled = svgMarkup.isNotBlank()) { onOpenFullscreen() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "全屏查看",
                                tint = theme.textPrimary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text("全屏", fontSize = 10.sp, color = theme.textPrimary)
                        }
                    }
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("svg", svgMarkup))
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制 SVG",
                            tint = theme.textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            if (showRawCode) {
                Text(
                    text = svgMarkup,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textPrimary,
                        lineHeight = 15.5.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            } else if (svgMarkup.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp)
                        .clickable { onOpenFullscreen() }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SvgWebViewCanvas(
                        svgContent = svgMarkup,
                        enableZoom = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("正在加载 SVG 矢量图...", fontSize = 11.5.sp, color = theme.textMuted)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SvgWebViewCanvas(
    svgContent: String,
    enableZoom: Boolean,
    modifier: Modifier = Modifier
) {
    val isDark = LocalAppTheme.current.isDark
    val html = remember(svgContent, isDark) {
        val bgHex = if (isDark) "#14161F" else "#F8FAFC"
        val fgHex = if (isDark) "#E2E8F0" else "#1E293B"
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=${if (enableZoom) "yes" else "no"}">
        <style>
          html, body {
            margin: 0; padding: 8px;
            background: $bgHex; color: $fgHex;
            display: flex; align-items: center; justify-content: center;
            min-height: calc(100vh - 16px);
            box-sizing: border-box;
          }
          svg {
            max-width: 100%;
            max-height: 92vh;
            height: auto;
            width: auto;
          }
        </style>
        </head>
        <body>
        $svgContent
        </body>
        </html>
        """.trimIndent()
    }

    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(6.dp)),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.apply {
                    javaScriptEnabled = false
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = enableZoom
                    displayZoomControls = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
    )
}

/**
 * PDF 文档内联缩略图预览卡片：使用 Android 原生 PdfRenderer 渲染第一页缩略图，支持点击全屏多页阅读与缩放
 */
@Composable
private fun PdfThumbnailCard(
    attachment: MediaAttachment,
    onOpenFullscreen: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val gateway = LocalMediaGatewayConfig.current

    var firstPageBitmap by remember(attachment.id, attachment.filePath, attachment.dataBase64) {
        mutableStateOf<ImageBitmap?>(null)
    }
    var pageCount by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(attachment.id, attachment.filePath, attachment.dataBase64, gateway) {
        isLoading = true
        val bytes = resolveMediaBytes(attachment, gateway)
        if (bytes != null && bytes.isNotEmpty()) {
            val rendered = withContext(Dispatchers.IO) {
                try {
                    val tempFile = File(context.cacheDir, "pdf_preview_${attachment.id.hashCode()}.pdf")
                    tempFile.writeBytes(bytes)
                    ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            val count = renderer.pageCount
                            if (count > 0) {
                                renderer.openPage(0).use { page ->
                                    val w = (page.width * 1.5f).toInt().coerceIn(300, 1400)
                                    val h = (page.height * 1.5f).toInt().coerceIn(300, 1800)
                                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    bmp.eraseColor(android.graphics.Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bmp.asImageBitmap() to count
                                }
                            } else null
                        }
                    }
                } catch (_: Exception) {
                    null
                }
            }
            if (rendered != null) {
                firstPageBitmap = rendered.first
                pageCount = rendered.second
            }
        }
        isLoading = false
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(1.dp, theme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpenFullscreen() }
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = "PDF",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = attachment.title.ifBlank { attachment.filePath ?: "PDF 文档" },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (pageCount > 0) {
                        Text(
                            text = "共 $pageCount 页",
                            fontSize = 10.5.sp,
                            color = theme.textSecondary
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = theme.primary.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "全屏阅读 PDF",
                                tint = theme.primary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "全屏阅读",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.primary
                            )
                        }
                    }
                }
            }

            if (firstPageBitmap != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.15f))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = firstPageBitmap!!,
                        contentDescription = "PDF 首页预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .heightIn(min = 120.dp, max = 240.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )
                }
            } else if (isLoading) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp, color = theme.primary)
                    Text("正在渲染 PDF 缩略图...", fontSize = 11.5.sp, color = theme.textSecondary)
                }
            } else {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                    Text(
                        text = attachment.filePath ?: attachment.title,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textSecondary
                    )
                }
            }
        }
    }
}

/**
 * 全屏多媒体查看器 (Lightbox Dialog)：
 * - 支持图片双指无级缩放 (0.8x ~ 6.0x)、双击放大/复位、拖拽平移
 * - 支持 PDF 多页翻页 + 单页高清双指缩放
 * - 支持 SVG 矢量图全屏无损缩放
 * - 支持 Mermaid 脑图/流程图全屏拖拽与双指缩放查看
 */
@Composable
fun FullscreenMediaLightboxDialog(
    attachment: MediaAttachment,
    onDismiss: () -> Unit,
    customDiagramComposable: (@Composable () -> Unit)? = null
) {
    val context = LocalContext.current
    val gateway = LocalMediaGatewayConfig.current

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // PDF 翻页状态
    var pdfPageCount by remember { mutableIntStateOf(0) }
    var currentPdfPage by remember { mutableIntStateOf(0) }
    var currentBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(attachment, currentPdfPage, gateway) {
        if (attachment.kind == MediaKind.IMAGE || attachment.kind == MediaKind.PDF) {
            isLoading = true
            val bytes = resolveMediaBytes(attachment, gateway)
            if (bytes != null && bytes.isNotEmpty()) {
                if (attachment.kind == MediaKind.IMAGE) {
                    currentBitmap = withContext(Dispatchers.IO) {
                        try {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                } else if (attachment.kind == MediaKind.PDF) {
                    val result = withContext(Dispatchers.IO) {
                        try {
                            val tempFile = File(context.cacheDir, "pdf_full_${attachment.id.hashCode()}.pdf")
                            if (!tempFile.exists()) tempFile.writeBytes(bytes)
                            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                                PdfRenderer(pfd).use { renderer ->
                                    val count = renderer.pageCount
                                    val safeIdx = currentPdfPage.coerceIn(0, (count - 1).coerceAtLeast(0))
                                    if (count > 0) {
                                        renderer.openPage(safeIdx).use { page ->
                                            val w = (page.width * 2.2f).toInt().coerceIn(600, 2400)
                                            val h = (page.height * 2.2f).toInt().coerceIn(600, 3200)
                                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                            bmp.eraseColor(android.graphics.Color.WHITE)
                                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            bmp.asImageBitmap() to count
                                        }
                                    } else null
                                }
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (result != null) {
                        currentBitmap = result.first
                        pdfPageCount = result.second
                    }
                }
            }
            isLoading = false
        } else {
            isLoading = false
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xF20B0D13))
        ) {
            // 主体可缩放画布区域
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                if (scale > 1.05f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.4f
                                }
                            }
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(0.75f, 6f)
                            scale = newScale
                            offset = if (newScale > 1f) {
                                Offset(
                                    x = offset.x + pan.x,
                                    y = offset.y + pan.y
                                )
                            } else {
                                Offset.Zero
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    customDiagramComposable != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 72.dp)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                                .verticalScroll(rememberScrollState())
                        ) {
                            customDiagramComposable()
                        }
                    }

                    attachment.kind == MediaKind.SVG -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp, vertical = 72.dp)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            SvgWebViewCanvas(
                                svgContent = attachment.rawContent ?: "",
                                enableZoom = true,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    currentBitmap != null -> {
                        Image(
                            bitmap = currentBitmap!!,
                            contentDescription = attachment.title,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 8.dp, vertical = 64.dp)
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y
                                )
                        )
                    }

                    isLoading -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White, strokeWidth = 2.5.dp)
                            Text("正在加载高清大图...", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                        }
                    }

                    else -> {
                        Text(
                            text = "无法加载媒体预览 (${attachment.title})",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 顶部悬浮导航栏：标题 + 缩放比例复位 + 关闭按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .statusBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = attachment.title.ifBlank { "全屏预览" },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!attachment.filePath.isNullOrBlank()) {
                        Text(
                            text = attachment.filePath,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 缩放控制按钮组
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Remove,
                                contentDescription = "缩小",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        scale = (scale / 1.25f).coerceAtLeast(0.75f)
                                        if (scale <= 1f) offset = Offset.Zero
                                    }
                            )
                            Text(
                                text = String.format(Locale.US, "%.0f%%", scale * 100f),
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.clickable {
                                    scale = 1f
                                    offset = Offset.Zero
                                }
                            )
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "放大",
                                tint = Color.White,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        scale = (scale * 1.25f).coerceAtMost(6f)
                                    }
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .background(Color.White.copy(alpha = 0.16f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭全屏预览",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 底部控制栏：PDF 分页切换或操作提示
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (attachment.kind == MediaKind.PDF && pdfPageCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = Color.Black.copy(alpha = 0.75f),
                        border = BorderStroke(0.8.dp, Color.White.copy(alpha = 0.2f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            IconButton(
                                onClick = {
                                    if (currentPdfPage > 0) {
                                        currentPdfPage--
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                },
                                enabled = currentPdfPage > 0,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "上一页",
                                    tint = if (currentPdfPage > 0) Color.White else Color.White.copy(alpha = 0.35f)
                                )
                            }
                            Text(
                                text = "第 ${currentPdfPage + 1} / $pdfPageCount 页",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                            IconButton(
                                onClick = {
                                    if (currentPdfPage < pdfPageCount - 1) {
                                        currentPdfPage++
                                        scale = 1f
                                        offset = Offset.Zero
                                    }
                                },
                                enabled = currentPdfPage < pdfPageCount - 1,
                                modifier = Modifier.size(26.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "下一页",
                                    tint = if (currentPdfPage < pdfPageCount - 1) Color.White else Color.White.copy(alpha = 0.35f)
                                )
                            }
                        }
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color.Black.copy(alpha = 0.55f)
                    ) {
                        Text(
                            text = "双指捏合缩放 · 双击快速放大/复位 · 拖拽平移",
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
