package com.pi.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.WrapText
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.FilePlaybackSource
import com.pi.app.data.model.formatBytes
import com.pi.app.network.FileListParsing
import com.pi.app.ui.components.edgeSwipeBack
import com.pi.app.ui.components.FileLinks
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.components.PdfDocumentView
import com.pi.app.ui.components.SvgWebViewCanvas
import com.pi.app.ui.components.ZoomableImageCanvas
import com.pi.app.ui.files.CodeSyntaxHighlighter
import com.pi.app.ui.files.OfficePreview
import com.pi.app.ui.files.OfficeXmlPreview
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.File

/**
 * 全屏文件 / 输出查看器（替代原先的 `ModalBottomSheet` 半屏弹层）。
 *
 * 为什么要全屏、为什么不再用 bottom sheet：
 * 1) `LazyColumn` 与 `ModalBottomSheet` 各自装着一个 nested-scroll connection，
 *    fling 速度会串到 sheet 自己的拖拽手势上 —— 于是「内容滑到底、sheet 继续动、
 *    然后弹回半屏」，用户看到的就是「一上滑就缩回去」（AOSP issue 353304855 的同一现象）；
 * 2) WebView（docx / SVG）根本不参与 Compose 的 nested scroll，滚动手势必被外层抢走；
 * 3) 半屏定位还带来「读到末尾下拉 = 误关闭」，对长报告是致命体验。
 * Material 3 对 bottom sheet 的定位是「补充屏幕主内容的次要区域」，
 * 一次性、长内容的阅读流程应当用全屏页面（如 Quick Look / 文档阅读器）。
 *
 * 交互约定（与 `docs/DESIGN-artifact-viewer.md` 一致）：
 * - 返回：系统返回键 / 系统手势返回 / 顶栏左侧返回按钮 —— 三条路都关掉查看器，
 *   且不会串到外层「返回会话列表」（本组件自己注册 BackHandler，后注册者优先）；
 * - 沉浸：点击正文区域切换顶栏显示（Quick Look 式），返回按钮不常驻隐藏；
 * - 动作：顶栏右侧 ⋮ 收口全部动作（复制全部 / 复制路径 / 用其他应用打开 / 重新加载），
 *   正文区只保留「加载更多」这一处行内主操作（与 pi-web FileViewer 的 Load more 1:1）；
 * - 触控目标：所有可点区域 ≥ 48dp（Material 规范，不是 iOS 的 44pt）。
 *
 * 平板（≥600dp）按 `docs/DESIGN-artifact-viewer.md` 规划为「左聊天 + 右文档」分栏，
 * 与 pi-web 桌面端 FileViewer 对齐；当前先保证手机全屏路径。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtifactViewerFullScreen(
    title: String,
    path: String?,
    meta: String?,
    kind: ArtifactKind,
    text: String?,
    image: ImageBitmap?,
    pdfFile: File? = null,
    docxHtml: String?,
    officePreview: OfficePreview? = null,
    message: String?,
    rawMode: Boolean,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    truncated: Boolean,
    nextOffset: Long,
    sizeBytes: Long,
    isOpeningExternally: Boolean,
    sessionCwd: String,
    onResolveMediaSource: (String) -> FilePlaybackSource?,
    onBack: () -> Unit,
    onToggleRawMode: (Boolean) -> Unit,
    onLoadMore: () -> Unit,
    onOpenExternal: () -> Unit,
    onReload: () -> Unit,
    onOpenLink: (href: String, baseDir: String) -> Unit,
    onCopyText: (String) -> Unit,
    onAtMention: ((String) -> Unit)? = null,
    onDownloadFile: ((String) -> Unit)? = null,
    onWatchFileChanges: ((String) -> Flow<Unit>)? = null,
) {
    val theme = LocalAppTheme.current

    // 自动刷新：对标 pi-web GET /api/files/[...path]?type=watch
    val currentOnReload by rememberUpdatedState(onReload)
    val watchFlow = remember(path, onWatchFileChanges) {
        path?.takeIf { it.isNotBlank() }?.let { onWatchFileChanges?.invoke(it) }
    }
    LaunchedEffect(watchFlow) {
        watchFlow?.catch { android.util.Log.d("ArtifactViewer", "watch flow closed: ${it.message}") }
            ?.collect {
                kotlinx.coroutines.delay(200L)
                currentOnReload()
            }
    }

    // 沉浸态（隐藏顶栏）用 rememberSaveable：转屏后仍保持用户选的阅读模式
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var wrapLines by rememberSaveable { mutableStateOf(false) }

    // 返回键/手势：先关查看器，不要漏到 ChatScreen 的「退回会话列表」
    BackHandler(enabled = true) { onBack() }

    val isErrorBody = text != null &&
        (text.startsWith("读取文件失败") || text.startsWith("读取图片失败"))
    val richPreviewKind = kind == ArtifactKind.MARKDOWN || kind == ArtifactKind.SVG
    // 对标 pi-web：必须整篇加载完（!truncated）才渲染，否则未闭合的代码围栏/表格/
    // 未封口的 <svg> 会渲染成错乱的样子。
    val richPreviewReady = richPreviewKind && !truncated && !isLoading
    val showRenderedPreview = richPreviewReady && !rawMode && !isErrorBody
    val docDir = path?.let { FileLinks.parentDirectory(it) }.orEmpty()

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 左边缘右滑返回：挂在根容器上（不叠透明热区），顶栏返回按钮照常可点
            .edgeSwipeBack(onSwipeBack = onBack)
    ) {
        Scaffold(
            containerColor = theme.surfaceContainerLow,
            topBar = {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                                tint = theme.textSecondary
                            )
                        }
                    },
                    title = {
                        Column {
                            val displayPath = remember(path, sessionCwd) {
                                if (path.isNullOrBlank()) title
                                else FileListParsing.relativePath(path, sessionCwd).removePrefix("./")
                            }
                            Text(
                                text = displayPath,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitleText = remember(meta, text, sizeBytes, kind) {
                                val lineCount = text?.count { it == '\n' }?.plus(1)
                                if (lineCount != null && sizeBytes > 0L) {
                                    val ext = path?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT) ?: "text"
                                    "$ext · $lineCount lines · ${formatBytes(sizeBytes)}"
                                } else {
                                    meta?.takeIf { it.isNotBlank() }
                                }
                            }
                            if (!subtitleText.isNullOrBlank()) {
                                Text(
                                    text = subtitleText,
                                    fontSize = 10.5.sp,
                                    color = theme.textMuted,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        // 1. 实时指示绿点
                        Box(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .size(6.dp)
                                .background(theme.success, CircleShape)
                        )

                        // 2. @ 引用按钮
                        if (onAtMention != null && !path.isNullOrBlank()) {
                            IconButton(
                                onClick = {
                                    val rel = FileListParsing.relativePath(path, sessionCwd).removePrefix("./")
                                    onAtMention(rel)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AlternateEmail,
                                    contentDescription = "引用到当前对话",
                                    tint = theme.textSecondary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        // 3. 自动折行切换（仅代码/文本源码视图生效）
                        if (!showRenderedPreview && kind != ArtifactKind.IMAGE && kind != ArtifactKind.PDF &&
                            kind != ArtifactKind.AUDIO && kind != ArtifactKind.VIDEO && kind != ArtifactKind.OFFICE
                        ) {
                            IconButton(
                                onClick = { wrapLines = !wrapLines },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.WrapText,
                                    contentDescription = if (wrapLines) "禁用自动换行" else "启用自动换行",
                                    tint = if (wrapLines) theme.primary else theme.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // 4. 一键下载按钮
                        if (onDownloadFile != null && !path.isNullOrBlank()) {
                            IconButton(
                                onClick = { onDownloadFile(path) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FileDownload,
                                    contentDescription = "下载到手机",
                                    tint = theme.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "更多操作",
                                    tint = theme.textSecondary
                                )
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false }
                            ) {
                                val body = text
                                if (body != null && !isErrorBody) {
                                    DropdownMenuItem(
                                        text = { Text("复制全部", fontSize = 13.sp) },
                                        onClick = {
                                            menuOpen = false
                                            onCopyText(body)
                                        }
                                    )
                                }
                                if (!path.isNullOrBlank()) {
                                    DropdownMenuItem(
                                        text = { Text("复制文件路径", fontSize = 13.sp) },
                                        onClick = {
                                            menuOpen = false
                                            onCopyText(path)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (isOpeningExternally) "正在准备…" else "用其他应用打开（WPS / 系统）",
                                                fontSize = 13.sp
                                            )
                                        },
                                        enabled = !isOpeningExternally,
                                        onClick = {
                                            menuOpen = false
                                            onOpenExternal()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("重新加载", fontSize = 13.sp) },
                                    onClick = {
                                        menuOpen = false
                                        onReload()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = theme.surfaceContainerLow
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            // 预览/源码：这是「模式开关」而不是动作，所以留在正文上方常驻，
            // 不进 ⋮ 菜单（与 pi-web FileViewer 的分段控件同义）。
            if (showRenderedPreview && chromeVisible) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("预览" to false, "源码" to true).forEach { (label, raw) ->
                        val selected = rawMode == raw
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (selected) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainer,
                            border = BorderStroke(
                                0.7.dp,
                                if (selected) theme.primary.copy(alpha = 0.5f) else theme.outline.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .height(32.dp)
                                .clickable { onToggleRawMode(raw) }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 12.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.5.sp,
                                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                                    color = if (selected) theme.primary else theme.textSecondary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    if (truncated) {
                        Text(
                            text = "${formatBytes(nextOffset)} / ${formatBytes(sizeBytes)}",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 点击正文切换沉浸（顶栏显隐）。子元素自带 clickable/滚动时
                    // 先消费事件，链接、按钮、滚动都不受影响。
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { chromeVisible = !chromeVisible }
            ) {
                if (isLoading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.8.dp,
                            color = theme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("正在读取…", fontSize = 12.sp, color = theme.textMuted)
                    }
                } else {
                    when (kind) {
                        ArtifactKind.AUDIO -> AudioArtifactCard(
                            source = path?.let { onResolveMediaSource(it) },
                            errorText = message
                        )
                        ArtifactKind.VIDEO -> VideoArtifactCard(
                            source = path?.let { onResolveMediaSource(it) },
                            errorText = message
                        )
                        ArtifactKind.DOCX -> DocxArtifactCard(
                            html = docxHtml,
                            message = message,
                            modifier = Modifier.fillMaxSize()
                        )
                        ArtifactKind.PDF -> {
                            if (pdfFile != null) {
                                PdfDocumentView(
                                    file = pdfFile,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                ArtifactHintCard(
                                    text = message
                                        ?: "PDF 交给手机上的阅读器：右上 ⋮「用其他应用打开」会把文件下载到本机，"
                                        + "交给 WPS / 系统 PDF 阅读器展示（可缩放、可搜文本）"
                                )
                            }
                        }
                        ArtifactKind.OFFICE -> {
                            when (val preview = officePreview) {
                                is OfficePreview.Sheet -> SheetTablePreview(
                                    preview = preview,
                                    modifier = Modifier.fillMaxSize()
                                )
                                is OfficePreview.Slides -> SlidesPreview(
                                    preview = preview,
                                    modifier = Modifier.fillMaxSize()
                                )
                                null -> ExternalDocumentCard(
                                    title = title,
                                    path = path,
                                    sizeBytes = sizeBytes,
                                    message = message,
                                    isOpeningExternally = isOpeningExternally,
                                    onOpenExternal = onOpenExternal,
                                    onDownload = { path?.let { onDownloadFile?.invoke(it) } }
                                )
                            }
                        }
                        else -> Unit
                    }

                    if (kind != ArtifactKind.OFFICE && kind != ArtifactKind.AUDIO && kind != ArtifactKind.VIDEO &&
                        kind != ArtifactKind.PDF && kind != ArtifactKind.IMAGE &&
                        text == null && image == null && docxHtml == null && !isLoading
                    ) {
                        ExternalDocumentCard(
                            title = title,
                            path = path,
                            sizeBytes = sizeBytes,
                            message = message,
                            isOpeningExternally = isOpeningExternally,
                            onOpenExternal = onOpenExternal,
                            onDownload = { path?.let { onDownloadFile?.invoke(it) } }
                        )
                    }

                    image?.let { bmp ->
                        ZoomableImageCanvas(
                            bitmap = bmp,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    // 图片读取失败：给「重试 / 用其他应用打开」，而不是误报成「办公文档」
                    if (kind == ArtifactKind.IMAGE && image == null && !isLoading) {
                        ArtifactErrorState(
                            message = message ?: "图片读取失败",
                            path = path,
                            canOpenExternally = !path.isNullOrBlank() && !isOpeningExternally,
                            onReload = onReload,
                            onOpenExternal = onOpenExternal
                        )
                    }

                    text?.let { body ->
                        if (isErrorBody) {
                            ArtifactErrorState(
                                message = body,
                                path = path,
                                canOpenExternally = !path.isNullOrBlank() && !isOpeningExternally,
                                onReload = onReload,
                                onOpenExternal = onOpenExternal
                            )
                        } else {
                            if (showRenderedPreview) {
                                if (kind == ArtifactKind.SVG) {
                                    Surface(
                                        shape = RoundedCornerShape(0.dp),
                                        color = theme.surfaceContainer,
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        SvgWebViewCanvas(
                                            svgContent = body,
                                            enableZoom = true,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        item {
                                            MarkdownRenderer(
                                                content = body,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                                isUser = false,
                                                onOpenLink = { href ->
                                                    // 文档内部链接以该文档所在目录为基准
                                                    // （pi-web FileViewer 的 markdownDirectory），
                                                    // 目录外才回退到会话工作目录。
                                                    onOpenLink(href, docDir.ifBlank { sessionCwd })
                                                }
                                            )
                                        }
                                    }
                                }
                            } else {
                                val lines = remember(body) { body.split('\n') }
                                val ext = path?.substringAfterLast('.', "") ?: ""
                                val lang = remember(ext) { CodeSyntaxHighlighter.resolveLanguage(ext) }
                                val maxDigits = remember(lines.size) { lines.size.toString().length.coerceAtLeast(2) }
                                val isTooLarge = lines.size > CodeSyntaxHighlighter.MAX_HIGHLIGHT_LINES

                                if (wrapLines) {
                                    LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 6.dp)) {
                                        itemsIndexed(
                                            items = lines,
                                            key = { idx, _ -> idx },
                                            contentType = { _, _ -> "code_line" }
                                        ) { idx, line ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 0.5.dp)
                                            ) {
                                                Text(
                                                    text = "${idx + 1}".padStart(maxDigits, ' '),
                                                    color = theme.textMuted.copy(alpha = 0.55f),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp,
                                                    textAlign = TextAlign.End,
                                                    modifier = Modifier.padding(end = 10.dp)
                                                )
                                                if (isTooLarge) {
                                                    Text(
                                                        text = line,
                                                        color = theme.textPrimary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                } else {
                                                    val tokens = remember(line, lang) { CodeSyntaxHighlighter.tokenizeLine(line, lang) }
                                                    val highlighted = remember(tokens, theme) {
                                                        CodeSyntaxHighlighter.buildLineAnnotatedString(tokens, theme)
                                                    }
                                                    Text(
                                                        text = highlighted,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                }
                                            }
                                        }
                                        if (richPreviewKind && truncated) {
                                            item {
                                                Text(
                                                    text = "内容尚未完整加载：先按源码显示，加载完才能切回预览",
                                                    fontSize = 10.5.sp,
                                                    color = theme.textMuted,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                        if (truncated) {
                                            item {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = theme.primary.copy(alpha = 0.14f),
                                                        border = BorderStroke(0.7.dp, theme.primary.copy(alpha = 0.45f)),
                                                        modifier = Modifier
                                                            .height(40.dp)
                                                            .clickable(enabled = !isLoadingMore) { onLoadMore() }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 14.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            if (isLoadingMore) {
                                                                CircularProgressIndicator(
                                                                    modifier = Modifier.size(12.dp),
                                                                    strokeWidth = 1.4.dp,
                                                                    color = theme.primary
                                                                )
                                                            }
                                                            Text(
                                                                text = if (isLoadingMore) "加载中…" else "加载更多",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = theme.primary
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "${formatBytes(nextOffset)} / ${formatBytes(sizeBytes)}",
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = theme.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val horizontalScrollState = rememberScrollState()
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(vertical = 6.dp)
                                            .horizontalScroll(horizontalScrollState)
                                    ) {
                                        itemsIndexed(
                                            items = lines,
                                            key = { idx, _ -> idx },
                                            contentType = { _, _ -> "code_line" }
                                        ) { idx, line ->
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 0.5.dp)
                                            ) {
                                                Text(
                                                    text = "${idx + 1}".padStart(maxDigits, ' '),
                                                    color = theme.textMuted.copy(alpha = 0.55f),
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    lineHeight = 16.sp,
                                                    textAlign = TextAlign.End,
                                                    modifier = Modifier.padding(end = 10.dp)
                                                )
                                                if (isTooLarge) {
                                                    Text(
                                                        text = line,
                                                        color = theme.textPrimary,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                } else {
                                                    val tokens = remember(line, lang) { CodeSyntaxHighlighter.tokenizeLine(line, lang) }
                                                    val highlighted = remember(tokens, theme) {
                                                        CodeSyntaxHighlighter.buildLineAnnotatedString(tokens, theme)
                                                    }
                                                    Text(
                                                        text = highlighted,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontSize = 11.sp,
                                                        lineHeight = 16.sp
                                                    )
                                                }
                                            }
                                        }
                                        if (richPreviewKind && truncated) {
                                            item {
                                                Text(
                                                    text = "内容尚未完整加载：先按源码显示，加载完才能切回预览",
                                                    fontSize = 10.5.sp,
                                                    color = theme.textMuted,
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                                )
                                            }
                                        }
                                        if (truncated) {
                                            item {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = theme.primary.copy(alpha = 0.14f),
                                                        border = BorderStroke(0.7.dp, theme.primary.copy(alpha = 0.45f)),
                                                        modifier = Modifier
                                                            .height(40.dp)
                                                            .clickable(enabled = !isLoadingMore) { onLoadMore() }
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 14.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            if (isLoadingMore) {
                                                                CircularProgressIndicator(
                                                                    modifier = Modifier.size(12.dp),
                                                                    strokeWidth = 1.4.dp,
                                                                    color = theme.primary
                                                                )
                                                            }
                                                            Text(
                                                                text = if (isLoadingMore) "加载中…" else "加载更多",
                                                                fontSize = 12.sp,
                                                                fontWeight = FontWeight.Medium,
                                                                color = theme.primary
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = "${formatBytes(nextOffset)} / ${formatBytes(sizeBytes)}",
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = theme.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 音频/视频没有内嵌渲染失败时的兜底提示
                    if ((kind == ArtifactKind.AUDIO || kind == ArtifactKind.VIDEO) &&
                        message != null && text.isNullOrBlank()
                    ) {
                        ArtifactHintCard(text = message)
                    }
                }
            }
        }
    }
}
}
}

/**
 * 错误态：一个主操作 + 一个次操作 + 可直接选中的路径。
 *
 * 之前只有一个红色文案「读取文件失败：Access denied」，用户既不知道原因也没有出路
 * （这次的 403 事故就是它把排查成本抬高的）。
 */
