package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.*
import com.pi.app.ui.components.MarkdownRenderer
import com.pi.app.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

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
    isSpeaking: Boolean = false,
    onBack: () -> Unit,
    onRefreshSession: () -> Unit = {},
    onProjectSwitch: (ProjectInfo) -> Unit = {},
    onOpenModelSheet: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onCompactContext: () -> Unit,
    onStopAgent: () -> Unit,
    onSendMessage: (String) -> Unit,
    onSendSteer: (String) -> Unit,
    onSendFollowUp: (String) -> Unit,
    onClearQueue: () -> Unit,
    onApproveTool: (approved: Boolean) -> Unit,
    onToggleSpeakSummary: () -> Unit,
    onStartVoiceInput: () -> Unit,
    onStopVoiceInput: () -> Unit,
    isVoiceRecording: Boolean,
    voicePartialText: String
) {
    val theme = LocalAppTheme.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showHudDialog by remember { mutableStateOf(false) }

    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.content?.length,
        messages.lastOrNull()?.thinking?.length,
        isAgentBusy,
        agentPhaseLabel
    ) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(voicePartialText) {
        if (voicePartialText.isNotBlank()) {
            inputText = voicePartialText
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

                // 2. 录音状态条
                AnimatedVisibility(visible = isVoiceRecording) {
                    Surface(
                        color = Color(0xFF2EA043).copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, Color(0xFF2EA043).copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF2EA043))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (voicePartialText.isBlank()) "正在倾听...再次轻触麦克风即可提交" else voicePartialText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = theme.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = "完成提交",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2EA043),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onStopVoiceInput() }
                            )
                        }
                    }
                }

                // 3. 核心输入框容器
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(1.dp, theme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                placeholder = {
                                    Text(
                                        text = if (isAgentBusy) "立即引导 / 排队后续消息..." else "向 Agent 发送指令...",
                                        color = theme.textMuted,
                                        fontSize = 14.sp
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 4,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = theme.textPrimary,
                                    unfocusedTextColor = theme.textPrimary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )

                            // 右侧动作按钮组
                            if (!isAgentBusy) {
                                IconButton(
                                    onClick = {
                                        if (inputText.isNotBlank()) {
                                            onSendMessage(inputText.trim())
                                            inputText = ""
                                        }
                                    },
                                    enabled = inputText.isNotBlank(),
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            if (inputText.isNotBlank()) theme.primary else theme.surfaceContainerHigh,
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "发送",
                                        tint = if (inputText.isNotBlank()) Color.White else theme.textMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (inputText.isNotBlank()) theme.primary else theme.surfaceContainerHigh,
                                        modifier = Modifier.clickable(enabled = inputText.isNotBlank()) {
                                            onSendSteer(inputText.trim())
                                            inputText = ""
                                        }
                                    ) {
                                        Text(
                                            text = "➔ 引导",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (inputText.isNotBlank()) Color.White else theme.textMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = theme.surfaceContainerHigh,
                                        border = BorderStroke(1.dp, theme.outline),
                                        modifier = Modifier.clickable(enabled = inputText.isNotBlank()) {
                                            onSendFollowUp(inputText.trim())
                                            inputText = ""
                                        }
                                    ) {
                                        Text(
                                            text = "↑ 后续",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (inputText.isNotBlank()) theme.textPrimary else theme.textMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. 输入框底栏（模型+思考极简合并芯片，工具紧凑排列）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = theme.surfaceContainer,
                                border = BorderStroke(1.dp, theme.outline),
                                modifier = Modifier.clickable { onOpenModelSheet() }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Memory,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp),
                                        tint = theme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${selectedModel.displayName.take(16)} · ${thinkingLevel.value}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = theme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = theme.textSecondary
                                    )
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = theme.surfaceContainer,
                                    border = BorderStroke(1.dp, theme.outline),
                                    modifier = Modifier.clickable { onCompactContext() }
                                ) {
                                    Box(modifier = Modifier.padding(5.dp)) {
                                        if (isCompacting) {
                                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = theme.primary)
                                        } else {
                                            Icon(
                                                Icons.Default.Compress,
                                                contentDescription = "压缩上下文",
                                                modifier = Modifier.size(13.dp),
                                                tint = theme.textSecondary
                                            )
                                        }
                                    }
                                }

                                if (isAgentBusy) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFFCF222E),
                                        modifier = Modifier.clickable { onStopAgent() }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "停止",
                                                modifier = Modifier.size(12.dp),
                                                tint = Color.White
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "停止",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSpeaking) Color(0xFF2EA043).copy(alpha = 0.2f) else theme.surfaceContainer,
                                    border = BorderStroke(1.dp, if (isSpeaking) Color(0xFF2EA043) else theme.outline),
                                    modifier = Modifier.clickable { onToggleSpeakSummary() }
                                ) {
                                    Icon(
                                        if (isSpeaking) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                        contentDescription = "朗读",
                                        tint = if (isSpeaking) Color(0xFF2EA043) else theme.textSecondary,
                                        modifier = Modifier.padding(5.dp).size(13.dp)
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isVoiceRecording) Color(0xFF2EA043) else theme.surfaceContainer,
                                    modifier = Modifier.clickable {
                                        if (isVoiceRecording) onStopVoiceInput() else onStartVoiceInput()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = "语音输入",
                                        tint = if (isVoiceRecording) Color.White else theme.textSecondary,
                                        modifier = Modifier.padding(5.dp).size(13.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. 微型 Monospace 状态栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 3.dp)
                        .clickable { showHudDialog = true },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val percent = (tokenStats.contextUsagePercent * 100).toInt()
                    val hitRate = String.format("%.1f", tokenStats.cacheHitRate)
                    Text(
                        text = "Ctx: ${tokenStats.contextTokens / 1000}k/${tokenStats.maxContextWindow / 1000}k ($percent%) · In: ${tokenStats.inputTokens} · Out: ${tokenStats.outputTokens} · Cache: $hitRate%",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (tokenStats.contextUsagePercent > 0.85f) Color(0xFFCF222E) else theme.textMuted
                    )

                    Icon(
                        Icons.Default.Info,
                        contentDescription = "HUD",
                        modifier = Modifier.size(11.dp),
                        tint = theme.textMuted
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(messages, key = { _, it -> it.id }) { idx, item ->
                val prevItem = messages.getOrNull(idx - 1)
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
    val timeFormatted = remember(item.timestamp) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(item.timestamp))
    }

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
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = theme.primaryContainer,
                        border = BorderStroke(1.dp, theme.outline),
                        modifier = Modifier.widthIn(max = 330.dp)
                    ) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                            MarkdownRenderer(
                                content = item.content,
                                isUser = true
                            )
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
                        expanded = isThinkingExpanded,
                        onToggle = { isThinkingExpanded = !isThinkingExpanded }
                    )
                }

                // 2. 正文回复（标准 CommonMark + GFM 富文本渲染引擎）
                if (item.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = theme.surfaceContainerLow,
                        border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.7f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            MarkdownRenderer(
                                content = item.content,
                                isUser = false
                            )
                        }
                    }
                }
            }

            MessageRole.TOOL -> {
                val tool = item.toolCall
                if (tool != null) {
                    PiWebToolCallCard(
                        tool = tool,
                        expanded = isToolExpanded,
                        onToggle = { isToolExpanded = !isToolExpanded },
                        onCopyText = { clipboardManager.setText(AnnotatedString(it)) },
                        onApproveTool = onApproveTool
                    )
                }
            }

            MessageRole.SYSTEM -> {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.labelSmall,
                        color = theme.textMuted
                    )
                }
            }
        }

        // 对标 pi-web MessageView.tsx 底部 Token 用量与时间戳栏：
        // 左侧: "2,254 in · 1,546 out · 210,686 cache R"   右侧: "16:37"
        if (!item.usageSummary.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 2.dp, vertical = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.usageSummary,
                    fontSize = 11.sp,
                    color = theme.textMuted
                )
                if (!item.isStreaming) {
                    Text(
                        text = timeFormatted,
                        fontSize = 10.5.sp,
                        color = theme.textMuted
                    )
                }
            }
        }
    }
}

