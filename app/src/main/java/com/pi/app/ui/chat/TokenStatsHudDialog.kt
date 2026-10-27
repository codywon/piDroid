package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.data.model.TokenStats
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@Composable
fun TokenStatsHudDialog(
    stats: TokenStats,
    onCompactClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val theme = LocalAppTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.surfaceContainerLow,
        titleContentColor = theme.textPrimary,
        textContentColor = theme.textSecondary,
        shape = RoundedCornerShape(14.dp),
        title = {
            InfoTitle(
                title = "Token 用量与监控",
                infoDescription = "上下文、缓存命中与花费",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 上下文进度条
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        InfoTitle(
                            title = "上下文容量",
                            infoDescription = "超过 80% 建议压缩上下文",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${TokenStats.formatTokenStandard(stats.contextTokens)} / ${TokenStats.formatTokenStandard(stats.maxContextWindow)} (${String.format(java.util.Locale.US, "%.1f%%", stats.contextUsagePercent * 100)})",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            color = when {
                                stats.contextUsagePercent > 0.85f -> Color(0xFFCF222E)
                                stats.contextUsagePercent > 0.70f -> Color(0xFFD29922)
                                else -> theme.primary
                            }
                        )
                    }
                    LinearProgressIndicator(
                        progress = { stats.contextUsagePercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = when {
                            stats.contextUsagePercent > 0.85f -> Color(0xFFCF222E)
                            stats.contextUsagePercent > 0.70f -> Color(0xFFD29922)
                            else -> theme.primary
                        },
                        trackColor = theme.surfaceContainer
                    )
                }

                // 2. 缓存命中率卡片
                Surface(
                    color = Color(0xFF2EA043).copy(alpha = if (theme.isDark) 0.25f else 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF2EA043).copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            InfoTitle(
                                title = "Prompt 缓存率",
                                infoDescription = "前缀缓存命中可省约 90% 费用",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "缓存命中: ${TokenStats.formatTokenStandard(stats.cacheReadTokens)} Tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF2EA043),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f%%", stats.cacheHitRate),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            color = Color(0xFF2EA043)
                        )
                    }
                }

                // 3. 指标网格
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = theme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, theme.outline)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("输入 (Prompt)", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                            Text(
                                text = TokenStats.formatTokenStandard(stats.inputTokens),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textPrimary
                            )
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = theme.surfaceContainer,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, theme.outline)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("输出 (Completion)", style = MaterialTheme.typography.labelSmall, color = theme.textMuted)
                            Text(
                                text = TokenStats.formatTokenStandard(stats.outputTokens),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = theme.textPrimary
                            )
                        }
                    }
                }

                // 4. 预估成本
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前会话成本", style = MaterialTheme.typography.bodySmall, color = theme.textSecondary)
                    Text(
                        "\$${String.format("%.4f", stats.cost)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textPrimary
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCompactClick()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = theme.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("压缩上下文")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = theme.textSecondary)
            }
        }
    )
}
