package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.*
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionTitle: String,
    currentProject: String,
    projects: List<ProjectInfo>,
    selectedModel: ModelInfo,
    thinkingLevel: ThinkingLevel,
    tokenStats: TokenStats,
    messages: List<ChatItem>,
    isAgentBusy: Boolean,
    queuedMessages: List<QueuedMessage> = emptyList(),
    isCompacting: Boolean = false,
    isSpeaking: Boolean = false,
    onBack: () -> Unit,
    onProjectSwitch: (ProjectInfo) -> Unit,
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
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showHudDialog by remember { mutableStateOf(false) }
    var showProjectDropdown by remember { mutableStateOf(false) }

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
        containerColor = TermiusBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TermiusBlack,
                    titleContentColor = TermiusTextPrimary
                ),
                title = {
                    Column {
                        // 顶部工作区切换
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures { showProjectDropdown = true }
                            }
                        ) {
                            Text(
                                text = currentProject.ifBlank { "默认工作区" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                color = TermiusTextPrimary
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TermiusTextSecondary)

                            DropdownMenu(
                                expanded = showProjectDropdown,
                                onDismissRequest = { showProjectDropdown = false },
                                modifier = Modifier.background(TermiusSurface)
                            ) {
                                Text(
                                    "工作区",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TermiusTextMuted
                                )
                                projects.forEach { proj ->
                                    DropdownMenuItem(
                                        text = { Text(proj.name, color = TermiusTextPrimary) },
                                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null, tint = TermiusBlue) },
                                        onClick = {
                                            showProjectDropdown = false
                                            onProjectSwitch(proj)
                                        }
                                    )
                                }
                            }
                        }

                        // 状态与简要描述
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isAgentBusy) TermiusBlue else TermiusGreen
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAgentBusy) "Agent 正在执行..." else "${sessionTitle.take(16)} (就绪)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TermiusTextSecondary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = TermiusTextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenCapabilities) {
                        Icon(Icons.Default.Tune, contentDescription = "能力中心", tint = TermiusBlue)
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TermiusBlack)
                    .navigationBarsPadding()
            ) {
                // 1. 排队消息提示栏 (Queued messages bar)
                if (queuedMessages.isNotEmpty()) {
                    Surface(
                        color = Color(0xFF1E2633),
                        border = BorderStroke(1.dp, TermiusBorder),
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
                                    color = TermiusBlue.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "${queuedMessages.size} 条排队",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        color = TermiusBlue
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = queuedMessages.first().text.take(24),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TermiusTextSecondary,
                                    maxLines = 1
                                )
                            }

                            Text(
                                text = "撤回 ↺",
                                style = MaterialTheme.typography.labelSmall,
                                color = TermiusBlue,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { onClearQueue() }
                            )
                        }
                    }
                }

                // 2. 录音状态条
                AnimatedVisibility(visible = isVoiceRecording) {
                    Surface(
                        color = Color(0xFF142C1D),
                        border = BorderStroke(1.dp, TermiusGreen.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TermiusGreen)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (voicePartialText.isBlank()) "正在倾听语音输入..." else voicePartialText,
                                style = MaterialTheme.typography.bodySmall,
                                color = TermiusTextPrimary
                            )
                        }
                    }
                }

                // 3. 核心输入框容器（根据 Agent 是否繁忙进行动态切换）
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = TermiusSurface,
                    border = BorderStroke(1.dp, TermiusBorder),
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
                                        color = TermiusTextMuted,
                                        fontSize = 14.sp
                                    )
                                },
                                modifier = Modifier.weight(1f),
                                maxLines = 4,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedTextColor = TermiusTextPrimary,
                                    unfocusedTextColor = TermiusTextPrimary,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent
                                )
                            )

                            // 右侧动作按钮组
                            if (!isAgentBusy) {
                                // 空闲状态：普通发送
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
                                            if (inputText.isNotBlank()) TermiusBlue else TermiusSurfaceVariant,
                                            CircleShape
                                        )
                                ) {
                                    Icon(
                                        Icons.Default.ArrowUpward,
                                        contentDescription = "发送",
                                        tint = if (inputText.isNotBlank()) Color.White else TermiusTextMuted,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else {
                                // 正在执行状态：引导 (Steer) 与 后续 (Follow-up) 双胶囊
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (inputText.isNotBlank()) TermiusBlue else TermiusSurfaceVariant,
                                        modifier = Modifier.clickable(enabled = inputText.isNotBlank()) {
                                            onSendSteer(inputText.trim())
                                            inputText = ""
                                        }
                                    ) {
                                        Text(
                                            text = "➔ 引导",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (inputText.isNotBlank()) Color.White else TermiusTextMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = TermiusSurfaceVariant,
                                        border = BorderStroke(1.dp, TermiusBorder),
                                        modifier = Modifier.clickable(enabled = inputText.isNotBlank()) {
                                            onSendFollowUp(inputText.trim())
                                            inputText = ""
                                        }
                                    ) {
                                        Text(
                                            text = "↑ 后续",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (inputText.isNotBlank()) TermiusTextPrimary else TermiusTextMuted,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. 输入框底栏（1:1 还原截图 5 精髓工具栏）
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // 左侧：多模态图片 + 模型选择器
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 图片多模态
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color.Transparent,
                                    modifier = Modifier.clickable { /* 相册选择 */ }
                                ) {
                                    Icon(
                                        Icons.Default.Image,
                                        contentDescription = "附加图片",
                                        tint = TermiusTextSecondary,
                                        modifier = Modifier.padding(4.dp).size(18.dp)
                                    )
                                }

                                // 模型切换按钮
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TermiusSurfaceVariant,
                                    border = BorderStroke(1.dp, TermiusBorder),
                                    modifier = Modifier.clickable { onOpenModelSheet() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Memory,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = TermiusBlue
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = selectedModel.displayName.take(14),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = TermiusTextPrimary
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = TermiusTextSecondary
                                        )
                                    }
                                }
                            }

                            // 右侧：思考级别 + 压缩 + 停止 + 朗读 + 语音
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 思考级别
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TermiusSurfaceVariant,
                                    border = BorderStroke(1.dp, TermiusBorder),
                                    modifier = Modifier.clickable { onOpenModelSheet() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Psychology,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = TermiusBlue
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = thinkingLevel.value,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = TermiusTextPrimary
                                        )
                                    }
                                }

                                // 压缩上下文按钮
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = TermiusSurfaceVariant,
                                    border = BorderStroke(1.dp, TermiusBorder),
                                    modifier = Modifier.clickable { onCompactContext() }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isCompacting) {
                                            CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = TermiusBlue)
                                        } else {
                                            Icon(
                                                Icons.Default.Compress,
                                                contentDescription = "压缩上下文",
                                                modifier = Modifier.size(12.dp),
                                                tint = TermiusTextSecondary
                                            )
                                        }
                                    }
                                }

                                // 停止执行按钮 (仅执行中呈现鲜艳警示红)
                                if (isAgentBusy) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = TermiusRed.copy(alpha = 0.2f),
                                        border = BorderStroke(1.dp, TermiusRed),
                                        modifier = Modifier.clickable { onStopAgent() }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Stop,
                                                contentDescription = "中止",
                                                modifier = Modifier.size(12.dp),
                                                tint = TermiusRed
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = "停止",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TermiusRed,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // 语音朗读切换 (TTS)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSpeaking) TermiusGreen.copy(alpha = 0.2f) else TermiusSurfaceVariant,
                                    border = BorderStroke(1.dp, if (isSpeaking) TermiusGreen else TermiusBorder),
                                    modifier = Modifier.clickable { onToggleSpeakSummary() }
                                ) {
                                    Icon(
                                        if (isSpeaking) Icons.Default.VolumeUp else Icons.Default.VolumeMute,
                                        contentDescription = "朗读",
                                        tint = if (isSpeaking) TermiusGreen else TermiusTextSecondary,
                                        modifier = Modifier.padding(4.dp).size(14.dp)
                                    )
                                }

                                // 语音录入 (STT)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isVoiceRecording) TermiusGreen else TermiusSurfaceVariant,
                                    modifier = Modifier.clickable {
                                        if (isVoiceRecording) onStopVoiceInput() else onStartVoiceInput()
                                    }
                                ) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = "语音输入",
                                        tint = if (isVoiceRecording) Color.Black else TermiusTextSecondary,
                                        modifier = Modifier.padding(4.dp).size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 5. 微型 Monospace 极客状态小字 (实时呈现 Token 与上下文占比)
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
                        text = "Ctx: ${tokenStats.contextTokens / 1000}k/${tokenStats.maxContextWindow / 1000}k ($percent%)  ·  In: ${tokenStats.inputTokens}  ·  Out: ${tokenStats.outputTokens}  ·  Cache: $hitRate%",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = if (tokenStats.contextUsagePercent > 0.85f) TermiusRed else TermiusTextMuted
                    )

                    Icon(
                        Icons.Default.Info,
                        contentDescription = "HUD",
                        modifier = Modifier.size(11.dp),
                        tint = TermiusTextMuted
                    )
                }
            }
        }
    ) { paddingValues ->
        // 中部消息展示流
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
            onDismiss = { showHudDialog = false }
        )
    }
}

