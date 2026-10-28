package com.pi.app.ui.git

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.GitFileChange
import com.pi.app.data.model.GitStatusResult
import com.pi.app.data.storage.AppPreferences
import com.pi.app.ui.components.FileLinks
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.components.SvgWebViewCanvas
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.launch

/**
 * 详情页模式：
 * DIFF: 查看差异补丁
 * SOURCE: 查看完整修改后的源码文本
 * PREVIEW: 若为 Markdown 或 SVG，直接渲染可视化结果
 */
enum class GitDetailMode { DIFF, SOURCE, PREVIEW }

/** 顶栏增删统计徽标：超过 999 行折叠为 999+，防止窄屏顶栏被大数字挤爆。 */
private fun formatDiffStat(count: Int): String = if (count > 999) "999+" else count.toString()

/**
 * 工作目录 Git 代码变更独立全屏阅读界面（对标 pi-web 移动端全视口文件与变更面板）。
 *
 * 核心设计：
 * 1. 独立全屏视野（无 Drawer/BottomSheet 截断与下滑冲突）；
 * 2. 两层层级：变更文件列表 <-> 单文件详情（系统返回键逐层后退，保留列表滚动位置）；
 * 3. 结构化 Diff 显示（行号、色块、增删条指示、横向滚动/软换行切换）；
 * 4. 详情页支持在同一个全屏视口内无缝切换「差异 / 源码 / 预览」，不再叠套狭窄弹层；
 * 5. 请求序列号与防乱序防护，避免快速切文件时旧请求覆写；
 * 6. 区分网络故障重试、非 Git 仓库提示与工作目录干净状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitChangesFullScreen(
    cwd: String,
    onBack: () -> Unit,
    onLoadGitStatus: suspend () -> Result<GitStatusResult>,
    onLoadGitDiff: suspend (String) -> Result<com.pi.app.data.model.GitDiffResult>,
    onLoadFileText: suspend (String) -> Result<String>,
    onOpenLinkTarget: (String, String) -> Unit = { _, _ -> }
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 状态与加载
    var statusResult by remember { mutableStateOf<GitStatusResult?>(null) }
    var statusError by remember { mutableStateOf<String?>(null) }
    var isLoadingStatus by remember { mutableStateOf(false) }

    // 当前选中的文件（为 null 表示在变更文件列表）
    var selectedFile by remember { mutableStateOf<GitFileChange?>(null) }
    val fileListState = rememberLazyListState()

    // 刷新 Git 状态
    fun refreshStatus() {
        isLoadingStatus = true
        statusError = null
        coroutineScope.launch {
            onLoadGitStatus()
                .onSuccess {
                    statusResult = it
                    statusError = null
                }
                .onFailure {
                    val msg = it.message ?: "读取 Git 状态失败"
                    statusError = msg
                    if (statusResult != null) {
                        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            isLoadingStatus = false
        }
    }

    LaunchedEffect(cwd) {
        refreshStatus()
    }

    // 后退键处理：优先从文件详情返回列表，列表再退出全屏
    BackHandler(enabled = true) {
        if (selectedFile != null) {
            selectedFile = null
        } else {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (selectedFile != null) {
                                selectedFile!!.filePath.substringAfterLast('/').substringAfterLast('\\')
                            } else {
                                "工作目录变更"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val repoRoot = statusResult?.repositoryRoot ?: cwd
                        val normRoot = repoRoot.replace('\\', '/').trimEnd('/')
                        Text(
                            text = if (selectedFile != null) {
                                val normPath = selectedFile!!.filePath.replace('\\', '/')
                                val rel = if (normPath.startsWith(normRoot, ignoreCase = true)) {
                                    normPath.substring(normRoot.length).trimStart('/')
                                } else {
                                    normPath.trimStart('/')
                                }
                                rel
                            } else {
                                normRoot
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 10.sp,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedFile != null) {
                            selectedFile = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = theme.textPrimary
                        )
                    }
                },
                actions = {
                    if (selectedFile == null) {
                        IconButton(onClick = { refreshStatus() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "刷新变更",
                                tint = theme.textSecondary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background
                )
            )
        },
        containerColor = theme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (selectedFile != null) {
                // ── 单文件 Diff / 源码 / 预览 详情页 ──
                GitFileDetailView(
                    file = selectedFile!!,
                    repoRoot = statusResult?.repositoryRoot ?: cwd,
                    onLoadGitDiff = onLoadGitDiff,
                    onLoadFileText = onLoadFileText,
                    onOpenLinkTarget = onOpenLinkTarget
                )
            } else {
                // ── 工作目录变更文件列表 ──
                GitFileListContent(
                    status = statusResult,
                    error = statusError,
                    isLoading = isLoadingStatus,
                    listState = fileListState,
                    onRetry = { refreshStatus() },
                    onSelectFile = { file -> selectedFile = file }
                )
            }
        }
    }
}

/**
 * 列表视图内容
 */
