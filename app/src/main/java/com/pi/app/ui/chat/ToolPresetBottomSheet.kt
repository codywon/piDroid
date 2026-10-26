package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ToolPreset
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 现代工业级工具权限模式选择底板 (1:1 对标 pi-web 5 档工具预设)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPresetBottomSheet(
    currentPreset: ToolPreset,
    onPresetSelected: (ToolPreset) -> Unit,
    onDismissRequest: () -> Unit
) {
    val theme = LocalAppTheme.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = theme.surfaceContainerLow,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        dragHandle = {
            Surface(
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                color = Color.Transparent
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 36.dp, height = 4.dp)
                        .background(theme.outline.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = theme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "工具权限模式 (Tool Presets)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
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

            Text(
                text = "根据任务场景精细控制智能体在当前会话中的系统执行能力",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Preset List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ToolPreset.entries.forEach { preset ->
                    val isSelected = preset == currentPreset
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) theme.primary.copy(alpha = 0.10f) else theme.surfaceContainer,
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 1.dp,
                            if (isSelected) theme.primary else theme.outline.copy(alpha = 0.45f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPresetSelected(preset)
                                onDismissRequest()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 1. 左侧代表性图标底座
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(
                                        if (isSelected) theme.primary.copy(alpha = 0.16f) else theme.surfaceContainerHigh,
                                        RoundedCornerShape(8.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (preset) {
                                        ToolPreset.CONFIGURED -> Icons.Default.Tune
                                        ToolPreset.CHAT_ONLY -> Icons.Default.Chat
                                        ToolPreset.READ_ONLY -> Icons.Default.Visibility
                                        ToolPreset.DEFAULT -> Icons.Default.Code
                                        ToolPreset.FULL -> Icons.Default.Bolt
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp),
                                    tint = if (isSelected) theme.primary else theme.textSecondary
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // 2. 标题与说明
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = preset.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) theme.primary else theme.textPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isSelected) theme.primary.copy(alpha = 0.18f) else theme.surfaceContainerHigh,
                                        modifier = Modifier.padding(top = 1.dp)
                                    ) {
                                        Text(
                                            text = when (preset) {
                                                ToolPreset.CONFIGURED -> "系统默认"
                                                ToolPreset.CHAT_ONLY -> "仅聊天"
                                                ToolPreset.READ_ONLY -> "只读审查"
                                                ToolPreset.DEFAULT -> "常用读写"
                                                ToolPreset.FULL -> "全能开启"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = if (isSelected) theme.primary else theme.textMuted,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(3.dp))

                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    color = if (isSelected) theme.textSecondary else theme.textMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Spacer(modifier = Modifier.width(10.dp))

                            // 3. 右侧单选指示圈 (Radio Check)
                            if (isSelected) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(theme.primary, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = if (theme.isDark) Color.Black else Color.White,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(BorderStroke(1.5.dp, theme.outline.copy(alpha = 0.5f)), CircleShape)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
