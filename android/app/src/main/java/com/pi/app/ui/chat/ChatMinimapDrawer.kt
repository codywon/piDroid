package com.pi.app.ui.chat

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ChatItem
import com.pi.app.data.model.MessageRole
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 对应 pi-web lib/turn-duration.ts 中的 formatTurnDuration
 * 格式：14s -> 2m 13s -> 1h 2m 5s
 */
fun formatTurnDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    if (minutes < 60) return "${minutes}m ${seconds}s"
    return "${minutes / 60}h ${minutes % 60}m ${seconds}s"
}

/**
 * 提取助手回答的关键标题大纲或首段摘要（对标 pi-web ChatMinimap.tsx AssistantOutline）
 */
fun extractAssistantOutline(markdown: String?): List<String> {
    if (markdown.isNullOrBlank()) return emptyList()
    val lines = markdown.lines()
    val headings = lines.filter { line ->
        val trimmed = line.trim()
        trimmed.startsWith("# ") || trimmed.startsWith("## ") || trimmed.startsWith("### ")
    }.map { line ->
        line.trim().trimStart('#').trim()
    }.filter { it.isNotBlank() }

    if (headings.isNotEmpty()) {
        return headings.take(3)
    }

    val firstPara = lines.firstOrNull { line ->
        val t = line.trim()
        t.isNotBlank() && !t.startsWith("```") && !t.startsWith(">") && !t.startsWith("-")
    }?.trim()?.take(45)

    return if (!firstPara.isNullOrBlank()) listOf(firstPara) else emptyList()
}

/**
 * 回合数据模型（对标 pi-web TurnInfo）
 */
data class TurnOutline(
    val turnNumber: Int,
    val userIndex: Int,
    val userMessage: ChatItem,
    val toolCount: Int,
    val assistantIndex: Int?,
    val assistantHeadings: List<String>,
    val processItems: List<ChatItem>,
    val finalAnswerItem: ChatItem?,
    val durationMs: Long?,
    val isRunning: Boolean
)

/**
 * 1:1 对标 pi-web ChatMinimap.tsx 侧边导轨与多轮提问大纲抽屉
 */
