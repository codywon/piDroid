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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.pi.app.data.model.*
import com.pi.app.data.ServerDefaults
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: AppPreferences
    private lateinit var apiClient: PiApiClient
    private lateinit var sseClient: PiSseClient
    private lateinit var hapticsAndNotify: PiHapticsAndNotify
    private lateinit var keepAliveManager: KeepAliveManager

    private var sseJob: Job? = null
    private var cachedSessions: List<Session> = emptyList()
    private var pendingTargetSessionId: String? = null
    private var lastFinishedTaskTitle: String? = null

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

    private fun syncPersistentForegroundService(
        isBusy: Boolean,
        project: String = "默认工作区",
        phase: String? = null,
        sessionTitle: String? = null,
        lastFinishedTask: String? = null,
        sessionId: String? = null
    ) {
        if (!prefs.isConfigured) {
            PiForegroundService.stop(this)
            return
        }
        if (prefs.persistentNotifyEnabled || isBusy) {
            if (!lastFinishedTask.isNullOrBlank()) {
                lastFinishedTaskTitle = lastFinishedTask
            }
            val totalSessions = cachedSessions.size
            // 运行态以 isLive 为准（runningSessionIds → isRunning 与本地 active 取或），
            // 只读 active 会让通知栏长期显示「0 运行 / 全部完成」。
            val activeSessionsCount = cachedSessions.count { it.isLive }
            val runningCount = if (isBusy) maxOf(1, activeSessionsCount) else activeSessionsCount
            val completedCount = maxOf(0, totalSessions - runningCount)
            val devName = prefs.deviceName.ifBlank { prefs.serverUrl.substringAfter("://").substringBefore(":") }.ifBlank { "Remote PC" }
            val activeTitle = sessionTitle ?: lastFinishedTaskTitle ?: "智能任务"

            val title: String
            val content: String
            val subText: String
            val bigText: String

            if (isBusy) {
                title = "⚡ 智能任务执行中 · $activeTitle"
                content = phase ?: "正在流式执行推理与工具调用..."
                subText = "$runningCount 项运行 / $completedCount 项完成"
                bigText = buildString {
                    appendLine("● 当前任务: $activeTitle")
                    appendLine("● 工作空间: $project")
                    appendLine("● 实时进度: ${phase ?: "正在执行流式推理与工具调用..."}")
                    appendLine("● 任务统计: $runningCount 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                    append("● 远程主机: $devName (网关长连接正常)")
                }
            } else if (!lastFinishedTaskTitle.isNullOrBlank()) {
                title = "✓ 任务已完成 · $lastFinishedTaskTitle"
                content = "主机: $devName | 所有步骤执行完毕，长连接就绪"
                subText = "0 运行 / $completedCount 完成"
                bigText = buildString {
                    appendLine("✓ 最近完成: $lastFinishedTaskTitle")
                    appendLine("● 工作空间: $project")
                    appendLine("● 当前状态: 空闲待命中，随时响应远程指令")
                    appendLine("● 任务统计: 0 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                    append("● 远程主机: $devName")
                }
            } else {
                title = if (totalSessions > 0) "piDroid 常驻守护中 · $completedCount 项任务就绪" else "piDroid · 网关常驻守护中"
                content = "主机: $devName | 保持长连接与后台实时对账"
                subText = if (totalSessions > 0) "$completedCount 项就绪" else "守护中"
                bigText = buildString {
                    appendLine("● 网关状态: 实时长连接正常，数据增量同步中")
                    appendLine("● 当前工作空间: $project")
                    appendLine("● 任务统计: 0 个运行中 · $completedCount 个已就绪 (共 $totalSessions 个)")
                    append("● 远程主机: $devName")
                }
            }

            PiForegroundService.start(
                context = this,
                title = title,
                content = content,
                subText = subText,
                bigText = bigText,
                targetSessionId = sessionId
            )
        } else {
            PiForegroundService.stop(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
        val targetSessionId = intent.getStringExtra("target_session_id")
        if (!targetSessionId.isNullOrBlank()) {
            pendingTargetSessionId = targetSessionId
        }
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

        // 用 SessionSaver 保存：Activity 重建（切深色模式 / 改系统字号 / 进程被回收）
        // 后仍停留在原会话，而不是被踢回列表页。
        var currentSession by rememberSaveable(stateSaver = SessionSaver) {
            mutableStateOf<Session?>(null)
        }
        var sessions by remember { mutableStateOf<List<Session>>(emptyList()) }
        var isLoadingSessions by remember { mutableStateOf(false) }

        var dynamicCatalog by remember { mutableStateOf(DynamicModelCatalog(emptyList(), emptyMap())) }
        var currentDefaultModelId by remember { mutableStateOf(prefs.defaultModelId) }
        var currentDefaultProvider by remember { mutableStateOf(prefs.defaultProvider) }
        var selectedModel by remember {
            mutableStateOf(ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", 200000, 8192, true, true))
        }
        var currentThinkingLevel by remember {
            mutableStateOf(ThinkingLevel.fromValue(prefs.defaultThinkingLevel))
        }
        var currentToolPreset by remember {
            mutableStateOf(ToolPreset.fromId(prefs.defaultToolPreset))
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
        var taskCompletedNotifyEnabled by remember { mutableStateOf(prefs.taskCompletedNotifyEnabled) }
        var hapticsEnabled by remember { mutableStateOf(prefs.hapticsEnabled) }
        var completionSoundEnabled by remember { mutableStateOf(prefs.completionSoundEnabled) }
        var pinnedSessionIds by remember { mutableStateOf(prefs.getPinnedSessionIds()) }
        var archivedSessionIds by remember { mutableStateOf(prefs.getArchivedSessionIds()) }
        var customTitles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var customWorkspaces by remember { mutableStateOf(prefs.getCustomWorkspaces()) }

        val messages = remember { mutableStateListOf<ChatItem>() }
        // 「更早的记录」独立存放（由游标翻页追加），与主快照 messages 互不重叠：
        // 主快照继续按下标对账更新，这里只做只增不减的 prepend，互不干扰。
        var olderMessages by remember { mutableStateOf<List<ChatItem>>(emptyList()) }
        var hasEarlierOnServer by remember { mutableStateOf(false) }
        var earlierTurnCount by remember { mutableStateOf(0) }
        var olderCursor by remember { mutableStateOf<String?>(null) }
        var olderLeafId by remember { mutableStateOf<String?>(null) }
        var isLoadingOlder by remember { mutableStateOf(false) }
        // 本地刚改过队列的时间戳：PC 端状态可能还没反映出来，避免 1.5s 后一轮对账就把刚排的消息清掉
        var lastQueueMutationAt by remember { mutableStateOf(0L) }
        // pi-web 的项目（工作区）置顶与排序（来自 ui-state，小写绝对路径）
        var serverPinnedWorkspaceKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
        var serverWorkspaceOrder by remember { mutableStateOf<List<String>>(emptyList()) }
        val queuedMessages = remember { mutableStateListOf<QueuedMessage>() }
        var isAgentBusy by remember { mutableStateOf(false) }
        var agentPhaseLabel by remember { mutableStateOf<String?>(null) }
        var isAutoRetrying by remember { mutableStateOf(false) }
        var isToolExecuting by remember { mutableStateOf(false) }
        var lastSseActivityAt by remember { mutableStateOf(0L) }
        var taskStartedAt by remember { mutableStateOf(0L) }
        var consecutiveIdlePolls by remember { mutableStateOf(0) }
        var hasNotifiedCompletionForCurrentRun by remember { mutableStateOf(false) }
        var completionSettleJob by remember { mutableStateOf<Job?>(null) }
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
                    catalog.defaultModel?.let { def ->
                        currentDefaultModelId = def.modelId
                        currentDefaultProvider = def.provider
                    }
                    val targetId = catalog.defaultModel?.modelId ?: prefs.defaultModelId
                    val targetProv = catalog.defaultModel?.provider ?: prefs.defaultProvider
                    val matched = catalog.models.firstOrNull { it.id == targetId && (targetProv.isBlank() || it.provider == targetProv) }
                        ?: catalog.models.firstOrNull { it.id == targetId }
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
                    cachedSessions = it
                    pinnedSessionIds = prefs.getPinnedSessionIds()
                    // 置顶/归档的真源在 pi-web（~/.pi 下的 session-state 文件），本地 SharedPreferences
                    // 只是离线兜底。这里以服务端为准拉一次，并把结果镜像回本地。
                    apiClient.getSessionUiState().onSuccess { ui ->
                        val pinned = ui.sessionFlags.filterValues { it.pinned }.keys
                        val archived = ui.sessionFlags.filterValues { it.archived }.keys
                        pinnedSessionIds = pinned
                        archivedSessionIds = archived
                        prefs.setPinnedSessionIds(pinned)
                        prefs.setArchivedSessionIds(archived)
                        // 项目（工作区）置顶与排序同样以 PC 为准
                        serverPinnedWorkspaceKeys = ui.pinnedProjectKeys
                        serverWorkspaceOrder = ui.projectOrder
                    }
                    customTitles = it.mapNotNull { s -> prefs.getSessionCustomTitle(s.id)?.let { t -> s.id to t } }.toMap()
                    isGatewayOnline = true
                    syncPersistentForegroundService(isBusy = isAgentBusy, project = currentProject, phase = agentPhaseLabel)
                }.onFailure {
                    isGatewayOnline = false
                    if (!silent) {
                        Toast.makeText(this@MainActivity, "获取会话列表失败: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                }
                if (!silent) isLoadingSessions = false
            }
        }

        /**
         * 上滑触顶时向 PC 拉取「更早的一页」记录（游标 = 当前最早一条的 entryId）。
         *
         * 页与页之间由服务端 excludeLeaf 保证不重叠；这里仍按「角色+时间戳+内容哈希」再兜一层去重，
         * 并把整页 prepend 到 olderMessages，主快照 messages 完全不动。
         */
        fun loadOlderHistory() {
            val sid = currentSession?.id ?: return
            if (isLoadingOlder || !hasEarlierOnServer) return
            val cursor = olderCursor
            if (cursor.isNullOrBlank()) return
            isLoadingOlder = true
            lifecycleScope.launch {
                apiClient.getOlderContextPage(sid, cursor, tail = 60, leafId = olderLeafId)
                    .onSuccess { page ->
                        if (currentSession?.id != sid) return@onSuccess
                        fun fingerprint(item: ChatItem) = "${item.role}|${item.timestamp}|${item.content.hashCode()}"
                        val seen = (olderMessages + messages).map { fingerprint(it) }.toHashSet()
                        val fresh = page.messages.filter { seen.add(fingerprint(it)) }
                        if (fresh.isNotEmpty()) {
                            olderMessages = fresh + olderMessages
                        }
                        olderCursor = page.oldestEntryId ?: cursor
                        hasEarlierOnServer = page.hasMore
                        earlierTurnCount = page.turnsBefore
                    }
                    .onFailure { err ->
                        Toast.makeText(this@MainActivity, "加载更早记录失败: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                isLoadingOlder = false
            }
        }

        fun autoGenerateTitleForSession(sessionId: String) {
            lifecycleScope.launch {
                Toast.makeText(this@MainActivity, "正在调用大模型分析任务上下文并生成标题...", Toast.LENGTH_SHORT).show()
                val result = apiClient.autoGenerateSessionTitle(sessionId)
                result.onSuccess { generatedTitle ->
                    if (generatedTitle.isNotBlank()) {
                        prefs.saveSessionCustomTitle(sessionId, generatedTitle)
                        customTitles = customTitles + (sessionId to generatedTitle)
                        if (currentSession?.id == sessionId) {
                            currentSession = currentSession?.copy(name = generatedTitle)
                        }
                        Toast.makeText(this@MainActivity, "✨ 大模型已生成标题: ${generatedTitle}", Toast.LENGTH_SHORT).show()
                        loadSessions(silent = true)
                    }
                }.onFailure { err ->
                    val firstUserMsg = messages.firstOrNull { it.role == MessageRole.USER }?.content
                    val fallbackTitle = if (!firstUserMsg.isNullOrBlank()) {
                        val clean = firstUserMsg.lines().firstOrNull { it.isNotBlank() }?.trim() ?: firstUserMsg.trim()
                        clean.take(24).filter { it !in "#*`>[]" }
                    } else null

                    if (!fallbackTitle.isNullOrBlank()) {
                        prefs.saveSessionCustomTitle(sessionId, fallbackTitle)
                        customTitles = customTitles + (sessionId to fallbackTitle)
                        if (currentSession?.id == sessionId) {
                            currentSession = currentSession?.copy(name = fallbackTitle)
                        }
                        Toast.makeText(this@MainActivity, "上下文不足，已使用提要: ${fallbackTitle}", Toast.LENGTH_SHORT).show()
                        loadSessions(silent = true)
                    } else {
                        Toast.makeText(this@MainActivity, "生成标题失败: ${err.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // 声明 startListeningEvents 与同步回调
        var reattachSseCallback: ((String, Boolean) -> Unit)? = null
        var syncSnapshotCallback: ((String) -> Unit)? = null

        fun markAgentRunActive(phase: String? = null, clearPhaseIfNull: Boolean = false) {
            completionSettleJob?.cancel()
            completionSettleJob = null
            val now = System.currentTimeMillis()
            lastSseActivityAt = now
            consecutiveIdlePolls = 0
            if (!isAgentBusy) {
                isAgentBusy = true
                taskStartedAt = now
                hasNotifiedCompletionForCurrentRun = false
                keepAliveManager.acquireWakeLock()
            }
            if (phase != null) {
                agentPhaseLabel = phase
            } else if (clearPhaseIfNull) {
                agentPhaseLabel = null
            }
            syncPersistentForegroundService(isBusy = true, project = currentProject, phase = agentPhaseLabel)
        }

        fun completeAgentSessionRun(sessionId: String, syncSnapshot: Boolean = true) {
            completionSettleJob?.cancel()
            completionSettleJob = null
            val shouldNotify = isAgentBusy && !hasNotifiedCompletionForCurrentRun
            isAgentBusy = false
            isAutoRetrying = false
            isToolExecuting = false
            agentPhaseLabel = null
            consecutiveIdlePolls = 0
            keepAliveManager.releaseWakeLock()
            syncPersistentForegroundService(isBusy = false, project = currentProject)

            if (shouldNotify) {
                hasNotifiedCompletionForCurrentRun = true
                if (prefs.hapticsEnabled) {
                    hapticsAndNotify.vibrateTaskCompleted()
                }
                if (prefs.completionSoundEnabled) {
                    hapticsAndNotify.playTaskCompletedSound()
                }
                val finishedTitle = currentSession?.name?.ifBlank { null } ?: "当前任务"
                lastFinishedTaskTitle = finishedTitle
                // 仅在用户显式开启任务完成独立通知时发送，默认关闭避免堆叠打扰
                if (prefs.taskCompletedNotifyEnabled) {
                    hapticsAndNotify.notifyTaskCompleted(
                        sessionTitle = finishedTitle,
                        project = currentProject,
                        sessionId = sessionId
                    )
                }
                // 立即更新常驻通知栏展示刚完成的任务与统计大盘
                syncPersistentForegroundService(
                    isBusy = false,
                    project = currentProject,
                    lastFinishedTask = finishedTitle,
                    sessionId = sessionId
                )
            }

            for (i in messages.indices) {
                val item = messages[i]
                if (item.isStreaming || item.toolCall?.isRunning == true) {
                    messages[i] = item.copy(
                        isStreaming = false,
                        toolCall = item.toolCall?.copy(isRunning = false)
                    )
                }
            }

            if (syncSnapshot) {
                syncSnapshotCallback?.invoke(sessionId)
            }
        }

        fun scheduleAgentCompletionSettle(sessionId: String, delayMs: Long = 800L) {
            completionSettleJob?.cancel()
            completionSettleJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(delayMs)
                if (isAutoRetrying || isToolExecuting) return@launch
                val stillRunning = apiClient.getAgentState(sessionId).getOrNull()?.running ?: false
                if (stillRunning || isAutoRetrying || isToolExecuting) return@launch
                completeAgentSessionRun(sessionId, syncSnapshot = true)
            }
        }

        // 增量无损同步会话快照：
        // 1) 保留本地正在流式接收 (isStreaming = true) 或正在执行的工具尾部项，绝不因磁盘尚未落盘而抹掉实时流式文本（彻底解决“挤牙膏”与闪烁问题）
        // 2) 当检测到 PC 端刚启动任务 (!isAgentBusy && isRunning) 时，立即自动挂载 SSE 实时流
        // 3) 仅在整个会话彻底结束（排除单条工具命令执行间隙与自动重试等待期）时触发完成震动与提示音
        suspend fun syncSessionSnapshot(
            sessionId: String,
            restoreModelMeta: Boolean = false,
            allowCompletionTransition: Boolean = true
        ) {
            // 运行态改由 /api/sessions/{id}/state 判定（39KB，实测 9ms），
            // 原先每次 GET /api/sessions 实测 273KB / 390 条会话，1.5s 一轮纯属浪费流量与电。
            // 同一个响应还带着 contextUsage 与排队消息，下面直接复用。
            val runtimeResult = apiClient.getAgentState(sessionId)
            val runtime = runtimeResult.getOrNull()
            val runningResult = if (runtime != null) {
                Result.success(runtime.running)
            } else {
                Result.failure(runtimeResult.exceptionOrNull() ?: IllegalStateException("state unavailable"))
            }
            val serverRunning = runtime?.running ?: isAgentBusy
            val effectiveRunning = serverRunning || (isAgentBusy && (!allowCompletionTransition || isAutoRetrying || isToolExecuting || consecutiveIdlePolls < 2))

            // 排队消息与 PC 对账：离开会话页再回来、或在 PC 端撤回，这里都能拉齐
            if (runtime != null && System.currentTimeMillis() - lastQueueMutationAt > 3_000L) {
                val serverQueue = runtime.steering.map { QueuedMessage("steer", it) } +
                    runtime.followUp.map { QueuedMessage("followUp", it) }
                val serverTexts = serverQueue.map { it.kind to it.text }.toSet()
                val localTexts = queuedMessages.map { it.kind to it.text }.toSet()
                if (serverTexts != localTexts) {
                    queuedMessages.clear()
                    queuedMessages.addAll(serverQueue)
                }
            }

            apiClient.getSessionDetail(sessionId).onSuccess { hist ->
                isGatewayOnline = true
                if (hist.messages.isNotEmpty()) {
                    val synced = hist.messages.mapIndexed { idx, item ->
                        if (effectiveRunning && idx == hist.messages.lastIndex && item.role == MessageRole.AGENT) {
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
                    val inFlightTail = if (effectiveRunning && messages.isNotEmpty()) {
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
                                        isStreaming = effectiveRunning
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
                            if (effectiveRunning && inFlightTail != null && messages.size == synced.size + 1) {
                                for (i in synced.indices) {
                                    val localItem = messages[i]
                                    val diskItem = synced[i]
                                    if (!isSameChatContent(localItem, diskItem)) {
                                        messages[i] = diskItem.copy(id = localItem.id, timestamp = localItem.timestamp)
                                    }
                                }
                                messages[synced.size] = inFlightTail
                            } else if (!effectiveRunning) {
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
                // 上下文窗口以服务端模型配置为准：本地偏好里的 contextWindow 优先级高于
                // 统计回落的 200k 默认值，避免把 1M 上下文的模型误判成 98% 满载。
                val preferredWindow = prefs.getModelOverrides()
                    .values.firstOrNull { it.modelId.equals(hist.modelId, ignoreCase = true) }
                    ?.contextWindow
                    ?: 0L
                tokenStats = if (preferredWindow > hist.stats.maxContextWindow) {
                    hist.stats.copy(maxContextWindow = preferredWindow)
                } else {
                    hist.stats
                }
                // 翻页元数据：一旦已经往前翻过页，真·最老游标来自更早页，不能被快照覆盖
                if (olderMessages.isEmpty()) {
                    olderCursor = hist.oldestEntryId
                    olderLeafId = hist.leafId
                    hasEarlierOnServer = hist.hasMoreOnServer
                    earlierTurnCount = hist.turnsBefore
                }
                // 上下文占用以 agent 运行时为准（pi 的 getContextUsage 自带模型真实窗口）：
                // 会话快照的 context.model 不带 contextWindow，App 以前兜底 200k，
                // 1M 窗口的模型就永久显示 100%。这里复用本轮已经取回的 runtime state。
                runtime?.let { st ->
                    if (st.hasContextUsage) {
                        val window = st.contextWindow.takeIf { it > 0L } ?: tokenStats.maxContextWindow
                        tokenStats = tokenStats.copy(
                            contextTokens = st.contextTokens.takeIf { it > 0L } ?: tokenStats.contextTokens,
                            maxContextWindow = window
                        )
                    }
                    // 顺手对齐模型/思考档位（用户可能在 PC 端切过）
                    if (restoreModelMeta) {
                        st.modelId?.takeIf { it.isNotBlank() }?.let { mid ->
                            dynamicCatalog.models.firstOrNull {
                                it.id.equals(mid, ignoreCase = true) &&
                                    (st.provider.isNullOrBlank() || it.provider.equals(st.provider, ignoreCase = true))
                            }?.let { selectedModel = it }
                        }
                        st.thinkingLevel?.takeIf { it.isNotBlank() }?.let {
                            currentThinkingLevel = ThinkingLevel.fromValue(it)
                        }
                    }
                }
                // 不再由 App 在阈值处自动 POST {"type":"compact"}：pi 的 manual compact
                // 内部第一步就是 await this.abort()，会把正在执行的回合直接打断成
                // 「模型服务异常: This operation was aborted」。阈值压缩交给 pi 自身的
                // auto_compaction（在 turn 边界触发，不打断进行中的请求）。
                if (restoreModelMeta) {
                    dynamicCatalog.models.firstOrNull { it.id == hist.modelId }?.let {
                        selectedModel = it
                    }
                    currentThinkingLevel = hist.thinkingLevel
                }
            }

            if (serverRunning) {
                consecutiveIdlePolls = 0
                if (!isAgentBusy) {
                    // PC 端或远端刚启动了任务：立即切换为运行态并重新挂载 SSE 实时流
                    markAgentRunActive(phase = agentPhaseLabel ?: "正在同步实时输出...")
                    reattachSseCallback?.invoke(sessionId, true)
                }
            } else if (isAgentBusy && allowCompletionTransition && runningResult.isSuccess) {
                val now = System.currentTimeMillis()
                val hasRunningToolCard = messages.any { it.role == MessageRole.TOOL && it.toolCall?.isRunning == true }
                val isRecentlyStarted = (now - taskStartedAt) < 5_000L
                val isSseRecentlyActive = sseConnectionState == SseConnectionState.CONNECTED && (now - lastSseActivityAt) < 6_000L
                if (!isAutoRetrying && !isToolExecuting && !hasRunningToolCard && completionSettleJob?.isActive != true && !isRecentlyStarted && !isSseRecentlyActive) {
                    consecutiveIdlePolls += 1
                    if (consecutiveIdlePolls >= 2) {
                        completeAgentSessionRun(sessionId, syncSnapshot = false)
                    }
                } else {
                    consecutiveIdlePolls = 0
                }
            }
        }

        syncSnapshotCallback = { sid ->
            lifecycleScope.launch {
                syncSessionSnapshot(sid, restoreModelMeta = false, allowCompletionTransition = false)
            }
        }

        fun startListeningEvents(sessionId: String, initialBusy: Boolean = false) {
            sseJob?.cancel()
            sseJob = lifecycleScope.launch {
                if (initialBusy) {
                    markAgentRunActive(phase = "正在等待模型...")
                } else {
                    isAgentBusy = false
                    agentPhaseLabel = null
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
                                markAgentRunActive(phase = agentPhaseLabel ?: "正在接收实时流...")
                            } else if (!isToolExecuting) {
                                isAgentBusy = false
                                agentPhaseLabel = null
                            }
                        }
                        "reconnected_sync" -> {
                            syncSessionSnapshot(sessionId, restoreModelMeta = false, allowCompletionTransition = true)
                        }
                        "agent_start", "turn_start" -> {
                            isAutoRetrying = false
                            markAgentRunActive(phase = event.phaseLabel ?: "正在等待模型...")
                        }
                        "turn_end", "message_start", "message_end_other", "toolcall_delta" -> {
                            markAgentRunActive(phase = event.phaseLabel)
                        }
                        "auto_retry_start" -> {
                            isAutoRetrying = true
                            isToolExecuting = false
                            markAgentRunActive(phase = event.phaseLabel ?: "正在自动重试...")
                        }
                        "auto_retry_end" -> {
                            isAutoRetrying = false
                            if (event.done) {
                                // 重试多次后最终终止，代表整个会话结束，触发震动与提示音
                                isToolExecuting = false
                                if (!event.output.isNullOrBlank()) {
                                    val last = messages.lastOrNull()
                                    if (last != null && last.role == MessageRole.AGENT) {
                                        val errSuffix = "\n\n⚠️ ${event.output}"
                                        if (!last.content.contains(event.output)) {
                                            messages[messages.size - 1] = last.copy(
                                                content = (last.content + errSuffix).trim(),
                                                isStreaming = false
                                            )
                                        }
                                    } else {
                                        messages.add(
                                            ChatItem(
                                                role = MessageRole.AGENT,
                                                content = "⚠️ ${event.output}",
                                                modelName = selectedModel.displayName,
                                                isStreaming = false
                                            )
                                        )
                                    }
                                }
                                scheduleAgentCompletionSettle(sessionId, delayMs = 300L)
                            } else {
                                markAgentRunActive(phase = event.phaseLabel ?: "重试成功，正在继续...")
                            }
                        }
                        "auto_compaction_start" -> {
                            markAgentRunActive(phase = event.phaseLabel ?: "正在自动压缩上下文...")
                        }
                        "auto_compaction_end" -> {
                            agentPhaseLabel = null
                            syncSessionSnapshot(sessionId, restoreModelMeta = false, allowCompletionTransition = false)
                        }
                        "delta", "message", "text_delta", "content_block_delta", "assistant_delta" -> {
                            val chunk = event.effectiveText
                            if (chunk.isNotEmpty() || event.mediaList.isNotEmpty()) {
                                isAutoRetrying = false
                                markAgentRunActive(phase = null, clearPhaseIfNull = true)
                                val last = messages.lastOrNull()
                                if (last != null && last.role == MessageRole.AGENT) {
                                    val updated = last.copy(
                                        content = last.content + chunk,
                                        mediaList = if (event.mediaList.isNotEmpty()) (last.mediaList + event.mediaList).distinctBy { "${it.kind}:${it.url ?: it.filePath ?: it.id}" } else last.mediaList,
                                        isStreaming = true
                                    )
                                    messages[messages.size - 1] = updated
                                } else {
                                    messages.add(
                                        ChatItem(
                                            role = MessageRole.AGENT,
                                            content = chunk,
                                            modelName = selectedModel.displayName,
                                            mediaList = event.mediaList,
                                            isStreaming = true
                                        )
                                    )
                                }
                            }
                        }
                        "text_replace" -> {
                            val fullText = event.effectiveText
                            if (fullText.isNotEmpty() || event.mediaList.isNotEmpty()) {
                                lastSseActivityAt = System.currentTimeMillis()
                                val last = messages.lastOrNull()
                                if (last != null && last.role == MessageRole.AGENT) {
                                    messages[messages.size - 1] = last.copy(
                                        content = fullText.ifEmpty { last.content },
                                        mediaList = if (event.mediaList.isNotEmpty()) event.mediaList else last.mediaList,
                                        isStreaming = event.isStreaming ?: false
                                    )
                                }
                            }
                        }
                        "thinking", "thinking_delta" -> {
                            val thinkChunk = event.thinking ?: event.effectiveText
                            if (thinkChunk.isNotEmpty()) {
                                isAutoRetrying = false
                                markAgentRunActive(phase = null, clearPhaseIfNull = true)
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
                                lastSseActivityAt = System.currentTimeMillis()
                                val lastIdx = messages.indexOfLast { it.role == MessageRole.AGENT && it.thinking != null }
                                if (lastIdx >= 0) {
                                    val existing = messages[lastIdx]
                                    messages[lastIdx] = existing.copy(thinking = fullThink)
                                }
                            }
                        }
                        "tool", "tool_call", "tool_use", "tool_start" -> {
                            isAutoRetrying = false
                            isToolExecuting = true
                            val toolName = event.effectiveToolName
                            markAgentRunActive(phase = event.phaseLabel ?: "正在准备工具 $toolName...")
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
                            isAutoRetrying = false
                            isToolExecuting = true
                            markAgentRunActive(phase = event.phaseLabel)
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
                                        command = event.effectiveCommand?.takeIf { it.isNotBlank() } ?: existingTool.command,
                                        isRunning = true
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
                            isAutoRetrying = false
                            isToolExecuting = true
                            val toolName = event.effectiveToolName
                            markAgentRunActive(phase = event.phaseLabel ?: "正在执行 $toolName...")
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
                            val isEndOfTool = event.type == "tool_execution_end" || event.type == "tool_end"
                            val idx = if (!event.toolCallId.isNullOrBlank()) {
                                messages.indexOfLast { it.role == MessageRole.TOOL && it.toolCall?.toolCallId == event.toolCallId }
                            } else {
                                messages.indexOfLast { it.role == MessageRole.TOOL }
                            }
                            if (idx >= 0) {
                                val existing = messages[idx]
                                val existingTool = existing.toolCall
                                if (existingTool != null) {
                                    val newOutput = event.output ?: event.effectiveText.ifBlank { existingTool.output }
                                    val mergedMedia = if (event.mediaList.isNotEmpty()) {
                                        (existingTool.mediaList + event.mediaList).distinctBy { "${it.kind}:${it.url ?: it.filePath ?: it.id}" }
                                    } else existingTool.mediaList
                                    messages[idx] = existing.copy(
                                        toolCall = existingTool.copy(
                                            output = newOutput,
                                            durationMs = event.durationMs ?: existingTool.durationMs,
                                            isError = event.isError || existingTool.isError,
                                            isRunning = if (isEndOfTool) false else (event.isStreaming ?: true),
                                            mediaList = mergedMedia
                                        )
                                    )
                                }
                            }
                            // 单个命令执行完仅更新卡片状态并继续等待模型下一轮推理，绝不触发任务完成震动或提示音
                            val anyOtherToolRunning = messages.any { it.role == MessageRole.TOOL && it.toolCall?.isRunning == true }
                            isToolExecuting = !isEndOfTool || anyOtherToolRunning
                            markAgentRunActive(
                                phase = if (isEndOfTool) (event.phaseLabel ?: "正在等待模型...") else event.phaseLabel
                            )
                        }
                        "message_end_sync" -> {
                            lastSseActivityAt = System.currentTimeMillis()
                            if (event.isStreaming == true) {
                                isToolExecuting = true
                                markAgentRunActive(phase = event.phaseLabel)
                            }
                            val last = messages.lastOrNull()
                            if (last != null) {
                                messages[messages.size - 1] = last.copy(
                                    isStreaming = false,
                                    modelName = event.modelName ?: last.modelName,
                                    usageSummary = event.usageSummary ?: last.usageSummary
                                )
                            }
                            lifecycleScope.launch {
                                syncSessionSnapshot(sessionId, restoreModelMeta = false, allowCompletionTransition = false)
                            }
                        }
                        "message_stop" -> {
                            lastSseActivityAt = System.currentTimeMillis()
                            val last = messages.lastOrNull()
                            if (last != null && last.isStreaming) {
                                messages[messages.size - 1] = last.copy(isStreaming = false)
                            }
                        }
                        "approval_required" -> {
                            lastSseActivityAt = System.currentTimeMillis()
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
                        "agent_end" -> {
                            // 仅当整个 Agent 会话执行链结束（且未处于自动重试等待期）时，经防抖确认后触发震动与提示音
                            isToolExecuting = false
                            if (!isAutoRetrying) {
                                scheduleAgentCompletionSettle(sessionId, delayMs = 800L)
                            }
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
            completionSettleJob?.cancel()
            completionSettleJob = null
            currentSession = session
            currentProject = session.workspaceName
            queuedMessages.clear()
            isAutoRetrying = false
            isToolExecuting = false
            consecutiveIdlePolls = 0
            hasNotifiedCompletionForCurrentRun = !session.isRunning
            isAgentBusy = session.isRunning
            agentPhaseLabel = if (session.isRunning) "正在等待模型..." else null

            // 切换会话必须清空上一场的「更早记录」，否则会把别人的历史拼进来
            olderMessages = emptyList()
            hasEarlierOnServer = false
            earlierTurnCount = 0
            olderCursor = null
            olderLeafId = null
            isLoadingOlder = false

            // 优先秒开展示内存缓存中的会话历史与配置，彻底杜绝切入会话时的白屏
            val cached = apiClient.getCachedSessionDetail(session.id)
            if (cached != null && cached.messages.isNotEmpty()) {
                messages.clear()
                messages.addAll(cached.messages)
                selectedModel = ModelInfo(
                    id = cached.modelId,
                    name = cached.modelId,
                    provider = cached.provider
                )
                currentThinkingLevel = cached.thinkingLevel
                tokenStats = cached.stats
                hasEarlierOnServer = cached.hasMoreOnServer
                earlierTurnCount = cached.turnsBefore
                olderCursor = cached.oldestEntryId
                olderLeafId = cached.leafId
            } else {
                messages.clear()
            }

            startListeningEvents(session.id, session.isRunning)

            lifecycleScope.launch {
                syncSessionSnapshot(session.id, restoreModelMeta = true, allowCompletionTransition = false)
            }
        }

        // 点击常驻通知栏带 target_session_id 唤醒时自动响应切入目标会话
        LaunchedEffect(pendingTargetSessionId, sessions) {
            val pendingId = pendingTargetSessionId ?: return@LaunchedEffect
            val target = sessions.firstOrNull { it.id == pendingId }
            if (target != null) {
                pendingTargetSessionId = null
                openSessionWithHistory(target)
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
                    syncSessionSnapshot(activeSession.id, restoreModelMeta = false, allowCompletionTransition = true)
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
                initialUrl = prefs.serverUrl.ifBlank { ServerDefaults.gatewayUrlHint },
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
                    apiClient = apiClient,
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
                    onSaveCustomModel = { providerName, baseUrl, apiKey, apiType, modelId, modelName, contextWindow, maxTokens, reasoning, supportsVision ->
                        // 立即在本地模型列表中乐观生效，同时同步至网关
                        val updatedModel = ModelInfo(
                            id = modelId,
                            name = modelName.ifBlank { modelId },
                            provider = providerName,
                            supportsVision = supportsVision,
                            supportsThinking = reasoning,
                            contextWindow = contextWindow,
                            maxTokens = maxTokens
                        )
                        val existingIdx = dynamicCatalog.models.indexOfFirst { it.provider == providerName && it.id == modelId }
                        val updatedList = if (existingIdx >= 0) {
                            dynamicCatalog.models.toMutableList().apply { set(existingIdx, updatedModel) }
                        } else {
                            dynamicCatalog.models + updatedModel
                        }
                        dynamicCatalog = dynamicCatalog.copy(models = updatedList)
                        if (selectedModel.id == modelId) {
                            selectedModel = updatedModel
                            tokenStats = tokenStats.copy(maxContextWindow = contextWindow)
                        }

                        lifecycleScope.launch {
                            apiClient.upsertCustomProviderModel(
                                providerName = providerName,
                                baseUrlEndpoint = baseUrl,
                                apiKey = apiKey,
                                apiType = apiType,
                                modelId = modelId,
                                modelName = modelName,
                                contextWindow = contextWindow,
                                maxTokens = maxTokens,
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
                    onLoadSkillContent = { skill ->
                        apiClient.getSkillContent(skill, currentProject).getOrNull()
                    },
                    onSaveSkillContent = { skill, content ->
                        lifecycleScope.launch {
                            apiClient.saveSkillContent(skill, content, currentProject).onSuccess {
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
                            apiClient.manageMcpServerAction(action, name, cwd = currentProject).onSuccess {
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
                    },
                    taskCompletedNotifyEnabled = taskCompletedNotifyEnabled,
                    onToggleTaskCompletedNotify = { enabled ->
                        taskCompletedNotifyEnabled = enabled
                        prefs.taskCompletedNotifyEnabled = enabled
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
                    completionSoundEnabled = completionSoundEnabled,
                    onToggleCompletionSound = { enabled ->
                        completionSoundEnabled = enabled
                        prefs.completionSoundEnabled = enabled
                        if (enabled) {
                            hapticsAndNotify.playTaskCompletedSound(force = true)
                        }
                    },
                    onTestCompletionSound = {
                        hapticsAndNotify.playTaskCompletedSound(force = true)
                        Toast.makeText(this@MainActivity, "已播放任务完成提示音", Toast.LENGTH_SHORT).show()
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
                val activeSessionId = currentSession?.id ?: ""
                val activeCustomTitle = customTitles[activeSessionId] ?: prefs.getSessionCustomTitle(activeSessionId)
                val isPinned = pinnedSessionIds.contains(activeSessionId)
                ChatScreen(
                    sessionId = activeSessionId,
                    sessionTitle = activeCustomTitle ?: currentSession?.displayTitle ?: "任务控制台",
                    currentProject = currentProject,
                    selectedModel = selectedModel,
                    thinkingLevel = currentThinkingLevel,
                    tokenStats = tokenStats,
                    messages = messages,
                    olderMessages = olderMessages,
                    hasEarlierOnServer = hasEarlierOnServer,
                    earlierTurnCount = earlierTurnCount,
                    isLoadingOlder = isLoadingOlder,
                    onLoadOlder = { loadOlderHistory() },
                    isAgentBusy = isAgentBusy,
                    agentPhaseLabel = agentPhaseLabel,
                    sseState = sseConnectionState,
                    queuedMessages = queuedMessages,
                    isCompacting = isCompacting,
                    isPinned = isPinned,
                    mediaGatewayConfig = com.pi.app.ui.components.MediaGatewayConfig(
                        serverUrl = prefs.serverUrl,
                        authToken = prefs.authToken,
                        cwd = currentSession?.cwd ?: currentProject
                    ),
                    onTogglePin = {
                        // 先在本地乐观切换（下拉/图标立刻响应），随后写回 pi-web 的 ui-state；
                        // 失败再回滚，避免「手机上置顶了、PC 上没置顶」。
                        val willPin = !pinnedSessionIds.contains(activeSessionId)
                        val pinned = prefs.togglePinSession(activeSessionId)
                        pinnedSessionIds = prefs.getPinnedSessionIds()
                        lifecycleScope.launch {
                            apiClient.setSessionPinned(activeSessionId, willPin).onFailure { err ->
                                prefs.togglePinSession(activeSessionId)
                                pinnedSessionIds = prefs.getPinnedSessionIds()
                                Toast.makeText(
                                    this@MainActivity,
                                    "同步到 PC 失败: ${err.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (pinned) "已置顶任务" else "已取消置顶",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onAutoGenerateTitle = {
                        autoGenerateTitleForSession(activeSessionId)
                    },
                    onBack = {
                        completionSettleJob?.cancel()
                        completionSettleJob = null
                        sseJob?.cancel()
                        currentSession = null
                        isAgentBusy = false
                        isAutoRetrying = false
                        isToolExecuting = false
                        agentPhaseLabel = null
                        keepAliveManager.releaseWakeLock()
                        syncPersistentForegroundService(isBusy = false, project = currentProject)
                        hapticsAndNotify.clearNotification()
                        loadSessions()
                    },
                    onRefreshSession = {
                        currentSession?.let { sess ->
                            lifecycleScope.launch {
                                syncSessionSnapshot(sess.id, restoreModelMeta = true, allowCompletionTransition = false)
                                Toast.makeText(this@MainActivity, "已同步最新任务状态", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onOpenModelSheet = { showModelSheet = true },
                    onOpenCapabilities = { showGlobalSettings = true },
                    onCompactContext = {
                        // pi 的 manual compact 第一步是 await this.abort()：任务执行中压缩会把进行中的
                        // 回合直接打断（表现为「模型服务异常: This operation was aborted」）。
                        // 因此只在空闲态允许，忙时提示用户先等本轮结束。
                        if (isAgentBusy || isToolExecuting) {
                            Toast.makeText(
                                this@MainActivity,
                                "任务执行中，压缩会中断当前回合；请等本轮结束或先停止任务",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            lifecycleScope.launch {
                                isCompacting = true
                                Toast.makeText(this@MainActivity, "正在智能压缩上下文...", Toast.LENGTH_SHORT).show()
                                apiClient.compactContext(currentSession!!.id)
                                    .onSuccess {
                                        Toast.makeText(this@MainActivity, "上下文压缩完成！", Toast.LENGTH_SHORT).show()
                                        apiClient.getSessionStats(currentSession!!.id).onSuccess { tokenStats = it }
                                        syncSessionSnapshot(currentSession!!.id, restoreModelMeta = false, allowCompletionTransition = false)
                                    }
                                    .onFailure { err ->
                                        Toast.makeText(
                                            this@MainActivity,
                                            "压缩失败: ${err.message ?: "未知错误"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                isCompacting = false
                            }
                        }
                    },
                    onStopAgent = {
                        lifecycleScope.launch {
                            apiClient.stopAgent(currentSession!!.id).onSuccess {
                                completionSettleJob?.cancel()
                                completionSettleJob = null
                                hasNotifiedCompletionForCurrentRun = true
                                isAgentBusy = false
                                isAutoRetrying = false
                                isToolExecuting = false
                                agentPhaseLabel = null
                                keepAliveManager.releaseWakeLock()
                                syncPersistentForegroundService(isBusy = false, project = currentProject)
                                Toast.makeText(this@MainActivity, "已停止 Agent 执行", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onUploadFiles = { files ->
                        // 上传目标必须是真实的会话工作目录（不是工作区显示名）
                        apiClient.uploadFiles(currentSession?.cwd ?: "", files)
                    },
                    onSendMessage = { prompt, images ->
                        val sid = currentSession!!.id
                        if (messages.none { it.role == MessageRole.USER } && prefs.getSessionCustomTitle(sid) == null) {
                            val clean = prompt.lines().firstOrNull { it.isNotBlank() }?.trim() ?: prompt.trim()
                            val autoTitle = clean.take(24).replace(Regex("[#*`>\\[\\]]"), "")
                            if (autoTitle.isNotBlank()) {
                                prefs.saveSessionCustomTitle(sid, autoTitle)
                                customTitles = customTitles + (sid to autoTitle)
                            }
                        }
                        messages.add(ChatItem(role = MessageRole.USER, content = prompt))
                        isAutoRetrying = false
                        hasNotifiedCompletionForCurrentRun = false
                        markAgentRunActive(phase = "正在等待模型...")
                        // 确保 SSE 已连接
                        if (sseConnectionState != SseConnectionState.CONNECTED) {
                            startListeningEvents(sid, initialBusy = true)
                        }
                        lifecycleScope.launch {
                            apiClient.sendCommand(sid, prompt, images).onFailure { err ->
                                isAgentBusy = false
                                agentPhaseLabel = null
                                keepAliveManager.releaseWakeLock()
                                syncPersistentForegroundService(isBusy = false, project = currentProject)
                                val errText = err.message ?: "网络或服务端异常"
                                messages.add(
                                    ChatItem(
                                        role = MessageRole.AGENT,
                                        content = "⚠️ 指令发送失败: $errText",
                                        isStreaming = false
                                    )
                                )
                                Toast.makeText(this@MainActivity, "指令发送失败: $errText", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onSendSteer = { steerText, steerImages ->
                        messages.add(ChatItem(role = MessageRole.USER, content = "➔ [引导] $steerText"))
                        lifecycleScope.launch {
                            apiClient.sendSteer(currentSession!!.id, steerText, steerImages).onSuccess {
                                Toast.makeText(this@MainActivity, "已下发引导指令", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "引导下发失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onSendFollowUp = { followText, followImages ->
                        queuedMessages.add(QueuedMessage("followUp", followText))
                        lastQueueMutationAt = System.currentTimeMillis()
                        lifecycleScope.launch {
                            apiClient.sendFollowUp(currentSession!!.id, followText, followImages).onSuccess {
                                Toast.makeText(this@MainActivity, "已排入后续任务队列", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                queuedMessages.removeAll { it.text == followText }
                                Toast.makeText(this@MainActivity, "排入队列失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onClearQueue = {
                        lifecycleScope.launch {
                            apiClient.clearQueue(currentSession!!.id).onSuccess {
                                queuedMessages.clear()
                                lastQueueMutationAt = System.currentTimeMillis()
                                Toast.makeText(this@MainActivity, "已撤回排队消息", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onApproveTool = { approved ->
                        lifecycleScope.launch {
                            apiClient.sendApproval(currentSession!!.id, approved)
                        }
                    },
                    onForkFromMessage = { userItem ->
                        val currentSid = currentSession?.id ?: return@ChatScreen
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在从此处创建新会话分支...", Toast.LENGTH_SHORT).show()
                            apiClient.forkSession(currentSid).onSuccess { forkRes ->
                                loadSessions(silent = true)
                                val forkedSession = Session(
                                    id = forkRes.sessionId,
                                    name = forkRes.name ?: "分支任务",
                                    cwd = currentSession?.cwd,
                                    project = currentSession?.project,
                                    projectRoot = currentSession?.projectRoot
                                )
                                openSessionWithHistory(forkedSession)
                                Toast.makeText(this@MainActivity, "已切换至新会话分支！", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "创建新会话失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    currentToolPreset = currentToolPreset,
                    onToolPresetChange = { preset ->
                        currentToolPreset = preset
                        prefs.defaultToolPreset = preset.id
                        currentSession?.let { sess ->
                            lifecycleScope.launch {
                                apiClient.setSessionTools(sess.id, preset.toolNames).onSuccess { res ->
                                    if (res.recreated && res.sessionId != sess.id) {
                                        val updatedSession = sess.copy(id = res.sessionId)
                                        currentSession = updatedSession
                                        startListeningEvents(res.sessionId)
                                    }
                                    Toast.makeText(this@MainActivity, "已切换权限模式: ${preset.displayName}", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(this@MainActivity, "权限切换失败: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                )

                if (showModelSheet) {
                    ModelAndThinkingSheet(
                        availableModels = dynamicCatalog.models,
                        selectedModelId = selectedModel.id,
                        currentThinkingLevel = currentThinkingLevel,
                        defaultModelId = currentDefaultModelId,
                        defaultProvider = currentDefaultProvider,
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
                        onSetDefaultModel = { model ->
                            currentDefaultModelId = model.id
                            currentDefaultProvider = model.provider
                            prefs.defaultModelId = model.id
                            prefs.defaultProvider = model.provider
                            lifecycleScope.launch {
                                apiClient.setDefaultModel(model.provider, model.id).onSuccess {
                                    Toast.makeText(this@MainActivity, "已设为全局默认模型: ${model.displayName}", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(this@MainActivity, "设置默认模型失败: ${err.message}", Toast.LENGTH_LONG).show()
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
                    pinnedSessionIds = pinnedSessionIds,
                    archivedSessionIds = archivedSessionIds,
                    customTitles = customTitles,
                    onTogglePinSession = { sid ->
                        val willPin = !pinnedSessionIds.contains(sid)
                        val pinned = prefs.togglePinSession(sid)
                        pinnedSessionIds = prefs.getPinnedSessionIds()
                        lifecycleScope.launch {
                            apiClient.setSessionPinned(sid, willPin).onFailure { err ->
                                prefs.togglePinSession(sid)
                                pinnedSessionIds = prefs.getPinnedSessionIds()
                                Toast.makeText(this@MainActivity, "同步到 PC 失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (pinned) "已置顶任务" else "已取消置顶",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onBulkArchive = { sids ->
                        if (sids.isNotEmpty()) {
                            lifecycleScope.launch {
                                apiClient.setSessionsArchived(sids, true).onSuccess {
                                    sids.forEach { prefs.toggleArchiveSession(it) }
                                    archivedSessionIds = prefs.getArchivedSessionIds()
                                    loadSessions(silent = true)
                                    Toast.makeText(this@MainActivity, "已归档 ${sids.size} 个会话", Toast.LENGTH_SHORT).show()
                                }.onFailure { err ->
                                    Toast.makeText(this@MainActivity, "归档失败: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    serverPinnedWorkspaceKeys = serverPinnedWorkspaceKeys,
                    serverWorkspaceOrder = serverWorkspaceOrder,
                    onTogglePinWorkspace = { path, root ->
                        val key = path.replace('\\', '/').lowercase()
                        val willPin = !serverPinnedWorkspaceKeys.contains(key)
                        lifecycleScope.launch {
                            apiClient.setProjectPinned(key, root, willPin).onSuccess {
                                serverPinnedWorkspaceKeys =
                                    if (willPin) serverPinnedWorkspaceKeys + key else serverPinnedWorkspaceKeys - key
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "项目置顶同步失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onMoveWorkspace = { path, anchorPath ->
                        val key = path.replace('\\', '/').lowercase()
                        val anchorKey = anchorPath.replace('\\', '/').lowercase()
                        lifecycleScope.launch {
                            apiClient.moveProject(key, anchorKey).onFailure { err ->
                                Toast.makeText(this@MainActivity, "项目排序同步失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onToggleArchiveSession = { sid ->
                        val willArchive = !archivedSessionIds.contains(sid)
                        val archived = prefs.toggleArchiveSession(sid)
                        archivedSessionIds = prefs.getArchivedSessionIds()
                        lifecycleScope.launch {
                            apiClient.setSessionArchived(sid, willArchive).onFailure { err ->
                                prefs.toggleArchiveSession(sid)
                                archivedSessionIds = prefs.getArchivedSessionIds()
                                Toast.makeText(this@MainActivity, "同步到 PC 失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        Toast.makeText(
                            this@MainActivity,
                            if (archived) "已归档任务" else "已取消归档",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onRenameSession = { sid, newTitle ->
                        prefs.saveSessionCustomTitle(sid, newTitle)
                        // 同步写进会话文件（pi 的 set_session_name），否则 PC 端看不到
                        lifecycleScope.launch {
                            apiClient.setSessionName(sid, newTitle).onSuccess {
                                loadSessions(silent = true)
                            }.onFailure { err ->
                                Toast.makeText(
                                    this@MainActivity,
                                    "改名已存本地，同步到 PC 失败: ${err.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        customTitles = customTitles + (sid to newTitle)
                        Toast.makeText(this@MainActivity, "已重命名会话", Toast.LENGTH_SHORT).show()
                    },
                    onForkSession = { session ->
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在分叉任务分支...", Toast.LENGTH_SHORT).show()
                            apiClient.forkSession(session.id).onSuccess { res ->
                                Toast.makeText(this@MainActivity, "已成功分叉出新会话！", Toast.LENGTH_SHORT).show()
                                loadSessions(silent = true)
                                val forkedSession = Session(
                                    id = res.sessionId,
                                    name = res.name ?: "${session.displayTitle} · 分叉",
                                    cwd = session.cwd,
                                    active = false
                                )
                                openSessionWithHistory(forkedSession)
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "分叉失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onDeleteSession = { sid ->
                        lifecycleScope.launch {
                            Toast.makeText(this@MainActivity, "正在删除会话...", Toast.LENGTH_SHORT).show()
                            apiClient.deleteSessions(listOf(sid)).onSuccess {
                                prefs.removeSessionLocalData(sid)
                                pinnedSessionIds = prefs.getPinnedSessionIds()
                                archivedSessionIds = prefs.getArchivedSessionIds()
                                customTitles = customTitles - sid
                                sessions = sessions.filter { it.id != sid }
                                Toast.makeText(this@MainActivity, "会话已彻底删除", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "删除失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onRefresh = { loadSessions() },
                    onSessionClick = { session ->
                        openSessionWithHistory(session)
                    },
                    onScanClick = { isScanning = true },
                    onSettingsClick = { showGlobalSettings = true },
                    onNewSessionClick = { wsPath ->
                        lifecycleScope.launch {
                            val targetCwd = wsPath.ifBlank {
                                currentSession?.cwd ?: sessions.firstOrNull()?.cwd ?: ""
                            }
                            if (targetCwd.isBlank()) {
                                Toast.makeText(this@MainActivity, "请先选择或添加有效的工作区目录", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            Toast.makeText(this@MainActivity, "正在当前工作区创建新任务...", Toast.LENGTH_SHORT).show()
                            val createResult = apiClient.createRemoteSessionInWorkspace(
                                cwd = targetCwd,
                                provider = selectedModel.provider,
                                modelId = selectedModel.id,
                                thinkingLevel = currentThinkingLevel
                            )
                            createResult.onSuccess { realSessionId ->
                                val newSession = Session(
                                    id = realSessionId,
                                    name = "新任务会话",
                                    cwd = targetCwd,
                                    active = true
                                )
                                currentSession = newSession
                                currentProject = newSession.workspaceName
                                messages.clear()
                                startListeningEvents(realSessionId)
                                loadSessions(silent = true)
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "创建新任务失败: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onManualConfigConfirm = { url, token, name ->
                        connectToServer(url, token, name)
                    },
                    customWorkspaces = customWorkspaces.toList(),
                    onBrowseRemoteDir = { path ->
                        apiClient.browseRemoteDirectories(path).getOrNull()
                    },
                    onAddWorkspace = { targetPath, newFolderName, createIfMissing ->
                        val result = apiClient.addOrCreateRemoteWorkspace(
                            targetPath = targetPath,
                            newChildFolderName = newFolderName,
                            createIfMissing = createIfMissing
                        )
                        val finalPath = result.getOrNull()?.takeIf { it.isNotBlank() }
                            ?: if (!newFolderName.isNullOrBlank()) {
                                val sep = if (targetPath.contains('\\')) "\\" else "/"
                                "${targetPath.trimEnd('/', '\\')}$sep$newFolderName"
                            } else targetPath
                        prefs.addCustomWorkspace(finalPath)
                        customWorkspaces = prefs.getCustomWorkspaces()
                        Toast.makeText(this@MainActivity, "已添加工作区目录: $finalPath", Toast.LENGTH_SHORT).show()
                        Result.success(finalPath)
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
                            if (!AppUpdateManager.installApk(this@MainActivity, apkFile)) {
                                updateUiState = UpdateUiState.Error(
                                    "安装包校验未通过，已阻止安装。请到官方 Release 手动下载安装。",
                                    info
                                )
                            }
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
                    if (!AppUpdateManager.installApk(this@MainActivity, file)) {
                        val currentInfo = (updateUiState as? UpdateUiState.ReadyToInstall)?.info
                        updateUiState = UpdateUiState.Error(
                            "安装包校验未通过，已阻止安装。请到官方 Release 手动下载安装。",
                            currentInfo
                        )
                    }
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
