package com.pi.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.*
import com.pi.app.network.DiscoveredModelItem
import com.pi.app.network.PiApiClient
import com.pi.app.ui.theme.LocalAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 单个模型的连通性实测状态缓存（列表行内「⚡ 测速」与抽屉内「⚡ 实测连通」共用同一份真源）。
 */
private data class ModelPingResult(
    val isTesting: Boolean = false,
    val ok: Boolean? = null,
    val latencyMs: Long? = null,
    val message: String? = null
)

/**
 * 1:1 对标 pi-web 原版的高级模型与提供商管理工作台：
 * - 顶栏概览 + 全局默认思考深度单行 7 档紧凑卡
 * - 一体化提供商中枢卡片（Provider 切换胶囊栏 + 凭据/协议/Base URL 详情合二为一）
 * - 清爽高密度模型清单 + 搜索/API 发现/新增工具行
 * - 点击任意模型行呼出 ModalBottomSheet 参数精调抽屉（告别页面底部冗长常驻大表单）
 * - 底部智能脏数据浮动栏（无未保存修改时完全隐藏，有修改时浮出「放弃修改 / 保存并生效」）
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

    // 先用内存快照秒开（不碰磁盘），磁盘缓存由 LaunchedEffect 在 IO 线程补齐
    val initialCached = remember { apiClient.peekCachedModelsManagementData() }

    var isLoading by remember { mutableStateOf(initialCached == null) }
    var isSyncing by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf<List<ProviderConfigItem>>(initialCached?.providers ?: emptyList()) }
    var rawConfigRoot by remember { mutableStateOf<JsonObject?>(initialCached?.rootConfig) }
    // 内置 Provider 的模型来自网关目录；离线冷启动靠缓存里这份目录补齐，绝不用 null 重建列表
    var catalogModels by remember { mutableStateOf<List<ModelInfo>?>(initialCached?.catalogModels) }

    // 草稿保护：providers 与服务端基准不一致 = 有未保存修改
    var serverProviders by remember { mutableStateOf(initialCached?.providers ?: emptyList()) }
    // 同步序号与保存代号：丢弃过期/已被保存超越的同步结果
    var syncSeq by remember { mutableStateOf(0) }
    var saveEpoch by remember { mutableStateOf(0) }
    var syncNotice by remember { mutableStateOf<String?>(null) }

    var selectedProviderName by remember {
        mutableStateOf<String?>(initialCached?.providers?.firstOrNull()?.name)
    }
    var selectedModelId by remember {
        mutableStateOf<String?>(initialCached?.providers?.firstOrNull()?.models?.firstOrNull()?.id)
    }
    var selectedModelIndex by remember { mutableStateOf(0) }

    // 模型参数精调抽屉 (ModalBottomSheet) 开关状态
    var showModelEditorSheet by remember { mutableStateOf(false) }

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
    var discoveredModels by remember { mutableStateOf<List<DiscoveredModelItem>>(emptyList()) }
    var discoverSearchQuery by remember { mutableStateOf("") }
    var selectedDiscoverIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var modelQuery by remember { mutableStateOf("") }
    var rawJsonBuffer by remember { mutableStateOf("") }

    // 模型连通性测速状态缓存（key = "${provider.lowercase()}/${modelId}"）与智能补全状态
    var modelPingResults by remember { mutableStateOf<Map<String, ModelPingResult>>(emptyMap()) }
    var isCatalogFilling by remember { mutableStateOf(false) }

    // 抽屉内折叠高级参数状态
    var isAdvancedExpanded by remember { mutableStateOf(false) }

    // 配置提供商凭据：写 pi 凭据库 (auth.json)，与 models.json 里的 apiKey 字段是两回事
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var isSavingApiKey by remember { mutableStateOf(false) }
    // 检验阶段：先拿候选 Key 真发一轮请求，通了才写入凭据库（避免把 PC 上所有会话打成 401）
    var apiKeyVerifyPhase by remember { mutableStateOf<String?>(null) }
    var apiKeyInputError by remember { mutableStateOf<String?>(null) }
    // 无法联网实测时（内置 Provider 无 Base URL / 旧网关无测试接口）允许用户明示跳过实测
    var allowUnverifiedSave by remember { mutableStateOf(false) }

    // pi 凭据库真实状态（GET /api/auth/providers）：models.json 里通常根本没 apiKey 字段，
    // 只看 models.json 会永远显示「未配置 Key」
    var authStates by remember { mutableStateOf<Map<String, ProviderAuthState>>(initialCached?.authStates ?: emptyMap()) }

    /** 同步完成后保持当前查看的 Provider/模型不跳变。 */
    fun restoreSelection(list: List<ProviderConfigItem>) {
        if (list.isEmpty()) {
            selectedProviderName = null
            selectedModelId = null
            selectedModelIndex = 0
            return
        }
        val prov = list.firstOrNull { it.name.equals(selectedProviderName, ignoreCase = true) } ?: list.first()
        selectedProviderName = prov.name
        if (prov.models.isEmpty()) {
            selectedModelId = null
            selectedModelIndex = 0
        } else {
            val idx = prov.models.indexOfFirst { it.id == selectedModelId }.takeIf { it >= 0 } ?: 0
            selectedModelIndex = idx
            selectedModelId = prov.models[idx].id
        }
    }

    // 辅助函数：只刷新凭据状态（清除/写入 Key 之后）。
    // 有未保存修改时不重建列表，避免把草稿覆盖掉（并保留已知的模型目录）
    fun loadAuthStates() {
        coroutineScope.launch {
            apiClient.getProviderAuthStates().onSuccess { states ->
                authStates = states
                if (providers == serverProviders) {
                    rawConfigRoot?.let { root ->
                        val rebuilt = ModelConfigConverter.combineProviders(root, states, catalogModels)
                        providers = rebuilt
                        serverProviders = rebuilt
                        restoreSelection(rebuilt)
                    }
                }
            }
        }
    }

    fun loadConfigFromServer(silent: Boolean = false, isManual: Boolean = false) {
        if (!silent && providers.isEmpty()) {
            isLoading = true
        } else {
            isSyncing = true
        }
        syncNotice = null
        val seq = ++syncSeq
        val epochAtStart = saveEpoch
        coroutineScope.launch {
            try {
                apiClient.syncModelsManagementData()
                    .onSuccess { synced ->
                        // 有更新的同步已接管，或期间保存过：这批数据已过期，直接丢弃，绝不回写
                        if (seq != syncSeq) return@onSuccess
                        if (epochAtStart != saveEpoch) return@onSuccess

                        val data = synced.data
                        // 先判定草稿：必须拿「旧基准」比，否则会把服务端变化误当成用户编辑
                        val dirtyNow = providers != serverProviders
                        rawConfigRoot = data.rootConfig
                        authStates = data.authStates
                        catalogModels = data.catalogModels
                        serverProviders = data.providers
                        syncNotice = if (synced.degraded) "部分数据未同步，已沿用上次结果" else null

                        // 只有本地没有未保存修改时才替换列表，否则会抹掉用户刚改的内容
                        if (!dirtyNow) {
                            providers = data.providers
                            restoreSelection(data.providers)
                        } else if (synced.degraded) {
                            syncNotice = "部分数据未同步，已保留本地修改"
                        }

                        if (isManual) {
                            Toast.makeText(
                                context,
                                if (synced.degraded) "已同步部分配置"
                                else "已同步最新模型配置（${data.providers.size} 个提供商）",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .onFailure { err ->
                        if (seq != syncSeq) return@onFailure
                        if (providers.isEmpty()) {
                            Toast.makeText(context, "读取模型配置失败：${err.message}", Toast.LENGTH_LONG).show()
                        } else if (isManual) {
                            Toast.makeText(context, "同步失败：${err.message}，已显示本地缓存", Toast.LENGTH_SHORT).show()
                        }
                    }
            } finally {
                if (seq == syncSeq) {
                    isLoading = false
                    isSyncing = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        // 冷启动时内存里还没有快照：先读磁盘缓存（阻塞操作放 IO），再静默对账
        if (initialCached == null) {
            val disk = withContext(Dispatchers.IO) { apiClient.loadCachedModelsManagementData() }
            if (disk != null) {
                providers = disk.providers
                serverProviders = disk.providers
                rawConfigRoot = disk.rootConfig
                authStates = disk.authStates
                catalogModels = disk.catalogModels
                restoreSelection(disk.providers)
                isLoading = false
            }
        }
        loadConfigFromServer(silent = providers.isNotEmpty(), isManual = false)
    }

    /** 有未保存修改：后台同步不会覆盖列表，底部自动浮出智能脏数据保存栏 */
    val draftDirty = providers != serverProviders

    // 获取当前选中的 Provider 和 Model（抽屉打开期间优先按精确索引定位，防止改 ID 途经同名模型发生跳变与污染）
    val currentProvider = remember(providers, selectedProviderName) {
        providers.firstOrNull { it.name == selectedProviderName }
    }
    val currentModelIndex = remember(currentProvider, selectedModelIndex, selectedModelId, showModelEditorSheet) {
        val models = currentProvider?.models ?: return@remember -1
        if (selectedModelIndex in models.indices &&
            (showModelEditorSheet || models[selectedModelIndex].id == selectedModelId)
        ) {
            selectedModelIndex
        } else {
            models.indexOfFirst { it.id == selectedModelId }.takeIf { it >= 0 }
                ?: if (models.isNotEmpty()) 0 else -1
        }
    }
    val currentModel = remember(currentProvider, currentModelIndex) {
        currentProvider?.models?.getOrNull(currentModelIndex)
    }

    // 凭据真实状态：优先 pi 凭据库（GET /api/auth/providers），models.json 里的 apiKey 仅作兜底
    val currentAuthState = remember(authStates, currentProvider) {
        currentProvider?.let { authStates[it.name.lowercase()] }
    }
    val isKeyConfigured = currentAuthState?.configured == true || currentProvider?.apiKey?.isNotBlank() == true
    // 详细来源说明（弹窗与状态条使用）
    val keySourceDetail = when {
        currentAuthState?.configured == true -> {
            val src = currentAuthState.sourceLabel
            if (src.isNotBlank()) src else "auth.json"
        }
        currentProvider?.apiKey?.isNotBlank() == true -> "models.json"
        else -> ""
    }
    val keyStatusLabel = if (isKeyConfigured) {
        "已配凭据 (${keySourceDetail.ifBlank { "auth.json" }})"
    } else {
        "未配置 Key"
    }
    // 只有存在已保存（stored）凭据时才能从手机端清除；环境变量等来源不归 App 管
    val canClearStoredKey = currentAuthState?.configured == true &&
        currentAuthState.source?.lowercase() == "stored"

    // 保存全部配置方法
    fun saveAllConfig() {
        if (isSaving) return
        val validationError = ModelConfigUiFormatters.validateProvidersConfig(providers)
        if (validationError != null) {
            Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
            return
        }
        isSaving = true
        coroutineScope.launch {
            try {
                val serialized = ModelConfigConverter.serializeProvidersConfig(providers, rawConfigRoot)
                val saveResult = apiClient.saveModelsConfigObject(serialized)
                saveResult.onSuccess {
                    // 保存成功：本地列表即服务端当前基准（保留内置 Provider 标记，不重新解析），草稿保护随之解除
                    saveEpoch++
                    serverProviders = providers
                    rawConfigRoot = serialized
                    syncNotice = null
                    Toast.makeText(context, "模型配置已保存并生效", Toast.LENGTH_SHORT).show()
                    onModelsConfigChanged()
                }.onFailure { err ->
                    Toast.makeText(context, "保存失败：${err.message}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "配置序列化失败：${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isSaving = false
            }
        }
    }

    // 更新当前正在编辑的模型的局部字段（按精确下标更新，杜绝修改 ID 途经已有模型 ID 时污染同名项）
    fun updateCurrentModel(transform: (ModelConfigItem) -> ModelConfigItem) {
        val prov = currentProvider ?: return
        val idx = currentModelIndex
        val m = prov.models.getOrNull(idx) ?: return
        val updatedModel = transform(m)
        val updatedModels = ModelConfigUiFormatters.updateModelAt(prov.models, idx) { updatedModel }
        val updatedProv = prov.copy(models = updatedModels)
        providers = providers.map { if (it.name == prov.name) updatedProv else it }
        selectedModelIndex = idx
        // 若 ID 被修改，同步更新选中 ID 并迁移测速记录
        if (updatedModel.id != m.id) {
            val oldKey = "${prov.name.lowercase()}/${m.id}"
            val newKey = "${prov.name.lowercase()}/${updatedModel.id}"
            modelPingResults[oldKey]?.let { existingPing ->
                val othersKeepOldId = updatedModels.withIndex().any { (i, item) -> i != idx && item.id == m.id }
                val baseMap = if (othersKeepOldId) modelPingResults else modelPingResults - oldKey
                modelPingResults = if (updatedModel.id.isNotBlank()) baseMap + (newKey to existingPing) else baseMap
            }
            selectedModelId = updatedModel.id
        }
    }

    // 发起单模型真实连通性测试（主列表行内测速按钮与抽屉内实测按钮共用）
    fun triggerModelPing(prov: ProviderConfigItem, model: ModelConfigItem, showToastOnFinish: Boolean = false) {
        if (model.id.isBlank()) {
            Toast.makeText(context, "请先填写模型标识 (ID)", Toast.LENGTH_SHORT).show()
            return
        }
        val pingKey = "${prov.name.lowercase()}/${model.id}"
        if (modelPingResults[pingKey]?.isTesting == true) return
        modelPingResults = modelPingResults + (pingKey to ModelPingResult(isTesting = true))
        coroutineScope.launch {
            val provObj = JsonObject().apply {
                if (prov.baseUrl.isNotBlank()) addProperty("baseUrl", prov.baseUrl)
                if (prov.apiKey.isNotBlank()) addProperty("apiKey", prov.apiKey)
                if (prov.api.isNotBlank()) addProperty("api", prov.api)
            }
            val modelObj = JsonObject().apply {
                addProperty("id", model.id)
                addProperty("name", model.name)
                addProperty("contextWindow", model.contextWindow)
                addProperty("maxTokens", model.maxTokens)
                if (model.apiOverride.isNotBlank()) addProperty("api", model.apiOverride)
            }
            val testRes = apiClient.testModelConfig(prov.name, provObj, modelObj)
            testRes.onSuccess { r ->
                if (r.ok) {
                    val msg = "连通性测试通过：模型响应正常"
                    modelPingResults = modelPingResults + (
                        pingKey to ModelPingResult(
                            isTesting = false,
                            ok = true,
                            latencyMs = r.latencyMs,
                            message = msg
                        )
                    )
                    if (showToastOnFinish) {
                        val latLabel = r.latencyMs?.let { "${it}ms" } ?: "正常"
                        Toast.makeText(context, "✓ ${model.id} 响应正常 ($latLabel)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errText = r.error ?: "未知错误"
                    val msg = "失败: $errText"
                    modelPingResults = modelPingResults + (
                        pingKey to ModelPingResult(
                            isTesting = false,
                            ok = false,
                            latencyMs = r.latencyMs,
                            message = msg
                        )
                    )
                    if (showToastOnFinish) {
                        Toast.makeText(context, "✗ ${model.id} $msg", Toast.LENGTH_SHORT).show()
                    }
                }
            }.onFailure { err ->
                val msg = "网络错误: ${err.message}"
                modelPingResults = modelPingResults + (
                    pingKey to ModelPingResult(
                        isTesting = false,
                        ok = false,
                        latencyMs = null,
                        message = msg
                    )
                )
                if (showToastOnFinish) {
                    Toast.makeText(context, "✗ ${model.id} $msg", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 触发从 API 发现模型弹窗
    fun openDiscoverModelsDialog(prov: ProviderConfigItem) {
        showDiscoverDialog = true
        isDiscovering = true
        discoverError = null
        discoverSearchQuery = ""
        selectedDiscoverIds = emptySet()
        coroutineScope.launch {
            apiClient.discoverProviderModels(
                providerName = prov.name,
                baseUrl = prov.baseUrl,
                apiKey = prov.apiKey,
                api = prov.api
            ).onSuccess { res ->
                discoveredModels = res.models
                isDiscovering = false
            }.onFailure { err ->
                discoverError = err.message
                isDiscovering = false
            }
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentPadding = PaddingValues(bottom = if (draftDirty) 84.dp else 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 1. 顶栏概览与工具图标
                item(key = "header_overview") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "提供商与模型工作台",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "已接入 ${providers.size} 个提供商 · 共 ${providers.sumOf { it.models.size }} 个可用模型",
                                    fontSize = 11.5.sp,
                                    color = theme.textMuted
                                )
                                val statusText = when {
                                    isSyncing && providers.isNotEmpty() -> "正在同步…"
                                    syncNotice != null -> syncNotice
                                    else -> null
                                }
                                if (statusText != null) {
                                    Text(
                                        text = "· $statusText",
                                        fontSize = 11.sp,
                                        color = if (isSyncing) theme.primary else theme.textMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.8.dp, theme.outline),
                                modifier = Modifier
                                    .size(32.dp)
                                    .clickable(enabled = !isSyncing) {
                                        loadConfigFromServer(silent = providers.isNotEmpty(), isManual = true)
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isSyncing) {
                                        CircularProgressIndicator(
                                            color = theme.primary,
                                            strokeWidth = 1.6.dp,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = "刷新配置",
                                            tint = theme.textSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.8.dp, theme.outline),
                                modifier = Modifier
                                    .height(32.dp)
                                    .clickable {
                                        rawJsonBuffer = GsonBuilder().setPrettyPrinting().create()
                                            .toJson(ModelConfigConverter.serializeProvidersConfig(providers, rawConfigRoot))
                                        showRawJsonDialog = true
                                    }
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(horizontal = 9.dp)
                                ) {
                                    Text(
                                        text = "{ } JSON",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Medium,
                                        color = theme.textSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. 全局默认思考深度紧凑卡片（顶栏右侧展示生效范围，下方 7 档单行完整展示）
                item(key = "thinking_level_card") {
                    SettingsGroup {
                        Column(
                            modifier = Modifier.padding(10.dp),
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
                                        text = "全局默认思考深度",
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

                            // 7 档选择器单行完整展示
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(3.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    ThinkingLevel.entries.forEach { level ->
                                        val isSel = currentDefaultThinkingLevel == level
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = if (isSel) theme.surfaceContainerLow else Color.Transparent,
                                            border = if (isSel) {
                                                BorderStroke(0.8.dp, theme.primary.copy(alpha = 0.55f))
                                            } else {
                                                null
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { onSetDefaultThinkingLevel(level) }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 5.5.dp)
                                            ) {
                                                Text(
                                                    text = level.value,
                                                    fontSize = 10.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSel) theme.primary else theme.textSecondary,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 一体化提供商中枢卡片（顶部横向滚动 Provider 切换栏 + 中部当前选中 Provider 详情合二为一）
                item(key = "provider_hub_card") {
                    SettingsGroup {
                        // 3.1 顶部：横向滚动的 Provider 切换胶囊栏
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(theme.surfaceContainer)
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            providers.forEach { prov ->
                                val isSel = prov.name == selectedProviderName
                                val provAuth = authStates[prov.name.lowercase()]
                                val provKeyReady = provAuth?.configured == true || prov.apiKey.isNotBlank()

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSel) theme.surfaceContainerLow else Color.Transparent,
                                    border = if (isSel) {
                                        BorderStroke(1.dp, theme.primary.copy(alpha = 0.65f))
                                    } else {
                                        BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.45f))
                                    },
                                    modifier = Modifier.clickable {
                                        selectedProviderName = prov.name
                                        selectedModelIndex = 0
                                        selectedModelId = prov.models.firstOrNull()?.id
                                        modelQuery = ""
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.5.dp)
                                    ) {
                                        // 凭据就绪绿/黄状态圆点
                                        Spacer(
                                            modifier = Modifier
                                                .size(6.5.dp)
                                                .background(
                                                    color = if (provKeyReady) theme.success else theme.warning,
                                                    shape = CircleShape
                                                )
                                        )
                                        Text(
                                            text = prov.name,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSel) theme.primary else theme.textPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                        // 模型数量徽章
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSel) theme.primary.copy(alpha = 0.12f) else theme.surfaceContainerHigh
                                        ) {
                                            Text(
                                                text = prov.models.size.toString(),
                                                fontSize = 9.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Medium,
                                                color = if (isSel) theme.primary else theme.textSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 末尾 + 添加提供商 按钮
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.primary.copy(alpha = 0.08f),
                                border = BorderStroke(0.6.dp, theme.primary.copy(alpha = 0.35f)),
                                modifier = Modifier.clickable { showAddProviderDialog = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = theme.primary,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "添加提供商",
                                        fontSize = 11.sp,
                                        color = theme.primary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = theme.outline, thickness = 1.dp)

                        // 3.2 中部：当前选中 Provider 的详情区
                        if (currentProvider != null) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 第一行：协议徽章 + 凭据状态 + 清除/配置 Key + 编辑/删除 Provider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(5.dp),
                                        color = theme.surfaceContainer,
                                        border = BorderStroke(0.6.dp, theme.outline)
                                    ) {
                                        Text(
                                            text = currentProvider.api.ifBlank { "openai-completions" },
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }

                                    if (currentProvider.isBuiltIn) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = theme.primary.copy(alpha = 0.12f)
                                        ) {
                                            Text(
                                                text = "官方内置",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.primary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                            )
                                        }
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Spacer(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .background(
                                                    color = if (isKeyConfigured) theme.success else theme.warning,
                                                    shape = CircleShape
                                                )
                                        )
                                        Text(
                                            text = keyStatusLabel,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isKeyConfigured) theme.success else theme.warning,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    // 清除已保存的 Key（仅 stored 来源时可见）
                                    if (canClearStoredKey) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = theme.surfaceContainer,
                                            border = BorderStroke(0.6.dp, theme.outline),
                                            modifier = Modifier.clickable {
                                                coroutineScope.launch {
                                                    apiClient.deleteProviderApiKey(currentProvider.name)
                                                        .onSuccess {
                                                            Toast.makeText(
                                                                context,
                                                                "已清除 ${currentProvider.name} 保存的 Key",
                                                                Toast.LENGTH_SHORT
                                                            ).show()
                                                            loadAuthStates()
                                                        }
                                                        .onFailure { err ->
                                                            Toast.makeText(
                                                                context,
                                                                "清除失败：${err.message}",
                                                                Toast.LENGTH_LONG
                                                            ).show()
                                                        }
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = "清除",
                                                fontSize = 10.sp,
                                                color = theme.textSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                            )
                                        }
                                    }

                                    // 🔑 配置 Key 按钮（写入 auth.json，带先验后存保护）
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = theme.surfaceContainer,
                                        border = BorderStroke(0.6.dp, theme.outline),
                                        modifier = Modifier.clickable {
                                            apiKeyInput = ""
                                            apiKeyInputError = null
                                            apiKeyVerifyPhase = null
                                            allowUnverifiedSave = false
                                            showApiKeyDialog = true
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Key,
                                                contentDescription = null,
                                                tint = theme.primary,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Text(
                                                text = if (isKeyConfigured) "更换 Key" else "配置 Key",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.textPrimary,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // 非内置 Provider 支持编辑端点与协议
                                    if (!currentProvider.isBuiltIn) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = theme.surfaceContainer,
                                            border = BorderStroke(0.6.dp, theme.outline),
                                            modifier = Modifier.clickable { showEditProviderDialog = true }
                                        ) {
                                            Text(
                                                text = "编辑",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.textSecondary,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                            )
                                        }
                                    }

                                    // 删除 / 断开 Provider 按钮
                                    MiniIconAction(
                                        icon = Icons.Default.DeleteOutline,
                                        tint = dangerColor,
                                        borderColor = dangerColor.copy(alpha = 0.35f),
                                        contentDescription = if (currentProvider.isBuiltIn) "断开 Provider" else "删除 Provider",
                                        onClick = { showDeleteProviderDialog = true }
                                    )
                                }

                                // 第二行：单行可点击复制的 Base URL 条
                                val canCopyUrl = !currentProvider.isBuiltIn && currentProvider.baseUrl.isNotBlank()
                                Surface(
                                    shape = RoundedCornerShape(7.dp),
                                    color = theme.surfaceContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = canCopyUrl) {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Base URL", currentProvider.baseUrl))
                                            Toast.makeText(context, "已复制 Base URL", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = "URL",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.textMuted,
                                            modifier = Modifier.padding(end = 8.dp)
                                        )
                                        Text(
                                            text = if (currentProvider.isBuiltIn && currentProvider.baseUrl.isBlank()) {
                                                "(官方内置端点，免配 URL)"
                                            } else {
                                                currentProvider.baseUrl.ifBlank { "(采用接口默认端点)" }
                                            },
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = if (currentProvider.baseUrl.isNotBlank()) theme.textPrimary else theme.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (canCopyUrl) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "复制",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.textMuted
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "暂未接入任何提供商，请点击上方「+ 添加提供商」",
                                    fontSize = 12.sp,
                                    color = theme.textMuted
                                )
                            }
                        }
                    }
                }

                // 4. 模型清单区：「搜索框 + ⚡ API 发现 + ＋ 新增」工具行 + 清爽高密度单列卡片列表
                if (currentProvider != null) {
                    val modelQueryMatched = currentProvider.models.withIndex().filter { (_, m) ->
                        val q = modelQuery.trim()
                        q.isBlank() || m.id.contains(q, ignoreCase = true) || m.name.contains(q, ignoreCase = true)
                    }

                    item(key = "models_toolbar") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SettingsSearchField(
                                value = modelQuery,
                                onValueChange = { modelQuery = it },
                                placeholder = "搜索模型名称或 ID...",
                                modifier = Modifier.weight(1f)
                            )

                            if (!currentProvider.isBuiltIn) {
                                // ⚡ API 发现 按钮
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = theme.primary,
                                    modifier = Modifier.clickable {
                                        openDiscoverModelsDialog(currentProvider)
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.5.dp)
                                    ) {
                                        Text(
                                            text = "⚡ API 发现",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = theme.onPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                // + 新增 按钮（创建新模型并立即呼出 ModalBottomSheet 精调抽屉）
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = theme.surfaceContainerLow,
                                    border = BorderStroke(1.dp, theme.outline),
                                    modifier = Modifier.clickable {
                                        val newId = ModelConfigUiFormatters.nextCustomModelId(currentProvider.models)
                                        val newModel = ModelConfigItem(
                                            id = newId,
                                            name = "自定义新增模型",
                                            reasoning = true,
                                            hasVision = false,
                                            contextWindow = 128_000L,
                                            maxTokens = 16_384L
                                        )
                                        val updatedProv = currentProvider.copy(models = listOf(newModel) + currentProvider.models)
                                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                                        modelQuery = ""
                                        selectedModelIndex = 0
                                        selectedModelId = newId
                                        isAdvancedExpanded = false
                                        showModelEditorSheet = true
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.5.dp)
                                    ) {
                                        Text(
                                            text = "+ 新增",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }

                    item(key = "models_list_card") {
                        SettingsGroup {
                            if (modelQueryMatched.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 18.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (currentProvider.models.isEmpty()) "该提供商下暂无模型" else "没有匹配的模型",
                                        fontSize = 12.sp,
                                        color = theme.textMuted
                                    )
                                }
                            } else {
                                modelQueryMatched.forEachIndexed { rowIndex, (origIdx, m) ->
                                    if (rowIndex > 0) {
                                        HorizontalDivider(color = theme.outline, thickness = 1.dp)
                                    }
                                    val pingKey = "${currentProvider.name.lowercase()}/${m.id}"
                                    val pingState = modelPingResults[pingKey]

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedModelIndex = origIdx
                                                selectedModelId = m.id
                                                isAdvancedExpanded = false
                                                showModelEditorSheet = true
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // 左侧：第一行模型 ID + 能力微章；第二行显示名 + 上下文/输出规格摘要
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Text(
                                                    text = m.id,
                                                    fontSize = 12.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = theme.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (m.reasoning) {
                                                    CapabilityTag(text = "思考", emphasized = true)
                                                }
                                                if (m.hasVision) {
                                                    CapabilityTag(text = "视觉", emphasized = false)
                                                }
                                            }

                                            Text(
                                                text = ModelConfigUiFormatters.formatModelRowSummary(m),
                                                fontSize = 11.sp,
                                                color = theme.textMuted,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        // 右侧：行内一键「⚡ 测速」（或实测延迟）胶囊按钮 + 右箭头 ›
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            val pingLabel = when {
                                                pingState?.isTesting == true -> "⚡ ..."
                                                pingState?.ok == true && pingState.latencyMs != null -> "⚡ ${pingState.latencyMs}ms"
                                                pingState?.ok == true -> "⚡ 正常"
                                                pingState?.ok == false -> "⚡ 失败"
                                                else -> "⚡ 测速"
                                            }
                                            val pingColor = when {
                                                pingState?.isTesting == true -> theme.primary
                                                pingState?.ok == true -> theme.success
                                                pingState?.ok == false -> dangerColor
                                                else -> theme.textSecondary
                                            }
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = theme.surfaceContainer,
                                                border = BorderStroke(
                                                    0.6.dp,
                                                    when {
                                                        pingState?.ok == true -> theme.success.copy(alpha = 0.4f)
                                                        pingState?.ok == false -> dangerColor.copy(alpha = 0.4f)
                                                        else -> theme.outline.copy(alpha = 0.6f)
                                                    }
                                                ),
                                                modifier = Modifier.clickable(enabled = pingState?.isTesting != true) {
                                                    triggerModelPing(currentProvider, m, showToastOnFinish = true)
                                                }
                                            ) {
                                                Text(
                                                    text = pingLabel,
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (pingState?.ok != null) FontWeight.SemiBold else FontWeight.Normal,
                                                    color = pingColor,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Default.ChevronRight,
                                                contentDescription = "调校模型参数",
                                                tint = theme.textMuted,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. 底部智能脏数据浮动栏 (Smart Dirty Bar)：仅当存在未保存修改 (draftDirty) 时浮出
            AnimatedVisibility(
                visible = draftDirty && !isLoading,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            ) {
                val pulseTransition = rememberInfiniteTransition(label = "dirty_pulse")
                val dotAlpha by pulseTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "dirty_dot_alpha"
                )

                Surface(
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(1.dp, theme.primary.copy(alpha = 0.55f)),
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            modifier = Modifier.weight(1f).padding(end = 8.dp)
                        ) {
                            Spacer(
                                modifier = Modifier
                                    .size(8.dp)
                                    .alpha(dotAlpha)
                                    .background(color = theme.warning, shape = CircleShape)
                            )
                            Text(
                                text = "有未保存的配置修改",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(0.8.dp, theme.outline),
                                modifier = Modifier.clickable(enabled = !isSaving) {
                                    providers = serverProviders
                                    restoreSelection(serverProviders)
                                    showModelEditorSheet = false
                                    Toast.makeText(context, "已还原未保存的修改", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text(
                                    text = "放弃修改",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }

                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.primary,
                                modifier = Modifier.clickable(enabled = !isSaving) {
                                    saveAllConfig()
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    if (isSaving) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 1.6.dp,
                                            color = theme.onPrimary
                                        )
                                        Text(
                                            text = "保存中...",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.onPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    } else {
                                        Text(
                                            text = "保存并生效",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = theme.onPrimary,
                                            maxLines = 1,
                                            softWrap = false
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

    // ==================== 模型参数精调抽屉 (ModalBottomSheet) ====================
    if (showModelEditorSheet && currentModel != null && currentProvider != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val pingKey = "${currentProvider.name.lowercase()}/${currentModel.id}"
        val currentPing = modelPingResults[pingKey]

        // 抽屉内数值输入框的独立文本缓冲（按 Provider + 模型索引隔离），
        // 避免每次按键因 Long/Double.toString() 强制往返导致无法清空或无法输入小数点 "." / "0.05"
        var contextInputText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(if (currentModel.contextWindow > 0L) currentModel.contextWindow.toString() else "")
        }
        var maxOutInputText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(if (currentModel.maxTokens > 0L) currentModel.maxTokens.toString() else "")
        }
        var costInputText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(ModelConfigUiFormatters.formatCostEditText(currentModel.costInput))
        }
        var costOutputText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(ModelConfigUiFormatters.formatCostEditText(currentModel.costOutput))
        }
        var costCacheReadText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(ModelConfigUiFormatters.formatCostEditText(currentModel.costCacheRead))
        }
        var costCacheWriteText by remember(currentProvider.name, currentModelIndex) {
            mutableStateOf(ModelConfigUiFormatters.formatCostEditText(currentModel.costCacheWrite))
        }

        ModalBottomSheet(
            onDismissRequest = { showModelEditorSheet = false },
            sheetState = sheetState,
            containerColor = theme.surfaceContainerLow,
            contentColor = theme.textPrimary,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = {
                Surface(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 36.dp, height = 4.dp),
                    shape = CircleShape,
                    color = theme.outline
                ) {}
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 抽屉顶栏：模型 ID 标题 + ⚡ 实测连通 + 🗑️ 移除模型 + 关闭按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "模型参数调校 · ${currentProvider.name}",
                            fontSize = 11.sp,
                            color = theme.textMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = currentModel.id.ifBlank { "(未命名模型)" },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val isPinging = currentPing?.isTesting == true
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.primary.copy(alpha = 0.10f),
                            border = BorderStroke(0.8.dp, theme.primary.copy(alpha = 0.5f)),
                            modifier = Modifier.clickable(enabled = !isPinging) {
                                triggerModelPing(currentProvider, currentModel, showToastOnFinish = false)
                            }
                        ) {
                            Text(
                                text = if (isPinging) "测试中..." else "⚡ 实测连通",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = theme.primary,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        if (!currentProvider.isBuiltIn) {
                            MiniIconAction(
                                icon = Icons.Default.DeleteOutline,
                                tint = dangerColor,
                                borderColor = dangerColor.copy(alpha = 0.35f),
                                contentDescription = "移除模型",
                                onClick = { showDeleteModelDialog = true }
                            )
                        }

                        MiniIconAction(
                            icon = Icons.Default.Close,
                            tint = theme.textSecondary,
                            borderColor = theme.outline,
                            contentDescription = "关闭抽屉",
                            onClick = { showModelEditorSheet = false }
                        )
                    }
                }

                HorizontalDivider(color = theme.outline, thickness = 1.dp)

                // 2. 连通性实测反馈横幅
                if (currentPing?.isTesting == true) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(0.8.dp, theme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(13.dp),
                                    strokeWidth = 1.6.dp,
                                    color = theme.primary
                                )
                                Text(
                                    text = "正在向网关发起真实推理握手测试...",
                                    fontSize = 11.5.sp,
                                    color = theme.textSecondary
                                )
                            }
                            Text(
                                text = "...",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textMuted
                            )
                        }
                    }
                } else if (currentPing?.message != null) {
                    val isOk = currentPing.ok == true
                    val accent = if (isOk) theme.success else dangerColor
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accent.copy(alpha = 0.10f),
                        border = BorderStroke(0.8.dp, accent.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = if (isOk) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                    contentDescription = null,
                                    tint = accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = currentPing.message,
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = accent
                                )
                            }
                            if (currentPing.latencyMs != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${currentPing.latencyMs}ms",
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = accent
                                )
                            }
                        }
                    }
                }

                // 3. 一键从 models.dev 智能补全官方规格魔法条
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(0.8.dp, theme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isCatalogFilling) {
                            isCatalogFilling = true
                            coroutineScope.launch {
                                val catalogRes = apiClient.getCatalogModelPreset(
                                    modelId = currentModel.id,
                                    providerName = currentProvider.name,
                                    baseUrlParam = currentProvider.baseUrl
                                )
                                catalogRes.onSuccess { preset ->
                                    if (preset != null) {
                                        val nextCtx = preset.contextWindow ?: currentModel.contextWindow
                                        val nextMaxOut = preset.maxTokens ?: currentModel.maxTokens
                                        val nextCostIn = preset.costInput ?: currentModel.costInput
                                        val nextCostOut = preset.costOutput ?: currentModel.costOutput
                                        val nextCostRead = preset.costCacheRead ?: currentModel.costCacheRead
                                        val nextCostWrite = preset.costCacheWrite ?: currentModel.costCacheWrite
                                        contextInputText = if (nextCtx > 0L) nextCtx.toString() else ""
                                        maxOutInputText = if (nextMaxOut > 0L) nextMaxOut.toString() else ""
                                        costInputText = ModelConfigUiFormatters.formatCostEditText(nextCostIn)
                                        costOutputText = ModelConfigUiFormatters.formatCostEditText(nextCostOut)
                                        costCacheReadText = ModelConfigUiFormatters.formatCostEditText(nextCostRead)
                                        costCacheWriteText = ModelConfigUiFormatters.formatCostEditText(nextCostWrite)
                                        updateCurrentModel { old ->
                                            old.copy(
                                                name = preset.name?.takeIf { it.isNotBlank() } ?: old.name,
                                                reasoning = preset.reasoning ?: old.reasoning,
                                                hasVision = preset.input?.contains("image") ?: old.hasVision,
                                                contextWindow = nextCtx,
                                                maxTokens = nextMaxOut,
                                                costInput = nextCostIn,
                                                costOutput = nextCostOut,
                                                costCacheRead = nextCostRead,
                                                costCacheWrite = nextCostWrite
                                            )
                                        }
                                        Toast.makeText(context, "✨ 已从 models.dev 匹配官方参数规格", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "models.dev 里没有精确匹配，保留原值", Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { err ->
                                    Toast.makeText(context, "获取推荐失败：${err.message}", Toast.LENGTH_SHORT).show()
                                }
                                isCatalogFilling = false
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isCatalogFilling) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.6.dp,
                                    color = theme.primary
                                )
                                Text(
                                    text = "正在从 models.dev 智能匹配...",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.AutoFixHigh,
                                    contentDescription = null,
                                    tint = theme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "✨ 从 models.dev 智能补全官方规格",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "一键填入 ›",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.primary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // 4. 双列输入：模型标识 (ID) 与显示名称 (Name)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModelInputField(
                        value = currentModel.id,
                        onValueChange = { newId -> updateCurrentModel { it.copy(id = newId) } },
                        label = "模型标识 (ID) *",
                        placeholder = "如 claude-sonnet-4-5",
                        isMonospace = true,
                        readOnly = currentProvider.isBuiltIn,
                        modifier = Modifier.weight(1f)
                    )
                    ModelInputField(
                        value = currentModel.name,
                        onValueChange = { newName -> updateCurrentModel { it.copy(name = newName) } },
                        label = "显示名称 (Name)",
                        placeholder = "如 Claude 4.5 Sonnet",
                        modifier = Modifier.weight(1f)
                    )
                }

                // 5. 双列大尺寸触控能力卡片开关：深度思考 与 多模态视觉
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ModelCapabilityCard(
                        title = "深度思考",
                        icon = Icons.Default.Psychology,
                        checked = currentModel.reasoning,
                        modifier = Modifier.weight(1f),
                        onToggle = { updateCurrentModel { it.copy(reasoning = !it.reasoning) } }
                    )
                    ModelCapabilityCard(
                        title = "多模态视觉",
                        icon = Icons.Default.Visibility,
                        checked = currentModel.hasVision,
                        modifier = Modifier.weight(1f),
                        onToggle = { updateCurrentModel { it.copy(hasVision = !it.hasVision) } }
                    )
                }

                // 6. 容量规格区：上下文窗口 (Context) 与最大输出 (Max Out) + 实时单位回显 + 快捷预设胶囊
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(0.8.dp, theme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "上下文窗口与最大输出",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textPrimary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 左列：上下文窗口 (Context)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "上下文 (Context)",
                                        fontSize = 11.sp,
                                        color = theme.textSecondary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = ModelConfigUiFormatters.formatModelTokenSpec(currentModel.contextWindow),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        maxLines = 1
                                    )
                                }

                                ModelInputField(
                                    value = contextInputText,
                                    onValueChange = { str ->
                                        val digits = str.filter { it.isDigit() }
                                        contextInputText = digits
                                        val v = digits.toLongOrNull() ?: 0L
                                        updateCurrentModel { it.copy(contextWindow = v) }
                                    },
                                    placeholder = "128000",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isMonospace = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ModelConfigUiFormatters.CONTEXT_WINDOW_PRESETS.forEach { (presetLabel, presetVal) ->
                                        val isPresetSel = ModelConfigUiFormatters.isPresetSelected(
                                            currentTokens = currentModel.contextWindow,
                                            presetTokens = presetVal,
                                            presetLabel = presetLabel
                                        )
                                        QuickTokenPresetChip(
                                            label = presetLabel,
                                            selected = isPresetSel,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                contextInputText = presetVal.toString()
                                                updateCurrentModel { it.copy(contextWindow = presetVal) }
                                            }
                                        )
                                    }
                                }
                            }

                            // 右列：最大输出 (Max Out)
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "最大输出 (Max Out)",
                                        fontSize = 11.sp,
                                        color = theme.textSecondary,
                                        maxLines = 1
                                    )
                                    Text(
                                        text = ModelConfigUiFormatters.formatModelTokenSpec(currentModel.maxTokens),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        maxLines = 1
                                    )
                                }

                                ModelInputField(
                                    value = maxOutInputText,
                                    onValueChange = { str ->
                                        val digits = str.filter { it.isDigit() }
                                        maxOutInputText = digits
                                        val v = digits.toLongOrNull() ?: 0L
                                        updateCurrentModel { it.copy(maxTokens = v) }
                                    },
                                    placeholder = "16384",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isMonospace = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    ModelConfigUiFormatters.MAX_OUTPUT_PRESETS.forEach { (presetLabel, presetVal) ->
                                        val isPresetSel = ModelConfigUiFormatters.isPresetSelected(
                                            currentTokens = currentModel.maxTokens,
                                            presetTokens = presetVal,
                                            presetLabel = presetLabel
                                        )
                                        QuickTokenPresetChip(
                                            label = presetLabel,
                                            selected = isPresetSel,
                                            modifier = Modifier.weight(1f),
                                            onClick = {
                                                maxOutInputText = presetVal.toString()
                                                updateCurrentModel { it.copy(maxTokens = presetVal) }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 7. 可折叠的价格（输入/输出/缓存读/缓存写）与自定义 API 协议覆盖高级参数区
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
                            text = if (isAdvancedExpanded) "收起价格与高级参数 ▴" else "展开价格与高级参数 ▾",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.primary
                        )
                    }
                }

                AnimatedVisibility(visible = isAdvancedExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "每百万 Tokens 价格 ($)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textMuted
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModelInputField(
                                value = costInputText,
                                onValueChange = { str ->
                                    val clean = ModelConfigUiFormatters.sanitizeDecimalInput(str)
                                    costInputText = clean
                                    updateCurrentModel { it.copy(costInput = clean.toDoubleOrNull() ?: 0.0) }
                                },
                                label = "输入价格",
                                placeholder = "0",
                                leadingText = "$",
                                trailingUnit = "/M",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isMonospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            ModelInputField(
                                value = costOutputText,
                                onValueChange = { str ->
                                    val clean = ModelConfigUiFormatters.sanitizeDecimalInput(str)
                                    costOutputText = clean
                                    updateCurrentModel { it.copy(costOutput = clean.toDoubleOrNull() ?: 0.0) }
                                },
                                label = "输出价格",
                                placeholder = "0",
                                leadingText = "$",
                                trailingUnit = "/M",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isMonospace = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ModelInputField(
                                value = costCacheReadText,
                                onValueChange = { str ->
                                    val clean = ModelConfigUiFormatters.sanitizeDecimalInput(str)
                                    costCacheReadText = clean
                                    updateCurrentModel { it.copy(costCacheRead = clean.toDoubleOrNull() ?: 0.0) }
                                },
                                label = "缓存读取",
                                placeholder = "0",
                                leadingText = "$",
                                trailingUnit = "/M",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                isMonospace = true,
                                modifier = Modifier.weight(1f)
                            )
                            ModelInputField(
                                value = costCacheWriteText,
                                onValueChange = { str ->
                                    val clean = ModelConfigUiFormatters.sanitizeDecimalInput(str)
                                    costCacheWriteText = clean
                                    updateCurrentModel { it.copy(costCacheWrite = clean.toDoubleOrNull() ?: 0.0) }
                                },
                                label = "缓存写入",
                                placeholder = "0",
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
                            label = "自定义 API 协议覆盖",
                            placeholder = "留空则继承 Provider API",
                            isMonospace = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 8. 抽屉底部「完成调校」按钮
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            val validationError = ModelConfigUiFormatters.validateModelsInProvider(currentProvider)
                            if (validationError != null) {
                                Toast.makeText(context, validationError, Toast.LENGTH_SHORT).show()
                            } else {
                                showModelEditorSheet = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = theme.primary,
                            contentColor = theme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "完成调校",
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.onPrimary
                        )
                    }
                }
            }
        }
    }

    // ==================== 弹窗 1：添加 Provider ====================
    if (showAddProviderDialog) {
        var addMode by remember { mutableStateOf(0) } // 0: 官方内置, 1: 自定义端点
        var builtInSearch by remember { mutableStateOf("") }
        var selectedBuiltIn by remember { mutableStateOf<ProviderAuthState?>(null) }
        var builtInApiKeyInput by remember { mutableStateOf("") }
        var isActivatingBuiltIn by remember { mutableStateOf(false) }

        var newProvName by remember { mutableStateOf("") }
        var newProvBaseUrl by remember { mutableStateOf("") }
        var newProvApiKey by remember { mutableStateOf("") }
        var newProvApi by remember { mutableStateOf("openai-completions") }

        AlertDialog(
            onDismissRequest = {
                if (!isActivatingBuiltIn) {
                    showAddProviderDialog = false
                    selectedBuiltIn = null
                }
            },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    text = if (selectedBuiltIn != null) "接入 ${selectedBuiltIn!!.displayName}" else "添加提供商",
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedBuiltIn != null) {
                        Text(
                            text = "请输入 ${selectedBuiltIn!!.displayName} 的 API Key，将写入凭据库并立即启用其 ${selectedBuiltIn!!.modelCount} 个可用模型：",
                            fontSize = 12.sp,
                            color = theme.textSecondary,
                            lineHeight = 17.sp
                        )
                        OutlinedTextField(
                            value = builtInApiKeyInput,
                            onValueChange = { builtInApiKeyInput = it.trim() },
                            label = { Text("API Key") },
                            placeholder = { Text("sk-…") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (addMode == 0) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainer,
                                border = BorderStroke(if (addMode == 0) 1.dp else 0.5.dp, if (addMode == 0) theme.primary else theme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { addMode = 0 }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                    Text(
                                        text = "官方内置",
                                        fontSize = 11.5.sp,
                                        fontWeight = if (addMode == 0) FontWeight.Bold else FontWeight.Normal,
                                        color = if (addMode == 0) theme.primary else theme.textSecondary
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (addMode == 1) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainer,
                                border = BorderStroke(if (addMode == 1) 1.dp else 0.5.dp, if (addMode == 1) theme.primary else theme.outline),
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { addMode = 1 }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                    Text(
                                        text = "自定义端点",
                                        fontSize = 11.5.sp,
                                        fontWeight = if (addMode == 1) FontWeight.Bold else FontWeight.Normal,
                                        color = if (addMode == 1) theme.primary else theme.textSecondary
                                    )
                                }
                            }
                        }

                        if (addMode == 0) {
                            OutlinedTextField(
                                value = builtInSearch,
                                onValueChange = { builtInSearch = it.trim() },
                                placeholder = { Text("搜索内置提供商，如 anthropic / openai...") },
                                singleLine = true,
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            val unconfiguredList = authStates.values
                                .filter { !it.configured && !providers.any { p -> p.name.equals(it.providerId, ignoreCase = true) } }
                                .filter {
                                    builtInSearch.isBlank() ||
                                        it.displayName.contains(builtInSearch, ignoreCase = true) ||
                                        it.providerId.contains(builtInSearch, ignoreCase = true)
                                }
                                .sortedBy { it.displayName }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (unconfiguredList.isEmpty()) {
                                    Text(
                                        text = if (builtInSearch.isBlank()) "无更多未接入的内置提供商" else "未匹配到任何提供商",
                                        fontSize = 11.sp,
                                        color = theme.textMuted,
                                        modifier = Modifier.padding(vertical = 12.dp)
                                    )
                                } else {
                                    unconfiguredList.forEach { pAuth ->
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = theme.surfaceContainer,
                                            border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.5f)),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    selectedBuiltIn = pAuth
                                                    builtInApiKeyInput = ""
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    Icons.Default.CloudQueue,
                                                    contentDescription = null,
                                                    tint = theme.primary,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = pAuth.displayName.ifBlank { pAuth.providerId },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = theme.textPrimary,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = "· ${pAuth.modelCount} 模型",
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = theme.textMuted
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    Icons.Default.ChevronRight,
                                                    contentDescription = null,
                                                    tint = theme.textMuted,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = newProvName,
                                onValueChange = { newProvName = it.trim() },
                                label = { Text("Provider 标识 *") },
                                placeholder = { Text("如 my-endpoint") },
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
                    }
                }
            },
            confirmButton = {
                if (selectedBuiltIn != null) {
                    Button(
                        onClick = {
                            val key = builtInApiKeyInput.trim()
                            if (key.isBlank() || isActivatingBuiltIn) return@Button
                            isActivatingBuiltIn = true
                            val pAuth = selectedBuiltIn!!
                            coroutineScope.launch {
                                apiClient.setProviderApiKey(pAuth.providerId, key)
                                    .onSuccess {
                                        Toast.makeText(context, "已接入 ${pAuth.displayName}", Toast.LENGTH_SHORT).show()
                                        selectedProviderName = pAuth.providerId
                                        showAddProviderDialog = false
                                        selectedBuiltIn = null
                                        loadConfigFromServer(silent = true)
                                        onModelsConfigChanged()
                                    }
                                    .onFailure { err ->
                                        Toast.makeText(context, "接入失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                isActivatingBuiltIn = false
                            }
                        },
                        enabled = builtInApiKeyInput.isNotBlank() && !isActivatingBuiltIn,
                        colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                    ) {
                        Text(if (isActivatingBuiltIn) "激活中…" else "激活并接入", color = theme.onPrimary)
                    }
                } else if (addMode == 1) {
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
                                displayName = newProvName,
                                baseUrl = newProvBaseUrl,
                                apiKey = newProvApiKey,
                                api = newProvApi,
                                models = listOf(ModelConfigItem(id = "$newProvName-default", name = "$newProvName-default", reasoning = true)),
                                isBuiltIn = false
                            )
                            providers = providers + created
                            selectedProviderName = created.name
                            selectedModelId = created.models.first().id
                            showAddProviderDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                    ) {
                        Text("添加", color = theme.onPrimary)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    if (selectedBuiltIn != null) {
                        selectedBuiltIn = null
                    } else {
                        showAddProviderDialog = false
                    }
                }) {
                    Text(if (selectedBuiltIn != null) "返回" else "取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 2：配置当前 Provider ====================
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
                        label = { Text("API Key（写入 models.json）") },
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
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                ) {
                    Text("完成", color = theme.onPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditProviderDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 2.5：配置 Provider 的 API Key（写 pi 凭据库 auth.json，先验后存） ====================
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
                        "先实测再写入 auth.json；错 Key 会让所有会话 401",
                        fontSize = 11.sp,
                        color = theme.textMuted,
                        lineHeight = 16.sp
                    )
                    if (keySourceDetail.isNotBlank()) {
                        Text(
                            "当前状态：${currentProvider.name} 已配置（来源：$keySourceDetail）",
                            fontSize = 10.5.sp,
                            color = theme.success
                        )
                    }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it.trim()
                            apiKeyInputError = null
                            allowUnverifiedSave = false
                        },
                        label = { Text("API Key 或 Bearer Token") },
                        placeholder = { Text("如 sk-…") },
                        singleLine = true,
                        isError = apiKeyInputError != null,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    apiKeyInputError?.let { err ->
                        Text(err, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.error, lineHeight = 15.sp)
                    }
                    apiKeyVerifyPhase?.let { phase ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 1.6.dp,
                                color = theme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(phase, fontSize = 10.5.sp, color = theme.textSecondary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val key = apiKeyInput.trim()
                        if (key.isEmpty() || isSavingApiKey) return@Button
                        val prov = currentProvider
                        // 先用该 Provider 下的第一个模型真实发一轮请求：
                        // 1) 服务端不认（401）就绝不写入；
                        // 2) 无 Base URL（如内置 anthropic/openai 走 pi 自带端点）时无法实测，
                        //    需用户再点一次「跳过实测并保存」明示确认；
                        // 3) 底层 setProviderApiKey 仍会再做一次格式自检。
                        val probeModelId = prov.models.firstOrNull()?.id ?: ""
                        val canVerify = prov.baseUrl.isNotBlank() && probeModelId.isNotBlank()
                        isSavingApiKey = true
                        apiKeyInputError = null
                        coroutineScope.launch {
                            var verified = allowUnverifiedSave
                            if (!verified && canVerify) {
                                apiKeyVerifyPhase = "正在用 ${prov.name} 实测该 Key…"
                                apiClient.verifyProviderApiKey(
                                    provider = prov.name,
                                    baseUrlEndpoint = prov.baseUrl,
                                    apiType = prov.api,
                                    modelId = probeModelId,
                                    candidateKey = key
                                ).onSuccess { res ->
                                    verified = res.ok
                                    if (!res.ok) {
                                        apiKeyInputError = "该 Key 未被服务端接受：${res.error ?: "未知错误"}"
                                    }
                                }.onFailure { err ->
                                    // 测试接口本身不可用（旧网关 / 网络异常）：不强存，只给出路
                                    allowUnverifiedSave = true
                                    apiKeyInputError =
                                        "无法实测（${err.message ?: "测试接口不可用"}）；确认无误请再点一次「跳过实测并保存」"
                                }
                            }

                            if (verified) {
                                apiKeyVerifyPhase = "验证通过，正在写入凭据库…"
                                apiClient.setProviderApiKey(prov.name, key)
                                    .onSuccess {
                                        Toast.makeText(context, "已保存 ${prov.name} 的 API Key", Toast.LENGTH_SHORT).show()
                                        apiKeyInput = ""
                                        apiKeyInputError = null
                                        allowUnverifiedSave = false
                                        showApiKeyDialog = false
                                        loadConfigFromServer(silent = true)
                                        onModelsConfigChanged()
                                    }
                                    .onFailure { err ->
                                        apiKeyInputError = "保存失败: ${err.message ?: "未知错误"}"
                                    }
                            } else if (!canVerify && apiKeyInputError == null) {
                                // 内置 Provider 没有 Base URL，提示需要明示确认
                                allowUnverifiedSave = true
                                apiKeyInputError = "无 Base URL，无法实测；再点一次「跳过实测并保存」"
                            }
                            apiKeyVerifyPhase = null
                            isSavingApiKey = false
                        }
                    },
                    enabled = apiKeyInput.isNotBlank() && !isSavingApiKey,
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                ) {
                    Text(
                        when {
                            isSavingApiKey -> "验证中..."
                            allowUnverifiedSave -> "跳过实测并保存"
                            else -> "验证并保存"
                        },
                        color = theme.onPrimary
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { if (!isSavingApiKey) showApiKeyDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 3：删除/断开 Provider 确认 ====================
    if (showDeleteProviderDialog && currentProvider != null) {
        val isBuiltIn = currentProvider.isBuiltIn
        AlertDialog(
            onDismissRequest = { showDeleteProviderDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = {
                Text(
                    if (isBuiltIn) "确认断开 ${currentProvider.displayName.ifBlank { currentProvider.name }}？"
                    else "确认删除 Provider？",
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary
                )
            },
            text = {
                Text(
                    if (isBuiltIn) "将清除已保存在凭据库中的 API Key，断开后该内置提供商将被停用。"
                    else "将删除 ${currentProvider.name} 及其全部模型，保存后生效。",
                    color = theme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isBuiltIn) {
                            coroutineScope.launch {
                                apiClient.deleteProviderApiKey(currentProvider.name)
                                    .onSuccess {
                                        Toast.makeText(context, "已断开 ${currentProvider.name}", Toast.LENGTH_SHORT).show()
                                        loadConfigFromServer(silent = true)
                                        onModelsConfigChanged()
                                    }
                                    .onFailure { err ->
                                        Toast.makeText(context, "断开失败：${err.message}", Toast.LENGTH_LONG).show()
                                    }
                                showDeleteProviderDialog = false
                            }
                        } else {
                            val remaining = providers.filterNot { it.name == currentProvider.name }
                            providers = remaining
                            selectedProviderName = remaining.firstOrNull()?.name
                            selectedModelId = remaining.firstOrNull()?.models?.firstOrNull()?.id
                            showDeleteProviderDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (isBuiltIn) "确认断开" else "确认删除", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteProviderDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 4：移除模型确认 ====================
    if (showDeleteModelDialog && currentModel != null && currentProvider != null) {
        AlertDialog(
            onDismissRequest = { showDeleteModelDialog = false },
            containerColor = theme.surfaceContainerLow,
            title = { Text("确认移除模型？", fontWeight = FontWeight.Bold, color = theme.textPrimary) },
            text = { Text("将从 ${currentProvider.name} 中移除模型 ${currentModel.id.ifBlank { "(未命名模型)" }}。", color = theme.textSecondary) },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedModels = ModelConfigUiFormatters.removeModelAt(currentProvider.models, currentModelIndex)
                        val updatedProv = currentProvider.copy(models = updatedModels)
                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                        selectedModelIndex = 0
                        selectedModelId = updatedModels.firstOrNull()?.id
                        showDeleteModelDialog = false
                        showModelEditorSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("确认移除", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteModelDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 5：从 API 自动发现模型并批量选择添加 ====================
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
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("查询失败", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 12.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(discoverError ?: "", color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 11.sp)
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
                                    BasicTextField(
                                        value = discoverSearchQuery,
                                        onValueChange = { discoverSearchQuery = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = theme.textPrimary, fontSize = 11.5.sp),
                                        cursorBrush = SolidColor(theme.primary),
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
                        val firstNewIndex = currentProvider.models.size
                        val updatedProv = currentProvider.copy(models = currentProvider.models + toAdd)
                        providers = providers.map { if (it.name == currentProvider.name) updatedProv else it }
                        modelQuery = ""
                        selectedModelIndex = firstNewIndex
                        selectedModelId = toAdd.first().id
                        showDiscoverDialog = false
                        Toast.makeText(context, "已添加 ${toAdd.size} 个模型，保存配置后生效", Toast.LENGTH_SHORT).show()
                    },
                    enabled = selectedDiscoverIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                ) {
                    Text("批量添加 (${selectedDiscoverIds.size})", color = theme.onPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscoverDialog = false }) {
                    Text("取消", color = theme.textSecondary)
                }
            }
        )
    }

    // ==================== 弹窗 6：查看与编辑原始 JSON 源码 ====================
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
                            val rebuilt = if (authStates.isNotEmpty() || catalogModels != null) {
                                ModelConfigConverter.combineProviders(parsed, authStates, catalogModels)
                            } else {
                                ModelConfigConverter.parseProvidersConfig(parsed)
                            }
                            providers = rebuilt
                            restoreSelection(rebuilt)
                            showRawJsonDialog = false
                            Toast.makeText(context, "已按 JSON 源码更新界面", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "JSON 语法错误：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = theme.primary, contentColor = theme.onPrimary)
                ) {
                    Text("应用", color = theme.onPrimary)
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
    height: Dp = 36.dp
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
            shape = RoundedCornerShape(7.dp),
            color = theme.surfaceContainerLow,
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
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = singleLine,
                    keyboardOptions = keyboardOptions,
                    textStyle = TextStyle(
                        color = theme.textPrimary,
                        fontSize = 12.sp,
                        fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default
                    ),
                    cursorBrush = SolidColor(theme.primary),
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
 * 统一的「图标操作」按钮：固定 28dp 圆形/圆角热区，仅图标不含文字，
 * 避免窄屏下文字按钮被挤压成竖排。
 */
@Composable
private fun MiniIconAction(
    icon: ImageVector,
    tint: Color,
    borderColor: Color,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (enabled) theme.surfaceContainer else theme.surfaceContainerHigh.copy(alpha = 0.5f),
        border = BorderStroke(0.7.dp, borderColor.copy(alpha = if (enabled) 1f else 0.4f)),
        modifier = Modifier
            .size(28.dp)
            .clickable(enabled = enabled) { onClick() }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) tint else tint.copy(alpha = 0.4f),
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 能力微章（思考 / 视觉）：统一到主题 primary + 中性色两档。
 */
@Composable
private fun CapabilityTag(text: String, emphasized: Boolean) {
    val theme = LocalAppTheme.current
    val bg = if (emphasized) theme.primary.copy(alpha = 0.12f) else theme.surfaceContainerHigh
    val fg = if (emphasized) theme.primary else theme.textSecondary
    Surface(shape = RoundedCornerShape(4.dp), color = bg) {
        Text(
            text = text,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = fg,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
        )
    }
}

/**
 * 抽屉内双列大尺寸触控能力卡片开关（深度思考 / 多模态视觉）。
 */
@Composable
private fun ModelCapabilityCard(
    title: String,
    icon: ImageVector,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (checked) theme.primary.copy(alpha = 0.10f) else theme.surfaceContainer,
        border = BorderStroke(
            width = if (checked) 1.dp else 0.8.dp,
            color = if (checked) theme.primary.copy(alpha = 0.65f) else theme.outline
        ),
        modifier = modifier.clickable { onToggle() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (checked) theme.primary else theme.textMuted,
                    modifier = Modifier.size(15.dp)
                )
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                    color = if (checked) theme.textPrimary else theme.textSecondary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (checked) theme.primary else theme.textMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 容量规格快捷预设胶囊按钮（如 32k / 128k / 200k / 1M、8k / 16k / 64k / 128k）。
 */
@Composable
private fun QuickTokenPresetChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = if (selected) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainerLow,
        border = BorderStroke(
            width = 0.7.dp,
            color = if (selected) theme.primary.copy(alpha = 0.65f) else theme.outline
        ),
        modifier = modifier.clickable { onClick() }
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(vertical = 3.5.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) theme.primary else theme.textSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
