package com.pi.app.ui.settings

import android.widget.Toast
import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.*
import com.pi.app.network.PiApiClient
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.launch

/**
 * 1:1 对标 pi-web 原版的高级模型与提供商管理标签页
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalModelsConfigTab(
    apiClient: PiApiClient,
    currentDefaultThinkingLevel: ThinkingLevel,
    onSetDefaultThinkingLevel: (ThinkingLevel) -> Unit,
    onModelsConfigChanged: () -> Unit = {}
) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var isSaving by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<ProviderConfigItem>>(emptyList()) }
    var rawConfigRoot by remember { mutableStateOf<JsonObject?>(null) }

    var selectedProviderName by remember { mutableStateOf<String?>(null) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }

    // 弹窗状态
    var showAddProviderDialog by remember { mutableStateOf(false) }
    var showEditProviderDialog by remember { mutableStateOf(false) }
    var showDeleteProviderDialog by remember { mutableStateOf(false) }
    var showDeleteModelDialog by remember { mutableStateOf(false) }
    var showRawJsonDialog by remember { mutableStateOf(false) }

    // 从 API 自动发现模型 (POST /api/models-config/discover 或 /v1/models)
    var showDiscoverDialog by remember { mutableStateOf(false) }
    var isDiscovering by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var discoveredModels by remember { mutableStateOf<List<com.pi.app.network.DiscoveredModelItem>>(emptyList()) }
    var discoverSearchQuery by remember { mutableStateOf("") }
    var selectedDiscoverIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isModelsExpanded by remember { mutableStateOf(false) }
    var rawJsonBuffer by remember { mutableStateOf("") }

    // 测试与自动填入状态
    var isTestingModel by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf<Boolean?>(null) }
    var isCatalogFilling by remember { mutableStateOf(false) }

    // 折叠高级设置状态
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    // 辅助函数：加载服务端配置
    fun loadConfigFromServer() {
        isLoading = true
        coroutineScope.launch {
            val result = apiClient.getModelsConfigObject()
            result.onSuccess { rootObj ->
                rawConfigRoot = rootObj
                val parsed = ModelConfigConverter.parseProvidersConfig(rootObj)
                providers = parsed
                if (parsed.isNotEmpty()) {
                    val currentProv = parsed.firstOrNull { it.name == selectedProviderName } ?: parsed.first()
                    selectedProviderName = currentProv.name
                    if (currentProv.models.isNotEmpty()) {
                        val currentM = currentProv.models.firstOrNull { it.id == selectedModelId } ?: currentProv.models.first()
                        selectedModelId = currentM.id
                    } else {
                        selectedModelId = null
                    }
                } else {
                    selectedProviderName = null
                    selectedModelId = null
                }
            }.onFailure { err ->
                Toast.makeText(context, "读取模型配置失败: ${err.message}", Toast.LENGTH_LONG).show()
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadConfigFromServer()
    }

    // 获取当前选中的 Provider 和 Model
    val currentProvider = remember(providers, selectedProviderName) {
        providers.firstOrNull { it.name == selectedProviderName }
    }
    val currentModel = remember(currentProvider, selectedModelId) {
        currentProvider?.models?.firstOrNull { it.id == selectedModelId }
    }

    // 保存方法
    fun saveAllConfig() {
        if (isSaving) return
        isSaving = true
        coroutineScope.launch {
            try {
                val serialized = ModelConfigConverter.serializeProvidersConfig(providers, rawConfigRoot)
                val saveResult = apiClient.saveModelsConfigObject(serialized)
                saveResult.onSuccess {
                    Toast.makeText(context, "已成功保存模型配置并刷新生效！", Toast.LENGTH_SHORT).show()
                    onModelsConfigChanged()
                }.onFailure { err ->
                    Toast.makeText(context, "保存失败: ${err.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "序列化配置出错: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    // 更新当前正在编辑的模型的局部字段
    fun updateCurrentModel(transform: (ModelConfigItem) -> ModelConfigItem) {
        val prov = currentProvider ?: return
        val m = currentModel ?: return
        val updatedModel = transform(m)
        val updatedModels = prov.models.map { if (it.id == m.id) updatedModel else it }
        val updatedProv = prov.copy(models = updatedModels)
        providers = providers.map { if (it.name == prov.name) updatedProv else it }
        // 若 ID 被修改，同步更新选中 ID
        if (updatedModel.id != m.id) {
            selectedModelId = updatedModel.id
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(color = theme.primary, modifier = Modifier.size(36.dp))
                Spacer(modifier = Modifier.height(12.dp))
                Text("正在加载网关模型配置 (models.json)...", color = theme.textSecondary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .padding(bottom = 68.dp), // 留出底部保存按钮空间
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 全局默认推理思考档位 (7 档)
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

                // 2. 顶栏操作区：Provider 总览与操作
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "提供商与模型管理 (${providers.size} 提供商 / ${providers.sumOf { it.models.size }} 模型)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(
                                onClick = { loadConfigFromServer() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = theme.primary, modifier = Modifier.size(16.dp))
                            }

                            IconButton(
                                onClick = {
                                    rawJsonBuffer = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                                        .toJson(ModelConfigConverter.serializeProvidersConfig(providers, rawConfigRoot))
                                    showRawJsonDialog = true
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Code, contentDescription = "查看 JSON 源码", tint = theme.textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // 3. Provider 选择水平滚动条
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        providers.forEach { prov ->
                            val isSel = prov.name == selectedProviderName
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) theme.surfaceContainerHigh else theme.surfaceContainerLow,
                                border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                modifier = Modifier.clickable {
                                    selectedProviderName = prov.name
                                    selectedModelId = prov.models.firstOrNull()?.id
                                    testResultText = null
                                    isTestSuccess = null
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudQueue,
                                        contentDescription = null,
                                        tint = if (isSel) theme.primary else theme.textSecondary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = prov.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) theme.textPrimary else theme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(${prov.models.size})",
                                        fontSize = 10.sp,
                                        color = theme.textMuted
                                    )
                                }
                            }
                        }

                        // + 添加 Provider 按钮
                        OutlinedButton(
                            onClick = { showAddProviderDialog = true },
                            border = BorderStroke(1.dp, theme.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = theme.primary, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("添加 Provider", fontSize = 11.sp, color = theme.primary)
                        }
                    }
                }

                // 4. 当前选中 Provider 的完整信息与管理面板 (Base URL 完整展示，不再截断)
                if (currentProvider != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = theme.surfaceContainerLow),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 顶行：Provider 标识、协议徽标与操作按钮
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = theme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = currentProvider.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = theme.textPrimary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = theme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(0.5.dp, theme.primary.copy(alpha = 0.35f))
                                        ) {
                                            Text(
                                                text = currentProvider.api.ifBlank { "openai-completions" },
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.primary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        OutlinedButton(
                                            onClick = { showEditProviderDialog = true },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, theme.outline),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(11.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("配置", fontSize = 10.5.sp, color = theme.textPrimary)
                                        }

                                        OutlinedButton(
                                            onClick = { showDeleteProviderDialog = true },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("删除", fontSize = 10.5.sp, color = Color(0xFFEF5350))
                                        }
                                    }
                                }

                                // 第二行：完整的 Base URL 展示框 (解决省略截断痛点，支持一键复制)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = theme.surfaceContainer,
                                    border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.6f)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Text(
                                            text = "Base URL",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.textMuted,
                                            modifier = Modifier.padding(end = 6.dp)
                                        )
                                        Text(
                                            text = currentProvider.baseUrl.ifBlank { "(未指定自定义 Base URL，采用接口默认端点)" },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (currentProvider.baseUrl.isNotBlank()) theme.textPrimary else theme.textMuted,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (currentProvider.baseUrl.isNotBlank()) {
                                            IconButton(
                                                onClick = {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Base URL", currentProvider.baseUrl))
                                                    Toast.makeText(context, "已复制 Base URL", Toast.LENGTH_SHORT).show()
                                                },
                                                modifier = Modifier.size(22.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.ContentCopy,
                                                    contentDescription = "复制",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = theme.textSecondary
                                                )
                                            }
                                        }
                                    }
                                }

                                // 第三行：API Key 安全掩码与状态提示
                                val maskedKey = if (currentProvider.apiKey.isNotBlank()) {
                                    if (currentProvider.apiKey.length > 8) {
                                        "${currentProvider.apiKey.take(3)}••••••••${currentProvider.apiKey.takeLast(4)}"
                                    } else "••••••••"
                                } else "未配置 API Key"

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "API Key: ",
                                            fontSize = 10.5.sp,
                                            color = theme.textMuted
                                        )
                                        Text(
                                            text = maskedKey,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            color = if (currentProvider.apiKey.isNotBlank()) theme.textSecondary else Color(0xFFEF5350)
                                        )
                                    }

                                    // 快捷操作：从 API 自动发现拉取模型按钮
                                    FilledTonalButton(
                                        onClick = {
                                            showDiscoverDialog = true
                                            isDiscovering = true
                                            discoverError = null
                                            selectedDiscoverIds = emptySet()
                                            coroutineScope.launch {
                                                apiClient.discoverProviderModels(
                                                    providerName = currentProvider.name,
                                                    baseUrl = currentProvider.baseUrl,
                                                    apiKey = currentProvider.apiKey,
                                                    api = currentProvider.api
                                                ).onSuccess { res ->
                                                    discoveredModels = res.models
                                                    isDiscovering = false
                                                }.onFailure { err ->
                                                    discoverError = err.message
                                                    isDiscovering = false
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Icon(Icons.Default.Bolt, contentDescription = null, tint = theme.primary, modifier = Modifier.size(12.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("从 API 发现模型", fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 5. 下属模型列表重构 (告别横向拖拽，采用 2 列精致网格 + 顶部双快捷按钮 + 超过6个折叠展开)
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "下属模型列表 (${currentProvider.models.size})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.textSecondary
                            )

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                // 1) 从 API 自动发现模型
                                OutlinedButton(
                                    onClick = {
                                        showDiscoverDialog = true
                                        isDiscovering = true
                                        discoverError = null
                                        selectedDiscoverIds = emptySet()
                                        coroutineScope.launch {
                                            apiClient.discoverProviderModels(
                                                providerName = currentProvider.name,
                                                baseUrl = currentProvider.baseUrl,
                                                apiKey = currentProvider.apiKey,
                                                api = currentProvider.api
                                            ).onSuccess { res ->
                                                discoveredModels = res.models
                                                isDiscovering = false
                                            }.onFailure { err ->
                                                discoverError = err.message
                                                isDiscovering = false
                                            }
                                        }
                                    },
                                    border = BorderStroke(1.dp, theme.primary),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(Icons.Default.Bolt, contentDescription = null, tint = theme.primary, modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("从 API 导入", fontSize = 10.5.sp, color = theme.primary, fontWeight = FontWeight.Bold)
                                }

                                // 2) 手动添加模型
                                Button(
                                    onClick = {
                                        var newId = "custom-model"
                                        var idx = 1
                                        while (currentProvider.models.any { it.id == newId }) {
                                            newId = "custom-model-$idx"
                                            idx++
                                        }
                                        val newModel = ModelConfigItem(id = newId, name = newId, reasoning = true)
                                        val updatedProv = currentProvider.copy(models = currentProvider.models + newModel)
                                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                                        selectedModelId = newId
                                        testResultText = null
                                        isTestSuccess = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = theme.surfaceContainerHigh, contentColor = theme.primary),
                                    border = BorderStroke(1.dp, theme.outline),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = theme.primary, modifier = Modifier.size(11.dp))
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("添加模型", fontSize = 10.5.sp, color = theme.primary)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))

                        // 2 列自适应网格排布 (移动端排版最舒适规整，彻底告别单行拉扯)
                        val visibleModels = if (isModelsExpanded || currentProvider.models.size <= 6) {
                            currentProvider.models
                        } else {
                            currentProvider.models.take(6)
                        }

                        val modelChunks = visibleModels.chunked(2)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            modelChunks.forEach { pair ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    pair.forEach { m ->
                                        val isSel = m.id == selectedModelId
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainerLow,
                                            border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
                                                    selectedModelId = m.id
                                                    testResultText = null
                                                    isTestSuccess = null
                                                }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = m.id,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) theme.primary else theme.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )

                                                if (m.reasoning) {
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Psychology,
                                                        contentDescription = "支持推理思考",
                                                        modifier = Modifier.size(12.dp),
                                                        tint = if (isSel) theme.primary else Color(0xFF8B5CF6)
                                                    )
                                                }
                                                if (m.hasVision) {
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Icon(
                                                        imageVector = Icons.Default.Image,
                                                        contentDescription = "支持多模态视觉",
                                                        modifier = Modifier.size(11.dp),
                                                        tint = if (isSel) theme.primary else theme.textMuted
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    if (pair.size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }

                            // 超过 6 个模型时，提供精致的折叠与展开切换胶囊
                            if (currentProvider.models.size > 6) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = theme.surfaceContainer,
                                    border = BorderStroke(0.5.dp, theme.outline),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isModelsExpanded = !isModelsExpanded }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 5.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isModelsExpanded) "收起模型列表 ▴" else "展开全部 ${currentProvider.models.size} 个模型 ▾",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = theme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 6. 1:1 对标截图的模型全功能编辑卡片
                    if (currentModel != null) {
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = theme.surfaceContainerLow),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    // 顶部 Header: 标题“模型” + [测试] + [移除]
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "模型",
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.textPrimary
                                        )

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            // [测试] 按钮
                                            OutlinedButton(
                                                onClick = {
                                                    if (isTestingModel) return@OutlinedButton
                                                    isTestingModel = true
                                                    testResultText = null
                                                    isTestSuccess = null
                                                    coroutineScope.launch {
                                                        val provObj = JsonObject().apply {
                                                            if (currentProvider.baseUrl.isNotBlank()) addProperty("baseUrl", currentProvider.baseUrl)
                                                            if (currentProvider.apiKey.isNotBlank()) addProperty("apiKey", currentProvider.apiKey)
                                                            if (currentProvider.api.isNotBlank()) addProperty("api", currentProvider.api)
                                                        }
                                                        val modelObj = JsonObject().apply {
                                                            addProperty("id", currentModel.id)
                                                            addProperty("name", currentModel.name)
                                                            addProperty("contextWindow", currentModel.contextWindow)
                                                            addProperty("maxTokens", currentModel.maxTokens)
                                                            if (currentModel.apiOverride.isNotBlank()) addProperty("api", currentModel.apiOverride)
                                                        }
                                                        val testRes = apiClient.testModelConfig(currentProvider.name, provObj, modelObj)
                                                        testRes.onSuccess { r ->
                                                            isTestSuccess = r.ok
                                                            if (r.ok) {
                                                                testResultText = "测试通过 (${r.latencyMs ?: 0}ms)"
                                                            } else {
                                                                testResultText = "失败: ${r.error ?: "未知错误"}"
                                                            }
                                                        }.onFailure { err ->
                                                            isTestSuccess = false
                                                            testResultText = "网络错误: ${err.message}"
                                                        }
                                                        isTestingModel = false
                                                    }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, theme.outline),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                if (isTestingModel) {
                                                    CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = theme.primary)
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("测试中...", fontSize = 11.sp, color = theme.textPrimary)
                                                } else {
                                                    Text("测试", fontSize = 11.sp, color = theme.textPrimary)
                                                }
                                            }

                                            // [移除] 按钮
                                            OutlinedButton(
                                                onClick = { showDeleteModelDialog = true },
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(28.dp)
                                            ) {
                                                Text("移除", fontSize = 11.sp, color = Color(0xFFEF5350))
                                            }
                                        }
                                    }

                                    // 测试结果通知条：清爽现代风格，自适应高对比度森林绿/绯红，带图标与毫秒延迟
                                    if (testResultText != null) {
                                        val isSuccess = isTestSuccess == true
                                        val bgClr = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.12f) else Color(0xFFEF4444).copy(alpha = 0.12f)
                                        val borderClr = if (isSuccess) Color(0xFF10B981).copy(alpha = 0.35f) else Color(0xFFEF4444).copy(alpha = 0.35f)
                                        val textClr = if (isSuccess) Color(0xFF065F46) else Color(0xFF991B1B)

                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = bgClr,
                                            border = BorderStroke(1.dp, borderClr),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp),
                                                    tint = if (isSuccess) Color(0xFF059669) else Color(0xFFDC2626)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = testResultText ?: "",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = textClr
                                                )
                                            }
                                        }
                                    }

                                    // 基础项：ID * 与 Name (紧凑 36dp 高度单行输入框，独立标签在上方，彻底告别原先 56dp 巨型方框)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ModelInputField(
                                            value = currentModel.id,
                                            onValueChange = { newId -> updateCurrentModel { it.copy(id = newId) } },
                                            label = "ID *",
                                            placeholder = "如 deepseek-flash",
                                            isMonospace = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        ModelInputField(
                                            value = currentModel.name,
                                            onValueChange = { newName -> updateCurrentModel { it.copy(name = newName) } },
                                            label = "Name",
                                            placeholder = "如 DeepSeek V4 Flash",
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    // [填入模型信息] 按钮 (来源: models.dev ↗)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                if (isCatalogFilling) return@OutlinedButton
                                                isCatalogFilling = true
                                                coroutineScope.launch {
                                                    val catalogRes = apiClient.getCatalogModelPreset(
                                                        modelId = currentModel.id,
                                                        providerName = currentProvider.name,
                                                        baseUrlParam = currentProvider.baseUrl
                                                    )
                                                    catalogRes.onSuccess { preset ->
                                                        if (preset != null) {
                                                            updateCurrentModel { old ->
                                                                old.copy(
                                                                    name = preset.name?.takeIf { it.isNotBlank() } ?: old.name,
                                                                    reasoning = preset.reasoning ?: old.reasoning,
                                                                    hasVision = preset.input?.contains("image") ?: old.hasVision,
                                                                    contextWindow = preset.contextWindow ?: old.contextWindow,
                                                                    maxTokens = preset.maxTokens ?: old.maxTokens,
                                                                    costInput = preset.costInput ?: old.costInput,
                                                                    costOutput = preset.costOutput ?: old.costOutput,
                                                                    costCacheRead = preset.costCacheRead ?: old.costCacheRead,
                                                                    costCacheWrite = preset.costCacheWrite ?: old.costCacheWrite
                                                                )
                                                            }
                                                            Toast.makeText(context, "已从 models.dev 自动匹配并填入规格！", Toast.LENGTH_SHORT).show()
                                                        } else {
                                                            Toast.makeText(context, "models.dev 中未找到该模型精准匹配，保留现有值", Toast.LENGTH_SHORT).show()
                                                        }
                                                    }.onFailure { err ->
                                                        Toast.makeText(context, "获取推荐失败: ${err.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                    isCatalogFilling = false
                                                }
                                            },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, theme.outline),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                            modifier = Modifier.height(28.dp)
                                        ) {
                                            if (isCatalogFilling) {
                                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = theme.primary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("正在匹配...", fontSize = 11.sp, color = theme.textPrimary)
                                            } else {
                                                Text("填入模型信息", fontSize = 11.sp, color = theme.textPrimary)
                                            }
                                        }

                                        Text(
                                            text = "来源: models.dev ↗",
                                            fontSize = 11.sp,
                                            color = theme.textMuted
                                        )
                                    }

                                    // 能力复选框：☑ 推理 / 思考   ☑ 图片输入
                                    Text("能力", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { updateCurrentModel { it.copy(reasoning = !it.reasoning) } }
                                        ) {
                                            Checkbox(
                                                checked = currentModel.reasoning,
                                                onCheckedChange = { v -> updateCurrentModel { it.copy(reasoning = v) } },
                                                colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                                            )
                                            Text("推理 / 思考", fontSize = 12.sp, color = theme.textPrimary)
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { updateCurrentModel { it.copy(hasVision = !it.hasVision) } }
                                        ) {
                                            Checkbox(
                                                checked = currentModel.hasVision,
                                                onCheckedChange = { v -> updateCurrentModel { it.copy(hasVision = v) } },
                                                colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                                            )
                                            Text("图片输入", fontSize = 12.sp, color = theme.textPrimary)
                                        }
                                    }

                                    // 模型规格：上下文窗口 与 最大输出 tokens (带单位后缀与 Monospace 数值，高度 36dp)
                                    Text("模型规格", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        ModelInputField(
                                            value = currentModel.contextWindow.toString(),
                                            onValueChange = { str ->
                                                val v = str.filter { it.isDigit() }.toLongOrNull() ?: 0L
                                                updateCurrentModel { it.copy(contextWindow = v) }
                                            },
                                            label = "上下文窗口",
                                            trailingUnit = "tokens",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            isMonospace = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        ModelInputField(
                                            value = currentModel.maxTokens.toString(),
                                            onValueChange = { str ->
                                                val v = str.filter { it.isDigit() }.toLongOrNull() ?: 0L
                                                updateCurrentModel { it.copy(maxTokens = v) }
                                            },
                                            label = "最大输出",
                                            trailingUnit = "tokens",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            isMonospace = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    // 每百万 TOKENS 价格 ($) (采用 2x2 网格，宽度充足舒展，彻底告别单行 4 个正方形大坑)
                                    Text("每百万 TOKENS 价格 ($)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ModelInputField(
                                                value = currentModel.costInput.toString(),
                                                onValueChange = { str ->
                                                    val v = str.toDoubleOrNull() ?: 0.0
                                                    updateCurrentModel { it.copy(costInput = v) }
                                                },
                                                label = "输入价格",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                height = 34.dp,
                                                modifier = Modifier.weight(1f)
                                            )

                                            ModelInputField(
                                                value = currentModel.costOutput.toString(),
                                                onValueChange = { str ->
                                                    val v = str.toDoubleOrNull() ?: 0.0
                                                    updateCurrentModel { it.copy(costOutput = v) }
                                                },
                                                label = "输出价格",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                height = 34.dp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            ModelInputField(
                                                value = currentModel.costCacheRead.toString(),
                                                onValueChange = { str ->
                                                    val v = str.toDoubleOrNull() ?: 0.0
                                                    updateCurrentModel { it.copy(costCacheRead = v) }
                                                },
                                                label = "缓存读取",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                height = 34.dp,
                                                modifier = Modifier.weight(1f)
                                            )

                                            ModelInputField(
                                                value = currentModel.costCacheWrite.toString(),
                                                onValueChange = { str ->
                                                    val v = str.toDoubleOrNull() ?: 0.0
                                                    updateCurrentModel { it.copy(costCacheWrite = v) }
                                                },
                                                label = "缓存写入",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                height = 34.dp,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                    }

                                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))

                                    // 高级设置 (折叠卡片，默认收起，带状态微标与专属背景)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = theme.surfaceContainer,
                                        border = BorderStroke(1.dp, theme.outline),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isAdvancedExpanded = !isAdvancedExpanded }
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
                                                    imageVector = Icons.Default.Tune,
                                                    contentDescription = null,
                                                    tint = theme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column {
                                                    Text(
                                                        text = "高级设置",
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = theme.textPrimary
                                                    )
                                                    val customMapCount = currentModel.thinkingLevelMap.count { it.mode != ThinkingLevelMode.DEFAULT }
                                                    val statusDesc = if (customMapCount > 0) "思考等级已定制 ($customMapCount 项) · API: ${currentModel.apiOverride.ifBlank { "默认" }}" else "思考: 默认 · API: ${currentModel.apiOverride.ifBlank { "默认" }}"
                                                    Text(
                                                        text = statusDesc,
                                                        fontSize = 10.5.sp,
                                                        color = theme.textMuted
                                                    )
                                                }
                                            }

                                            Icon(
                                                imageVector = if (isAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                                contentDescription = null,
                                                tint = theme.textSecondary
                                            )
                                        }
                                    }

                                    AnimatedVisibility(visible = isAdvancedExpanded) {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            // API 覆盖 下拉单选
                                            Text("API 覆盖", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                            val apiOptions = listOf("— 默认 / none —", "openai-completions", "openai-responses", "anthropic-messages", "google-generative-ai")
                                            Row(
                                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                apiOptions.forEach { opt ->
                                                    val isSel = if (opt == "— 默认 / none —") {
                                                        currentModel.apiOverride.isBlank() || currentModel.apiOverride == "none"
                                                    } else {
                                                        currentModel.apiOverride == opt
                                                    }
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = if (isSel) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                                        border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                                        modifier = Modifier.clickable {
                                                            val v = if (opt == "— 默认 / none —") "" else opt
                                                            updateCurrentModel { it.copy(apiOverride = v) }
                                                        }
                                                    ) {
                                                        Text(
                                                            text = opt,
                                                            fontSize = 10.5.sp,
                                                            color = if (isSel) theme.primary else theme.textSecondary,
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }
                                            }

                                            // Headers 自定义请求头
                                            Text("Headers", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                            Text(
                                                text = "仅添加到该模型的请求中，并覆盖 Provider 的同名 Header。",
                                                fontSize = 10.sp,
                                                color = theme.textMuted
                                            )

                                            currentModel.headers.forEachIndexed { hIdx, hEntry ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    OutlinedTextField(
                                                        value = hEntry.key,
                                                        onValueChange = { k ->
                                                            val updatedH = currentModel.headers.toMutableList()
                                                            updatedH[hIdx] = hEntry.copy(key = k)
                                                            updateCurrentModel { it.copy(headers = updatedH) }
                                                        },
                                                        placeholder = { Text("Header Key", fontSize = 10.sp) },
                                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1f)
                                                    )

                                                    OutlinedTextField(
                                                        value = hEntry.value,
                                                        onValueChange = { v ->
                                                            val updatedH = currentModel.headers.toMutableList()
                                                            updatedH[hIdx] = hEntry.copy(value = v)
                                                            updateCurrentModel { it.copy(headers = updatedH) }
                                                        },
                                                        placeholder = { Text("Value", fontSize = 10.sp) },
                                                        textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                        singleLine = true,
                                                        modifier = Modifier.weight(1.2f)
                                                    )

                                                    IconButton(
                                                        onClick = {
                                                            val updatedH = currentModel.headers.filterIndexed { i, _ -> i != hIdx }
                                                            updateCurrentModel { it.copy(headers = updatedH) }
                                                        },
                                                        modifier = Modifier.size(28.dp)
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = Color(0xFFEF5350), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }

                                            OutlinedButton(
                                                onClick = {
                                                    val updatedH = currentModel.headers + HeaderEntry(key = "", value = "")
                                                    updateCurrentModel { it.copy(headers = updatedH) }
                                                },
                                                shape = RoundedCornerShape(6.dp),
                                                border = BorderStroke(1.dp, theme.outline),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.height(26.dp)
                                            ) {
                                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
                                                Spacer(modifier = Modifier.width(3.dp))
                                                Text("Add header", fontSize = 10.5.sp, color = theme.textPrimary)
                                            }

                                            // 兼容性复选框
                                            Text("兼容性", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.clickable { updateCurrentModel { it.copy(deepSeekThinkingCompat = !it.deepSeekThinkingCompat) } }
                                                ) {
                                                    Checkbox(
                                                        checked = currentModel.deepSeekThinkingCompat,
                                                        onCheckedChange = { v -> updateCurrentModel { it.copy(deepSeekThinkingCompat = v) } },
                                                        colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                                                    )
                                                    Text("DeepSeek 思考兼容", fontSize = 12.sp, color = theme.textPrimary)
                                                }

                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.clickable { updateCurrentModel { it.copy(developerRole = !it.developerRole) } }
                                                ) {
                                                    Checkbox(
                                                        checked = currentModel.developerRole,
                                                        onCheckedChange = { v -> updateCurrentModel { it.copy(developerRole = v) } },
                                                        colors = CheckboxDefaults.colors(checkedColor = theme.primary)
                                                    )
                                                    Text("使用 developer role 传递系统提示词", fontSize = 12.sp, color = theme.textPrimary)
                                                }
                                            }

                                            // 思考等级映射 (7 档 Segmented Buttons)
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("思考等级映射", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                                Text(
                                                    text = "全部清除",
                                                    fontSize = 11.sp,
                                                    color = theme.primary,
                                                    modifier = Modifier.clickable {
                                                        updateCurrentModel { old ->
                                                            old.copy(thinkingLevelMap = ModelConfigItem.defaultThinkingLevelMap())
                                                        }
                                                    }
                                                )
                                            }

                                            val levelColors = mapOf(
                                                "off" to Color(0xFF9E9E9E),
                                                "minimal" to Color(0xFF80CBC4),
                                                "low" to Color(0xFF42A5F5),
                                                "medium" to Color(0xFF7E57C2),
                                                "high" to Color(0xFFEC407A),
                                                "xhigh" to Color(0xFFFFA726),
                                                "max" to Color(0xFFEF5350)
                                            )

                                            currentModel.thinkingLevelMap.forEachIndexed { lvlIdx, item ->
                                                val dotColor = levelColors[item.levelKey] ?: Color.Gray

                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    // 彩色圆点 + 等级名称
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.width(76.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(7.dp)
                                                                .background(dotColor, CircleShape)
                                                        )
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = item.levelKey,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontSize = 11.sp,
                                                            color = theme.textPrimary
                                                        )
                                                    }

                                                    // Segmented 按钮: Default / Disabled / Custom
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        border = BorderStroke(1.dp, theme.outline),
                                                        color = theme.surfaceContainer
                                                    ) {
                                                        Row {
                                                            listOf(
                                                                ThinkingLevelMode.DEFAULT to "Default",
                                                                ThinkingLevelMode.DISABLED to "Disabled",
                                                                ThinkingLevelMode.CUSTOM to "Custom"
                                                            ).forEach { (mode, label) ->
                                                                val isSel = item.mode == mode
                                                                Box(
                                                                    modifier = Modifier
                                                                        .background(if (isSel) theme.primary else Color.Transparent)
                                                                        .clickable {
                                                                            val updatedMap = currentModel.thinkingLevelMap.toMutableList()
                                                                            val newCustomVal = if (mode == ThinkingLevelMode.CUSTOM && item.customValue.isBlank()) {
                                                                                if (item.levelKey == "off") "none" else item.levelKey
                                                                            } else item.customValue
                                                                            updatedMap[lvlIdx] = item.copy(mode = mode, customValue = newCustomVal)
                                                                            updateCurrentModel { it.copy(thinkingLevelMap = updatedMap) }
                                                                        }
                                                                        .padding(horizontal = 7.dp, vertical = 4.dp)
                                                                ) {
                                                                    Text(
                                                                        text = label,
                                                                        fontSize = 9.5.sp,
                                                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                                        color = if (isSel) Color.White else theme.textSecondary
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // 若 Custom 激活，展示与左侧胶囊严格等高 (26dp) 的紧凑微输入框，告别 56dp 突兀大白方框
                                                    if (item.mode == ThinkingLevelMode.CUSTOM) {
                                                        Surface(
                                                            shape = RoundedCornerShape(6.dp),
                                                            color = theme.surfaceContainer,
                                                            border = BorderStroke(1.dp, theme.primary.copy(alpha = 0.5f)),
                                                            modifier = Modifier.weight(1f).height(26.dp)
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.padding(horizontal = 8.dp)
                                                            ) {
                                                                androidx.compose.foundation.text.BasicTextField(
                                                                    value = item.customValue,
                                                                    onValueChange = { cv ->
                                                                        val updatedMap = currentModel.thinkingLevelMap.toMutableList()
                                                                        updatedMap[lvlIdx] = item.copy(customValue = cv)
                                                                        updateCurrentModel { it.copy(thinkingLevelMap = updatedMap) }
                                                                    },
                                                                    singleLine = true,
                                                                    textStyle = androidx.compose.ui.text.TextStyle(
                                                                        fontFamily = FontFamily.Monospace,
                                                                        fontSize = 11.sp,
                                                                        color = theme.textPrimary
                                                                    ),
                                                                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                                                                    modifier = Modifier.fillMaxWidth(),
                                                                    decorationBox = { inner ->
                                                                        if (item.customValue.isEmpty()) {
                                                                            Text("映射值", fontSize = 10.sp, color = theme.textMuted)
                                                                        }
                                                                        inner()
                                                                    }
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
                } else {
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("未配置任何提供商", color = theme.textSecondary, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { showAddProviderDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                                ) {
                                    Text("添加第一个 Provider", color = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 底部吸底常驻保存栏
        Surface(
            color = theme.background.copy(alpha = 0.95f),
            border = BorderStroke(1.dp, theme.outline),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { saveAllConfig() },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存中...", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("保存配置", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // 弹窗 1：新增 Provider
    if (showAddProviderDialog) {
        var newProvName by remember { mutableStateOf("") }
        var newProvBaseUrl by remember { mutableStateOf("") }
        var newProvApiKey by remember { mutableStateOf("") }
        var newProvApi by remember { mutableStateOf("openai-completions") }

        AlertDialog(
            onDismissRequest = { showAddProviderDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("添加 Provider", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newProvName,
                        onValueChange = { newProvName = it.trim() },
                        label = { Text("Provider 标识 *") },
                        placeholder = { Text("如 deepseek / codywon") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProvBaseUrl,
                        onValueChange = { newProvBaseUrl = it.trim() },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProvApiKey,
                        onValueChange = { newProvApiKey = it.trim() },
                        label = { Text("API Key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newProvApi,
                        onValueChange = { newProvApi = it.trim() },
                        label = { Text("API 协议") },
                        placeholder = { Text("openai-completions / anthropic-messages") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProvName.isBlank()) {
                            Toast.makeText(context, "Provider 标识不能为空", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (providers.any { it.name.equals(newProvName, ignoreCase = true) }) {
                            Toast.makeText(context, "已存在同名 Provider", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val created = ProviderConfigItem(
                            name = newProvName,
                            baseUrl = newProvBaseUrl,
                            apiKey = newProvApiKey,
                            api = newProvApi,
                            models = listOf(ModelConfigItem(id = "$newProvName-default", name = "$newProvName-default", reasoning = true))
                        )
                        providers = providers + created
                        selectedProviderName = created.name
                        selectedModelId = created.models.first().id
                        showAddProviderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text("添加", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddProviderDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 弹窗 2：配置当前 Provider
    if (showEditProviderDialog && currentProvider != null) {
        var editBaseUrl by remember(currentProvider) { mutableStateOf(currentProvider.baseUrl) }
        var editApiKey by remember(currentProvider) { mutableStateOf(currentProvider.apiKey) }
        var editApi by remember(currentProvider) { mutableStateOf(currentProvider.api) }
        var showPassword by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showEditProviderDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("配置 Provider: ${currentProvider.name}", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = editBaseUrl,
                        onValueChange = { editBaseUrl = it.trim() },
                        label = { Text("Base URL") },
                        placeholder = { Text("https://api.example.com/v1") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editApiKey,
                        onValueChange = { editApiKey = it.trim() },
                        label = { Text("API Key") },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = theme.textSecondary
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editApi,
                        onValueChange = { editApi = it.trim() },
                        label = { Text("API 协议") },
                        placeholder = { Text("openai-completions / anthropic-messages") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = currentProvider.copy(
                            baseUrl = editBaseUrl,
                            apiKey = editApiKey,
                            api = editApi
                        )
                        providers = providers.map { if (it.name == currentProvider.name) updated else it }
                        showEditProviderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text("完成", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProviderDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 弹窗 3：删除 Provider 确认
    if (showDeleteProviderDialog && currentProvider != null) {
        AlertDialog(
            onDismissRequest = { showDeleteProviderDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("确认删除 Provider？", fontWeight = FontWeight.Bold, color = theme.textPrimary) },
            text = { Text("将删除 ${currentProvider.name} 以及其下属所有模型配置，此操作在点击保存后永久生效。", color = theme.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        val remaining = providers.filterNot { it.name == currentProvider.name }
                        providers = remaining
                        selectedProviderName = remaining.firstOrNull()?.name
                        selectedModelId = remaining.firstOrNull()?.models?.firstOrNull()?.id
                        showDeleteProviderDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) {
                    Text("确认删除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProviderDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 弹窗 4：移除模型确认
    if (showDeleteModelDialog && currentModel != null && currentProvider != null) {
        AlertDialog(
            onDismissRequest = { showDeleteModelDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("确认移除模型？", fontWeight = FontWeight.Bold, color = theme.textPrimary) },
            text = { Text("将从 ${currentProvider.name} 中移除模型 ${currentModel.id}。", color = theme.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedModels = currentProvider.models.filterNot { it.id == currentModel.id }
                        val updatedProv = currentProvider.copy(models = updatedModels)
                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                        selectedModelId = updatedModels.firstOrNull()?.id
                        showDeleteModelDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF5350))
                ) {
                    Text("确认移除", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModelDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    
    // 弹窗 6：从 API 自动发现模型并批量选择添加 (对标 pi-web 的 POST /api/models-config/discover)
    if (showDiscoverDialog && currentProvider != null) {
        val existingModelIds = remember(currentProvider.models) { currentProvider.models.map { it.id }.toSet() }
        val filteredDiscovered = remember(discoveredModels, discoverSearchQuery) {
            if (discoverSearchQuery.isBlank()) discoveredModels
            else {
                val q = discoverSearchQuery.trim().lowercase()
                discoveredModels.filter { it.id.lowercase().contains(q) || it.name.lowercase().contains(q) }
            }
        }
        val unaddedFiltered = remember(filteredDiscovered, existingModelIds) {
            filteredDiscovered.filterNot { existingModelIds.contains(it.id) }
        }

        AlertDialog(
            onDismissRequest = { showDiscoverDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = theme.primary, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("从 API 发现模型", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp)
                    }
                    IconButton(onClick = { showDiscoverDialog = false }, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp), tint = theme.textSecondary)
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "服务商: ${currentProvider.name} (${currentProvider.api})",
                        fontSize = 11.sp,
                        color = theme.textMuted
                    )

                    if (isDiscovering) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(color = theme.primary, modifier = Modifier.size(32.dp))
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("正在向端点查询可用模型 (/v1/models)...", fontSize = 12.sp, color = theme.textSecondary)
                        }
                    } else if (discoverError != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFEF4444).copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("查询失败", fontWeight = FontWeight.Bold, color = Color(0xFF991B1B), fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(discoverError ?: "", color = Color(0xFF991B1B), fontSize = 11.sp)
                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = {
                                        isDiscovering = true
                                        discoverError = null
                                        coroutineScope.launch {
                                            apiClient.discoverProviderModels(
                                                providerName = currentProvider.name,
                                                baseUrl = currentProvider.baseUrl,
                                                apiKey = currentProvider.apiKey,
                                                api = currentProvider.api
                                            ).onSuccess { res ->
                                                discoveredModels = res.models
                                                isDiscovering = false
                                            }.onFailure { err ->
                                                discoverError = err.message
                                                isDiscovering = false
                                            }
                                        }
                                    },
                                    modifier = Modifier.height(26.dp)
                                ) {
                                    Text("重试", fontSize = 11.sp)
                                }
                            }
                        }
                    } else {
                        // 搜索过滤栏与批量全选
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.weight(1f).height(32.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                ) {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.textMuted)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    androidx.compose.foundation.text.BasicTextField(
                                        value = discoverSearchQuery,
                                        onValueChange = { discoverSearchQuery = it },
                                        singleLine = true,
                                        textStyle = androidx.compose.ui.text.TextStyle(color = theme.textPrimary, fontSize = 11.5.sp),
                                        cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                                        modifier = Modifier.weight(1f),
                                        decorationBox = { inner ->
                                            if (discoverSearchQuery.isEmpty()) Text("搜索拉取到的模型...", fontSize = 11.sp, color = theme.textMuted)
                                            inner()
                                        }
                                    )
                                    if (discoverSearchQuery.isNotEmpty()) {
                                        IconButton(onClick = { discoverSearchQuery = "" }, modifier = Modifier.size(18.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(11.dp))
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (unaddedFiltered.isNotEmpty() && selectedDiscoverIds.size == unaddedFiltered.size) "取消全选" else "全选未添加",
                                fontSize = 11.sp,
                                color = theme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    if (selectedDiscoverIds.size == unaddedFiltered.size) {
                                        selectedDiscoverIds = emptySet()
                                    } else {
                                        selectedDiscoverIds = unaddedFiltered.map { it.id }.toSet()
                                    }
                                }
                            )
                        }

                        Text(
                            text = "共发现 ${discoveredModels.size} 个模型 (${unaddedFiltered.size} 个未添加)",
                            fontSize = 10.5.sp,
                            color = theme.textMuted
                        )

                        // 模型多选列表
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(filteredDiscovered, key = { it.id }) { item ->
                                val isAlreadyAdded = existingModelIds.contains(item.id)
                                val isChecked = selectedDiscoverIds.contains(item.id)

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isAlreadyAdded) theme.surfaceContainerLow.copy(alpha = 0.5f) else theme.surfaceContainer,
                                    border = BorderStroke(0.5.dp, if (isChecked) theme.primary else theme.outline),
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = !isAlreadyAdded) {
                                        selectedDiscoverIds = if (isChecked) selectedDiscoverIds - item.id else selectedDiscoverIds + item.id
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                    ) {
                                        if (isAlreadyAdded) {
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = theme.surfaceContainerHigh
                                            ) {
                                                Text("已添加", fontSize = 9.sp, color = theme.textMuted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                            }
                                        } else {
                                            Checkbox(
                                                checked = isChecked,
                                                onCheckedChange = { chk ->
                                                    selectedDiscoverIds = if (chk) selectedDiscoverIds + item.id else selectedDiscoverIds - item.id
                                                },
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = item.id,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.5.sp,
                                            color = if (isAlreadyAdded) theme.textMuted else theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedDiscoverIds.isEmpty()) return@Button
                        val toAdd = selectedDiscoverIds.map { modelId ->
                            val isReasoning = modelId.lowercase().let { it.contains("reason") || it.contains("r1") || it.contains("think") }
                            val hasVision = modelId.lowercase().let { it.contains("vl") || it.contains("vision") || it.contains("4o") }
                            ModelConfigItem(id = modelId, name = modelId, reasoning = isReasoning, hasVision = hasVision)
                        }
                        val updatedProv = currentProvider.copy(models = currentProvider.models + toAdd)
                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                        selectedModelId = toAdd.first().id
                        showDiscoverDialog = false
                        Toast.makeText(context, "已成功批量添加 ${toAdd.size} 个模型，保存配置后即可生效！", Toast.LENGTH_SHORT).show()
                    },
                    enabled = selectedDiscoverIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text("批量添加 (${selectedDiscoverIds.size})", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscoverDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // 弹窗 5：查看与编辑原始 JSON 源码
    if (showRawJsonDialog) {
        AlertDialog(
            onDismissRequest = { showRawJsonDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("models.json 源码", fontWeight = FontWeight.Bold, color = theme.textPrimary, fontSize = 15.sp) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    OutlinedTextField(
                        value = rawJsonBuffer,
                        onValueChange = { rawJsonBuffer = it },
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val parsed = JsonParser.parseString(rawJsonBuffer).asJsonObject
                            rawConfigRoot = parsed
                            providers = ModelConfigConverter.parseProvidersConfig(parsed)
                            showRawJsonDialog = false
                            Toast.makeText(context, "已从 JSON 源码同步更新界面", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "JSON 语法错误: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text("应用", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRawJsonDialog = false }) {
                    Text("关闭", color = theme.textSecondary)
                }
            }
        )
    }
}


/**
 * 紧凑型极客表单输入框组件：
 * 具有独立顶部 Label、高度可控 (默认 36dp)、单行居中、支持单位后缀与等宽字体，
 * 解决移动端 Material 3 OutlinedTextField 默认 56dp 高度撑爆排版的问题。
 */
@Composable
private fun ModelInputField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    singleLine: Boolean = true,
    isMonospace: Boolean = false,
    readOnly: Boolean = false,
    leadingText: String? = null,
    trailingUnit: String? = null,
    height: androidx.compose.ui.unit.Dp = 36.dp
) {
    val theme = LocalAppTheme.current
    Column(modifier = modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = theme.textSecondary,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = theme.surfaceContainer,
            border = BorderStroke(1.dp, theme.outline),
            modifier = Modifier.fillMaxWidth().height(height)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                if (leadingText != null) {
                    Text(
                        text = leadingText,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textMuted,
                        modifier = Modifier.padding(end = 4.dp)
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    keyboardOptions = keyboardOptions,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        color = theme.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
                    ),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { innerTextField ->
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                color = theme.textMuted,
                                fontSize = 11.5.sp
                            )
                        }
                        innerTextField()
                    }
                )
                if (trailingUnit != null) {
                    Text(
                        text = trailingUnit,
                        fontSize = 10.5.sp,
                        color = theme.textMuted,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}
