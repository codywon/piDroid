package com.pi.app.ui.capabilities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.data.model.McpServerInfo
import com.pi.app.data.model.SkillInfo
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesCenterScreen(
    skills: List<SkillInfo>,
    mcpServers: List<McpServerInfo>,
    isIgnoringBattery: Boolean = true,
    onRequestIgnoreBattery: () -> Unit = {},
    onToggleSkill: (String, Boolean) -> Unit,
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("技能 (Skills)", "MCP 协议", "系统偏好")

    Scaffold(
        containerColor = TermiusBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TermiusBlack,
                    titleContentColor = TermiusTextPrimary
                ),
                title = { Text("能力与扩展", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TermiusTextSecondary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(TermiusBlack)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = TermiusSurface,
                contentColor = TermiusBlue,
                divider = { HorizontalDivider(color = TermiusBorder) }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTab == index) TermiusBlue else TermiusTextSecondary
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> SkillsTab(skills = skills, onToggleSkill = onToggleSkill)
                1 -> McpTab(mcpServers = mcpServers)
                2 -> PreferencesTab(
                    isIgnoringBattery = isIgnoringBattery,
                    onRequestIgnoreBattery = onRequestIgnoreBattery
                )
            }
        }
    }
}

@Composable
fun SkillsTab(skills: List<SkillInfo>, onToggleSkill: (String, Boolean) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(skills) { skill ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            InfoTitle(
                                title = skill.name,
                                infoDescription = skill.description ?: "工作区内置技能",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = TermiusSurfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = skill.category ?: "技能",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                                    color = TermiusTextSecondary
                                )
                            }
                        }
                    }
                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { onToggleSkill(skill.name, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = TermiusBlue,
                            uncheckedThumbColor = TermiusTextMuted,
                            uncheckedTrackColor = TermiusSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun McpTab(mcpServers: List<McpServerInfo>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(mcpServers) { mcp ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InfoTitle(
                            title = mcp.name,
                            infoDescription = "MCP 服务器 ID: ${mcp.id}\n暴露工具总数: ${mcp.tools.size}\n用于向 Agent 提供外部上下文调用。",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Surface(
                            color = if (mcp.status == "active") Color(0xFF1B3D23) else Color(0xFF381A1C),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = if (mcp.status == "active") "● 在线" else "离线",
                                color = if (mcp.status == "active") TermiusGreen else TermiusRed,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        mcp.tools.take(3).forEach { tool ->
                            Surface(
                                color = TermiusSurfaceVariant,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = tool,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = TermiusTextSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (mcp.tools.size > 3) {
                            Text(
                                text = "+${mcp.tools.size - 3}",
                                style = MaterialTheme.typography.labelSmall,
                                color = TermiusTextMuted,
                                modifier = Modifier.align(Alignment.CenterVertically)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreferencesTab(
    isIgnoringBattery: Boolean = true,
    onRequestIgnoreBattery: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 1. 保活与后台连接 (Termius 级别)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TermiusSurface,
            border = BorderStroke(1.dp, TermiusBorder)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    InfoTitle(
                        title = "后台保活守护",
                        infoDescription = "参考 Termius 架构：包含高优先级前台服务常驻、任务自动唤醒锁 (WakeLock & WifiLock) 与电池白名单豁免，保证息屏或切换后台时 SSE 长连接不被系统掐断。"
                    )
                    Text(
                        text = if (isIgnoringBattery) "● 已加入白名单" else "未加入",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isIgnoringBattery) TermiusGreen else TermiusRed
                    )
                }

                if (!isIgnoringBattery) {
                    Button(
                        onClick = onRequestIgnoreBattery,
                        colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("一键加入免电池优化白名单", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // 2. 上下文提醒
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TermiusSurface,
            border = BorderStroke(1.dp, TermiusBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoTitle(
                    title = "上下文溢出预警",
                    infoDescription = "当会话累积消耗超过模型上下文容量的 80% 时，在手机控制条变色提示您进行压缩 (Compact)。"
                )
                var autoCompactNotify by remember { mutableStateOf(true) }
                Switch(
                    checked = autoCompactNotify,
                    onCheckedChange = { autoCompactNotify = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TermiusBlue,
                        uncheckedThumbColor = TermiusTextMuted,
                        uncheckedTrackColor = TermiusSurfaceVariant
                    )
                )
            }
        }

        // 3. 语音纯化
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            color = TermiusSurface,
            border = BorderStroke(1.dp, TermiusBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoTitle(
                    title = "语音播报智能纯化",
                    infoDescription = "朗读时自动跳过所有代码块、JSON、终端命令和 ANSI 控制符，只朗读关键意图和执行结果。"
                )
                var purifyAudio by remember { mutableStateOf(true) }
                Switch(
                    checked = purifyAudio,
                    onCheckedChange = { purifyAudio = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = TermiusBlue,
                        uncheckedThumbColor = TermiusTextMuted,
                        uncheckedTrackColor = TermiusSurfaceVariant
                    )
                )
            }
        }
    }
}
