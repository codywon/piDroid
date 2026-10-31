package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 1:1 对标 pi-web ChatWindow.tsx 中的 ProcessDetailsGroup 组件：
 * 折叠每轮对话中间的思考过程、工具调用与中间响应。
 * 格式：> 4 条消息 · 4 次工具调用 · 耗时 2m 13s
 */
@Composable
fun ProcessDetailsGroup(
    messageCount: Int,
    toolCallCount: Int,
    durationMs: Long?,
    defaultExpanded: Boolean = false,
    content: @Composable () -> Unit
) {
    val theme = LocalAppTheme.current
    var userToggled by remember { mutableStateOf<Boolean?>(null) }
    val expanded = userToggled ?: defaultExpanded

    val rotationAngle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        label = "chevron_rotation"
    )

    val parts = remember(messageCount, toolCallCount, durationMs) {
        val list = mutableListOf<String>()
        list.add("$messageCount 条消息")
        if (toolCallCount > 0) {
            list.add("$toolCallCount 次工具调用")
        }
        if (durationMs != null && durationMs > 0) {
            list.add("耗时 ${formatTurnDuration(durationMs)}")
        }
        list.joinToString(" · ")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    userToggled = !expanded
                }
                .padding(vertical = 4.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) "折叠过程" else "展开过程",
                tint = theme.textMuted,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotationAngle)
            )
            Text(
                text = parts,
                fontSize = 12.sp,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                content()
            }
        }
    }
}

/**
 * 长回合折叠行：`↑ 显示更早的 N 条过程`。
 *
 * 放在过程组内容的最上方。点击后由调用方把渲染窗口按 [com.pi.app.data.model.ProcessWindowPolicy.WINDOW_STEP]
 * 递增（始终渲染最近的若干条，与「正在跑什么」保持一致）。
 */
@Composable
fun ProcessWindowExpandRow(
    hiddenCount: Int,
    onClick: () -> Unit
) {
    val theme = LocalAppTheme.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.surfaceContainer.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.KeyboardArrowUp,
            contentDescription = null,
            tint = theme.textMuted,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = "显示更早的 $hiddenCount 条过程",
            fontSize = 11.5.sp,
            color = theme.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