@Composable
fun PiWebChatMinimap(
    turns: List<TurnOutline>,
    activeTurnNumber: Int,
    turnsBefore: Int = 0,
    onLoadEarlier: () -> Unit = {},
    onJumpToUserMessage: (TurnOutline) -> Unit,
    onJumpToAssistant: (TurnOutline) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var isLocked by remember { mutableStateOf(false) }
    var isDrawerOpen by remember { mutableStateOf(false) }

    val showDrawer = isLocked || isDrawerOpen

    Box(modifier = modifier.fillMaxHeight()) {
        // ── 抽屉大盘 (从右向左滑出，对标 pi-web ChatMinimap.module.css .preview) ──
        AnimatedVisibility(
            visible = showDrawer,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 32.dp)
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 310.dp)
                    .fillMaxHeight()
                    .padding(vertical = 8.dp),
                shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                color = theme.surfaceContainerLow,
                border = BorderStroke(1.dp, theme.outline.copy(alpha = 0.6f)),
                shadowElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // 顶栏：标题 + 锁定指示 + 关闭按钮
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "历次提问时间轴",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = theme.textPrimary
                            )
                            Surface(
                                shape = CircleShape,
                                color = theme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "${turns.size}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                isLocked = false
                                isDrawerOpen = false
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "关闭",
                                tint = theme.textMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    HorizontalDivider(color = theme.outline.copy(alpha = 0.35f), thickness = 0.5.dp)

                    // 1. 更早的 N 轮还没加载，点击加载 (对标 pi-web data-minimap-preview-earlier)
                    if (turnsBefore > 0) {
                        val strokeColor = theme.outline.copy(alpha = 0.7f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onLoadEarlier() }
                                .padding(vertical = 7.dp, horizontal = 10.dp)
                                .drawBehind {
                                    val strokeWidth = 1.dp.toPx()
                                    val y = size.height - strokeWidth / 2
                                    drawLine(
                                        color = strokeColor,
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = strokeWidth,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "更早的 $turnsBefore 轮还没加载，点击加载",
                                fontSize = 11.5.sp,
                                color = theme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // 2. 轮次列表 (对标 pi-web .turn)
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(turns, key = { it.turnNumber }) { turn ->
                            val isLocated = turn.turnNumber == activeTurnNumber
                            val userText = turn.userMessage.content.replace('\n', ' ').trim()

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(if (isLocated) theme.primary.copy(alpha = 0.08f) else Color.Transparent)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    // 左列 (36dp)：大号轮次数字 + 工具徽章
                                    Column(
                                        modifier = Modifier
                                            .width(36.dp)
                                            .padding(top = 4.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Text(
                                            text = String.format("%02d", turn.turnNumber),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = if (isLocated) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isLocated) theme.primary else theme.textMuted
                                        )

                                        if (turn.toolCount > 0) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = theme.surfaceContainerHigh,
                                                border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = if (turn.toolCount > 99) "99+" else "${turn.toolCount}",
                                                    fontSize = 9.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = theme.textSecondary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 0.5.dp)
                                                )
                                            }
                                        }
                                    }

                                    // 右列：用户提问 + 助手回答大纲
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(end = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        // 用户提问
                                        Text(
                                            text = userText,
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            lineHeight = 17.sp,
                                            color = theme.textPrimary,
                                            maxLines = 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onJumpToUserMessage(turn) }
                                                .padding(vertical = 2.dp)
                                        )

                                        // 助手答复大纲 (带 'A' 标识)
                                        if (turn.assistantHeadings.isNotEmpty()) {
                                            turn.assistantHeadings.forEach { heading ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { onJumpToAssistant(turn) }
                                                        .padding(vertical = 1.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                                ) {
                                                    Surface(
                                                        shape = RoundedCornerShape(3.dp),
                                                        color = theme.primary.copy(alpha = 0.12f),
                                                        modifier = Modifier.size(16.dp)
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center) {
                                                            Text(
                                                                text = "A",
                                                                fontSize = 9.5.sp,
                                                                fontFamily = FontFamily.Monospace,
                                                                fontWeight = FontWeight.Bold,
                                                                color = theme.primary
                                                            )
                                                        }
                                                    }
                                                    Text(
                                                        text = heading,
                                                        fontSize = 11.sp,
                                                        color = theme.textMuted,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                HorizontalDivider(color = theme.outline.copy(alpha = 0.22f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }

        // ── 右侧时间轴细导轨 (Minimap Rail，对标 pi-web ChatMinimap 28dp Rail) ──
        Surface(
            modifier = Modifier
                .width(28.dp)
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .clickable {
                    isDrawerOpen = !isDrawerOpen
                },
            color = theme.surfaceContainerLow.copy(alpha = 0.65f),
            border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.3f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 中心竖直导轨线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .background(theme.outline.copy(alpha = 0.4f))
                )

                // 节点点位列表 (在竖直方向均分或紧凑排列)
                val displayNodes = remember(turns) {
                    if (turns.size <= 24) turns
                    else {
                        val step = turns.size.toFloat() / 23f
                        (0 until 23).map { i -> turns[(i * step).toInt().coerceIn(0, turns.lastIndex)] }
                            .distinctBy { it.turnNumber } + listOf(turns.last())
                    }.distinctBy { it.turnNumber }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 16.dp, bottom = 44.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    displayNodes.forEach { turn ->
                        val isLocated = turn.turnNumber == activeTurnNumber
                        Box(
                            modifier = Modifier
                                .size(if (isLocated) 10.dp else 8.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isLocated) theme.primary.copy(alpha = 0.45f)
                                    else theme.surfaceContainerHigh
                                )
                                .border(
                                    BorderStroke(
                                        if (isLocated) 1.5.dp else 1.dp,
                                        if (isLocated) theme.primary else theme.textMuted.copy(alpha = 0.55f)
                                    ),
                                    RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }

                // 底部锁定开关按钮 (对标 pi-web styles.lock)
                IconButton(
                    onClick = {
                        isLocked = !isLocked
                        if (isLocked) isDrawerOpen = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .size(28.dp)
                ) {
                    Icon(
                        imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (isLocked) "取消锁定" else "锁定抽屉",
                        tint = if (isLocked) theme.primary else theme.textMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
