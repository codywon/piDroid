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
import com.pi.app.data.model.ChatItem
import com.pi.app.data.model.MessageRole
import com.pi.app.data.model.Session
import com.pi.app.data.model.ToolCallInfo
import com.pi.app.data.storage.AppPreferences
import com.pi.app.network.PiApiClient
import com.pi.app.network.PiSseClient
import com.pi.app.notification.PiHapticsAndNotify
import com.pi.app.ui.chat.ChatScreen
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

    // 运行时权限申请
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
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
        var currentSession by remember { mutableStateOf<Session?>(null) }
        var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
        var isLoadingSessions by remember { mutableStateOf(false) }

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
                            // 关键事件：触发警示长震提醒！
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
                            // 关键事件：任务完成触发触觉轻震两次，并更新常驻通知栏！
                            hapticsAndNotify.vibrateTaskCompleted()
                            hapticsAndNotify.updateTaskNotification("Pi 任务已完成", "所有步骤已执行完毕", isFinished = true)

                            val last = messages.lastOrNull()
                            if (last != null && last.isStreaming) {
                                messages[messages.size - 1] = last.copy(isStreaming = false)
                            }
                        }
                    }
                }
            }
        }

        LaunchedEffect(prefs.isConfigured) {
            if (prefs.isConfigured) {
                loadSessions()
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
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "握手失败: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onClose = { isScanning = false }
                )
            }
            currentSession != null -> {
                ChatScreen(
                    sessionTitle = currentSession?.title ?: "任务控制台",
                    messages = messages,
                    isAgentBusy = isAgentBusy,
                    onBack = {
                        sseJob?.cancel()
                        currentSession = null
                        hapticsAndNotify.clearNotification()
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
                        // 用户主动点击朗读按钮时，提纯朗读
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
