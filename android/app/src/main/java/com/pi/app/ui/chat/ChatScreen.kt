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
        containerColor = TermiusBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = TermiusBlack,
                    titleContentColor = TermiusTextPrimary
                ),
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(6.dp),
                                shape = CircleShape,
                                color = if (isAgentBusy) TermiusBlue else TermiusGreen
                            ) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isAgentBusy) "正在执行..." else "就绪",
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
                        Icon(Icons.Default.AutoFixHigh, contentDescription = "扩展", tint = TermiusTextSecondary)
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
                // 1. 录音状态浮窗
                AnimatedVisibility(visible = isVoiceRecording) {
                    Surface(
                        color = Color(0xFF1B3D23),
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
                                text = if (voicePartialText.isBlank()) "正在倾听..." else voicePartialText,
                                style = MaterialTheme.typography.bodySmall,
                                color = TermiusTextPrimary
                            )
                        }
                    }
                }

                // 2. Termius 极客风格高能胶囊条
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 模型
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TermiusSurface,
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.clickable { onOpenModelSheet() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusBlue)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(selectedModel.name.take(14), style = MaterialTheme.typography.labelSmall, color = TermiusTextPrimary)
                        }
                    }

                    // 思考
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TermiusSurface,
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.clickable { onOpenModelSheet() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Psychology, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusBlue)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(thinkingLevel.label.take(4), style = MaterialTheme.typography.labelSmall, color = TermiusTextPrimary)
                        }
                    }

                    // 压缩
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TermiusSurface,
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.clickable { onCompactContext() }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusTextSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("压缩", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                        }
                    }

                    // Token 与缓存 HUD
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = TermiusSurface,
                        border = BorderStroke(1.dp, TermiusBorder),
                        modifier = Modifier.clickable { showHudDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(13.dp), tint = TermiusGreen)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${tokenStats.contextTokens / 1000}k/${tokenStats.maxContextWindow / 1000}k (${String.format("%.0f", tokenStats.cacheHitRate)}%)",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = if (tokenStats.contextUsagePercent > 0.8f) TermiusRed else TermiusGreen
                            )
                        }
                    }
                }

                // 3. 底部极客输入栏
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text("输入指令...", color = TermiusTextMuted) },
                        modifier = Modifier.weight(1f),
                        maxLines = 4,
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = TermiusBlue,
                            unfocusedBorderColor = TermiusBorder,
                            focusedTextColor = TermiusTextPrimary,
                            unfocusedTextColor = TermiusTextPrimary,
                            focusedContainerColor = TermiusSurface,
                            unfocusedContainerColor = TermiusSurface
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    if (inputText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                onSendMessage(inputText.trim())
                                inputText = ""
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(TermiusBlue, RoundedCornerShape(10.dp))
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "发送", tint = Color.White)
                        }
                    } else {
                        IconButton(
                            onClick = {},
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (isVoiceRecording) TermiusRed else TermiusBlue,
                                    RoundedCornerShape(10.dp)
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
                    color = Color(0xFF1F3556),
                    shape = RoundedCornerShape(12.dp, 12.dp, 2.dp, 12.dp),
                    border = BorderStroke(1.dp, TermiusBlue.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = message.content,
                        color = TermiusTextPrimary,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        MessageRole.AGENT -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp, 12.dp, 12.dp, 2.dp),
                color = TermiusSurface,
                border = BorderStroke(1.dp, TermiusBorder)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    if (!message.thinking.isNullOrBlank()) {
                        Surface(
                            color = TermiusBlack,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, TermiusBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    InfoTitle(
                                        title = "思考过程",
                                        infoDescription = "大模型在输出前进行的内部推演和逻辑推理步骤。",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    IconButton(
                                        onClick = { isThinkingExpanded = !isThinkingExpanded },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(
                                            if (isThinkingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = TermiusTextSecondary
                                        )
                                    }
                                }
                                if (isThinkingExpanded) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = message.thinking ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = TermiusTextSecondary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Text(text = message.content, style = MaterialTheme.typography.bodyMedium, color = TermiusTextPrimary)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { onSpeakSummary(message.content) },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, contentDescription = "朗读", modifier = Modifier.size(14.dp), tint = TermiusTextSecondary)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("朗读", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                        }
                    }
                }
            }
        }
        MessageRole.TOOL -> {
            val tool = message.toolCall
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = if (tool?.requiresApproval == true && !tool.isApproved)
                    Color(0xFF381A1C)
                else
                    TermiusSurface,
                border = BorderStroke(
                    1.dp,
                    if (tool?.requiresApproval == true && !tool.isApproved) TermiusRed else TermiusBorder
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$",
                            color = TermiusGreen,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tool?.toolName ?: "Command",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = TermiusTextPrimary
                        )
                    }
                    if (!tool?.command.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Surface(
                            color = TermiusBlack,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, TermiusBorder),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = tool?.command ?: "",
                                color = TermiusGreen,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (tool?.requiresApproval == true && !tool.isApproved) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⚠️ 需要授权",
                                style = MaterialTheme.typography.labelSmall,
                                color = TermiusRed,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onApproveTool(true) },
                                    colors = ButtonDefaults.buttonColors(containerColor = TermiusGreen),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Text("批准", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { onApproveTool(false) },
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                                ) {
                                    Text("拒绝", style = MaterialTheme.typography.labelSmall, color = TermiusTextSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
        MessageRole.SYSTEM -> {}
    }
}
