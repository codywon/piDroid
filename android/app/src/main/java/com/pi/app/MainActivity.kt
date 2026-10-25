package com.pi.app

import android.Manifest
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
import com.pi.app.network.PiApiClient
import com.pi.app.network.PiSseClient
import com.pi.app.notification.PiHapticsAndNotify
import com.pi.app.ui.capabilities.CapabilitiesCenterScreen
import com.pi.app.ui.chat.ChatScreen
import com.pi.app.ui.chat.ModelAndThinkingSheet
import com.pi.app.ui.scanner.QrScannerScreen
import com.pi.app.ui.session.SessionListScreen
import com.pi.app.ui.settings.ServerConfigDialog
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

    // 参考 Termius / MQTT 助手：保活与应用内更新引擎
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
        updateChecker = UpdateChecker(currentVersion = "1.4.0")
        apkInstaller = ApkInstaller(this)

        checkAndRequestPermissions()

        setContent {
            com.pi.app.ui.theme.TermiusColorScheme.let { scheme ->
                MaterialTheme(colorScheme = scheme) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = com.pi.app.ui.theme.TermiusBlack
                    ) {
                        MainAppNav()
                    }
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
        var showSettings by remember { mutableStateOf(false) } // 首次打开绝不弹窗打扰用户
        var showCapabilities by remember { mutableStateOf(false) }
        var showModelSheet by remember { mutableStateOf(false) }

        // 应用内更新状态
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isDownloadingUpdate by remember { mutableStateOf(false) }
        var downloadProgress by remember { mutableFloatStateOf(0f) }
        var downloadedApkFile by remember { mutableStateOf<File?>(null) }

        var currentSession by remember { mutableStateOf<Session?>(null) }
        var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
        var isLoadingSessions by remember { mutableStateOf(false) }

        var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
        var selectedModel by remember {
            mutableStateOf(ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", 200000, 8192, true, true))
        }
        var currentThinkingLevel by remember { mutableStateOf(ThinkingLevel.HIGH) }
        var currentProject by remember { mutableStateOf("eldercare-agent-platform") }
        var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
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

        // 启动时自动检查更新 (参考 Termius)
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
                apiClient.getModels().onSuccess { models ->
                    availableModels = models
                    models.firstOrNull()?.let { selectedModel = it }
                }
                apiClient.getProjects().onSuccess { projs ->
                    projects = projs
                    projs.firstOrNull { it.isCurrent }?.let { currentProject = it.name }
                }
                val cwd = currentProject
                apiClient.getSkills(cwd).onSuccess { skills = it }
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
                    Toast.makeText(this@MainActivity, "获取会话失败: ${it.message}", Toast.LENGTH_SHORT).show()
                }
                isLoadingSessions = false
            }
        }

        fun startListeningEvents(sessionId: String) {
            sseJob?.cancel()
            sseJob = lifecycleScope.launch {
                isAgentBusy = true

                // 参考 Termius / MQTT 助手：启动唤醒锁与前台服务保活！
                keepAliveManager.acquireWakeLock()
                PiForegroundService.start(
                    this@MainActivity,
                    title = "piDroid · 任务运行中",
                    content = "项目: $currentProject | 正在执行代码长任务..."
                )

                apiClient.getSessionStats(sessionId).onSuccess { tokenStats = it }

                sseClient.listenAgentEvents(sessionId).collect { event ->
                    when (event.type) {
                        "delta", "message" -> {
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
                            val last = messages.lastOrNull()
                            if (last != null && last.role == MessageRole.AGENT) {
                                val updated = last.copy(thinking = (last.thinking ?: "") + (event.thinking ?: ""))
                                messages[messages.size - 1] = updated
                            }
                        }
                        "tool" -> {
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
                            // 任务完成：释放唤醒锁省电，触发触觉震动两次
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
            showCapabilities -> {
                CapabilitiesCenterScreen(
                    models = availableModels,
                    skills = skills,
                    plugins = plugins,
                    mcpConfig = mcpConfig,
                    mcpServers = mcpServers,
                    isIgnoringBattery = keepAliveManager.isIgnoringBatteryOptimizations(),
                    onRequestIgnoreBattery = { keepAliveManager.requestIgnoreBatteryOptimizations() },
                    onToggleSkill = { name, enabled ->
                        lifecycleScope.launch {
                            skills = skills.map { if (it.name == name) it.copy(enabled = enabled) else it }
                        }
                    },
                    onClose = { showCapabilities = false }
                )
            }
            currentSession != null -> {
                ChatScreen(
                    sessionTitle = currentSession?.displayTitle ?: "任务控制台",
                    currentProject = currentProject,
                    projects = projects,
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
                    },
                    onProjectSwitch = { proj ->
                        currentProject = proj.name
                        lifecycleScope.launch {
                            loadSessions()
                            loadCapabilitiesAndModels()
                            Toast.makeText(this@MainActivity, "已切换工作区: ${proj.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenModelSheet = { showModelSheet = true },
                    onOpenCapabilities = { showCapabilities = true },
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
                        availableModels = availableModels,
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
                SessionListScreen(
                    deviceName = prefs.deviceName,
                    serverUrl = prefs.serverUrl,
                    isConfigured = prefs.isConfigured,
                    sessions = sessions,
                    isLoading = isLoadingSessions,
                    onRefresh = { loadSessions() },
                    onSessionClick = { session ->
                        currentSession = session
                        messages.clear()
                        startListeningEvents(session.id)
                    },
                    onScanClick = { isScanning = true },
                    onSettingsClick = { showSettings = true },
                    onNewSessionClick = {
                        val newId = "session_" + System.currentTimeMillis()
                        val newSession = Session(id = newId, name = "新任务会话", active = true)
                        currentSession = newSession
                        messages.clear()
                        startListeningEvents(newId)
                    }
                )
            }
        }

        // 应用内更新弹窗 (参考 Termius / MQTT 助手)
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

        if (showSettings) {
            ServerConfigDialog(
                initialUrl = prefs.serverUrl,
                initialToken = prefs.authToken,
                initialName = prefs.deviceName,
                onConfirm = { url, token, name ->
                    showSettings = false
                    prefs.saveConnection(url, token, name)
                    loadSessions()
                    loadCapabilitiesAndModels()
                },
                onDismiss = { showSettings = false }
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
