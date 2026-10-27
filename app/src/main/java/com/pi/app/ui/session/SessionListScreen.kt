package com.pi.app.ui.session

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.RemoteBrowseData
import com.pi.app.data.model.RemoteDirEntry
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import com.pi.app.data.ServerDefaults
import com.pi.app.data.storage.AppPreferences
import com.pi.app.data.model.Session
import com.pi.app.data.model.WorkspaceInfo
import com.pi.app.ui.settings.ServerConfigDialog
import com.pi.app.ui.theme.*
import kotlinx.coroutines.launch

const val PINNED_WORKSPACE_KEY = "__global_pinned__"

private enum class SessionStatusFilter {
    ALL,
    RUNNING,
    COMPLETED
}

/**
 * 1:1 还原 pi-web SidebarIcons.tsx 中的 SpinnerIcon 动态旋转圆弧图标
 */
@Composable
fun PiWebRunningSpinner(
    size: Dp = 14.dp,
    color: Color = Color(0xFF16A34A),
    strokeWidth: Dp = 2.2.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pi_web_spinner")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spin_angle"
    )
    Canvas(
        modifier = Modifier
            .size(size)
            .rotate(angle)
    ) {
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 250f,
            useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    deviceName: String,
    serverUrl: String,
    isConfigured: Boolean,
    isGatewayOnline: Boolean = true,
    sessions: List<Session>,
    isLoading: Boolean,
    pinnedSessionIds: Set<String> = emptySet(),
    archivedSessionIds: Set<String> = emptySet(),
    customTitles: Map<String, String> = emptyMap(),
    onTogglePinSession: (String) -> Unit = {},
    onToggleArchiveSession: (String) -> Unit = {},
    /** 批量归档（一次提交全部 id 到 pi-web 的 ui-state） */
    onBulkArchive: (List<String>) -> Unit = {},
    /** pi-web 里的项目（工作区）置顶 key 集合（小写绝对路径） */
    serverPinnedWorkspaceKeys: Set<String> = emptySet(),
    /** pi-web 里的项目排序（小写绝对路径） */
    serverWorkspaceOrder: List<String> = emptyList(),
    /** 项目置顶切换：(工作区路径, 展示用根路径) */
    onTogglePinWorkspace: (path: String, root: String) -> Unit = { _, _ -> },
    /** 项目排序：(被移动的项目路径, 锚点项目路径) */
    onMoveWorkspace: (path: String, anchorPath: String) -> Unit = { _, _ -> },
    onRenameSession: (sessionId: String, newTitle: String) -> Unit = { _, _ -> },
    onForkSession: (Session) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onRefresh: () -> Unit,
    onSessionClick: (Session) -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewSessionClick: (workspacePath: String) -> Unit,
    onManualConfigConfirm: (url: String, token: String, name: String) -> Unit = { _, _, _ -> },
    customWorkspaces: List<String> = emptyList(),
    onBrowseRemoteDir: suspend (String) -> RemoteBrowseData? = { null },
    onAddWorkspace: suspend (targetPath: String, newFolderName: String?, createIfMissing: Boolean) -> Result<String> = { p, _, _ -> Result.success(p) }
) {
    val context = LocalContext.current
    val prefs = remember(context) { AppPreferences(context) }
    val theme = LocalAppTheme.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var showManualDialog by remember { mutableStateOf(false) }
    var showAddWorkspaceDialog by remember { mutableStateOf(false) }
    var selectedMenuWorkspace by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var showRenameWsDialog by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renameWsInput by remember { mutableStateOf("") }
    var showDeleteAllSessionsDialog by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var showArchivedSessionsDialog by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var pinnedWorkspaces by remember { mutableStateOf(prefs.getPinnedWorkspaces()) }
    var wsOrder by remember { mutableStateOf(prefs.getWorkspaceOrder()) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SessionStatusFilter.ALL) }
    var renamingSession by remember { mutableStateOf<Session?>(null) }
    var renameInputText by remember { mutableStateOf("") }
    var deletingSession by remember { mutableStateOf<Session?>(null) }
    var isArchivedExpanded by remember { mutableStateOf(false) }

    // 按工作区项目路径归组，并融合用户手动添加/创建的远程工作区目录
    val workspaces = remember(sessions, customWorkspaces) {
        val map = sessions.groupBy { it.workspacePath }.toMutableMap()
        customWorkspaces.forEach { customPath ->
            val trimmed = customPath.trim()
            if (trimmed.isNotBlank() && !map.containsKey(trimmed)) {
                map[trimmed] = emptyList()
            }
        }
        map.map { (path, list) ->
            val first = list.firstOrNull()
            val name = first?.workspaceName ?: path.trimEnd('/', '\\').substringAfterLast('\\').substringAfterLast('/')
            WorkspaceInfo(
                path = path,
                name = name.ifBlank { "默认项目" },
                sessionCount = list.size,
                runningCount = list.count { it.isLive },
                awaitingCount = list.count { it.awaitingInput }
            )
        }.sortedWith(
            compareByDescending<WorkspaceInfo> { it.runningCount }
                .thenByDescending { it.sessionCount }
        )
    }

    var selectedWorkspacePath by remember(workspaces) {
        mutableStateOf(workspaces.firstOrNull()?.path ?: "")
    }

    val isPinnedWorkspaceSelected = selectedWorkspacePath == PINNED_WORKSPACE_KEY

    // 当前选定工作区下的任务列表（若是全局置顶专区，则跨工作区聚合所有已置顶任务）
    val currentWorkspaceSessions = remember(sessions, selectedWorkspacePath, pinnedSessionIds) {
        val base = when {
            selectedWorkspacePath == PINNED_WORKSPACE_KEY -> {
                sessions.filter { pinnedSessionIds.contains(it.id) }
            }
            selectedWorkspacePath.isBlank() -> {
                sessions
            }
            else -> {
                sessions.filter { it.workspacePath == selectedWorkspacePath }
            }
        }
        base.sortedWith(
            compareByDescending<Session> { it.awaitingInput }
                .thenByDescending { it.isLive }
                .thenByDescending { it.modified ?: it.created ?: "" }
        )
    }

    val runningInWorkspace = remember(currentWorkspaceSessions) {
        currentWorkspaceSessions.count { it.isLive || it.awaitingInput }
    }
    val completedInWorkspace = remember(currentWorkspaceSessions, runningInWorkspace) {
        (currentWorkspaceSessions.size - runningInWorkspace).coerceAtLeast(0)
    }

    val filteredSessions = remember(currentWorkspaceSessions, statusFilter, searchQuery, customTitles) {
        currentWorkspaceSessions.filter { s ->
            val matchesStatus = when (statusFilter) {
                SessionStatusFilter.ALL -> true
                SessionStatusFilter.RUNNING -> s.isLive || s.awaitingInput
                SessionStatusFilter.COMPLETED -> !s.isLive && !s.awaitingInput
            }
            val q = searchQuery.trim()
            val cTitle = customTitles[s.id] ?: ""
            val matchesQuery = q.isEmpty() ||
                s.displayTitle.contains(q, ignoreCase = true) ||
                cTitle.contains(q, ignoreCase = true) ||
                (s.firstMessage?.contains(q, ignoreCase = true) == true) ||
                s.workspaceName.contains(q, ignoreCase = true)
            matchesStatus && matchesQuery
        }
    }

    val currentWorkspaceName = remember(workspaces, selectedWorkspacePath) {
        if (selectedWorkspacePath == PINNED_WORKSPACE_KEY) {
            "🌟 常用置顶"
        } else {
            workspaces.firstOrNull { it.path == selectedWorkspacePath }?.displayName ?: "全部工作区"
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = isConfigured && workspaces.isNotEmpty(),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = theme.surfaceContainerLow,
                drawerContentColor = theme.textPrimary,
                modifier = Modifier.width(305.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    // 🌟 顶栏：标题 + 右侧新增项目 [+] 小图标 + 关闭按钮 (释放顶部宝贵高度)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "工作区项目",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Text(
                                text = "共 ${workspaces.size} 个活动目录 · ${sessions.count { it.isLive }} 个运行中",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { showAddWorkspaceDialog = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "新建/添加项目", tint = theme.primary, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                            IconButton(
                                onClick = { coroutineScope.launch { drawerState.close() } },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "关闭", tint = theme.textSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val globalPinnedCount = remember(sessions, pinnedSessionIds) {
                        sessions.count { pinnedSessionIds.contains(it.id) }
                    }

                    // 置顶与排序以 pi-web 的 ui-state 为准；取不到时才回退到本地偏好
                    fun wsKey(path: String) = path.replace('\\', '/').trimEnd('/').lowercase()
                    val pinnedKeys = remember(serverPinnedWorkspaceKeys, pinnedWorkspaces, workspaces) {
                        if (serverPinnedWorkspaceKeys.isNotEmpty() || serverWorkspaceOrder.isNotEmpty()) {
                            serverPinnedWorkspaceKeys
                        } else {
                            pinnedWorkspaces.map { wsKey(it) }.toSet()
                        }
                    }
                    val orderList = remember(serverWorkspaceOrder, wsOrder, workspaces) {
                        if (serverWorkspaceOrder.isNotEmpty()) serverWorkspaceOrder else wsOrder.map { wsKey(it) }
                    }
                    val sortedWorkspaces = remember(workspaces, pinnedKeys, orderList) {
                        workspaces.sortedWith(
                            compareByDescending<WorkspaceInfo> { pinnedKeys.contains(wsKey(it.path)) }
                                .thenBy { ws ->
                                    val idx = orderList.indexOf(wsKey(ws.path))
                                    if (idx != -1) idx else Int.MAX_VALUE
                                }
                        )
                    }

                    Text(
                        text = "项目工作区 (${workspaces.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 🌟 常用置顶（永远排在工作区列表第 1 项，与常规工作区尺寸风格完全一致）
                        item(key = "workspace_pinned_item") {
                            val isPinnedSelected = selectedWorkspacePath == PINNED_WORKSPACE_KEY
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isPinnedSelected) theme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                border = if (isPinnedSelected) BorderStroke(1.dp, theme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedWorkspacePath = PINNED_WORKSPACE_KEY
                                        coroutineScope.launch { drawerState.close() }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isPinnedSelected) Color(0xFFEAB308) else theme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "常用置顶",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isPinnedSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isPinnedSelected) theme.textPrimary else theme.textSecondary
                                        )
                                        Text(
                                            text = "跨工作区聚合置顶",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = theme.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (sessions.any { pinnedSessionIds.contains(it.id) && it.isLive }) {
                                        PiWebRunningSpinner(size = 12.dp)
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = if (isPinnedSelected) Color(0xFFEAB308).copy(alpha = 0.22f) else theme.surfaceContainerHigh,
                                        modifier = Modifier.padding(start = 4.dp)
                                    ) {
                                        Text(
                                            text = "$globalPinnedCount",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isPinnedSelected) Color(0xFFEAB308) else theme.textSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }



                        items(sortedWorkspaces, key = { it.path }) { ws ->
                            val isSelected = ws.path == selectedWorkspacePath
                            val isPinned = pinnedKeys.contains(wsKey(ws.path))
                            val customName = prefs.getWorkspaceCustomName(ws.path)
                            val displayName = customName ?: ws.displayName

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) theme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, theme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            selectedWorkspacePath = ws.path
                                            coroutineScope.launch { drawerState.close() }
                                        },
                                        onLongClick = {
                                            selectedMenuWorkspace = ws
                                        }
                                    )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isPinned) Icons.Default.PushPin else Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isPinned) Color(0xFFEAB308) else (if (isSelected) theme.primary else theme.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = displayName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) theme.primary else theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = ws.path.takeLast(32),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 11.sp,
                                            color = theme.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // 对标 pi-web SessionTree ActivitySummary：工作区右侧展示动态旋转图标 + 运行数
                                    if (ws.runningCount > 0) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color(0xFF22C55E).copy(alpha = 0.15f),
                                            border = BorderStroke(0.8.dp, Color(0xFF22C55E).copy(alpha = 0.4f))
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                PiWebRunningSpinner(size = 10.dp, color = Color(0xFF16A34A), strokeWidth = 1.8.dp)
                                                Text(
                                                    text = "${ws.runningCount}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF16A34A)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }

                                    Surface(
                                        shape = CircleShape,
                                        color = if (isSelected) theme.primary else theme.surfaceContainerHigh
                                    ) {
                                        Text(
                                            text = "${ws.sessionCount}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) Color.White else theme.textSecondary,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                        )
                                    }

                                    // 快捷操作图标 (点击也可直接呼出项目维护菜单)
                                    IconButton(
                                        onClick = { selectedMenuWorkspace = ws },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "项目操作",
                                            modifier = Modifier.size(15.dp),
                                            tint = theme.textMuted
                                        )
                                    }
                                }
                            }
                        }

                        // 🌟 底部常驻轻量入口：打开 / 添加其他项目 (1:1 对标 pi-web 底部架构)
                        item(key = "workspace_add_bottom_item") {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = Color.Transparent,
                                border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.6f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showAddWorkspaceDialog = true }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        Icons.Default.CreateNewFolder,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = theme.primary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "打开 / 添加其他项目...",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = theme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = theme.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = theme.background,
                        titleContentColor = theme.textPrimary
                    ),
                    navigationIcon = {
                        if (isConfigured && workspaces.isNotEmpty()) {
                            IconButton(onClick = { coroutineScope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "工作区抽屉", tint = theme.primary)
                            }
                        }
                    },
                    title = {
                        Column(
                            modifier = if (isConfigured && workspaces.isNotEmpty()) {
                                Modifier.clickable { coroutineScope.launch { drawerState.open() } }
                            } else Modifier
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isConfigured) currentWorkspaceName else "piDroid",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = theme.textPrimary
                                )
                                if (isConfigured && workspaces.isNotEmpty()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "切换工作区",
                                        modifier = Modifier.size(18.dp),
                                        tint = theme.textSecondary
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val dotColor = when {
                                    !isConfigured -> theme.textMuted
                                    isGatewayOnline -> Color(0xFF2EA043)
                                    else -> Color(0xFFD29922)
                                }
                                Surface(
                                    modifier = Modifier.size(6.dp),
                                    shape = CircleShape,
                                    color = dotColor
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = when {
                                        !isConfigured -> "未连接主机网关"
                                        isGatewayOnline -> "${deviceName.ifEmpty { "已连接主机" }} · 在线"
                                        else -> "${deviceName.ifEmpty { "主机网关" }} · 自动重连中..."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (!isConfigured || isGatewayOnline) theme.textSecondary else Color(0xFFD29922)
                                )
                            }
                        }
                    },
                    actions = {
                        if (isConfigured) {
                            // 添加 / 新建项目目录入口保留在左侧工作区抽屉里（顶部横幅 [+] 与底部
                            // 「打开 / 添加其他项目...」两处），顶栏不再放这个图标，避免列表页误触。
                            IconButton(onClick = onRefresh, enabled = !isLoading) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = theme.primary
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "同步会话与连接状态", tint = theme.textSecondary)
                                }
                            }
                        }
                        IconButton(onClick = onScanClick) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码配对", tint = theme.primary)
                        }
                        IconButton(onClick = onSettingsClick) {
                            Icon(Icons.Default.Settings, contentDescription = "系统设置中心", tint = theme.textPrimary)
                        }
                    }
                )
            },
            floatingActionButton = {
                if (isConfigured) {
                    FloatingActionButton(
                        onClick = {
                            val targetPath = if (selectedWorkspacePath == PINNED_WORKSPACE_KEY) {
                                workspaces.firstOrNull()?.path ?: ""
                            } else {
                                selectedWorkspacePath
                            }
                            onNewSessionClick(targetPath)
                        },
                        containerColor = theme.primary,
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "新建任务",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (!isConfigured) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "连接您 PC 上的网关",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary
                                )
                                Text(
                                    text = "支持相机扫码、从相册识别二维码截图，或直接输入网关地址与安全 Token",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Button(
                                    onClick = onScanClick,
                                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("扫码 / 相册识别连接", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                                OutlinedButton(
                                    onClick = { showManualDialog = true },
                                    border = BorderStroke(1.dp, theme.outline),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = null, tint = theme.primary, modifier = Modifier.size(17.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("手动输入地址与 Token", color = theme.textPrimary, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                } else {
                    HorizontalDivider(color = theme.outline)

                    // ── 对标 pi-web SessionSidebar：顶部任务状态筛选胶囊 + 快速搜索栏 ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(theme.background)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 搜索框
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = "搜索任务",
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.textMuted
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    if (searchQuery.isEmpty()) {
                                        Text(
                                            text = "搜索任务标题或指令内容...",
                                            fontSize = 12.sp,
                                            color = theme.textMuted
                                        )
                                    }
                                    BasicTextField(
                                        value = searchQuery,
                                        onValueChange = { searchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = theme.textPrimary,
                                            fontSize = 12.5.sp
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                if (searchQuery.isNotEmpty()) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "清除",
                                        modifier = Modifier
                                            .size(15.dp)
                                            .clickable { searchQuery = "" },
                                        tint = theme.textMuted
                                    )
                                }
                            }
                        }

                        // 状态过滤标签：全部 | 运行中 (带动态旋转 Spinner) | 已完成 (带完成图标)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterPill(
                                selected = statusFilter == SessionStatusFilter.ALL,
                                onClick = { statusFilter = SessionStatusFilter.ALL },
                                label = "全部 (${currentWorkspaceSessions.size})"
                            )
                            FilterPill(
                                selected = statusFilter == SessionStatusFilter.RUNNING,
                                onClick = { statusFilter = SessionStatusFilter.RUNNING },
                                label = "运行中 ($runningInWorkspace)",
                                activeColor = Color(0xFF16A34A),
                                leadingIcon = {
                                    if (runningInWorkspace > 0) {
                                        PiWebRunningSpinner(
                                            size = 11.dp,
                                            color = if (statusFilter == SessionStatusFilter.RUNNING) Color(0xFF16A34A) else Color(0xFF22C55E),
                                            strokeWidth = 1.8.dp
                                        )
                                    } else {
                                        Surface(
                                            modifier = Modifier.size(6.dp),
                                            shape = CircleShape,
                                            color = theme.textMuted
                                        ) {}
                                    }
                                }
                            )
                            FilterPill(
                                selected = statusFilter == SessionStatusFilter.COMPLETED,
                                onClick = { statusFilter = SessionStatusFilter.COMPLETED },
                                label = "已完成 ($completedInWorkspace)",
                                leadingIcon = {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = if (statusFilter == SessionStatusFilter.COMPLETED) theme.primary else theme.textMuted
                                    )
                                }
                            )
                        }
                    }

                    // 任务列表
                    if (isLoading && sessions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = theme.primary)
                        }
                    } else if (filteredSessions.isEmpty()) {
                        val isPinnedZone = selectedWorkspacePath == PINNED_WORKSPACE_KEY
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(52.dp)
                                        .background(
                                            if (isPinnedZone) Color(0xFFEAB308).copy(alpha = 0.14f) else theme.surfaceContainerHigh,
                                            CircleShape
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = when {
                                            isPinnedZone -> Icons.Default.PushPin
                                            searchQuery.isNotBlank() -> Icons.Default.Search
                                            else -> Icons.Default.FolderOpen
                                        },
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = if (isPinnedZone) Color(0xFFEAB308) else theme.textMuted
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                Text(
                                    text = when {
                                        searchQuery.isNotBlank() -> "未找到匹配任务"
                                        isPinnedZone -> "暂无置顶任务"
                                        statusFilter == SessionStatusFilter.RUNNING -> "暂无运行中的任务"
                                        statusFilter == SessionStatusFilter.COMPLETED -> "暂无已完成的任务"
                                        else -> "当前工作区暂无任务"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textSecondary,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )

                                if (isPinnedZone) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "点击会话菜单即可置顶常用任务",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.textMuted,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        // 会话分组：如果在置顶专区则全量展示置顶项；若在普通项目区，则将已归档会话分离到末尾折叠区
                        val isPinnedZone = selectedWorkspacePath == PINNED_WORKSPACE_KEY
                        val (archivedSessions, activeSessions) = remember(filteredSessions, archivedSessionIds, isPinnedZone) {
                            if (isPinnedZone) {
                                emptyList<Session>() to filteredSessions
                            } else {
                                filteredSessions.partition { archivedSessionIds.contains(it.id) }
                            }
                        }

                        val (pinnedList, normalList) = remember(activeSessions, pinnedSessionIds) {
                            activeSessions.partition { pinnedSessionIds.contains(it.id) }
                        }

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(bottom = 84.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (pinnedList.isNotEmpty()) {
                                item(key = "section_pinned") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFFEAB308))
                                        Text(
                                            text = "置顶任务 (${pinnedList.size})",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFEAB308)
                                        )
                                    }
                                }
                                items(pinnedList, key = { "pin_${it.id}" }) { session ->
                                    SessionCard(
                                        session = session,
                                        isPinned = true,
                                        isArchived = archivedSessionIds.contains(session.id),
                                        customTitle = customTitles[session.id],
                                        onTogglePin = { onTogglePinSession(session.id) },
                                        onRename = {
                                            renamingSession = session
                                            renameInputText = customTitles[session.id] ?: session.displayTitle
                                        },
                                        onFork = { onForkSession(session) },
                                        onToggleArchive = { onToggleArchiveSession(session.id) },
                                        onDelete = { deletingSession = session },
                                        onClick = { onSessionClick(session) }
                                    )
                                }
                                if (normalList.isNotEmpty()) {
                                    item(key = "section_normal") {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "所有任务 (${normalList.size})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = theme.textSecondary
                                            )
                                        }
                                    }
                                }
                            }

                            items(if (pinnedList.isNotEmpty()) normalList else activeSessions, key = { it.id }) { session ->
                                SessionCard(
                                    session = session,
                                    isPinned = pinnedSessionIds.contains(session.id),
                                    isArchived = archivedSessionIds.contains(session.id),
                                    customTitle = customTitles[session.id],
                                    onTogglePin = { onTogglePinSession(session.id) },
                                    onRename = {
                                        renamingSession = session
                                        renameInputText = customTitles[session.id] ?: session.displayTitle
                                    },
                                    onFork = { onForkSession(session) },
                                    onToggleArchive = { onToggleArchiveSession(session.id) },
                                    onDelete = { deletingSession = session },
                                    onClick = { onSessionClick(session) }
                                )
                            }

                            // 🗃️ 已归档会话折叠区域 (1:1 对标 pi-web 侧边栏底部 Archived 专区)
                            if (archivedSessions.isNotEmpty()) {
                                item(key = "section_archived_toggle") {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = theme.surfaceContainer,
                                        border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.5f)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 10.dp)
                                            .clickable { isArchivedExpanded = !isArchivedExpanded }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    Icons.Default.Archive,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(15.dp),
                                                    tint = theme.textMuted
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = "已归档任务 (${archivedSessions.size})",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = theme.textSecondary
                                                )
                                            }
                                            Icon(
                                                imageVector = if (isArchivedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = if (isArchivedExpanded) "收起" else "展开",
                                                modifier = Modifier.size(18.dp),
                                                tint = theme.textSecondary
                                            )
                                        }
                                    }
                                }

                                if (isArchivedExpanded) {
                                    items(archivedSessions, key = { "archived_${it.id}" }) { session ->
                                        SessionCard(
                                            session = session,
                                            isPinned = pinnedSessionIds.contains(session.id),
                                            isArchived = true,
                                            customTitle = customTitles[session.id],
                                            onTogglePin = { onTogglePinSession(session.id) },
                                            onRename = {
                                                renamingSession = session
                                                renameInputText = customTitles[session.id] ?: session.displayTitle
                                            },
                                            onFork = { onForkSession(session) },
                                            onToggleArchive = { onToggleArchiveSession(session.id) },
                                            onDelete = { deletingSession = session },
                                            onClick = { onSessionClick(session) }
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

    if (showManualDialog) {
        ServerConfigDialog(
            initialUrl = serverUrl.ifBlank { ServerDefaults.gatewayUrlHint },
            initialToken = "",
            initialName = deviceName.ifBlank { "My PC" },
            onConfirm = { url, token, name ->
                showManualDialog = false
                onManualConfigConfirm(url, token, name)
            },
            onDismiss = { showManualDialog = false }
        )
    }


    // ==========================================
    // 🌟 对标 pi-web 源码级实现：工作区项目长按维护操作面板
    // ==========================================
    val menuWsTarget = selectedMenuWorkspace
    if (menuWsTarget != null) {
        val targetWs = menuWsTarget
        val isPinned = if (serverPinnedWorkspaceKeys.isNotEmpty() || serverWorkspaceOrder.isNotEmpty()) {
            serverPinnedWorkspaceKeys.contains(targetWs.path.replace('\\', '/').trimEnd('/').lowercase())
        } else {
            pinnedWorkspaces.contains(targetWs.path)
        }
        val customName = prefs.getWorkspaceCustomName(targetWs.path)
        val wsDisplayName = customName ?: targetWs.displayName

        ModalBottomSheet(
            onDismissRequest = { selectedMenuWorkspace = null },
            containerColor = theme.surfaceContainerLow,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 头部：项目名称与全路径
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.primary.copy(alpha = 0.12f),
                        border = BorderStroke(0.8.dp, theme.primary.copy(alpha = 0.35f)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = theme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = wsDisplayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            if (isPinned) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(Icons.Default.PushPin, contentDescription = "已置顶", tint = Color(0xFFEAB308), modifier = Modifier.size(13.dp))
                            }
                        }
                        Text(
                            text = targetWs.path,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = theme.outline.copy(alpha = 0.35f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(2.dp))

                // 板块 1：核心属性与快速进入
                // 1. 置顶项目 / 取消置顶项目
                WorkspaceMenuActionItem(
                    icon = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                    iconTint = if (isPinned) Color(0xFFEAB308) else theme.primary,
                    title = if (isPinned) "取消置顶项目" else "置顶项目",
                    subtitle = if (isPinned) "从大盘顶部移除常驻优先排列" else "固定在工作区项目列表最顶端",
                    onClick = {
                        // 项目置顶的真源在 pi-web 的 ui-state（pin-project），本地只做乐观镜像
                        val willPin = !isPinned
                        prefs.togglePinWorkspace(targetWs.path)
                        pinnedWorkspaces = prefs.getPinnedWorkspaces()
                        onTogglePinWorkspace(targetWs.path, targetWs.path)
                        selectedMenuWorkspace = null
                        Toast.makeText(context, if (willPin) "已置顶该项目" else "已取消置顶", Toast.LENGTH_SHORT).show()
                    }
                )

                // 2. 重命名项目别名...
                WorkspaceMenuActionItem(
                    icon = Icons.Default.Edit,
                    iconTint = theme.textPrimary,
                    title = "重命名项目名称...",
                    subtitle = if (customName != null) "当前别名: $customName (支持一键恢复)" else "为项目设置更友好的专属显示名称",
                    onClick = {
                        renameWsInput = wsDisplayName
                        showRenameWsDialog = targetWs
                        selectedMenuWorkspace = null
                    }
                )

                // 3. 在新会话中打开
                WorkspaceMenuActionItem(
                    icon = Icons.Default.OpenInNew,
                    iconTint = theme.primary,
                    title = "在新会话中打开",
                    subtitle = "切换至该工作区并直接开启新的任务会话",
                    onClick = {
                        selectedWorkspacePath = targetWs.path
                        selectedMenuWorkspace = null
                        coroutineScope.launch {
                            drawerState.close()
                            onNewSessionClick(targetWs.path)
                        }
                    }
                )

                HorizontalDivider(color = theme.outline.copy(alpha = 0.35f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(2.dp))

                // 板块 2：排序微调 (上移 / 下移)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(0.6.dp, theme.outline),
                        modifier = Modifier.weight(1f).clickable {
                            val currentList = workspaces.map { it.path }.toMutableList()
                            val idx = currentList.indexOf(targetWs.path)
                            if (idx > 0) {
                                java.util.Collections.swap(currentList, idx, idx - 1)
                                prefs.saveWorkspaceOrder(currentList)
                                wsOrder = currentList
                                // 同步到 pi-web 的 projectOrder（anchor = 换到它前面的那个项目）
                                onMoveWorkspace(targetWs.path, currentList[idx - 1])
                                Toast.makeText(context, "已上移项目", Toast.LENGTH_SHORT).show()
                            }
                            selectedMenuWorkspace = null
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("上移项目", fontSize = 11.5.sp, color = theme.textPrimary)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(0.6.dp, theme.outline),
                        modifier = Modifier.weight(1f).clickable {
                            val currentList = workspaces.map { it.path }.toMutableList()
                            val idx = currentList.indexOf(targetWs.path)
                            if (idx >= 0 && idx < currentList.lastIndex) {
                                java.util.Collections.swap(currentList, idx, idx + 1)
                                prefs.saveWorkspaceOrder(currentList)
                                wsOrder = currentList
                                onMoveWorkspace(targetWs.path, currentList[idx + 1])
                                Toast.makeText(context, "已下移项目", Toast.LENGTH_SHORT).show()
                            }
                            selectedMenuWorkspace = null
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下移项目", fontSize = 11.5.sp, color = theme.textPrimary)
                        }
                    }
                }

                HorizontalDivider(color = theme.outline.copy(alpha = 0.35f), thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(2.dp))

                // 板块 3：会话生命周期与清理维护 (对标 pi-web)
                val projectSessions = remember(sessions, targetWs) {
                    sessions.filter { it.workspacePath == targetWs.path || it.cwd == targetWs.path || it.project == targetWs.displayName }
                }
                val weekAgo = remember { System.currentTimeMillis() - 7 * 86400000L }
                val olderSessions = remember(projectSessions, weekAgo) {
                    projectSessions.filter { s ->
                        if (s.isLive || prefs.isSessionArchived(s.id)) return@filter false
                        val t = parseSessionTimeMillis(s.modified ?: s.created)
                        t in 1..<weekAgo
                    }
                }
                val archivedCount = remember(projectSessions) {
                    projectSessions.count { prefs.isSessionArchived(it.id) }
                }

                // 4. 归档 7 天前的会话 · N
                WorkspaceMenuActionItem(
                    icon = Icons.Default.Archive,
                    iconTint = if (olderSessions.isNotEmpty()) theme.primary else theme.textMuted,
                    title = "归档 7 天前的会话 · ${olderSessions.size}",
                    subtitle = if (olderSessions.isNotEmpty()) "一键批量沉淀已逾一周的 ${olderSessions.size} 个历史会话" else "暂无超过 7 天的未归档历史会话",
                    enabled = olderSessions.isNotEmpty(),
                    onClick = {
                        // 一次提交全部 id 到 pi-web 的 ui-state（本地标记由外层回调同步）
                        onBulkArchive(olderSessions.map { it.id })
                        selectedMenuWorkspace = null
                    }
                )

                // 5. 查看已归档会话 · N
                WorkspaceMenuActionItem(
                    icon = Icons.Default.FolderZip,
                    iconTint = if (archivedCount > 0) Color(0xFF8B5CF6) else theme.textMuted,
                    title = "查看已归档会话 · $archivedCount",
                    subtitle = if (archivedCount > 0) "浏览该项目已沉淀的 $archivedCount 个归档会话，支持一键恢复" else "该项目暂无归档会话",
                    enabled = archivedCount > 0,
                    onClick = {
                        showArchivedSessionsDialog = targetWs
                        selectedMenuWorkspace = null
                    }
                )

                // 6. 删除全部会话... (危险红色警告操作)
                WorkspaceMenuActionItem(
                    icon = Icons.Default.DeleteForever,
                    iconTint = Color(0xFFEF4444),
                    title = "删除全部会话... · ${projectSessions.size}",
                    subtitle = "删除所有历史会话（正在运行的会话会自动保留）",
                    titleColor = Color(0xFFEF4444),
                    enabled = projectSessions.isNotEmpty(),
                    onClick = {
                        showDeleteAllSessionsDialog = targetWs
                        selectedMenuWorkspace = null
                    }
                )
            }
        }
    }

    // 弹窗 A：重命名项目别名对话框 (支持恢复默认名称)
    val renameTarget = showRenameWsDialog
    if (renameTarget != null) {
        val target = renameTarget
        AlertDialog(
            onDismissRequest = { showRenameWsDialog = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text("重命名项目名称", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("路径: ${target.path}", fontSize = 11.sp, color = theme.textMuted, fontFamily = FontFamily.Monospace)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(1.dp, theme.outline),
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 10.dp)) {
                            BasicTextField(
                                value = renameWsInput,
                                onValueChange = { renameWsInput = it },
                                singleLine = true,
                                textStyle = TextStyle(color = theme.textPrimary, fontSize = 13.sp),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        prefs.saveWorkspaceCustomName(target.path, renameWsInput.trim())
                        showRenameWsDialog = null
                        Toast.makeText(context, "已更新项目名称", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text("保存别名", color = Color.White)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (prefs.getWorkspaceCustomName(target.path) != null) {
                        TextButton(
                            onClick = {
                                prefs.saveWorkspaceCustomName(target.path, null)
                                showRenameWsDialog = null
                                Toast.makeText(context, "已恢复项目默认名称", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text("恢复默认", color = Color(0xFFEF4444))
                        }
                    }
                    TextButton(onClick = { showRenameWsDialog = null }) {
                        Text("取消", color = theme.textSecondary)
                    }
                }
            }
        )
    }

    // 弹窗 B：删除全部会话二次危险确认对话框 (对标 pi-web)
    val deleteTarget = showDeleteAllSessionsDialog
    if (deleteTarget != null) {
        val target = deleteTarget
        val projectSessions = remember(sessions, target) {
            sessions.filter { it.workspacePath == target.path || it.cwd == target.path || it.project == target.displayName }
        }
        val runningSessions = remember(projectSessions) { projectSessions.filter { it.isLive } }
        val toDeleteSessions = remember(projectSessions) { projectSessions.filterNot { it.isLive } }

        AlertDialog(
            onDismissRequest = { showDeleteAllSessionsDialog = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("删除 ${target.displayName} 的全部会话", fontWeight = FontWeight.Bold, color = Color(0xFFEF4444), fontSize = 15.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "此操作不可逆！置顶和已归档的历史会话均将被永久删除。",
                        fontSize = 12.sp,
                        color = theme.textPrimary
                    )
                    if (runningSessions.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF22C55E).copy(alpha = 0.12f),
                            border = BorderStroke(0.8.dp, Color(0xFF22C55E).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "💡 正在运行中的 ${runningSessions.size} 个活跃会话会被完整保留。",
                                fontSize = 11.5.sp,
                                color = Color(0xFF16A34A),
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                    Text(
                        text = "即将永久删除 ${toDeleteSessions.size} 个会话。",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textMuted
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val count = toDeleteSessions.size
                        toDeleteSessions.forEach { s ->
                            onDeleteSession(s.id)
                        }
                        showDeleteAllSessionsDialog = null
                        Toast.makeText(context, "已删除 $count 个历史会话" + (if (runningSessions.isNotEmpty()) "，保留 ${runningSessions.size} 个运行中会话" else ""), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                ) {
                    Text("确认删除 (${toDeleteSessions.size})", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllSessionsDialog = null }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 弹窗 C：查看与管理项目已归档会话弹窗
    val archiveTarget = showArchivedSessionsDialog
    if (archiveTarget != null) {
        val target = archiveTarget
        val archivedList = remember(sessions, target) {
            sessions.filter { (it.workspacePath == target.path || it.cwd == target.path || it.project == target.displayName) && prefs.isSessionArchived(it.id) }
        }

        AlertDialog(
            onDismissRequest = { showArchivedSessionsDialog = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, tint = Color(0xFF8B5CF6), modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${target.displayName} 已归档会话 (${archivedList.size})", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                    }
                    IconButton(onClick = { showArchivedSessionsDialog = null }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                    }
                }
            },
            text = {
                if (archivedList.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("暂无归档会话", color = theme.textMuted, fontSize = 12.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(archivedList, key = { it.id }) { s ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(0.6.dp, theme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = s.name ?: s.id.take(8),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "ID: ${s.id.take(12)}...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = theme.textMuted,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    OutlinedButton(
                                        onClick = {
                                            prefs.toggleArchiveSession(s.id)
                                            Toast.makeText(context, "已恢复会话「${s.name ?: s.id.take(6)}」", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Icon(Icons.Default.Unarchive, contentDescription = null, modifier = Modifier.size(11.dp))
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("恢复", fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showArchivedSessionsDialog = null }) {
                    Text("关闭", color = theme.primary)
                }
            }
        )
    }

        if (showAddWorkspaceDialog) {
        RemoteWorkspaceDialog(
            initialPath = selectedWorkspacePath,
            onBrowseRemoteDir = onBrowseRemoteDir,
            onDismiss = { showAddWorkspaceDialog = false },
            onConfirm = { targetPath, newFolderName, createIfMissing, startSessionNow ->
                coroutineScope.launch {
                    val result = onAddWorkspace(targetPath, newFolderName, createIfMissing)
                    val finalPath = result.getOrDefault(targetPath)
                    selectedWorkspacePath = finalPath
                    showAddWorkspaceDialog = false
                    drawerState.close()
                    if (startSessionNow) {
                        onNewSessionClick(finalPath)
                    }
                }
            }
        )
    }

    // ✏️ 重命名会话对话框
    renamingSession?.let { sess ->
        AlertDialog(
            onDismissRequest = { renamingSession = null },
            title = { Text("重命名会话", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "为任务设置易于辨识的备注名称：",
                        style = MaterialTheme.typography.bodySmall,
                        color = theme.textMuted
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = renameInputText,
                        onValueChange = { renameInputText = it },
                        singleLine = true,
                        placeholder = { Text(sess.displayTitle) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clean = renameInputText.trim()
                        if (clean.isNotBlank()) {
                            onRenameSession(sess.id, clean)
                        }
                        renamingSession = null
                    }
                ) {
                    Text("保存", fontWeight = FontWeight.Bold, color = theme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingSession = null }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 🗑️ 删除会话确认对话框
    deletingSession?.let { sess ->
        AlertDialog(
            onDismissRequest = { deletingSession = null },
            title = { Text("删除任务会话", fontWeight = FontWeight.Bold, color = Color(0xFFDC2626)) },
            text = {
                Text("确定要彻底删除任务「${customTitles[sess.id] ?: sess.displayTitle}」吗？\n删除后任务上下文与历史记录将无法恢复。")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteSession(sess.id)
                        deletingSession = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                ) {
                    Text("确认删除", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { deletingSession = null }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }
}

@Composable
private fun RemoteWorkspaceDialog(
    initialPath: String,
    onBrowseRemoteDir: suspend (String) -> RemoteBrowseData?,
    onDismiss: () -> Unit,
    onConfirm: (targetPath: String, newFolderName: String?, createIfMissing: Boolean, startSessionNow: Boolean) -> Unit
) {
    val theme = LocalAppTheme.current
    val scope = rememberCoroutineScope()
    var pathInput by remember { mutableStateOf(initialPath) }
    var newChildName by remember { mutableStateOf("") }
    var createIfMissing by remember { mutableStateOf(true) }
    var isBrowsing by remember { mutableStateOf(false) }
    var browseData by remember { mutableStateOf<RemoteBrowseData?>(null) }

    fun loadDirectory(dirPath: String) {
        scope.launch {
            isBrowsing = true
            val res = onBrowseRemoteDir(dirPath)
            if (res != null) {
                browseData = res
                if (res.currentPath.isNotBlank()) {
                    pathInput = res.currentPath
                }
            }
            isBrowsing = false
        }
    }

    LaunchedEffect(Unit) {
        loadDirectory(initialPath)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = null, tint = theme.primary, modifier = Modifier.size(18.dp))
                Text(
                    text = "添加 / 新建远程项目目录",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "可直接输入 PC 上的目标工程路径，或从下方远程目录列表中点击选择、新建子目录：",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 11.sp,
                    color = theme.textSecondary
                )

                OutlinedTextField(
                    value = pathInput,
                    onValueChange = { pathInput = it },
                    label = { Text("远程工程目录路径", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                    placeholder = { Text("如 D:\\Projects\\my-app 或 ~/projects/app", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.textPrimary),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { loadDirectory(pathInput) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "浏览该路径", tint = theme.primary, modifier = Modifier.size(18.dp))
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = newChildName,
                    onValueChange = { newChildName = it },
                    label = { Text("在此路径下新建子文件夹 (选填)", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                    placeholder = { Text("如 new-feature-project", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.textPrimary),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 远程目录浏览器面板
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(1.dp, theme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "PC 远程目录浏览",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.textSecondary,
                                fontSize = 11.sp
                            )
                            val parentPath = browseData?.parentPath
                            if (!parentPath.isNullOrBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = theme.surfaceContainerLow,
                                    border = BorderStroke(0.8.dp, theme.outline),
                                    modifier = Modifier.clickable { loadDirectory(parentPath) }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(12.dp), tint = theme.primary)
                                        Text("上一级", fontSize = 10.5.sp, color = theme.primary, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        if (isBrowsing) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(110.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = theme.primary)
                            }
                        } else {
                            val dirs = browseData?.directories.orEmpty()
                            if (dirs.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "可直接手动输入任意 PC 目录路径并自动创建",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.textMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 150.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(dirs, key = { it.path }) { dirEntry ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = theme.surfaceContainerLow,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    pathInput = dirEntry.path
                                                    loadDirectory(dirEntry.path)
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(15.dp), tint = theme.primary)
                                                Text(
                                                    text = dirEntry.name,
                                                    fontSize = 11.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = theme.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(14.dp), tint = theme.textMuted)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "若远程目录不存在则自动在 PC 上创建",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.5.sp,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = createIfMissing,
                        onCheckedChange = { createIfMissing = it }
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        if (pathInput.isNotBlank()) {
                            onConfirm(pathInput.trim(), newChildName.trim().takeIf { it.isNotBlank() }, createIfMissing, false)
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, theme.outline),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("仅添加目录", fontSize = 11.5.sp, color = theme.textPrimary, maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = {
                        if (pathInput.isNotBlank()) {
                            onConfirm(pathInput.trim(), newChildName.trim().takeIf { it.isNotBlank() }, createIfMissing, true)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("添加并新建任务", fontSize = 11.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = theme.textMuted, maxLines = 1, softWrap = false)
            }
        }
    )
}

@Composable
private fun FilterPill(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    activeColor: Color? = null,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val theme = LocalAppTheme.current
    val highlight = activeColor ?: theme.primary
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) highlight.copy(alpha = 0.14f) else theme.surfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) highlight.copy(alpha = 0.6f) else theme.outline),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            leadingIcon?.invoke()
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) highlight else theme.textSecondary
            )
        }
    }
}

/**
 * 对标 pi-web SessionTree.tsx 的任务行卡片：
 * - 正在运行 (`session.isLive`)：左侧动态旋转圆弧 `PiWebRunningSpinner` + 右侧绿色 `运行中` 徽章 + 翠绿高亮边框
 * - 等待输入 (`session.awaitingInput`)：左侧琥珀色脉冲圆点 + 右侧 `等待确认` 徽章
 * - 已完成 (`!session.isLive`)：左侧已完成打勾图标 (`CheckCircle`) + 消息条数统计 + 格式化时间
 * - 支持置顶与自定义上下文取名展示
 */
@Composable
fun SessionCard(
    session: Session,
    isPinned: Boolean = false,
    isArchived: Boolean = false,
    customTitle: String? = null,
    onTogglePin: () -> Unit = {},
    onRename: () -> Unit = {},
    onFork: () -> Unit = {},
    onToggleArchive: () -> Unit = {},
    onDelete: () -> Unit = {},
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    var showMenu by remember { mutableStateOf(false) }
    val timeRaw = session.modified ?: session.created ?: ""
    // pi-web 的 modified 是 UTC ISO 串（如 2026-10-27T08:31:27.266Z）。
    // 以前直接 take(16) 截字符串，手机列表显示的是 UTC 时间，看上去比 pi-web 的
    // 本地时间「慢 8 小时」，很像没同步。这里转成本地时区再格式化。
    val dateStr = remember(timeRaw) {
        val millis = parseSessionTimeMillis(timeRaw)
        if (millis > 0L) {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(millis))
        } else if (timeRaw.length >= 16) {
            timeRaw.take(16).replace("T", " ").substringAfter("-")
        } else {
            timeRaw
        }
    }

    val isRunning = session.isLive
    val isAwaiting = session.awaitingInput
    val titleText = customTitle ?: session.displayTitle

    val borderStroke = when {
        isPinned -> BorderStroke(1.dp, theme.primary.copy(alpha = 0.55f))
        isAwaiting -> BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f))
        isRunning -> BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.45f))
        else -> BorderStroke(1.dp, theme.outline)
    }

    val cardBg = when {
        isPinned -> theme.primary.copy(alpha = if (theme.isDark) 0.08f else 0.04f)
        isAwaiting -> Color(0xFFF59E0B).copy(alpha = if (theme.isDark) 0.07f else 0.04f)
        isRunning -> Color(0xFF22C55E).copy(alpha = if (theme.isDark) 0.06f else 0.03f)
        else -> theme.surfaceContainerLow
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = cardBg,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // ── 左侧动态状态图标 (对标 pi-web SessionTree status slot) ──
                    when {
                        isAwaiting -> {
                            Surface(
                                modifier = Modifier.size(10.dp),
                                shape = CircleShape,
                                color = Color(0xFFF59E0B)
                            ) {}
                        }
                        isRunning -> {
                            PiWebRunningSpinner(
                                size = 14.dp,
                                color = if (theme.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A),
                                strokeWidth = 2.dp
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Outlined.CheckCircle,
                                contentDescription = "已完成",
                                modifier = Modifier.size(15.dp),
                                tint = if (theme.isDark) Color(0xFF3FB950).copy(alpha = 0.75f) else Color(0xFF16A34A).copy(alpha = 0.75f)
                            )
                        }
                    }

                    if (isPinned) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = theme.primary.copy(alpha = 0.16f),
                            border = BorderStroke(0.5.dp, theme.primary.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(9.dp), tint = theme.primary)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("置顶", fontSize = 9.sp, color = theme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isRunning || isAwaiting || isPinned) FontWeight.Bold else FontWeight.SemiBold,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // ── 右侧状态徽章或时间 (对标 pi-web SessionTree meta column) ──
                    when {
                        isAwaiting -> {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFF59E0B).copy(alpha = 0.16f),
                                border = BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.5f))
                            ) {
                                Text(
                                    text = "等待确认",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFD97706),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        isRunning -> {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF22C55E).copy(alpha = 0.15f),
                                border = BorderStroke(0.5.dp, Color(0xFF22C55E).copy(alpha = 0.45f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "运行中",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        softWrap = false,
                                        color = if (theme.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
                                    )
                                }
                            }
                        }
                        else -> {
                            Text(
                                text = "已完成",
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.5.sp,
                                maxLines = 1,
                                softWrap = false,
                                color = theme.textMuted
                            )
                        }
                    }

                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "已置顶",
                            tint = Color(0xFFEAB308),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "更多操作",
                                tint = theme.textSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (isPinned) "取消置顶" else "置顶", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = if (isPinned) Color(0xFFEAB308) else theme.textSecondary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onTogglePin()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("重命名", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = theme.textSecondary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onRename()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text("分叉", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AltRoute,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = theme.textSecondary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onFork()
                                }
                            )

                            DropdownMenuItem(
                                text = { Text(if (isArchived) "取消归档" else "归档", style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Archive,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = theme.textSecondary
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onToggleArchive()
                                }
                            )

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            DropdownMenuItem(
                                text = { Text("删除...", color = Color(0xFFDC2626), style = MaterialTheme.typography.bodyMedium) },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = Color(0xFFDC2626)
                                    )
                                },
                                onClick = {
                                    showMenu = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            if (!session.firstMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = session.firstMessage.replace('\n', ' ').trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 22.dp)
                )
            }

            // 底部元信息行：左侧自适应截断 (weight(1f)) + 右侧单行不折行时间 (softWrap = false, maxLines = 1)
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = session.workspaceName,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (session.messageCount > 0) {
                        Text(
                            text = "· ${session.messageCount} 条",
                            fontSize = 10.5.sp,
                            color = theme.textMuted,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (!session.branch.isNullOrBlank()) {
                        Text(
                            text = "· ⑂ ${session.branch}",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.primary,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (dateStr.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}


@Composable
private fun WorkspaceMenuActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    titleColor: Color = LocalAppTheme.current.textPrimary,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color.Transparent else theme.surfaceContainerLow.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) iconTint else theme.textMuted,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = if (enabled) titleColor else theme.textMuted
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.5.sp,
                        color = theme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun parseSessionTimeMillis(timeStr: String?): Long {
    if (timeStr.isNullOrBlank()) return 0L
    timeStr.toLongOrNull()?.let { return it }
    return try {
        java.time.Instant.parse(timeStr).toEpochMilli()
    } catch (_: Exception) {
        try {
            java.time.OffsetDateTime.parse(timeStr).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                val cleaned = timeStr.replace("T", " ").take(19)
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                sdf.parse(cleaned)?.time ?: 0L
            } catch (_: Exception) {
                0L
            }
        }
    }
}

