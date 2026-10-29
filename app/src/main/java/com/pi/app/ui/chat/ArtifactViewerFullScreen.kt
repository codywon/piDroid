package com.pi.app.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.FilePlaybackSource
import com.pi.app.data.model.formatBytes
import com.pi.app.ui.components.FileLinks
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.components.SvgWebViewCanvas
import com.pi.app.ui.theme.LocalAppTheme

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
    docxHtml: String?,
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
) {
    val theme = LocalAppTheme.current

    // 沉浸态（隐藏顶栏）用 rememberSaveable：转屏后仍保持用户选的阅读模式
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }

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
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "返回",
                                tint = theme.textSecondary
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            meta?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    fontSize = 10.5.sp,
                                    color = theme.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { menuOpen = true },
                                modifier = Modifier.size(48.dp)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
                        ArtifactKind.PDF -> ArtifactHintCard(
                            text = message
                                ?: "PDF 交给手机上的阅读器：右上 ⋮「用其他应用打开」会把文件下载到本机，"
                                + "交给 WPS / 系统 PDF 阅读器展示（可缩放、可搜文本）"
                        )
                        else -> Unit
                    }

                    image?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = title,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    if (kind == ArtifactKind.IMAGE && message != null) {
                        ArtifactHintCard(text = message)
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
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    item {
                                        // 源码/纯文本：横向可滚动。左右留出内边距，
                                        // 避免起手就贴着系统的边缘返回热区（完整方案是
                                        // Modifier.systemGestureExclusion，见设计文档 P2）。
                                        Text(
                                            text = body,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = theme.textPrimary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                                .horizontalScroll(rememberScrollState())
                                        )
                                    }
                                    if (richPreviewKind && truncated) {
                                        item {
                                            Text(
                                                text = "内容尚未完整加载：先按源码显示，加载完才能切回预览",
                                                fontSize = 10.5.sp,
                                                color = theme.textMuted,
                                                modifier = Modifier.padding(horizontal = 14.dp)
                                            )
                                        }
                                    }
                                    if (truncated) {
                                        item {
                                            // 对标 pi-web FileViewer 的 Load more：手动续读（不自动追加），
                                            // 阅读器的「读到哪」必须可预期。
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
