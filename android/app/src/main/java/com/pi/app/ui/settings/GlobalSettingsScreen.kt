package com.pi.app.ui.settings

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pi.app.data.model.*
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*
import com.pi.app.ui.updater.AppUpdateDialog
import com.pi.app.ui.updater.UpdateUiState
import com.pi.app.updater.AppUpdateManager
import com.pi.app.updater.UpdateCheckResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    models: List<ModelInfo>,
    currentDefaultModelId: String,
    currentDefaultThinkingLevel: ThinkingLevel,
    onSetDefaultModel: (ModelInfo) -> Unit,
    onSetDefaultThinkingLevel: (ThinkingLevel) -> Unit,
    skills: List<SkillInfo>,
    onToggleSkill: (String, Boolean) -> Unit,
    onUpdateSkillAlias: (skillName: String, alias: String) -> Unit,
    plugins: List<PluginPackage>,
    mcpConfig: McpCodeModeConfig,
    mcpServers: List<McpServerInfo>,
    isIgnoringBattery: Boolean,
    onRequestIgnoreBattery: () -> Unit,
    serverUrl: String,
    authToken: String,
    deviceName: String,
    onDisconnect: () -> Unit,
    onClose: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("模型管理", "技能中心", "MCP协议", "插件扩展", "系统设置")

    var selectedSkillForDetail by remember { mutableStateOf<SkillInfo?>(null) }
    var editingSkillAlias by remember { mutableStateOf<SkillInfo?>(null) }
    var aliasInput by remember { mutableStateOf("") }

    // Termius 工业级应用内更新状态
    var updateUiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }
    var isCheckingUpdate by remember { mutableStateOf(false) }

    // 从系统设置授权未知应用安装权限返回时自适应续接安装
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (updateUiState is UpdateUiState.PermissionRequired) {
                    val permState = updateUiState as UpdateUiState.PermissionRequired
                    if (AppUpdateManager.canInstallPackages(context)) {
                        updateUiState = UpdateUiState.ReadyToInstall(permState.apkFile, permState.info)
                        AppUpdateManager.installApk(context, permState.apkFile)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.textPrimary
                ),
                title = {
                    Text(
                        text = "全局设置与能力中心",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = theme.textSecondary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(theme.background)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = theme.surfaceContainerLow,
                contentColor = theme.primary,
                divider = { HorizontalDivider(color = theme.outline) },
                edgePadding = 12.dp
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
                                color = if (selectedTab == index) theme.primary else theme.textSecondary
                            )
                        }
                    )
                }
            }

            when (selectedTab) {
                0 -> GlobalModelsTab(
                    models = models,
                    currentDefaultModelId = currentDefaultModelId,
                    currentDefaultThinkingLevel = currentDefaultThinkingLevel,
                    onSetDefaultModel = onSetDefaultModel,
                    onSetDefaultThinkingLevel = onSetDefaultThinkingLevel
                )
                1 -> GlobalSkillsTab(
                    skills = skills,
                    onToggleSkill = onToggleSkill,
                    onEditAlias = {
                        editingSkillAlias = it
                        aliasInput = it.alias ?: ""
                    },
                    onViewDetail = { selectedSkillForDetail = it }
                )
                2 -> GlobalMcpTab(
                    mcpConfig = mcpConfig,
                    mcpServers = mcpServers
                )
                3 -> GlobalPluginsTab(plugins = plugins)
                4 -> GlobalSystemSettingsTab(
                    isIgnoringBattery = isIgnoringBattery,
                    onRequestIgnoreBattery = onRequestIgnoreBattery,
                    serverUrl = serverUrl,
                    authToken = authToken,
                    deviceName = deviceName,
                    onDisconnect = onDisconnect,
                    isCheckingUpdate = isCheckingUpdate,
                    onCheckUpdate = {
                        if (isCheckingUpdate) return@GlobalSystemSettingsTab
                        isCheckingUpdate = true
                        Toast.makeText(context, "正在检查最新发布版本...", Toast.LENGTH_SHORT).show()
                        coroutineScope.launch {
                            when (val result = AppUpdateManager.checkUpdate(context, isManual = true)) {
                                is UpdateCheckResult.HasUpdate -> {
                                    updateUiState = UpdateUiState.HasUpdate(result.info)
                                }
                                is UpdateCheckResult.NoUpdate -> {
                                    Toast.makeText(context, "当前已是最新版本 (v${result.currentVersion})", Toast.LENGTH_SHORT).show()
                                }
                                is UpdateCheckResult.Error -> {
                                    Toast.makeText(context, "检查更新失败: ${result.message}", Toast.LENGTH_LONG).show()
                                }
                                else -> {}
                            }
                            isCheckingUpdate = false
                        }
                    }
                )
            }
        }
    }

    // 工业级应用内自动更新弹窗 (参考 Termius)
    AppUpdateDialog(
        state = updateUiState,
        onStartDownload = { info ->
            coroutineScope.launch {
                updateUiState = UpdateUiState.Downloading(
                    info = info,
                    progress = 0f,
                    downloadedBytes = 0L,
                    totalBytes = info.fileSize,
                    speedText = "连接镜像中...",
                    channelName = "智能优选通道"
                )
                val result = AppUpdateManager.downloadApk(context, info) { progress, downloaded, total, speed, channel ->
                    updateUiState = UpdateUiState.Downloading(info, progress, downloaded, total, speed, channel)
                }
                result.onSuccess { apkFile ->
                    if (AppUpdateManager.canInstallPackages(context)) {
                        updateUiState = UpdateUiState.ReadyToInstall(apkFile, info)
                        AppUpdateManager.installApk(context, apkFile)
                    } else {
                        updateUiState = UpdateUiState.PermissionRequired(apkFile, info)
                    }
                }.onFailure { err ->
                    updateUiState = UpdateUiState.Error(err.message ?: "下载失败", info)
                }
            }
        },
        onInstall = { file ->
            if (AppUpdateManager.canInstallPackages(context)) {
                AppUpdateManager.installApk(context, file)
            } else {
                val currentInfo = (updateUiState as? UpdateUiState.ReadyToInstall)?.info
                if (currentInfo != null) {
                    updateUiState = UpdateUiState.PermissionRequired(file, currentInfo)
                }
            }
        },
        onIgnore = { tagName ->
            AppUpdateManager.ignoreVersion(context, tagName)
            updateUiState = UpdateUiState.Idle
        },
        onDismiss = {
            updateUiState = UpdateUiState.Idle
        }
    )

    // 技能说明 Markdown 弹窗
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
                            text = "文件路径: ${skill.filePath}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusTextMuted
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedSkillForDetail = null }) {
                    Text("完成", color = TermiusBlue)
                }
            }
        )
    }

    // 技能别名编辑弹窗
    editingSkillAlias?.let { skill ->
        AlertDialog(
            onDismissRequest = { editingSkillAlias = null },
            containerColor = TermiusSurface,
            title = { Text("编辑技能别名", color = TermiusTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原技能名称: ${skill.name}", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text("自定义别名 (如: 上下文节能)", color = TermiusTextSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TermiusBlue,
                            unfocusedBorderColor = TermiusBorder,
                            focusedTextColor = TermiusTextPrimary,
                            unfocusedTextColor = TermiusTextPrimary
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateSkillAlias(skill.name, aliasInput.trim())
                    editingSkillAlias = null
                }) {
                    Text("保存", color = TermiusBlue)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSkillAlias = null }) {
                    Text("取消", color = TermiusTextMuted)
                }
            }
        )
    }
}

