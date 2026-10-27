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
    val dangerColor = MaterialTheme.colorScheme.error
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

    // 配置提供商凭据：写 pi 凭据库 (auth.json)，与 models.json 里的 apiKey 字段是两回事
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var isSavingApiKey by remember { mutableStateOf(false) }

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
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .padding(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 顶栏概览与操作
                item(key = "header_overview") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "提供商与模型管理",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Text(
                                text = "已接入 ${providers.size} 个提供商 · 共 ${providers.sumOf { it.models.size }} 个可用模型",
                                fontSize = 11.5.sp,
                                color = theme.textMuted
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable { loadConfigFromServer() }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "刷新配置",
                                        tint = theme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }

                            Surface(
                                shape = CircleShape,
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable {
                                        rawJsonBuffer = com.google.gson.GsonBuilder().setPrettyPrinting().create()
                                            .toJson(ModelConfigConverter.serializeProvidersConfig(providers, rawConfigRoot))
                                        showRawJsonDialog = true
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Code,
                                        contentDescription = "查看 JSON 源码",
                                        tint = theme.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 模块一：全局默认推理思考档位 (紧凑单行卡片，分段胶囊控制)
                item(key = "thinking_level_card") {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = theme.surfaceContainerLow,
                        border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Psychology,
                                        contentDescription = null,
                                        tint = theme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = "全局默认推理思考档位",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = theme.textPrimary
                                    )
                                }
                                Text(
                                    text = "新会话生效",
                                    fontSize = 10.sp,
                                    color = theme.textMuted
                                )
                            }

                            // 等宽胶囊分段选择器：固定内边距 + 横向滚动，
                            // 不再按字符长度分配权重（旧实现会把 minimal 压成 minima+l 或留白突变）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ThinkingLevel.entries.forEach { level ->
                                    val isSel = currentDefaultThinkingLevel == level
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = if (isSel) theme.primary.copy(alpha = 0.12f) else theme.surfaceContainer,
                                        border = BorderStroke(
                                            if (isSel) 1.dp else 0.6.dp,
                                            if (isSel) theme.primary.copy(alpha = 0.55f) else theme.outline.copy(alpha = 0.45f)
                                        ),
                                        modifier = Modifier.clickable { onSetDefaultThinkingLevel(level) }
                                    ) {
                                        Text(
                                            text = level.value,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSel) theme.primary else theme.textSecondary,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 模块二：提供商选择与当前提供商卡片
                item(key = "provider_section") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // 3.1 横向滑动选择提供商胶囊
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            providers.forEach { prov ->
                                val isSel = prov.name == selectedProviderName
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSel) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainerLow,
                                    border = BorderStroke(
                                        if (isSel) 1.dp else 0.6.dp,
                                        if (isSel) theme.primary else theme.outline.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier.clickable {
                                        selectedProviderName = prov.name
                                        selectedModelId = prov.models.firstOrNull()?.id
                                        testResultText = null
                                        isTestSuccess = null
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = if (isSel) theme.primary else theme.textSecondary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = prov.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSel) theme.primary else theme.textPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        // 数量用等宽小字 + 中间点，不再用圆形填充脏（CircleShape 在中文宽度下会被挤成竖椭圆）
                                        Text(
                                            text = "· ${prov.models.size}",
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSel) theme.primary.copy(alpha = 0.75f) else theme.textMuted,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }

                            // 添加 Provider 轻量小按钮
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.6f)),
                                modifier = Modifier.clickable { showAddProviderDialog = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.5.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = theme.primary, modifier = Modifier.size(12.dp))
                                    Text("添加 Provider", fontSize = 11.sp, color = theme.primary, fontWeight = FontWeight.Medium)
                                }
                            }
                        }

                        // 3.2 当前提供商核心信息卡片
                        if (currentProvider != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.45f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp)
                                ) {
                                    // 顶行：提供商名占满剩余宽度，操作按钮固定为图标按钮，
                                    // 不再与协议胶囊挤在一行（旧实现窄屏下会重叠/溢出）
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.CloudQueue,
                                            contentDescription = null,
                                            tint = theme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = currentProvider.name,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.5.sp,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        MiniIconAction(
                                            icon = Icons.Default.Edit,
                                            tint = theme.textSecondary,
                                            borderColor = theme.outline.copy(alpha = 0.55f),
                                            contentDescription = "编辑 Provider",
                                            onClick = { showEditProviderDialog = true }
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        MiniIconAction(
                                            icon = Icons.Default.DeleteOutline,
                                            tint = dangerColor,
                                            borderColor = dangerColor.copy(alpha = 0.35f),
                                            contentDescription = "删除 Provider",
                                            onClick = { showDeleteProviderDialog = true }
                                        )
                                    }

                                    // 协议与密钥状态统一成一行元信息（单一配色体系，不再混用紫/天蓝/绿）
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = theme.primary.copy(alpha = 0.08f)
                                        ) {
                                            Text(
                                                text = currentProvider.api.ifBlank { "openai-completions" },
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = theme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                            )
                                        }
                                        Text(
                                            text = if (currentProvider.apiKey.isNotBlank()) "Key 已配置" else "未配置 Key",
                                            fontSize = 10.sp,
                                            color = theme.textMuted,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        // 真正的密钥入口：写 pi 凭据库 (auth.json)，而不是 models.json
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = theme.primary.copy(alpha = 0.08f),
                                            border = BorderStroke(0.6.dp, theme.primary.copy(alpha = 0.3f)),
                                            modifier = Modifier.clickable {
                                                apiKeyInput = ""
                                                showApiKeyDialog = true
                                            }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Lock,
                                                    contentDescription = null,
                                                    tint = theme.primary,
                                                    modifier = Modifier.size(11.dp)
                                                )
                                                Text(
                                                    text = "配置 Key",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = theme.primary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }

                                    // Base URL 单行展示框 (点击复制)
                                    Surface(
                                        shape = RoundedCornerShape(7.dp),
                                        color = theme.surfaceContainer,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                if (currentProvider.baseUrl.isNotBlank()) {
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Base URL", currentProvider.baseUrl))
                                                    Toast.makeText(context, "已复制 Base URL", Toast.LENGTH_SHORT).show()
                                                }
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Base URL",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = theme.textMuted,
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                            Text(
                                                text = currentProvider.baseUrl.ifBlank { "(采用接口默认端点)" },
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 11.sp,
                                                color = if (currentProvider.baseUrl.isNotBlank()) theme.textPrimary else theme.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ContentCopy,
                                                contentDescription = "复制",
                                                modifier = Modifier.size(12.dp),
                                                tint = theme.textMuted
                                            )
                                        }
                                    }

                                    // API Key 入口已上提到协议元信息行；这里只保留「主体信息 + 双列操作」

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(7.dp),
                                            color = theme.primary.copy(alpha = 0.12f),
                                            border = BorderStroke(0.6.dp, theme.primary.copy(alpha = 0.4f)),
                                            modifier = Modifier
                                                .weight(1.15f)
                                                .clickable {
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
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Text("从 API 发现模型", fontSize = 11.sp, color = theme.primary, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                            }
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(7.dp),
                                            color = theme.surfaceContainerHigh,
                                            border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable {
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
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                Text("新增模型", fontSize = 11.sp, color = theme.textPrimary, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 模块三：已配置模型列表 (单列全宽清单，彻底消除截断，大方美观)
                if (currentProvider != null && currentProvider.models.isNotEmpty()) {
                    item(key = "models_list_section") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "已配置模型 (${currentProvider.models.size})",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.textPrimary
                                    )

                                    if (currentProvider.models.size > 5) {
                                        Text(
                                            text = if (isModelsExpanded) "收起 ▴" else "展开全部 ${currentProvider.models.size} 个 ▾",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = theme.primary,
                                            modifier = Modifier.clickable { isModelsExpanded = !isModelsExpanded }
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(2.dp))

                                val displayList = if (isModelsExpanded || currentProvider.models.size <= 5) {
                                    currentProvider.models
                                } else {
                                    currentProvider.models.take(5)
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    displayList.forEach { m ->
                                        val isSel = m.id == selectedModelId
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isSel) theme.primary.copy(alpha = 0.09f) else theme.surfaceContainer,
                                            border = BorderStroke(
                                                if (isSel) 1.dp else 0.5.dp,
                                                if (isSel) theme.primary else theme.outline.copy(alpha = 0.35f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedModelId = m.id
                                                    testResultText = null
                                                    isTestSuccess = null
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    Text(
                                                        text = m.id,
                                                        fontSize = 12.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSel) theme.primary else theme.textPrimary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    val desc = buildString {
                                                        if (m.name.isNotBlank() && m.name != m.id) {
                                                            append(m.name)
                                                            append(" · ")
                                                        }
                                                        append("${m.contextWindow / 1000}k 上下文")
                                                        if (m.maxTokens > 0) {
                                                            append(" · ${m.maxTokens / 1000}k 输出")
                                                        }
                                                    }
                                                    Text(
                                                        text = desc,
                                                        fontSize = 10.5.sp,
                                                        color = theme.textMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                                    if (m.reasoning) {
                                                        CapabilityTag(text = "思考", emphasized = true)
                                                    }
                                                    if (m.hasVision) {
                                                        CapabilityTag(text = "视觉", emphasized = false)
                                                    }
                                                    if (isSel) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "当前选中编辑",
                                                            tint = theme.primary,
                                                            modifier = Modifier.size(15.dp)
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

                // 5. 模块四：模型参数调校卡片 (针对当前选中的模型)
                if (currentModel != null && currentProvider != null) {
                    item(key = "model_detail_editor") {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.45f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // 顶行：标题 + 图标操作（测试/移除），模型 ID 单独一行展示
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = theme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "模型参数调校",
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.textPrimary,
                                        modifier = Modifier.weight(1f)
                                    )
                                    MiniIconAction(
                                        icon = if (isTestingModel) Icons.Default.HourglassEmpty else Icons.Default.PlayArrow,
                                        tint = theme.primary,
                                        borderColor = theme.primary.copy(alpha = 0.35f),
                                        enabled = !isTestingModel,
                                        contentDescription = "测试连通性",
                                        onClick = {
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
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    MiniIconAction(
                                        icon = Icons.Default.DeleteOutline,
                                        tint = dangerColor,
                                        borderColor = dangerColor.copy(alpha = 0.35f),
                                        contentDescription = "移除模型",
                                        onClick = { showDeleteModelDialog = true }
                                    )
                                }

                                // 当前编辑对象：单独一行、等宽字体、单行省略，不再挤进标题行
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Memory,
                                        contentDescription = null,
                                        tint = theme.textMuted,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = currentModel.id,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = theme.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = if (isTestingModel) "测试中..." else "test",
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = theme.textMuted
                                    )
                                }

                                if (testResultText != null) {
                                    val isSuccess = isTestSuccess == true
                                    val accent = if (isSuccess) theme.primary else dangerColor
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = accent.copy(alpha = 0.08f),
                                        border = BorderStroke(0.6.dp, accent.copy(alpha = 0.3f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (isSuccess) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = accent
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = testResultText ?: "",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = accent
                                            )
                                        }
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ModelInputField(
                                        value = currentModel.id,
                                        onValueChange = { newId -> updateCurrentModel { it.copy(id = newId) } },
                                        label = "模型标识 (ID) *",
                                        placeholder = "如 deepseek-flash",
                                        isMonospace = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    ModelInputField(
                                        value = currentModel.name,
                                        onValueChange = { newName -> updateCurrentModel { it.copy(name = newName) } },
                                        label = "显示别名 (Name)",
                                        placeholder = "如 DeepSeek V4 Flash",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = theme.surfaceContainer,
                                    border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.5f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (isCatalogFilling) return@clickable
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
                                                        Toast.makeText(context, "models.dev 中未找到精准匹配，保留现有值", Toast.LENGTH_SHORT).show()
                                                    }
                                                }.onFailure { err ->
                                                    Toast.makeText(context, "获取推荐失败: ${err.message}", Toast.LENGTH_SHORT).show()
                                                }
                                                isCatalogFilling = false
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            if (isCatalogFilling) {
                                                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = theme.primary)
                                                Text("正在从 models.dev 智能匹配...", fontSize = 11.sp, color = theme.primary, maxLines = 1, softWrap = false)
                                            } else {
                                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = theme.primary, modifier = Modifier.size(13.dp))
                                                Text(
                                                    text = "智能补全规格 (models.dev)",
                                                    fontSize = 11.sp,
                                                    color = theme.primary,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Text("一键填入", fontSize = 10.sp, color = theme.textMuted, maxLines = 1, softWrap = false)
                                    }
                                }

                                // 双列等宽功能开关（旧实现两个 Checkbox 并排 + 长中英双标签，窄屏必折行）
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ModelFlagChip(
                                        label = "深度思考",
                                        checked = currentModel.reasoning,
                                        modifier = Modifier.weight(1f),
                                        onToggle = { updateCurrentModel { it.copy(reasoning = !it.reasoning) } }
                                    )
                                    ModelFlagChip(
                                        label = "多模态视觉",
                                        checked = currentModel.hasVision,
                                        modifier = Modifier.weight(1f),
                                        onToggle = { updateCurrentModel { it.copy(hasVision = !it.hasVision) } }
                                    )
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { isAdvancedExpanded = !isAdvancedExpanded }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (isAdvancedExpanded) "收起价格与高级参数 ▴" else "展开价格与高级参数 (可选) ▾",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = theme.primary
                                        )
                                    }
                                }

                                AnimatedVisibility(visible = isAdvancedExpanded) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("每百万 Tokens 价格 ($)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = theme.textMuted)
                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ModelInputField(
                                                value = currentModel.costInput.toString(),
                                                onValueChange = { str -> updateCurrentModel { it.copy(costInput = str.toDoubleOrNull() ?: 0.0) } },
                                                label = "输入价格",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            ModelInputField(
                                                value = currentModel.costOutput.toString(),
                                                onValueChange = { str -> updateCurrentModel { it.copy(costOutput = str.toDoubleOrNull() ?: 0.0) } },
                                                label = "输出价格",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            ModelInputField(
                                                value = currentModel.costCacheRead.toString(),
                                                onValueChange = { str -> updateCurrentModel { it.copy(costCacheRead = str.toDoubleOrNull() ?: 0.0) } },
                                                label = "缓存读取",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            ModelInputField(
                                                value = currentModel.costCacheWrite.toString(),
                                                onValueChange = { str -> updateCurrentModel { it.copy(costCacheWrite = str.toDoubleOrNull() ?: 0.0) } },
                                                label = "缓存写入",
                                                leadingText = "$",
                                                trailingUnit = "/M",
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                                isMonospace = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                        }

                                        ModelInputField(
                                            value = currentModel.apiOverride,
                                            onValueChange = { str -> updateCurrentModel { it.copy(apiOverride = str.trim()) } },
                                            label = "自定义 API 协议覆盖 (选填)",
                                            placeholder = "留空则继承 Provider API",
                                            isMonospace = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 6. 底部常驻悬浮保存栏 (优雅圆角大药丸，浮动半透明阴影)
            Surface(
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                color = theme.background.copy(alpha = 0.94f),
                border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.3f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Button(
                        onClick = { saveAllConfig() },
                        enabled = !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在保存配置到服务端...", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("保存全部配置并生效", fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
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
                        label = { Text("API Key（可选，写入 models.json）") },
                        placeholder = { Text("留空则不修改") },
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
                            // 留空时保留原有值，避免一次「只改 Base URL」的编辑把已有 Key 抹掉
                            apiKey = if (editApiKey.isNotBlank()) editApiKey else currentProvider.apiKey,
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

    // 弹窗 2.5：配置 Provider 的 API Key（写 pi 凭据库 auth.json，即时生效）
    if (showApiKeyDialog && currentProvider != null) {
        AlertDialog(
            onDismissRequest = { if (!isSavingApiKey) showApiKeyDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    "配置 ${currentProvider.name} 的 API Key",
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "写入 pi 凭据库 (auth.json)，对所有会话与子代理立即生效（与 models.json 里的 apiKey 字段无关）。",
                        fontSize = 11.sp,
                        color = theme.textMuted,
                        lineHeight = 16.sp
                    )
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it.trim() },
                        label = { Text("API Key 或 Bearer Token") },
                        placeholder = { Text("如 sk-…") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val key = apiKeyInput.trim()
                        if (key.isEmpty() || isSavingApiKey) return@Button
                        isSavingApiKey = true
                        coroutineScope.launch {
                            apiClient.setProviderApiKey(currentProvider.name, key)
                                .onSuccess {
                                    Toast.makeText(context, "已保存 ${currentProvider.name} 的 API Key", Toast.LENGTH_SHORT).show()
                                    apiKeyInput = ""
                                    showApiKeyDialog = false
                                    loadConfigFromServer()
                                }
                                .onFailure { err ->
                                    Toast.makeText(
                                        context,
                                        "保存失败: ${err.message ?: "未知错误"}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            isSavingApiKey = false
                        }
                    },
                    enabled = apiKeyInput.isNotBlank() && !isSavingApiKey,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary)
                ) {
                    Text(if (isSavingApiKey) "保存中..." else "保存", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isSavingApiKey) showApiKeyDialog = false }) {
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

/**
 * 统一的「图标操作」按钮：固定 30dp 圆形热区，仅图标不含文字，
 * 避免窄屏下文字按钮被挤压成竖排（旧实现里的「移/除」竖排即由此产生）。
 */
@Composable
private fun MiniIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    borderColor: Color,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = CircleShape,
        color = if (enabled) theme.surfaceContainer else theme.surfaceContainerHigh.copy(alpha = 0.5f),
        border = BorderStroke(0.7.dp, borderColor.copy(alpha = if (enabled) 1f else 0.4f)),
        modifier = Modifier
            .size(30.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/**
 * 能力标签（思考 / 视觉）：统一到主题 primary + 中性色两档，
 * 不再出现紫、天蓝、绿等多套互不相干的强调色。
 */
@Composable
private fun CapabilityTag(text: String, emphasized: Boolean) {
    val theme = LocalAppTheme.current
    val bg = if (emphasized) theme.primary.copy(alpha = 0.10f) else theme.surfaceContainerHigh
    val fg = if (emphasized) theme.primary else theme.textSecondary
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = bg) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 双列等宽能力开关胶囊：整体可点击，选中态用主题色描边 + 对勾，
 * 保证「深度思考 / 多模态视觉」两个标签在窄屏上永不折行。
 */
@Composable
private fun ModelFlagChip(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = if (checked) theme.primary.copy(alpha = 0.08f) else theme.surfaceContainer,
        border = BorderStroke(
            0.7.dp,
            if (checked) theme.primary.copy(alpha = 0.45f) else theme.outline.copy(alpha = 0.5f)
        ),
        modifier = modifier.clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) theme.primary else theme.textMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (checked) theme.textPrimary else theme.textSecondary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
