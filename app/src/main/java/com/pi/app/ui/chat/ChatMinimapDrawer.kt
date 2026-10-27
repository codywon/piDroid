package com.pi.app.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 * 1:1 对标 pi-web 与移动端最佳实践的侧边导轨与右边栏提问时间轴抽屉
 * - 右侧细导轨 (Minimap Rail)：方块节点独立点击直接平滑滚动定位，绝不弹出抽屉！
 * - 导轨底部大纲按钮：专属点击呼出贴合右侧屏幕的半透明侧滑抽屉，彻底告别突兀悬浮弹窗。
 */
@Composable
fun PiWebChatMinimap(
    turns: List<TurnOutline>,
    activeTurnNumber: Int,
    /** PC 上是否还有更早的回合没加载（pi 的 context.hasMore） */
    hasEarlierOnServer: Boolean = false,
    /** 服务端统计的「更早但未加载的回合数」（pi 的 context.turnsBefore） */
    earlierTurnCount: Int = 0,
    isLoadingEarlier: Boolean = false,
    onLoadEarlier: () -> Unit = {},
    onJumpToUserMessage: (TurnOutline) -> Unit,
    onJumpToAssistant: (TurnOutline) -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    var isDrawerOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxHeight()) {
        // ── 1. 右侧极简时间轴细导轨 (Minimap Rail) ──
        Surface(
            modifier = Modifier
                .width(26.dp)
                .fillMaxHeight()
                .align(Alignment.CenterEnd),
            color = theme.surfaceContainerLow.copy(alpha = 0.65f),
            border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.25f))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 导轨中心垂直指示线
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .padding(top = 8.dp, bottom = 34.dp)
                        .align(Alignment.Center)
                        .background(theme.outline.copy(alpha = 0.35f))
                )

                // 节点点位列表 (在竖直方向自适应均分)
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
                        .padding(top = 8.dp, bottom = 34.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    displayNodes.forEach { turn ->
                        val isLocated = turn.turnNumber == activeTurnNumber
                        // 单独为每一个方块提供独立触控热区，点击直接跳转，绝不呼出抽屉
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(18.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    onJumpToUserMessage(turn)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isLocated) 10.dp else 7.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(
                                        if (isLocated) theme.primary
                                        else theme.surfaceContainerHigh
                                    )
                                    .border(
                                        BorderStroke(
                                            if (isLocated) 1.5.dp else 0.8.dp,
                                            if (isLocated) theme.primary else theme.textMuted.copy(alpha = 0.5f)
                                        ),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    }
                }

                // 导轨底部专属大纲抽屉开关按钮
                IconButton(
                    onClick = { isDrawerOpen = !isDrawerOpen },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 3.dp)
                        .size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.History,
                        contentDescription = "查看历次提问大纲",
                        tint = if (isDrawerOpen) theme.primary else theme.textMuted,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // ── 2. 右侧全高贴边抽屉大盘 (带背景暗色遮罩，右滑与点击空白平滑收起) ──
        if (isDrawerOpen) {
            // 背景半透明暗色遮罩
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        isDrawerOpen = false
                    }
            )

            // 右侧抽屉卡片面板 (从右向左滑出，占屏幕 82% 宽度，贴紧右边缘)
            AnimatedVisibility(
                visible = isDrawerOpen,
                enter = slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(220)) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(180)) + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.82f)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}, // 阻止点击穿透到遮罩
                    shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.5f)),
                    shadowElevation = 16.dp
                ) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // 顶栏：标题 + 轮次小徽章 + 关闭按钮
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "历次提问时间轴",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary
                                )
                                Surface(
                                    shape = CircleShape,
                                    color = theme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "${turns.size}",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = theme.primary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }

                            IconButton(
                                onClick = { isDrawerOpen = false },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "关闭",
                                    tint = theme.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        HorizontalDivider(color = theme.outline.copy(alpha = 0.25f), thickness = 0.5.dp)

                        // 更早的回合还没加载时，给一条一眼看懂的入口（文案/口径与 pi-web 一致：按「轮」计）
                        if (hasEarlierOnServer || isLoadingEarlier) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onLoadEarlier()
                                        isDrawerOpen = false
                                    },
                                color = theme.primary.copy(alpha = 0.08f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.UnfoldMore,
                                        contentDescription = null,
                                        tint = theme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = if (isLoadingEarlier) "正在加载更早的记录…"
                                        else if (earlierTurnCount > 0) "更早的 $earlierTurnCount 轮还没加载 · 点击加载"
                                        else "点击加载更早的记录",
                                        fontSize = 12.sp,
                                        color = theme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            HorizontalDivider(color = theme.outline.copy(alpha = 0.25f), thickness = 0.5.dp)
                        }

                        // 轮次列表 (LazyColumn)
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
                                            .clickable {
                                                onJumpToUserMessage(turn)
                                                isDrawerOpen = false
                                            }
                                            .padding(vertical = 9.dp, horizontal = 12.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        // 左侧序号 + 工具调用胶囊
                                        Column(
                                            modifier = Modifier
                                                .width(36.dp)
                                                .padding(top = 2.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Text(
                                                text = String.format("%02d", turn.turnNumber),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = if (isLocated) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isLocated) theme.primary else theme.textMuted
                                            )

                                            if (turn.toolCount > 0) {
                                                Surface(
                                                    shape = RoundedCornerShape(999.dp),
                                                    color = theme.surfaceContainerHigh,
                                                    border = BorderStroke(0.5.dp, theme.outline.copy(alpha = 0.45f))
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

                                        // 右侧内容：提问 + 助手大纲
                                        Column(
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(start = 6.dp, end = 2.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = userText,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium,
                                                lineHeight = 18.sp,
                                                color = theme.textPrimary,
                                                maxLines = 3,
                                                overflow = TextOverflow.Ellipsis
                                            )

                                            if (turn.assistantHeadings.isNotEmpty()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                turn.assistantHeadings.forEach { heading ->
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                onJumpToAssistant(turn)
                                                                isDrawerOpen = false
                                                            }
                                                            .padding(vertical = 2.dp),
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
                                                            fontSize = 11.5.sp,
                                                            color = theme.textSecondary,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    HorizontalDivider(color = theme.outline.copy(alpha = 0.18f), thickness = 0.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
