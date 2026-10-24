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
import com.pi.app.network.PiApiClient
import com.pi.app.network.PiSseClient
import com.pi.app.notification.PiHapticsAndNotify
import com.pi.app.ui.capabilities.CapabilitiesCenterScreen
import com.pi.app.ui.chat.ChatScreen
import com.pi.app.ui.chat.ModelAndThinkingSheet
import com.pi.app.ui.scanner.QrScannerScreen
import com.pi.app.ui.session.SessionListScreen
import com.pi.app.ui.settings.ServerConfigDialog
import com.pi.app.voice.SmartTtsManager
import com.pi.app.voice.VoiceInputManager
import com.pi.app.voice.VoiceInputState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: PiApiClient
    private lateinit var sseClient: PiSseClient
    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var smartTtsManager: SmartTtsManager
    private lateinit var hapticsAndNotify: PiHapticsAndNotify

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

        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainAppNav()
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
        var showSettings by remember { mutableStateOf(!prefs.isConfigured) }
        var showCapabilities by remember { mutableStateOf(false) }
        var showModelSheet by remember { mutableStateOf(false) }

        var currentSession by remember { mutableStateOf<Session?>(null) }
        var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
        var isLoadingSessions by remember { mutableStateOf(false) }

        // 高阶配置：模型、思考级别、项目、Token 监控、Skills、MCP
        var availableModels by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
        var selectedModel by remember {
            mutableStateOf(ModelInfo("claude-3-7-sonnet", "Claude 3.7 Sonnet", "Anthropic", 200000, true))
        }
        var currentThinkingLevel by remember { mutableStateOf(ThinkingLevel.MEDIUM) }
        var currentProject by remember { mutableStateOf("pi-app") }
        var projects by remember { mutableStateOf<List<ProjectInfo>>(emptyList()) }
        var tokenStats by remember { mutableStateOf(TokenStats(contextTokens = 38400, maxContextWindow = 200000, inputTokens = 1240, outputTokens = 680, cacheReadTokens = 36480, cost = 0.016)) }
        var skills by remember { mutableStateOf<List<SkillInfo>>(emptyList()) }
        var mcpServers by remember { mutableStateOf<List<McpServerInfo>>(emptyList()) }

        // 聊天状态
        val messages = remember { mutableStateListOf<ChatItem>() }
        var isAgentBusy by remember { mutableStateOf(false) }

        // 语音状态
        val voiceState by voiceInputManager.state.collectAsState()
        var isRecording by remember { mutableStateOf(false) }
        var partialVoiceText by remember { mutableStateOf("") }

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
                apiClient.getSkills().onSuccess { skills = it }
                apiClient.getMcpServers().onSuccess { mcpServers = it }
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
                hapticsAndNotify.updateTaskNotification("Pi 任务执行中", "正在监听 Agent 事件...")

                // 刷新 Token 统计
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
                            hapticsAndNotify.vibrateTaskCompleted()
                            hapticsAndNotify.updateTaskNotification("Pi 任务已完成", "所有步骤已执行完毕", isFinished = true)

                            val last = messages.lastOrNull()
                            if (last != null && last.isStreaming) {
                                messages[messages.size - 1] = last.copy(isStreaming = false)
                            }
                            // 重新拉取最新的 Token 状态
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
                    skills = skills,
                    mcpServers = mcpServers,
                    onToggleSkill = { name, enabled ->
                        lifecycleScope.launch {
                            apiClient.toggleSkill(name, enabled)
                            skills = skills.map { if (it.name == name) it.copy(enabled = enabled) else it }
                        }
                    },
                    onClose = { showCapabilities = false }
                )
            }
            currentSession != null -> {
                ChatScreen(
                    sessionTitle = currentSession?.title ?: "任务控制台",
                    currentProject = currentProject,
                    projects = projects,
                    selectedModel = selectedModel,
                    thinkingLevel = currentThinkingLevel,
                    tokenStats = tokenStats,
                    messages = messages,
                    isAgentBusy = isAgentBusy,
                    onBack = {
                        sseJob?.cancel()
                        currentSession = null
                        hapticsAndNotify.clearNotification()
                    },
                    onProjectSwitch = { proj ->
                        currentProject = proj.name
                        lifecycleScope.launch {
                            apiClient.switchProject(proj.path)
                            loadSessions()
                            Toast.makeText(this@MainActivity, "已切换工作区: ${proj.name}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenModelSheet = { showModelSheet = true },
                    onOpenCapabilities = { showCapabilities = true },
                    onCompactContext = {
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在压缩上下文...", Toast.LENGTH_SHORT).show()
                            apiClient.compactContext(currentSession!!.id).onSuccess {
                                Toast.makeText(this@MainActivity, "上下文压缩完成！", Toast.LENGTH_SHORT).show()
                                apiClient.getSessionStats(currentSession!!.id).onSuccess { tokenStats = it }
                            }
                        }
                    },
                    onSendMessage = { prompt ->
                        messages.add(ChatItem(role = MessageRole.USER, content = prompt))
                        lifecycleScope.launch {
                            apiClient.sendCommand(currentSession!!.id, prompt)
                        }
                    },
                    onApproveTool = { approved ->
                        lifecycleScope.launch {
                            apiClient.sendApproval(currentSession!!.id, approved)
                        }
                    },
                    onSpeakSummary = { rawContent ->
                        smartTtsManager.speakSummary(rawContent)
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
                                    apiClient.updateModelConfig(sess.id, model.id, currentThinkingLevel)
                                }
                            }
                        },
                        onThinkingLevelChanged = { level ->
                            currentThinkingLevel = level
                            currentSession?.let { sess ->
                                lifecycleScope.launch {
                                    apiClient.updateModelConfig(sess.id, selectedModel.id, level)
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
                        val newSession = Session(id = newId, title = "新任务会话", active = true)
                        currentSession = newSession
                        messages.clear()
                        startListeningEvents(newId)
                    }
                )
            }
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
        sseJob?.cancel()
        voiceInputManager.destroy()
        smartTtsManager.shutdown()
        hapticsAndNotify.clearNotification()
    }
}
