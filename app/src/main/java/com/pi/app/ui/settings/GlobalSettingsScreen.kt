package com.pi.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pi.app.data.model.*
import com.pi.app.keepalive.KeepAliveManager
import com.pi.app.network.PiApiClient
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.components.PiDroidLogo
import com.pi.app.ui.theme.*
import com.pi.app.ui.updater.AppUpdateDialog
import com.pi.app.ui.updater.UpdateUiState
import com.pi.app.updater.AppUpdateManager
import com.pi.app.updater.UpdateCheckResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSettingsScreen(
    apiClient: PiApiClient? = null,
    models: List<ModelInfo>,
    currentDefaultModelId: String,
    currentDefaultThinkingLevel: ThinkingLevel,
    onSetDefaultModel: (ModelInfo) -> Unit,
    onSetDefaultThinkingLevel: (ThinkingLevel) -> Unit,
    onRefreshModels: () -> Unit = {},
    onSaveProviderApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    onSaveCustomModel: (
        providerName: String,
        baseUrl: String,
        apiKey: String,
        apiType: String,
        modelId: String,
        modelName: String,
        contextWindow: Long,
        maxTokens: Long,
        reasoning: Boolean,
        supportsVision: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onDeleteModel: (providerName: String, modelId: String) -> Unit = { _, _ -> },
    onLoadModelsRawConfig: suspend () -> String? = { null },
    onSaveModelsRawConfig: (String) -> Unit = {},
    skills: List<SkillInfo>,
    onToggleSkill: (SkillInfo, Boolean) -> Unit,
    onUpdateSkillAlias: (skillName: String, alias: String) -> Unit,
    onLoadSkillContent: suspend (SkillInfo) -> String? = { null },
    onSaveSkillContent: (SkillInfo, String) -> Unit = { _, _ -> },
    onInstallSkill: (pkgSource: String, scope: String) -> Unit = { _, _ -> },
    plugins: List<PluginPackage>,
    onManagePlugin: (action: String, source: String, scope: String) -> Unit = { _, _, _ -> },
    onEditPlugin: (oldPkg: PluginPackage, newSource: String, newVersion: String, newDesc: String, newScope: String) -> Unit = { _, _, _, _, _ -> },
    mcpConfig: McpCodeModeConfig,
    onUpdateMcpCodeMode: (mode: String, scriptMode: Boolean, tokenBudget: Int) -> Unit = { _, _, _ -> },
    mcpServers: List<McpServerInfo>,
    onAddMcpServer: (name: String, transport: String, endpoint: String, description: String, scope: String) -> Unit = { _, _, _, _, _ -> },
    onManageMcpServer: (action: String, name: String) -> Unit = { _, _ -> },
    isIgnoringBattery: Boolean,
    onRequestIgnoreBattery: () -> Unit,
    persistentNotifyEnabled: Boolean = true,
    onTogglePersistentNotify: (Boolean) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onToggleHaptics: (Boolean) -> Unit = {},
    onTestHaptics: () -> Unit = {},
    completionSoundEnabled: Boolean = true,
    onToggleCompletionSound: (Boolean) -> Unit = {},
    onTestCompletionSound: () -> Unit = {},
    serverUrl: String,
    authToken: String,
    deviceName: String,
    onManualConfigConfirm: (url: String, token: String, name: String) -> Unit = { _, _, _ -> },
    onScanClick: () -> Unit = {},
    onDisconnect: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler { onClose() }

    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("模型管理", "技能中心", "MCP协议", "插件扩展", "系统设置")

    var selectedSkillForDetail by remember { mutableStateOf<SkillInfo?>(null) }
    var skillMarkdownContent by remember { mutableStateOf<String?>(null) }
    var isLoadingSkillMd by remember { mutableStateOf(false) }
    var isEditingSkillMd by remember { mutableStateOf(false) }
    var skillEditBuffer by remember { mutableStateOf("") }

    var editingSkillAlias by remember { mutableStateOf<SkillInfo?>(null) }
    var aliasInput by remember { mutableStateOf("") }
    var showServerConfigDialog by remember { mutableStateOf(false) }

    // 应用内更新状态
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
                0 -> {
                    if (apiClient != null) {
                        GlobalModelsConfigTab(
                            apiClient = apiClient,
                            currentDefaultThinkingLevel = currentDefaultThinkingLevel,
                            onSetDefaultThinkingLevel = onSetDefaultThinkingLevel,
                            onModelsConfigChanged = onRefreshModels
                        )
                    } else {
                        GlobalModelsTab(
                            models = models,
                            currentDefaultModelId = currentDefaultModelId,
                            currentDefaultThinkingLevel = currentDefaultThinkingLevel,
                            onSetDefaultModel = onSetDefaultModel,
                            onSetDefaultThinkingLevel = onSetDefaultThinkingLevel,
                            onRefreshModels = onRefreshModels,
                            onSaveProviderApiKey = onSaveProviderApiKey,
                            onSaveCustomModel = onSaveCustomModel,
                            onDeleteModel = onDeleteModel,
                            onLoadModelsRawConfig = onLoadModelsRawConfig,
                            onSaveModelsRawConfig = onSaveModelsRawConfig
                        )
                    }
                }
                1 -> GlobalSkillsTab(
                    skills = skills,
                    onToggleSkill = onToggleSkill,
                    onEditAlias = {
                        editingSkillAlias = it
                        aliasInput = it.alias ?: ""
                    },
                    onViewDetail = { skill ->
                        selectedSkillForDetail = skill
                        isEditingSkillMd = true
                        val initialMd = skill.content?.takeIf { it.isNotBlank() }
                            ?: buildString {
                                appendLine("---")
                                appendLine("name: ${skill.name}")
                                appendLine("description: ${skill.description ?: "技能说明与执行规范"}")
                                appendLine("---")
                                appendLine()
                                appendLine("# ${skill.name}")
                                appendLine()
                                appendLine(skill.description ?: "在此编写技能说明与系统指令...")
                            }
                        skillMarkdownContent = initialMd
                        skillEditBuffer = initialMd
                        isLoadingSkillMd = true
                        coroutineScope.launch {
                            val loaded = onLoadSkillContent(skill)
                            if (!loaded.isNullOrBlank()) {
                                skillMarkdownContent = loaded
                                skillEditBuffer = loaded
                            }
                            isLoadingSkillMd = false
                        }
                    },
                    onInstallSkill = onInstallSkill
                )
                2 -> GlobalMcpTab(
                    mcpConfig = mcpConfig,
                    onUpdateMcpCodeMode = onUpdateMcpCodeMode,
                    mcpServers = mcpServers,
                    onAddMcpServer = onAddMcpServer,
                    onManageMcpServer = onManageMcpServer
                )
                3 -> GlobalPluginsTab(
                    plugins = plugins,
                    onManagePlugin = onManagePlugin,
                    onEditPlugin = onEditPlugin
                )
                4 -> GlobalSystemSettingsTab(
                    isIgnoringBattery = isIgnoringBattery,
                    onRequestIgnoreBattery = onRequestIgnoreBattery,
                    persistentNotifyEnabled = persistentNotifyEnabled,
                    onTogglePersistentNotify = onTogglePersistentNotify,
                    hapticsEnabled = hapticsEnabled,
                    onToggleHaptics = onToggleHaptics,
                    onTestHaptics = onTestHaptics,
                    completionSoundEnabled = completionSoundEnabled,
                    onToggleCompletionSound = onToggleCompletionSound,
                    onTestCompletionSound = onTestCompletionSound,
                    serverUrl = serverUrl,
                    authToken = authToken,
                    deviceName = deviceName,
                    onEditConnection = { showServerConfigDialog = true },
                    onScanQr = onScanClick,
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

    // 技能说明与 SKILL.md 查看/在线编辑弹窗
    selectedSkillForDetail?.let { skill ->
        AlertDialog(
            onDismissRequest = { selectedSkillForDetail = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Extension, contentDescription = null, tint = theme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = skill.displayTitle,
                            color = theme.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isEditingSkillMd) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainer,
                            border = BorderStroke(1.dp, if (isEditingSkillMd) theme.primary else theme.outline),
                            modifier = Modifier.clickable { isEditingSkillMd = true }
                        ) {
                            Text(
                                text = "源码编辑 ✎",
                                fontSize = 11.sp,
                                fontWeight = if (isEditingSkillMd) FontWeight.Bold else FontWeight.Normal,
                                color = if (isEditingSkillMd) theme.primary else theme.textSecondary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (!isEditingSkillMd) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainer,
                            border = BorderStroke(1.dp, if (!isEditingSkillMd) theme.primary else theme.outline),
                            modifier = Modifier.clickable { isEditingSkillMd = false }
                        ) {
                            Text(
                                text = "效果预览",
                                fontSize = 11.sp,
                                fontWeight = if (!isEditingSkillMd) FontWeight.Bold else FontWeight.Normal,
                                color = if (!isEditingSkillMd) theme.primary else theme.textSecondary,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val displayPath = skill.filePath?.takeIf { it.isNotBlank() }
                        ?: skill.baseDir?.takeIf { it.isNotBlank() }?.let { "${it.trimEnd('/', '\\')}/SKILL.md" }
                        ?: "~/.pi/agent/skills/${skill.name}/SKILL.md"
                    Surface(
                        color = theme.surfaceContainer,
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(0.5.dp, theme.outline)
                    ) {
                        Text(
                            text = displayPath,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.5.sp,
                            color = theme.textMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    if (isLoadingSkillMd) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = theme.primary)
                            Text("正在从网关加载 SKILL.md 源码...", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = theme.textSecondary)
                        }
                    } else if (isEditingSkillMd) {
                        OutlinedTextField(
                            value = skillEditBuffer,
                            onValueChange = { skillEditBuffer = it },
                            label = { Text("SKILL.md 正文与指令源码", fontSize = 11.sp, color = theme.textSecondary) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = theme.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = theme.primary,
                                unfocusedBorderColor = theme.outline,
                                focusedTextColor = theme.textPrimary,
                                unfocusedTextColor = theme.textPrimary
                            )
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            MarkdownRenderer(content = skillMarkdownContent?.ifBlank { skillEditBuffer } ?: skill.description ?: "暂无描述")
                        }
                    }
                }
            },
            confirmButton = {
                if (isEditingSkillMd) {
                    TextButton(
                        onClick = {
                            skillMarkdownContent = skillEditBuffer
                            onSaveSkillContent(skill, skillEditBuffer)
                            isEditingSkillMd = false
                        }
                    ) {
                        Text("保存修改", color = theme.primary, fontWeight = FontWeight.Bold)
                    }
                } else {
                    TextButton(onClick = { selectedSkillForDetail = null }) {
                        Text("关闭", color = theme.primary)
                    }
                }
            },
            dismissButton = {
                if (isEditingSkillMd) {
                    TextButton(onClick = { selectedSkillForDetail = null }) {
                        Text("取消", color = theme.textMuted)
                    }
                }
            }
        )
    }

    // 技能别名编辑弹窗
    editingSkillAlias?.let { skill ->
        AlertDialog(
            onDismissRequest = { editingSkillAlias = null },
            containerColor = theme.surfaceContainerLow,
            title = { Text("编辑技能别名", color = theme.textPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("原技能名称: ${skill.name}", style = MaterialTheme.typography.labelSmall, color = theme.textSecondary)
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text("自定义别名 (如: 上下文节能)", color = theme.textSecondary) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = theme.primary,
                            unfocusedBorderColor = theme.outline,
                            focusedTextColor = theme.textPrimary,
                            unfocusedTextColor = theme.textPrimary
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateSkillAlias(skill.name, aliasInput.trim())
                    editingSkillAlias = null
                }) {
                    Text("保存", color = theme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingSkillAlias = null }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }

    if (showServerConfigDialog) {
        ServerConfigDialog(
            initialUrl = serverUrl.ifBlank { "http://192.168.1.100:4100" },
            initialToken = authToken,
            initialName = deviceName.ifBlank { "My PC" },
            onConfirm = { url, token, name ->
                showServerConfigDialog = false
                onManualConfigConfirm(url, token, name)
            },
            onDismiss = { showServerConfigDialog = false }
        )
    }
}

// ==========================================
// Tab 1: 全局模型库管理 (对标 pi-web ModelsConfigModal & AuthModal)
// ==========================================
@Composable
fun GlobalModelsTab(
    models: List<ModelInfo>,
    currentDefaultModelId: String,
    currentDefaultThinkingLevel: ThinkingLevel,
    onSetDefaultModel: (ModelInfo) -> Unit,
    onSetDefaultThinkingLevel: (ThinkingLevel) -> Unit,
    onRefreshModels: () -> Unit = {},
    onSaveProviderApiKey: (provider: String, apiKey: String) -> Unit = { _, _ -> },
    onSaveCustomModel: (
        providerName: String,
        baseUrl: String,
        apiKey: String,
        apiType: String,
        modelId: String,
        modelName: String,
        contextWindow: Long,
        maxTokens: Long,
        reasoning: Boolean,
        supportsVision: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onDeleteModel: (providerName: String, modelId: String) -> Unit = { _, _ -> },
    onLoadModelsRawConfig: suspend () -> String? = { null },
    onSaveModelsRawConfig: (rawJson: String) -> Unit = {}
) {
    val theme = LocalAppTheme.current
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    val providers = remember(models) { models.map { it.provider }.distinct() }
    val filtered = remember(models, selectedProvider) {
        if (selectedProvider == null) models else models.filter { it.provider == selectedProvider }
    }

    // 弹窗状态：配置 Provider API Key / 新增或编辑自定义模型 / 原始 models.json 源码编辑器
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var editingModelTarget by remember { mutableStateOf<ModelInfo?>(null) }
    var showCustomModelDialog by remember { mutableStateOf(false) }
    var showRawJsonDialog by remember { mutableStateOf(false) }
    var rawJsonContent by remember { mutableStateOf("") }
    var isLoadingRawJson by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoTitle(
                title = "默认推理思考档位",
                infoDescription = "为新启动的任务设定全局默认思考深度，也可在每个会话内随时切换。",
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
                        color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainerLow,
                        border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                        modifier = Modifier.clickable { onSetDefaultThinkingLevel(level) }
                    ) {
                        Text(
                            text = level.value,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) theme.primary else theme.textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = theme.outline)
        }

        // 模型与提供商管理操作栏 (新增/编辑模型、配置 API Key、编辑 models.json、刷新目录)
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoTitle(
                    title = "模型与提供商管理 (${models.size})",
                    infoDescription = "支持添加自定义提供商与模型、配置各厂商 API 密钥、直接编辑 models.json 配置文件，或修改现有模型参数。",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        editingModelTarget = null
                        showCustomModelDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("新增模型", fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showApiKeyDialog = true },
                    border = BorderStroke(1.dp, theme.outline),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.primary)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("API 密钥", fontSize = 11.sp, color = theme.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = { showRawJsonDialog = true },
                    border = BorderStroke(1.dp, theme.outline),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.primary)
                    Spacer(modifier = Modifier.width(3.dp))
                    Text("JSON 配置", fontSize = 11.sp, color = theme.textPrimary, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                }

                OutlinedButton(
                    onClick = onRefreshModels,
                    border = BorderStroke(1.dp, theme.outline),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新模型", modifier = Modifier.size(15.dp), tint = theme.textSecondary)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Provider 筛选栏
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (selectedProvider == null) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainerLow,
                    border = BorderStroke(1.dp, if (selectedProvider == null) theme.primary else theme.outline),
                    modifier = Modifier.clickable { selectedProvider = null }
                ) {
                    Text(
                        text = "全部 (${models.size})",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        color = if (selectedProvider == null) theme.primary else theme.textSecondary
                    )
                }

                providers.forEach { p ->
                    val isSel = selectedProvider == p
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainerLow,
                        border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                        modifier = Modifier.clickable { selectedProvider = p }
                    ) {
                        Text(
                            text = p,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = if (isSel) theme.primary else theme.textSecondary
                        )
                    }
                }
            }
        }

        items(filtered, key = { "${it.provider}:${it.id}" }) { model ->
            val isDefault = model.id == currentDefaultModelId
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, if (isDefault) theme.primary else theme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingModelTarget = model
                        showCustomModelDialog = true
                    }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Memory, contentDescription = null, modifier = Modifier.size(16.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = theme.surfaceContainer, shape = RoundedCornerShape(4.dp)) {
                                Text(
                                    text = model.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TextButton(
                                onClick = {
                                    editingModelTarget = model
                                    showCustomModelDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(13.dp), tint = theme.primary)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("编辑", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                            }

                            if (isDefault) {
                                Surface(color = theme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                    Text("默认模型", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                }
                            } else {
                                TextButton(onClick = { onSetDefaultModel(model) }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                    Text("设为默认", style = MaterialTheme.typography.labelSmall, color = theme.textSecondary, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    val ctxText = when {
                        model.contextWindow >= 1_000_000L && model.contextWindow % 1_000_000L == 0L -> "${model.contextWindow / 1_000_000L}M (${model.contextWindow})"
                        model.contextWindow >= 1000L -> "${model.contextWindow / 1000L}k (${model.contextWindow})"
                        else -> "${model.contextWindow}"
                    }
                    Text(
                        text = "ID: ${model.id}  ·  上下文: $ctxText",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textMuted
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (model.hasVision) {
                            Surface(color = Color(0xFF2EA043).copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("Vision 图像输入", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2EA043), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                        if (model.supportsThinking) {
                            Surface(color = theme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                                Text("Thinking 思考推演", style = MaterialTheme.typography.labelSmall, color = theme.primary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // 弹窗 1：配置 Provider API Key
    if (showApiKeyDialog) {
        var providerInput by remember { mutableStateOf(selectedProvider ?: providers.firstOrNull() ?: "openai") }
        var apiKeyInput by remember { mutableStateOf("") }
        val presetProviders = listOf("anthropic", "openai", "google", "deepseek", "openrouter", "codywon")

        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text("配置提供商 API 密钥", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("选择或输入模型提供商标识：", style = MaterialTheme.typography.labelSmall, color = theme.textSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        presetProviders.forEach { p ->
                            val isSel = providerInput.equals(p, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.clickable { providerInput = p }
                            ) {
                                Text(
                                    text = p,
                                    fontSize = 11.sp,
                                    color = if (isSel) theme.primary else theme.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = providerInput,
                        onValueChange = { providerInput = it },
                        label = { Text("Provider 名称", color = theme.textSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key (如 sk-...)", color = theme.textSecondary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (providerInput.isNotBlank() && apiKeyInput.isNotBlank()) {
                            onSaveProviderApiKey(providerInput.trim(), apiKeyInput.trim())
                            showApiKeyDialog = false
                        }
                    }
                ) {
                    Text("保存并同步", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }

    // 弹窗 2：新增或编辑自定义模型配置
    if (showCustomModelDialog) {
        val target = editingModelTarget
        var providerName by remember(target) { mutableStateOf(target?.provider ?: (selectedProvider ?: "custom-openai")) }
        var baseUrl by remember(target) { mutableStateOf(target?.baseUrl ?: "") }
        var apiKey by remember(target) { mutableStateOf("") }
        var apiType by remember(target) { mutableStateOf(target?.apiType?.takeIf { it.isNotBlank() } ?: "openai-completions") }
        var modelId by remember(target) { mutableStateOf(target?.id ?: "") }
        var modelName by remember(target) { mutableStateOf(target?.name ?: "") }
        var contextWindowStr by remember(target) { mutableStateOf((target?.contextWindow ?: 200000L).toString()) }
        var maxTokensStr by remember(target) { mutableStateOf((target?.maxTokens ?: 8192L).toString()) }
        var supportsReasoning by remember(target) { mutableStateOf(target?.supportsThinking ?: true) }
        var supportsVision by remember(target) { mutableStateOf(target?.hasVision ?: false) }

        val apiTypeOptions = listOf(
            "openai-completions" to "OpenAI 兼容",
            "anthropic-messages" to "Anthropic",
            "google-generative-ai" to "Google Gemini"
        )

        AlertDialog(
            onDismissRequest = { showCustomModelDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    text = if (target == null) "新增自定义模型 / 提供商" else "编辑模型: ${target.displayName}",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = providerName,
                        onValueChange = { providerName = it },
                        label = { Text("提供商标识", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1) },
                        placeholder = { Text("如 codywon / deepseek", fontSize = 11.sp, color = theme.textMuted) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelId,
                        onValueChange = { modelId = it },
                        label = { Text("模型 ID", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1) },
                        placeholder = { Text("如 gpt-6.1-sol", fontSize = 11.sp, color = theme.textMuted) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = modelName,
                        onValueChange = { modelName = it },
                        label = { Text("显示名称 (选填)", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1) },
                        placeholder = { Text("留空则默认使用模型 ID", fontSize = 11.sp, color = theme.textMuted) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("接口协议类型：", style = MaterialTheme.typography.labelSmall, fontSize = 11.sp, color = theme.textSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        apiTypeOptions.forEach { (typeVal, typeLabel) ->
                            val isSel = apiType == typeVal
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.clickable { apiType = typeVal }
                            ) {
                                Text(
                                    text = typeLabel,
                                    fontSize = 10.5.sp,
                                    color = if (isSel) theme.primary else theme.textSecondary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("接口 Base URL (选填)", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1) },
                        placeholder = { Text("如 https://api.xxx/v1", fontSize = 11.sp, color = theme.textMuted) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key (选填)", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1) },
                        placeholder = { Text("密钥或环境变量名", fontSize = 11.sp, color = theme.textMuted) },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 对标 pi-web：同时支持配置输入上下文上限与最大输出窗口（精简标签防止换行）
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = contextWindowStr,
                            onValueChange = { contextWindowStr = it.filter { ch -> ch.isDigit() } },
                            label = { Text("输入上下文", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text("200000", fontSize = 11.sp, color = theme.textMuted) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = maxTokensStr,
                            onValueChange = { maxTokensStr = it.filter { ch -> ch.isDigit() } },
                            label = { Text("最大输出", fontSize = 11.sp, color = theme.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            placeholder = { Text("8192", fontSize = 11.sp, color = theme.textMuted) },
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("支持 Thinking 深度推演", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = theme.textPrimary)
                        Switch(checked = supportsReasoning, onCheckedChange = { supportsReasoning = it })
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("支持 Vision 图像输入", style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, color = theme.textPrimary)
                        Switch(checked = supportsVision, onCheckedChange = { supportsVision = it })
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (providerName.isNotBlank() && modelId.isNotBlank()) {
                            val ctxWin = contextWindowStr.toLongOrNull() ?: 200000L
                            val maxTok = maxTokensStr.toLongOrNull() ?: 8192L
                            onSaveCustomModel(
                                providerName.trim(),
                                baseUrl.trim(),
                                apiKey.trim(),
                                apiType,
                                modelId.trim(),
                                modelName.trim().ifBlank { modelId.trim() },
                                ctxWin,
                                maxTok,
                                supportsReasoning,
                                supportsVision
                            )
                            showCustomModelDialog = false
                        }
                    }
                ) {
                    Text("保存至网关", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (target != null) {
                        TextButton(
                            onClick = {
                                onDeleteModel(target.provider, target.id)
                                showCustomModelDialog = false
                            }
                        ) {
                            Text("删除模型", color = Color(0xFFCF222E))
                        }
                    }
                    TextButton(onClick = { showCustomModelDialog = false }) {
                        Text("取消", color = theme.textMuted)
                    }
                }
            }
        )
    }

    // 弹窗 3：models.json 原始 JSON 配置文件在线编辑器
    if (showRawJsonDialog) {
        LaunchedEffect(showRawJsonDialog) {
            isLoadingRawJson = true
            val loaded = onLoadModelsRawConfig()
            rawJsonContent = loaded ?: "{\n  \"providers\": {}\n}"
            isLoadingRawJson = false
        }

        AlertDialog(
            onDismissRequest = { showRawJsonDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text("编辑 models.json 配置文件", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "直接编辑网关 ~/.pi/agent/models.json（支持完整自定义 providers 与 models）：",
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textSecondary
                    )
                    if (isLoadingRawJson) {
                        Box(modifier = Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = theme.primary, strokeWidth = 2.dp)
                        }
                    } else {
                        OutlinedTextField(
                            value = rawJsonContent,
                            onValueChange = { rawJsonContent = it },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                color = theme.textPrimary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 240.dp, max = 380.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (rawJsonContent.isNotBlank()) {
                            onSaveModelsRawConfig(rawJsonContent)
                            showRawJsonDialog = false
                        }
                    }
                ) {
                    Text("保存 JSON", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawJsonDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }
}

// ==========================================
// Tab 2: 全局技能中心
// ==========================================
@Composable
fun GlobalSkillsTab(
    skills: List<SkillInfo>,
    onToggleSkill: (SkillInfo, Boolean) -> Unit,
    onEditAlias: (SkillInfo) -> Unit,
    onViewDetail: (SkillInfo) -> Unit,
    onInstallSkill: (pkgSource: String, scope: String) -> Unit = { _, _ -> }
) {
    val theme = LocalAppTheme.current
    var showInstallSkillDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InfoTitle(
                        title = "智能体技能包 (${skills.size} 项)",
                        infoDescription = "全局与工作区工程技能包。点击卡片或「编辑」按钮可查看与在线修改 SKILL.md 源码，支持自定义中文别名与安装新技能包。",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showInstallSkillDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("安装技能", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(skills, key = { it.name }) { skill ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
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
                            Icon(Icons.Default.Extension, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color(0xFF2EA043))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = skill.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )

                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = if (skill.alias.isNullOrBlank()) theme.surfaceContainer else theme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.clickable { onEditAlias(skill) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = skill.alias?.takeIf { it.isNotBlank() } ?: "别名 ✎",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (skill.alias.isNullOrBlank()) theme.textMuted else theme.primary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = skill.description ?: "点击查看或编辑 SKILL.md 详细指令",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            onClick = { onViewDetail(skill) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "编辑技能", modifier = Modifier.size(13.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(2.dp))
                            Text("编辑", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                        }

                        Switch(
                            checked = skill.enabled,
                            onCheckedChange = { onToggleSkill(skill, it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF2EA043),
                                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f),
                                uncheckedThumbColor = theme.textMuted,
                                uncheckedTrackColor = theme.surfaceContainer
                            )
                        )
                    }
                }
            }
        }
    }

    if (showInstallSkillDialog) {
        var pkgInput by remember { mutableStateOf("") }
        var scopeInput by remember { mutableStateOf("global") }
        AlertDialog(
            onDismissRequest = { showInstallSkillDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("安装技能包", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = pkgInput,
                        onValueChange = { pkgInput = it },
                        label = { Text("技能包来源", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("如 npm:xxx / git:xxx / 本地路径", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("global" to "全局安装", "project" to "当前工作区").forEach { (sc, label) ->
                            val isSel = scopeInput == sc
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f).clickable { scopeInput = sc }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 11.5.sp, color = if (isSel) theme.primary else theme.textSecondary, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (pkgInput.isNotBlank()) {
                        onInstallSkill(pkgInput.trim(), scopeInput)
                        showInstallSkillDialog = false
                    }
                }) {
                    Text("安装", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallSkillDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }
}

// ==========================================
// Tab 3: MCP 协议
// ==========================================
@Composable
fun GlobalMcpTab(
    mcpConfig: McpCodeModeConfig,
    mcpServers: List<McpServerInfo>,
    onAddMcpServer: (name: String, transport: String, endpoint: String, description: String, scope: String) -> Unit = { _, _, _, _, _ -> },
    onManageMcpServer: (action: String, name: String) -> Unit = { _, _ -> },
    onUpdateMcpCodeMode: (mode: String, scriptMode: Boolean, tokenBudget: Int) -> Unit = { _, _, _ -> }
) {
    val theme = LocalAppTheme.current
    var codeMode by remember(mcpConfig.mode) { mutableStateOf(mcpConfig.mode) }
    var scriptMode by remember(mcpConfig.scriptMode) { mutableStateOf(mcpConfig.scriptMode) }
    var tokenBudget by remember(mcpConfig.tokenBudget) { mutableStateOf(mcpConfig.tokenBudget) }
    var showAddMcpDialog by remember { mutableStateOf(false) }
    var editingMcpServer by remember { mutableStateOf<McpServerInfo?>(null) }
    var showBudgetDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InfoTitle(
                        title = "MCP 协议与沙箱代码模式",
                        infoDescription = "配置 Model Context Protocol 协议服务器，支持新增、编辑、启停节点及配置 Code Mode 沙箱预算。",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        editingMcpServer = null
                        showAddMcpDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("添加 MCP", fontSize = 11.5.sp)
                }
            }
        }

        // 代码模式单选 (修改后自动同步保存)
        item {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("代码模式 (Code Mode)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("automatic" to "自动 (推荐)", "always" to "始终开启", "off" to "关闭").forEach { (mode, label) ->
                            val isSel = codeMode == mode
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.14f) else theme.background,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f).clickable {
                                    codeMode = mode
                                    onUpdateMcpCodeMode(codeMode, scriptMode, tokenBudget)
                                }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSel) theme.primary else theme.textSecondary,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 脚本模式与预算设置 (点击预算支持编辑)
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
                            Text("脚本模式 (Script Mode)", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                            Text("使用一次性脚本批处理执行以节约 Token", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                        }
                        Switch(
                            checked = scriptMode,
                            onCheckedChange = {
                                scriptMode = it
                                onUpdateMcpCodeMode(codeMode, scriptMode, tokenBudget)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF2EA043),
                                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f),
                                uncheckedThumbColor = theme.textMuted,
                                uncheckedTrackColor = theme.surfaceContainer
                            )
                        )
                    }

                    HorizontalDivider(color = theme.outline)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showBudgetDialog = true },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("工具清单 Token 预算", style = MaterialTheme.typography.bodyMedium, color = theme.textPrimary)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$tokenBudget tokens", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = theme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(Icons.Default.Edit, contentDescription = "编辑预算", modifier = Modifier.size(13.dp), tint = theme.primary)
                        }
                    }
                }
            }
        }

        item {
            Text("已配置的 MCP 服务器 (${mcpServers.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = theme.textPrimary)
        }

        items(mcpServers, key = { it.name }) { server ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingMcpServer = server
                        showAddMcpDialog = true
                    }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier.size(8.dp),
                                shape = CircleShape,
                                color = if (server.isConnected && server.enabled) Color(0xFF2EA043) else Color(0xFFCF222E)
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(server.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(shape = RoundedCornerShape(4.dp), color = theme.surfaceContainer) {
                                Text(
                                    text = "${server.toolExposureCount} 工具",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textSecondary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = {
                                    editingMcpServer = server
                                    showAddMcpDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑", modifier = Modifier.size(13.dp), tint = theme.primary)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("编辑", style = MaterialTheme.typography.labelSmall, color = theme.primary)
                            }
                            TextButton(
                                onClick = { onManageMcpServer("remove", server.name) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("移除", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCF222E))
                            }
                            Switch(
                                checked = server.enabled,
                                onCheckedChange = { enabled ->
                                    onManageMcpServer(if (enabled) "enable" else "disable", server.name)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF2EA043),
                                    checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    if (!server.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(server.description ?: "", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                    }

                    if (!server.url.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${server.transport?.uppercase() ?: "HTTP"}: ${server.url}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted
                        )
                    }
                }
            }
        }
    }

    if (showBudgetDialog) {
        var budgetInput by remember { mutableStateOf(tokenBudget.toString()) }
        AlertDialog(
            onDismissRequest = { showBudgetDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("修改工具清单 Token 预算", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                OutlinedTextField(
                    value = budgetInput,
                    onValueChange = { budgetInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Token 预算上限", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                    placeholder = { Text("如 3000", fontSize = 11.5.sp, color = theme.textMuted) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = budgetInput.toIntOrNull() ?: 1000
                        tokenBudget = parsed
                        onUpdateMcpCodeMode(codeMode, scriptMode, tokenBudget)
                        showBudgetDialog = false
                    }
                ) {
                    Text("保存", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBudgetDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }

    if (showAddMcpDialog) {
        val target = editingMcpServer
        var nameInput by remember(target) { mutableStateOf(target?.name ?: "") }
        var transportInput by remember(target) {
            val raw = (target?.transport ?: "http").lowercase()
            mutableStateOf(if (raw == "stdio") "stdio" else "http")
        }
        var endpointInput by remember(target) { mutableStateOf(target?.url ?: "") }
        var descInput by remember(target) { mutableStateOf(target?.description ?: "") }
        var scopeInput by remember(target) { mutableStateOf("user") }

        AlertDialog(
            onDismissRequest = { showAddMcpDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    if (target == null) "添加 MCP 服务器" else "编辑 MCP 服务器: ${target.name}",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("节点名称", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("如 filesystem / github", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("http" to "HTTP / SSE", "stdio" to "Stdio 本地进程").forEach { (tr, label) ->
                            val isSel = transportInput == tr
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f).clickable { transportInput = tr }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 11.5.sp, color = if (isSel) theme.primary else theme.textSecondary, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = endpointInput,
                        onValueChange = { endpointInput = it },
                        label = {
                            Text(
                                if (transportInput == "http") "服务器 URL" else "启动命令与参数",
                                fontSize = 11.5.sp,
                                color = theme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        },
                        placeholder = {
                            Text(
                                if (transportInput == "http") "如 http://127.0.0.1:8888/mcp"
                                else "如 npx -y @modelcontextprotocol/server-filesystem .",
                                fontSize = 11.5.sp,
                                color = theme.textMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = descInput,
                        onValueChange = { descInput = it },
                        label = { Text("功能备注 (选填)", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("描述该 MCP 节点提供的工具能力", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (nameInput.isNotBlank() && endpointInput.isNotBlank()) {
                            onAddMcpServer(nameInput.trim(), transportInput, endpointInput.trim(), descInput.trim(), scopeInput)
                            showAddMcpDialog = false
                        }
                    }
                ) {
                    Text(if (target == null) "添加并连接" else "保存修改", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMcpDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }
}

// ==========================================
// Tab 4: 插件扩展
// ==========================================
@Composable
fun GlobalPluginsTab(
    plugins: List<PluginPackage>,
    onManagePlugin: (action: String, source: String, scope: String) -> Unit = { _, _, _ -> },
    onEditPlugin: (oldPkg: PluginPackage, newSource: String, newVersion: String, newDesc: String, newScope: String) -> Unit = { _, _, _, _, _ -> }
) {
    val theme = LocalAppTheme.current
    var showInstallPluginDialog by remember { mutableStateOf(false) }
    var editingPluginTarget by remember { mutableStateOf<PluginPackage?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    InfoTitle(
                        title = "插件扩展 (${plugins.size} 个扩展包)",
                        infoDescription = "支持在线安装 NPM / Git 插件包，以及随时编辑配置、启用、禁用、更新或卸载。",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showInstallPluginDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("安装插件", fontSize = 11.5.sp, maxLines = 1, softWrap = false)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        items(plugins, key = { it.packageName }) { pkg ->
            val pkgSource = pkg.source?.takeIf { it.isNotBlank() } ?: pkg.packageName
            val pkgScope = pkg.scope ?: "global"
            val isEnabled = !pkg.disabled
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { editingPluginTarget = pkg }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Layers, contentDescription = null, modifier = Modifier.size(16.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = pkg.packageName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(color = theme.surfaceContainer, shape = RoundedCornerShape(4.dp)) {
                                Text("v${pkg.version}", style = MaterialTheme.typography.labelSmall, color = theme.textSecondary, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                            }
                        }

                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { enabled ->
                                onManagePlugin(if (enabled) "enable" else "disable", pkgSource, pkgScope)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF2EA043),
                                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f)
                            )
                        )
                    }

                    if (!pkg.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(pkg.description ?: "", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = pkgSource,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(
                                onClick = { editingPluginTarget = pkg },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "编辑插件", modifier = Modifier.size(13.dp), tint = theme.primary)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("编辑", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                onClick = { onManagePlugin("update", pkgSource, pkgScope) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("更新", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                            }
                            TextButton(
                                onClick = { onManagePlugin("remove", pkgSource, pkgScope) },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text("卸载", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCF222E), maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }

    // 编辑现有插件配置弹窗
    editingPluginTarget?.let { target ->
        var sourceInput by remember(target) { mutableStateOf(target.source ?: target.packageName) }
        var versionInput by remember(target) { mutableStateOf(target.version) }
        var descInput by remember(target) { mutableStateOf(target.description ?: "") }
        var scopeInput by remember(target) { mutableStateOf(target.scope ?: "global") }

        AlertDialog(
            onDismissRequest = { editingPluginTarget = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    "编辑插件配置: ${target.packageName}",
                    color = theme.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        label = { Text("插件源地址", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("如 npm:xxx / git:xxx / 本地路径", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = versionInput,
                        onValueChange = { versionInput = it },
                        label = { Text("版本号", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("如 1.0.0", fontSize = 11.5.sp, color = theme.textMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = descInput,
                        onValueChange = { descInput = it },
                        label = { Text("描述说明", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("选填：插件功能备注", fontSize = 11.5.sp, color = theme.textMuted) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("global" to "全局作用", "project" to "当前项目").forEach { (sc, label) ->
                            val isSel = scopeInput == sc
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f).clickable { scopeInput = sc }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 11.5.sp, color = if (isSel) theme.primary else theme.textSecondary, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sourceInput.isNotBlank()) {
                            onEditPlugin(target, sourceInput.trim(), versionInput.trim().ifBlank { target.version }, descInput.trim(), scopeInput)
                            editingPluginTarget = null
                        }
                    }
                ) {
                    Text("保存修改", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPluginTarget = null }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }

    if (showInstallPluginDialog) {
        var sourceInput by remember { mutableStateOf("") }
        var scopeInput by remember { mutableStateOf("global") }
        AlertDialog(
            onDismissRequest = { showInstallPluginDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("安装插件扩展", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sourceInput,
                        onValueChange = { sourceInput = it },
                        label = { Text("插件包标识", fontSize = 11.5.sp, color = theme.textSecondary, maxLines = 1, softWrap = false) },
                        placeholder = { Text("如 npm:pi-smart-compact 或 git:xxx", fontSize = 11.5.sp, color = theme.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("global" to "全局安装", "project" to "当前项目").forEach { (sc, label) ->
                            val isSel = scopeInput == sc
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f).clickable { scopeInput = sc }
                            ) {
                                Box(modifier = Modifier.padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 11.5.sp, color = if (isSel) theme.primary else theme.textSecondary, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sourceInput.isNotBlank()) {
                            onManagePlugin("install", sourceInput.trim(), scopeInput)
                            showInstallPluginDialog = false
                        }
                    }
                ) {
                    Text("安装", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstallPluginDialog = false }) {
                    Text("取消", color = theme.textMuted)
                }
            }
        )
    }
}

// ==========================================
// Tab 5: 系统设置 (主题切换、常驻通知栏保活、网关管理与关于)
// ==========================================
@Composable
fun GlobalSystemSettingsTab(
    isIgnoringBattery: Boolean,
    onRequestIgnoreBattery: () -> Unit,
    persistentNotifyEnabled: Boolean = true,
    onTogglePersistentNotify: (Boolean) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onToggleHaptics: (Boolean) -> Unit = {},
    onTestHaptics: () -> Unit = {},
    completionSoundEnabled: Boolean = true,
    onToggleCompletionSound: (Boolean) -> Unit = {},
    onTestCompletionSound: () -> Unit = {},
    serverUrl: String,
    authToken: String,
    deviceName: String,
    onEditConnection: () -> Unit = {},
    onScanQr: () -> Unit = {},
    onDisconnect: () -> Unit,
    isCheckingUpdate: Boolean,
    onCheckUpdate: () -> Unit
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val currentVersion = remember { AppUpdateManager.getCurrentVersionName(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 实时同步电池白名单状态：监听 Activity Resume 并做多拍延迟检测（应对系统授权弹窗返回后的异步生效）
    var liveIgnoringBattery by remember(isIgnoringBattery) { mutableStateOf(isIgnoringBattery) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    listOf(0L, 350L, 800L, 1500L).forEach { delayMs ->
                        if (delayMs > 0) delay(delayMs)
                        liveIgnoringBattery = KeepAliveManager(context).isIgnoringBatteryOptimizations()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            InfoTitle(
                title = "应用主题外观",
                infoDescription = "默认浅色纸质明眸，提供深空暗黑、纯黑 OLED、冰蓝与翡翠等高对比度配色方案。",
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                text = "马达触觉反馈",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = theme.textPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "任务完成时双连震动提醒，安全审批时重震提醒",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 16.sp,
                                color = theme.textMuted
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onTestHaptics,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("测试震动", style = MaterialTheme.typography.labelSmall, color = theme.primary)
                            }
                            Switch(
                                checked = hapticsEnabled,
                                onCheckedChange = onToggleHaptics,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = theme.primary,
                                    checkedTrackColor = theme.primary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = theme.outline)

                    // 对标 pi-web 任务完成提示音
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 16.dp)
                        ) {
                            Text(
                                text = "任务完成提示音",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = theme.textPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "任务完成时播放提示音（对标 pi-web 提示音）",
                                style = MaterialTheme.typography.labelSmall,
                                lineHeight = 16.sp,
                                color = theme.textMuted
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = onTestCompletionSound,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("测试音效", style = MaterialTheme.typography.labelSmall, color = theme.primary)
                            }
                            Switch(
                                checked = completionSoundEnabled,
                                onCheckedChange = onToggleCompletionSound,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = theme.primary,
                                    checkedTrackColor = theme.primary.copy(alpha = 0.3f)
                                )
                            )
                        }
                    }
                }
            }
        }

        item {
            InfoTitle(
                title = "后台保活",
                infoDescription = "三重保活架构（常驻通知栏前台服务 + 系统电池白名单 + 任务期动态唤醒锁），确保锁屏与切后台不断连。",
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 1. 常驻通知栏保活开关
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("常驻通知栏保活", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = theme.textPrimary)
                                UnifiedStatusBadge(
                                    text = if (persistentNotifyEnabled) "常驻中" else "已关闭",
                                    isActive = persistentNotifyEnabled
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "在通知栏常驻展示网关连接与实时任务进度，提升进程为前台级优先级",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
                        }
                        Switch(
                            checked = persistentNotifyEnabled,
                            onCheckedChange = onTogglePersistentNotify,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF2EA043),
                                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f)
                            )
                        )
                    }

                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))

                    // 2. 电池优化白名单 (实时同步状态)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("电池优化白名单", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = theme.textPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                if (liveIgnoringBattery) "已加入系统电池白名单，后台网络不受限制" else "建议开启：防止息屏后系统休眠冻结后台长连接",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (liveIgnoringBattery) theme.textMuted else Color(0xFFD97706)
                            )
                        }
                        if (liveIgnoringBattery) {
                            UnifiedStatusBadge(
                                text = "已加入",
                                isActive = true
                            )
                        } else {
                            UnifiedStatusBadge(
                                text = "去开启 ➔",
                                isActive = false,
                                isAction = true,
                                onClick = {
                                    onRequestIgnoreBattery()
                                    scope.launch {
                                        listOf(500L, 1200L, 2500L).forEach { d ->
                                            delay(d)
                                            liveIgnoringBattery = KeepAliveManager(context).isIgnoringBatteryOptimizations()
                                        }
                                    }
                                }
                            )
                        }
                    }

                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))

                    // 3. CPU 唤醒锁与断线自动重连
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("CPU 唤醒锁与断线自愈", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = theme.textPrimary)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text("任务运行期间自动持有唤醒锁 + 指数退避 SSE 重连与 2s 快照对账", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                        }
                        UnifiedStatusBadge(
                            text = "已就绪",
                            isActive = true
                        )
                    }
                }
            }
        }

        item {
            InfoTitle(
                title = "主机网关连接",
                infoDescription = "管理已配对的 PC 上的网关地址与访问令牌，支持手动编辑与扫码切换。",
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
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isConnected = serverUrl.isNotBlank() && authToken.isNotBlank()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Dns,
                                contentDescription = null,
                                tint = if (isConnected) theme.primary else theme.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = deviceName.ifBlank { if (isConnected) "My PC" else "未配置网关" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                        }
                        UnifiedStatusBadge(
                            text = if (isConnected) "已配置" else "未配对",
                            isActive = isConnected
                        )
                    }

                    Surface(
                        color = theme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "网关地址: ${serverUrl.ifBlank { "未设置" }}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (isConnected) theme.primary else theme.textMuted
                            )
                            Text(
                                text = "访问令牌: ${if (authToken.isNotBlank()) "${authToken.take(10)}••••••••" else "未设置"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onEditConnection,
                            colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isConnected) "手动编辑配置" else "手动添加连接", style = MaterialTheme.typography.labelSmall, color = Color.White)
                        }
                        OutlinedButton(
                            onClick = onScanQr,
                            border = BorderStroke(1.dp, theme.outline),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(15.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("扫码 / 相册配对", style = MaterialTheme.typography.labelSmall, color = theme.textPrimary)
                        }
                    }

                    if (isConnected) {
                        OutlinedButton(
                            onClick = onDisconnect,
                            border = BorderStroke(1.dp, Color(0xFFCF222E).copy(alpha = 0.5f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF222E)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("断开当前网关连接", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // ==========================================
        // 关于与升级 (扁平化品牌 Logo + 作者 Powered by codywon & CC BY-NC 许可)
        // ==========================================
        item {
            InfoTitle(
                title = "关于与升级",
                infoDescription = "查看版本信息、开发者署名与开源使用许可协议。",
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PiDroidLogo(size = 36.dp)
                            Spacer(modifier = Modifier.width(10.dp))
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

                        Button(
                            onClick = onCheckUpdate,
                            enabled = !isCheckingUpdate,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = theme.primary,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            if (isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    color = Color.White,
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

                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("开发者", style = MaterialTheme.typography.bodySmall, color = theme.textPrimary)
                        Text(
                            text = "Powered by codywon",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.primary
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("许可协议", style = MaterialTheme.typography.bodySmall, color = theme.textPrimary)
                        Text(
                            text = "CC BY-NC（个人学习交流，禁止商业用途）",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.textSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 统一规范的状态/动作徽章组件：用于设置页的电池白名单、前台常驻服务与连接状态
 */
@Composable
private fun UnifiedStatusBadge(
    text: String,
    isActive: Boolean,
    isAction: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val theme = LocalAppTheme.current
    val fgColor = when {
        isActive -> Color(0xFF16A34A)
        isAction -> theme.primary
        else -> Color(0xFFD97706)
    }
    val bgColor = when {
        isActive -> Color(0xFF22C55E).copy(alpha = 0.12f)
        isAction -> theme.primary.copy(alpha = 0.12f)
        else -> Color(0xFFF59E0B).copy(alpha = 0.12f)
    }
    val borderColor = when {
        isActive -> Color(0xFF22C55E).copy(alpha = 0.35f)
        isAction -> theme.primary.copy(alpha = 0.4f)
        else -> Color(0xFFF59E0B).copy(alpha = 0.35f)
    }

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = if (onClick != null) Modifier.clickable { onClick() } else Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(fgColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = fgColor
            )
        }
    }
}