// ==========================================
// Tab 1: 全局模型库管理
// ==========================================
@Composable
fun GlobalModelsTab(
    models: List<ModelInfo>,
    currentDefaultModelId: String,
    currentDefaultThinkingLevel: ThinkingLevel,
    onSetDefaultModel: (ModelInfo) -> Unit,
    onSetDefaultThinkingLevel: (ThinkingLevel) -> Unit
) {
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    val providers = remember(models) { models.map { it.provider }.distinct() }
    val filtered = remember(models, selectedProvider) {
        if (selectedProvider == null) models else models.filter { it.provider == selectedProvider }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoTitle(
                title = "默认推理思考档位",
                infoDescription = "为新启动的任务设定全局默认思考深度，也可在每个会话内临时切换。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThinkingLevel.entries.forEach { level ->
                    val isSel = currentDefaultThinkingLevel == level
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSel) Color(0xFF1B3D5A) else TermiusSurface,
                        border = BorderStroke(1.dp, if (isSel) TermiusBlue else TermiusBorder),
                        modifier = Modifier.clickable { onSetDefaultThinkingLevel(level) }
                    ) {
                        Text(
                            text = level.value,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) TermiusBlue else TermiusTextSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = TermiusBorder)
        }

        item {
            InfoTitle(
                title = "已同步的模型列表 (${models.size})",
                infoDescription = "从 PC 伴侣网关动态检索的所有可用大语言模型与多模态支持状态。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(6.dp))

            // Provider 切换
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selectedProvider == null) TermiusBlue.copy(alpha = 0.2f) else TermiusSurface,
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
                        color = if (isSel) TermiusBlue.copy(alpha = 0.2f) else TermiusSurface,
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
        }

        items(filtered, key = { "${it.provider}:${it.id}" }) { model ->
            val isDefault = model.id == currentDefaultModelId
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, if (isDefault) TermiusBlue else TermiusBorder),
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

                        if (isDefault) {
                            Surface(color = TermiusBlue.copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                                Text("默认模型", style = MaterialTheme.typography.labelSmall, color = TermiusBlue, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        } else {
                            TextButton(onClick = { onSetDefaultModel(model) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                Text("设为默认", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "ID: ${model.id}  ·  上下文: ${model.contextWindow / 1000}k",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = TermiusTextMuted
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
// Tab 2: 全局技能中心
// ==========================================
@Composable
fun GlobalSkillsTab(
    skills: List<SkillInfo>,
    onToggleSkill: (String, Boolean) -> Unit,
    onEditAlias: (SkillInfo) -> Unit,
    onViewDetail: (SkillInfo) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoTitle(
                title = "智能体技能包 (${skills.size} 项)",
                infoDescription = "全局生效的工程技能包。支持为每个技能设定别名以直观表达功能语义。",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(skills, key = { it.name }) { skill ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f).clickable { onViewDetail(skill) }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(15.dp), tint = TermiusGreen)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = TermiusTextPrimary)

                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = if (skill.alias.isNullOrBlank()) TermiusSurfaceVariant else TermiusBlue.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { onEditAlias(skill) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = skill.alias?.takeIf { it.isNotBlank() } ?: "加别名 ✎",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (skill.alias.isNullOrBlank()) TermiusTextMuted else TermiusBlue
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = skill.description ?: "无说明文档",
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
// Tab 3: MCP 协议
// ==========================================
@Composable
fun GlobalMcpTab(
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
                title = "MCP 协议与沙箱代码模式",
                infoDescription = "配置 Model Context Protocol 协议服务器、Code Mode 沙箱与工具预算。",
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

        // 脚本模式
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

                        Surface(shape = RoundedCornerShape(4.dp), color = TermiusSurfaceVariant) {
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
                        Text(server.description ?: "", style = MaterialTheme.typography.bodySmall, color = TermiusTextSecondary)
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

// ==========================================
// Tab 4: 插件扩展
// ==========================================
@Composable
fun GlobalPluginsTab(plugins: List<PluginPackage>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            InfoTitle(
                title = "插件扩展 (${plugins.size} 个全局包)",
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
                                text = if (pkg.status == "loaded") "已加载" else (pkg.status ?: "已加载"),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pkg.status == "loaded") TermiusGreen else TermiusTextMuted,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    if (!pkg.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(pkg.description ?: "", style = MaterialTheme.typography.bodySmall, color = TermiusTextSecondary)
                    }

                    if (!pkg.source.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = pkg.source ?: "",
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
// Tab 5: 系统设置 (参考 Termius 主题切换与系统保活)
// ==========================================
@Composable
fun GlobalSystemSettingsTab(
    isIgnoringBattery: Boolean,
    onRequestIgnoreBattery: () -> Unit,
    serverUrl: String,
    authToken: String,
    deviceName: String,
    onDisconnect: () -> Unit,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val currentVersion = remember { AppUpdateManager.getCurrentVersionName(context) }
    var hapticsEnabled by remember { mutableStateOf(true) }
    var autoTtsEnabled by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoTitle(
                title = "应用主题外观",
                infoDescription = "参考 Termius 配色体系，默认浅色纸质明眸，提供纯黑、冰蓝、翡翠等深色高质感配色，杜绝紫色。",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "当前主题: ${ThemeManager.currentTheme.titleZh} (${if (ThemeManager.currentTheme.isDark) "深色" else "浅色"})",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary
                    )

                    // 5 大主题色卡选择器
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AppTheme.entries.forEach { appTheme ->
                            val isSelected = ThemeManager.currentTheme == appTheme
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) appTheme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) appTheme.primary else theme.outline
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { ThemeManager.setTheme(appTheme) }
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .background(
                                                brush = androidx.compose.ui.graphics.Brush.linearGradient(appTheme.previewGradient),
                                                shape = CircleShape
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = appTheme.titleZh,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) appTheme.primary else theme.textPrimary,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = theme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("马达触觉反馈", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                            Text("任务完成时两次短震动提醒，审批时重震提醒", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                        }
                        Switch(
                            checked = hapticsEnabled,
                            onCheckedChange = { hapticsEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = theme.primary,
                                checkedTrackColor = theme.primary.copy(alpha = 0.3f)
                            )
                        )
                    }

                    HorizontalDivider(color = theme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("任务自动语音播报", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                            Text("默认关闭；建议在工作台按需点击朗读", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                        }
                        Switch(
                            checked = autoTtsEnabled,
                            onCheckedChange = { autoTtsEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = theme.primary,
                                checkedTrackColor = theme.primary.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }
        }

        item {
            InfoTitle(
                title = "Termius 级别长任务后台保活",
                infoDescription = "防止智能体执行 20~40 分钟代码大任务时被 Android 杀后台或断网。",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("电池优化白名单", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                            Text(
                                if (isIgnoringBattery) "已加入白名单，长任务受系统保护" else "未忽略电池优化，锁屏后可能断开",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isIgnoringBattery) Color(0xFF2EA043) else Color(0xFFCF222E)
                            )
                        }
                        if (!isIgnoringBattery) {
                            Button(
                                onClick = onRequestIgnoreBattery,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("立即授权", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    HorizontalDivider(color = theme.outline)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("前台常驻服务与 WakeLock", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                            Text("任务执行时自适应持有 CPU 唤醒锁，完成后自动释放省电", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                        }
                        Surface(color = Color(0xFF2EA043).copy(alpha = 0.2f), shape = RoundedCornerShape(4.dp)) {
                            Text("常驻就绪", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2EA043), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }
        }

        item {
            InfoTitle(
                title = "当前主机连接",
                infoDescription = "已配对的 PC 端伴侣网关与安全凭据。",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("设备名称: $deviceName", style = MaterialTheme.typography.bodySmall, color = theme.textPrimary)
                    Text("网关地址: $serverUrl", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = theme.primary)
                    Text("安全凭据: ${authToken.take(10)}***", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = theme.textMuted)

                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = onDisconnect,
                        border = BorderStroke(1.dp, Color(0xFFCF222E)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF222E)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("断开配对并重新配置", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // ==========================================
        // 关于与应用更新 (参考 Termius 架构)
        // ==========================================
        item {
            InfoTitle(
                title = "关于与软件更新",
                infoDescription = "查看软件版本信息、检查最新发布版本并进行无缝平滑在线升级。",
                style = MaterialTheme.typography.titleMedium
            )
        }

        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "piDroid",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Surface(
                                    color = theme.primary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "v$currentVersion",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        color = theme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "已注入全局统一签名 Key · 支持无缝覆盖安装",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
                        }

                        Button(
                            onClick = onCheckUpdate,
                            enabled = !isCheckingUpdate,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.primary,
                                contentColor = if (theme.isDark) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = if (theme.isDark) Color.Black else Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("检测中...", fontSize = 12.sp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("检查更新", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    HorizontalDivider(color = theme.outline.copy(alpha = 0.35f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("构建渠道", style = MaterialTheme.typography.bodySmall, color = theme.textPrimary)
                        Text(
                            text = "GitHub Actions CI/CD (Official)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textSecondary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("开源仓库", style = MaterialTheme.typography.bodySmall, color = theme.textPrimary)
                        Text(
                            text = "github.com/codywon/piDroid",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.primary
                        )
                    }
                }
            }
        }
    }
}
