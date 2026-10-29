package com.pi.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pi.app.data.ServerDefaults
import com.pi.app.data.model.*
import com.pi.app.data.storage.AppPreferences
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
    currentProject: String = "",
    onToggleSkill: (SkillInfo, Boolean) -> Unit,
    onToggleSkillsBatch: (List<SkillInfo>, Boolean) -> Unit = { _, _ -> },
    onUpdateSkillAlias: (skillName: String, alias: String) -> Unit,
    onLoadSkillContent: suspend (SkillInfo) -> String? = { null },
    onSaveSkillContent: (SkillInfo, String) -> Unit = { _, _ -> },
    onInstallSkill: (pkgSource: String, scope: String) -> Unit = { _, _ -> },
    onRefreshSkills: () -> Unit = {},
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
    taskCompletedNotifyEnabled: Boolean = false,
    onToggleTaskCompletedNotify: (Boolean) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onToggleHaptics: (Boolean) -> Unit = {},
    onTestHaptics: () -> Unit = {},
    completionSoundEnabled: Boolean = true,
    onToggleCompletionSound: (Boolean) -> Unit = {},
    onTestCompletionSound: () -> Unit = {},
    autoCompactionEnabled: Boolean = true,
    onToggleAutoCompaction: (Boolean) -> Unit = {},
    autoRetryEnabled: Boolean = true,
    onToggleAutoRetry: (Boolean) -> Unit = {},
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
                        if (!AppUpdateManager.installApk(context, permState.apkFile)) {
                            updateUiState = UpdateUiState.Error(
                                "校验未通过，已阻止安装；请到 Release 手动下载",
                                permState.info
                            )
                        }
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
                    currentProject = currentProject,
                    apiClient = apiClient,
                    onToggleSkill = onToggleSkill,
                    onToggleSkillsBatch = onToggleSkillsBatch,
                    onEditAlias = {
                        editingSkillAlias = it
                        aliasInput = it.alias ?: ""
                    },
                    onViewDetail = { skill ->
                        selectedSkillForDetail = skill
                        isEditingSkillMd = false
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
                    onInstallSkill = onInstallSkill,
                    onRefreshSkills = onRefreshSkills
                )
                2 -> GlobalMcpTab(
                    mcpConfig = mcpConfig,
                    onUpdateMcpCodeMode = onUpdateMcpCodeMode,
                    mcpServers = mcpServers,
                    apiClient = apiClient,
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
                    taskCompletedNotifyEnabled = taskCompletedNotifyEnabled,
                    onToggleTaskCompletedNotify = onToggleTaskCompletedNotify,
                    hapticsEnabled = hapticsEnabled,
                    onToggleHaptics = onToggleHaptics,
                    onTestHaptics = onTestHaptics,
                    completionSoundEnabled = completionSoundEnabled,
                    onToggleCompletionSound = onToggleCompletionSound,
                    onTestCompletionSound = onTestCompletionSound,
                    autoCompactionEnabled = autoCompactionEnabled,
                    onToggleAutoCompaction = onToggleAutoCompaction,
                    autoRetryEnabled = autoRetryEnabled,
                    onToggleAutoRetry = onToggleAutoRetry,
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
                        if (!AppUpdateManager.installApk(context, apkFile)) {
                            updateUiState = UpdateUiState.Error(
                                "校验未通过，已阻止安装；请到 Release 手动下载",
                                info
                            )
                        }
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
                if (!AppUpdateManager.installApk(context, file)) {
                    updateUiState = UpdateUiState.Error(
                        "校验未通过，已阻止安装；请到 Release 手动下载",
                        (updateUiState as? UpdateUiState.ReadyToInstall)?.info
                    )
                }
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

    // 技能详情（对标 pi-web 的 SkillDetail 面板）：近全屏 BottomSheet，
    // 预览 / 源码编辑做成真正的两段分段控件，正文吃满剩余高度。
    selectedSkillForDetail?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            markdownContent = skillMarkdownContent,
            isLoading = isLoadingSkillMd,
            isEditing = isEditingSkillMd,
            editBuffer = skillEditBuffer,
            onEditBufferChange = { skillEditBuffer = it },
            onEditingChange = { isEditingSkillMd = it },
            onToggleVisible = { checked ->
                onToggleSkill(skill, checked)
                selectedSkillForDetail = skill.copy(enabled = checked, disableModelInvocation = !checked)
            },
            onSave = { content ->
                skillMarkdownContent = content
                onSaveSkillContent(skill, content)
                isEditingSkillMd = false
            },
            onDismiss = { selectedSkillForDetail = null }
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
            initialUrl = serverUrl.ifBlank { ServerDefaults.gatewayUrlHint },
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
                infoDescription = "新任务默认思考深度",
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
                    infoDescription = "增删提供商与模型、配置 Key 与参数",
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
                        "直接编辑 models.json：",
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
// Tab 2: 全局与项目技能中心 (复刻 pi-web 技能管理)
// ==========================================
enum class SkillSection {
    INSTALLED,  // 已安装技能（分项目与全局分组，支持模型可见性开关）
    ADD_SKILL   // 添加技能（搜索 skills.sh 技能市场并安装）
}

/** 已安装技能的本地筛选维度（不触发网络请求） */
private enum class SkillFilter {
    ALL,        // 全部
    VISIBLE,    // 模型可见
    HIDDEN,     // 对模型隐藏
    UPDATABLE   // 有可升级版本
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSkillsTab(
    skills: List<SkillInfo>,
    currentProject: String = "",
    apiClient: PiApiClient? = null,
    onToggleSkill: (SkillInfo, Boolean) -> Unit,
    onToggleSkillsBatch: (List<SkillInfo>, Boolean) -> Unit = { _, _ -> },
    onEditAlias: (SkillInfo) -> Unit,
    onViewDetail: (SkillInfo) -> Unit,
    onInstallSkill: (pkgSource: String, scope: String) -> Unit = { _, _ -> },
    onRefreshSkills: () -> Unit = {}
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var currentSection by remember { mutableStateOf(SkillSection.INSTALLED) }
    var addSkillTargetScope by remember { mutableStateOf("global") } // "global" | "project"
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<SkillSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var hasSearched by remember { mutableStateOf(false) }
    var installingPackage by remember { mutableStateOf<String?>(null) }

    // 技能版本更新检测状态
    var updatesMap by remember { mutableStateOf<Map<String, SkillUpdateInfo>>(emptyMap()) }
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updatingPackage by remember { mutableStateOf<String?>(null) }

    fun doSearch(queryText: String = searchQuery) {
        val q = queryText.trim()
        if (q.isBlank() || isSearching) return
        isSearching = true
        searchError = null
        hasSearched = true
        coroutineScope.launch {
            if (apiClient != null) {
                apiClient.searchSkills(q).onSuccess { list ->
                    searchResults = list
                    isSearching = false
                }.onFailure { err ->
                    searchError = err.message ?: "搜索失败"
                    isSearching = false
                }
            } else {
                isSearching = false
                searchError = "未连接后端网关"
            }
        }
    }

    fun doCheckUpdates() {
        if (isCheckingUpdates || apiClient == null) return
        isCheckingUpdates = true
        coroutineScope.launch {
            apiClient.checkSkillUpdates(cwd = currentProject).onSuccess { list ->
                val map = list.associateBy { it.`package` }
                updatesMap = map
                isCheckingUpdates = false
                val updateCount = list.count { it.state == "update-available" }
                Toast.makeText(
                    context,
                    if (updateCount > 0) "发现 $updateCount 项技能可更新" else "所有技能均为最新版本",
                    Toast.LENGTH_SHORT
                ).show()
            }.onFailure { err ->
                isCheckingUpdates = false
                Toast.makeText(context, "检查更新失败: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun doUpdateSkill(skill: SkillInfo) {
        val pkg = skill.install?.`package` ?: skill.name
        val scope = skill.scope ?: if (skill.isProjectScope) "project" else "global"
        if (updatingPackage != null || apiClient == null) return
        updatingPackage = pkg
        coroutineScope.launch {
            Toast.makeText(context, "正在更新技能: ${skill.name}...", Toast.LENGTH_SHORT).show()
            apiClient.updateSkill(pkg, scope, currentProject).onSuccess {
                Toast.makeText(context, "已成功更新技能: ${skill.name}", Toast.LENGTH_SHORT).show()
                updatingPackage = null
                onRefreshSkills()
            }.onFailure { err ->
                updatingPackage = null
                Toast.makeText(context, "更新失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val projectSkills = remember(skills) { skills.filter { it.isProjectScope } }
    val globalSkills = remember(skills) { skills.filter { it.isGlobalScope } }

    // 已安装列表的本地筛选（与 skills.sh 远程搜索 searchQuery 完全独立）
    var localQuery by remember { mutableStateOf("") }
    var localFilter by remember { mutableStateOf(SkillFilter.ALL) }
    var showSkillHelp by remember { mutableStateOf(false) }

    val updatableCount = updatesMap.values.count { it.state == "update-available" }
    val hiddenCount = skills.count { !it.isVisibleToModel }
    val visibleCount = skills.size - hiddenCount

    fun filterInstalled(list: List<SkillInfo>): List<SkillInfo> {
        val q = localQuery.trim()
        return list.filter { skill ->
            val matchesQuery = q.isBlank() ||
                skill.name.contains(q, ignoreCase = true) ||
                skill.alias?.contains(q, ignoreCase = true) == true ||
                skill.description?.contains(q, ignoreCase = true) == true
            val matchesFilter = when (localFilter) {
                SkillFilter.ALL -> true
                SkillFilter.VISIBLE -> skill.isVisibleToModel
                SkillFilter.HIDDEN -> !skill.isVisibleToModel
                SkillFilter.UPDATABLE -> updatesMap[skill.install?.`package`]?.state == "update-available"
            }
            matchesQuery && matchesFilter
        }
    }

    val filteredProject = filterInstalled(projectSkills)
    val filteredGlobal = filterInstalled(globalSkills)
    // 有内容的组优先显示，空组折叠置底（避免 12 项全局技能被迫从第二屏才开始）
    val projectGroupFirst = filteredProject.isNotEmpty() || filteredGlobal.isEmpty()

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        when (currentSection) {
            SkillSection.INSTALLED -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶部固定工具区：搜索 + 添加技能 + 检查更新
                    // 三个入口合一：不再重复出现在顶部 pill、标题行与列表底部
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 已安装技能本地搜索（固定 44dp，与右侧两个动作按钮同高）
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(
                                1.dp,
                                if (localQuery.isBlank()) theme.outline else theme.primary.copy(alpha = 0.6f)
                            ),
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = theme.textMuted
                                )
                                Spacer(modifier = Modifier.width(7.dp))
                                BasicTextField(
                                    value = localQuery,
                                    onValueChange = { localQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(fontSize = 12.5.sp, color = theme.textPrimary),
                                    cursorBrush = SolidColor(theme.primary),
                                    modifier = Modifier.weight(1f),
                                    decorationBox = { innerTextField ->
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.CenterStart
                                        ) {
                                            if (localQuery.isBlank()) {
                                                Text(
                                                    text = "搜索已安装技能",
                                                    fontSize = 12.sp,
                                                    color = theme.textMuted
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                                if (localQuery.isNotBlank()) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        Icons.Default.Clear,
                                        contentDescription = "清除",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { localQuery = "" },
                                        tint = theme.textMuted
                                    )
                                }
                            }
                        }

                        // 添加技能（唯一入口）
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = theme.primary,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable {
                                    currentSection = SkillSection.ADD_SKILL
                                },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "添加技能",
                                    modifier = Modifier.size(19.dp),
                                    tint = Color.White
                                )
                            }
                        }

                        // 检查更新（有可更新项时图标转为琥珀色）
                        Surface(
                            shape = RoundedCornerShape(9.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable { doCheckUpdates() },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCheckingUpdates) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = theme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "检查更新",
                                        modifier = Modifier.size(17.dp),
                                        tint = if (updatableCount > 0) Color(0xFFD29922) else theme.textSecondary
                                    )
                                }
                            }
                        }
                    }

                    // 筛选 chips（等宽铺满）+ 可见性说明入口
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            SkillFilter.ALL,
                            SkillFilter.VISIBLE,
                            SkillFilter.HIDDEN,
                            SkillFilter.UPDATABLE
                        ).forEach { filter ->
                            val label = when (filter) {
                                SkillFilter.ALL -> "全部 ${skills.size}"
                                SkillFilter.VISIBLE -> "可见 $visibleCount"
                                SkillFilter.HIDDEN -> "隐藏 $hiddenCount"
                                SkillFilter.UPDATABLE -> "可更新 $updatableCount"
                            }
                            val selected = localFilter == filter
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = if (selected) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainerLow,
                                border = BorderStroke(1.dp, if (selected) theme.primary else theme.outline),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) theme.primary else theme.textSecondary,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { localFilter = filter }
                                        .padding(vertical = 4.dp)
                                )
                            }
                        }
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = "技能可见性说明",
                            tint = theme.textMuted,
                            modifier = Modifier.size(15.dp).clickable { showSkillHelp = true }
                        )
                    }

                    fun LazyListScope.skillRows(list: List<SkillInfo>, keyPrefix: String) {
                        items(list, key = { "$keyPrefix-${it.filePath ?: it.name}" }) { skill ->
                            SkillCardItem(
                                skill = skill,
                                isProject = keyPrefix == "project",
                                isUpdateAvailable = updatesMap[skill.install?.`package`]?.state == "update-available",
                                isUpdating = updatingPackage == (skill.install?.`package` ?: skill.name),
                                onToggle = { onToggleSkill(skill, it) },
                                onEditAlias = { onEditAlias(skill) },
                                onViewDetail = { onViewDetail(skill) },
                                onUpdate = { doUpdateSkill(skill) }
                            )
                        }
                    }

                    fun LazyListScope.projectGroup() {
                        item(key = "project-header") {
                            SkillGroupHeader(
                                title = "项目技能",
                                icon = Icons.Default.Folder,
                                accent = Color(0xFF8250DF),
                                badgeText = "${projectSkills.count { it.isVisibleToModel }}/${projectSkills.size}",
                                checked = projectSkills.isNotEmpty() && projectSkills.all { it.isVisibleToModel },
                                switchEnabled = projectSkills.isNotEmpty(),
                                onToggleAll = { checked -> onToggleSkillsBatch(projectSkills, checked) }
                            )
                        }
                        if (projectSkills.isEmpty()) {
                            item(key = "project-empty") {
                                SkillHintRow(
                                    text = "暂无项目技能 · 去添加",
                                    onClick = { currentSection = SkillSection.ADD_SKILL }
                                )
                            }
                        } else {
                            skillRows(filteredProject, "project")
                            if (filteredProject.isEmpty()) {
                                item(key = "project-nomatch") { SkillHintRow(text = "无匹配的项目技能") }
                            }
                        }
                    }

                    fun LazyListScope.globalGroup() {
                        item(key = "global-header") {
                            SkillGroupHeader(
                                title = "全局技能",
                                icon = Icons.Default.Public,
                                accent = Color(0xFF58A6FF),
                                badgeText = "${globalSkills.count { it.isVisibleToModel }}/${globalSkills.size}",
                                checked = globalSkills.isNotEmpty() && globalSkills.all { it.isVisibleToModel },
                                switchEnabled = globalSkills.isNotEmpty(),
                                onToggleAll = { checked -> onToggleSkillsBatch(globalSkills, checked) }
                            )
                        }
                        if (globalSkills.isEmpty()) {
                            item(key = "global-empty") {
                                SkillHintRow(
                                    text = "暂无全局技能 · 去添加",
                                    onClick = { currentSection = SkillSection.ADD_SKILL }
                                )
                            }
                        } else {
                            skillRows(filteredGlobal, "global")
                            if (filteredGlobal.isEmpty()) {
                                item(key = "global-nomatch") { SkillHintRow(text = "无匹配的全局技能") }
                            }
                        }
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (updatableCount > 0) {
                            item(key = "update-banner") {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFD29922).copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, Color(0xFFD29922).copy(alpha = 0.35f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "发现 $updatableCount 项技能可升级",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFD29922)
                                        )
                                        Text(
                                            text = "点击卡片可单独更新",
                                            fontSize = 10.5.sp,
                                            color = theme.textMuted
                                        )
                                    }
                                }
                            }
                        }

                        if (projectGroupFirst) {
                            projectGroup()
                            item(key = "group-gap") { Spacer(modifier = Modifier.height(2.dp)) }
                            globalGroup()
                        } else {
                            globalGroup()
                            item(key = "group-gap") { Spacer(modifier = Modifier.height(2.dp)) }
                            projectGroup()
                        }

                        item(key = "bottom-gap") { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }

                if (showSkillHelp) {
                    AlertDialog(
                        onDismissRequest = { showSkillHelp = false },
                        containerColor = theme.surfaceContainerLow,
                        titleContentColor = theme.textPrimary,
                        textContentColor = theme.textSecondary,
                        title = {
                            Text(
                                text = "技能可见性与范围",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                        },
                        text = {
                            Text(
                                text = "开启后模型可在系统提示词中发现该技能并主动调用；关闭后对模型隐藏" +
                                    "（不消耗提示词 Token），仍可在对话框通过 /技能名 手动调用。\n\n" +
                                    "项目技能安装在 .pi/skills，仅当前工作区生效；" +
                                    "全局技能安装在 ~/.pi/agent/skills，对所有工作区生效。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = theme.textSecondary
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { showSkillHelp = false }) {
                                Text("我知道了", color = theme.primary)
                            }
                        }
                    )
                }
            }

            SkillSection.ADD_SKILL -> {
                // ==========================================
                // 添加技能 (完全复刻 pi-web 添加技能面板)
                // ==========================================
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TextButton(
                                onClick = { currentSection = SkillSection.INSTALLED },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(14.dp), tint = theme.primary)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("返回已安装列表", fontSize = 12.sp, color = theme.primary)
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://skills.sh")))
                                    }
                                }
                            ) {
                                Text("skills.sh", fontSize = 12.sp, color = Color(0xFF58A6FF), fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF58A6FF))
                            }
                        }
                    }

                    // 目标安装范围选择与目录预览
                    item {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("添加技能", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = theme.textPrimary)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("global" to "全局", "project" to "项目").forEach { (sc, label) ->
                                            val isSel = addSkillTargetScope == sc
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (isSel) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainer,
                                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                                modifier = Modifier.clickable { addSkillTargetScope = sc }
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.5.sp,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) theme.primary else theme.textSecondary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                val pathPreview = if (addSkillTargetScope == "global") {
                                    "~/.pi/agent/skills/"
                                } else {
                                    "${currentProject.ifBlank { "当前项目" }}/.pi/skills/"
                                }
                                Surface(
                                    color = theme.surfaceContainer,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "安装路径: $pathPreview",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = theme.textMuted,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }

                    // 搜索输入栏 (例如 react、testing、deploy)
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("例如 react、testing、deploy", fontSize = 12.sp, color = theme.textMuted) },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp), tint = theme.textMuted) },
                                trailingIcon = {
                                    if (searchQuery.isNotBlank()) {
                                        IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                            Icon(Icons.Default.Clear, contentDescription = "清除", modifier = Modifier.size(14.dp), tint = theme.textMuted)
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, color = theme.textPrimary),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = theme.primary,
                                    unfocusedBorderColor = theme.outline,
                                    focusedTextColor = theme.textPrimary,
                                    unfocusedTextColor = theme.textPrimary
                                )
                            )

                            Button(
                                onClick = { doSearch() },
                                enabled = searchQuery.isNotBlank() && !isSearching,
                                colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                if (isSearching) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("搜索中", fontSize = 12.sp)
                                } else {
                                    Text("搜索", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    // 快速热门标签
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("react", "testing", "deploy", "git", "python", "hindsight", "docker", "review").forEach { tag ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = theme.surfaceContainer,
                                    border = BorderStroke(0.5.dp, theme.outline),
                                    modifier = Modifier.clickable {
                                        searchQuery = tag
                                        doSearch(tag)
                                    }
                                ) {
                                    Text(
                                        text = tag,
                                        fontSize = 11.sp,
                                        color = theme.textSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 搜索结果列表
                    if (isSearching) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = theme.primary, strokeWidth = 2.dp)
                                    Text("正在从 skills.sh 技能市场搜索...", fontSize = 12.sp, color = theme.textMuted)
                                }
                            }
                        }
                    } else if (searchError != null) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF85149).copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, Color(0xFFF85149).copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "搜索遇到错误: $searchError",
                                    fontSize = 12.sp,
                                    color = Color(0xFFF85149),
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    } else if (hasSearched && searchResults.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Text("未在 skills.sh 找到匹配的技能，请更换关键词搜索", fontSize = 12.sp, color = theme.textMuted)
                            }
                        }
                    } else if (searchResults.isNotEmpty()) {
                        item {
                            Text(
                                text = "搜索结果 (${searchResults.size} 项)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textSecondary,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        items(searchResults, key = { it.`package` }) { result ->
                            val isAlreadyInstalled = skills.any {
                                it.scope.equals(addSkillTargetScope, true) &&
                                    (it.install?.`package` == result.`package` || it.name.equals(result.skillName, ignoreCase = true))
                            }
                            val isInstallingThis = installingPackage == result.`package`

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // 紧凑两行式：标题一行 + 元信息一行，右侧固定动作区。
                                    // 旧版把「repo · 安装量 · skills.sh ↗」塞进不限宽的 Row，长仓库名会把
                                    // skills.sh 挤成逐字竖排（sk / ill / s. / sh），卡片也被撑得很高。
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Text(
                                            text = result.skillName,
                                            fontSize = 13.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = result.repoName,
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.5.sp,
                                                color = theme.textMuted,
                                                maxLines = 1,
                                                softWrap = false,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            if (result.installs.isNotBlank()) {
                                                Text(
                                                    text = " · ${result.installs}",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = Color(0xFF3FB950),
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(6.dp))

                                    // 外链改为图标按钮：不会再出现“skills.sh ↗”被挤到竖排
                                    if (result.url.isNotBlank()) {
                                        IconButton(
                                            onClick = {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                                                }
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "在 skills.sh 打开",
                                                modifier = Modifier.size(15.dp),
                                                tint = Color(0xFF58A6FF)
                                            )
                                        }
                                    }

                                    if (isAlreadyInstalled) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF2EA043).copy(alpha = 0.15f),
                                            border = BorderStroke(1.dp, Color(0xFF2EA043).copy(alpha = 0.4f))
                                        ) {
                                            Text(
                                                text = "✓ 已安装",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2EA043),
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    } else if (isInstallingThis) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = theme.primary
                                            )
                                            Text(
                                                text = "安装中",
                                                fontSize = 11.sp,
                                                color = theme.primary,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    } else {
                                        Button(
                                            onClick = {
                                                installingPackage = result.`package`
                                                onInstallSkill(result.`package`, addSkillTargetScope)
                                                coroutineScope.launch {
                                                    delay(1500)
                                                    installingPackage = null
                                                }
                                            },
                                            enabled = installingPackage == null,
                                            colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 5.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text("安装", fontSize = 12.sp, maxLines = 1, softWrap = false)
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

/**
 * 技能卡片（紧凑单行式）：状态点 + 名称/别名/更新标记 + 单行描述 + 可见性开关。
 *
 * 可见性由「左侧状态点 + 右侧开关 + 整卡明暗」三重表达，不再占用第三行文字。
 * 单击进入详情，长按编辑别名。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SkillCardItem(
    skill: SkillInfo,
    isProject: Boolean,
    isUpdateAvailable: Boolean,
    isUpdating: Boolean,
    onToggle: (Boolean) -> Unit,
    onEditAlias: () -> Unit,
    onViewDetail: () -> Unit,
    onUpdate: () -> Unit
) {
    val theme = LocalAppTheme.current
    val isVisible = skill.isVisibleToModel

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isVisible) theme.surfaceContainerLow else theme.surfaceContainerLow.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, if (isVisible) theme.outline else theme.outline.copy(alpha = 0.55f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onViewDetail, onLongClick = onEditAlias)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态圆点：绿色 = 模型可见；灰色 = 对模型隐藏
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        color = if (isVisible) Color(0xFF2EA043) else Color(0xFF6E7681),
                        shape = CircleShape
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isVisible) theme.textPrimary else theme.textMuted,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    // 别名：仅在有别名时出现（去掉常驻的"别名 ✎"噪音），点击可直接修改
                    val alias = skill.alias
                    if (!alias.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = theme.primary.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = alias,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.primary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable { onEditAlias() }
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }

                    if (isUpdateAvailable) {
                        Spacer(modifier = Modifier.width(5.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFD29922).copy(alpha = 0.15f),
                            border = BorderStroke(0.5.dp, Color(0xFFD29922).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = if (isUpdating) "更新中" else "↑ 可更新",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD29922),
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier
                                    .clickable { onUpdate() }
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(1.dp))

                Text(
                    text = skill.description?.takeIf { it.isNotBlank() } ?: "点开查看 / 编辑 SKILL.md",
                    fontSize = 11.sp,
                    color = if (isVisible) theme.textSecondary else theme.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            Switch(
                checked = isVisible,
                onCheckedChange = { onToggle(it) },
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

/**
 * 技能分组头（轻量化标签行）：图标 + 标题 + 计数徽章 + 批量开关。
 * 不再使用卡片容器，空分组因此只占一行。
 */
@Composable
private fun SkillGroupHeader(
    title: String,
    icon: ImageVector,
    accent: Color,
    badgeText: String,
    checked: Boolean,
    switchEnabled: Boolean,
    onToggleAll: (Boolean) -> Unit
) {
    val theme = LocalAppTheme.current

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = accent)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = theme.textPrimary
            )
            Spacer(modifier = Modifier.width(6.dp))
            Surface(shape = RoundedCornerShape(4.dp), color = accent.copy(alpha = 0.15f)) {
                Text(
                    text = badgeText,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
        }

        Switch(
            checked = checked,
            enabled = switchEnabled,
            onCheckedChange = onToggleAll,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF2EA043),
                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f),
                uncheckedThumbColor = theme.textMuted,
                uncheckedTrackColor = theme.surfaceContainerHigh
            )
        )
    }
}

