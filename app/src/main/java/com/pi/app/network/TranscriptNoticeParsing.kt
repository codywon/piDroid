package com.pi.app.network

import com.google.gson.JsonObject
import java.util.Locale

/**
 * 会话正文里的「提示行」（pi-web 的 `transcriptNotices`），纯 JVM、可单测。
 *
 * 服务端（pi-web `lib/transcript-notices-server.ts`）按 pi 自己的 `notices.*` 设置，
 * 把缓存未命中 / 缓存预热计费 / 摘要计费 / 推理被丢弃这几类事实挂到消息的
 * `transcriptNotices` 上；这些设置**默认全关**，所以绝大多数会话里这个字段压根不存在。
 *
 * 这里直接把每条渲染成一行中文，文案与数字格式 1:1 取自 pi-web 的 zh-CN 词条
 * （`transcriptNotice.*`）与 `formatNoticeTokens` / `formatNoticeCost` / `formatCacheWarmCost`，
 * 免得手机端与网页端对同一件事说两种话。
 */
object TranscriptNoticeParsing {

    /**
     * 一条消息的提示行；没有就是空列表。
     *
     * 与被显示的消息一致：`summary_cost` 若来自上下文压缩（`source == "compaction"`）不在这里显示，
     * 它已经由压缩卡片自己报了（pi-web `TranscriptNoticeLines` 的同一规则）。
     */
    fun of(message: JsonObject): List<String> {
        val notices = message.get("transcriptNotices")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val out = mutableListOf<String>()
        for (element in notices) {
            if (!element.isJsonObject) continue
            describe(element.asJsonObject)?.let { out.add(it) }
        }
        return out
    }

    private fun describe(notice: JsonObject): String? {
        val kind = notice.stringOrNull("kind") ?: return null
        return when (kind) {
            "cache_miss" -> {
                val tokens = formatTokens(notice.longOrZero("missedTokens"))
                val cost = costSuffix(notice.doubleOrZero("missedCost"))
                when {
                    notice.boolOrFalse("modelChanged") -> "切换模型后缓存未命中：$tokens Token 重新计费$cost"
                    notice.get("idleMinutes") != null -> {
                        val minutes = notice.longOrZero("idleMinutes")
                        "空闲 $minutes 分钟后缓存未命中：$tokens Token 重新计费$cost"
                    }
                    else -> "缓存未命中：$tokens Token 重新计费$cost"
                }
            }
            "cache_warm" -> {
                val note = notice.stringOrNull("note")?.let { "（$it）" } ?: ""
                "缓存已预热$note：${formatCacheWarmCost(notice.doubleOrZero("cost"))}"
            }
            "summary_cost" -> {
                if (notice.stringOrNull("source") == "compaction") return null
                val tokens = formatTokens(notice.longOrZero("tokens"))
                "分支摘要计费 $tokens Token${costSuffix(notice.doubleOrZero("cost"))}"
            }
            "reasoning_dropped" -> {
                val count = notice.longOrZero("count")
                val reasons = notice.stringList("reasons")
                    .takeIf { it.isNotEmpty() }
                    ?.let { "：${it.joinToString("、")}" }
                    ?: ""
                val one = count == 1L
                if (notice.stringOrNull("provider") == "openai") {
                    "OpenAI 丢弃了 ${if (one) "1 个" else "$count 个"}推理项$reasons（详情见会话文件）"
                } else {
                    "Anthropic 丢弃了 ${if (one) "1 个" else "$count 个"}思考块$reasons（详情见会话文件）"
                }
            }
            else -> null
        }
    }

    /** 与 pi-web `formatNoticeTokens` 一致：1000 以下原样，1k~10k 一位小数，再往上取整。 */
    fun formatTokens(count: Long): String = when {
        count < 1_000 -> count.toString()
        count < 10_000 -> String.format(Locale.US, "%.1fk", count / 1000.0)
        count < 1_000_000 -> "${Math.round(count / 1000.0)}k"
        count < 10_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        else -> "${Math.round(count / 1_000_000.0)}M"
    }

    /** 与 pi-web `formatNoticeCost` 一致：满一美分才显示，两位小数。 */
    fun formatCost(cost: Double): String? =
        if (cost >= 0.01) "$" + String.format(Locale.US, "%.2f", cost) else null

    /** 与 pi-web `formatCacheWarmCost` 一致：六位小数，第三位之后的尾零去掉。 */
    fun formatCacheWarmCost(cost: Double): String =
        "$" + String.format(Locale.US, "%.6f", cost).replace(Regex("(\\.\\d{3}\\d*?)0+$"), "$1")

    private fun costSuffix(cost: Double): String = formatCost(cost)?.let { "（约 $it）" } ?: ""
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.longOrZero(key: String): Long =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: 0L

private fun JsonObject.doubleOrZero(key: String): Double =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble ?: 0.0

private fun JsonObject.boolOrFalse(key: String): Boolean =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true

private fun JsonObject.stringList(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { element ->
            element.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        }
        ?: emptyList()