/**
 * 1:1 还原 pi-web MessageView.tsx 中的 ThinkingBlock 组件：
 * [💡 The platform's role needs to be refined; limit UI-specific instructions in prompts...  14s]
 */
@Composable
fun PiWebThinkingCard(
    thinking: String,
    durationSec: Int?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val theme = LocalAppTheme.current
    val previewText = remember(thinking) {
        thinking
            .replace(Regex("[#*`_~>]+"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = theme.background,
        border = BorderStroke(1.dp, theme.outline),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = if (expanded) Alignment.Top else Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Lightbulb,
                contentDescription = "思考过程",
                modifier = Modifier
                    .size(14.dp)
                    .padding(top = if (expanded) 2.dp else 0.dp),
                tint = if (expanded) Color(0xFFD4A017) else theme.textSecondary
            )

            if (!expanded) {
                Text(
                    text = previewText.ifBlank { "..." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            } else {
                Text(
                    text = thinking.trim(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    lineHeight = 17.sp,
                    color = theme.textSecondary,
                    modifier = Modifier.weight(1f)
                )
            }

            if (durationSec != null && durationSec > 0) {
                Text(
                    text = "${durationSec}s",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = theme.textMuted
                )
            }
        }
    }
}

/**
 * 1:1 还原 pi-web MessageView.tsx 中的 ToolCallBlock 组件：
 * - 常规工具 (read / write / edit / bash 等)：清透绿框 rgba(34,197,94,0.28) + 柔和绿底 rgba(34,197,94,0.05) + 绿色粗体工具名
 * - 异常或特殊执行工具 (isError / ctx_execute_file / requiresApproval)：清透珊瑚红框 rgba(248,113,113,0.45) + 柔和红底 + 红色粗体工具名
 * - 头部紧凑展示：[toolName]  [参数单行预览 (如文件路径或命令)]   [0.3s] [⌄]
 * - 展开后展示结构化键值参数与终端输出
 */
@Composable
fun PiWebToolCallCard(
    tool: ToolCallInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onCopyText: (String) -> Unit,
    onApproveTool: (Boolean) -> Unit
) {
    val theme = LocalAppTheme.current
    val isRedAccent = tool.isError ||
        tool.requiresApproval ||
        tool.toolName.startsWith("ctx_", ignoreCase = true)

    val borderColor = if (isRedAccent) {
        Color(0xFFF87171).copy(alpha = 0.45f)
    } else {
        Color(0xFF22C55E).copy(alpha = 0.30f)
    }

    val containerBg = if (isRedAccent) {
        Color(0xFFF87171).copy(alpha = if (theme.isDark) 0.08f else 0.05f)
    } else {
        Color(0xFF22C55E).copy(alpha = if (theme.isDark) 0.07f else 0.04f)
    }

    val toolNameColor = if (isRedAccent) {
        if (theme.isDark) Color(0xFFF87171) else Color(0xFFDC2626)
    } else {
        if (theme.isDark) Color(0xFF4ADE80) else Color(0xFF16A34A)
    }

    val previewSummary = remember(tool.preview, tool.command) {
        tool.preview?.takeIf { it.isNotBlank() }
            ?: tool.command?.replace('\n', ' ')?.trim()?.take(100)
            ?: ""
    }

    Surface(
        shape = RoundedCornerShape(7.dp),
        color = containerBg,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // ── Tool Call 紧凑单行头部 (对标 pi-web) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = tool.toolName,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.5.sp,
                    color = toolNameColor
                )

                Text(
                    text = previewSummary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.5.sp,
                    color = theme.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (tool.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(11.dp),
                        strokeWidth = 1.6.dp,
                        color = toolNameColor
                    )
                }

                tool.formattedDuration?.let { durText ->
                    Text(
                        text = durText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = theme.textMuted
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(15.dp),
                    tint = theme.textMuted
                )
            }

            // ── 展开详情区：结构化参数与配对结果输出 ──
            if (expanded) {
                HorizontalDivider(color = borderColor.copy(alpha = 0.35f), thickness = 0.8.dp)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(theme.surfaceContainerLow.copy(alpha = 0.85f))
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // 1. 若有结构化字段，按 pi-web ToolFieldRows 样式渲染对齐键值表
                    if (tool.fields.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            tool.fields.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = key,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = theme.textMuted,
                                        modifier = Modifier.widthIn(min = 68.dp).padding(end = 10.dp)
                                    )
                                    Text(
                                        text = value,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = theme.textPrimary,
                                        modifier = Modifier.weight(1f)
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
                            Text(
                                text = "输入参数",
                                style = MaterialTheme.typography.labelSmall,
                                color = theme.textMuted
                            )
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
