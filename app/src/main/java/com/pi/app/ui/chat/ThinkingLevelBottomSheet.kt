package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.ThinkingLevel
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 专用的思考深度选择底板 (对标 pi-web 与 CLI thinking selector)：
 * - 单独选择思考级别，选完即关（1 步到位，无需在模型列表中往返）
 * - 去除 token 括号数值，仅呈现简洁优雅的级别与纯净中文定义
 * - 右侧五角星支持一键设为全局默认档位
 * - 高档位（high / xhigh / max）带有醒目琥珀色高亮，防止误开造成 token 浪费
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThinkingLevelBottomSheet(
    currentLevel: ThinkingLevel,
    defaultLevel: ThinkingLevel? = null,
    onLevelSelected: (ThinkingLevel) -> Unit,
    onSetDefaultLevel: ((ThinkingLevel) -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val theme = LocalAppTheme.current

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        containerColor = theme.surfaceContainerLow,
        contentColor = theme.textPrimary,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        dragHandle = {
            Surface(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp),
                shape = RoundedCornerShape(2.dp),
                color = theme.outline
            ) {}
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                InfoTitle(
                    title = "思考深度 (Thinking Level)",
                    infoDescription = "仅作用于当前会话；全局默认在设置页",
                    style = MaterialTheme.typography.titleMedium
                )
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

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ThinkingLevel.entries.forEach { level ->
                    val isSelected = level == currentLevel
                    val isDefault = defaultLevel != null && level == defaultLevel
                    val isHigh = level.isHighBudget

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) theme.primary.copy(alpha = 0.12f) else theme.background,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) theme.primary else theme.outline.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onLevelSelected(level)
                                    onDismissRequest()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 左侧勾选
                            Box(
                                modifier = Modifier.width(22.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "当前选中",
                                        tint = theme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // 中间：value 与 cleanLabel（无任何 token 括号）
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = level.value,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = when {
                                        isSelected -> theme.primary
                                        isHigh -> Color(0xFFF59E0B)
                                        else -> theme.textPrimary
                                    }
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = level.cleanLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isSelected) theme.primary.copy(alpha = 0.85f) else theme.textSecondary
                                )

                                if (isHigh) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = Color(0xFFF59E0B).copy(alpha = 0.14f),
                                        shape = RoundedCornerShape(4.dp),
                                        border = BorderStroke(0.5.dp, Color(0xFFF59E0B).copy(alpha = 0.4f))
                                    ) {
                                        Text(
                                            text = "深度推理",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                            color = Color(0xFFF59E0B)
                                        )
                                    }
                                }
                            }

                            // 右侧：默认星标（对标模型选择列表）
                            if (onSetDefaultLevel != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clickable { onSetDefaultLevel(level) }
                                        .padding(start = 6.dp)
                                ) {
                                    if (isDefault) {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "当前默认档位",
                                            tint = Color(0xFFF59E0B),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Star,
                                            contentDescription = "点击设为全局默认档位",
                                            tint = theme.textMuted.copy(alpha = 0.22f),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
