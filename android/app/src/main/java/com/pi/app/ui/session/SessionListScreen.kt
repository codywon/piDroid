package com.pi.app.ui.session

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CheckCircle
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
import com.pi.app.data.model.Session
import com.pi.app.data.model.WorkspaceInfo
import com.pi.app.ui.settings.ServerConfigDialog
import com.pi.app.ui.theme.*
import kotlinx.coroutines.launch

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    deviceName: String,
    serverUrl: String,
    isConfigured: Boolean,
    isGatewayOnline: Boolean = true,
    sessions: List<Session>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSessionClick: (Session) -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewSessionClick: (workspacePath: String) -> Unit,
    onManualConfigConfirm: (url: String, token: String, name: String) -> Unit = { _, _, _ -> }
) {
    val theme = LocalAppTheme.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    var showManualDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var statusFilter by remember { mutableStateOf(SessionStatusFilter.ALL) }

    // 按工作区项目路径归组，并统计每个工作区下正在运行的任务数（对标 pi-web ActivitySummary）
    val workspaces = remember(sessions) {
        val map = sessions.groupBy { it.workspacePath }
        map.map { (path, list) ->
            val first = list.firstOrNull()
            val name = first?.workspaceName ?: path.substringAfterLast('\\').substringAfterLast('/')
            WorkspaceInfo(
                path = path,
                name = name.ifBlank { "默认项目" },
                sessionCount = list.size,
                runningCount = list.count { it.active },
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

    // 当前选定工作区下的任务列表（正在运行的任务自动置顶，随后按时间排列）
    val currentWorkspaceSessions = remember(sessions, selectedWorkspacePath) {
        val base = if (selectedWorkspacePath.isBlank()) {
            sessions
        } else {
            sessions.filter { it.workspacePath == selectedWorkspacePath }
        }
        base.sortedWith(
            compareByDescending<Session> { it.awaitingInput }
                .thenByDescending { it.active }
                .thenByDescending { it.modified ?: it.created ?: "" }
        )
    }

    val runningInWorkspace = remember(currentWorkspaceSessions) {
        currentWorkspaceSessions.count { it.active || it.awaitingInput }
    }
    val completedInWorkspace = remember(currentWorkspaceSessions, runningInWorkspace) {
        (currentWorkspaceSessions.size - runningInWorkspace).coerceAtLeast(0)
    }

    val filteredSessions = remember(currentWorkspaceSessions, statusFilter, searchQuery) {
        currentWorkspaceSessions.filter { s ->
            val matchesStatus = when (statusFilter) {
                SessionStatusFilter.ALL -> true
                SessionStatusFilter.RUNNING -> s.active || s.awaitingInput
                SessionStatusFilter.COMPLETED -> !s.active && !s.awaitingInput
            }
            val q = searchQuery.trim()
            val matchesQuery = q.isEmpty() ||
                s.displayTitle.contains(q, ignoreCase = true) ||
                (s.firstMessage?.contains(q, ignoreCase = true) == true) ||
                s.workspaceName.contains(q, ignoreCase = true)
            matchesStatus && matchesQuery
        }
    }

    val currentWorkspaceName = remember(workspaces, selectedWorkspacePath) {
        workspaces.firstOrNull { it.path == selectedWorkspacePath }?.displayName ?: "全部工作区"
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
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "工作区项目",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Text(
                                text = "共 ${workspaces.size} 个活动目录 · ${sessions.count { it.active }} 个运行中",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
                        }
                        IconButton(onClick = { coroutineScope.launch { drawerState.close() } }) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = theme.textSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = theme.outline)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(workspaces) { ws ->
                            val isSelected = ws.path == selectedWorkspacePath
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) theme.primary.copy(alpha = 0.14f) else Color.Transparent,
                                border = if (isSelected) BorderStroke(1.dp, theme.primary) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedWorkspacePath = ws.path
                                        coroutineScope.launch { drawerState.close() }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = if (isSelected) theme.primary else theme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = ws.displayName,
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
                                        Spacer(modifier = Modifier.width(6.dp))
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
                                }
                            }
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
                        onClick = { onNewSessionClick(selectedWorkspacePath) },
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
                                Surface(
                                    shape = CircleShape,
                                    color = theme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.Computer,
                                            contentDescription = null,
                                            modifier = Modifier.size(32.dp),
                                            tint = theme.primary
                                        )
                                    }
                                }
                                Text(
                                    text = "连接您的 PC 伴侣网关",
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
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = when (statusFilter) {
                                    SessionStatusFilter.RUNNING -> "当前没有正在运行的任务"
                                    SessionStatusFilter.COMPLETED -> "暂无已完成的任务"
                                    SessionStatusFilter.ALL -> "当前工作区暂无历史任务"
                                },
                                color = theme.textMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentPadding = PaddingValues(bottom = 84.dp, top = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredSessions, key = { it.id }) { session ->
                                SessionCard(session = session, onClick = { onSessionClick(session) })
                            }
                        }
                    }
                }
            }
        }
    }

    if (showManualDialog) {
        ServerConfigDialog(
            initialUrl = serverUrl.ifBlank { "http://10.0.2.2:4100" },
            initialToken = "",
            initialName = deviceName.ifBlank { "My PC" },
            onConfirm = { url, token, name ->
                showManualDialog = false
                onManualConfigConfirm(url, token, name)
            },
            onDismiss = { showManualDialog = false }
        )
    }
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
 * - 正在运行 (`session.active`)：左侧动态旋转圆弧 `PiWebRunningSpinner` + 右侧绿色 `运行中` 徽章 + 翠绿高亮边框
 * - 等待输入 (`session.awaitingInput`)：左侧琥珀色脉冲圆点 + 右侧 `等待确认` 徽章
 * - 已完成 (`!session.active`)：左侧已完成打勾图标 (`CheckCircle`) + 消息条数统计 + 格式化时间
 */
@Composable
fun SessionCard(
    session: Session,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    val timeRaw = session.modified ?: session.created ?: ""
    val dateStr = remember(timeRaw) {
        if (timeRaw.length >= 16) {
            timeRaw.take(16).replace("T", " ").substringAfter("-")
        } else {
            timeRaw
        }
    }

    val isRunning = session.active
    val isAwaiting = session.awaitingInput

    val borderStroke = when {
        isAwaiting -> BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.55f))
        isRunning -> BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.45f))
        else -> BorderStroke(1.dp, theme.outline)
    }

    val cardBg = when {
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

                    Text(
                        text = session.displayTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isRunning || isAwaiting) FontWeight.Bold else FontWeight.SemiBold,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

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
                            color = theme.textMuted
                        )
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

            // 底部元信息行：工作区分支 / 消息数量 / 最近更新时间
            Spacer(modifier = Modifier.height(5.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = session.workspaceName,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )
                    if (session.messageCount > 0) {
                        Text(
                            text = "· ${session.messageCount} 条消息",
                            fontSize = 10.5.sp,
                            color = theme.textMuted
                        )
                    }
                    if (!session.branch.isNullOrBlank()) {
                        Text(
                            text = "· ⑂ ${session.branch}",
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.primary
                        )
                    }
                }

                if (dateStr.isNotBlank()) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )
                }
            }
        }
    }
}
