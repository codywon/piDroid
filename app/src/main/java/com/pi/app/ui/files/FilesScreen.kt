package com.pi.app.ui.files

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.FileDirectoryListing
import com.pi.app.data.model.FileEntry
import com.pi.app.data.model.FileMeta
import com.pi.app.data.model.FilePlaybackSource
import com.pi.app.data.model.FileTextChunk
import com.pi.app.data.model.GitStatusResult
import com.pi.app.data.model.RemoteDirEntry
import com.pi.app.network.FileListParsing
import com.pi.app.ui.chat.ArtifactKind
import com.pi.app.ui.chat.ArtifactViewerFullScreen
import com.pi.app.ui.components.edgeSwipeBack
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 独立全屏移动端文件管理器。
 *
 * 核心交互优化：
 * 1. 采用移动端最佳实践的层级导航式结构（点文件夹进入子目录，无缩进排版挤压）；
 * 2. 左上角返回键及边缘右滑手势：在子目录时平滑返回上一级，在根目录时退出文件管理器；
 * 3. 顶栏显示当前文件夹名称与绝对路径，并支持驱动器盘符（C:、D:、家目录）一键秒切；
 * 4. 具备全局文件搜索（150ms 实时防抖）、隐藏文件切换、长按快捷引用等完整能力。
 */
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
    onLoadDrives: (suspend () -> List<RemoteDirEntry>)? = null,
    onDirectoryChanged: ((String) -> Unit)? = null,
    /**
     * 「浏览电脑文件」模式：起始目录只是**上次记住的位置**，不是边界，
     * 因此可以一直向上退到盘符根（如 `D:/`）才退出页面。
     */
    allowRoam: Boolean = false,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // 当前浏览路径及多级导航历史栈
    var currentPath by rememberSaveable(workspaceCwd) { mutableStateOf(workspaceCwd) }
    val navigationStack = remember { mutableStateListOf<String>() }

    var showHidden by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchTreeNode>>(emptyList()) }

    var isDirectoryLoading by remember { mutableStateOf(true) }
    var directoryErrorMessage by remember { mutableStateOf<String?>(null) }
    var directoryEntries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isTruncated by remember { mutableStateOf(false) }
    var gitStatus by remember { mutableStateOf<GitStatusResult?>(null) }

    // 驱动器盘符菜单状态
    var showDrivesMenu by remember { mutableStateOf(false) }
    var drivesList by remember { mutableStateOf<List<RemoteDirEntry>>(emptyList()) }

    // 预览相关状态
    var previewPath by remember { mutableStateOf<String?>(null) }
    var previewTitle by remember { mutableStateOf("") }
    var previewKind by remember { mutableStateOf(ArtifactKind.TEXT) }
    var previewMeta by remember { mutableStateOf<String?>(null) }
    var previewText by remember { mutableStateOf<String?>(null) }
    var previewImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var previewPdfFile by remember { mutableStateOf<File?>(null) }
    var previewDocxHtml by remember { mutableStateOf<String?>(null) }
    var previewOffice by remember { mutableStateOf<OfficePreview?>(null) }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    var previewRawMode by remember { mutableStateOf(false) }
    var isPreviewLoading by remember { mutableStateOf(false) }
    var isPreviewLoadingMore by remember { mutableStateOf(false) }
    var previewTruncated by remember { mutableStateOf(false) }
    var previewNextOffset by remember { mutableStateOf(0L) }
    var previewSizeBytes by remember { mutableStateOf(0L) }

    // 判断当前是否还能向上退回父级目录。
    // 工作区浏览（allowRoam=false）不得越过工作区；电脑文件浏览（allowRoam=true）
    // 一直可以退到 `parentPath() == null`（盘符根 / POSIX 根 / UNC 共享根）为止。
    val parentOfCurrent = remember(currentPath) { FileListParsing.parentPath(currentPath) }
    val canStepUp = parentOfCurrent != null &&
        (allowRoam || !currentPath.equals(workspaceCwd, ignoreCase = true))
    val canGoUp = navigationStack.isNotEmpty() || canStepUp

    // 统一处理返回操作（预览 -> 搜索 -> 上一级 -> 退出页面）
    fun handleBack() {
        if (previewPath != null) {
            previewPath = null
        } else if (searchOpen) {
            searchOpen = false
            searchQuery = ""
        } else if (navigationStack.isNotEmpty()) {
            val prev = navigationStack.removeAt(navigationStack.lastIndex)
            currentPath = prev
            onDirectoryChanged?.invoke(prev)
        } else if (canStepUp && parentOfCurrent != null) {
            currentPath = parentOfCurrent
            onDirectoryChanged?.invoke(parentOfCurrent)
        } else {
            onBack()
        }
    }

    // 监听系统物理/手势返回键
    BackHandler { handleBack() }

    // 载入当前目录内容
    fun loadCurrentDirectory() {
        isDirectoryLoading = true
        directoryErrorMessage = null
        coroutineScope.launch {
            val gitRes = onLoadGitStatus(currentPath)
            gitStatus = gitRes.getOrNull()

            val dirRes = onLoadDirectory(currentPath, showHidden)
            dirRes.onSuccess { listing ->
                val (capped, truncated) = FileTreeState.filterAndCapEntries(listing.entries, showHidden)
                directoryEntries = capped
                isTruncated = truncated
            }.onFailure { err ->
                directoryErrorMessage = err.message ?: "加载目录失败"
                directoryEntries = emptyList()
            }
            isDirectoryLoading = false
        }
    }

    LaunchedEffect(currentPath, showHidden) {
        loadCurrentDirectory()
    }

    // 搜索输入防抖
    LaunchedEffect(searchQuery, searchOpen) {
        if (!searchOpen || searchQuery.trim().isEmpty()) {
            searchResults = emptyList()
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        delay(150)
        val res = onSearchFiles(currentPath, searchQuery.trim())
        res.onSuccess { matches ->
            val paths = matches.map { it.path }
            searchResults = FileTreeState.buildSearchTree(paths)
        }.onFailure {
            searchResults = emptyList()
        }
        searchLoading = false
    }

    fun openFilePreview(absPath: String) {
        previewPath = absPath
        previewTitle = FileListParsing.displayName(absPath)
        previewText = null
        previewImage = null
        previewPdfFile = null
        previewDocxHtml = null
        previewOffice = null
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
            previewOffice = data.officePreview
            previewMessage = data.message
            previewNextOffset = data.nextOffset
            previewTruncated = data.truncated
            previewSizeBytes = data.sizeBytes
            previewPdfFile = data.pdfFile
            previewText = data.text

            data.imageBytes?.let { bytes ->
                previewImage = decodeDownsampledBitmap(bytes, maxDimension = 2048)
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

    // Git 变更与目录状态映射
    val gitChangesByPath = remember(gitStatus) {
        gitStatus?.files?.associateBy { it.filePath.replace('\\', '/') } ?: emptyMap()
    }
    val changedDirectories = remember(gitStatus, currentPath) {
        val files = gitStatus?.files?.map { it.filePath } ?: emptyList()
        FileTreeState.calculateChangedDirectoryPaths(files, currentPath)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 左边缘右滑返回：挂在根容器上（不叠透明热区），点击事件照常下发给顶栏返回按钮
            .then(if (previewPath == null) Modifier.edgeSwipeBack(onSwipeBack = { handleBack() }) else Modifier)
    ) {
        Scaffold(
            containerColor = theme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            val dirDisplayName = remember(currentPath) {
                                FileListParsing.displayName(currentPath).ifBlank { currentPath }
                            }
                            Text(
                                text = dirDisplayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = currentPath,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = { handleBack() },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (canGoUp) "返回上一级" else "退出文件管理器",
                                tint = theme.textPrimary
                            )
                        }
                    },
                    actions = {
                        // 1. 搜索按钮
                        IconButton(onClick = {
                            searchOpen = !searchOpen
                            if (!searchOpen) searchQuery = ""
                        }) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "搜索当前目录",
                                tint = if (searchOpen) theme.primary else theme.textMuted
                            )
                        }

                        // 2. 磁盘驱动器快速切换菜单
                        if (onLoadDrives != null) {
                            Box {
                                IconButton(onClick = {
                                    coroutineScope.launch {
                                        drivesList = onLoadDrives()
                                        showDrivesMenu = true
                                    }
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Storage,
                                        contentDescription = "切换磁盘",
                                        tint = theme.textMuted
                                    )
                                }
                                DropdownMenu(
                                    expanded = showDrivesMenu,
                                    onDismissRequest = { showDrivesMenu = false },
                                    modifier = Modifier.background(theme.surfaceContainer)
                                ) {
                                    Text(
                                        text = "切换磁盘盘符",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.textMuted,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                    drivesList.forEach { drive ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = drive.name.ifBlank { drive.path },
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 13.sp,
                                                    color = theme.textPrimary
                                                )
                                            },
                                            onClick = {
                                                showDrivesMenu = false
                                                navigationStack.clear()
                                                currentPath = drive.path
                                                onDirectoryChanged?.invoke(drive.path)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // 3. 隐藏文件开关
                        IconButton(onClick = { showHidden = !showHidden }) {
                            Icon(
                                imageVector = if (showHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = "显示隐藏文件",
                                tint = if (showHidden) theme.primary else theme.textMuted
                            )
                        }

                        // 4. 刷新
                        IconButton(onClick = { loadCurrentDirectory() }) {
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
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
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

                // Git 变更统计
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

                // 内容呈现
                when {
                    isDirectoryLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = theme.primary)
                        }
                    }
                    directoryErrorMessage != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = directoryErrorMessage.orEmpty(),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 13.5.sp
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = theme.surfaceContainer,
                                    modifier = Modifier.clickable { loadCurrentDirectory() }
                                ) {
                                    Text("重试", color = theme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    searchOpen && searchQuery.trim().isNotEmpty() -> {
                        if (searchLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = theme.primary, modifier = Modifier.size(24.dp))
                            }
                        } else if (searchResults.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = "未找到匹配文件", color = theme.textMuted, fontSize = 13.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                fun renderSearchNode(node: SearchTreeNode, depth: Int) {
                                    val fullPath = FileListParsing.joinPath(currentPath, node.path)
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
                                                if (node.isDir) {
                                                    navigationStack.add(currentPath)
                                                    currentPath = fullPath
                                                    searchOpen = false
                                                    searchQuery = ""
                                                    onDirectoryChanged?.invoke(fullPath)
                                                } else {
                                                    openFilePreview(fullPath)
                                                }
                                            },
                                            onPreview = { openFilePreview(fullPath) },
                                            onDownload = { onDownloadFile(fullPath) },
                                            onOpenExternal = { onOpenExternal(fullPath) },
                                            onCopyPath = {
                                                clipboardManager.setText(AnnotatedString(fullPath))
                                                Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                            },
                                            onAtMention = {
                                                val rel = FileListParsing.relativePath(fullPath, currentPath)
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
                        // 单级层级浏览视图（干净清爽，杜绝多层深缩进）
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            // 1. 若有上一级，列表顶首展示 [.. 返回上一级]
                            if (canGoUp && parentOfCurrent != null) {
                                item(key = ".._parent_up") {
                                    ParentDirectoryRow(
                                        parentPath = parentOfCurrent,
                                        onClick = { handleBack() }
                                    )
                                }
                            }

                            // 2. 文件夹和文件列表
                            if (directoryEntries.isEmpty()) {
                                item(key = "empty_dir_notice") {
                                    EmptyDirectoryRow(depth = 0)
                                }
                            } else {
                                items(
                                    items = directoryEntries,
                                    key = { it.name }
                                ) { entry ->
                                    val fullPath = FileListParsing.joinPath(currentPath, entry.name)
                                    FileRow(
                                        entry = entry,
                                        depth = 0,
                                        fullPath = fullPath,
                                        isExpanded = false,
                                        isLoading = false,
                                        gitChange = gitChangesByPath[fullPath],
                                        containsGitChanges = changedDirectories.contains(fullPath),
                                        onClick = {
                                            if (entry.isDir) {
                                                // 导航进入子目录！
                                                navigationStack.add(currentPath)
                                                currentPath = fullPath
                                                onDirectoryChanged?.invoke(fullPath)
                                            } else {
                                                openFilePreview(fullPath)
                                            }
                                        },
                                        onPreview = { openFilePreview(fullPath) },
                                        onDownload = { onDownloadFile(fullPath) },
                                        onOpenExternal = { onOpenExternal(fullPath) },
                                        onCopyPath = {
                                            clipboardManager.setText(AnnotatedString(fullPath))
                                            Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
                                        },
                                        onAtMention = {
                                            val rel = FileListParsing.relativePath(fullPath, currentPath)
                                            onAtMention(rel, entry.isDir)
                                        }
                                    )
                                }
                            }

                            if (isTruncated) {
                                item(key = "truncated_notice") {
                                    TruncatedNoticeRow(depth = 0)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

        // 全屏文件查看器覆盖层（与 Scaffold 平级覆盖整个屏幕）
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
                officePreview = previewOffice,
                message = previewMessage,
                rawMode = previewRawMode,
                isLoading = isPreviewLoading,
                isLoadingMore = isPreviewLoadingMore,
                truncated = previewTruncated,
                nextOffset = previewNextOffset,
                sizeBytes = previewSizeBytes,
                isOpeningExternally = false,
                sessionCwd = currentPath,
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

/**
 * 列表顶部 ".. (返回上一级)" 导航行项。
 */
@Composable
internal fun ParentDirectoryRow(
    parentPath: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    Surface(
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = null,
                tint = theme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = ".. (返回上一级)",
                color = theme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            val parentName = FileListParsing.displayName(parentPath).ifBlank { parentPath }
            Text(
                text = parentName,
                color = theme.textMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 按最长边降采样解码图片。
 *
 * 4K PNG（6.1MB）直接 `decodeByteArray` 会产出一张 4096×4096 的 ARGB_8888 位图，
 * 即 67MB 堆内存 —— 在手机上大概率 OOM，表现出来就是「图片打不开」。
 * 这里先读尺寸、按 2 的幂次降采样到 [maxDimension] 以内再真正解码。
 */
private fun decodeDownsampledBitmap(bytes: ByteArray, maxDimension: Int): ImageBitmap? {
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest > 0 && longest / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    }.getOrNull()
}
