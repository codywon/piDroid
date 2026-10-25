package com.pi.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.pi.app.data.model.*
import com.pi.app.data.storage.AppPreferences
import com.pi.app.keepalive.KeepAliveManager
import com.pi.app.keepalive.PiForegroundService
import com.pi.app.network.DynamicModelCatalog
import com.pi.app.network.PiApiClient
import com.pi.app.network.PiSseClient
import com.pi.app.notification.PiHapticsAndNotify
import com.pi.app.ui.chat.ChatScreen
import com.pi.app.ui.chat.ModelAndThinkingSheet
import com.pi.app.ui.scanner.QrScannerScreen
import com.pi.app.ui.session.SessionListScreen
import com.pi.app.ui.settings.GlobalSettingsScreen
import com.pi.app.ui.updater.AppUpdateDialog
import com.pi.app.ui.updater.UpdateUiState
import com.pi.app.updater.AppUpdateManager
import com.pi.app.updater.UpdateCheckResult
import com.pi.app.voice.SmartTtsManager
import com.pi.app.voice.VoiceInputManager
import com.pi.app.voice.VoiceInputState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: PiApiClient
    private lateinit var sseClient: PiSseClient
    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var smartTtsManager: SmartTtsManager
    private lateinit var hapticsAndNotify: PiHapticsAndNotify

    private lateinit var keepAliveManager: KeepAliveManager

    private var sseJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordAudioGranted) {
            Toast.makeText(this, "未授予录音权限，将无法使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AppPreferences(this)
        apiClient = PiApiClient(prefs)
        sseClient = PiSseClient(prefs)
        voiceInputManager = VoiceInputManager(this)
        smartTtsManager = SmartTtsManager(this)
        hapticsAndNotify = PiHapticsAndNotify(this)

        keepAliveManager = KeepAliveManager(this)

        // 初始化主题管理
        com.pi.app.ui.theme.ThemeManager.init(this)

        checkAndRequestPermissions()
        handleDeepLink(intent)

        setContent {
            com.pi.app.ui.theme.PiDroidTheme {
                val theme = com.pi.app.ui.theme.LocalAppTheme.current
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = theme.background
                ) {
                    MainAppNav()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "pidroid" && data.host == "connect") {
            val url = data.getQueryParameter("url") ?: return
            val token = data.getQueryParameter("token") ?: ""
            val name = data.getQueryParameter("name") ?: "Remote PC"
            lifecycleScope.launch {
                apiClient.ping(url, token).onSuccess {
                    prefs.saveConnection(url, token, name)
                    Toast.makeText(this@MainActivity, "已成功绑定设备: $name", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@MainActivity, "连接握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    @Composable
    fun MainAppNav() {
        var isScanning by remember { mutableStateOf(false) }
        var showGlobalSettings by remember { mutableStateOf(false) }
        var showModelSheet by remember { mutableStateOf(false) }
        var showManualConfigDialog by remember { mutableStateOf(false) }

        // 工业级应用内自动更新状态 (参考 Termius)
        var updateUiState by remember { mutableStateOf<UpdateUiState>(UpdateUiState.Idle) }

        var currentSession by remember { mutableStateOf<Session?>(null) }
        var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
        var isLoadingSessions by remember { mutableStateOf(false) }

        var dynamicCatalog by remember { mutableStateOf(DynamicModelCatalog(emptyList(), emptyMap())) }
        var selectedModel by remember {
            mutableStateOf(ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", 200000, 8192, true, true))
        }
        var currentThinkingLevel by remember {
            mutableStateOf(ThinkingLevel.fromValue(prefs.defaultThinkingLevel))
        }

        var currentProject by remember { mutableStateOf("默认工作区") }
        var tokenStats by remember { mutableStateOf(TokenStats(contextTokens = 42100, maxContextWindow = 200000, inputTokens = 1240, outputTokens = 412, cacheReadTokens = 38000, cost = 0.0)) }
        var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
        var plugins by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var mcpConfig by remember { mutableStateOf(McpCodeModeConfig()) }
        var mcpServers by remember { mutableStateOf<List<McpServerInfo>>(emptyList()) }

        val messages = remember { mutableStateListOf<ChatItem>() }
        val queuedMessages = remember { mutableStateListOf<QueuedMessage>() }
        var isAgentBusy by remember { mutableStateOf(false) }
        var agentPhaseLabel by remember { mutableStateOf<String?>(null) }
        var isCompacting by remember { mutableStateOf(false) }
        var isSpeaking by remember { mutableStateOf(false) }
        var isGatewayOnline by remember { mutableStateOf(true) }
        var sseConnectionState by remember { mutableStateOf(SseConnectionState.DISCONNECTED) }

        val voiceState by voiceInputManager.state.collectAsState()
        var isRecording by remember { mutableStateOf(false) }
        var partialVoiceText by remember { mutableStateOf("") }

        // 启动时静默检查更新 (非手动静默模式，若有更新则弹窗)
        LaunchedEffect(Unit) {
            when (val result = AppUpdateManager.checkUpdate(this@MainActivity, isManual = false)) {
                is UpdateCheckResult.HasUpdate -> {
                    updateUiState = UpdateUiState.HasUpdate(result.info)
                }
                else -> {}
            }
        }

        LaunchedEffect(voiceState) {
            when (val state = voiceState) {
                is VoiceInputState.Listening -> {
                    isRecording = true
                    partialVoiceText = ""
                }
                is VoiceInputState.PartialResult -> {
                    partialVoiceText = state.text
                }
                is VoiceInputState.FinalResult -> {
                    isRecording = false
                    partialVoiceText = state.text
                }
                is VoiceInputState.Error -> {
                    isRecording = false
                    Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_SHORT).show()
                }
                is VoiceInputState.Idle -> {
                    isRecording = false
                }
            }
        }

        fun loadCapabilitiesAndModels() {
            if (!prefs.isConfigured) return
            lifecycleScope.launch {
                apiClient.getDynamicModels().onSuccess { catalog ->
                    dynamicCatalog = catalog
                    val savedModelId = prefs.defaultModelId
                    val matched = catalog.models.firstOrNull { it.id == savedModelId }
                        ?: catalog.models.firstOrNull()
                    matched?.let { selectedModel = it }
                }

                val cwd = currentProject
                apiClient.getSkills(cwd).onSuccess { list ->
                    skills = list.map {
                        val customAlias = prefs.getSkillAlias(it.name)
                        if (!customAlias.isNullOrBlank()) it.copy(alias = customAlias) else it
                    }
                }
                apiClient.getPlugins(cwd).onSuccess { plugins = it }
                apiClient.getMcpServers(cwd).onSuccess { (cfg, srvs) ->
                    mcpConfig = cfg
                    mcpServers = srvs
                }
            }
        }

        fun loadSessions(silent: Boolean = false) {
            if (!prefs.isConfigured) return
            if (!silent) isLoadingSessions = true
            lifecycleScope.launch {
                apiClient.getSessions().onSuccess {
                    sessions = it
                    isGatewayOnline = true
                }.onFailure {
                    isGatewayOnline = false
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "获取会话列表失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                if (!silent) isLoadingSessions = false
            }
        }

        // 增量同步会话快照：当正在运行的任务存在网络抖动、SSE 重连或代理缓冲时，保证数据 100% 实时一致
        suspend fun syncSessionSnapshot(sessionId: String, restoreModelMeta: Boolean = false) {
            val isRunning = apiClient.getAgentRunningState(sessionId).getOrDefault(isAgentBusy)
            apiClient.getSessionDetail(sessionId).onSuccess { hist ->
                isGatewayOnline = true
                if (hist.messages.isNotEmpty()) {
                    val synced = hist.messages.mapIndexed { idx, item ->
                        if (isRunning && idx == hist.messages.lastIndex && item.role == MessageRole.AGENT) {
                            item.copy(isStreaming = true)
                        } else {
                            item
                        }
                    }
                    // 对账同步：若服务端消息数量变化，或最后几条消息的工具输出/Token 用量/正文更新，原地无闪烁替换
                    if (messages.size != synced.size) {
                        messages.clear()
                        messages.addAll(synced)
                    } else {
                        for (i in synced.indices) {
                            if (messages[i] != synced[i]) {
                                messages[i] = synced[i]
                            }
                        }
                    }
                }
                tokenStats = hist.stats
                if (restoreModelMeta) {
                    dynamicCatalog.models.firstOrNull { it.id == hist.modelId }?.let {
                        selectedModel = it
                    }
                    currentThinkingLevel = hist.thinkingLevel
                }
            }
            if (isAgentBusy && !isRunning) {
                isAgentBusy = false
                agentPhaseLabel = null
                keepAliveManager.releaseWakeLock()
                PiForegroundService.stop(this@MainActivity)
                val last = messages.lastOrNull()
                if (last != null && last.isStreaming) {
                    messages[messages.size - 1] = last.copy(isStreaming = false)
                }
            } else if (!isAgentBusy && isRunning) {
                isAgentBusy = true
                if (agentPhaseLabel == null) agentPhaseLabel = "正在等待模型..."
                keepAliveManager.acquireWakeLock()
            }
        }

        fun startListeningEvents(sessionId: String, initialBusy: Boolean = false) {
            sseJob?.cancel()
            sseJob = lifecycleScope.launch {
                isAgentBusy = initialBusy
                agentPhaseLabel = if (initialBusy) "正在等待模型..." else null

                if (initialBusy) {
                    keepAliveManager.acquireWakeLock()
                    PiForegroundService.start(
                        this@MainActivity,
                        title = "piDroid · 智能任务执行中",
                        content = "项目: $currentProject | 正在执行代码任务..."
                    )
                }

                sseClient.listenAgentEvents(sessionId).collect { event ->
                    when (event.type) {
                        "connection_state" -> {
                            sseConnectionState = when (event.connectionState) {
                                "CONNECTED" -> {
                                    isGatewayOnline = true
                                    SseConnectionState.CONNECTED
                                }
                                "RECONNECTING" -> SseConnectionState.RECONNECTING
                                "CONNECTING" -> SseConnectionState.CONNECTING
                                else -> SseConnectionState.DISCONNECTED
                            }
                        }
                        "connected" -> {
                            isGatewayOnline = true
                            if (event.isStreaming == true) {
                                isAgentBusy = true
                                if (agentPhaseLabel == null) agentPhaseLabel = "正在等待模型..."
                                keepAliveManager.acquireWakeLock()
                            }
                        }
                        "reconnected_sync" -> {
                            // SSE 断线重连成功后自动拉取一次快照，补齐断线期间遗漏的 Token 与工具输出
                            syncSessionSnapshot(sessionId, restoreModelMeta = false)
                        }
                        "agent_start" -> {
                            isAgentBusy = true
                            agentPhaseLabel = event.phaseLabel ?: "正在等待模型..."
                            keepAliveManager.acquireWakeLock()
                        }
                        "delta", "message", "text_delta", "content_block_delta", "assistant_delta" -> {
                            val chunk = event.effectiveText
                            if (chunk.isNotEmpty()) {
                                if (!isAgentBusy) {
                                    isAgentBusy = true
                                    keepAliveManager.acquireWakeLock()
                                }
                                agentPhaseLabel = null
                                val last = messages.lastOrNull()
                                if (last != null && last.role == MessageRole.AGENT) {
                                    val updated = last.copy(
                                        content = last.content + chunk,
                                        isStreaming = true
                                    )
                                    messages[messages.size - 1] = updated
                                } else {
                                    messages.add(
                                        ChatItem(
                                            role = MessageRole.AGENT,
                                            content = chunk,
                                            modelName = selectedModel.displayName,
                                            isStreaming = true
                                        )
                                    )
                                }
                            }
                        }
                        "text_replace" -> {
                            val fullText = event.effectiveText
                            if (fullText.isNotEmpty()) {
                                val last = messages.lastOrNull()
                                if (last != null && last.role == MessageRole.AGENT) {
                                    messages[messages.size - 1] = last.copy(
                                        content = fullText,
                                        isStreaming = event.isStreaming ?: false
                                    )
                                }
                            }
                        }
                        "thinking", "thinking_delta" -> {
                            val thinkChunk = event.thinking ?: event.effectiveText
                            if (thinkChunk.isNotEmpty()) {
                                if (!isAgentBusy) isAgentBusy = true
                                agentPhaseLabel = null
                                val last = messages.lastOrNull()
                                if (last != null && last.role == MessageRole.AGENT && last.content.isEmpty()) {
                                    val updated = last.copy(
                                        thinking = (last.thinking ?: "") + thinkChunk,
                                        isStreaming = true
                                    )
                                    messages[messages.size - 1] = updated
                                } else {
                                    messages.add(
                                        ChatItem(
                                            role = MessageRole.AGENT,
                                            content = "",
                                            thinking = thinkChunk,
                                            modelName = selectedModel.displayName,
                                            isStreaming = true
                                        )
                                    )
                                }
                            }
                        }
                        "thinking_replace" -> {
                            val fullThink = event.thinking ?: ""
                            if (fullThink.isNotEmpty()) {
                                val lastIdx = messages.indexOfLast { it.role == MessageRole.AGENT && it.thinking != null }
                                if (lastIdx >= 0) {
                                    val existing = messages[lastIdx]
                                    messages[lastIdx] = existing.copy(thinking = fullThink)
                                }
                            }
                        }
                        "tool", "tool_call", "tool_use", "tool_start" -> {
                            if (!isAgentBusy) isAgentBusy = true
                            val toolName = event.effectiveToolName
                            agentPhaseLabel = event.phaseLabel ?: "正在准备工具 $toolName..."
                            val existingIdx = if (!event.toolCallId.isNullOrBlank()) {
                                messages.indexOfLast { it.role == MessageRole.TOOL && it.toolCall?.toolCallId == event.toolCallId }
                            } else -1
                            if (existingIdx < 0) {
                                messages.add(
                                    ChatItem(
                                        role = MessageRole.TOOL,
                                        content = "",
                                        modelName = selectedModel.displayName,
                                        toolCall = ToolCallInfo(
                                            toolCallId = event.toolCallId,
                                            toolName = toolName,
                                            preview = event.preview,
                                            command = event.effectiveCommand,
                                            output = event.output,
                                            isRunning = true,
                                            durationMs = event.durationMs ?: 0L
                                        )
                                    )
                                )
                            }
                        }
                        "tool_call_ready" -> {
                            val idx = if (!event.toolCallId.isNullOrBlank()) {
                                messages.indexOfLast { it.role == MessageRole.TOOL && it.toolCall?.toolCallId == event.toolCallId }
                            } else {
                                messages.indexOfLast { it.role == MessageRole.TOOL }
                            }
                            if (idx >= 0) {
                                val existing = messages[idx]
                                val existingTool = existing.toolCall ?: ToolCallInfo(toolName = event.effectiveToolName)
                                messages[idx] = existing.copy(
                                    toolCall = existingTool.copy(
                                        toolCallId = event.toolCallId?.takeIf { it.isNotBlank() } ?: existingTool.toolCallId,
                                        toolName = event.effectiveToolName,
                                        preview = event.preview?.takeIf { it.isNotBlank() } ?: existingTool.preview,
                                        command = event.effectiveCommand?.takeIf { it.isNotBlank() } ?: existingTool.command
                                    )
                                )
                            } else {
                                messages.add(
                                    ChatItem(
                                        role = MessageRole.TOOL,
                                        content = "",
                                        modelName = selectedModel.displayName,
                                        toolCall = ToolCallInfo(
                                            toolCallId = event.toolCallId,
                                            toolName = event.effectiveToolName,
                                            preview = event.preview,
                                            command = event.effectiveCommand,
                                            isRunning = true
                                        )
                                    )
                                )
                            }
                        }
                        "tool_execution_start" -> {
                            if (!isAgentBusy) isAgentBusy = true
                            val toolName = event.effectiveToolName
                            agentPhaseLabel = event.phaseLabel ?: "正在执行 $toolName..."
                            val idx = if (!event.toolCallId.isNullOrBlank()) {
                                messages.indexOfLast { it.role == MessageRole.TOOL && it.toolCall?.toolCallId == event.toolCallId }
                            } else {
                                messages.indexOfLast { it.role == MessageRole.TOOL }
                            }
                            if (idx >= 0) {
                                val existing = messages[idx]
                                val existingTool = existing.toolCall
                                if (existingTool != null) {
                                    messages[idx] = existing.copy(
                                        toolCall = existingTool.copy(
                                            preview = event.preview?.takeIf { it.isNotBlank() } ?: existingTool.preview,
                                            command = event.effectiveCommand?.takeIf { it.isNotBlank() } ?: existingTool.command,
                                            isRunning = true
                                        )
                                    )
                                }
                            } else {
                                messages.add(
                                    ChatItem(
                                        role = MessageRole.TOOL,
                                        content = "",
                                        modelName = selectedModel.displayName,
                                        toolCall = ToolCallInfo(
                                            toolCallId = event.toolCallId,
                                            toolName = toolName,
                                            preview = event.preview,
                                            command = event.effectiveCommand,
                                            isRunning = true
                                        )
                                    )
                                )
                            }
                        }
                        "tool_result", "tool_output", "tool_end", "tool_execution_end" -> {
                            val idx = if (!event.toolCallId.isNullOrBlank()) {
                                messages.indexOfLast { it.role == MessageRole.TOOL && it.toolCall?.toolCallId == event.toolCallId }
                            } else {
                                messages.indexOfLast { it.role == MessageRole.TOOL }
                            }
                            if (idx >= 0) {
                                val existing = messages[idx]
                                val existingTool = existing.toolCall
                                if (existingTool != null) {
                                    messages[idx] = existing.copy(
                                        toolCall = existingTool.copy(
                                            output = event.output ?: event.effectiveText.ifBlank { existingTool.output },
                                            durationMs = event.durationMs ?: existingTool.durationMs,
                                            isError = event.isError || existingTool.isError,
                                            isRunning = event.isStreaming ?: false
                                        )
                                    )
                                }
                            }
                            if (event.type == "tool_execution_end") {
                                agentPhaseLabel = event.phaseLabel ?: "正在等待模型..."
                            }
                        }
                        "message_end_sync" -> {
                            val last = messages.lastOrNull()
                            if (last != null) {
                                messages[messages.size - 1] = last.copy(
                                    isStreaming = false,
                                    modelName = event.modelName ?: last.modelName,
                                    usageSummary = event.usageSummary ?: last.usageSummary
                                )
                            }
                            // 后台拉取一次详情，确保 fields、配对结果和 Token 统计 100% 同步
                            lifecycleScope.launch {
                                syncSessionSnapshot(sessionId, restoreModelMeta = false)
                            }
                        }
                        "approval_required" -> {
                            hapticsAndNotify.vibrateApprovalRequired()
                            agentPhaseLabel = "等待人工安全审批..."
                            messages.add(
                                ChatItem(
                                    role = MessageRole.TOOL,
                                    content = "",
                                    toolCall = ToolCallInfo(
                                        toolCallId = event.toolCallId,
                                        toolName = event.effectiveToolName,
                                        preview = event.preview,
                                        command = event.effectiveCommand,
                                        requiresApproval = true,
                                        isApproved = false
                                    )
                                )
                            )
                        }
                        "turn_completed", "done", "message_stop", "agent_idle" -> {
                            isAgentBusy = false
                            agentPhaseLabel = null
                            keepAliveManager.releaseWakeLock()
                            PiForegroundService.stop(this@MainActivity)

                            hapticsAndNotify.vibrateTaskCompleted()
                            hapticsAndNotify.updateTaskNotification("Pi 任务已完成", "所有步骤已执行完毕", isFinished = true)

                            val last = messages.lastOrNull()
                            if (last != null && last.isStreaming) {
                                messages[messages.size - 1] = last.copy(isStreaming = false)
                            }
                            syncSessionSnapshot(sessionId, restoreModelMeta = false)
                        }
                    }
                }
            }
        }

        // 打开任务会话：先建立 SSE 实时监听防止丢包，同时并发拉取完整历史快照
        fun openSessionWithHistory(session: Session) {
            currentSession = session
            currentProject = session.workspaceName
            messages.clear()
            queuedMessages.clear()
            isAgentBusy = session.active
            agentPhaseLabel = if (session.active) "正在等待模型..." else null

            // 立即开启 SSE 监听，避免历史拉取期间漏掉正在产生的流式事件
            startListeningEvents(session.id, session.active)

            lifecycleScope.launch {
                syncSessionSnapshot(session.id, restoreModelMeta = true)
            }
        }

        // 初始加载与后台自动心跳同步：
        // 1) 在首页时每 5 秒静默刷新会话状态（包含正在运行的任务 runningSessionIds 动态图标）與网关健康度
        // 2) 在任务详情页且任务正在运行（或 SSE 重连中）时，每 2 秒执行一次增量快照对账，彻底解决正在跑的任务不实时同步的问题
        LaunchedEffect(prefs.isConfigured, currentSession?.id, isAgentBusy, sseConnectionState) {
            if (!prefs.isConfigured) return@LaunchedEffect
            val activeSession = currentSession
            if (activeSession == null) {
                loadSessions(silent = true)
                loadCapabilitiesAndModels()
                while (true) {
                    kotlinx.coroutines.delay(5_000L)
                    loadSessions(silent = true)
                }
            } else if (isAgentBusy || sseConnectionState == SseConnectionState.RECONNECTING) {
                while (isAgentBusy || sseConnectionState == SseConnectionState.RECONNECTING) {
                    kotlinx.coroutines.delay(2_000L)
                    syncSessionSnapshot(activeSession.id, restoreModelMeta = false)
                }
            }
        }

        fun connectToServer(url: String, token: String, name: String) {
            lifecycleScope.launch {
                apiClient.ping(url, token).onSuccess {
                    prefs.saveConnection(url, token, name)
                    Toast.makeText(this@MainActivity, "连接成功: $name", Toast.LENGTH_SHORT).show()
                    loadSessions()
                    loadCapabilitiesAndModels()
                }.onFailure {
                    Toast.makeText(this@MainActivity, "连接握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        if (showManualConfigDialog) {
            com.pi.app.ui.settings.ServerConfigDialog(
                initialUrl = prefs.serverUrl.ifBlank { "http://192.168.1.100:4100" },
                initialToken = prefs.authToken,
                initialName = prefs.deviceName.ifBlank { "My PC" },
                onConfirm = { url, token, name ->
                    showManualConfigDialog = false
                    connectToServer(url, token, name)
                },
                onDismiss = { showManualConfigDialog = false }
            )
        }

        when {
            isScanning -> {
                QrScannerScreen(
                    onScanSuccess = { url, token, name ->
                        isScanning = false
                        connectToServer(url, token, name)
                    },
                    onManualInputClick = {
                        isScanning = false
                        showManualConfigDialog = true
                    },
                    onClose = { isScanning = false }
                )
            }
            showGlobalSettings -> {
                GlobalSettingsScreen(
                    models = dynamicCatalog.models,
                    currentDefaultModelId = prefs.defaultModelId,
                    currentDefaultThinkingLevel = ThinkingLevel.fromValue(prefs.defaultThinkingLevel),
                    onSetDefaultModel = { model ->
                        prefs.defaultModelId = model.id
                        selectedModel = model
                        Toast.makeText(this@MainActivity, "已设为全局默认模型: ${model.displayName}", Toast.LENGTH_SHORT).show()
                    },
                    onSetDefaultThinkingLevel = { level ->
                        prefs.defaultThinkingLevel = level.value
                        currentThinkingLevel = level
                        Toast.makeText(this@MainActivity, "已设为全局默认思考档位: ${level.value}", Toast.LENGTH_SHORT).show()
                    },
                    skills = skills,
                    onToggleSkill = { name, enabled ->
                        lifecycleScope.launch {
                            skills = skills.map { if (it.name == name) it.copy(enabled = enabled) else it }
                        }
                    },
                    onUpdateSkillAlias = { name, alias ->
                        prefs.saveSkillAlias(name, alias)
                        skills = skills.map { if (it.name == name) it.copy(alias = alias) else it }
                        Toast.makeText(this@MainActivity, "已保存别名: $alias", Toast.LENGTH_SHORT).show()
                    },
                    plugins = plugins,
                    mcpConfig = mcpConfig,
                    mcpServers = mcpServers,
                    isIgnoringBattery = keepAliveManager.isIgnoringBatteryOptimizations(),
                    onRequestIgnoreBattery = { keepAliveManager.requestIgnoreBatteryOptimizations() },
                    serverUrl = prefs.serverUrl,
                    authToken = prefs.authToken,
                    deviceName = prefs.deviceName,
                    onManualConfigConfirm = { url, token, name ->
                        connectToServer(url, token, name)
                    },
                    onScanClick = {
                        showGlobalSettings = false
                        isScanning = true
                    },
                    onDisconnect = {
                        prefs.clear()
                        showGlobalSettings = false
                        Toast.makeText(this@MainActivity, "已断开连接", Toast.LENGTH_SHORT).show()
                    },
                    onClose = { showGlobalSettings = false }
                )
            }
            currentSession != null -> {
                // 工作台任务执行界面
                ChatScreen(
                    sessionTitle = currentSession?.displayTitle ?: "任务控制台",
                    currentProject = currentProject,
                    selectedModel = selectedModel,
                    thinkingLevel = currentThinkingLevel,
                    tokenStats = tokenStats,
                    messages = messages,
                    isAgentBusy = isAgentBusy,
                    agentPhaseLabel = agentPhaseLabel,
                    sseState = sseConnectionState,
                    queuedMessages = queuedMessages,
                    isCompacting = isCompacting,
                    isSpeaking = isSpeaking,
                    onBack = {
                        sseJob?.cancel()
                        currentSession = null
                        agentPhaseLabel = null
                        keepAliveManager.releaseWakeLock()
                        PiForegroundService.stop(this@MainActivity)
                        hapticsAndNotify.clearNotification()
                        smartTtsManager.stop()
                        isSpeaking = false
                        loadSessions() // 返回时刷新会话
                    },
                    onRefreshSession = {
                        currentSession?.let { sess ->
                            lifecycleScope.launch {
                                syncSessionSnapshot(sess.id, restoreModelMeta = true)
                                Toast.makeText(this@MainActivity, "已同步最新任务状态", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenModelSheet = { showModelSheet = true },
                    onOpenCapabilities = { showGlobalSettings = true },
                    onCompactContext = {
                        lifecycleScope.launch {
                            isCompacting = true
                            Toast.makeText(this@MainActivity, "正在智能压缩上下文...", Toast.LENGTH_SHORT).show()
                            apiClient.compactContext(currentSession!!.id).onSuccess {
                                Toast.makeText(this@MainActivity, "上下文压缩完成！", Toast.LENGTH_SHORT).show()
                                apiClient.getSessionStats(currentSession!!.id).onSuccess { tokenStats = it }
                            }
                            isCompacting = false
                        }
                    },
                    onStopAgent = {
                        lifecycleScope.launch {
                            apiClient.stopAgent(currentSession!!.id).onSuccess {
                                isAgentBusy = false
                                agentPhaseLabel = null
                                Toast.makeText(this@MainActivity, "已停止 Agent 执行", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSendMessage = { prompt ->
                        messages.add(ChatItem(role = MessageRole.USER, content = prompt))
                        isAgentBusy = true
                        agentPhaseLabel = "正在等待模型..."
                        lifecycleScope.launch {
                            apiClient.sendCommand(currentSession!!.id, prompt)
                        }
                    },
                    onSendSteer = { steerText ->
                        messages.add(ChatItem(role = MessageRole.USER, content = "➔ [引导] $steerText"))
                        lifecycleScope.launch {
                            apiClient.sendSteer(currentSession!!.id, steerText).onSuccess {
                                Toast.makeText(this@MainActivity, "已下发引导指令", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSendFollowUp = { followText ->
                        queuedMessages.add(QueuedMessage("followUp", followText))
                        lifecycleScope.launch {
                            apiClient.sendFollowUp(currentSession!!.id, followText).onSuccess {
                                Toast.makeText(this@MainActivity, "已排入后续任务队列", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onClearQueue = {
                        lifecycleScope.launch {
                            apiClient.clearQueue(currentSession!!.id).onSuccess {
                                queuedMessages.clear()
                                Toast.makeText(this@MainActivity, "已撤回排队消息", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onApproveTool = { approved ->
                        lifecycleScope.launch {
                            apiClient.sendApproval(currentSession!!.id, approved)
                        }
                    },
                    onToggleSpeakSummary = {
                        if (isSpeaking) {
                            smartTtsManager.stop()
                            isSpeaking = false
                        } else {
                            val lastAgentMsg = messages.lastOrNull { it.role == MessageRole.AGENT }?.content
                            if (!lastAgentMsg.isNullOrBlank()) {
                                isSpeaking = true
                                smartTtsManager.speakSummary(lastAgentMsg)
                            } else {
                                Toast.makeText(this@MainActivity, "暂无可播报内容", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onStartVoiceInput = {
                        voiceInputManager.startListening()
                    },
                    onStopVoiceInput = {
                        voiceInputManager.stopListening()
                    },
                    isVoiceRecording = isRecording,
                    voicePartialText = partialVoiceText
                )

                if (showModelSheet) {
                    ModelAndThinkingSheet(
                        availableModels = dynamicCatalog.models,
                        selectedModelId = selectedModel.id,
                        currentThinkingLevel = currentThinkingLevel,
                        onModelSelected = { model ->
                            selectedModel = model
                            currentSession?.let { sess ->
                                lifecycleScope.launch {
                                    apiClient.updateModel(sess.id, model.provider, model.id)
                                    Toast.makeText(this@MainActivity, "已切换模型: ${model.displayName}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onThinkingLevelChanged = { level ->
                            currentThinkingLevel = level
                            currentSession?.let { sess ->
                                lifecycleScope.launch {
                                    apiClient.updateThinkingLevel(sess.id, level)
                                    Toast.makeText(this@MainActivity, "已切换思考级别: ${level.value}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        onDismiss = { showModelSheet = false }
                    )
                }
            }
            else -> {
                // 首页：工作区与任务管理
                SessionListScreen(
                    deviceName = prefs.deviceName,
                    serverUrl = prefs.serverUrl,
                    isConfigured = prefs.isConfigured,
                    isGatewayOnline = isGatewayOnline,
                    sessions = sessions,
                    isLoading = isLoadingSessions,
                    onRefresh = { loadSessions() },
                    onSessionClick = { session ->
                        openSessionWithHistory(session)
                    },
                    onScanClick = { isScanning = true },
                    onSettingsClick = { showGlobalSettings = true },
                    onNewSessionClick = { wsPath ->
                        val newId = "session_" + System.currentTimeMillis()
                        val newSession = Session(
                            id = newId,
                            name = "新任务会话",
                            cwd = wsPath.ifBlank { "D:/myscript/eldercare-agent-platform" },
                            active = true
                        )
                        currentSession = newSession
                        currentProject = newSession.workspaceName
                        messages.clear()
                        startListeningEvents(newId)
                    },
                    onManualConfigConfirm = { url, token, name ->
                        lifecycleScope.launch {
                            apiClient.ping(url, token).onSuccess {
                                prefs.saveConnection(url, token, name)
                                Toast.makeText(this@MainActivity, "连接成功: $name", Toast.LENGTH_SHORT).show()
                                loadSessions()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "连接握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }

        // 工业级应用内自动更新弹窗 (参考 Termius)
        AppUpdateDialog(
            state = updateUiState,
            onStartDownload = { info ->
                lifecycleScope.launch {
                    updateUiState = UpdateUiState.Downloading(
                        info = info,
                        progress = 0f,
                        downloadedBytes = 0L,
                        totalBytes = info.fileSize,
                        speedText = "连接镜像中...",
                        channelName = "智能优选通道"
                    )
                    val result = AppUpdateManager.downloadApk(this@MainActivity, info) { progress, downloaded, total, speed, channel ->
                        updateUiState = UpdateUiState.Downloading(info, progress, downloaded, total, speed, channel)
                    }
                    result.onSuccess { apkFile ->
                        if (AppUpdateManager.canInstallPackages(this@MainActivity)) {
                            updateUiState = UpdateUiState.ReadyToInstall(apkFile, info)
                            AppUpdateManager.installApk(this@MainActivity, apkFile)
                        } else {
                            updateUiState = UpdateUiState.PermissionRequired(apkFile, info)
                        }
                    }.onFailure { err ->
                        updateUiState = UpdateUiState.Error(err.message ?: "下载失败", info)
                    }
                }
            },
            onInstall = { file ->
                if (AppUpdateManager.canInstallPackages(this@MainActivity)) {
                    AppUpdateManager.installApk(this@MainActivity, file)
                } else {
                    val currentInfo = (updateUiState as? UpdateUiState.ReadyToInstall)?.info
                    if (currentInfo != null) {
                        updateUiState = UpdateUiState.PermissionRequired(file, currentInfo)
                    }
                }
            },
            onIgnore = { tagName ->
                AppUpdateManager.ignoreVersion(this@MainActivity, tagName)
                updateUiState = UpdateUiState.Idle
            },
            onDismiss = {
                updateUiState = UpdateUiState.Idle
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputManager.destroy()
        smartTtsManager.shutdown()
        keepAliveManager.releaseWakeLock()
    }
}
