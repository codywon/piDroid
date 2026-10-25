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
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: PiApiClient
    private lateinit var sseClient: PiSseClient
    private lateinit var hapticsAndNotify: PiHapticsAndNotify
    private lateinit var keepAliveManager: KeepAliveManager

    private var sseJob: Job? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // 权限回调完成后，若已开启常驻通知栏保活则确保前台服务启动
        syncPersistentForegroundService(isBusy = false, project = "默认工作区")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 开启标准 Edge-to-Edge 软键盘窗口自适应，配合 ChatScreen bottomBar 的 imePadding() 将输入框平滑顶起
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = AppPreferences(this)
        apiClient = PiApiClient(prefs)
        sseClient = PiSseClient(prefs)
        hapticsAndNotify = PiHapticsAndNotify(this)
        keepAliveManager = KeepAliveManager(this)

        // 初始化主题管理
        com.pi.app.ui.theme.ThemeManager.init(this)

        checkAndRequestPermissions()
        handleDeepLink(intent)
        syncPersistentForegroundService(isBusy = false, project = "默认工作区")

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

    private fun syncPersistentForegroundService(isBusy: Boolean, project: String, phase: String? = null) {
        if (!prefs.isConfigured) {
            PiForegroundService.stop(this)
            return
        }
        if (prefs.persistentNotifyEnabled || isBusy) {
            val title = if (isBusy) "piDroid · 智能任务执行中" else "piDroid · 网关常驻守护中"
            val content = if (isBusy) {
                "项目: $project | ${phase ?: "正在流式同步执行..."}"
            } else {
                "主机: ${prefs.deviceName.ifBlank { prefs.serverUrl }} | 随时接收远程指令"
            }
            PiForegroundService.start(this, title = title, content = content)
        } else {
            PiForegroundService.stop(this)
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
                    syncPersistentForegroundService(isBusy = false, project = "默认工作区")
                    Toast.makeText(this@MainActivity, "已成功绑定设备: $name", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(this@MainActivity, "连接握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
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

        // 应用内自动更新状态
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
        var tokenStats by remember {
            mutableStateOf(
                TokenStats(
                    contextTokens = 42100,
                    maxContextWindow = 200000,
                    inputTokens = 1240,
                    outputTokens = 412,
                    cacheReadTokens = 38000,
                    cost = 0.0
                )
            )
        }
        var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
        var plugins by remember { mutableStateOf<List<PluginPackage>>(emptyList()) }
        var mcpConfig by remember { mutableStateOf(McpCodeModeConfig()) }
        var mcpServers by remember { mutableStateOf<List<McpServerInfo>>(emptyList()) }

        var persistentNotifyEnabled by remember { mutableStateOf(prefs.persistentNotifyEnabled) }
        var hapticsEnabled by remember { mutableStateOf(prefs.hapticsEnabled) }

        val messages = remember { mutableStateListOf<ChatItem>() }
        val queuedMessages = remember { mutableStateListOf<QueuedMessage>() }
        var isAgentBusy by remember { mutableStateOf(false) }
        var agentPhaseLabel by remember { mutableStateOf<String?>(null) }
        var isCompacting by remember { mutableStateOf(false) }
        var isGatewayOnline by remember { mutableStateOf(true) }
        var sseConnectionState by remember { mutableStateOf(SseConnectionState.DISCONNECTED) }

        // 启动时静默检查更新
        LaunchedEffect(Unit) {
            when (val result = AppUpdateManager.checkUpdate(this@MainActivity, isManual = false)) {
                is UpdateCheckResult.HasUpdate -> {
                    updateUiState = UpdateUiState.HasUpdate(result.info)
                }
                else -> {}
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

        // 声明 startListeningEvents 以便在 syncSessionSnapshot 检测到 PC 端新启动任务时自动重连 SSE
        var reattachSseCallback: ((String, Boolean) -> Unit)? = null

        // 增量无损同步会话快照：
        // 1) 保留本地正在流式接收 (isStreaming = true) 或正在执行的工具尾部项，绝不因磁盘尚未落盘而抹掉实时流式文本（彻底解决“挤牙膏”与闪烁问题）
        // 2) 当检测到 PC 端刚启动任务 (!isAgentBusy && isRunning) 时，立即自动挂载 SSE 实时流
        // 3) 当检测到任务从运行态变为完成态 (isAgentBusy && !isRunning) 时，自动触发马达完成震动
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

                    // 比较两条消息的实质内容是否一致（忽略 id 与 timestamp），并保持既有 item.id 不变，彻底消除轮询闪屏
                    fun isSameChatContent(a: ChatItem, b: ChatItem): Boolean {
                        return a.role == b.role &&
                            a.content == b.content &&
                            a.thinking == b.thinking &&
                            a.isStreaming == b.isStreaming &&
                            a.toolCall == b.toolCall &&
                            a.usageSummary == b.usageSummary
                    }

                    // 保存当前内存中尚未落盘的流式尾部消息（例如 SSE 正在推送 delta，但服务端 .jsonl 只在 message_end 才写入）
                    val inFlightTail = if (isRunning && messages.isNotEmpty()) {
                        val tail = messages.last()
                        val isInFlightItem = tail.isStreaming || (tail.role == MessageRole.TOOL && tail.toolCall?.isRunning == true)
                        if (isInFlightItem && messages.size > synced.size) tail else null
                    } else null

                    when {
                        messages.isEmpty() -> {
                            messages.addAll(synced)
                        }
                        synced.size > messages.size -> {
                            for (i in messages.indices) {
                                val localItem = messages[i]
                                val diskItem = synced[i]
                                if (!isSameChatContent(localItem, diskItem)) {
                                    messages[i] = diskItem.copy(id = localItem.id, timestamp = localItem.timestamp)
                                }
                            }
                            for (i in messages.size until synced.size) {
                                messages.add(synced[i])
                            }
                        }
                        synced.size == messages.size -> {
                            for (i in synced.indices) {
                                val localItem = messages[i]
                                val diskItem = synced[i]
                                // 如果最后一条正在 SSE 流式输出且本地累积字符比磁盘快照更新，保留本地流式内容，防止回退卡顿
                                if (i == synced.lastIndex && localItem.isStreaming && localItem.role == MessageRole.AGENT && diskItem.role == MessageRole.AGENT) {
                                    val mergedContent = if (localItem.content.length >= diskItem.content.length) localItem.content else diskItem.content
                                    val mergedThinking = if ((localItem.thinking?.length ?: 0) >= (diskItem.thinking?.length ?: 0)) localItem.thinking else diskItem.thinking
                                    val updated = diskItem.copy(
                                        id = localItem.id,
                                        timestamp = localItem.timestamp,
                                        content = mergedContent,
                                        thinking = mergedThinking,
                                        isStreaming = isRunning
                                    )
                                    if (!isSameChatContent(localItem, updated)) {
                                        messages[i] = updated
                                    }
                                } else if (!isSameChatContent(localItem, diskItem)) {
                                    messages[i] = diskItem.copy(id = localItem.id, timestamp = localItem.timestamp)
                                }
                            }
                        }
                        else -> {
                            // messages.size > synced.size：说明本地有乐观发送的消息或正在流式输出的尾部消息
                            if (isRunning && inFlightTail != null && messages.size == synced.size + 1) {
                                for (i in synced.indices) {
                                    val localItem = messages[i]
                                    val diskItem = synced[i]
                                    if (!isSameChatContent(localItem, diskItem)) {
                                        messages[i] = diskItem.copy(id = localItem.id, timestamp = localItem.timestamp)
                                    }
                                }
                                messages[synced.size] = inFlightTail
                            } else if (!isRunning) {
                                // 保持已存在索引的 id 稳定，仅截断多余尾部
                                for (i in synced.indices) {
                                    val localItem = messages[i]
                                    val diskItem = synced[i]
                                    if (!isSameChatContent(localItem, diskItem)) {
                                        messages[i] = diskItem.copy(id = localItem.id, timestamp = localItem.timestamp)
                                    }
                                }
                                while (messages.size > synced.size) {
                                    messages.removeAt(messages.lastIndex)
                                }
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
                syncPersistentForegroundService(isBusy = false, project = currentProject)

                if (prefs.hapticsEnabled) {
                    hapticsAndNotify.vibrateTaskCompleted()
                }
                hapticsAndNotify.updateTaskNotification("Pi 任务已完成", "所有步骤已执行完毕", isFinished = true)

                val last = messages.lastOrNull()
                if (last != null && last.isStreaming) {
                    messages[messages.size - 1] = last.copy(isStreaming = false)
                }
            } else if (!isAgentBusy && isRunning) {
                // PC 端或远端刚启动了任务：立即切换为运行态并重新挂载 SSE 实时流
                isAgentBusy = true
                if (agentPhaseLabel == null) agentPhaseLabel = "正在同步实时输出..."
                keepAliveManager.acquireWakeLock()
                syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
                reattachSseCallback?.invoke(sessionId, true)
            }
        }

        fun startListeningEvents(sessionId: String, initialBusy: Boolean = false) {
            sseJob?.cancel()
            sseJob = lifecycleScope.launch {
                isAgentBusy = initialBusy
                agentPhaseLabel = if (initialBusy) "正在等待模型..." else null

                if (initialBusy) {
                    keepAliveManager.acquireWakeLock()
                    syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
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
                                if (agentPhaseLabel == null) agentPhaseLabel = "正在接收实时流..."
                                keepAliveManager.acquireWakeLock()
                                syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
                            }
                        }
                        "reconnected_sync" -> {
                            syncSessionSnapshot(sessionId, restoreModelMeta = false)
                        }
                        "agent_start" -> {
                            isAgentBusy = true
                            agentPhaseLabel = event.phaseLabel ?: "正在等待模型..."
                            keepAliveManager.acquireWakeLock()
                            syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
                        }
                        "delta", "message", "text_delta", "content_block_delta", "assistant_delta" -> {
                            val chunk = event.effectiveText
                            if (chunk.isNotEmpty()) {
                                if (!isAgentBusy) {
                                    isAgentBusy = true
                                    keepAliveManager.acquireWakeLock()
                                    syncPersistentForegroundService(isBusy = true, project = currentProject, phase = "正在生成回复...")
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
                            syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
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
                            lifecycleScope.launch {
                                syncSessionSnapshot(sessionId, restoreModelMeta = false)
                            }
                        }
                        "approval_required" -> {
                            if (prefs.hapticsEnabled) {
                                hapticsAndNotify.vibrateApprovalRequired()
                            }
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
                            val wasBusy = isAgentBusy
                            isAgentBusy = false
                            agentPhaseLabel = null
                            keepAliveManager.releaseWakeLock()
                            syncPersistentForegroundService(isBusy = false, project = currentProject)

                            if (wasBusy && prefs.hapticsEnabled) {
                                hapticsAndNotify.vibrateTaskCompleted()
                            }
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

        reattachSseCallback = { sid, busy ->
            startListeningEvents(sid, initialBusy = busy)
        }

        // 打开任务会话：先建立 SSE 实时监听防止丢包，同时并发拉取完整历史快照
        fun openSessionWithHistory(session: Session) {
            currentSession = session
            currentProject = session.workspaceName
            messages.clear()
            queuedMessages.clear()
            isAgentBusy = session.active
            agentPhaseLabel = if (session.active) "正在等待模型..." else null

            startListeningEvents(session.id, session.active)

            lifecycleScope.launch {
                syncSessionSnapshot(session.id, restoreModelMeta = true)
            }
        }

        // 全场景实时同步循环：
        // 1) 在首页时每 4 秒静默刷新会话状态（包含正在运行的任务 runningSessionIds 动态图标）与网关健康度
        // 2) 在任务详情页时：无论当前是运行态还是空闲态，均保持轻量级增量对账（运行中 1.5s，空闲中 2.5s），
        //    确保在 PC 端发消息或运行工具时，手机端无需点击右上角同步按钮即可自动捕获并实时流式呈现
        LaunchedEffect(prefs.isConfigured, currentSession?.id) {
            if (!prefs.isConfigured) return@LaunchedEffect
            val activeSession = currentSession
            if (activeSession == null) {
                loadSessions(silent = true)
                loadCapabilitiesAndModels()
                while (true) {
                    kotlinx.coroutines.delay(4_000L)
                    loadSessions(silent = true)
                }
            } else {
                while (true) {
                    val delayMs = if (isAgentBusy || sseConnectionState == SseConnectionState.RECONNECTING) 1_500L else 2_500L
                    kotlinx.coroutines.delay(delayMs)
                    syncSessionSnapshot(activeSession.id, restoreModelMeta = false)
                }
            }
        }

        fun connectToServer(url: String, token: String, name: String) {
            lifecycleScope.launch {
                apiClient.ping(url, token).onSuccess {
                    prefs.saveConnection(url, token, name)
                    syncPersistentForegroundService(isBusy = false, project = currentProject)
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
                    onRefreshModels = {
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在从网关刷新模型目录...", Toast.LENGTH_SHORT).show()
                            apiClient.refreshModelsCatalog().onSuccess { catalog ->
                                dynamicCatalog = catalog
                                Toast.makeText(this@MainActivity, "已同步 ${catalog.models.size} 个模型", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                loadCapabilitiesAndModels()
                            }
                        }
                    },
                    onSaveProviderApiKey = { provider, apiKey ->
                        lifecycleScope.launch {
                            apiClient.setProviderApiKey(provider, apiKey).onSuccess {
                                Toast.makeText(this@MainActivity, "已保存 $provider API Key", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "保存失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onSaveCustomModel = { providerName, baseUrl, apiKey, apiType, modelId, modelName, contextWindow, reasoning, supportsVision ->
                        // 立即在本地模型列表中乐观生效，同时同步至网关
                        val updatedModel = ModelInfo(
                            id = modelId,
                            name = modelName.ifBlank { modelId },
                            provider = providerName,
                            supportsVision = supportsVision,
                            supportsThinking = reasoning,
                            contextWindow = contextWindow
                        )
                        val existingIdx = dynamicCatalog.models.indexOfFirst { it.provider == providerName && it.id == modelId }
                        val updatedList = if (existingIdx >= 0) {
                            dynamicCatalog.models.toMutableList().apply { set(existingIdx, updatedModel) }
                        } else {
                            dynamicCatalog.models + updatedModel
                        }
                        dynamicCatalog = dynamicCatalog.copy(models = updatedList)

                        lifecycleScope.launch {
                            apiClient.upsertCustomProviderModel(
                                providerName = providerName,
                                baseUrlEndpoint = baseUrl,
                                apiKey = apiKey,
                                apiType = apiType,
                                modelId = modelId,
                                modelName = modelName,
                                contextWindow = contextWindow,
                                reasoning = reasoning,
                                supportsVision = supportsVision
                            ).onSuccess {
                                Toast.makeText(this@MainActivity, "已保存模型配置: $modelId", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "已更新本地模型配置: $modelId", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onDeleteModel = { providerName, modelId ->
                        dynamicCatalog = dynamicCatalog.copy(
                            models = dynamicCatalog.models.filterNot { it.provider == providerName && it.id == modelId }
                        )
                        lifecycleScope.launch {
                            apiClient.deleteCustomModel(providerName, modelId).onSuccess {
                                Toast.makeText(this@MainActivity, "已删除模型: $modelId", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "已移除模型: $modelId", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onLoadModelsRawConfig = {
                        apiClient.getModelsRawConfig().getOrNull()
                    },
                    onSaveModelsRawConfig = { rawJson ->
                        lifecycleScope.launch {
                            apiClient.saveModelsRawConfig(rawJson).onSuccess {
                                Toast.makeText(this@MainActivity, "已保存 models.json 并刷新目录", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "保存 models.json 失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    skills = skills,
                    onToggleSkill = { skill, enabled ->
                        skills = skills.map { if (it.name == skill.name) it.copy(enabled = enabled) else it }
                        lifecycleScope.launch {
                            apiClient.toggleSkillOnServer(skill.filePath, disableModelInvocation = !enabled).onSuccess {
                                Toast.makeText(
                                    this@MainActivity,
                                    if (enabled) "已启用技能: ${skill.name}" else "已禁用技能: ${skill.name}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onUpdateSkillAlias = { name, alias ->
                        prefs.saveSkillAlias(name, alias)
                        skills = skills.map { if (it.name == name) it.copy(alias = alias) else it }
                        Toast.makeText(this@MainActivity, "已保存别名: $alias", Toast.LENGTH_SHORT).show()
                    },
                    onLoadSkillContent = { filePath ->
                        apiClient.getSkillContent(filePath, currentProject).getOrNull()
                    },
                    onSaveSkillContent = { skill, content ->
                        lifecycleScope.launch {
                            apiClient.saveSkillContent(skill.filePath, content, currentProject).onSuccess {
                                Toast.makeText(this@MainActivity, "已保存 SKILL.md 源码修改", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "已更新本地技能指令", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onInstallSkill = { pkgSource, scope ->
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在安装技能包: $pkgSource...", Toast.LENGTH_SHORT).show()
                            apiClient.installSkill(pkgSource, scope, currentProject).onSuccess {
                                Toast.makeText(this@MainActivity, "已成功安装技能包: $pkgSource", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "安装失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    plugins = plugins,
                    onManagePlugin = { action, source, scope ->
                        // 本地立即乐观更新启用/卸载状态
                        when (action) {
                            "enable" -> plugins = plugins.map { if ((it.source ?: it.packageName) == source) it.copy(disabled = false) else it }
                            "disable" -> plugins = plugins.map { if ((it.source ?: it.packageName) == source) it.copy(disabled = true) else it }
                            "remove" -> plugins = plugins.filterNot { (it.source ?: it.packageName) == source }
                        }
                        lifecycleScope.launch {
                            apiClient.managePluginAction(action, source, scope, currentProject).onSuccess {
                                Toast.makeText(this@MainActivity, "插件操作已生效 ($action): $source", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "插件状态已更新 ($action)", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onEditPlugin = { oldPkg, newSource, newVersion, newDesc, newScope ->
                        val updatedName = newSource.removePrefix("npm:").removePrefix("git:").substringAfterLast("/").ifBlank { oldPkg.packageName }
                        plugins = plugins.map {
                            if (it.packageName == oldPkg.packageName) {
                                it.copy(
                                    packageName = updatedName,
                                    version = newVersion,
                                    description = newDesc,
                                    source = newSource,
                                    scope = newScope
                                )
                            } else it
                        }
                        lifecycleScope.launch {
                            apiClient.managePluginAction("install", newSource, newScope, currentProject)
                            Toast.makeText(this@MainActivity, "已保存插件配置: $updatedName", Toast.LENGTH_SHORT).show()
                        }
                    },
                    mcpConfig = mcpConfig,
                    mcpServers = mcpServers,
                    onAddMcpServer = { name, transport, endpoint, description, scope ->
                        val updatedServer = McpServerInfo(
                            name = name,
                            description = description.ifBlank { "$transport: $endpoint" },
                            transport = transport,
                            url = endpoint,
                            enabled = true,
                            toolExposureCount = 1
                        )
                        val existingIdx = mcpServers.indexOfFirst { it.name == name }
                        mcpServers = if (existingIdx >= 0) {
                            mcpServers.toMutableList().apply { set(existingIdx, updatedServer) }
                        } else {
                            mcpServers + updatedServer
                        }
                        lifecycleScope.launch {
                            apiClient.addMcpServer(name, transport, endpoint, description, scope, currentProject).onSuccess {
                                Toast.makeText(this@MainActivity, "已保存 MCP 服务: $name", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "已更新 MCP 节点配置: $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onManageMcpServer = { action, name ->
                        when (action) {
                            "enable" -> mcpServers = mcpServers.map { if (it.name == name) it.copy(enabled = true) else it }
                            "disable" -> mcpServers = mcpServers.map { if (it.name == name) it.copy(enabled = false) else it }
                            "remove" -> mcpServers = mcpServers.filterNot { it.name == name }
                        }
                        lifecycleScope.launch {
                            apiClient.manageMcpServerAction(action, name, currentProject).onSuccess {
                                Toast.makeText(this@MainActivity, "MCP 操作已生效: $name", Toast.LENGTH_SHORT).show()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "已更新 MCP 节点状态: $name", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUpdateMcpCodeMode = { mode, scriptMode, tokenBudget ->
                        mcpConfig = mcpConfig.copy(mode = mode, scriptMode = scriptMode, tokenBudget = tokenBudget)
                        lifecycleScope.launch {
                            apiClient.updateMcpCodeMode(mode, scriptMode, tokenBudget, currentProject)
                            Toast.makeText(this@MainActivity, "已保存 MCP 代码沙箱配置", Toast.LENGTH_SHORT).show()
                        }
                    },
                    isIgnoringBattery = keepAliveManager.isIgnoringBatteryOptimizations(),
                    onRequestIgnoreBattery = { keepAliveManager.requestIgnoreBatteryOptimizations() },
                    persistentNotifyEnabled = persistentNotifyEnabled,
                    onTogglePersistentNotify = { enabled ->
                        persistentNotifyEnabled = enabled
                        prefs.persistentNotifyEnabled = enabled
                        syncPersistentForegroundService(isBusy = isAgentBusy, project = currentProject, phase = agentPhaseLabel)
                        Toast.makeText(
                            this@MainActivity,
                            if (enabled) "已开启常驻通知栏保活守护" else "已关闭常驻通知栏（仅在任务运行时启动前台服务）",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    hapticsEnabled = hapticsEnabled,
                    onToggleHaptics = { enabled ->
                        hapticsEnabled = enabled
                        prefs.hapticsEnabled = enabled
                        if (enabled) {
                            hapticsAndNotify.vibrateTaskCompleted()
                        }
                    },
                    onTestHaptics = {
                        hapticsAndNotify.vibrateTaskCompleted()
                        Toast.makeText(this@MainActivity, "已触发任务完成双连震动测试", Toast.LENGTH_SHORT).show()
                    },
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
                        PiForegroundService.stop(this@MainActivity)
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
                    onBack = {
                        sseJob?.cancel()
                        currentSession = null
                        agentPhaseLabel = null
                        keepAliveManager.releaseWakeLock()
                        syncPersistentForegroundService(isBusy = false, project = currentProject)
                        hapticsAndNotify.clearNotification()
                        loadSessions()
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
                                keepAliveManager.releaseWakeLock()
                                syncPersistentForegroundService(isBusy = false, project = currentProject)
                                Toast.makeText(this@MainActivity, "已停止 Agent 执行", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSendMessage = { prompt ->
                        val sid = currentSession!!.id
                        messages.add(ChatItem(role = MessageRole.USER, content = prompt))
                        isAgentBusy = true
                        agentPhaseLabel = "正在等待模型..."
                        keepAliveManager.acquireWakeLock()
                        syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
                        // 确保 SSE 已连接
                        if (sseConnectionState != SseConnectionState.CONNECTED) {
                            startListeningEvents(sid, initialBusy = true)
                        }
                        lifecycleScope.launch {
                            apiClient.sendCommand(sid, prompt)
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
                    }
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
                        connectToServer(url, token, name)
                    }
                )
            }
        }

        // 应用内自动更新弹窗
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
        keepAliveManager.releaseWakeLock()
    }
}