@Composable
private fun ArtifactErrorState(
    message: String,
    path: String?,
    canOpenExternally: Boolean,
    onReload: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val theme = LocalAppTheme.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "打不开这个文件",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = theme.textPrimary
        )
        Text(
            text = message,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = theme.textSecondary
        )
        if (!path.isNullOrBlank()) {
            SelectionContainer {
                Text(
                    text = path,
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )
            }
            Text(
                text = "长按路径可选中复制；也可以直接交给 WPS / 系统应用打开。",
                fontSize = 10.5.sp,
                color = theme.textMuted
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = theme.primary.copy(alpha = 0.16f),
                border = BorderStroke(0.7.dp, theme.primary.copy(alpha = 0.5f)),
                modifier = Modifier
                    .height(40.dp)
                    .clickable { onReload() }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text("重新加载", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = theme.primary)
                }
            }
            if (canOpenExternally) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .height(40.dp)
                        .clickable { onOpenExternal() }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(
                            "用其他应用打开",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.textSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 针对 Word、Excel、PPT、压缩包等端内不直接解析格式的高清优雅卡片。
 * 提供一键使用 WPS/系统应用打开的稳定兜底与下载能力。
 */
@Composable
private fun ExternalDocumentCard(
    title: String,
    path: String?,
    sizeBytes: Long,
    message: String?,
    isOpeningExternally: Boolean,
    onOpenExternal: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val ext = path?.substringAfterLast('.', "")?.lowercase(java.util.Locale.ROOT) ?: ""
    val (icon, iconColor, typeDesc) = when (ext) {
        "xls", "xlsx", "et", "csv" -> Triple(Icons.Default.TableChart, theme.success, "Excel 工作表")
        "ppt", "pptx", "dps" -> Triple(Icons.Default.Slideshow, theme.warning, "PowerPoint 演示文稿")
        "doc", "docx", "wps" -> Triple(Icons.Default.Description, theme.primary, "Word 文档")
        "zip", "rar", "7z", "tar", "gz", "apk" -> Triple(Icons.Default.FolderZip, theme.warning, "压缩包 / 安装包")
        else -> Triple(Icons.AutoMirrored.Filled.InsertDriveFile, theme.primary, "办公 / 外部文档")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = theme.surfaceContainer,
            border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = iconColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = iconColor,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = if (sizeBytes > 0L) "$typeDesc · ${formatBytes(sizeBytes)}" else typeDesc,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = theme.textMuted
                )

                Text(
                    text = message ?: "当前格式暂不支持端内直接渲染，建议通过外部应用（如 WPS / 系统 Office）打开查阅。",
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    color = theme.textSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 主操作按钮：用系统应用打开
                Button(
                    onClick = onOpenExternal,
                    enabled = !isOpeningExternally,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = theme.primary,
                        contentColor = theme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    if (isOpeningExternally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = theme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在传输至应用…", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("用其他应用打开 (WPS / Office)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // 次操作按钮：下载保存到手机
                OutlinedButton(
                    onClick = onDownload,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, theme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, tint = theme.textPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("下载保存到手机", fontSize = 13.sp, color = theme.textPrimary)
                }
            }
        }
    }
}

/**
 * xlsx 表格预览。
 *
 * Excel 在手机上真正要看的是「表格本身」，所以端内解析 OOXML 后直接渲染成
 * 等宽网格（首行为表头样式），横向可滚动、纵向懒加载，而不是丢一张
 * 「请用 WPS 打开」的卡片。超出 [OfficeXmlPreview.MAX_ROWS] / `MAX_COLS`
 * 的部分会提示被截断。
 */
@Composable
private fun SheetTablePreview(
    preview: OfficePreview.Sheet,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val tableScrollState = rememberScrollState()
    val columnCount = preview.rows.maxOfOrNull { it.size }?.coerceAtLeast(1) ?: 1
    val widthPerColumn = 128.dp

    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.TableChart,
                contentDescription = null,
                tint = theme.success,
                modifier = Modifier.size(15.dp)
            )
            Text(
                text = preview.sheetName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = theme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${preview.rows.size} 行 · $columnCount 列",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted
            )
        }
        if (preview.truncated) {
            Text(
                text = "仅展示前 ${OfficeXmlPreview.MAX_ROWS} 行 / ${OfficeXmlPreview.MAX_COLS} 列",
                fontSize = 10.5.sp,
                color = theme.textMuted,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
        HorizontalDivider(color = theme.outline.copy(alpha = 0.35f), thickness = 0.5.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .horizontalScroll(tableScrollState)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(preview.rows) { rowIndex, row ->
                    val isHeader = rowIndex == 0
                    Row(
                        modifier = Modifier
                            .background(
                                if (isHeader) theme.surfaceContainer
                                else if (rowIndex % 2 == 0) androidx.compose.ui.graphics.Color.Transparent
                                else theme.surfaceContainerLow
                            )
                    ) {
                        for (col in 0 until columnCount) {
                            Text(
                                text = row.getOrNull(col).orEmpty(),
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isHeader) theme.textPrimary else theme.textSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .width(widthPerColumn)
                                    .border(
                                        width = 0.5.dp,
                                        color = theme.outline.copy(alpha = 0.3f)
                                    )
                                    .padding(horizontal = 6.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * pptx 幻灯片预览：按页抽出文本（标题 + 要点），保留页码顺序。
 * 图形/图片版面无法在端内还原，但文字大纲已足够「看一眼讲什么」。
 */
@Composable
private fun SlidesPreview(
    preview: OfficePreview.Slides,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(preview.slides) { _, slide ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainer,
                border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Slideshow,
                            contentDescription = null,
                            tint = theme.warning,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "第 ${slide.index} 页",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    slide.lines.forEachIndexed { lineIndex, line ->
                        Text(
                            text = line,
                            fontSize = if (lineIndex == 0) 13.5.sp else 12.5.sp,
                            fontWeight = if (lineIndex == 0) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (lineIndex == 0) theme.textPrimary else theme.textSecondary,
                            lineHeight = 19.sp,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }
    }
}
