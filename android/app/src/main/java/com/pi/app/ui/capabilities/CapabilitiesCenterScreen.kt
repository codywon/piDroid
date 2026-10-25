package com.pi.app.ui.capabilities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import com.pi.app.data.model.*
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesCenterScreen(
    models: List<ModelInfo>,
    skills: List<SkillInfo>,
    plugins: List<PluginPackage>,
    mcpConfig: McpCodeModeConfig,
    mcpServers: List<McpServerInfo>,
    isIgnoringBattery: Boolean = true,
    onRequestIgnoreBattery: () -> Unit = {},
    onToggleSkill: (String, Boolean) -> Unit,
    onSaveSkillAlias: (String, String) -> Unit = { _, _ -> },
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("模型配置", "技能中心", "插件扩展", "MCP协议")
    var selectedSkillForDetail by remember { mutableStateOf<SkillInfo?>(null) }

    Scaffold(
        containerColor = TermiusBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TermiusBlack,
                    titleContentColor = TermiusTextPrimary
                ),
                title = { Text("能力中心与系统设置", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TermiusTextSecondary)
                    }
                },
                actions = {
                    // 保活与电池优化状态指示
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isIgnoringBattery) TermiusGreen.copy(alpha = 0.15f) else TermiusRed.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, if (isIgnoringBattery) TermiusGreen else TermiusRed),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clickable { onRequestIgnoreBattery() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isIgnoringBattery) TermiusGreen else TermiusRed
                            ) {}
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isIgnoringBattery) "后台保活" else "未忽略电池优化",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isIgnoringBattery) TermiusGreen else TermiusRed
                            )
                        }
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
                0 -> ModelsConfigTab(models = models)
                1 -> SkillsConfigTab(
                    skills = skills,
                    onToggleSkill = onToggleSkill,
                    onViewDetail = { selectedSkillForDetail = it }
                )
                2 -> PluginsConfigTab(plugins = plugins)
                3 -> McpConfigTab(mcpConfig = mcpConfig, mcpServers = mcpServers)
            }
        }
    }

    // 技能详情弹窗 (Markdown 说明)
    selectedSkillForDetail?.let { skill ->
        AlertDialog(
            onDismissRequest = { selectedSkillForDetail = null },
            containerColor = TermiusSurface,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Extension, contentDescription = null, tint = TermiusBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(skill.name, color = TermiusTextPrimary, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    Text(
                        text = "技能说明与语义指南：",
                        style = MaterialTheme.typography.labelSmall,
                        color = TermiusTextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = skill.description ?: "暂无描述",
                        style = MaterialTheme.typography.bodySmall,
                        color = TermiusTextPrimary
                    )
                    if (!skill.filePath.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "路径: ${skill.filePath}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusTextMuted
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedSkillForDetail = null }) {
                    Text("关闭", color = TermiusBlue)
                }
            }
        )
    }
}