@Composable
fun ChatMessageItem(
    item: ChatItem,
    onApproveTool: (approved: Boolean) -> Unit
) {
    var isThinkingExpanded by remember { mutableStateOf(false) }

    when (item.role) {
        MessageRole.USER -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF1E2633),
                    border = BorderStroke(1.dp, TermiusBorder),
                    modifier = Modifier.widthIn(max = 320.dp)
                ) {
                    Text(
                        text = item.content,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TermiusTextPrimary,
                        modifier = Modifier.padding(10.dp)
                    )
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
                        color = Color(0xFF141922),
                        border = BorderStroke(1.dp, TermiusBorder),
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
                                    Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusBlue)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("思考推理过程", style = MaterialTheme.typography.labelSmall, color = TermiusBlue)
                                }
                                Text(
                                    text = if (isThinkingExpanded) "收起 ▲" else "展开 ▼",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TermiusTextMuted
                                )
                            }
                            if (isThinkingExpanded) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.thinking,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = TermiusTextSecondary
                                )
                            }
                        }
                    }
                }

                // 2. 正文回复
                if (item.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = TermiusSurface,
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = TermiusTextPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }
        MessageRole.TOOL -> {
            val tool = item.toolCall
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFF12171E),
                border = BorderStroke(1.dp, TermiusBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusGreen)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "执行工具: ${tool?.toolName ?: "未知"}",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = TermiusGreen
                            )
                        }
                    }

                    if (!tool?.command.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = tool?.command ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusTextSecondary
                        )
                    }

                    if (tool?.requiresApproval == true && !tool.isApproved) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onApproveTool(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = TermiusGreen),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("批准执行", color = Color.Black, style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { onApproveTool(false) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = TermiusRed),
                                border = BorderStroke(1.dp, TermiusRed),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text("拒绝", style = MaterialTheme.typography.labelSmall)
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
                    color = TermiusTextMuted
                )
            }
        }
    }
}
