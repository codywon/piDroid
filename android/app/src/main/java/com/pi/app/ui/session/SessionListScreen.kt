package com.pi.app.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.Session
import com.pi.app.data.model.WorkspaceInfo
import com.pi.app.ui.theme.*

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
    // 按工作区项目路径归组
    val workspaces = remember(sessions) {
        val map = sessions.groupBy { it.workspacePath }
        map.map { (path, list) ->
            val first = list.firstOrNull()
            val name = first?.workspaceName ?: path.substringAfterLast('\\').substringAfterLast('/')
            WorkspaceInfo(
                path = path,
                name = name,
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

    Scaffold(
        containerColor = TermiusBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TermiusBlack,
                    titleContentColor = TermiusTextPrimary
                ),
                title = {
                    Column {
                        Text(
                            text = "piDroid",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 18.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isConfigured) TermiusGreen else TermiusTextMuted
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConfigured) (deviceName.ifEmpty { "已连接 PC" }) else "未连接 PC",
                                style = MaterialTheme.typography.bodySmall,
                                color = TermiusTextSecondary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = TermiusTextSecondary)
                    }
                    IconButton(onClick = onScanClick) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "扫码配对", tint = TermiusBlue)
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "全局设置中心", tint = TermiusTextPrimary)
                    }
                }
            )
        },
        floatingActionButton = {
            if (isConfigured) {
                FloatingActionButton(
                    onClick = { onNewSessionClick(selectedWorkspacePath) },
                    containerColor = TermiusBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("新任务", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                    }
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
                // 未连接引导
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = TermiusBlue
                        )
                        Text(
                            text = "尚未配对 PC 伴侣网关",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TermiusTextPrimary
                        )
                        Text(
                            text = "在 PC 终端运行 start.bat 或 start-tunnel.bat，然后点击下方按钮扫码配对即可全面掌控 PC 智能体。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TermiusTextSecondary,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                        Button(
                            onClick = onScanClick,
                            colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("扫码配对连接", color = Color.White)
                        }
                    }
                }
            } else {
                // 1. 工作区快速切换胶囊栏
                if (workspaces.isNotEmpty()) {
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = "工作区项目 (${workspaces.size})",
                            style = MaterialTheme.typography.labelSmall,
                            color = TermiusTextSecondary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            workspaces.forEach { ws ->
                                val isSelected = ws.path == selectedWorkspacePath
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF1B3D5A) else TermiusSurface,
                                    border = BorderStroke(1.dp, if (isSelected) TermiusBlue else TermiusBorder),
                                    modifier = Modifier.clickable { selectedWorkspacePath = ws.path }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Folder,
                                            contentDescription = null,
                                            modifier = Modifier.size(13.dp),
                                            tint = if (isSelected) TermiusBlue else TermiusTextSecondary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = ws.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) TermiusBlue else TermiusTextPrimary
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = if (isSelected) TermiusBlue.copy(alpha = 0.3f) else TermiusSurfaceVariant
                                        ) {
                                            Text(
                                                text = "${ws.sessionCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                color = if (isSelected) Color.White else TermiusTextMuted,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = TermiusBorder)

                // 2. 当前工作区下的任务列表
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = TermiusBlue)
                    }
                } else if (currentWorkspaceSessions.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("当前工作区暂无历史任务", color = TermiusTextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentWorkspaceSessions, key = { it.id }) { session ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = TermiusSurface,
                                border = BorderStroke(1.dp, TermiusBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSessionClick(session) }
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // 任务标题与活跃指示
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                modifier = Modifier.size(6.dp),
                                                shape = CircleShape,
                                                color = if (session.active) TermiusGreen else TermiusTextMuted
                                            ) {}
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = session.displayTitle,
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = TermiusTextPrimary,
                                                maxLines = 1
                                            )
                                        }

                                        Icon(
                                            Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = TermiusTextMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    // 需求首句
                                    if (!session.firstMessage.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = session.firstMessage ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TermiusTextSecondary,
                                            maxLines = 2
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // 元数据小字：消息数、分支、修改时间
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = TermiusSurfaceVariant
                                            ) {
                                                Text(
                                                    text = "${session.messageCount} 条消息",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = TermiusTextSecondary,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }

                                            if (!session.branch.isNullOrBlank()) {
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = TermiusSurfaceVariant
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Default.AccountTree, contentDescription = null, modifier = Modifier.size(9.dp), tint = TermiusBlue)
                                                        Spacer(modifier = Modifier.width(3.dp))
                                                        Text(
                                                            text = session.branch ?: "",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontSize = 10.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = TermiusBlue
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        if (!session.modified.isNullOrBlank()) {
                                            Text(
                                                text = session.modified?.take(16)?.replace("T", " ") ?: "",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = TermiusTextMuted
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