// ==========================================
// Tab 1: 模型管理 (对标截图 4)
// ==========================================
@Composable
fun ModelsConfigTab(models: List<ModelInfo>) {
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    val providers = remember(models) { models.map { it.provider }.distinct() }
    val filtered = remember(models, selectedProvider) {
        if (selectedProvider == null) models else models.filter { it.provider == selectedProvider }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoTitle(
                title = "智能体模型库",
                infoDescription = "管理不同 Provider 的大语言模型配置、推理规格及多模态输入开关。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Provider 切换胶囊
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selectedProvider == null) TermiusBlue.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                    border = BorderStroke(1.dp, if (selectedProvider == null) TermiusBlue else TermiusBorder),
                    modifier = Modifier.clickable { selectedProvider = null }
                ) {
                    Text(
                        text = "全部 (${models.size})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (selectedProvider == null) TermiusBlue else TermiusTextSecondary
                    )
                }

                providers.forEach { p ->
                    val isSel = selectedProvider == p
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSel) TermiusBlue.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                        border = BorderStroke(1.dp, if (isSel) TermiusBlue else TermiusBorder),
                        modifier = Modifier.clickable { selectedProvider = p }
                    ) {
                        Text(
                            text = p,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (isSel) TermiusBlue else TermiusTextSecondary
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(filtered, key = { "${it.provider}:${it.id}" }) { model ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = TermiusBlue)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(model.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = TermiusSurfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                Text(model.provider, style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Model ID: ${model.id}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TermiusTextMuted
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(color = TermiusSurfaceVariant, shape = RoundedCornerShape(4.dp)) {
                            Text("上下文: ${model.contextWindow / 1000}k", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        if (model.hasVision) {
                            Surface(color = TermiusGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("Vision 图像输入", style = MaterialTheme.typography.labelSmall, color = TermiusGreen, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        if (model.supportsThinking) {
                            Surface(color = TermiusBlue.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("Thinking 思考推演", style = MaterialTheme.typography.labelSmall, color = TermiusBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 2: 技能中心 (对标截图 2)
// ==========================================
@Composable
fun SkillsConfigTab(
    skills: List<SkillInfo>,
    onToggleSkill: (String, Boolean) -> Unit,
    onViewDetail: (SkillInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoTitle(
                title = "技能 (Skills: ${skills.size} 项可用)",
                infoDescription = "为 Agent 赋予专属工程技能包，如长效记忆反思、智能上下文节能等。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(skills, key = { it.name }) { skill ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onViewDetail(skill) }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(15.dp), tint = TermiusGreen)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
                            if (!skill.alias.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(color = TermiusBlue.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                    Text(skill.alias ?: "", style = MaterialTheme.typography.labelSmall, color = TermiusBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = skill.description ?: "无详细说明",
                            style = MaterialTheme.typography.bodySmall,
                            color = TermiusTextSecondary,
                            maxLines = 2
                        )
                    }

                    Switch(
                        checked = skill.enabled,
                        onCheckedChange = { onToggleSkill(skill.name, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = TermiusGreen,
                            checkedTrackColor = Color(0xFF1E3A24),
                            uncheckedThumbColor = TermiusTextMuted,
                            uncheckedTrackColor = TermiusSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}

// ==========================================
// Tab 3: 插件管理 (对标截图 3)
// ==========================================
@Composable
fun PluginsConfigTab(plugins: List<PluginPackage>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoTitle(
                title = "插件扩展 (Plugins: ${plugins.size} 个全局包)",
                infoDescription = "已加载的 NPM 模块与本地扩展包，为智能体扩展工具、指令与微前端看板。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(plugins, key = { it.packageName }) { pkg ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp), tint = TermiusBlue)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(pkg.packageName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = TermiusSurfaceVariant, shape = RoundedCornerShape(4.dp)) {
                                Text("v${pkg.version}", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (pkg.status == "loaded") TermiusGreen.copy(alpha = 0.15f) else TermiusTextMuted.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (pkg.status == "loaded") "已加载" else pkg.status,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pkg.status == "loaded") TermiusGreen else TermiusTextMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!pkg.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = pkg.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = TermiusTextSecondary
                        )
                    }

                    if (!pkg.source.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pkg.source,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusTextMuted
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// Tab 4: MCP 协议管理 (对标截图 1)
// ==========================================
@Composable
fun McpConfigTab(
    mcpConfig: McpCodeModeConfig,
    mcpServers: List<McpServerInfo>
) {
    var codeMode by remember { mutableStateOf(mcpConfig.mode) }
    var scriptMode by remember { mutableStateOf(mcpConfig.scriptMode) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoTitle(
                title = "MCP 协议与代码模式",
                infoDescription = "配置 Model Context Protocol 协议服务器、Code Mode 智能沙箱与工具预算。",
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 代码模式单选
        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("代码模式 (Code Mode)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("automatic" to "自动 (推荐)", "always" to "始终开启", "off" to "关闭").forEach { (mode, label) ->
                            val isSel = codeMode == mode
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) TermiusBlue.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                                border = BorderStroke(1.dp, if (isSel) TermiusBlue else TermiusBorder),
                                modifier = Modifier.weight(1f).clickable { codeMode = mode }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) TermiusBlue else TermiusTextSecondary,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 脚本模式与工具清单预算
        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("脚本模式 (Script Mode)", style = MaterialTheme.typography.bodyMedium, color = TermiusTextPrimary)
                            Text("使用一次性脚本批处理执行以节约 Token", style = MaterialTheme.typography.labelSmall, color = TermiusTextMuted)
                        }
                        Switch(
                            checked = scriptMode,
                            onCheckedChange = { scriptMode = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = TermiusGreen,
                                checkedTrackColor = Color(0xFF1E3A24)
                            )
                        )
                    }

                    HorizontalDivider(color = TermiusBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("工具清单预算", style = MaterialTheme.typography.bodyMedium, color = TermiusTextPrimary)
                        Text("${mcpConfig.tokenBudget} tokens", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = TermiusBlue)
                    }
                }
            }
        }

        item {
            Text("已连接的 MCP 服务器 (${mcpServers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
        }

        items(mcpServers, key = { it.name }) { server ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = CircleShape,
                                color = if (server.isConnected) TermiusGreen else TermiusRed
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = TermiusSurfaceVariant
                        ) {
                            Text(
                                text = "${server.toolExposureCount} 个可用工具",
                                style = MaterialTheme.typography.labelSmall,
                                color = TermiusTextSecondary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!server.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(server.description, style = MaterialTheme.typography.bodySmall, color = TermiusTextSecondary)
                    }

                    if (!server.url.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "URL: ${server.url}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusTextMuted
                        )
                    }
                }
            }
        }
    }
}
