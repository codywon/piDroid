package com.pi.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.Session
import com.pi.app.data.model.WorkspaceInfo
import com.pi.app.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    deviceName: String,
    serverUrl: String,
    isConfigured: Boolean,
    sessions: List<Session>,
    isLoading: Boolean,
    onRefresh: () -> Unit,
    onSessionClick: (Session) -> Unit,
    onScanClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNewSessionClick: (workspacePath: String) -> Unit
) {
    val theme = LocalAppTheme.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    // 按工作区项目路径归组
    val workspaces = remember(sessions) {
        val map = sessions.groupBy { it.workspacePath }
        map.map { (path, list) ->
            val first = list.firstOrNull()
            val name = first?.workspaceName ?: path.substringAfterLast('\\').substringAfterLast('/')
            WorkspaceInfo(
                path = path,
                name = name.ifBlank { "默认项目" },
                sessionCount = list.size
            )
        }.sortedByDescending { it.sessionCount }
    }

    var selectedWorkspacePath by remember(workspaces) {
        mutableStateOf(workspaces.firstOrNull()?.path ?: "")
    }

    // 当前选定工作区下的任务列表
    val currentWorkspaceSessions = remember(sessions, selectedWorkspacePath) {
        if (selectedWorkspacePath.isBlank()) {
            sessions
        } else {
            sessions.filter { it.workspacePath == selectedWorkspacePath }
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
                modifier = Modifier.width(300.dp)
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
                                text = "共 ${workspaces.size} 个活动目录",
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
                                color = if (isSelected) theme.primary.copy(alpha = 0.15f) else Color.Transparent,
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
                                    Spacer(modifier = Modifier.width(6.dp))
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
                                Surface(
                                    modifier = Modifier.size(6.dp),
                                    shape = CircleShape,
                                    color = if (isConfigured) Color(0xFF2EA043) else theme.textMuted
                                ) {}
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isConfigured) (deviceName.ifEmpty { "已连接 PC" }) else "未连接 PC",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textSecondary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = theme.textSecondary)
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
                    // 极简圆形浮动加号按钮，去掉冗长文字
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
                    // 未连接配对引导 (极简干净，杜绝啰嗦废话)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Computer,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = theme.primary
                            )
                            Text(
                                text = "连接您的 PC 伴侣网关",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Button(
                                onClick = onScanClick,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("扫码配对连接", color = Color.White)
                            }
                        }
                    }
                } else {
                    HorizontalDivider(color = theme.outline)

                    // 任务列表
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = theme.primary)
                        }
                    } else if (currentWorkspaceSessions.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("当前工作区暂无历史任务", color = theme.textMuted, style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(currentWorkspaceSessions, key = { it.id }) { session ->
                                SessionCard(session = session, onClick = { onSessionClick(session) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SessionCard(
    session: Session,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    val timeRaw = session.modified ?: session.created ?: ""
    val dateStr = remember(timeRaw) {
        if (timeRaw.length >= 16) {
            timeRaw.take(16).replace("T", " ")
        } else {
            timeRaw
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(1.dp, theme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.displayTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (dateStr.isNotBlank()) {
                    Text(
                        text = dateStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted
                    )
                }
            }

            if (!session.firstMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = session.firstMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = theme.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
