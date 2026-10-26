package com.pi.app.ui.settings

import android.widget.Toast
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
    var rawJsonBuffer by remember { mutableStateOf("") }

    // 测试与自动填入状态
    var isTestingModel by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf<Boolean?>(null) }
    var isCatalogFilling by remember { mutableStateOf(false) }

    // 折叠高级设置状态
    var isAdvancedExpanded by remember { mutableStateOf(true) }

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

                // 4. 当前选中 Provider 的快捷设置面板
                if (currentProvider != null) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = theme.surfaceContainerLow),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "Provider: ${currentProvider.name}",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.5.sp,
                                            color = theme.textPrimary
                                        )
                                        Text(
                                            text = "接口协议: ${currentProvider.api}  |  Base URL: ${currentProvider.baseUrl.ifBlank { "默认" }}",
                                            fontSize = 10.5.sp,
                                            color = theme.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(
                                            onClick = { showEditProviderDialog = true },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, theme.outline),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("配置", fontSize = 10.5.sp, color = theme.textPrimary)
                                        }

                                        OutlinedButton(
                                            onClick = { showDeleteProviderDialog = true },
                                            shape = RoundedCornerShape(6.dp),
                                            border = BorderStroke(1.dp, Color(0xFFEF5350)),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.height(26.dp)
                                        ) {
                                            Text("删除", fontSize = 10.5.sp, color = Color(0xFFEF5350))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. 模型横向选择条 (带 T 思考徽标，末尾为 + 模型)
                    item {
                        Text(
                            text = "下属模型列表",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            currentProvider.models.forEach { m ->
                                val isSel = m.id == selectedModelId
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) theme.primary.copy(alpha = 0.18f) else theme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, if (isSel) theme.primary else theme.outline),
                                    modifier = Modifier.clickable {
                                        selectedModelId = m.id
                                        testResultText = null
                                        isTestSuccess = null
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = m.id,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) theme.primary else theme.textPrimary
                                        )

                                        if (m.reasoning) {
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Surface(
                                                shape = RoundedCornerShape(3.dp),
                                                color = Color(0xFF1E3A8A).copy(alpha = 0.65f),
                                                border = BorderStroke(0.5.dp, Color(0xFF60A5FA))
                                            ) {
                                                Text(
                                                    text = "T",
                                                    fontSize = 8.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = Color(0xFF93C5FD),
                                                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 0.5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // + 模型 按钮
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
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = theme.primary, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("模型", fontSize = 11.sp, color = theme.primary)
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

                                    // 测试结果显示行
                                    if (testResultText != null) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isTestSuccess == true) Color(0xFF1B5E20).copy(alpha = 0.25f) else Color(0xFFB71C1C).copy(alpha = 0.25f),
                                            border = BorderStroke(1.dp, if (isTestSuccess == true) Color(0xFF4CAF50) else Color(0xFFE57373)),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = testResultText ?: "",
                                                fontSize = 11.sp,
                                                color = if (isTestSuccess == true) Color(0xFF81C784) else Color(0xFFEF9A9A),
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                        }
                                    }

                                    // 基础项：ID * 与 Name
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = currentModel.id,
                                            onValueChange = { newId -> updateCurrentModel { it.copy(id = newId) } },
                                            label = { Text("ID *", fontSize = 11.sp, color = theme.textSecondary) },
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedTextField(
                                            value = currentModel.name,
                                            onValueChange = { newName -> updateCurrentModel { it.copy(name = newName) } },
                                            label = { Text("Name", fontSize = 11.sp, color = theme.textSecondary) },
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                            singleLine = true,
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

                                    // 模型规格：上下文窗口 与 最大输出 tokens (真实读写)
                                    Text("模型规格", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = currentModel.contextWindow.toString(),
                                            onValueChange = { str ->
                                                val v = str.filter { it.isDigit() }.toLongOrNull() ?: 0L
                                                updateCurrentModel { it.copy(contextWindow = v) }
                                            },
                                            label = { Text("上下文窗口 (tokens)", fontSize = 10.5.sp, color = theme.textSecondary) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedTextField(
                                            value = currentModel.maxTokens.toString(),
                                            onValueChange = { str ->
                                                val v = str.filter { it.isDigit() }.toLongOrNull() ?: 0L
                                                updateCurrentModel { it.copy(maxTokens = v) }
                                            },
                                            label = { Text("最大输出 tokens", fontSize = 10.5.sp, color = theme.textSecondary) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    // 每百万 TOKENS 价格 (4 列紧凑输入)
                                    Text("每百万 TOKENS 价格 ($)", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = theme.textSecondary)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = currentModel.costInput.toString(),
                                            onValueChange = { str ->
                                                val v = str.toDoubleOrNull() ?: 0.0
                                                updateCurrentModel { it.copy(costInput = v) }
                                            },
                                            label = { Text("输入", fontSize = 9.5.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedTextField(
                                            value = currentModel.costOutput.toString(),
                                            onValueChange = { str ->
                                                val v = str.toDoubleOrNull() ?: 0.0
                                                updateCurrentModel { it.copy(costOutput = v) }
                                            },
                                            label = { Text("输出", fontSize = 9.5.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedTextField(
                                            value = currentModel.costCacheRead.toString(),
                                            onValueChange = { str ->
                                                val v = str.toDoubleOrNull() ?: 0.0
                                                updateCurrentModel { it.copy(costCacheRead = v) }
                                            },
                                            label = { Text("缓存读", fontSize = 9.5.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        OutlinedTextField(
                                            value = currentModel.costCacheWrite.toString(),
                                            onValueChange = { str ->
                                                val v = str.toDoubleOrNull() ?: 0.0
                                                updateCurrentModel { it.copy(costCacheWrite = v) }
                                            },
                                            label = { Text("缓存写", fontSize = 9.5.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))

                                    // 高级设置 (可折叠卡片)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isAdvancedExpanded = !isAdvancedExpanded }
                                            .padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "高级设置",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = theme.textPrimary
                                            )
                                            Text(
                                                text = "思考等级: ${currentModel.thinkingLevel.ifBlank { "默认" }}",
                                                fontSize = 10.5.sp,
                                                color = theme.textMuted
                                            )
                                        }

                                        Icon(
                                            imageVector = if (isAdvancedExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                            contentDescription = null,
                                            tint = theme.textSecondary
                                        )
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

                                                    // 若 Custom 激活，展示自定义映射值输入框
                                                    if (item.mode == ThinkingLevelMode.CUSTOM) {
                                                        OutlinedTextField(
                                                            value = item.customValue,
                                                            onValueChange = { cv ->
                                                                val updatedMap = currentModel.thinkingLevelMap.toMutableList()
                                                                updatedMap[lvlIdx] = item.copy(customValue = cv)
                                                                updateCurrentModel { it.copy(thinkingLevelMap = updatedMap) }
                                                            },
                                                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                                            singleLine = true,
                                                            modifier = Modifier.weight(1f)
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
