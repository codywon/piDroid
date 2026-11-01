package com.pi.app.ui.files

import androidx.activity.compose.BackHandler
import com.pi.app.network.FileListParsing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import com.pi.app.data.model.FileDirectoryListing
import com.pi.app.data.model.FileEntry
import com.pi.app.data.model.FileMeta
import com.pi.app.data.model.FilePlaybackSource
import com.pi.app.data.model.FileTextChunk
import com.pi.app.data.model.GitStatusResult
import com.pi.app.ui.chat.ArtifactKind
import com.pi.app.ui.chat.ArtifactViewerFullScreen
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val sharedExpandedMemory = ExpandedPathsMemory()

/**
 * 展平后的树视图行项。
 */
private data class FlattenedTreeItem(
    val entry: FileEntry,
    val depth: Int,
    val fullPath: String,
    val isExpanded: Boolean,
    val isLoading: Boolean,
    val isTruncated: Boolean,
    val errorMessage: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilesScreen(
    workspaceCwd: String,
    workspaceName: String,
    onBack: () -> Unit,
    onDownloadFile: (String) -> Unit,
    onOpenExternal: (String) -> Unit,
    onAtMention: (relativePath: String, isDir: Boolean) -> Unit = { _, _ -> },
    onLoadDirectory: suspend (String, Boolean) -> Result<FileDirectoryListing>,
    onSearchFiles: suspend (String, String) -> Result<List<com.pi.app.data.model.FileIndexMatch>>,
    onLoadGitStatus: suspend (String) -> Result<GitStatusResult>,
    onLoadFileMeta: suspend (String) -> Result<FileMeta>,
    onLoadFileTextChunk: suspend (String, Long) -> Result<FileTextChunk>,
    onLoadFilePreviewHtml: suspend (String) -> Result<String>,
    onLoadFileBytes: suspend (String) -> Result<ByteArray>,
    onDownloadFileToCache: suspend (String) -> Result<File>,
    onResolveFileSource: (String) -> FilePlaybackSource?,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var showHidden by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchTreeNode>>(emptyList()) }

    var isInitialLoading by remember { mutableStateOf(true) }
    var rootErrorMessage by remember { mutableStateOf<String?>(null) }
    var gitStatus by remember { mutableStateOf<GitStatusResult?>(null) }

    // 目录缓存与展开状态
    val childrenMap = remember { mutableStateMapOf<String, List<FileEntry>>() }
    val loadingPaths = remember { mutableStateMapOf<String, Boolean>() }
    val errorPaths = remember { mutableStateMapOf<String, String>() }
    val truncatedPaths = remember { mutableStateMapOf<String, Boolean>() }
    var expandedPaths by remember(workspaceCwd) {
        mutableStateOf(sharedExpandedMemory.restore(workspaceCwd))
    }

    // 预览相关状态
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewTitle by remember { mutableStateOf<String>("") }
    var previewKind by remember { mutableStateOf(ArtifactKind.TEXT) }
    var previewMeta by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var previewDocxHtml by remember { mutableStateOf<String?>(null) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    var previewRawMode by remember { mutableStateOf(false) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var isPreviewLoadingMore by remember { mutableStateOf(false) }
    var previewTruncated by remember { mutableStateOf(false) }
    var previewNextOffset by remember { mutableStateOf(0L) }
    var previewSizeBytes by remember { mutableStateOf(0L) }

    fun openFilePreview(absPath: String) {
        previewPath = absPath
        previewTitle = FileListParsing.displayName(absPath)
        previewText = null
        previewImage = null
        previewPdfFile = null
        previewDocxHtml = null
        previewMessage = null
        previewRawMode = false
        previewNextOffset = 0L
        previewSizeBytes = 0L
        previewTruncated = false
        isPreviewLoadingMore = false
        isPreviewLoading = true

        coroutineScope.launch {
            val dataSource = object : FilePreviewDataSource {
                override suspend fun getFileMeta(path: String) = onLoadFileMeta(path)
                override suspend fun getFileTextChunk(path: String, offset: Long) = onLoadFileTextChunk(path, offset)
                override suspend fun getFilePreviewHtml(path: String) = onLoadFilePreviewHtml(path)
                override suspend fun getFileBytes(path: String) = onLoadFileBytes(path)
                override suspend fun downloadFileToCache(path: String) = onDownloadFileToCache(path)
            }
            val data = RemoteFilePreviewLoader.load(absPath, dataSource)
            previewTitle = data.title
            previewKind = data.kind
            previewMeta = data.metaLabel
            previewDocxHtml = data.docxHtml
            previewMessage = data.message
            previewNextOffset = data.nextOffset
            previewTruncated = data.truncated
            previewSizeBytes = data.sizeBytes
            previewPdfFile = data.pdfFile
            previewText = data.text

            data.imageBytes?.let { bytes ->
                previewImage = runCatching {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
                if (previewImage == null && previewMessage == null) {
                    previewMessage = "图片解码失败（可能过大或格式不支持）"
                }
            }
            isPreviewLoading = false
        }
    }

    fun loadMorePreviewText() {
        val path = previewPath ?: return
        if (isPreviewLoadingMore || !previewTruncated) return
        isPreviewLoadingMore = true
        coroutineScope.launch {
            onLoadFileTextChunk(path, previewNextOffset)
                .onSuccess { chunk ->
                    previewText = (previewText ?: "") + chunk.content
                    previewNextOffset = chunk.nextOffset
                    previewTruncated = chunk.truncated
                    if (chunk.size > 0L) previewSizeBytes = chunk.size
                }
                .onFailure { err ->
                    Toast.makeText(context, "加载更多失败: ${err.message}", Toast.LENGTH_SHORT).show()
                }
            isPreviewLoadingMore = false
        }
    }

    // 展开或折叠目录
    fun toggleExpand(dirPath: String) {
        val next = HashSet(expandedPaths)
        if (next.contains(dirPath)) {
            next.remove(dirPath)
        } else {
            next.add(dirPath)
            if (!childrenMap.containsKey(dirPath)) {
                loadingPaths[dirPath] = true
                errorPaths.remove(dirPath)
                coroutineScope.launch {
                    val result = onLoadDirectory(dirPath, showHidden)
                    result.onSuccess { listing ->
                        val (capped, truncated) = FileTreeState.filterAndCapEntries(listing.entries, showHidden)
                        childrenMap[dirPath] = capped
                        truncatedPaths[dirPath] = truncated
                    }.onFailure { err ->
                        errorPaths[dirPath] = err.message ?: "未知错误"
                    }
                    loadingPaths.remove(dirPath)
                }
            }
        }
        expandedPaths = next
        sharedExpandedMemory.save(workspaceCwd, next)
    }

    // 刷新根目录
    fun refreshTree() {
        isInitialLoading = true
        rootErrorMessage = null
        childrenMap.clear()
        errorPaths.clear()
        truncatedPaths.clear()

        coroutineScope.launch {
            val gitRes = onLoadGitStatus(workspaceCwd)
            gitStatus = gitRes.getOrNull()

            val dirRes = onLoadDirectory(workspaceCwd, showHidden)
            dirRes.onSuccess { listing ->
                val (capped, truncated) = FileTreeState.filterAndCapEntries(listing.entries, showHidden)
                childrenMap[workspaceCwd] = capped
                truncatedPaths[workspaceCwd] = truncated
            }.onFailure { err ->
                rootErrorMessage = err.message ?: "加载工作区文件失败"
            }
            isInitialLoading = false
        }
    }

    LaunchedEffect(workspaceCwd, showHidden) {
        refreshTree()
    }

    // 搜索输入防抖（对齐 pi-web 的 150ms 响应）
    LaunchedEffect(searchQuery, searchOpen) {
        if (!searchOpen || searchQuery.trim().isEmpty()) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(150)
        val res = onSearchFiles(workspaceCwd, searchQuery.trim())
        res.onSuccess { matches ->
            val paths = matches.map { it.path }
            searchResults = FileTreeState.buildSearchTree(paths)
        }.onFailure {
            searchResults = emptyList()
        }
        searchLoading = false
    }

    // 计算包含 Git 变更的目录路径集合
    val changedDirectories = remember(gitStatus, workspaceCwd) {
        val files = gitStatus?.files?.map { it.filePath } ?: emptyList()
        FileTreeState.calculateChangedDirectoryPaths(files, workspaceCwd)
    }
    val gitChangesByPath = remember(gitStatus) {
        gitStatus?.files?.associateBy { it.filePath.replace('\\', '/') } ?: emptyMap()
    }

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            containerColor = theme.background,
            topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = workspaceName.ifBlank { "资源管理器" },
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = workspaceCwd,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = theme.textPrimary
                        )
                    }
                },
                actions = {
                    // 搜索开关
                    IconButton(onClick = {
                        searchOpen = !searchOpen
                        if (!searchOpen) searchQuery = ""
                    }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = if (searchOpen) theme.primary else theme.textMuted
                        )
                    }

                    // 隐藏项开关
                    IconButton(onClick = { showHidden = !showHidden }) {
                        Icon(
                            imageVector = if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                            contentDescription = "显示隐藏文件",
                            tint = if (showHidden) theme.primary else theme.textMuted
                        )
                    }

                    // 刷新
                    IconButton(onClick = { refreshTree() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = theme.textMuted
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = theme.surfaceContainer)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 搜索输入栏
            if (searchOpen) {
                Surface(
                    color = theme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .background(theme.surfaceContainer, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = theme.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            cursorBrush = SolidColor(theme.primary),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = theme.textPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "清除",
                                tint = theme.textMuted,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { searchQuery = "" }
                            )
                        }
                    }
                }
            }

            // Git 变更统计摘要
            gitStatus?.let { gs ->
                if (gs.files.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.surfaceContainerLow)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "${gs.files.size} 项变更",
                            color = theme.textSecondary,
                            fontSize = 11.5.sp
                        )
                        Text(
                            text = "+${gs.additions}",
                            color = theme.success,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "-${gs.deletions}",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 内容区
            when {
                isInitialLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = theme.primary)
                    }
                }
                rootErrorMessage != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = rootErrorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 13.5.sp
                        )
                    }
                }
                searchOpen && searchQuery.trim().isNotEmpty() -> {
                    // 渲染搜索结果树
                    if (searchLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = theme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    } else if (searchResults.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "未找到匹配文件",
                                color = theme.textMuted,
                                fontSize = 13.sp
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            fun renderSearchNode(node: SearchTreeNode, depth: Int) {
                                val fullPath = FileListParsing.joinPath(workspaceCwd, node.path)
                                item(key = "search:$fullPath") {
                                    val entry = FileEntry(name = node.name, isDir = node.isDir)
                                    FileRow(
                                        entry = entry,
                                        depth = depth,
                                        fullPath = fullPath,
                                        isExpanded = true,
                                        isLoading = false,
                                        gitChange = gitChangesByPath[fullPath],
                                        containsGitChanges = changedDirectories.contains(fullPath),
                                        onClick = {
                                            if (!node.isDir) openFilePreview(fullPath)
                                        },
                                        onPreview = { openFilePreview(fullPath) },
                                        onDownload = { onDownloadFile(fullPath) },
                                        onOpenExternal = { onOpenExternal(fullPath) },
                                        onCopyPath = {
                                            clipboardManager.setText(AnnotatedString(fullPath))
                                            Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                        },
                                        onAtMention = {
                                            val rel = FileListParsing.relativePath(fullPath, workspaceCwd)
                                            onAtMention(rel, node.isDir)
                                        }
                                    )
                                }
                                for (child in node.children) {
                                    renderSearchNode(child, depth + 1)
                                }
                            }

                            for (rootNode in searchResults) {
                                renderSearchNode(rootNode, 0)
                            }
                        }
                    }
                }
                else -> {
                    // 递归展开目录树并展平为列表渲染
                    val flattenedItems = remember(
                        childrenMap.toMap(),
                        expandedPaths,
                        loadingPaths.toMap(),
                        errorPaths.toMap(),
                        truncatedPaths.toMap()
                    ) {
                        val list = mutableListOf<FlattenedTreeItem>()

                        fun walk(dirPath: String, depth: Int) {
                            val children = childrenMap[dirPath] ?: return
                            for (entry in children) {
                                val childFullPath = FileListParsing.joinPath(dirPath, entry.name)
                                val isExp = expandedPaths.contains(childFullPath)
                                val isLdg = loadingPaths[childFullPath] == true
                                val isTr = truncatedPaths[childFullPath] == true
                                val err = errorPaths[childFullPath]

                                list.add(
                                    FlattenedTreeItem(
                                        entry = entry,
                                        depth = depth,
                                        fullPath = childFullPath,
                                        isExpanded = isExp,
                                        isLoading = isLdg,
                                        isTruncated = isTr,
                                        errorMessage = err
                                    )
                                )

                                if (entry.isDir && isExp) {
                                    walk(childFullPath, depth + 1)
                                }
                            }
                        }

                        walk(workspaceCwd, 0)
                        list
                    }

                    if (flattenedItems.isEmpty()) {
                        EmptyDirectoryRow(depth = 0)
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(
                                items = flattenedItems,
                                key = { it.fullPath }
                            ) { item ->
                                FileRow(
                                    entry = item.entry,
                                    depth = item.depth,
                                    fullPath = item.fullPath,
                                    isExpanded = item.isExpanded,
                                    isLoading = item.isLoading,
                                    gitChange = gitChangesByPath[item.fullPath],
                                    containsGitChanges = changedDirectories.contains(item.fullPath),
                                    onClick = {
                                        if (item.entry.isDir) {
                                            toggleExpand(item.fullPath)
                                        } else {
                                            openFilePreview(item.fullPath)
                                        }
                                    },
                                    onPreview = { openFilePreview(item.fullPath) },
                                    onDownload = { onDownloadFile(item.fullPath) },
                                    onOpenExternal = { onOpenExternal(item.fullPath) },
                                    onCopyPath = {
                                        clipboardManager.setText(AnnotatedString(item.fullPath))
                                        Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                    },
                                    onAtMention = {
                                        val rel = FileListParsing.relativePath(item.fullPath, workspaceCwd)
                                        onAtMention(rel, item.entry.isDir)
                                    }
                                )

                                if (item.entry.isDir && item.isExpanded) {
                                    if (item.errorMessage != null) {
                                        ErrorDirectoryRow(
                                            depth = item.depth + 1,
                                            errorMessage = item.errorMessage,
                                            onRetry = {
                                                toggleExpand(item.fullPath)
                                                toggleExpand(item.fullPath)
                                            }
                                        )
                                    } else if (childrenMap[item.fullPath]?.isEmpty() == true && !item.isLoading) {
                                        EmptyDirectoryRow(depth = item.depth + 1)
                                    } else if (item.isTruncated) {
                                        TruncatedNoticeRow(depth = item.depth + 1)
                                    }
                                }
                            }

                            if (truncatedPaths[workspaceCwd] == true) {
                                item {
                                    TruncatedNoticeRow(depth = 0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 全屏文件查看器覆盖层（与 Scaffold 平级，覆盖整个屏幕）
    val activePreviewPath = previewPath
    if (activePreviewPath != null) {
        ArtifactViewerFullScreen(
            title = previewTitle,
            path = activePreviewPath,
            meta = previewMeta,
            kind = previewKind,
            text = previewText,
            image = previewImage,
            pdfFile = previewPdfFile,
            docxHtml = previewDocxHtml,
            message = previewMessage,
            rawMode = previewRawMode,
            isLoading = isPreviewLoading,
            isLoadingMore = isPreviewLoadingMore,
            truncated = previewTruncated,
            nextOffset = previewNextOffset,
            sizeBytes = previewSizeBytes,
            isOpeningExternally = false,
            sessionCwd = workspaceCwd,
            onResolveMediaSource = { p -> onResolveFileSource(p) },
            onBack = { previewPath = null },
            onToggleRawMode = { raw -> previewRawMode = raw },
            onLoadMore = { loadMorePreviewText() },
            onOpenExternal = { onOpenExternal(activePreviewPath) },
            onReload = { openFilePreview(activePreviewPath) },
            onOpenLink = { href, baseDir ->
                val resolved = com.pi.app.ui.components.FileLinks.resolveLocalFileHref(href, baseDir)
                if (resolved != null) {
                    openFilePreview(resolved)
                } else if (com.pi.app.ui.components.FileLinks.isWebUrl(href)) {
                    runCatching {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(href)))
                    }
                }
            },
            onCopyText = { value ->
                clipboardManager.setText(AnnotatedString(value))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            },
            onAtMention = { rel ->
                onAtMention(rel, false)
            },
            onDownloadFile = { p ->
                onDownloadFile(p)
            }
        )
    }
}
}