@Composable
private fun GitFileListContent(
    status: GitStatusResult?,
    error: String?,
    isLoading: Boolean,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onRetry: () -> Unit,
    onSelectFile: (GitFileChange) -> Unit
) {
    val theme = LocalAppTheme.current

    when {
        isLoading && status == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = theme.primary)
                    Text("正在同步工作目录 Git 状态…", fontSize = 12.sp, color = theme.textMuted)
                }
            }
        }
        error != null && status == null -> {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFCF222E), modifier = Modifier.size(32.dp))
                    Text(text = "读取 Git 状态失败", fontWeight = FontWeight.Bold, color = theme.textPrimary)
                    Text(text = error, fontSize = 11.5.sp, color = theme.textMuted)
                    Button(onClick = onRetry) {
                        Text("重试")
                    }
                }
            }
        }
        status == null || !status.isGitRepository -> {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.FolderOff, contentDescription = null, tint = theme.textMuted, modifier = Modifier.size(32.dp))
                    Text("当前工作目录不是 Git 仓库", fontWeight = FontWeight.SemiBold, color = theme.textPrimary)
                    Text("请确保打开了包含 .git 的项目目录", fontSize = 11.sp, color = theme.textMuted)
                }
            }
        }
        status.files.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.CheckCircleOutline, contentDescription = null, tint = Color(0xFF2EA043), modifier = Modifier.size(36.dp))
                    Text("工作目录很干净", fontWeight = FontWeight.Bold, color = theme.textPrimary)
                    Text("没有未提交的代码改动", fontSize = 11.5.sp, color = theme.textMuted)
                }
            }
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 统计条（对标 pi-web 的 CHANGES (N) +adds -dels）
                item(key = "header_stats") {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "共 ${status.files.size} 个文件变更",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.textPrimary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    text = "+${status.additions}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2EA043)
                                )
                                Text(
                                    text = "-${status.deletions}",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCF222E)
                                )
                            }
                        }
                    }
                }

                // 文件卡片列表
                items(status.files, key = { it.filePath }) { file ->
                    GitFileRowItem(
                        file = file,
                        repoRoot = status.repositoryRoot,
                        onClick = { onSelectFile(file) }
                    )
                }
            }
        }
    }
}

/**
 * 列表单项行
 */
