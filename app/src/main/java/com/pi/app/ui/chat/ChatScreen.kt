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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    sessionTitle: String = "",
    currentProject: String,
    projects: List<ProjectInfo> = emptyList(),
    selectedModel: ModelInfo,
    thinkingLevel: ThinkingLevel,
    tokenStats: TokenStats,
    messages: List<ChatItem>,
    /** PC 端游标翻页拉回来的更早记录（严格早于 [messages]，由外层 prepend） */
    olderMessages: List<ChatItem> = emptyList(),
    /** 服务端是否还有更早的记录可以继续翻页 */
    hasEarlierOnServer: Boolean = false,
    /** 服务端统计的「更早但未加载的回合数」（pi 的 context.turnsBefore） */
    earlierTurnCount: Int = 0,
    isLoadingOlder: Boolean = false,
    onLoadOlder: () -> Unit = {},
    isAgentBusy: Boolean,
    agentPhaseLabel: String? = null,
    /** 首屏历史正在从 PC 拉取（只用于画骨架，不向用户展示具体状态文案） */
    isLoadingSessionHistory: Boolean = false,
    sseState: SseConnectionState = SseConnectionState.CONNECTED,
    queuedMessages: List<QueuedMessage> = emptyList(),
    isCompacting: Boolean = false,
    isPinned: Boolean = false,
    onBack: () -> Unit,
    onRefreshSession: () -> Unit = {},
    onProjectSwitch: (ProjectInfo) -> Unit = {},
    onOpenModelSheet: () -> Unit,
    onCompactContext: () -> Unit,
    onStopAgent: () -> Unit,
    onTogglePin: () -> Unit = {},
    onAutoGenerateTitle: () -> Unit = {},
    /** 读取一次 bash 的完整输出（GET /api/agent/{id}/bash-output?path=） */
    onLoadBashOutput: suspend (String) -> Result<String> = { Result.success("") },
    /** 文件元信息 + 文本内容 + 原始字节（图片预览） */
    onLoadFileMeta: suspend (String) -> Result<FileMeta> = { Result.failure(Exception("未接入")) },
    onLoadFileText: suspend (String) -> Result<String> = { Result.success("") },
    onLoadFileBytes: suspend (String) -> Result<ByteArray> = { Result.failure(Exception("未接入")) },
    /** 读取工作目录的 git 变更（GET /api/git/status?cwd=） */
    onLoadGitStatus: suspend () -> Result<GitStatusResult> = { Result.success(GitStatusResult()) },
    /** 读取单个文件的 diff（GET /api/git/diff?cwd=&path=） */
    onLoadGitDiff: suspend (String) -> Result<GitDiffResult> = { Result.success(GitDiffResult()) },
    /** 读取斜杠命令列表（pi 的 agent get_commands：扩展命令 / prompt 模板 / 技能） */
    onLoadCommands: suspend () -> Result<List<SlashCommand>> = { Result.success(emptyList()) },
    /** 把文件真上传到 PC 的工作目录（pi 的 /api/files?type=upload），返回成功上传的文件名 */
    onUploadFiles: suspend (List<UploadFileInput>) -> Result<UploadFilesResult> = { Result.success(UploadFilesResult()) },
    onSendMessage: (String, List<ChatImageInput>) -> Unit,
    onSendSteer: (String, List<ChatImageInput>) -> Unit,
    onSendFollowUp: (String, List<ChatImageInput>) -> Unit,
    onClearQueue: () -> Unit,
    onApproveTool: (approved: Boolean) -> Unit,
    currentToolPreset: ToolPreset = ToolPreset.FULL,
    onToolPresetChange: (ToolPreset) -> Unit = {},
    onForkFromMessage: ((ChatItem) -> Unit)? = null,
    /** 「从此处编辑」：把会话树切到该条（navigate_tree），后续内容变成被放弃的分支 */
    onEditFromMessage: ((ChatItem) -> Unit)? = null,
    mediaGatewayConfig: MediaGatewayConfig = MediaGatewayConfig()
) {
    CompositionLocalProvider(LocalMediaGatewayConfig provides mediaGatewayConfig) {
    // 监听系统物理/手势返回：向左滑动时退回会话列表，而非直接退出应用
    BackHandler { onBack() }

    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
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
    // 滚动位置也必须按会话隔离：否则切到新会话时会沿用上一个会话的滚动偏移，
    // 看起来像“打开了一个不从头也不到底的会话”。
    val listState = remember(sessionId) { LazyListState() }
    val coroutineScope = rememberCoroutineScope()
    var showHudDialog by remember { mutableStateOf(false) }
    var showToolPresetSheet by remember { mutableStateOf(false) }
    // 通用查看器（bash 完整输出 / 文件内容 / 图片）：一个面板复用，避免各处各弹一种 UI
    var artifactTitle by remember { mutableStateOf<String?>(null) }
    var artifactText by remember { mutableStateOf<String?>(null) }
    var artifactImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var artifactMeta by remember { mutableStateOf<String?>(null) }
    var isLoadingArtifact by remember { mutableStateOf(false) }

    // Git 变更面板
    var showGitSheet by remember { mutableStateOf(false) }
    var gitStatus by remember { mutableStateOf<GitStatusResult?>(null) }
    var isLoadingGit by remember { mutableStateOf(false) }
    var gitDiff by remember { mutableStateOf<GitDiffResult?>(null) }
    var gitDiffTitle by remember { mutableStateOf<String?>(null) }
    var isLoadingGitDiff by remember { mutableStateOf(false) }

    // 斜杠命令面板（扩展命令 / prompt 模板 / 技能）：跟随输入框里的 "/" 自动展开
    var commands by remember { mutableStateOf<List<SlashCommand>>(emptyList()) }
    var isLoadingCommands by remember { mutableStateOf(false) }

    // 图片与文件附件选择器（按会话隔离：切会话后不会把上个会话的附件带过去）
    val attachments = remember(sessionId) { mutableStateListOf<ChatAttachment>() }
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

    // 统一发送入口：图片真的读成 base64 走 pi 的 images 字段；非图片文件目前只能按名字引用
    // （pi 的图片通道只收 image/*，文件要走 /api/files?type=upload 的 multipart 上传，后续补）
    fun dispatchSend(text: String, images: List<ChatImageInput>, kind: SendKind) {
        when (kind) {
            SendKind.SEND -> onSendMessage(text, images)
            SendKind.STEER -> onSendSteer(text, images)
            SendKind.FOLLOW_UP -> onSendFollowUp(text, images)
        }
    }

    fun submitMessage(rawText: String, kind: SendKind) {
        val picked = attachments.toList()
        attachments.clear()
        if (picked.isEmpty()) {
            dispatchSend(rawText, emptyList(), kind)
            return
        }
        coroutineScope.launch {
            // 图片：读成 base64 走 prompt 的 images 字段
            val (images, skipped) = withContext(Dispatchers.IO) { encodePickedImages(context, picked) }
            if (skipped > 0) {
                android.widget.Toast.makeText(context, "$skipped 张图片超过 10MB 或读取失败，已跳过", android.widget.Toast.LENGTH_SHORT).show()
            }

            // 非图片文件：真上传到 PC 的工作目录（multipart），再把文件名写进正文，
            // 让 agent 能在 cwd 里直接看到它们
            var uploadedNote = ""
            val fileAtts = picked.filter { !it.isImage }
            if (fileAtts.isNotEmpty()) {
                val payloads = withContext(Dispatchers.IO) { readPickedFiles(context, fileAtts) }
                val skippedFiles = fileAtts.size - payloads.size
                if (skippedFiles > 0) {
                    android.widget.Toast.makeText(context, "$skippedFiles 个文件读取失败，已跳过", android.widget.Toast.LENGTH_SHORT).show()
                }
                if (payloads.isNotEmpty()) {
                    val res = onUploadFiles(payloads)
                    res.onSuccess { r ->
                        val names = r.uploaded
                        if (names.isNotEmpty()) {
                            uploadedNote = names.joinToString("\n") { "已上传文件到工作目录: $it" }
                        }
                        if (r.errors.isNotEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                "部分文件上传失败: ${r.errors.joinToString("; ").take(120)}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }.onFailure { err ->
                        android.widget.Toast.makeText(
                            context,
                            "文件上传失败: ${err.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            val text = listOf(rawText, uploadedNote).filter { it.isNotBlank() }.joinToString("\n\n")
            // 纯图片消息也必须给 pi 一句可见文本，避免空 message 被拒
            val finalText = if (text.isBlank() && images.isNotEmpty()) "（图片）" else text
            dispatchSend(finalText, images, kind)
        }
    }


    /** 打开「bash 完整输出」查看器 */
    fun openBashOutput(outputPath: String, titleLabel: String) {
        artifactTitle = titleLabel
        artifactText = null
        artifactImage = null
        artifactMeta = null
        isLoadingArtifact = true
        coroutineScope.launch {
            onLoadBashOutput(outputPath)
                .onSuccess { artifactText = it.ifBlank { "（输出为空）" } }
                .onFailure { err ->
                    artifactText = "读取完整输出失败：${err.message}"
                }
            isLoadingArtifact = false
        }
    }

    /** 打开「文件预览」查看器：图片直接看图，其余读文本 */
    fun openFilePreview(absPath: String) {
        val name = absPath.replace('\\', '/').substringAfterLast('/')
        artifactTitle = name
        artifactText = null
        artifactImage = null
        artifactMeta = null
        isLoadingArtifact = true
        coroutineScope.launch {
            val meta = onLoadFileMeta(absPath).getOrNull()
            artifactMeta = meta?.let { m ->
                listOfNotNull(m.sizeLabel, m.language.ifBlank { null }, m.mime.ifBlank { null })
                    .joinToString(" · ")
            }
            if (meta?.isImage == true) {
                onLoadFileBytes(absPath)
                    .onSuccess { bytes ->
                        artifactImage = runCatching {
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                ?.asImageBitmap()
                        }.getOrNull()
                        if (artifactImage == null) artifactText = "图片解码失败（可能过大或格式不支持）"
                    }
                    .onFailure { artifactText = "读取图片失败：${it.message}" }
            } else {
                onLoadFileText(absPath)
                    .onSuccess { artifactText = it.ifBlank { "（空文件）" } }
                    .onFailure { artifactText = "读取文件失败：${it.message}" }
            }
            isLoadingArtifact = false
        }
    }

    // 会话正文 = PC 上游标翻页拉来的更早记录 + 主窗口快照。
    // 两者互不重叠（更早记录只 prepend、主快照只按下标对账更新），这里直接拼接渲染；
    // 回合大纲也必须建立在拼接后的完整列表上，否则翻页拉回来的历史不进时间轴。
    //
    // 绝对不要把它包进 remember(...)：messages 是 SnapshotStateList，它没有实现结构化 equals
    // （不是 AbstractList 子类，用的是引用相等），所以 remember(olderMessages, messages) 的 key
    // 永远“没变”。首屏在 messages 还是空的时候就把它缓存成了空列表，之后快照填满 messages
    // 也不会重算 —— 于是正文一直空着，直到 olderMessages（普通 List，翻页时换新实例）变化
    // 才突然全部出现。这就是「打开历史会话必须点一下上滑加载才出正文」的根因。
    val conversation = olderMessages + messages

    /**
     * pi-web 的 reducer 里 `streamingMessage` 是**单数**（一个 store 只持一条正在流式的消息），
     * 渲染时也只有那一条会被传 `isStreaming: true`（`jsx(sv,{message:d,isStreaming:!0})`），
     * 所以界面上永远最多只有一个「↓ token + t/s」徽标。
     *
     * 本地的 `ChatItem.isStreaming` 是逐条粘住的：一回合里插了工具卡 / 多段正文，
     * 就会有好几个 AGENT 气泡同时为 true → 同时挂出多个徽标。前面那些早已不再增长，
     * 显示的是它们停下来时的旧值（看起来“很快”），最新的那个才刚起算（看起来“慢/0”）。
     * 这里显式只认「会话最后一条且正在流式」，把徽标收敛到一条，与 pi-web 一致。
     */
    val liveStreamingItemId = remember(messages.size, messages.lastOrNull()?.id, messages.lastOrNull()?.isStreaming) {
        val tail = messages.lastOrNull()
        if (tail != null && tail.role == MessageRole.AGENT && tail.isStreaming) tail.id else null
    }

    // 首屏还没有任何正文时，不投射「正在等待模型…」式的状态噪音，只给静默骨架。
    // （会话在 PC 侧确实在跑时，isAgentBusy 也为真，但正文还在路上，报「等待模型」纯属噪声）
    val isFirstScreenLoading = conversation.isEmpty() && (isLoadingSessionHistory || isAgentBusy)

    // 1:1 对标 pi-web：将整场会话的消息按用户提问构建回合大纲列表 (TurnOutline)
    val allTurns = remember(
        conversation.size,
        conversation.firstOrNull()?.id,
        conversation.lastOrNull()?.id,
        conversation.lastOrNull()?.content?.length,
        conversation.lastOrNull()?.thinking?.length,
        isAgentBusy
    ) {
        val userIndices = mutableListOf<Int>()
        conversation.forEachIndexed { i, m ->
            if (m.role == MessageRole.USER) {
                userIndices.add(i)
            }
        }

        userIndices.mapIndexed { turnIdx, uIdx ->
            val turnNum = turnIdx + 1
            val userMsg = conversation[uIdx]
            val nextUIdx = if (turnIdx + 1 < userIndices.size) userIndices[turnIdx + 1] else conversation.size
            val turnSlice = conversation.subList(uIdx + 1, nextUIdx)

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

            val processItems = if (finalAns != null && (!isCurrentRunning || finalAnsIdx != conversation.lastIndex)) {
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
    // rememberSaveable：滚动位置已在 listState 里按 sessionId 隔离（见上），
    // 这里额外记住「是否已经完成首屏定位」与「用户是否在上翻读历史」。
    var userJumpedToHistory by rememberSaveable(sessionId) { mutableStateOf(false) }
    var hasInitialScrolled by rememberSaveable(sessionId) { mutableStateOf(false) }

    // 顶部哨兵行：有更早历史时是加载提示行，否则是「起点」分隔线（都只占一行）
    val showTopSentinel = hasEarlierOnServer || isLoadingOlder || conversation.size > 6
    val leadingRowCount = allTurns.firstOrNull()?.userIndex ?: conversation.size

    // 一个回合不是一行：可能还有「过程折叠组」与「最终答复」
    fun turnRowCount(turn: TurnOutline): Int =
        1 + (if (turn.processItems.isNotEmpty()) 1 else 0) + (if (turn.finalAnswerItem != null) 1 else 0)

    fun turnStartRowIndex(turnIdx: Int): Int {
        var rows = (if (showTopSentinel) 1 else 0) + leadingRowCount
        for (i in 0 until turnIdx.coerceIn(0, allTurns.size)) rows += turnRowCount(allTurns[i])
        return rows
    }

    // ── 翻页前后的视口锚点 ──
    // 往前插一整页后，回合编号会整体后移、行 key 全变，LazyColumn 的「按首个可见 key 维持索引」
    // 机制失效，视口会跳到最顶端。这里记下「首个可见回合的提问消息 id + 像素偏移」，
    // 翻页回来后按这个 id 找回它在新列表里的行号，把视口钉回原处。
    var pendingAnchorMessageId by remember { mutableStateOf<String?>(null) }
    var pendingAnchorOffsetPx by remember { mutableStateOf(0) }

    fun captureViewportAnchor() {
        val info = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { turnNumberFromRowKey(it.key) != null } ?: return
        val turnNum = turnNumberFromRowKey(info.key) ?: return
        val userMsgId = allTurns.getOrNull(turnNum - 1)?.userMessage?.id ?: return
        pendingAnchorMessageId = userMsgId
        pendingAnchorOffsetPx = info.offset
    }

    LaunchedEffect(olderMessages.size) {
        val anchorId = pendingAnchorMessageId ?: return@LaunchedEffect
        pendingAnchorMessageId = null
        if (olderMessages.isEmpty()) return@LaunchedEffect
        val newIdx = allTurns.indexOfFirst { it.userMessage.id == anchorId }
        if (newIdx >= 0) {
            listState.scrollToItem(turnStartRowIndex(newIdx), pendingAnchorOffsetPx.coerceAtLeast(0))
        }
        pendingAnchorOffsetPx = 0
    }

    // 回到底部 / 跟随新消息：按像素一直滚到真正尽头。
    // 之前用「算出来的行号」scrollToItem，行数估算偏一点、或者动画期间又来了新消息，
    // 就会停在半路 —— 这正是「点了一键到底却没到底」的原因。
    suspend fun scrollToVeryBottom() {
        var guard = 0
        while (listState.canScrollForward && guard < 400) {
            guard++
            listState.scroll { scrollBy(6_000f) }
        }
    }

    /**
     * 「从此处编辑」统一入口（1:1 对标 pi-web）：
     * 1) 把该条正文填回输入框（本地行为，立即生效）；
     * 2) 交给外层的 onEditFromMessage 做会话树导航（navigate_tree）——
     *    导航后这个节点之后的内容变成被放弃的分支，不再是活跃分支，所以刷新后就看不见了。
     *    之前只填输入框、后面的内容一直留着，继续发就是「旧尾巴 + 新分支」重复，与 pi-web 不一致。
     */
    val editFromMessage: (ChatItem) -> Unit = { msg ->
        inputText = msg.content
        coroutineScope.launch { scrollToVeryBottom() }
        onEditFromMessage?.invoke(msg)
    }

    // 监听软键盘 (IME) 弹出高度：当软键盘弹起时，自动将底部输入框与最新对话内容整体往上推，防止遮挡
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isImeVisible = imeBottomPx > 0

    LaunchedEffect(
        conversation.size,
        conversation.lastOrNull()?.content?.length,
        conversation.lastOrNull()?.thinking?.length,
        isAgentBusy,
        agentPhaseLabel,
        isImeVisible
    ) {
        if (conversation.isNotEmpty() && (!userJumpedToHistory || isImeVisible)) {
            if (!hasInitialScrolled) {
                // 首屏进入直接瞬间定位到底部，避免长列表逐条滚动造成的屏幕闪现
                scrollToVeryBottom()
                hasInitialScrolled = true
            } else if (!listState.canScrollForward || isImeVisible || isAgentBusy) {
                scrollToVeryBottom()
            }
        }
    }

    // 用户自己滑回底部时恢复「跟随新消息」（pi-web 同款）：
    // 否则只要点过一次时间轴/往上阅览，后续流式输出就再也不会自动跟到底部。
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }
            .collect { canForward ->
                if (!canForward && userJumpedToHistory) userJumpedToHistory = false
            }
    }

    // 滑到最顶端就自动向 PC 续批更早的记录（IntersectionObserver 的 Compose 等价写法：
    // 观察首个可见行索引）。必须等首屏「瞬间定位到底部」完成（hasInitialScrolled），
    // 否则刚进会话时索引本来就是 0，会立刻狂拉页并把视口钉在顶部而不是最新对话上。
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex, hasInitialScrolled, hasEarlierOnServer, isLoadingOlder) {
        if (hasInitialScrolled && !isLoadingOlder && hasEarlierOnServer && firstVisibleIndex <= 0) {
            captureViewportAnchor()
            onLoadOlder()
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
                        // 第一行：项目目录（图标变窄后可用宽度变大）
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = currentProject.ifBlank { "默认工作区" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = theme.textPrimary,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            // 唯一的例外状态：事件流重连中。正文里看不到这个信号，
                            // 所以退化成一个琥珀小点（不再占一整行文字）。
                            if (sseState == SseConnectionState.RECONNECTING) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .semantics { contentDescription = "事件流自动重连并同步中" },
                                    shape = CircleShape,
                                    color = Color(0xFFD29922)
                                ) {}
                            }
                        }

                        // 第二行：纯会话标题。
                        // 原先这一行是「状态点 + （重连文案 / Agent 阶段文案 / 会话标题）」三选一，
                        // 等于把标题行当状态栏用；正文与底部脉冲条已经能看到完整状态，这里不再重复报状态。
                        Text(
                            text = sessionTitle.ifBlank { "未命名会话" },
                            style = MaterialTheme.typography.bodySmall,
                            color = theme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                actions = {
                    // 顶栏只留会话级动作，图标统一 18dp + 34dp 紧凑热区，尽可能把宽度让给项目名。
                    // 顺序：置顶 → 用模型重写标题 → 工作目录变更 → 同步刷新（最右）。
                    // 「能力与设置中心」已从这里移除：会话列表顶栏的齿轮仍是入口，避免同屏两个入口。
                    IconButton(onClick = onTogglePin, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                            contentDescription = if (isPinned) "取消置顶" else "置顶会话",
                            tint = if (isPinned) theme.primary else theme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onAutoGenerateTitle, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.AutoFixHigh,
                            contentDescription = "用模型重写会话标题",
                            tint = theme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    // 工作目录 git 变更（GET /api/git/status + /api/git/diff）
                    IconButton(
                        onClick = {
                            showGitSheet = true
                            gitDiff = null
                            gitDiffTitle = null
                            isLoadingGit = true
                            coroutineScope.launch {
                                onLoadGitStatus().onSuccess { gitStatus = it }
                                    .onFailure { err ->
                                        android.widget.Toast.makeText(
                                            context,
                                            "读取 git 状态失败: ${err.message}",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                isLoadingGit = false
                            }
                        },
                        modifier = Modifier.size(34.dp)
                    ) {
                        Icon(
                            Icons.Default.AccountTree,
                            contentDescription = "工作目录变更",
                            tint = theme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onRefreshSession, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.Sync,
                            contentDescription = "重新加载会话（同步最新任务状态）",
                            tint = theme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
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
                // 1. 排队消息：对齐 pi-web 的紧凑面板（左侧 kind 标签 + 单行文本 + 右上角「放回输入框」）
                if (queuedMessages.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(theme.surfaceContainerLow)
                            .border(1.dp, theme.outline, RoundedCornerShape(6.dp))
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "QUEUED (${queuedMessages.size})",
                                fontSize = 9.5.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.4.sp,
                                color = theme.textMuted
                            )
                            // pi-web 的 recall：把排队内容全部放回输入框（服务端队列同步清空）
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        val recalled = queuedMessages.joinToString("\n\n") { it.text }
                                        inputText = if (inputText.isBlank()) recalled else "$recalled\n\n$inputText"
                                        onClearQueue()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardReturn,
                                    contentDescription = "放回输入框",
                                    modifier = Modifier.size(12.dp),
                                    tint = theme.textSecondary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "放回输入框",
                                    fontSize = 10.5.sp,
                                    color = theme.textSecondary
                                )
                            }
                        }
                        // 只铺前 4 条，避免排队把输入区顶到屏幕外（pi-web 桌面端不受此限）
                        queuedMessages.take(4).forEach { q ->
                            val isSteer = q.kind == "steer"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color.Transparent,
                                    border = BorderStroke(
                                        1.dp,
                                        if (isSteer) theme.primary.copy(alpha = 0.45f) else theme.outline
                                    )
                                ) {
                                    Text(
                                        text = if (isSteer) "steer" else "follow-up",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isSteer) theme.primary else theme.textMuted,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = q.text.replace('\n', ' ').trim(),
                                    fontSize = 11.5.sp,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (queuedMessages.size > 4) {
                            Text(
                                text = "还有 ${queuedMessages.size - 4} 条排队中…",
                                fontSize = 10.sp,
                                color = theme.textMuted,
                                modifier = Modifier.padding(start = 10.dp, top = 1.dp)
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
                                        submitMessage(inputText.trim(), SendKind.STEER)
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
                                        submitMessage(inputText.trim(), SendKind.FOLLOW_UP)
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

                        // ── 斜杠命令：在输入框里敲 "/" 就地展开命令列表 ──
                        // （不再于输入框左侧常驻一个命令按钮：日常输入用不上，还挤占一行）
                        val slashQuery = inputText
                            .takeIf { it.startsWith("/") && !it.contains('\n') }
                            ?.drop(1)
                        val showSlashMenu = slashQuery != null && !slashQuery.contains(' ')
                        val matchedCommands = remember(commands, slashQuery) {
                            val q = slashQuery?.trim().orEmpty()
                            if (q.isEmpty()) {
                                commands.take(8)
                            } else {
                                commands.filter {
                                    it.name.contains(q, ignoreCase = true) ||
                                        it.description.contains(q, ignoreCase = true)
                                }.take(8)
                            }
                        }
                        LaunchedEffect(showSlashMenu) {
                            if (showSlashMenu && commands.isEmpty() && !isLoadingCommands) {
                                isLoadingCommands = true
                                onLoadCommands().onSuccess { commands = it }
                                isLoadingCommands = false
                            }
                        }
                        if (showSlashMenu) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = theme.surfaceContainerLow,
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 2.dp)
                            ) {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    if (isLoadingCommands && commands.isEmpty()) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 1.6.dp,
                                                color = theme.primary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("正在读取命令列表…", fontSize = 11.sp, color = theme.textMuted)
                                        }
                                    } else if (matchedCommands.isEmpty()) {
                                        Text(
                                            text = "没有匹配的命令",
                                            fontSize = 11.5.sp,
                                            color = theme.textMuted,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                        )
                                    } else {
                                        LazyColumn(modifier = Modifier.heightIn(max = 216.dp)) {
                                            items(matchedCommands, key = { it.source + ":" + it.name }) { cmd ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable {
                                                            // 选中即填入输入框；尾部空格会让菜单自然收起，光标在命令后
                                                            inputText = "/${cmd.name} "
                                                        }
                                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "/${cmd.name}",
                                                        fontSize = 12.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = theme.primary
                                                    )
                                                    if (cmd.description.isNotBlank()) {
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = cmd.description,
                                                            fontSize = 10.5.sp,
                                                            color = theme.textMuted,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.weight(1f)
                                                        )
                                                    } else if (cmd.sourceLabel.isNotBlank()) {
                                                        Spacer(modifier = Modifier.weight(1f))
                                                    }
                                                    if (cmd.sourceLabel.isNotBlank()) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Text(
                                                            text = cmd.sourceLabel,
                                                            fontSize = 9.5.sp,
                                                            color = theme.textMuted
                                                        )
                                                    }
                                                }
                                            }
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
                                            submitMessage(inputText.trim(), SendKind.SEND)
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
            // 首屏正文还在路上：屏幕正中一枚加载圈（对上“白屏里什么都没有”的观感）
            if (isFirstScreenLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = theme.primary.copy(alpha = 0.85f)
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = if (allTurns.isNotEmpty()) 32.dp else 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 顶部加载哨兵：只占一行。触顶即自动向 PC 续批，加载中显示一枚细圆圈
                // （此前这里是一张占满一屏的「已折叠 N 条早期记录」大卡片，已按大厂 App 惯例移除）
                if (hasEarlierOnServer || isLoadingOlder) {
                    item(key = "history_top_loader") {
                        HistoryTopLoader(
                            isLoading = isLoadingOlder,
                            hiddenCount = earlierTurnCount,
                            onClick = {
                                captureViewportAnchor()
                                onLoadOlder()
                            }
                        )
                    }
                } else if (conversation.size > 6) {
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
                                        text = "本次已加载的起点 · 共 ${conversation.size} 条记录",
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
                val firstUserIdx = allTurns.firstOrNull()?.userIndex ?: conversation.size
                if (firstUserIdx > 0) {
                    val leadingSlice = conversation.subList(0, firstUserIdx.coerceAtMost(conversation.size))
                    items(leadingSlice, key = { it.id }) { item ->
                        ChatMessageItem(
                            item = item,
                            showModelHeader = false,
                            defaultModelName = selectedModel.displayName,
                            onApproveTool = onApproveTool,
                            onEditFromHere = editFromMessage,
                            onForkFromHere = onForkFromMessage,
                            onOpenBashOutput = { p, t -> openBashOutput(p, t) },
                            onOpenFilePreview = { p -> openFilePreview(p) },
                            isLiveStreamingTail = item.id == liveStreamingItemId
                        )
                    }
                }

                // 遍历渲染每个回合（对标 pi-web ProcessDetailsGroup 与最终回答）。
                // 全量渲染：LazyColumn 本身只组合可见项，无需再自己维护窗口 —— 之前那套
                // 「按条数切窗口 / 行数补偿锚定」正是各种空白与停半路的来源。
                allTurns.forEach { turn ->
                    // 1. 用户提问
                    item(key = "turn_${turn.turnNumber}_user_${turn.userMessage.id}") {
                        ChatMessageItem(
                            item = turn.userMessage,
                            showModelHeader = false,
                            defaultModelName = selectedModel.displayName,
                            onApproveTool = onApproveTool,
                            onEditFromHere = { msg ->
                                editFromMessage(msg)
                                android.widget.Toast.makeText(
                                    context,
                                    "已填入输入框；发送时将从这里开新分支，后面的内容不再属于当前会话",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            },
                            onForkFromHere = { userItem ->
                                onForkFromMessage?.invoke(userItem)
                            },
                            onOpenBashOutput = { p, t -> openBashOutput(p, t) },
                            onOpenFilePreview = { p -> openFilePreview(p) }
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
                                        onEditFromHere = editFromMessage,
                                        onForkFromHere = onForkFromMessage,
                                        onOpenBashOutput = { p, t -> openBashOutput(p, t) },
                                        onOpenFilePreview = { p -> openFilePreview(p) },
                                        isLiveStreamingTail = procItem.id == liveStreamingItemId
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
                                onEditFromHere = editFromMessage,
                                onForkFromHere = onForkFromMessage,
                                onOpenBashOutput = { p, t -> openBashOutput(p, t) },
                                onOpenFilePreview = { p -> openFilePreview(p) },
                                isLiveStreamingTail = ans.id == liveStreamingItemId
                            )
                        }
                    }
                }

                // 历史尚未到达时只画一条静默骨架（对齐 pi-web：不显示「正在同步历史…」类文案）
                if (conversation.isEmpty()) {
                    item(key = "chat_empty_hint") {
                        // 加载中不再放任何文案（居中加载圈在上一层 Box 里），
                        // 只有「确实没有对话」时才提示开始。
                        if (!isFirstScreenLoading) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "还没有对话，发送指令开始",
                                    fontSize = 12.sp,
                                    color = theme.textMuted
                                )
                            }
                        }
                    }
                }

                // 对标 pi-web：底部脉冲状态只在「已经有正文」时展示，首屏空白不再刷「正在等待模型...」
                if ((isAgentBusy || isCompacting) && conversation.isNotEmpty()) {
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
                        else if (allTurns.isNotEmpty()) activeTurnNumber = allTurns.last().turnNumber
                    }
                }

                PiWebChatMinimap(
                    turns = allTurns,
                    activeTurnNumber = activeTurnNumber,
                    hasEarlierOnServer = hasEarlierOnServer,
                    earlierTurnCount = earlierTurnCount,
                    isLoadingEarlier = isLoadingOlder,
                    onLoadEarlier = onLoadOlder,
                    onJumpToUserMessage = { turn ->
                        userJumpedToHistory = true
                        coroutineScope.launch {
                            val turnIdx = allTurns.indexOfFirst { it.turnNumber == turn.turnNumber }
                            if (turnIdx >= 0) {
                                // 用瞬时定位而不是动画：动画在流式输出/长列表下容易被下一次自动跟随打断，
                                // 表现出来就是「点了一下没反应」。
                                listState.scrollToItem(turnStartRowIndex(turnIdx))
                            }
                        }
                    },
                    onJumpToAssistant = { turn ->
                        userJumpedToHistory = true
                        coroutineScope.launch {
                            val turnIdx = allTurns.indexOfFirst { it.turnNumber == turn.turnNumber }
                            if (turnIdx >= 0) {
                                val t = allTurns[turnIdx]
                                // 落到真实存在的那一行：没最终答复就停在回合起点，
                                // 否则会多跑一格、直接跳到下一个回合。
                                var row = turnStartRowIndex(turnIdx)
                                if (t.processItems.isNotEmpty()) row += 1
                                if (t.finalAnswerItem != null) row += 1
                                val maxRow = listState.layoutInfo.totalItemsCount - 1
                                if (maxRow >= 0) row = row.coerceIn(0, maxRow)
                                listState.scrollToItem(row)
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
                                if (conversation.isNotEmpty()) scrollToVeryBottom()
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

    // ── 通用查看器：bash 完整输出 / 文件文本 / 图片 ──
    if (artifactTitle != null) {
        ModalBottomSheet(
            onDismissRequest = { artifactTitle = null },
            containerColor = theme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = artifactTitle ?: "",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                artifactMeta?.let {
                    Text(text = it, fontSize = 10.5.sp, color = theme.textMuted)
                }

                if (isLoadingArtifact) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.8.dp,
                            color = theme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("正在读取…", fontSize = 11.5.sp, color = theme.textMuted)
                    }
                } else {
                    artifactImage?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = artifactTitle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    artifactText?.let { body ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = theme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                                item {
                                    Text(
                                        text = body,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        color = theme.textPrimary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                            .horizontalScroll(rememberScrollState())
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text(
                                text = "复制全部",
                                fontSize = 11.5.sp,
                                color = theme.primary,
                                modifier = Modifier.clickable {
                                    clipboardManager.setText(AnnotatedString(body))
                                    android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showGitSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showGitSheet = false
                gitDiff = null
                gitDiffTitle = null
            },
            containerColor = theme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val st = gitStatus
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = gitDiffTitle?.let { it.substringAfterLast('/').substringAfterLast('\\') }
                            ?: "工作目录变更",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (gitDiffTitle != null) {
                        Text(
                            text = "返回列表",
                            fontSize = 11.5.sp,
                            color = theme.primary,
                            modifier = Modifier.clickable {
                                gitDiff = null
                                gitDiffTitle = null
                            }
                        )
                    }
                }

                // ── diff 视图 ──
                if (gitDiffTitle != null) {
                    if (isLoadingGitDiff) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = theme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在读取 diff…", fontSize = 11.5.sp, color = theme.textMuted)
                        }
                    } else {
                        val d = gitDiff
                        if (d == null || !d.supported || d.patch.isBlank()) {
                            Text(
                                text = if (d != null && !d.supported) "该文件不支持 diff（可能是二进制）" else "没有可显示的 diff",
                                fontSize = 11.5.sp,
                                color = theme.textMuted
                            )
                        } else {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = theme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                                    item {
                                        Text(
                                            text = d.patch,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            lineHeight = 14.sp,
                                            color = theme.textPrimary,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(8.dp)
                                                .horizontalScroll(rememberScrollState())
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // ── 文件列表视图 ──
                    if (isLoadingGit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.8.dp,
                                color = theme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("正在读取 git 状态…", fontSize = 11.5.sp, color = theme.textMuted)
                        }
                    } else if (st == null || !st.isGitRepository) {
                        Text(
                            text = "当前工作目录不是 git 仓库",
                            fontSize = 11.5.sp,
                            color = theme.textMuted
                        )
                    } else {
                        Text(
                            text = "＋${st.additions}  －${st.deletions} · ${st.files.size} 个文件变更",
                            fontSize = 11.sp,
                            color = theme.textMuted
                        )
                        if (st.files.isEmpty()) {
                            Text("工作目录干净，没有未提交变更", fontSize = 11.5.sp, color = theme.textMuted)
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                                items(st.files, key = { it.filePath }) { f ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                gitDiffTitle = f.filePath
                                                gitDiff = null
                                                isLoadingGitDiff = true
                                                coroutineScope.launch {
                                                    onLoadGitDiff(f.filePath).onSuccess { gitDiff = it }
                                                        .onFailure { err ->
                                                            android.widget.Toast.makeText(
                                                                context,
                                                                "读取 diff 失败: ${err.message}",
                                                                android.widget.Toast.LENGTH_SHORT
                                                            ).show()
                                                        }
                                                    isLoadingGitDiff = false
                                                }
                                            }
                                            .padding(vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = (f.code.ifBlank { f.status }).take(2),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = when {
                                                f.code.startsWith("D") || f.status == "deleted" -> Color(0xFFCF222E)
                                                f.code.startsWith("A") || f.status == "added" || f.code == "??" -> Color(0xFF2EA043)
                                                else -> Color(0xFFD29922)
                                            },
                                            modifier = Modifier.width(26.dp)
                                        )
                                        Text(
                                            text = f.filePath
                                                .removePrefix(st.repositoryRoot)
                                                .trimStart('/', '\\'),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
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
    onEditFromHere: ((ChatItem) -> Unit)? = null,
    onForkFromHere: ((ChatItem) -> Unit)? = null,
    /** 打开 bash 完整输出：由 ChatScreen 注入，工具卡里才有入口 */
    onOpenBashOutput: ((path: String, title: String) -> Unit)? = null,
    /** 打开文件预览：由 ChatScreen 注入 */
    onOpenFilePreview: ((path: String) -> Unit)? = null,
    /** 是否为「当前唯一在流式的那条」（对标 pi-web 的单数 streamingMessage）：只有它为真才挂实时指标 */
    isLiveStreamingTail: Boolean = false
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
        // 流式期间在模型名右侧追加「↓ 估算 token + 彩色 t/s」(StreamingSpeedBadge)。
        // 注意：过程折叠组里 showModelHeader=false，所以流式徽标的显示条件不能挂在它上面，
        // 否则「最后一轮正在跑」时（那条流式气泡被算进 processItems）根本看不到速度。
        val showSpeedBadge = item.role == MessageRole.AGENT && item.isStreaming && isLiveStreamingTail
        if (showModelHeader || showSpeedBadge) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 流式期间名字必须一起显示（pi-web 的 header 是「模型名 ↓ token [t/s]」整体）
                Text(
                    text = item.modelName ?: defaultModelName,
                    fontSize = 11.sp,
                    color = theme.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (showSpeedBadge) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StreamingSpeedBadge(item)
                }
            }
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
                                    onEditFromHere?.invoke(item)
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
                    // 先落到局部变量：filePath 是自定义 getter，无法 smart cast
                    val toolFilePath = tool.filePath
                    val toolFullOutputPath = tool.fullOutputPath
                    PiWebToolCallCard(
                        tool = if (mergedMedia.size != tool.mediaList.size) tool.copy(mediaList = mergedMedia) else tool,
                        expanded = isToolExpanded || tool.requiresApproval,
                        onToggle = { isToolExpanded = !isToolExpanded },
                        onCopyText = { text -> clipboardManager.setText(AnnotatedString(text)) },
                        onApproveTool = onApproveTool,
                        onOpenFullOutput = if (onOpenBashOutput == null || toolFullOutputPath == null) null else {
                            { onOpenBashOutput(toolFullOutputPath, "${tool.toolName} 完整输出") }
                        },
                        onOpenFile = if (onOpenFilePreview == null || toolFilePath == null) null else {
                            { onOpenFilePreview(toolFilePath) }
                        }
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
    onApproveTool: (approved: Boolean) -> Unit,
    /** 有 fullOutputPath 时提供「完整输出」入口（bash 长命令聊天里只有截断预览） */
    onOpenFullOutput: (() -> Unit)? = null,
    /** 有绝对文件路径时提供「查看文件」入口 */
    onOpenFile: (() -> Unit)? = null
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                onOpenFile?.let { open ->
                                    Text(
                                        text = "查看文件",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.primary,
                                        modifier = Modifier.clickable { open() }
                                    )
                                }
                                onOpenFullOutput?.let { open ->
                                    Text(
                                        text = "完整输出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.primary,
                                        modifier = Modifier.clickable { open() }
                                    )
                                }
                                Text(
                                    text = "复制输出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.primary,
                                    modifier = Modifier.clickable { onCopyText(tool.output) }
                                )
                            }
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
                text = if (hiddenCount > 0) "上滑加载更早的 $hiddenCount 轮" else "上滑加载更早的记录",
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

/** 提交类型：普通发送 / 实时引导 / 后续排队 */
private enum class SendKind { SEND, STEER, FOLLOW_UP }

/**
 * 把用户挑的图片读成 pi 要求的格式：type=image、data=纯 base64、mimeType=image/xxx。
 * 返回 (可发送列表, 被跳过的张数)；超过 10MB 或读取失败的一律跳过，绝不把半截 base64 发上去。
 */
private fun encodePickedImages(
    context: android.content.Context,
    picked: List<ChatAttachment>
): Pair<List<ChatImageInput>, Int> {
    val images = mutableListOf<ChatImageInput>()
    var skipped = 0
    for (att in picked) {
        if (!att.isImage) continue
        if (images.size >= com.pi.app.network.PiApiClient.MAX_PROMPT_IMAGES) {
            skipped++
            continue
        }
        try {
            val mime = context.contentResolver.getType(att.uri)?.takeIf { it.startsWith("image/") } ?: "image/png"
            val bytes = context.contentResolver.openInputStream(att.uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty() || bytes.size > MAX_PROMPT_IMAGE_BYTES) {
                skipped++
                continue
            }
            images.add(
                ChatImageInput(
                    mimeType = mime,
                    base64Data = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                )
            )
        } catch (e: Exception) {
            skipped++
        }
    }
    return images to skipped
}

/** 单张图片上限：pi 服务端校验为解码后 ≤10MB */
private const val MAX_PROMPT_IMAGE_BYTES = 10 * 1024 * 1024

/** 读取待上传文件的原始字节；超过 25MB（pi 服务端单文件上限）或读取失败的一律跳过 */
private fun readPickedFiles(
    context: android.content.Context,
    picked: List<ChatAttachment>
): List<UploadFileInput> {
    val out = mutableListOf<UploadFileInput>()
    for (att in picked) {
        try {
            val bytes = context.contentResolver.openInputStream(att.uri)?.use { it.readBytes() } ?: continue
            if (bytes.isEmpty() || bytes.size > MAX_UPLOAD_FILE_BYTES) continue
            out.add(UploadFileInput(name = att.name.ifBlank { "attachment" }, bytes = bytes))
        } catch (e: Exception) {
            // 单个文件失败不影响其它文件
        }
    }
    return out
}

/** 单文件上限：pi 服务端限制 25MB */
private const val MAX_UPLOAD_FILE_BYTES = 25 * 1024 * 1024