/**
 * 分组空态 / 无匹配提示：单行文本，可点击跳转到添加技能。
 */
@Composable
private fun SkillHintRow(text: String, onClick: (() -> Unit)? = null) {
    val theme = LocalAppTheme.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = text, fontSize = 11.5.sp, color = theme.textMuted)
        if (onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = theme.textMuted
            )
        }
    }
}

// ==========================================
// Tab 3: MCP 协议
// ==========================================
@Composable
fun GlobalMcpTab(
    mcpConfig: McpCodeModeConfig,
    mcpServers: List<McpServerInfo>,
    apiClient: PiApiClient? = null,
    onAddMcpServer: (name: String, transport: String, endpoint: String, description: String, scope: String) -> Unit = { _, _, _, _, _ -> },
    onManageMcpServer: (action: String, name: String) -> Unit = { _, _ -> },
    onUpdateMcpCodeMode: (mode: String, scriptMode: Boolean, tokenBudget: Int) -> Unit = { _, _, _ -> }
) {
    val theme = LocalAppTheme.current
    val coroutineScope = rememberCoroutineScope()
    var codeMode by remember(mcpConfig.mode) { mutableStateOf(mcpConfig.mode) }
    var scriptMode by remember(mcpConfig.scriptMode) { mutableStateOf(mcpConfig.scriptMode) }
    var tokenBudget by remember(mcpConfig.tokenBudget) { mutableStateOf(mcpConfig.tokenBudget) }
    var showAddMcpDialog by remember { mutableStateOf(false) }
    var editingMcpServer by remember { mutableStateOf<McpServerInfo?>(null) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var testingServerName by remember { mutableStateOf<String?>(null) }
    var testResultDialog by remember { mutableStateOf<Pair<String, McpTestToolResult>?>(null) }
    var testErrorDialog by remember { mutableStateOf<Pair<String, String>?>(null) }

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
                        infoDescription = "MCP 服务器与 Code Mode 预算",
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
            val connected = server.isConnected && server.enabled
            val statusColor = if (connected) Color(0xFF2EA043) else theme.textMuted
            val toolCount = server.toolExposureCount
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
                    // 第一行：仅保留「状态点 + 名称 + 开关」，名称占据剩余宽度并可省略，
                    // 彻底避免工具数、按钮、开关挤在同一行导致的徽标折行（“6 工/具”）。
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = CircleShape,
                            color = statusColor
                        ) {}
                        Spacer(modifier = Modifier.width(7.dp))
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = server.enabled,
                            onCheckedChange = { enabled ->
                                onManageMcpServer(if (enabled) "enable" else "disable", server.name)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF2EA043),
                                checkedTrackColor = Color(0xFF2EA043).copy(alpha = 0.3f),
                                uncheckedThumbColor = theme.textMuted,
                                uncheckedTrackColor = theme.surfaceContainer
                            )
                        )
                    }

                    // 第二行：元信息标签，纯文字 + 分隔点，无灰底填充，单行不折行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (toolCount > 0) {
                            Text(
                                text = "$toolCount 个工具",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                            Text(
                                text = "  ·  ",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        Text(
                            text = "${server.transport?.uppercase() ?: "HTTP"} · ${if (connected) "已连接" else if (server.enabled) "未连接" else "已停用"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!server.description.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = server.description ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textSecondary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (!server.url.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = server.url ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = theme.outline.copy(alpha = 0.8f))

                    // 操作区：独占一行并均分，任何屏幕宽度下都不会挤压或折行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    if (apiClient != null) {
                                        testingServerName = server.name
                                        coroutineScope.launch {
                                            val res = apiClient.testMcpServer(
                                                name = server.name,
                                                scope = server.scope ?: "global"
                                            )
                                            testingServerName = null
                                            res.onSuccess { info ->
                                                testResultDialog = server.name to info
                                            }.onFailure { err ->
                                                testErrorDialog = server.name to (err.message ?: "未知错误")
                                            }
                                        }
                                    }
                                },
                                enabled = testingServerName == null && apiClient != null,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                if (testingServerName == server.name) {
                                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = theme.primary)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("测试中", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                                } else {
                                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(12.dp), tint = theme.primary)
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("测试", style = MaterialTheme.typography.labelSmall, color = theme.primary, maxLines = 1, softWrap = false)
                                }
                            }
                            TextButton(
                                onClick = {
                                    editingMcpServer = server
                                    showAddMcpDialog = true
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(12.dp), tint = theme.textSecondary)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("编辑", style = MaterialTheme.typography.labelSmall, color = theme.textSecondary, maxLines = 1, softWrap = false)
                            }
                        }
                        TextButton(
                            onClick = { onManageMcpServer("remove", server.name) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color(0xFFCF222E))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("移除", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCF222E), maxLines = 1, softWrap = false)
                        }
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

    // MCP 测试成功对话框
    testResultDialog?.let { (name, info) ->
        AlertDialog(
            onDismissRequest = { testResultDialog = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFF2EA043), modifier = Modifier.size(8.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("MCP 节点测试通过: $name", color = theme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.message, style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                    if (info.tools.isNotEmpty()) {
                        Text("导出工具列表 (${info.tools.size} 个):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textPrimary)
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = theme.background,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)
                        ) {
                            LazyColumn(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(info.tools) { toolName ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("•", color = theme.primary, modifier = Modifier.padding(end = 6.dp))
                                        Text(toolName, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = theme.textPrimary)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { testResultDialog = null }) {
                    Text("确定", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // MCP 测试失败对话框
    testErrorDialog?.let { (name, err) ->
        AlertDialog(
            onDismissRequest = { testErrorDialog = null },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Color(0xFFCF222E), modifier = Modifier.size(8.dp)) {}
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("MCP 节点连通失败: $name", color = Color(0xFFCF222E), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("探测失败：", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = theme.background,
                        border = BorderStroke(1.dp, Color(0xFFCF222E).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    ) {
                        Text(err, color = Color(0xFFCF222E), fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { testErrorDialog = null }) {
                    Text("关闭", color = theme.textSecondary)
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
                        infoDescription = "NPM / Git 插件，可随时启停与更新",
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
    taskCompletedNotifyEnabled: Boolean = false,
    onToggleTaskCompletedNotify: (Boolean) -> Unit = {},
    hapticsEnabled: Boolean = true,
    onToggleHaptics: (Boolean) -> Unit = {},
    onTestHaptics: () -> Unit = {},
    completionSoundEnabled: Boolean = true,
    onToggleCompletionSound: (Boolean) -> Unit = {},
    onTestCompletionSound: () -> Unit = {},
    autoCompactionEnabled: Boolean = true,
    onToggleAutoCompaction: (Boolean) -> Unit = {},
    autoRetryEnabled: Boolean = true,
    onToggleAutoRetry: (Boolean) -> Unit = {},
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
    // 「访问令牌」默认打码，点「显示」才展开明文：密钥已在二维码/日志里泄露过，
    // 屏幕是剩下的最容易被人拍走的一环。
    var showFullToken by remember { mutableStateOf(false) }
    val currentVersion = remember { AppUpdateManager.getCurrentVersionName(context) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 第三方镜像说明弹窗（开关在「关于与升级」卡片内）
    var showMirrorInfo by remember { mutableStateOf(false) }

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
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // ── 外观与反馈 ───────────────────────────────────────────────
        item(key = "appearance") {
            Column {
                SettingsGroupLabel(text = "外观与反馈")
                SettingsGroup {
                    SettingsRow(
                        title = "主题外观",
                        subtitle = "${ThemeManager.currentTheme.titleZh} · ${if (ThemeManager.currentTheme.isDark) "深色" else "浅色"}",
                        trailing = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                AppTheme.entries.forEach { appTheme ->
                                    val isSelected = ThemeManager.currentTheme == appTheme
                                    Spacer(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .background(
                                                color = if (isSelected) theme.textPrimary else Color.Transparent,
                                                shape = CircleShape
                                            )
                                            .padding(if (isSelected) 2.dp else 0.dp)
                                            .background(
                                                brush = androidx.compose.ui.graphics.Brush.linearGradient(appTheme.previewGradient),
                                                shape = CircleShape
                                            )
                                            .clickable { ThemeManager.setTheme(appTheme) }
                                    )
                                }
                            }
                        }
                    )
                    SettingsRow(
                        title = "马达触觉反馈",
                        subtitle = "完成双振、审批重振",
                        topDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsInlineAction(text = "试振", onClick = onTestHaptics)
                                SettingsSwitch(checked = hapticsEnabled, onCheckedChange = onToggleHaptics)
                            }
                        }
                    )
                    SettingsRow(
                        title = "任务完成提示音",
                        subtitle = "任务完成时播放一次提示音",
                        topDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsInlineAction(text = "试听", onClick = onTestCompletionSound)
                                SettingsSwitch(checked = completionSoundEnabled, onCheckedChange = onToggleCompletionSound)
                            }
                        }
                    )
                }
            }
        }

        // ── 后台与保活 ───────────────────────────────────────────────
        item(key = "keepalive") {
            Column {
                SettingsGroupLabel(text = "后台与保活")
                SettingsGroup {
                    SettingsRow(
                        title = "常驻通知栏保活",
                        subtitle = if (persistentNotifyEnabled) "前台服务运行中，防系统回收" else "关闭后锁屏可能被回收连接",
                        trailing = {
                            SettingsSwitch(checked = persistentNotifyEnabled, onCheckedChange = onTogglePersistentNotify)
                        }
                    )
                    SettingsRow(
                        title = "任务完成独立弹窗通知",
                        subtitle = "完成时额外弹一张通知卡片",
                        topDivider = true,
                        trailing = {
                            SettingsSwitch(checked = taskCompletedNotifyEnabled, onCheckedChange = onToggleTaskCompletedNotify)
                        }
                    )
                    SettingsRow(
                        title = "电池优化白名单",
                        subtitle = if (liveIgnoringBattery) "后台网络不受系统休眠限制" else "建议加入，防息屏冻结长连接",
                        value = if (liveIgnoringBattery) "已加入" else "去开启",
                        valueColor = if (liveIgnoringBattery) Color(0xFF2EA043) else theme.primary,
                        showChevron = !liveIgnoringBattery,
                        topDivider = true,
                        onClick = {
                            if (!liveIgnoringBattery) {
                                onRequestIgnoreBattery()
                                scope.launch {
                                    listOf(500L, 1200L, 2500L).forEach { d ->
                                        delay(d)
                                        liveIgnoringBattery = KeepAliveManager(context).isIgnoringBatteryOptimizations()
                                    }
                                }
                            }
                        }
                    )
                    SettingsRow(
                        title = "CPU 唤醒锁与断线自愈",
                        subtitle = "任务期持有唤醒锁，断线自动重连",
                        value = "已就绪",
                        valueColor = Color(0xFF2EA043),
                        topDivider = true
                    )
                }
            }
        }

        // ── 会话行为 ─────────────────────────────────────────────────
        item(key = "session") {
            Column {
                SettingsGroupLabel(text = "会话行为")
                SettingsGroup {
                    SettingsRow(
                        title = "自动压缩上下文",
                        subtitle = "接近窗口上限时在回合边界压缩，不打断当前请求",
                        trailing = {
                            SettingsSwitch(checked = autoCompactionEnabled, onCheckedChange = onToggleAutoCompaction)
                        }
                    )
                    SettingsRow(
                        title = "自动重试",
                        subtitle = "模型报错或断流时自动重试本轮",
                        topDivider = true,
                        trailing = {
                            SettingsSwitch(checked = autoRetryEnabled, onCheckedChange = onToggleAutoRetry)
                        }
                    )
                }
            }
        }

        // ── 网关连接 ─────────────────────────────────────────────────
        item(key = "connection") {
            val isConnected = serverUrl.isNotBlank() && authToken.isNotBlank()
            Column {
                SettingsGroupLabel(text = "网关连接")
                SettingsGroup {
                    SettingsRow(
                        title = deviceName.ifBlank { if (isConnected) "My PC" else "未配对" },
                        subtitle = if (isConnected) "已配对 · 令牌就绪" else "扫码或手动填写网关地址与访问令牌",
                        value = if (isConnected) "在线" else "未配对",
                        valueColor = if (isConnected) Color(0xFF2EA043) else theme.textMuted
                    )
                    SettingsRow(
                        title = "网关地址",
                        value = serverUrl.ifBlank { "未设置" },
                        monoValue = true,
                        topDivider = true,
                        showChevron = true,
                        onClick = onEditConnection
                    )
                    SettingsRow(
                        title = "访问令牌",
                        value = when {
                            authToken.isBlank() -> "未设置"
                            showFullToken -> authToken
                            else -> "${authToken.take(6)}••••${authToken.takeLast(4)}"
                        },
                        monoValue = true,
                        topDivider = true,
                        trailing = {
                            if (authToken.isNotBlank()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    SettingsInlineAction(
                                        text = if (showFullToken) "隐藏" else "显示",
                                        onClick = { showFullToken = !showFullToken }
                                    )
                                    SettingsInlineAction(
                                        text = "复制",
                                        onClick = {
                                            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                            clipboard?.setPrimaryClip(
                                                android.content.ClipData.newPlainText("piDroid Token", authToken)
                                            )
                                            Toast.makeText(context, "访问令牌已复制", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    HorizontalDivider(color = theme.outline, thickness = 1.dp)
                    SettingsGroupActionRow {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.primary,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onEditConnection() }
                        ) {
                            Text(
                                text = if (isConnected) "编辑连接配置" else "手动添加连接",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onScanQr() }
                        ) {
                            Text(
                                text = "扫码 / 相册配对",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.textPrimary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            )
                        }
                    }
                    if (isConnected) {
                        SettingsRow(
                            title = "断开当前连接",
                            subtitle = "清空本地令牌，需要重新配对",
                            titleColor = Color(0xFFCF222E),
                            topDivider = true,
                            showChevron = true,
                            onClick = onDisconnect
                        )
                    }
                }
            }
        }

        // ── 关于与升级 ───────────────────────────────────────────────
        item(key = "about") {
            var useMirrors by remember { mutableStateOf(AppPreferences(context).useUpdateMirrors) }
            Column {
                SettingsGroupLabel(text = "关于与升级")
                SettingsGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PiDroidLogo(size = 28.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "piDroid",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.textPrimary
                            )
                            Text(
                                text = "v$currentVersion",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.primary.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, theme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier.clickable(enabled = !isCheckingUpdate) { onCheckUpdate() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                if (isCheckingUpdate) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = theme.primary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = theme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = if (isCheckingUpdate) "检测中" else "检查更新",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.primary
                                )
                            }
                        }
                    }
                    SettingsRow(
                        title = "使用第三方加速镜像",
                        subtitle = "ghfast.top / ghproxy.net / gh-proxy.com 中转下载",
                        topDivider = true,
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { showMirrorInfo = true }, modifier = Modifier.size(28.dp)) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "镜像说明",
                                        tint = theme.textMuted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                SettingsSwitch(
                                    checked = useMirrors,
                                    onCheckedChange = { checked ->
                                        useMirrors = checked
                                        AppPreferences(context).useUpdateMirrors = checked
                                    }
                                )
                            }
                        }
                    )
                    SettingsRow(
                        title = "开发者",
                        value = "Powered by codywon",
                        monoValue = true,
                        valueColor = theme.primary,
                        topDivider = true
                    )
                    SettingsRow(
                        title = "许可协议",
                        value = "CC BY-NC · 禁止商用",
                        topDivider = true
                    )
                }
            }
        }
    }

    if (showMirrorInfo) {
        AlertDialog(
            onDismissRequest = { showMirrorInfo = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("第三方加速镜像", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("仅加速安装包下载，检测新版本仍走 api.github.com。", fontSize = 12.sp, color = theme.textSecondary, lineHeight = 18.sp)
                    Text("中转站点：ghfast.top、ghproxy.net、gh-proxy.com（均为第三方公益镜像，与本应用无关）。", fontSize = 12.sp, color = theme.textSecondary, lineHeight = 18.sp)
                    Text("风险：镜像方可见你的 IP 与所下版本；若文件被篡改，SHA-256 与签名校验会拦住安装。", fontSize = 12.sp, color = theme.textSecondary, lineHeight = 18.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showMirrorInfo = false }) {
                    Text("知道了", color = theme.primary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}
