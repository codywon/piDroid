package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ToolPreset
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 智能体内置工具权限预设切换面板 (1:1 严格对标 pi-web 权限菜单规范)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolPresetBottomSheet(
    currentPreset: ToolPreset,
    onPresetSelected: (ToolPreset) -> Unit,
    onDismissRequest: () -> Unit
) {
    val theme = LocalAppTheme.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = theme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle(color = theme.outline.copy(alpha = 0.5f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Build,
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
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = theme.textSecondary
                    )
                }
            }

            Text(
                text = "控制 Agent 在当前任务中允许调用的内置工具与 Shell 执行权限",
                style = MaterialTheme.typography.bodySmall,
                color = theme.textMuted,
                modifier = Modifier.padding(bottom = 14.dp)
            )

            // Preset List
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolPreset.entries.forEach { preset ->
                    val isSelected = preset == currentPreset
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) theme.primary.copy(alpha = 0.12f) else theme.surfaceContainer,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) theme.primary else theme.outline.copy(alpha = 0.5f)
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "已选择",
                                        modifier = Modifier.size(16.dp),
                                        tint = theme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                } else {
                                    Spacer(modifier = Modifier.width(24.dp))
                                }

                                Text(
                                    text = preset.displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) theme.primary else theme.textPrimary
                                )
                            }

                            Text(
                                text = preset.description,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = if (isSelected) theme.textSecondary else theme.textMuted
                            )
                        }
                    }
                }
            }
        }
    }
}
