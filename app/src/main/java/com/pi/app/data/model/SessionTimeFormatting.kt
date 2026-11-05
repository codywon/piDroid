package com.pi.app.data.model

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/**
 * 会话时间的解析与相对显示（纯 JVM，便于单测）。
 *
 * 为什么是相对时间：pi-web 侧栏会话行的右侧槽位显示的就是相对时间
 * （`lib/format.ts` 的 `Rg()`，zh-CN 单位表 `{刚刚, 分, 小时, 天, 周, 个月, 年}`）：
 * <1 分钟「刚刚」、<1 小时「N分」、<24 小时「N小时」、<7 天「N天」、<30 天「N周」、
 * <365 天「N个月」，再远「N年」。
 *
 * 顺带修掉一个老问题：`modified` 是 UTC ISO 串，旧实现按本地时区格式化成
 * `MM-dd HH:mm`，历史上就出现过「手机上的时间比 pi-web 慢 8 小时」的误判。
 * 相对时间只做同一时标下相减，不存在时区歧义。
 */
object SessionTimeFormatting {

    /** <1 分钟的统一文案（与 pi-web zh-CN 的 `now` 一致）。 */
    const val JUST_NOW = "刚刚"

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 86_400_000L

    /**
     * 解析 `modified` / `created`：支持 UTC ISO 串、带偏移的 ISO 串、毫秒数字串与
     * `yyyy-MM-dd HH:mm:ss`。失败返回 0（调用方据此不显示时间）。
     */
    fun parseMillis(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L
        timeStr.toLongOrNull()?.let { return it }
        return try {
            Instant.parse(timeStr).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(timeStr).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    val cleaned = timeStr.replace("T", " ").take(19)
                    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    sdf.parse(cleaned)?.time ?: 0L
                } catch (_: Exception) {
                    0L
                }
            }
        }
    }

    /**
     * 相对时间，与 pi-web `Rg()` 同口径（单位表取 zh-CN）。
     *
     * @param nowMs 参照时刻（测试注入）；未来时间按 0 处理（pi-web 同样是 `Math.max(0, diff)`）
     * @return 解析不出时间时返回空串，调用方无需再判空
     */
    fun formatRelative(timeStr: String?, nowMs: Long = System.currentTimeMillis()): String {
        val millis = parseMillis(timeStr)
        if (millis <= 0L) return ""
        val diff = (nowMs - millis).coerceAtLeast(0L)

        val minutes = diff / MINUTE_MS
        if (minutes < 1L) return JUST_NOW
        if (minutes < 60L) return "${minutes}分"

        val hours = diff / HOUR_MS
        if (hours < 24L) return "${hours}小时"

        val days = diff / DAY_MS
        return when {
            days < 7L -> "${days}天"
            days < 30L -> "${days / 7L}周"
            // 与 pi-web 一致：不足一个月的按 1 个月显示，避免出现「0个月」
            days < 365L -> "${maxOf(1L, days / 30L)}个月"
            else -> "${days / 365L}年"
        }
    }
}
