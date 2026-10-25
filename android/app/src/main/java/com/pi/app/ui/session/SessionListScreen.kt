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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.data.model.Session
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
    onNewSessionClick: () -> Unit
) {
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
                            fontFamily = FontFamily.Monospace
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Termius 风格的小圆点指示器
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isConfigured) TermiusGreen else TermiusTextMuted
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isConfigured) (deviceName.ifEmpty { "已连接" }) else "未连接 PC",
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
                        Icon(Icons.Default.Settings, contentDescription = "配置", tint = TermiusTextSecondary)
                    }
                }
            )
        },
        floatingActionButton = {
            if (isConfigured) {
                FloatingActionButton(
                    onClick = onNewSessionClick,
                    containerColor = TermiusBlue,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "新任务")
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!isConfigured) {
                // 首次打开或未配置时的干净引导界面 (绝不强弹对话框打扰用户)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        color = TermiusSurface,
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(
                                Icons.Default.Terminal,
                                contentDescription = null,
                                tint = TermiusBlue,
                                modifier = Modifier.size(44.dp)
                            )
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "尚未配对 PC 工作台",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = TermiusTextPrimary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "在 PC 运行 npm start 启动网关",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TermiusTextSecondary
                                )
                            }

                            Button(
                                onClick = onScanClick,
                                colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("立即扫码配对", fontWeight = FontWeight.Bold)
                            }

                            TextButton(onClick = onSettingsClick) {
                                Text("手动输入连接配置", color = TermiusTextSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            } else if (sessions.isEmpty() && !isLoading) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "暂无活跃任务",
                        style = MaterialTheme.typography.titleSmall,
                        color = TermiusTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "点击右下角按钮创建新任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = TermiusTextMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { session ->
                        SessionItemCard(session = session, onClick = { onSessionClick(session) })
                    }
                }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = TermiusBlue
                )
            }
        }
    }
}

@Composable
fun SessionItemCard(session: Session, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onClick() },
        color = TermiusSurface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, TermiusBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = session.title ?: "任务: ${session.id.take(8)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = TermiusTextPrimary
                )
                if (session.active) {
                    Surface(
                        color = Color(0xFF1B3D23),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "运行中",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = TermiusGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Tokens: ${session.tokenUsage}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = TermiusTextSecondary
                )
                if (session.cost > 0) {
                    Text(
                        text = "\$${String.format("%.4f", session.cost)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = TermiusTextSecondary
                    )
                }
            }
        }
    }
}