@Composable
private fun GitFileRowItem(
    file: GitFileChange,
    repoRoot: String,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    val isDeleted = file.code.contains('D') || file.status.equals("deleted", ignoreCase = true)
    val isAdded = file.code.contains('A') || file.code.contains('?') ||
        file.status.equals("added", ignoreCase = true) || file.status.equals("untracked", ignoreCase = true)

    val badgeColor = when {
        isDeleted -> Color(0xFFCF222E)
        isAdded -> Color(0xFF2EA043)
        else -> Color(0xFFD29922)
    }

    val normPath = file.filePath.replace('\\', '/')
    val normRoot = repoRoot.replace('\\', '/').trimEnd('/')
    val relPath = if (normPath.startsWith(normRoot, ignoreCase = true)) {
        normPath.substring(normRoot.length).trimStart('/')
    } else {
        normPath.trimStart('/')
    }
    val lastSlash = relPath.lastIndexOf('/')
    val dirPart = if (lastSlash >= 0) relPath.substring(0, lastSlash + 1) else ""
    val namePart = if (lastSlash >= 0) relPath.substring(lastSlash + 1) else relPath

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 状态徽章（对标 pi-web GitStatusBadge）
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = badgeColor.copy(alpha = 0.15f),
                border = BorderStroke(0.5.dp, badgeColor.copy(alpha = 0.4f)),
                modifier = Modifier.size(24.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = (file.code.ifBlank { file.status }).take(2),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }

            // 文件名与目录路径分层（目录半透明淡化，文件名加粗）
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = namePart,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (dirPart.isNotBlank()) {
                    Text(
                        text = dirPart,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = theme.textMuted.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 单文件详情视图（差异 / 源码 / 预览 无缝切换）
 */
@Composable
private fun GitFileDetailView(
    file: GitFileChange,
    repoRoot: String,
    onLoadGitDiff: suspend (String) -> Result<com.pi.app.data.model.GitDiffResult>,
    onLoadFileText: suspend (String) -> Result<String>,
    onOpenLinkTarget: (String, String) -> Unit
) {
    val theme = LocalAppTheme.current
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }

    var mode by remember { mutableStateOf(GitDetailMode.DIFF) }
    var softWrap by remember { mutableStateOf(prefs.gitDiffSoftWrap) }

    // Diff 数据
    var patchText by remember { mutableStateOf<String?>(null) }
    var isSupported by remember { mutableStateOf(true) }
    var diffError by remember { mutableStateOf<String?>(null) }
    var isLoadingDiff by remember { mutableStateOf(false) }

    // 源码数据
    var sourceText by remember { mutableStateOf<String?>(null) }
    var sourceError by remember { mutableStateOf<String?>(null) }
    var isLoadingSource by remember { mutableStateOf(false) }

    // 请求序号防乱序
    var currentRequestSeq by remember { mutableIntStateOf(0) }

    val isDeleted = file.code.contains('D') || file.status.equals("deleted", ignoreCase = true)
    val isMarkdown = remember(file.filePath) { FileLinks.isMarkdownPath(file.filePath) }
    val isSvg = remember(file.filePath) { file.filePath.endsWith(".svg", ignoreCase = true) }
    val canPreview = !isDeleted && (isMarkdown || isSvg)

    // 加载 Diff
    fun loadDiff() {
        val reqId = ++currentRequestSeq
        isLoadingDiff = true
        diffError = null
        coroutineScope.launch {
            onLoadGitDiff(file.filePath)
                .onSuccess { res ->
                    if (reqId == currentRequestSeq) {
                        isSupported = res.supported
                        patchText = res.patch
                        diffError = null
                    }
                }
                .onFailure { err ->
                    if (reqId == currentRequestSeq) {
                        diffError = err.message ?: "读取 Diff 失败"
                    }
                }
            if (reqId == currentRequestSeq) {
                isLoadingDiff = false
            }
        }
    }

    // 加载完整源码
    fun loadSource() {
        if (sourceText != null) return
        isLoadingSource = true
        sourceError = null
        coroutineScope.launch {
            onLoadFileText(file.filePath)
                .onSuccess {
                    sourceText = it
                    sourceError = null
                }
                .onFailure {
                    sourceError = it.message ?: "读取源码失败"
                }
            isLoadingSource = false
        }
    }

    LaunchedEffect(file.filePath) {
        sourceText = null
        sourceError = null
        loadDiff()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 模式切换条 + 软换行开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 模式选择分段药丸按钮
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = theme.surfaceContainer,
                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f))
            ) {
                Row(modifier = Modifier.padding(2.dp)) {
                    val modes = buildList {
                        add(GitDetailMode.DIFF to "差异")
                        // 已删除文件在工作区已无实体内容，对齐 pi-web 锁定仅展示删除 Diff
                        if (!isDeleted) {
                            add(GitDetailMode.SOURCE to "源码")
                            if (canPreview) add(GitDetailMode.PREVIEW to "预览")
                        }
                    }

                modes.forEach { item ->
                    val m = item.first
                    val label = item.second
                    val selected = mode == m
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) theme.primary.copy(alpha = 0.16f) else Color.Transparent,
                        border = BorderStroke(
                            0.6.dp,
                            if (selected) theme.primary.copy(alpha = 0.45f) else Color.Transparent
                        ),
                        modifier = Modifier.clickable {
                            mode = m
                            if (m == GitDetailMode.SOURCE || m == GitDetailMode.PREVIEW) {
                                loadSource()
                            }
                        }
                    ) {
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) theme.primary else theme.textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
                }
            }

            // 右侧信息区：变更规模统计（+N -N）+ 软换行开关（对标 GitHub Mobile 规范）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mode == GitDetailMode.DIFF && !patchText.isNullOrBlank()) {
                    val stats = remember(patchText) {
                        var adds = 0
                        var dels = 0
                        patchText.orEmpty().lineSequence().forEach { raw ->
                            // 精确排除 "+++ b/..." 与 "--- a/..." 文件头，避免统计多 1 行；
                            // 用 lineSequence 避免为长补丁额外分配整份行列表
                            when {
                                raw.startsWith("+++ ") || raw.startsWith("--- ") -> Unit
                                raw.startsWith("+") -> adds++
                                raw.startsWith("-") -> dels++
                            }
                        }
                        adds to dels
                    }
                    if (stats.first > 0 || stats.second > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (stats.first > 0) {
                                Text(
                                    text = "+${formatDiffStat(stats.first)}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2EA043),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            if (stats.first > 0 && stats.second > 0) {
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            if (stats.second > 0) {
                                Text(
                                    text = "-${formatDiffStat(stats.second)}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCF222E),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .size(3.dp)
                                .background(theme.textMuted.copy(alpha = 0.4f), RoundedCornerShape(1.5.dp))
                        )
                    }
                }

                // 软换行开关（仅在差异与源码模式有效）
                if (mode != GitDetailMode.PREVIEW) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            val next = !softWrap
                            softWrap = next
                            prefs.gitDiffSoftWrap = next
                        }
                    ) {
                        Icon(
                            imageVector = if (softWrap) Icons.Default.WrapText else Icons.Default.Notes,
                            contentDescription = null,
                            tint = if (softWrap) theme.primary else theme.textMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (softWrap) "已自动换行" else "单行滚动",
                            fontSize = 10.5.sp,
                            color = if (softWrap) theme.primary else theme.textMuted
                        )
                    }
                }
            }
        }

        // 主体视口
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                GitDetailMode.DIFF -> {
                    when {
                        isLoadingDiff -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 1.8.dp, color = theme.primary)
                            }
                        }
                        diffError != null -> {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("加载 Diff 失败", fontWeight = FontWeight.Bold, color = Color(0xFFCF222E), fontSize = 12.sp)
                                    Text(diffError!!, fontSize = 11.sp, color = theme.textMuted)
                                    Button(onClick = { loadDiff() }) { Text("重试") }
                                }
                            }
                        }
                        !isSupported -> {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("该文件不支持展示文本 Diff", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 12.sp)
                                    Text("可能是二进制文件、文件超过大小上限或仅涉及权限变更", fontSize = 10.5.sp, color = theme.textMuted)
                                    Button(onClick = {
                                        mode = GitDetailMode.SOURCE
                                        loadSource()
                                    }) {
                                        Text("尝试直接查看完整文件")
                                    }
                                }
                            }
                        }
                        patchText.isNullOrBlank() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("没有可显示的差异内容", fontSize = 11.5.sp, color = theme.textMuted)
                            }
                        }
                        else -> {
                            StructuredDiffView(
                                patchText = patchText!!,
                                modifier = Modifier.fillMaxSize(),
                                softWrap = softWrap
                            )
                        }
                    }
                }
                GitDetailMode.SOURCE -> {
                    when {
                        isLoadingSource -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 1.8.dp, color = theme.primary)
                            }
                        }
                        sourceError != null -> {
                            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("读取文件内容失败", fontWeight = FontWeight.Bold, color = Color(0xFFCF222E), fontSize = 12.sp)
                                    Text(sourceError!!, fontSize = 11.sp, color = theme.textMuted)
                                    Button(onClick = { loadSource() }) { Text("重试") }
                                }
                            }
                        }
                        sourceText != null -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                val scrollState = rememberScrollState()
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .then(if (!softWrap) Modifier.horizontalScroll(scrollState) else Modifier)
                                        .padding(10.dp)
                                ) {
                                    item {
                                        Text(
                                            text = sourceText!!,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = theme.textPrimary,
                                            softWrap = softWrap
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                GitDetailMode.PREVIEW -> {
                    when {
                        isLoadingSource -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 1.8.dp, color = theme.primary)
                            }
                        }
                        sourceText != null -> {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (isSvg) {
                                    SvgWebViewCanvas(
                                        svgContent = sourceText!!,
                                        enableZoom = true,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                                        item {
                                            MarkdownRenderer(
                                                content = sourceText!!,
                                                modifier = Modifier.fillMaxWidth(),
                                                isUser = false,
                                                onOpenLink = { href ->
                                                    val docDir = FileLinks.parentDirectory(file.filePath)
                                                    val targetBase = if (docDir.isNotBlank()) docDir else repoRoot
                                                    onOpenLinkTarget(href, targetBase)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
