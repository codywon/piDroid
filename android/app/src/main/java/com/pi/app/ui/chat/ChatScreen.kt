package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import com.pi.app.data.model.*

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
    onBack: () -> Unit,
    onProjectSwitch: (ProjectInfo) -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenCapabilities: () -> Unit,
    onCompactContext: () -> Unit,
    onSendMessage: (String) -> Unit,
    onApproveTool: (approved: Boolean) -> Unit,
    onSpeakSummary: (String) -> Unit,
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        // 顶部项目快速切换下拉菜单
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures { showProjectDropdown = true }
                            }
                        ) {
                            Text(
                                text = currentProject.ifBlank { "默认项目" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)

                            DropdownMenu(
                                expanded = showProjectDropdown,
                                onDismissRequest = { showProjectDropdown = false }
                            ) {
                                Text(
                                    "快速切换工作区项目",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                                projects.forEach { proj ->
                                    DropdownMenuItem(
                                        text = { Text(proj.name) },
                                        leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                                        onClick = {
                                            showProjectDropdown = false
                                            onProjectSwitch(proj)
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (isAgentBusy) "⚡ 正在执行任务..." else "就绪",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAgentBusy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 能力与扩展中心 (Skills, MCP, Providers)
                    IconButton(onClick = onOpenCapabilities) {
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "扩展中心")
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
            ) {
                // 1. 录音状态浮窗
                AnimatedVisibility(visible = isVoiceRecording) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (voicePartialText.isBlank()) "正在倾听语音指令..." else voicePartialText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // 2. 继承自 pi-web 的“高能胶囊控制条”（Power Capsule Bar）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 模型快速切换胶囊
                    SuggestionChip(
                        onClick = onOpenModelSheet,
                        label = { Text(selectedModel.name.take(16), style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )

                    // 思考深度胶囊
                    SuggestionChip(
                        onClick = onOpenModelSheet,
                        label = { Text(thinkingLevel.label.take(6), style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )

                    // 一键压缩上下文胶囊
                    SuggestionChip(
                        onClick = onCompactContext,
                        label = { Text("压缩上下文", style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )

                    // Token 与缓存命中率 HUD 胶囊
                    SuggestionChip(
                        onClick = { showHudDialog = true },
                        label = {
                            Text(
                                text = "${tokenStats.contextTokens / 1000}k/${tokenStats.maxContextWindow / 1000}k (命中率 ${String.format("%.0f", tokenStats.cacheHitRate)}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tokenStats.contextUsagePercent > 0.8f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        },
                        icon = { Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }

                // 3. 底部输入区（单手优化：左侧打字，右侧悬浮语音大按键）
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("输入或说话交代任务...") },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(24.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (inputText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                onSendMessage(inputText.trim())
                                inputText = ""
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(48.dp)
                                .background(
                                    if (isVoiceRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                    CircleShape
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures(
                                        onPress = {
                                            onStartVoiceInput()
                                            tryAwaitRelease()
                                            onStopVoiceInput()
                                        }
                                    )
                                }
                        ) {
                            Icon(Icons.Default.Mic, contentDescription = "按住说话", tint = Color.White)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatMessageCard(
                    message = message,
                    onApproveTool = onApproveTool,
                    onSpeakSummary = onSpeakSummary
                )
            }
        }
    }

    if (showHudDialog) {
        TokenStatsHudDialog(
            stats = tokenStats,
            onCompactClick = onCompactContext,
            onDismiss = { showHudDialog = false }
        )
    }
}

@Composable
fun ChatMessageCard(
    message: ChatItem,
    onApproveTool: (approved: Boolean) -> Unit,
    onSpeakSummary: (String) -> Unit
) {
    var isThinkingExpanded by remember { mutableStateOf(false) }

    when (message.role) {
        MessageRole.USER -> {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp, 16.dp, 2.dp, 16.dp)
                ) {
                    Text(
                        text = message.content,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        MessageRole.AGENT -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 2.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (!message.thinking.isNullOrBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Agent 思考过程",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    IconButton(
                                        onClick = { isThinkingExpanded = !isThinkingExpanded },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (isThinkingExpanded) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = message.thinking,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(text = message.content, style = MaterialTheme.typography.bodyMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onSpeakSummary(message.content) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "朗读", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("朗读摘要", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        MessageRole.TOOL -> {
            val tool = message.toolCall
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (tool?.requiresApproval == true && !tool.isApproved)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                    else
                        MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (tool?.requiresApproval == true && !tool.isApproved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🛠️ 执行工具: ${tool?.toolName ?: "Command"}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    if (!tool?.command.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            color = Color(0xFF1E1E1E),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "$ ${tool?.command}",
                                color = Color(0xFF4CAF50),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (tool?.requiresApproval == true && !tool.isApproved) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "⚠️ 此操作需要您的确认授权",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { onApproveTool(true) },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("批准执行")
                            }
                            OutlinedButton(onClick = { onApproveTool(false) }) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("拒绝")
                            }
                        }
                    }
                }
            }
        }
        MessageRole.SYSTEM -> {}
    }
}
