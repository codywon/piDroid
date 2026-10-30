package com.pi.app.data.model

/**
 * 「每个会话最后确认的模型身份」的编解码（provider/modelId 一对，单一字符串存 SharedPreferences）。
 *
 * 为什么需要它：回到会话时，本机只有三样东西能告诉我们这条会话用的是哪个模型——
 * 内存快照、磁盘快照、全局默认。前两个都可能是**切换之前**的旧数据（磁盘快照还有 10s 写节流、
 * 只留 8 个），全局默认更不是这条会话的选择。三者都可能把 provider 显示成上一家，
 * 于是「回到会话 → 又从 deepseek 变回 codywon」。所以本地额外记一份**每会话**的身份，
 * 打开会话时先按它还原，再让服务端快照覆盖。
 */
object SessionModelIdentityCodec {
    /** provider 里不会出现 `/`，所以用第一个 `/` 分隔两边即可。 */
    fun encode(provider: String, modelId: String): String = "${provider.trim()}/${modelId.trim()}"

    fun decode(value: String?): Pair<String, String>? {
        val raw = value?.trim().orEmpty()
        val idx = raw.indexOf('/')
        if (idx <= 0 || idx == raw.length - 1) return null
        val provider = raw.substring(0, idx).trim()
        val modelId = raw.substring(idx + 1).trim()
        if (provider.isEmpty() || modelId.isEmpty()) return null
        return provider to modelId
    }

    /**
     * 写入一条会话身份；超出 [limit] 时丢掉最早写入的那些（[current] 需保持插入顺序，
     * 由调用方用 LinkedHashMap 维持）。
     */
    fun withEntry(
        current: Map<String, String>,
        sessionId: String,
        provider: String,
        modelId: String,
        limit: Int = 300
    ): Map<String, String> {
        if (sessionId.isBlank() || provider.isBlank() || modelId.isBlank()) return current
        val next = LinkedHashMap<String, String>()
        for ((k, v) in current) {
            if (k == sessionId) continue
            // 顺手丢掉解析不出来的历史脏值，避免一直占位
            if (decode(v) != null) next[k] = v
        }
        next[sessionId] = encode(provider, modelId)
        if (next.size <= limit) return next
        val trimmed = LinkedHashMap<String, String>()
        next.entries.toList().takeLast(limit).forEach { trimmed[it.key] = it.value }
        return trimmed
    }
}
