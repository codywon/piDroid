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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TermiusSurface,
        titleContentColor = TermiusTextPrimary,
        textContentColor = TermiusTextSecondary,
        shape = RoundedCornerShape(14.dp),
        title = {
            InfoTitle(
                title = "Token 用量与监控",
                infoDescription = "实时追踪大模型上下文窗口、Prompt 缓存复用率与累计花费。",
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
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        InfoTitle(
                            title = "上下文容量",
                            infoDescription = "当前历史会话占用的 Token 数量以及模型所支持的最大上限。超过 80% 建议执行压缩。",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = "${stats.contextTokens / 1000}k / ${stats.maxContextWindow / 1000}k (${String.format("%.1f", stats.contextUsagePercent * 100)}%)",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                stats.contextUsagePercent > 0.85f -> TermiusRed
                                stats.contextUsagePercent > 0.70f -> Color(0xFFD29922)
                                else -> TermiusBlue
                            }
                        )
                    }
                    LinearProgressIndicator(
                        progress = { stats.contextUsagePercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = when {
                            stats.contextUsagePercent > 0.85f -> TermiusRed
                            stats.contextUsagePercent > 0.70f -> Color(0xFFD29922)
                            else -> TermiusBlue
                        },
                        trackColor = TermiusSurfaceVariant
                    )
                }

                // 2. 缓存命中率卡片 (Termius 纯深色质感)
                Surface(
                    color = Color(0xFF1B3D23),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, TermiusGreen.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            InfoTitle(
                                title = "Prompt 缓存率",
                                infoDescription = "基于 Anthropic/OpenAI 的前缀缓存机制：命中缓存的 Token 响应更快且费用减少高达 90%！",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "缓存命中: ${stats.cacheReadTokens} Tokens",
                                style = MaterialTheme.typography.bodySmall,
                                color = TermiusGreen,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "${String.format("%.1f", stats.cacheHitRate)}%",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.Monospace,
                            color = TermiusGreen
                        )
                    }
                }

                // 3. 指标网格
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = TermiusSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, TermiusBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("输入 (Prompt)", style = MaterialTheme.typography.labelSmall, color = TermiusTextMuted)
                            Text("${stats.inputTokens}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Surface(
                        modifier = Modifier.weight(1f),
                        color = TermiusSurfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, TermiusBorder)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("输出 (Completion)", style = MaterialTheme.typography.labelSmall, color = TermiusTextMuted)
                            Text("${stats.outputTokens}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // 4. 预估成本
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("当前会话成本", style = MaterialTheme.typography.bodySmall, color = TermiusTextSecondary)
                    Text(
                        "\$${String.format("%.4f", stats.cost)}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = TermiusTextPrimary
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
                colors = ButtonDefaults.buttonColors(containerColor = TermiusBlue),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Compress, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("压缩上下文")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = TermiusTextSecondary)
            }
        }
    )
}
