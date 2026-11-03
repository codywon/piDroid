package com.pi.app.data.model

import java.util.Locale

/**
 * 会话统计数据汇总（对标 pi-web POST /api/sessions/stats 响应中的单项 stats）。
 */
data class SessionStatsSummary(
    val totalTokens: Long = 0L,
    val cost: Double = 0.0,
    val toolCalls: Int = 0,
    val totalActiveMs: Long = 0L
)

/**
 * 会话用量与统计格式化工具（纯 JVM 逻辑，1:1 对标 pi-web 格式化规范）。
 */
object SessionStatsFormatting {

    /**
     * 格式化活跃耗时（对标 pi-web de(totalActiveMs)）。
     *
     * 规则：
     * - <= 0: 返回 ""
     * - < 60s: "${sec}s"
     * - < 3600s: "${min}m ${sec}s" 或 "${min}m"
     * - >= 3600s: "${hours}h ${min}m"
     */
    fun formatActiveDuration(totalActiveMs: Long): String {
        if (totalActiveMs <= 0L) return ""
        val totalSec = totalActiveMs / 1000L
        if (totalSec < 60L) return "${totalSec}s"
        val minutes = totalSec / 60L
        val seconds = totalSec % 60L
        if (minutes < 60L) {
            return if (seconds > 0L) "${minutes}m ${seconds}s" else "${minutes}m"
        }
        val hours = minutes / 60L
        val remMinutes = minutes % 60L
        return if (remMinutes > 0L) "${hours}h ${remMinutes}m" else "${hours}h"
    }

    /**
     * 格式化 Token 紧凑读数（对标 pi-web a9(tokens.total)）。
     *
     * 规则：
     * - <= 0: ""
     * - < 1000: "$tokens tok"
     * - < 1,000,000: "%.1fk tok" (如 "24.8k tok", "10k tok")
     * - >= 1,000,000: "%.1fM tok"
     */
    fun formatTokensCompact(totalTokens: Long): String {
        if (totalTokens <= 0L) return ""
        return if (totalTokens < 1000L) {
            "$totalTokens tok"
        } else if (totalTokens < 1_000_000L) {
            val v = totalTokens / 1000.0
            val formatted = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
            "${formatted}k tok"
        } else {
            val v = totalTokens / 1_000_000.0
            val formatted = String.format(Locale.US, "%.1f", v).removeSuffix(".0")
            "${formatted}M tok"
        }
    }

    /**
     * 格式化费用显示（门槛 >= $0.0001 才展示）。
     */
    fun formatCostCompact(cost: Double): String {
        if (cost < 0.0001) return ""
        return String.format(Locale.US, "$%.4f", cost)
    }

    /**
     * 构造 SessionCard 方案 A 紧凑第二行指标文本集合。
     *
     * 格式：时间 · [🕒 耗时] · [Token] · [费用]
     */
    fun buildCompactMetricsLine(
        dateStr: String,
        stats: SessionStatsSummary?
    ): List<Pair<String, Boolean>> {
        val list = mutableListOf<Pair<String, Boolean>>()
        if (dateStr.isNotBlank()) {
            list.add(dateStr to false)
        }
        if (stats == null) return list

        val dur = formatActiveDuration(stats.totalActiveMs)
        if (dur.isNotBlank()) {
            list.add(dur to true) // true 表示需要时钟图标
        }

        val tok = formatTokensCompact(stats.totalTokens)
        if (tok.isNotBlank()) {
            list.add(tok to false)
        }

        val costStr = formatCostCompact(stats.cost)
        if (costStr.isNotBlank()) {
            list.add(costStr to false)
        }
        return list
    }
}
