package com.pi.app.ui.chat

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.pi.app.data.storage.AppPreferences
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.*
import com.pi.app.ui.components.LocalMediaGatewayConfig
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.components.MediaAttachmentGallery
import com.pi.app.ui.components.MediaGatewayConfig
import com.pi.app.data.model.ToolPreset
import com.pi.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class ChatAttachment(
    val name: String,
    val uri: Uri,
    val isImage: Boolean
)

data class PromptTimelineEntry(
    val orderNumber: Int,
    val messageIndex: Int,
    val id: String,
    val content: String,
    val timestamp: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String = "",
    sessionTitle: String,
    currentProject: String,
    projects: List<ProjectInfo> = emptyList(),
    selectedModel: ModelInfo,
    thinkingLevel: ThinkingLevel,
    tokenStats: TokenStats,
    messages: List<ChatItem>,
    isAgentBusy: Boolean,
    agentPhaseLabel: String? = null,
    sseState: SseConnectionState = SseConnectionState.CONNECTED,
    queuedMessages: List<QueuedMessage> = emptyList(),
    isCompacting: Boolean = false,
    isPinned: Boolean = false,
    onBack: () -> Unit,
    onRefreshSession: () -> Unit = {},
    onProjectSwitch: (ProjectInfo) -> Unit = {},
    onOpenModelSheet: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onCompactContext: () -> Unit,
    onStopAgent: () -> Unit,
    onTogglePin: () -> Unit = {},
    onAutoGenerateTitle: () -> Unit = {},
    onSendMessage: (String) -> Unit,
    onSendSteer: (String) -> Unit,
    onSendFollowUp: (String) -> Unit,
    onClearQueue: () -> Unit,
    onApproveTool: (approved: Boolean) -> Unit,
    currentToolPreset: ToolPreset = ToolPreset.FULL,
    onToolPresetChange: (ToolPreset) -> Unit = {},
    onForkFromMessage: ((ChatItem) -> Unit)? = null,
    mediaGatewayConfig: MediaGatewayConfig = MediaGatewayConfig()
) {
    CompositionLocalProvider(LocalMediaGatewayConfig provides mediaGatewayConfig) {
    // 监听系统物理/手势返回：向左滑动时退回会话列表，而非直接退出应用
    BackHandler { onBack() }

    val theme = LocalAppTheme.current
    val context = LocalContext.current
    // 未发送的内容按会话持久化：切换深色模式、改系统字号、被系统回收进程后，
    // 输入框里的文字不会消失（这些场景都会重建 Activity，remember 里的状态会清零）。
    val draftPrefs = remember(context) { AppPreferences(context) }
    var inputText by remember(sessionId) {
        mutableStateOf(if (sessionId.isBlank()) "" else draftPrefs.getSessionDraft(sessionId))
    }
    LaunchedEffect(sessionId, inputText) {
        if (sessionId.isBlank()) return@LaunchedEffect
        // 防抖：连续打字时不要每个字符都写一次 SharedPreferences
        delay(400)
        draftPrefs.saveSessionDraft(sessionId, inputText)
    }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showHudDialog by remember { mutableStateOf(false) }
    var showToolPresetSheet by remember { mutableStateOf(false) }

    // 图片与文件附件选择器
    val attachments = remember { mutableStateListOf<ChatAttachment>() }
    val attachmentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        uris.forEach { uri ->
            val cr = context.contentResolver
            val mimeType = cr.getType(uri) ?: ""
            val isImg = mimeType.startsWith("image/")
            var fileName = "attachment"
            cr.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIdx >= 0) {
                    fileName = cursor.getString(nameIdx) ?: "attachment"
                }
            }
            attachments.add(ChatAttachment(name = fileName, uri = uri, isImage = isImg))
        }
    }

    fun buildMessageWithAttachments(rawText: String): String {
        if (attachments.isEmpty()) return rawText
        val attachHeader = attachments.joinToString(separator = "\n") { att ->
            if (att.isImage) "[图片: ${att.name}]" else "[文件附件: ${att.name}]"
        }
        attachments.clear()
        return if (rawText.isBlank()) attachHeader else "$rawText\n\n$attachHeader"
    }

    // 1:1 对标 pi-web：将整场会话的消息按用户提问构建回合大纲列表 (TurnOutline)
    val allTurns = remember(
        messages.size,
        messages.lastOrNull()?.id,
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.thinking?.length,
        isAgentBusy
    ) {
        val userIndices = mutableListOf<Int>()
        messages.forEachIndexed { i, m ->
            if (m.role == MessageRole.USER) {
                userIndices.add(i)
            }
        }

        userIndices.mapIndexed { turnIdx, uIdx ->
            val turnNum = turnIdx + 1
            val userMsg = messages[uIdx]
            val nextUIdx = if (turnIdx + 1 < userIndices.size) userIndices[turnIdx + 1] else messages.size
            val turnSlice = messages.subList(uIdx + 1, nextUIdx)

            // 从后往前找最后一个具有文本内容的 assistant 消息作为最终回答
            var finalAns: ChatItem? = null
            var finalAnsIdx: Int? = null
            for (i in turnSlice.indices.reversed()) {
                val m = turnSlice[i]
                if (m.role == MessageRole.AGENT && m.content.isNotBlank()) {
                    finalAns = m
                    finalAnsIdx = uIdx + 1 + i
                    break
                }
            }

            // 如果处于最后一轮且正在运行，且最后一条是工具调用或在流式生成，按 pi-web 规范视作中间过程
            val isCurrentRunning = (turnIdx == userIndices.lastIndex) && isAgentBusy

            val processItems = if (finalAns != null && (!isCurrentRunning || finalAnsIdx != messages.lastIndex)) {
                val pEnd = finalAnsIdx!! - (uIdx + 1)
                turnSlice.subList(0, pEnd)
            } else {
                finalAns = null
                finalAnsIdx = null
                turnSlice
            }

            var toolCount = 0
            turnSlice.forEach { m ->
                if (m.role == MessageRole.TOOL || m.toolCall != null) {
                    toolCount++
                }
            }

            val endTime = (finalAns ?: processItems.lastOrNull() ?: userMsg).timestamp
            val durationMs = if (endTime > userMsg.timestamp) endTime - userMsg.timestamp else null
            val headings = extractAssistantOutline(finalAns?.content ?: processItems.lastOrNull { it.role == MessageRole.AGENT }?.content)

            TurnOutline(
                turnNumber = turnNum,
                userIndex = uIdx,
                userMessage = userMsg,
                toolCount = toolCount,
                assistantIndex = finalAnsIdx,
                assistantHeadings = headings,
                processItems = processItems,
                finalAnswerItem = finalAns,
                durationMs = durationMs,
                isRunning = isCurrentRunning
            )
        }
    }

    // 记录用户是否正在通过时间轴或手动上翻回看历史，避免后台轮询或流式输出强制拉回底部打断阅读
    //
    // 以下四个状态用 rememberSaveable：rememberLazyListState 自身会把滚动位置存进
    // SavedState，而窗口大小若不留存、Activity 重建后回退到 50 条，就会出现
    // "列表恢复到很深的索引、但只渲染了 50 条"的错位跳动。
    var userJumpedToHistory by rememberSaveable(sessionTitle) { mutableStateOf(false) }

    // 针对超长会话的分页按需加载窗口：默认首屏渲染最近 50 条记录；支持向上追加 50 条或一键展开全部完整历史
    var visibleWindowCount by rememberSaveable(sessionTitle) { mutableStateOf(50) }
    var isAllHistoryExpanded by rememberSaveable(sessionTitle) { mutableStateOf(false) }
    var hasInitialScrolled by rememberSaveable(sessionTitle) { mutableStateOf(false) }

    // 上滑加载更早记录的加载态：顶部只留一枚细圆圈，不再占掉一整屏
    var isLoadingMoreHistory by remember { mutableStateOf(false) }

    // 窗口起点必须落在「回合边界」上。一个回合可能包含几十条 assistant / toolResult 记录，
    // 早前按消息条数硬切（messages.size - 50）时，起点会掉进最后一个回合的内部：该回合的
    // 提问（user 消息）落在窗口之外 → visibleTurns 直接为空 → 界面变成
    // 「已折叠 N 条 + 正在等待模型… + 中间一片空白」。
    val rawWindowStart = if (isAllHistoryExpanded) 0 else (messages.size - visibleWindowCount).coerceAtLeast(0)
    val windowStartIndex = remember(allTurns, rawWindowStart) {
        alignWindowToTurnStart(rawWindowStart, allTurns)
    }
    val hasHiddenHistory = windowStartIndex > 0
    val showOriginBanner = !hasHiddenHistory && messages.size > 6

    val visibleTurns = remember(allTurns, windowStartIndex) {
        when {
            windowStartIndex <= 0 -> allTurns
            else -> allTurns.filter { it.userIndex >= windowStartIndex }.ifEmpty {
                // 兜底：窗口起点即便算偏，也保证最后一个回合完整可见，绝不留白屏
                listOfNotNull(allTurns.lastOrNull())
            }
        }
    }

    // 列表行数 ≠ 消息条数：一个回合至少一行，还可能多出「过程折叠组」与「最终答复」两行。
    // 回到底部 / 上滑加载的视口锚定 / 时间轴跳转都要用精确的行索引，否则会跳偏。
    fun turnRowCount(turn: TurnOutline): Int =
        1 + (if (turn.processItems.isNotEmpty()) 1 else 0) + (if (turn.finalAnswerItem != null) 1 else 0)

    fun headerRowsFor(start: Int): Int {
        val hidden = start > 0
        val topBanner = if (hidden || messages.size > 6) 1 else 0
        val firstUserIdx = allTurns.firstOrNull()?.userIndex ?: messages.size
        val leading = if (firstUserIdx > start) firstUserIdx - start else 0
        return topBanner + leading
    }

    fun visibleRowCount(): Int =
        headerRowsFor(windowStartIndex) +
            visibleTurns.sumOf { turnRowCount(it) } +
            (if (isAgentBusy || isCompacting) 1 else 0)

    // 上滑触顶自动加载更早记录：把窗口往上扩一批，并保持当前阅读位置不跳动
    fun loadEarlierHistory() {
        if (isLoadingMoreHistory || isAllHistoryExpanded || !hasHiddenHistory) return
        isLoadingMoreHistory = true
        val firstIdx = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        val oldStart = windowStartIndex
        // 一个回合可能横跨上百条记录：按 50 条续批时，起点可能落在同一个回合内部，
        // 对齐后窗口完全没变（addedRows == 0）。此时必须继续往前扩，否则触顶效果会自旋。
        var newVisibleCount = visibleWindowCount
        var newStart = oldStart
        while (newStart >= oldStart && newVisibleCount < messages.size) {
            newVisibleCount = (newVisibleCount + HISTORY_LOAD_BATCH).coerceAtMost(messages.size)
            newStart = alignWindowToTurnStart((messages.size - newVisibleCount).coerceAtLeast(0), allTurns)
        }
        if (newStart >= oldStart) {
            // 已经没有更早的内容可露出来了（例如整段历史就一个回合且已全量渲染）
            isAllHistoryExpanded = true
            isLoadingMoreHistory = false
            return
        }
        // 新增到视口上方的行数 = 新露出的回合行 + 顶部横幅/前置系统消息行数变化
        val addedRows = allTurns.filter { it.userIndex in newStart until oldStart }.sumOf { turnRowCount(it) } +
            (headerRowsFor(newStart) - headerRowsFor(oldStart))
        userJumpedToHistory = true
        visibleWindowCount = newVisibleCount
        if (newStart <= 0) isAllHistoryExpanded = true
        coroutineScope.launch {
            kotlinx.coroutines.yield()
            // 等一帧真正完成布局后再补偿滚动，否则锚点会漂移
            kotlinx.coroutines.delay(16)
            if (addedRows > 0) {
                listState.scrollToItem((firstIdx + addedRows).coerceAtLeast(0), firstOffset)
            }
            isLoadingMoreHistory = false
        }
    }

    // 监听软键盘 (IME) 弹出高度：当软键盘弹起时，自动将底部输入框与最新对话内容整体往上推，防止遮挡
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottomPx > 0

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.thinking?.length,
        isAgentBusy,
        agentPhaseLabel,
        isImeVisible
    ) {
        if (messages.isNotEmpty() && (!userJumpedToHistory || isImeVisible)) {
            val targetIdx = (visibleRowCount() - 1).coerceAtLeast(0)
            if (!hasInitialScrolled) {
                // 首屏进入直接瞬间定位到底部，避免长列表逐条滚动造成的屏幕闪现
                listState.scrollToItem(targetIdx)
                hasInitialScrolled = true
            } else if (!listState.canScrollForward || isImeVisible || isAgentBusy) {
                listState.animateScrollToItem(targetIdx)
            }
        }
    }

    // 滑到最顶端就自动续批：顶部加载哨兵露头（索引 0）时补一批更早的记录。
    // 必须等首屏「瞬间定位到底部」完成（hasInitialScrolled）——否则刚进会话时索引本来就是 0，
    // 会立刻连打好几批，还会把视口钉在顶部而不是最新的对话上。
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex, hasInitialScrolled, hasHiddenHistory, isAllHistoryExpanded, isLoadingMoreHistory) {
        if (hasInitialScrolled && firstVisibleIndex <= 0 && hasHiddenHistory &&
            !isAllHistoryExpanded && !isLoadingMoreHistory
        ) {
            loadEarlierHistory()
        }
    }

    Scaffold(
        containerColor = theme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = theme.background,
                    titleContentColor = theme.textPrimary
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = theme.textSecondary)
                    }
                },
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentProject.ifBlank { "默认工作区" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = theme.textPrimary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val statusColor = when {
                                sseState == SseConnectionState.RECONNECTING -> Color(0xFFD29922)
                                isAgentBusy -> Color(0xFF22C55E)
                                else -> Color(0xFF2EA043)
                            }
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = statusColor
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    sseState == SseConnectionState.RECONNECTING -> "事件流自动重连并同步中..."
                                    isAgentBusy -> (agentPhaseLabel ?: "Agent 正在实时执行任务...")
                                    else -> sessionTitle.take(26)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (sseState == SseConnectionState.RECONNECTING) Color(0xFFD29922) else theme.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onTogglePin) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "取消置顶" else "置顶会话",
                            tint = if (isPinned) theme.primary else theme.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onAutoGenerateTitle) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "智能生成标题",
                            tint = theme.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onRefreshSession) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "同步最新任务状态",
                            tint = theme.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onOpenCapabilities) {
                        Icon(Icons.Default.Tune, contentDescription = "能力与设置中心", tint = theme.primary)
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.background)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                // 1. 排队消息提示栏
                if (queuedMessages.isNotEmpty()) {
                    Surface(
                        color = theme.surfaceContainerHigh,
                        border = BorderStroke(1.dp, theme.outline),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = theme.primary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${queuedMessages.size} 条排队",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = theme.primary
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = queuedMessages.first().text.take(24),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textSecondary,
                                    maxLines = 1
                                )
                            }

                            Text(
                                text = "撤回 ↺",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    val lastMsg = queuedMessages.lastOrNull()
                                    if (lastMsg != null) {
                                        inputText = if (inputText.isBlank()) lastMsg.text else "$inputText\n${lastMsg.text}"
                                        onClearQueue()
                                        android.widget.Toast.makeText(context, "已撤回排队消息并恢复至输入框", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        onClearQueue()
                                    }
                                }
                            )
                        }
                    }
                }

                val canSend = inputText.isNotBlank() || attachments.isNotEmpty()

                // 2. 附着在输入框上边沿上方的控制栏（弹性两端布局，模型自适应截断，工具权限与圆圈图标和谐统一，永不变形）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧：模型+思考档位胶囊芯片 + 权限预设胶囊芯片 (弹性自适应排布)
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 模型与思考档位胶囊芯片
                        Surface(
                            shape = CircleShape,
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clickable { onOpenModelSheet() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = theme.primary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${selectedModel.displayName} · ${thinkingLevel.value}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = theme.textPrimary,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = theme.textSecondary
                                )
                            }
                        }

                        // 权限切换胶囊芯片 (1:1 对标 pi-web 的 [wrench] full)
                        Surface(
                            shape = CircleShape,
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.clickable { showToolPresetSheet = true }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Build,
                                    contentDescription = "权限模式",
                                    modifier = Modifier.size(11.dp),
                                    tint = Color(0xFFEAB308)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = currentToolPreset.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = theme.textPrimary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // 右侧：统一带圈图标按键区 (压缩、引导、后续，统一28dp圆圈，紧凑精致，永不畸变换行)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1. 圆圈 + 压缩图标按钮
                        Surface(
                            shape = CircleShape,
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { onCompactContext() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (isCompacting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(13.dp),
                                        strokeWidth = 1.5.dp,
                                        color = theme.primary
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Compress,
                                        contentDescription = "压缩上下文",
                                        modifier = Modifier.size(14.dp),
                                        tint = theme.textSecondary
                                    )
                                }
                            }
                        }

                        // 2. 任务执行中的带圈动作按键（仅在 isAgentBusy 时呈现）
                        if (isAgentBusy) {
                            // 引导 (Steer) 圆圈图标按键
                            Surface(
                                shape = CircleShape,
                                color = if (canSend) theme.primary else theme.surfaceContainer,
                                border = BorderStroke(
                                    1.dp,
                                    if (canSend) theme.primary else theme.outline
                                ),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(enabled = canSend) {
                                        userJumpedToHistory = false
                                        val fullMsg = buildMessageWithAttachments(inputText.trim())
                                        onSendSteer(fullMsg)
                                        inputText = ""
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.NearMe,
                                        contentDescription = "实时引导 (Steer)",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (canSend) Color.White else theme.textMuted
                                    )
                                }
                            }

                            // 后续 (Follow-up) 圆圈图标按键
                            Surface(
                                shape = CircleShape,
                                color = if (canSend) theme.surfaceContainerHigh else theme.surfaceContainer,
                                border = BorderStroke(
                                    1.dp,
                                    if (canSend) theme.primary.copy(alpha = 0.8f) else theme.outline
                                ),
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable(enabled = canSend) {
                                        userJumpedToHistory = false
                                        val fullMsg = buildMessageWithAttachments(inputText.trim())
                                        onSendFollowUp(fullMsg)
                                        inputText = ""
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.PlaylistAdd,
                                        contentDescription = "后续排队 (Follow-up)",
                                        modifier = Modifier.size(15.dp),
                                        tint = if (canSend) theme.primary else theme.textMuted
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. 核心大圆角舒适输入框容器 (22.dp 圆角 + 充足输入高度 + 零挤压单行占位提示)
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(
                        1.dp,
                        if (isAgentBusy) theme.primary.copy(alpha = 0.35f) else theme.outline
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        // 附件预览列表 (支持图片预览与普通文件标签)
                        if (attachments.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(bottom = 6.dp, start = 2.dp, end = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                attachments.forEachIndexed { idx, att ->
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = theme.surfaceContainer,
                                        border = BorderStroke(1.dp, theme.outline)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (att.isImage) Icons.Default.Image else Icons.Default.AttachFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = theme.primary
                                            )
                                            Text(
                                                text = att.name.take(16),
                                                fontSize = 11.sp,
                                                color = theme.textPrimary,
                                                maxLines = 1
                                            )
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "移除附件",
                                                modifier = Modifier
                                                    .size(13.dp)
                                                    .clickable { attachments.removeAt(idx) },
                                                tint = theme.textMuted
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 38.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧：圆形附件按钮
                            Surface(
                                shape = CircleShape,
                                color = if (attachments.isNotEmpty()) theme.primary.copy(alpha = 0.15f) else theme.surfaceContainer,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clickable { attachmentPicker.launch("*/*") }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.AttachFile,
                                        contentDescription = "添加图片或文件",
                                        tint = if (attachments.isNotEmpty()) theme.primary else theme.textSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            // 中间：真正宽敞、无默认内边距裁剪的输入区域
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 38.dp)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (inputText.isEmpty()) {
                                    Text(
                                        text = if (isAgentBusy) "输入指令后点击上方引导/排队图标..." else "向 Agent 发送指令...",
                                        color = theme.textMuted,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    maxLines = 5,
                                    textStyle = TextStyle(
                                        color = theme.textPrimary,
                                        fontSize = 14.5.sp,
                                        lineHeight = 20.sp
                                    ),
                                    cursorBrush = SolidColor(theme.primary)
                                )
                            }

                            // 右侧：行业标准主动作按钮
                            // 空闲时为圆形上箭头发送按钮；运行中为红色填充圆圈 + 中央白色正方形块（停止按钮）
                            if (!isAgentBusy) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (canSend) theme.primary else theme.surfaceContainerHigh,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clickable(enabled = canSend) {
                                            userJumpedToHistory = false
                                            val fullMsg = buildMessageWithAttachments(inputText.trim())
                                            onSendMessage(fullMsg)
                                            inputText = ""
                                        }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Default.ArrowUpward,
                                            contentDescription = "发送",
                                            tint = if (canSend) Color.White else theme.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            } else {
                                // 行业标准停止按钮：红色填充圆圈 + 内部纯白正方形块，无多余文字
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFFDF2A39),
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clickable { onStopAgent() }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Box(
                                            modifier = Modifier
                                                .size(11.dp)
                                                .background(Color.White, RoundedCornerShape(2.dp))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 底部极简指标栏（4 个核心参数合理分布，左右安全边距避免圆角屏遮挡，点击呼出 HUD）
                val percent = (tokenStats.contextUsagePercent * 100).toInt().coerceIn(0, 100)
                val inTokens = TokenStats.formatTokenCompact(tokenStats.inputTokens)
                val outTokens = TokenStats.formatTokenCompact(tokenStats.outputTokens)
                val cacheTokensStr = TokenStats.formatCacheCompact(tokenStats.cacheReadTokens)
                val ctxColor = when {
                    percent >= 90 -> Color(0xFFCF222E)
                    percent >= 70 -> Color(0xFFD29922)
                    else -> theme.textSecondary
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showHudDialog = true },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // 1) 上下文占用 (图标 + 百分比)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DataUsage,
                                contentDescription = "上下文占用",
                                modifier = Modifier.size(12.dp),
                                tint = ctxColor
                            )
                            Text(
                                text = "$percent%",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ctxColor
                            )
                        }

                        // 2) 输入 Token (↑ in)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "↑",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textMuted
                            )
                            Text(
                                text = "$inTokens in",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = theme.textSecondary
                            )
                        }

                        // 3) 输出 Token (↓ out)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "↓",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textMuted
                            )
                            Text(
                                text = "$outTokens out",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = theme.textSecondary
                            )
                        }

                        // 4) 缓存读取 Token (闪电图标 + 自动换算，如 58.9k cache R)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "缓存读取",
                                modifier = Modifier.size(12.dp),
                                tint = if (tokenStats.cacheReadTokens > 0) Color(0xFF60A5FA) else theme.textMuted
                            )
                            Text(
                                text = cacheTokensStr,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = if (tokenStats.cacheReadTokens > 0) FontWeight.Medium else FontWeight.Normal,
                                color = if (tokenStats.cacheReadTokens > 0) theme.textPrimary else theme.textMuted
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = if (allTurns.isNotEmpty()) 32.dp else 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 顶部加载哨兵：只占一行。触顶即自动续批，加载中显示一枚细圆圈
                // （此前这里是一张占满一屏的「已折叠 N 条早期记录」大卡片，
                //   用户只能手动点按钮，且长回合下会整屏空白，已按大厂 App 惯例移除）
                if (hasHiddenHistory) {
                    item(key = "history_top_loader") {
                        HistoryTopLoader(
                            isLoading = isLoadingMoreHistory,
                            hiddenCount = windowStartIndex,
                            onClick = { loadEarlierHistory() }
                        )
                    }
                } else if (showOriginBanner) {
                    item(key = "history_origin_banner") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = theme.outline.copy(alpha = 0.35f),
                                thickness = 0.6.dp
                            )
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.55f)),
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Flag,
                                        contentDescription = null,
                                        tint = theme.primary.copy(alpha = 0.85f),
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = "本次已加载的起点 · 共 ${messages.size} 条记录",
                                        fontSize = 10.5.sp,
                                        color = theme.textMuted
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = theme.outline.copy(alpha = 0.35f),
                                thickness = 0.6.dp
                            )
                        }
                    }
                }

                // 若首个提问前有系统前置消息 (例如欢饮语)
                val firstUserIdx = allTurns.firstOrNull()?.userIndex ?: messages.size
                if (firstUserIdx > windowStartIndex) {
                    val leadingSlice = messages.subList(windowStartIndex, firstUserIdx.coerceAtMost(messages.size))
                    items(leadingSlice, key = { it.id }) { item ->
                        ChatMessageItem(
                            item = item,
                            showModelHeader = false,
                            defaultModelName = selectedModel.displayName,
                            onApproveTool = onApproveTool,
                            onEditFromHere = { text ->
                                inputText = text
                                coroutineScope.launch {
                                    listState.animateScrollToItem(messages.size)
                                }
                            },
                            onForkFromHere = onForkFromMessage
                        )
                    }
                }

                // 遍历渲染当前可见窗口内的每个回合 (对标 pi-web ProcessDetailsGroup 与最终回答)
                visibleTurns.forEach { turn ->
                    // 1. 用户提问
                    item(key = "turn_${turn.turnNumber}_user_${turn.userMessage.id}") {
                        ChatMessageItem(
                            item = turn.userMessage,
                            showModelHeader = false,
                            defaultModelName = selectedModel.displayName,
                            onApproveTool = onApproveTool,
                            onEditFromHere = { text ->
                                inputText = text
                                coroutineScope.launch {
                                    listState.animateScrollToItem(messages.size)
                                }
                                android.widget.Toast.makeText(context, "已填入输入框，修改后可直接发送", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            onForkFromHere = { userItem ->
                                onForkFromMessage?.invoke(userItem)
                            }
                        )
                    }

                    // 2. 过程折叠组 (ProcessDetailsGroup: > 4 条消息 · 4 次工具调用 · 耗时 2m 13s)
                    if (turn.processItems.isNotEmpty()) {
                        item(key = "turn_${turn.turnNumber}_process") {
                            ProcessDetailsGroup(
                                messageCount = turn.processItems.size,
                                toolCallCount = turn.toolCount,
                                durationMs = turn.durationMs,
                                defaultExpanded = turn.isRunning || turn.finalAnswerItem == null
                            ) {
                                turn.processItems.forEach { procItem ->
                                    ChatMessageItem(
                                        item = procItem,
                                        showModelHeader = false,
                                        defaultModelName = selectedModel.displayName,
                                        onApproveTool = onApproveTool,
                                        onEditFromHere = { text ->
                                            inputText = text
                                            coroutineScope.launch {
                                                listState.animateScrollToItem(messages.size)
                                            }
                                        },
                                        onForkFromHere = onForkFromMessage
                                    )
                                }
                            }
                        }
                    }

                    // 3. 助手最终答复 (Final Answer)
                    if (turn.finalAnswerItem != null) {
                        val ans = turn.finalAnswerItem
                        val showModelHeader = !ans.modelName.isNullOrBlank()
                        item(key = "turn_${turn.turnNumber}_ans_${ans.id}") {
                            ChatMessageItem(
                                item = ans,
                                showModelHeader = showModelHeader,
                                defaultModelName = selectedModel.displayName,
                                onApproveTool = onApproveTool,
                                onEditFromHere = { text ->
                                    inputText = text
                                    coroutineScope.launch {
                                        listState.animateScrollToItem(messages.size)
                                    }
                                },
                                onForkFromHere = onForkFromMessage
                            )
                        }
                    }
                }

                // 历史尚未到达（首屏同步中）时给一个轻量占位，避免整屏空白
                if (messages.isEmpty()) {
                    item(key = "chat_empty_hint") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isAgentBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 1.8.dp,
                                        color = theme.primary
                                    )
                                }
                                Text(
                                    text = if (isAgentBusy) "正在同步会话历史…" else "还没有对话，发送指令开始",
                                    fontSize = 12.sp,
                                    color = theme.textMuted
                                )
                            }
                        }
                    }
                }

                // 对标 pi-web ChatWindow.tsx：当 Agent 正在运行或等待模型响应时，底部展示脉冲状态文字（如 "正在等待模型..."）
                if (isAgentBusy || isCompacting) {
                    item(key = "pi_web_agent_phase_indicator") {
                        PiWebPhaseIndicator(
                            text = when {
                                isCompacting -> "正在压缩上下文..."
                                !agentPhaseLabel.isNullOrBlank() -> agentPhaseLabel
                                else -> "正在等待模型..."
                            }
                        )
                    }
                }
            }

            // ── 1:1 对标 pi-web 右侧时间轴导轨与历次提问大纲抽屉 (ChatMinimap) ──
            if (allTurns.isNotEmpty()) {
                // 时间轴当前高亮档位：直接读「首个可见行」的 key（turn_<n>_…）解析回合号。
                // 旧实现用 firstVisibleItemIndex 减去顶部横幅再当回合下标猜，一旦回合被折叠成
                // 「过程组 / 最终答复」多行（或上滑续批插入新行），索引与回合号就对不上，高亮乱跳。
                var activeTurnNumber by remember { mutableStateOf(allTurns.firstOrNull()?.turnNumber ?: 1) }
                LaunchedEffect(allTurns) {
                    snapshotFlow {
                        listState.layoutInfo.visibleItemsInfo
                            .firstNotNullOfOrNull { info -> turnNumberFromRowKey(info.key) }
                    }.collect { num ->
                        if (num != null) activeTurnNumber = num
                        else if (visibleTurns.isNotEmpty()) activeTurnNumber = visibleTurns.last().turnNumber
                    }
                }

                PiWebChatMinimap(
                    turns = allTurns,
                    activeTurnNumber = activeTurnNumber,
                    hasHiddenHistory = hasHiddenHistory,
                    onLoadEarlier = {
                        userJumpedToHistory = true
                        isAllHistoryExpanded = true
                        visibleWindowCount = messages.size + 5000
                        onRefreshSession()
                        coroutineScope.launch {
                            kotlinx.coroutines.yield()
                            listState.scrollToItem(0)
                        }
                    },
                    onJumpToUserMessage = { turn ->
                        userJumpedToHistory = turn.turnNumber < allTurns.size
                        coroutineScope.launch {
                            if (turn.userIndex < windowStartIndex) {
                                isAllHistoryExpanded = true
                                visibleWindowCount = messages.size + 5000
                                kotlinx.coroutines.yield()
                            }
                            val allTurnsList = allTurns
                            val turnIdx = allTurnsList.indexOfFirst { it.turnNumber == turn.turnNumber }
                            if (turnIdx >= 0) {
                                // 展开后首行是起始横幅（若有），其下还可能跟着提问前的系统消息
                                var itemOffset = headerRowsFor(0)
                                for (i in 0 until turnIdx) {
                                    itemOffset += turnRowCount(allTurnsList[i])
                                }
                                listState.animateScrollToItem(itemOffset.coerceAtLeast(0))
                            }
                        }
                    },
                    onJumpToAssistant = { turn ->
                        userJumpedToHistory = turn.turnNumber < allTurns.size
                        coroutineScope.launch {
                            if (turn.userIndex < windowStartIndex) {
                                isAllHistoryExpanded = true
                                visibleWindowCount = messages.size + 5000
                                kotlinx.coroutines.yield()
                            }
                            val allTurnsList = allTurns
                            val turnIdx = allTurnsList.indexOfFirst { it.turnNumber == turn.turnNumber }
                            if (turnIdx >= 0) {
                                var itemOffset = headerRowsFor(0)
                                for (i in 0 until turnIdx) {
                                    itemOffset += turnRowCount(allTurnsList[i])
                                }
                                // 越过「用户提问」与可能的「过程折叠组」，落到最终答复
                                itemOffset += 1
                                if (allTurnsList[turnIdx].processItems.isNotEmpty()) itemOffset += 1
                                listState.animateScrollToItem(itemOffset.coerceAtLeast(0))
                            }
                        }
                    },
                    modifier = Modifier.matchParentSize()
                )
            }

            // ── 回到底部悬浮按钮：下箭头加圆圈图标在正文框右下角 (对标行业标准与用户要求) ──
            val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
            AnimatedVisibility(
                visible = userJumpedToHistory || canScrollDown,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (allTurns.isNotEmpty()) 36.dp else 16.dp, bottom = 12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = theme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.85f)),
                    shadowElevation = 5.dp,
                    modifier = Modifier
                        .size(38.dp)
                        .clickable {
                            userJumpedToHistory = false
                            coroutineScope.launch {
                                if (messages.isNotEmpty()) {
                                    listState.animateScrollToItem((visibleRowCount() - 1).coerceAtLeast(0))
                                }
                            }
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "回到底部",
                            tint = theme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }

    // 权限模式与工具预设选择面板 (对标 pi-web)
    if (showToolPresetSheet) {
        ToolPresetBottomSheet(
            currentPreset = currentToolPreset,
            onPresetSelected = { preset ->
                onToolPresetChange(preset)
            },
            onDismissRequest = { showToolPresetSheet = false }
        )
    }

    if (showHudDialog) {
        TokenStatsHudDialog(
            stats = tokenStats,
            onCompactClick = {
                showHudDialog = false
                onCompactContext()
            },
            onDismiss = { showHudDialog = false }
        )
    }
    }
}


