package com.pi.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
