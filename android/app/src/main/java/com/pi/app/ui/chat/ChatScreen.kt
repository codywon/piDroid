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
import androidx.compose.runtime.*
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
import com.pi.app.ui.theme.*
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
    mediaGatewayConfig: MediaGatewayConfig = MediaGatewayConfig()
) {
    CompositionLocalProvider(LocalMediaGatewayConfig provides mediaGatewayConfig) {
    // 监听系统物理/手势返回：向左滑动时退回会话列表，而非直接退出应用
    BackHandler { onBack() }

    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showHudDialog by remember { mutableStateOf(false) }

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

    // 提取所有用户提问节点，用于右侧 pi-web 风格提问时间轴导航
    val userPrompts = remember(messages.size, messages.lastOrNull()?.id) {
        var order = 0
        messages.mapIndexedNotNull { index, item ->
            if (item.role == MessageRole.USER) {
                order++
                PromptTimelineEntry(
                    orderNumber = order,
                    messageIndex = index,
                    id = item.id,
                    content = item.content.replace('\n', ' ').trim(),
                    timestamp = item.timestamp
                )
            } else null
        }
    }

    // 记录用户是否正在通过时间轴或手动上翻回看历史，避免后台轮询或流式输出强制拉回底部打断阅读
    var userJumpedToHistory by remember(sessionTitle) { mutableStateOf(false) }

    // 针对超长会话的分页按需加载窗口：默认首屏渲染最近 50 条记录；支持向上追加 50 条或一键展开全部完整历史
    var visibleWindowCount by remember(sessionTitle) { mutableIntStateOf(50) }
    var isAllHistoryExpanded by remember(sessionTitle) { mutableStateOf(false) }
    var hasInitialScrolled by remember(sessionTitle) { mutableStateOf(false) }

    val windowStartIndex = if (isAllHistoryExpanded) {
        0
    } else {
        (messages.size - visibleWindowCount).coerceAtLeast(0)
    }
    val hasHiddenHistory = windowStartIndex > 0
    val showOriginBanner = !hasHiddenHistory && messages.size > 6

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
            val topHeaderCount = if (hasHiddenHistory || showOriginBanner) 1 else 0
            val visibleItemCount = (messages.size - windowStartIndex) + topHeaderCount
            val targetIdx = (visibleItemCount - 1).coerceAtLeast(0)
            if (!hasInitialScrolled) {
                // 首屏进入直接瞬间定位到底部，避免长列表逐条滚动造成的屏幕闪现
                listState.scrollToItem(targetIdx)
                hasInitialScrolled = true
            } else if (!listState.canScrollForward || isImeVisible || isAgentBusy) {
                listState.animateScrollToItem(targetIdx)
            }
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
                                modifier = Modifier.clickable { onClearQueue() }
                            )
                        }
                    }
                }

                val canSend = inputText.isNotBlank() || attachments.isNotEmpty()

                // 2. 附着在输入框上边沿上方的控制栏（最佳实践：释放输入框内部宽度，避免按钮挤占文本空间）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // 左侧：模型+思考档位胶囊芯片 + 圆形压缩上下文图标按钮
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = theme.surfaceContainer,
                            border = BorderStroke(1.dp, theme.outline),
                            modifier = Modifier.clickable { onOpenModelSheet() }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Memory,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp),
                                    tint = theme.primary
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "${selectedModel.displayName.take(18)} · ${thinkingLevel.value}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    color = theme.textPrimary
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

                        // 圆圈 + 压缩图标按钮
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
                    }

                    // 右侧：任务运行中展示精简“引导 / 后续”胶囊按钮（不占用下方输入框宽度）
                    if (isAgentBusy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (canSend) theme.primary else theme.surfaceContainerHigh,
                                border = BorderStroke(
                                    0.8.dp,
                                    if (canSend) theme.primary else theme.outline
                                ),
                                modifier = Modifier.clickable(enabled = canSend) {
                                    userJumpedToHistory = false
                                    val fullMsg = buildMessageWithAttachments(inputText.trim())
                                    onSendSteer(fullMsg)
                                    inputText = ""
                                }
                            ) {
                                Text(
                                    text = "➔ 引导",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (canSend) Color.White else theme.textMuted,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }

                            Surface(
                                shape = CircleShape,
                                color = theme.surfaceContainerHigh,
                                border = BorderStroke(0.8.dp, if (canSend) theme.primary.copy(alpha = 0.6f) else theme.outline),
                                modifier = Modifier.clickable(enabled = canSend) {
                                    userJumpedToHistory = false
                                    val fullMsg = buildMessageWithAttachments(inputText.trim())
                                    onSendFollowUp(fullMsg)
                                    inputText = ""
                                }
                            ) {
                                Text(
                                    text = "↑ 后续",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (canSend) theme.textPrimary else theme.textMuted,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
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
                                        text = if (isAgentBusy) "输入指令后点上方 引导 / 后续..." else "向 Agent 发送指令...",
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

                // 4. 底部极简图标化指标栏（小图标 + 百分比/精简数值 + 竖杠 | 分隔，点击弹出完整 HUD 详情）
                val percent = (tokenStats.contextUsagePercent * 100).toInt().coerceIn(0, 100)
                val hitRate = tokenStats.cacheHitRate.toInt().coerceIn(0, 100)
                val inTokens = TokenStats.formatTokenCompact(tokenStats.inputTokens)
                val outTokens = TokenStats.formatTokenCompact(tokenStats.outputTokens)
                val ctxColor = when {
                    percent >= 90 -> Color(0xFFCF222E)
                    percent >= 75 -> Color(0xFFD29922)
                    else -> theme.textSecondary
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp)
                        .clickable { showHudDialog = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // 1) 上下文占用百分比 (图标 + 百分比)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DataUsage,
                                contentDescription = "上下文占用",
                                modifier = Modifier.size(11.dp),
                                tint = ctxColor
                            )
                            Text(
                                text = "$percent%",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = ctxColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "|",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = theme.outline
                        )

                        // 2) 输入 Token (上箭头小图标 + 数值)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowUpward,
                                contentDescription = "输入 Token",
                                modifier = Modifier.size(10.5.dp),
                                tint = theme.textMuted
                            )
                            Text(
                                text = inTokens,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = theme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "|",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = theme.outline
                        )

                        // 3) 输出 Token (下箭头小图标 + 数值)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowDownward,
                                contentDescription = "输出 Token",
                                modifier = Modifier.size(10.5.dp),
                                tint = theme.textMuted
                            )
                            Text(
                                text = outTokens,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = theme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "|",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = theme.outline
                        )

                        // 4) 缓存命中率 (闪电小图标 + 百分比)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = "缓存命中率",
                                modifier = Modifier.size(11.dp),
                                tint = theme.primary.copy(alpha = 0.85f)
                            )
                            Text(
                                text = "$hitRate%",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.5.sp,
                                color = theme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // 右侧详情提示小图标（点击查看完整 HUD 弹窗）
                    Icon(
                        Icons.Default.Info,
                        contentDescription = "查看 Token 详情",
                        modifier = Modifier.size(12.dp),
                        tint = theme.textMuted
                    )
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
                    .padding(start = 12.dp, end = if (userPrompts.isNotEmpty()) 26.dp else 12.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 超长会话顶部按需分页加载栏（现代简洁大气高级胶囊设计 + 锚定视口位置）
                if (hasHiddenHistory) {
                    item(key = "history_window_loader") {
                        val nextBatchCount = windowStartIndex.coerceAtMost(50)
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = theme.surfaceContainerLow,
                            border = BorderStroke(0.9.dp, theme.primary.copy(alpha = 0.25f)),
                            shadowElevation = 2.dp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = theme.primary.copy(alpha = 0.14f),
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(
                                                imageVector = Icons.Default.History,
                                                contentDescription = null,
                                                tint = theme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                        Text(
                                            text = "已折叠 $windowStartIndex 条早期记录",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = theme.textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "共 ${messages.size} 条完整对话 · 点击向上回溯",
                                            fontSize = 10.5.sp,
                                            color = theme.textMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    // 按钮 1：加载前 N 条（保持当前阅读锚点，不跳回底部）
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = theme.surfaceContainerHigh,
                                        border = BorderStroke(0.8.dp, theme.outline),
                                        modifier = Modifier.clickable {
                                            val oldStart = windowStartIndex
                                            val newVisible = (visibleWindowCount + 50).coerceAtMost(messages.size)
                                            val newStart = (messages.size - newVisible).coerceAtLeast(0)
                                            val addedCount = (oldStart - newStart).coerceAtLeast(0)
                                            userJumpedToHistory = true
                                            visibleWindowCount = newVisible
                                            if (newStart == 0) {
                                                isAllHistoryExpanded = true
                                                onRefreshSession()
                                            }
                                            coroutineScope.launch {
                                                kotlinx.coroutines.yield()
                                                // 锚定在刚加载出来的分界点上方，方便用户顺滑向上翻阅新加载的记录
                                                listState.scrollToItem(addedCount.coerceAtLeast(0))
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.KeyboardArrowUp,
                                                contentDescription = null,
                                                tint = theme.textPrimary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "前 $nextBatchCount 条",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = theme.textPrimary
                                            )
                                        }
                                    }

                                    // 按钮 2：展开全部完整历史并滚动至任务起点
                                    Surface(
                                        shape = RoundedCornerShape(20.dp),
                                        color = theme.primary.copy(alpha = 0.16f),
                                        border = BorderStroke(0.8.dp, theme.primary.copy(alpha = 0.45f)),
                                        modifier = Modifier.clickable {
                                            userJumpedToHistory = true
                                            isAllHistoryExpanded = true
                                            visibleWindowCount = messages.size + 5000
                                            onRefreshSession()
                                            coroutineScope.launch {
                                                kotlinx.coroutines.yield()
                                                listState.scrollToItem(0)
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.UnfoldMore,
                                                contentDescription = null,
                                                tint = theme.primary,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "展开全部",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = theme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
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
                                        text = "任务起点 · 已完整展示全部 ${messages.size} 条记录",
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

                val windowedSlice = if (windowStartIndex == 0) messages else messages.subList(windowStartIndex, messages.size)
                itemsIndexed(windowedSlice, key = { _, it -> it.id }) { sliceIdx, item ->
                    val globalIdx = windowStartIndex + sliceIdx
                    val prevItem = messages.getOrNull(globalIdx - 1)
                    val showModelHeader = item.role != MessageRole.USER &&
                        !item.modelName.isNullOrBlank() &&
                        (prevItem == null || prevItem.role == MessageRole.USER || prevItem.usageSummary != null)

                    ChatMessageItem(
                        item = item,
                        showModelHeader = showModelHeader,
                        defaultModelName = selectedModel.displayName,
                        onApproveTool = onApproveTool
                    )
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

            // ── 对标 pi-web 右侧用户提问快捷时间轴 (Prompt Timeline Rail) ──
            if (userPrompts.isNotEmpty()) {
                val topHeaderOffset = if (hasHiddenHistory || showOriginBanner) 1 else 0
                val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                val currentGlobalFirstIdx = windowStartIndex + (firstVisible - topHeaderOffset).coerceAtLeast(0)
                val activePromptOrder = remember(currentGlobalFirstIdx, userPrompts) {
                    userPrompts.lastOrNull { it.messageIndex <= currentGlobalFirstIdx + 2 }?.orderNumber
                        ?: userPrompts.first().orderNumber
                }

                PiWebPromptTimelineRail(
                    prompts = userPrompts,
                    activeOrderNumber = activePromptOrder,
                    onJumpToPrompt = { entry ->
                        userJumpedToHistory = entry.orderNumber < userPrompts.size
                        coroutineScope.launch {
                            if (entry.messageIndex < windowStartIndex) {
                                visibleWindowCount = (messages.size - entry.messageIndex + 15).coerceAtLeast(visibleWindowCount)
                                if (messages.size - visibleWindowCount <= 0) {
                                    isAllHistoryExpanded = true
                                }
                                kotlinx.coroutines.yield()
                            }
                            val newStart = if (isAllHistoryExpanded) 0 else (messages.size - visibleWindowCount).coerceAtLeast(0)
                            val headerOffset = if (newStart > 0 || messages.size > 6) 1 else 0
                            val targetLazyIdx = (entry.messageIndex - newStart + headerOffset).coerceAtLeast(0)
                            listState.animateScrollToItem(targetLazyIdx)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 3.dp)
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
                    .padding(end = if (userPrompts.isNotEmpty()) 28.dp else 16.dp, bottom = 12.dp)
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
                                    val topHeaderCount = if (hasHiddenHistory || showOriginBanner) 1 else 0
                                    val visibleItemCount = (messages.size - windowStartIndex) + topHeaderCount
                                    listState.animateScrollToItem((visibleItemCount - 1).coerceAtLeast(0))
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
 * 对标 pi-web 右侧用户提问快捷时间轴：
 * - 右侧竖条展示所有用户提问刻度点，高亮当前视野所在提问
 * - 点击或上下滑动刻度条可实时预览用户当时提出了什么问题，并一键跳转至对应对话位置
 * - 点击顶部提问数徽章可展开完整提问目录抽屉
 */
@Composable
fun PiWebPromptTimelineRail(
    prompts: List<PromptTimelineEntry>,
    activeOrderNumber: Int,
    onJumpToPrompt: (PromptTimelineEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var previewEntry by remember { mutableStateOf<PromptTimelineEntry?>(null) }
    var showPromptListDrawer by remember { mutableStateOf(false) }
    var railHeightPx by remember { mutableIntStateOf(1) }

    val visibleTicks = remember(prompts) {
        if (prompts.size <= 16) prompts
        else {
            val step = (prompts.size.toFloat() / 15f)
            (0 until 15).map { i -> prompts[(i * step).toInt().coerceIn(0, prompts.lastIndex)] }
                .distinctBy { it.orderNumber } + listOf(prompts.last())
        }.distinctBy { it.orderNumber }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End
    ) {
        // 1. 悬浮预览气泡卡片：当点击或滑动时间轴节点时展示该次用户提问详情与跳转按钮
        AnimatedVisibility(
            visible = previewEntry != null && !showPromptListDrawer,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            previewEntry?.let { entry ->
                val timeStr = remember(entry.timestamp) {
                    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(entry.timestamp))
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, theme.primary.copy(alpha = 0.5f)),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .widthIn(min = 180.dp, max = 260.dp)
                        .clickable {
                            onJumpToPrompt(entry)
                            previewEntry = null
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    color = theme.primary.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "提问 #${entry.orderNumber}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                                Text(
                                    text = timeStr,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = theme.textMuted
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "跳转 ➔",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.primary
                                )
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭预览",
                                    tint = theme.textMuted,
                                    modifier = Modifier
                                        .size(13.dp)
                                        .clickable { previewEntry = null }
                                )
                            }
                        }

                        Text(
                            text = entry.content,
                            fontSize = 12.sp,
                            color = theme.textPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        // 2. 展开式全量提问时间轴面板
        AnimatedVisibility(
            visible = showPromptListDrawer,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = theme.surfaceContainerHigh,
                border = BorderStroke(1.dp, theme.outline),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .width(250.dp)
                    .heightIn(max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "用户提问时间轴 (${prompts.size})",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.textPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "收起",
                            tint = theme.textMuted,
                            modifier = Modifier
                                .size(15.dp)
                                .clickable { showPromptListDrawer = false }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        prompts.forEach { item ->
                            val isCurrent = item.orderNumber == activeOrderNumber
                            val tStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = if (isCurrent) theme.primary.copy(alpha = 0.14f) else theme.surfaceContainerLow,
                                border = BorderStroke(0.7.dp, if (isCurrent) theme.primary else theme.outline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onJumpToPrompt(item)
                                        showPromptListDrawer = false
                                    }
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(19.dp)
                                                    .background(
                                                        if (isCurrent) theme.primary else theme.surfaceContainer,
                                                        CircleShape
                                                    ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${item.orderNumber}",
                                                    style = TextStyle(
                                                        fontSize = 9.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isCurrent) Color.White else theme.textSecondary,
                                                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                                                        textAlign = TextAlign.Center
                                                    )
                                                )
                                            }
                                            Text(
                                                text = "提问 #${item.orderNumber}",
                                                fontSize = 10.5.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isCurrent) theme.primary else theme.textSecondary
                                            )
                                        }
                                        Text(
                                            text = tStr,
                                            fontSize = 9.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = theme.textMuted
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = item.content,
                                        fontSize = 11.5.sp,
                                        color = theme.textPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. 右侧紧凑刻度导航轨 (支持点击与手指上下滑动实时预览跳转)
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = theme.surfaceContainerLow.copy(alpha = 0.88f),
            border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.7f)),
            modifier = Modifier.width(20.dp)
        ) {
            Column(
                modifier = Modifier.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                // 顶部提问总数按钮：点击展开/收起完整提问列表 (圆形居中数字)
                Surface(
                    shape = CircleShape,
                    color = if (showPromptListDrawer) theme.primary else theme.primary.copy(alpha = 0.16f),
                    modifier = Modifier
                        .size(17.dp)
                        .clickable {
                            showPromptListDrawer = !showPromptListDrawer
                            previewEntry = null
                        }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${prompts.size}",
                            style = TextStyle(
                                fontSize = 8.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (showPromptListDrawer) Color.White else theme.primary,
                                platformStyle = PlatformTextStyle(includeFontPadding = false),
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }

                // 刻度轨道：支持上下滑动预览 + 点击节点预览并跳转
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { railHeightPx = it.height.coerceAtLeast(1) }
                        .pointerInput(visibleTicks) {
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    val idx = ((offset.y / railHeightPx.toFloat()) * visibleTicks.size)
                                        .toInt()
                                        .coerceIn(0, visibleTicks.lastIndex)
                                    previewEntry = visibleTicks[idx]
                                },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    val idx = ((change.position.y / railHeightPx.toFloat()) * visibleTicks.size)
                                        .toInt()
                                        .coerceIn(0, visibleTicks.lastIndex)
                                    previewEntry = visibleTicks[idx]
                                },
                                onDragEnd = {
                                    previewEntry?.let { onJumpToPrompt(it) }
                                }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    visibleTicks.forEach { tick ->
                        val isCurrent = tick.orderNumber == activeOrderNumber
                        val isPreviewed = previewEntry?.orderNumber == tick.orderNumber
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(12.dp)
                                .pointerInput(tick) {
                                    detectTapGestures(
                                        onTap = {
                                            if (previewEntry?.orderNumber == tick.orderNumber) {
                                                onJumpToPrompt(tick)
                                                previewEntry = null
                                            } else {
                                                previewEntry = tick
                                                onJumpToPrompt(tick)
                                            }
                                        }
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(if (isCurrent || isPreviewed) 12.dp else 7.dp)
                                    .height(if (isCurrent || isPreviewed) 3.5.dp else 2.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        when {
                                            isPreviewed -> Color(0xFFF59E0B)
                                            isCurrent -> theme.primary
                                            else -> theme.textMuted.copy(alpha = 0.55f)
                                        }
                                    )
                            )
                        }
                    }
                }
            }
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
    onApproveTool: (approved: Boolean) -> Unit
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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
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
                var sysExpanded by remember { mutableStateOf(false) }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { sysExpanded = !sysExpanded }
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    text = item.content.lineSequence().firstOrNull()?.trim() ?: "系统状态摘要",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = theme.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Icon(
                                imageVector = if (sysExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null,
                                tint = theme.textMuted,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        if (sysExpanded && item.content.lines().size > 1) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = item.content.lines().drop(1).joinToString("\n").trim(),
                                fontSize = 11.5.sp,
                                color = theme.textSecondary,
                                lineHeight = 17.sp
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

private fun formatCompactTokenCount(tokens: Long): String {
    return when {
        tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).removeSuffix(".0M") + if (tokens % 1_000_000L == 0L) "M" else ""
        tokens >= 10_000L -> "${tokens / 1000}k"
        tokens >= 1_000L -> String.format(Locale.US, "%.1fk", tokens / 1000.0).replace(".0k", "k")
        else -> tokens.toString()
    }
}
