package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    queuedMessages: List<QueuedMessage> = emptyList(),
    isCompacting: Boolean = false,
    isSpeaking: Boolean = false,
    onBack: () -> Unit,
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

    LaunchedEffect(messages.size, messages.lastOrNull()?.content) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
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
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isAgentBusy) theme.primary else Color(0xFF2EA043)
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAgentBusy) "Agent 正在执行..." else sessionTitle.take(24),
                                style = MaterialTheme.typography.bodySmall,
                                color = theme.textSecondary,
                                maxLines = 1
                            )
                        }
                    }
                },
                actions = {
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
                            // 左侧：模型 + 思考级别合并胶囊 (⚙️ GPT-6.1 · high ▾)
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
                                        text = "${selectedModel.displayName.take(12)} · ${thinkingLevel.value}",
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

                            // 右侧快捷工具群：压缩 + 停止 + 朗读 + 语音
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 压缩上下文
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

                                // 停止执行按钮 (仅执行中呈现醒目警示红底)
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

                                // 语音朗读切换 (TTS)
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

                                // 语音录入 (STT)
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

                // 5. 微型 Monospace 极客状态小字 (单行展示，不换行，不溢出)
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages, key = { it.id }) { item ->
                ChatMessageItem(
                    item = item,
                    onApproveTool = onApproveTool
                )
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

@Composable
fun ChatMessageItem(
    item: ChatItem,
    onApproveTool: (approved: Boolean) -> Unit
) {
    val theme = LocalAppTheme.current
    var isThinkingExpanded by remember { mutableStateOf(false) }
    var isToolExpanded by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    when (item.role) {
        MessageRole.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = theme.primaryContainer,
                    border = BorderStroke(1.dp, theme.outline),
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Box(modifier = Modifier.padding(10.dp)) {
                        MarkdownRenderer(
                            content = item.content,
                            isUser = true
                        )
                    }
                }
            }
        }
        MessageRole.AGENT -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 1. 思考过程折叠条
                if (!item.thinking.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = theme.surfaceContainer,
                        border = BorderStroke(1.dp, theme.outline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { isThinkingExpanded = !isThinkingExpanded },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(13.dp), tint = theme.primary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("思考推理过程", style = MaterialTheme.typography.labelSmall, color = theme.primary)
                                }
                                Text(
                                    text = if (isThinkingExpanded) "收起 ▲" else "展开 ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textMuted
                                )
                            }
                            if (isThinkingExpanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.thinking,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = theme.textSecondary
                                )
                            }
                        }
                    }
                }

                // 2. 正文回复（标准 CommonMark + GFM 富文本渲染引擎）
                if (item.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = theme.surfaceContainerLow,
                        border = BorderStroke(1.dp, theme.outline),
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
        }
        MessageRole.TOOL -> {
            val tool = item.toolCall
            // 遵循 pi-web 规范：默认完全折叠为单行微型胶囊 (⚡ ctx_execute (261ms) ▾)，点击才展开查看
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isToolExpanded = !isToolExpanded },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = theme.primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = buildString {
                                    append(tool?.toolName ?: "Tool")
                                    if ((tool?.durationMs ?: 0L) > 0) {
                                        append(" (${tool?.durationMs}ms)")
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = theme.primary
                            )
                        }
                        Icon(
                            if (isToolExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = theme.textMuted
                        )
                    }

                    // 展开内容：限制最大高度为 240dp 并支持内部滚动
                    if (isToolExpanded) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = theme.outline)
                        Spacer(modifier = Modifier.height(6.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            if (!tool?.command.isNullOrBlank()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$ 指令 / 参数",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.textSecondary
                                    )
                                    Text(
                                        text = "复制",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.primary,
                                        modifier = Modifier.clickable {
                                            clipboardManager.setText(AnnotatedString(tool?.command ?: ""))
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Surface(
                                    color = theme.surfaceContainer,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = tool?.command ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = theme.textPrimary,
                                        modifier = Modifier.padding(6.dp)
                                    )
                                }
                            }

                            if (!tool?.output.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "终端输出",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.textSecondary
                                    )
                                    Text(
                                        text = "复制输出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = theme.primary,
                                        modifier = Modifier.clickable {
                                            clipboardManager.setText(AnnotatedString(tool?.output ?: ""))
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Surface(
                                    color = theme.surfaceContainer,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = tool?.output ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = theme.textSecondary,
                                        modifier = Modifier.padding(6.dp)
                                    )
                                }
                            }
                        }

                        if (tool?.requiresApproval == true && !tool.isApproved) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onApproveTool(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2EA043)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("批准执行", color = Color.White, style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { onApproveTool(false) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCF222E)),
                                    border = BorderStroke(1.dp, Color(0xFFCF222E)),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text("拒绝", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
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
}
