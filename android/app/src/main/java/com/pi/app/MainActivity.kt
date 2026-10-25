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
import com.pi.app.ui.updater.UpdateDialog
import com.pi.app.updater.ApkInstaller
import com.pi.app.updater.UpdateChecker
import com.pi.app.updater.UpdateInfo
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
    private lateinit var updateChecker: UpdateChecker
    private lateinit var apkInstaller: ApkInstaller

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
        updateChecker = UpdateChecker(currentVersion = "1.0.0")
        apkInstaller = ApkInstaller(this)

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

        // 应用内更新状态
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isDownloadingUpdate by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var downloadedApkFile by remember { mutableStateOf<File?>(null) }

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
        var isCompacting by remember { mutableStateOf(false) }
        var isSpeaking by remember { mutableStateOf(false) }

        val voiceState by voiceInputManager.state.collectAsState()
        var isRecording by remember { mutableStateOf(false) }
        var partialVoiceText by remember { mutableStateOf("") }

        // 启动时自动检查更新
        LaunchedEffect(Unit) {
            updateChecker.checkForUpdates().onSuccess { info ->
                if (info.hasNewVersion) {
                    updateInfo = info
                }
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
                    // 填充用户自定义的技能别名
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

        fun loadSessions() {
            if (!prefs.isConfigured) return
            isLoadingSessions = true
            lifecycleScope.launch {
                apiClient.getSessions().onSuccess {
                    sessions = it
                }.onFailure {
                    Toast.makeText(this@MainActivity, "获取会话列表失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                isLoadingSessions = false
            }
        }

        fun startListeningEvents(sessionId: String, initialBusy: Boolean = false) {
            sseJob?.cancel()
            sseJob = lifecycleScope.launch {
                isAgentBusy = initialBusy

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
                        "delta", "message" -> {
                            if (!isAgentBusy) {
                                isAgentBusy = true
                                keepAliveManager.acquireWakeLock()
                            }
                            val last = messages.lastOrNull()
                            if (last != null && last.role == MessageRole.AGENT && last.isStreaming) {
                                val updated = last.copy(content = last.content + (event.delta ?: ""))
                                messages[messages.size - 1] = updated
                            } else {
                                messages.add(
                                    ChatItem(
                                        role = MessageRole.AGENT,
                                        content = event.delta ?: "",
                                        isStreaming = true
                                    )
                                )
                            }
                        }
                        "thinking" -> {
                            if (!isAgentBusy) isAgentBusy = true
                            val last = messages.lastOrNull()
                            if (last != null && last.role == MessageRole.AGENT) {
                                val updated = last.copy(thinking = (last.thinking ?: "") + (event.thinking ?: ""))
                                messages[messages.size - 1] = updated
                            }
                        }
                        "tool" -> {
                            if (!isAgentBusy) isAgentBusy = true
                            messages.add(
                                ChatItem(
                                    role = MessageRole.TOOL,
                                    content = "",
                                    toolCall = ToolCallInfo(
                                        toolName = event.tool ?: "Bash",
                                        command = event.command,
                                        output = event.output
                                    )
                                )
                            )
                        }
                        "approval_required" -> {
                            hapticsAndNotify.vibrateApprovalRequired()
                            messages.add(
                                ChatItem(
                                    role = MessageRole.TOOL,
                                    content = "",
                                    toolCall = ToolCallInfo(
                                        toolName = event.tool ?: "System",
                                        command = event.command,
                                        requiresApproval = true,
                                        isApproved = false
                                    )
                                )
                            )
                        }
                        "turn_completed" -> {
                            isAgentBusy = false
                            keepAliveManager.releaseWakeLock()
                            PiForegroundService.stop(this@MainActivity)

                            hapticsAndNotify.vibrateTaskCompleted()
                            hapticsAndNotify.updateTaskNotification("Pi 任务已完成", "所有步骤已执行完毕", isFinished = true)

                            val last = messages.lastOrNull()
                            if (last != null && last.isStreaming) {
                                messages[messages.size - 1] = last.copy(isStreaming = false)
                            }
                            apiClient.getSessionStats(sessionId).onSuccess { tokenStats = it }
                        }
                    }
                }
            }
        }

        // 打开任务会话：拉取完整历史并探知真实状态
        fun openSessionWithHistory(session: Session) {
            currentSession = session
            currentProject = session.workspaceName
            messages.clear()
            queuedMessages.clear()

            lifecycleScope.launch {
                // 1. 调用 GET /api/sessions/[id] 获取完整历史消息与元数据
                apiClient.getSessionDetail(session.id).onSuccess { hist ->
                    messages.addAll(hist.messages)
                    tokenStats = hist.stats

                    // 还原任务上次使用的模型
                    dynamicCatalog.models.firstOrNull { it.id == hist.modelId }?.let {
                        selectedModel = it
                    }
                    currentThinkingLevel = hist.thinkingLevel
                }

                // 2. 探测 Agent 是否真正处于运行中
                val isRunning = apiClient.getAgentRunningState(session.id).getOrDefault(false)
                isAgentBusy = isRunning

                // 3. 开启增量监听并进入会话
                startListeningEvents(session.id, isRunning)
            }
        }

        LaunchedEffect(prefs.isConfigured) {
            if (prefs.isConfigured) {
                loadSessions()
                loadCapabilitiesAndModels()
            }
        }

        when {
            isScanning -> {
                QrScannerScreen(
                    onScanSuccess = { url, token, name ->
                        isScanning = false
                        lifecycleScope.launch {
                            apiClient.ping(url, token).onSuccess {
                                prefs.saveConnection(url, token, name)
                                Toast.makeText(this@MainActivity, "连接成功: $name", Toast.LENGTH_SHORT).show()
                                loadSessions()
                                loadCapabilitiesAndModels()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
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
                    queuedMessages = queuedMessages,
                    isCompacting = isCompacting,
                    isSpeaking = isSpeaking,
                    onBack = {
                        sseJob?.cancel()
                        currentSession = null
                        keepAliveManager.releaseWakeLock()
                        PiForegroundService.stop(this@MainActivity)
                        hapticsAndNotify.clearNotification()
                        smartTtsManager.stop()
                        isSpeaking = false
                        loadSessions() // 返回时刷新会话
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
                                Toast.makeText(this@MainActivity, "已停止 Agent 执行", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onSendMessage = { prompt ->
                        messages.add(ChatItem(role = MessageRole.USER, content = prompt))
                        isAgentBusy = true
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

        // 应用内平滑更新弹窗
        updateInfo?.let { info ->
            UpdateDialog(
                updateInfo = info,
                isDownloading = isDownloadingUpdate,
                downloadProgress = downloadProgress,
                isDownloaded = downloadedApkFile != null,
                onStartDownload = {
                    isDownloadingUpdate = true
                    lifecycleScope.launch {
                        apkInstaller.downloadApk(info.downloadUrl) { progress ->
                            downloadProgress = progress
                        }.onSuccess { file ->
                            isDownloadingUpdate = false
                            downloadedApkFile = file
                            apkInstaller.installApk(file)
                        }.onFailure { err ->
                            isDownloadingUpdate = false
                            Toast.makeText(this@MainActivity, "下载更新失败: ${err.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                onInstall = {
                    downloadedApkFile?.let { apkInstaller.installApk(it) }
                },
                onDismiss = { updateInfo = null }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceInputManager.destroy()
        smartTtsManager.shutdown()
        keepAliveManager.releaseWakeLock()
    }
}