/**
 * 对标 pi-web ChatWindow.tsx 的底部脉冲等待状态提示 ("正在等待模型...")
 */
@Composable
fun PiWebPhaseIndicator(text: String) {
    val theme = LocalAppTheme.current
    val infiniteTransition = rememberInfiniteTransition(label = "phase_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp)
            .alpha(alpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = theme.textSecondary
        )
    }
}

@Composable
fun ChatMessageItem(
    item: ChatItem,
    showModelHeader: Boolean = false,
    defaultModelName: String = "",
    onApproveTool: (approved: Boolean) -> Unit,
    onEditFromHere: ((String) -> Unit)? = null,
    onForkFromHere: ((ChatItem) -> Unit)? = null
) {
    val theme = LocalAppTheme.current
    var isThinkingExpanded by remember { mutableStateOf(false) }
    var isToolExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // 对标 pi-web MessageView.tsx：回合顶部显示模型名称 (如 Gemini 3.8 Flash High)
        if (showModelHeader) {
            Text(
                text = item.modelName ?: defaultModelName,
                fontSize = 11.sp,
                color = theme.textMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 1.dp)
            )
        }

        when (item.role) {
            MessageRole.USER -> {
                // 对标 pi-web MessageView.tsx & globals.css (--user-bg: #EFF6FF / #232C3F, border: rgba(59,130,246,0.22), color: var(--text))
                val userBubbleBg = if (theme.isDark) Color(0xFF232C3F) else Color(0xFFEFF6FF)
                val userBubbleBorder = Color(0xFF3B82F6).copy(alpha = if (theme.isDark) 0.28f else 0.22f)
                val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                val context = androidx.compose.ui.platform.LocalContext.current

                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = userBubbleBg,
                        border = BorderStroke(1.dp, userBubbleBorder),
                        modifier = Modifier.widthIn(max = 330.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (item.content.isNotBlank()) {
                                MarkdownRenderer(
                                    content = item.content,
                                    isUser = true
                                )
                            }
                            if (item.mediaList.isNotEmpty()) {
                                MediaAttachmentGallery(mediaList = item.mediaList)
                            }
                        }
                    }

                    // 1:1 对标 pi-web 用户消息底部的操作工具条 (复制 · 从此处编辑 · 新会话 · 时间戳)
                    Row(
                        modifier = Modifier.padding(top = 3.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. 复制按钮
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(item.content))
                                    android.widget.Toast.makeText(context, "已复制消息内容", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制", modifier = Modifier.size(11.dp), tint = theme.textMuted)
                            Text("复制", fontSize = 10.5.sp, color = theme.textMuted)
                        }

                        // 2. 从此处编辑按钮
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    onEditFromHere?.invoke(item.content)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "从此处编辑", modifier = Modifier.size(11.dp), tint = theme.textMuted)
                            Text("从此处编辑", fontSize = 10.5.sp, color = theme.textMuted)
                        }

                        // 3. 新会话按钮
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    onForkFromHere?.invoke(item)
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(Icons.Default.CallSplit, contentDescription = "新会话", modifier = Modifier.size(11.dp), tint = theme.textMuted)
                            Text("新会话", fontSize = 10.5.sp, color = theme.textMuted)
                        }

                        // 4. 时间戳 (如 14:17)
                        val timeStr = remember(item.timestamp) {
                            try {
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(item.timestamp))
                            } catch (_: Exception) { "" }
                        }
                        if (timeStr.isNotBlank()) {
                            Text(text = timeStr, fontSize = 10.sp, color = theme.textMuted)
                        }
                    }
                }
            }

            MessageRole.AGENT -> {
                // 1. 对标 pi-web ThinkingBlock：灯泡图标 + 单行预览省略 + 右侧耗时秒数 (如 14s)，点击展开完整推理
                if (!item.thinking.isNullOrBlank()) {
                    PiWebThinkingCard(
                        thinking = item.thinking,
                        durationSec = item.thinkingDurationSec,
                        isStreaming = item.isStreaming && item.content.isBlank(),
                        expanded = isThinkingExpanded,
                        onToggle = { isThinkingExpanded = !isThinkingExpanded }
                    )
                }

                // 2. 对标 pi-web 正文区：无厚重外框，直接清晰渲染 Markdown、内联图片/PDF/SVG 与复制按钮
                if (item.content.isNotBlank() || item.mediaList.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (item.content.isNotBlank()) {
                            MarkdownRenderer(
                                content = item.content,
                                isUser = false
                            )
                        }

                        if (item.mediaList.isNotEmpty()) {
                            MediaAttachmentGallery(mediaList = item.mediaList)
                        }

                        if (!item.isStreaming && item.content.length > 80) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { clipboardManager.setText(AnnotatedString(item.content)) }
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "复制正文",
                                        modifier = Modifier.size(11.dp),
                                        tint = theme.textMuted
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "复制",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = theme.textMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            MessageRole.TOOL -> {
                item.toolCall?.let { tool ->
                    val mergedMedia = remember(tool.mediaList, item.mediaList) {
                        if (item.mediaList.isEmpty()) tool.mediaList
                        else (tool.mediaList + item.mediaList).distinctBy { it.filePath ?: it.dataBase64?.take(48) ?: it.id }
                    }
                    PiWebToolCallCard(
                        tool = if (mergedMedia.size != tool.mediaList.size) tool.copy(mediaList = mergedMedia) else tool,
                        expanded = isToolExpanded || tool.requiresApproval,
                        onToggle = { isToolExpanded = !isToolExpanded },
                        onCopyText = { text -> clipboardManager.setText(AnnotatedString(text)) },
                        onApproveTool = onApproveTool
                    )
                }
            }

            MessageRole.SYSTEM -> {
                // 压缩摘要 / 自定义注入消息：头部用单行标题（剥除 Markdown 标记），
                // 正文统一走 Markdown 渲染，彻底消除「## Goal」这类裸露标记。
                val isCompactionCard = remember(item.content) { item.content.trimStart().startsWith("📦") }
                val titleText = remember(item.content, isCompactionCard) {
                    val firstLine = item.content.lineSequence().firstOrNull()?.trim().orEmpty()
                    if (isCompactionCard) {
                        firstLine
                    } else {
                        firstLine.replace(Regex("^[#>*\\-\\s]+"), "").ifBlank { "系统状态摘要" }
                    }
                }
                val bodyMarkdown = remember(item.content, isCompactionCard) {
                    if (isCompactionCard) {
                        item.content.lineSequence().drop(1).joinToString("\n").trim()
                    } else {
                        item.content.trim()
                    }
                }
                val hasBody = bodyMarkdown.isNotBlank()
                var sysExpanded by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = hasBody) { sysExpanded = !sysExpanded },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Inventory2,
                                    contentDescription = null,
                                    tint = theme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = titleText.ifBlank { "系统状态摘要" },
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (!sysExpanded) {
                                    Text(
                                        text = if (isCompactionCard) "查看摘要" else "展开",
                                        fontSize = 10.sp,
                                        color = theme.primary.copy(alpha = 0.85f)
                                    )
                                }
                            }
                            if (hasBody) {
                                Icon(
                                    imageVector = if (sysExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (sysExpanded) "收起" else "展开",
                                    tint = theme.textMuted,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                        if (sysExpanded && hasBody) {
                            Spacer(modifier = Modifier.height(6.dp))
                            HorizontalDivider(color = theme.outline.copy(alpha = 0.7f))
                            Spacer(modifier = Modifier.height(6.dp))
                            MarkdownRenderer(
                                content = bodyMarkdown,
                                modifier = Modifier.fillMaxWidth(),
                                isUser = false
                            )
                        }
                    }
                }
            }
        }

        // 对标 pi-web MessageView.tsx：每回合底部展示 Token 与成本明细 (如 34,938 in · 635 out · $0.0021)
        if (!item.usageSummary.isNullOrBlank()) {
            Text(
                text = item.usageSummary,
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                color = theme.textMuted.copy(alpha = 0.78f),
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

/**
 * 1:1 对标 pi-web ThinkingBlock：
 * 折叠时展示灯泡图标 + 思考内容最后一行单行省略预览 + 右侧秒数 (如 14s)
 * 展开时展示完整推理过程文本
 */
@Composable
fun PiWebThinkingCard(
    thinking: String,
    durationSec: Int?,
    isStreaming: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val theme = LocalAppTheme.current
    val previewLine = remember(thinking) {
        thinking.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .lastOrNull()
            ?.replace(Regex("[#*_>`~]"), "")
            ?.trim()
            ?: thinking.replace('\n', ' ').trim()
    }

    val borderColor = if (isStreaming) {
        theme.primary.copy(alpha = 0.55f)
    } else {
        theme.outline.copy(alpha = 0.65f)
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = theme.surfaceContainerLow.copy(alpha = 0.65f),
        border = BorderStroke(0.75.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lightbulb,
                    contentDescription = "思考过程",
                    modifier = Modifier.size(13.dp),
                    tint = if (isStreaming) theme.primary else theme.textSecondary
                )

                Text(
                    text = if (expanded) "思考过程" else previewLine,
                    fontSize = 11.5.sp,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (isStreaming) {
                    Text(
                        text = "思考中...",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.primary
                    )
                } else if (durationSec != null && durationSec > 0) {
                    Text(
                        text = "${durationSec}s",
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textSecondary
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = theme.textSecondary
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = theme.outline.copy(alpha = 0.4f), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(6.dp))
                    SelectionContainer {
                        Text(
                            text = thinking.trim(),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            color = theme.textSecondary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 1:1 对标 pi-web ToolCallBlock：
 * 顶栏：`▸` 折叠箭头 + 工具名徽章 (如 `bash` / `read` / `edit`) + 单行参数预览 + 右侧耗时 (如 `2.5s`) 与完成状态 (`✓` / `✗` / 旋转脉冲)
 * 展开区：结构化入参 + 配对的完整执行输出 (PairedResult)
 */
@Composable
fun PiWebToolCallCard(
    tool: ToolCallInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyText: (String) -> Unit,
    onApproveTool: (approved: Boolean) -> Unit
) {
    val theme = LocalAppTheme.current
    val hasResult = !tool.output.isNullOrBlank() || !tool.isRunning
    val isPending = tool.isRunning && tool.output.isNullOrBlank() && !tool.requiresApproval

    val hasMedia = tool.mediaList.isNotEmpty()
    val borderColor = when {
        tool.requiresApproval && !tool.isApproved -> Color(0xFFD29922)
        tool.isError -> Color(0xFFF87171).copy(alpha = 0.65f)
        isPending -> theme.primary.copy(alpha = 0.55f)
        hasMedia -> Color(0xFF22C55E).copy(alpha = if (theme.isDark) 0.42f else 0.35f)
        else -> theme.outline
    }

    val cardContainerColor = when {
        hasMedia && !tool.isError -> Color(0xFF22C55E).copy(alpha = if (theme.isDark) 0.08f else 0.06f)
        else -> theme.surfaceContainerLow
    }

    val badgeBg = when {
        tool.isError -> Color(0xFFEF4444).copy(alpha = 0.14f)
        hasMedia -> Color(0xFF22C55E).copy(alpha = 0.16f)
        else -> theme.primary.copy(alpha = 0.13f)
    }

    val badgeFg = when {
        tool.isError -> Color(0xFFEF4444)
        hasMedia -> if (theme.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
        else -> theme.primary
    }

    val previewSummary = remember(tool.preview, tool.command, tool.output) {
        when {
            !tool.preview.isNullOrBlank() -> tool.preview
            !tool.command.isNullOrBlank() -> tool.command.replace('\n', ' ').trim().take(100)
            !tool.output.isNullOrBlank() -> tool.output.replace('\n', ' ').trim().take(80)
            else -> ""
        }
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = cardContainerColor,
        border = BorderStroke(0.85.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── 头部单行栏 (对标 pi-web ToolCallBlock Header: 紧凑 4dp 垂直高密排版 + 高对比文字) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = if (expanded) "▾" else "▸",
                    fontSize = 11.sp,
                    color = theme.textSecondary
                )

                // 工具名称胶囊徽章
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = tool.toolName.ifBlank { "tool" },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }

                // 工具参数单行预览 (如文件路径、命令摘要，提升对比度不再发白发淡)
                Text(
                    text = previewSummary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // 右侧耗时徽章 (如 1.8s)
                tool.formattedDuration?.let { dur ->
                    Text(
                        text = dur,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.5.sp,
                        color = theme.textMuted.copy(alpha = 0.78f)
                    )
                }

                // 右侧状态指示 (旋转加载 / ✓ / ✗)
                when {
                    tool.requiresApproval && !tool.isApproved -> {
                        Text(
                            text = "待审批",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD97706)
                        )
                    }
                    isPending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(11.dp),
                            strokeWidth = 1.6.dp,
                            color = theme.primary
                        )
                    }
                    tool.isError -> {
                        Text(
                            text = "✗",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFEF4444)
                        )
                    }
                    hasResult -> {
                        Text(
                            text = "✓",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF22C55E)
                        )
                    }
                }
            }

            // ── 1:1 复刻 pi-web 多媒体直出预览：当 read xxx.png / svg / pdf 等包含媒体附件时，无需展开文本参数即可在卡片内直接展示缩略图，点击全屏查看 ──
            if (hasMedia) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    MediaAttachmentGallery(
                        mediaList = tool.mediaList,
                        inToolCard = true
                    )
                }
            }

            // ── 展开详情区 (对标 pi-web ToolDetails + PairedResult) ──
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.surfaceContainer.copy(alpha = 0.45f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f), thickness = 0.5.dp)

                    // 1. 工具输入参数
                    if (tool.fields.isNotEmpty()) {
                        tool.fields.forEach { (k, v) ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = k,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = theme.textMuted
                                    )
                                    if (v.length > 40) {
                                        Text(
                                            text = "复制",
                                            fontSize = 10.sp,
                                            color = theme.primary,
                                            modifier = Modifier.clickable { onCopyText(v) }
                                        )
                                    }
                                }
                                Surface(
                                    color = theme.background,
                                    shape = RoundedCornerShape(5.dp),
                                    border = BorderStroke(0.5.dp, theme.outline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = v,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                        color = theme.textPrimary,
                                        modifier = Modifier.padding(7.dp)
                                    )
                                }
                            }
                        }
                    } else if (!tool.command.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("输入参数", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                            Text(
                                text = "复制",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.primary,
                                modifier = Modifier.clickable { onCopyText(tool.command) }
                            )
                        }
                        Surface(
                            color = theme.background,
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(0.5.dp, theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = tool.command,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = theme.textPrimary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    // 2. 配对执行结果 (PairedResult)
                    if (!tool.output.isNullOrBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (tool.isError) "错误输出" else "执行结果",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tool.isError) Color(0xFFEF4444) else theme.textMuted
                            )
                            Text(
                                text = "复制输出",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.primary,
                                modifier = Modifier.clickable { onCopyText(tool.output) }
                            )
                        }
                        Surface(
                            color = theme.background,
                            shape = RoundedCornerShape(5.dp),
                            border = BorderStroke(0.5.dp, if (tool.isError) Color(0xFFF87171).copy(alpha = 0.5f) else theme.outline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = tool.output,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = if (tool.isError) Color(0xFFEF4444) else theme.textSecondary,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    // 3. 危险命令人工审批操作
                    if (tool.requiresApproval && !tool.isApproved) {
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onApproveTool(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("批准执行", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onApproveTool(false) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626)),
                                border = BorderStroke(1.dp, Color(0xFFDC2626)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text("拒绝", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单次上滑续批的条数（按消息条数计，实际会向上对齐到最近的回合起点）。 */
private const val HISTORY_LOAD_BATCH = 50

/**
 * 把「按消息条数算出的窗口起点」向上对齐到回合起点。
 *
 * 一个回合里可能夹着几十条 assistant / toolResult 记录（长工具链），如果直接按
 * `messages.size - 50` 切，起点会落进最后一个回合内部 —— 该回合的提问（user 消息）
 * 被切到窗口外，`visibleTurns` 就变空了，界面表现为「已折叠 N 条 + 中间一片空白」。
 */
private fun alignWindowToTurnStart(rawStart: Int, turns: List<TurnOutline>): Int {
    if (rawStart <= 0 || turns.isEmpty()) return 0
    // 最后一个「提问位置不晚于 rawStart」的回合，就是包含该位置的回合
    val owner = turns.lastOrNull { it.userIndex <= rawStart } ?: return 0
    return owner.userIndex.coerceAtMost(rawStart)
}

/**
 * 从 LazyColumn 的行 key 解析回合号（key 形如 `turn_3_user_<id>` / `turn_3_process` / `turn_3_ans_<id>`）。
 * 顶部横幅、加载哨兵、阶段提示等非回合行返回 null。
 */
private fun turnNumberFromRowKey(key: Any?): Int? {
    val raw = key as? String ?: return null
    if (!raw.startsWith("turn_")) return null
    return raw.removePrefix("turn_").substringBefore('_').toIntOrNull()
}

/**
 * 顶部「上滑加载更早记录」哨兵：只占一行，加载中显示细圆圈。
 *
 * 取代原先那张占满一屏的大卡片（“已折叠 N 条早期记录 · 前 50 条 · 展开全部”），
 * 交互对齐大厂 App：滑到顶自动续批，用户无需点任何按钮。
 */
@Composable
private fun HistoryTopLoader(
    isLoading: Boolean,
    hiddenCount: Int,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clickable { if (!isLoading) onClick() },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.8.dp,
                color = theme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("正在加载更早的记录…", fontSize = 11.sp, color = theme.textMuted)
        } else {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                tint = theme.textMuted,
                modifier = Modifier.size(13.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "上滑加载更早的 $hiddenCount 条",
                fontSize = 11.sp,
                color = theme.textMuted
            )
        }
    }
}

private fun formatCompactTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).removeSuffix(".0M") + if (tokens % 1_000_000L == 0L) "M" else ""
        tokens >= 10_000L -> "${tokens / 1000}k"
        tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1000.0).replace(".0k", "k")
        else -> tokens.toString()
    }
}
