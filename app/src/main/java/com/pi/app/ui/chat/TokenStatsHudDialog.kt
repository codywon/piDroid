package com.pi.app.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pi.app.data.model.CompactionRecord
import com.pi.app.data.model.TokenStats
import com.pi.app.ui.components.InfoTitle
import com.pi.app.ui.theme.*

@Composable
fun TokenStatsHudDialog(
    stats: TokenStats,
    compactionRecords: List<CompactionRecord> = emptyList(),
    /** 事件流最后一条事件的时间戳（0 = 从未收到） */
    streamLastEventAtMs: Long = 0L,
    /** 最后一次成功会话快照的时间戳（0 = 从未成功） */
    lastSnapshotAtMs: Long = 0L,
    sseConnected: Boolean = false,
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
            // 占用率三档色阶：必须在 Column 之外先算好。
            // 原先三处 when 各自内联，改写成变量时若声明在 Row 的 content lambda 里，
            // 兄弟节点 LinearProgressIndicator / 缓存卡片就会引用不到（作用域越界）。
            // error 走 M3 ColorScheme（全局唯一真源），warning 走 AppTheme 语义色。
            val usageColor = when {
                stats.contextUsagePercent > 0.85f -> MaterialTheme.colorScheme.error
                stats.contextUsagePercent > 0.70f -> theme.warning
                else -> theme.primary
            }
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 0. 同步健康：把「体感不同步」变成可读的数字（事件流最后事件 / 最后一次快照）
                SyncHealthRow(
                    sseConnected = sseConnected,
                    streamLastEventAtMs = streamLastEventAtMs,
                    lastSnapshotAtMs = lastSnapshotAtMs
                )

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
                            color = usageColor
                        )
                    }
                    LinearProgressIndicator(
                        progress = { stats.contextUsagePercent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = usageColor,
                        trackColor = theme.surfaceContainer
                    )
                }

                // 2. 缓存命中率卡片：容器用不透明语义色（半透明容器会让对比度不可计算、不可断言）
                Surface(
                    color = theme.successContainer,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, theme.success.copy(alpha = 0.35f))
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
                                color = theme.success,
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
                            color = theme.success
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

                // 5. 压缩历史明细
                if (compactionRecords.isNotEmpty()) {
                    HorizontalDivider(color = theme.outline.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "压缩历史 (${compactionRecords.size} 次)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = theme.textPrimary
                        )
                        compactionRecords.take(3).forEachIndexed { idx, record ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "第 ${idx + 1} 轮压缩",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = theme.textSecondary
                                )
                                Text(
                                    text = "-${TokenStats.formatTokenStandard(record.tokensSaved)} (${record.savingsPercent}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.success
                                )
                            }
                        }
                    }
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

/**
 * 同步健康一行：`实时流 已连接 · 事件 2s 前 · 快照 8s 前`。
 *
 * 存在的理由：之前判断「App 不同步」只能凭感觉和截图比对，没有数字。
 * 这里每拿一次读数就自增一个 tick，让「事件流是否还在动、快照多久没成功」变成可截图的事实。
 */
@Composable
private fun SyncHealthRow(
    sseConnected: Boolean,
    streamLastEventAtMs: Long,
    lastSnapshotAtMs: Long
) {
    val theme = LocalAppTheme.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    fun ageText(at: Long): String = if (at <= 0L) "—" else {
        val sec = ((now - at).coerceAtLeast(0L)) / 1000
        if (sec < 1) "刚刚" else "${sec}s 前"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        InfoTitle(
            title = "实时同步",
            infoDescription = "事件流与快照的最后更新时间",
            style = MaterialTheme.typography.labelMedium
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "${if (sseConnected) "流已连接" else "流已断开"} · 事件 ${ageText(streamLastEventAtMs)} · 快照 ${ageText(lastSnapshotAtMs)}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            color = if (sseConnected) theme.textMuted else theme.warning
        )
    }
}
