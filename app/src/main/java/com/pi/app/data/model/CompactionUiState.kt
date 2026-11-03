package com.pi.app.data.model

/**
 * 「正在压缩上下文」的**按会话**状态（纯 JVM，可单测）。
 *
 * 历史缺陷（v1.8.46）：MainActivity 里只有一个全局 `isCompacting`，手动压缩的 POST 还在飞时
 * 切到任何会话，界面都会显示「正在压缩上下文...」；更糟的是这个全局标记只由那一次请求的
 * finally 复位，请求超时（实测压缩可跑 71s~333s，客户端 readTimeout 只有 120s）就会一直挂到
 * 超时那一刻，期间所有会话都像在压缩。
 *
 * 真源有两个，缺一不可：
 * 1) [requestInFlight] —— 本机发起的那次 `POST /api/agent/{id} {"type":"compact"}` 还在飞。
 *    它只代表「有请求在等」，不代表服务端一定还在压（可能早就结束了、只是回执丢了）。
 * 2) [serverCompacting] —— 服务端自己的压缩事实：SSE `compaction_start` / `compaction_end`，
 *    以及 `GET /api/agent/{id}`（`AgentRuntimeState.isCompacting`）。压缩也可能由 PC 端或
 *    auto-compaction 触发，那时本机根本没有请求在飞。
 *
 * 两者取并集，并且**只对带 sessionId 的那一个会话生效** —— 这就是修复「其他会话都在压缩」的关键。
 */
data class CompactionUiState(
    val requestInFlight: Set<String> = emptySet(),
    val serverCompacting: Set<String> = emptySet()
) {
    /** UI 唯一入口：这条会话此刻是否该显示「正在压缩」。空白 id（还没有会话）一律不算。 */
    fun isCompacting(sessionId: String?): Boolean {
        if (sessionId.isNullOrBlank()) return false
        return sessionId in requestInFlight || sessionId in serverCompacting
    }

    /** 本机发出压缩请求（按钮点击那一刻）。 */
    fun requestStarted(sessionId: String): CompactionUiState {
        if (sessionId.isBlank()) return this
        return copy(requestInFlight = requestInFlight + sessionId)
    }

    /**
     * 本机那次请求结束（成功 / 失败 / 超时）。
     *
     * 刻意**不动** [serverCompacting]：请求超时只说明「回执没拿到」，服务端很可能还在压；
     * 压缩真正结束由 SSE `compaction_end` 或状态里的 `isCompacting=false` 通知。
     */
    fun requestFinished(sessionId: String): CompactionUiState {
        if (sessionId.isBlank()) return this
        return copy(requestInFlight = requestInFlight - sessionId)
    }

    /** 服务端事件/状态：这条会话在不在压缩。 */
    fun serverChanged(sessionId: String, compacting: Boolean): CompactionUiState {
        if (sessionId.isBlank()) return this
        val next = if (compacting) serverCompacting + sessionId else serverCompacting - sessionId
        if (next == serverCompacting) return this
        return copy(serverCompacting = next)
    }

    /**
     * 服务端明确的「压缩结束了」（SSE `compaction_end`，或请求本身成功返回）。
     *
     * 这里连 [requestInFlight] 一起清：`compaction_end` 到达时压缩肯定已经结束，而那条 POST
     * 可能永远拿不到回执（隧道断 / 超时），不能让它把一个已经结束的压缩继续挂在界面上。
     *
     * 注意与 [serverChanged] 的区别：状态轮询读到一次 `isCompacting=false` 时，请求可能
     * 刚发出、服务端还没开始压，这时只清服务端标记，不能把本次请求的转圈也提前打断。
     */
    fun serverFinished(sessionId: String): CompactionUiState {
        if (sessionId.isBlank()) return this
        if (sessionId !in requestInFlight && sessionId !in serverCompacting) return this
        return copy(
            requestInFlight = requestInFlight - sessionId,
            serverCompacting = serverCompacting - sessionId
        )
    }

    /** 会话被删除 / 换设备后清理，避免集合无限增长。 */
    fun forget(sessionId: String): CompactionUiState {
        if (sessionId.isBlank()) return this
        if (sessionId !in requestInFlight && sessionId !in serverCompacting) return this
        return copy(
            requestInFlight = requestInFlight - sessionId,
            serverCompacting = serverCompacting - sessionId
        )
    }
}

/**
 * 历史压缩记录明细（对标 pi-web 会话中 type == "compaction" 的 entry）。
 */
data class CompactionRecord(
    val id: String = "",
    val timestamp: Long = 0L,
    val summary: String = "",
    val tokensBefore: Long = 0L,
    val tokensAfter: Long = 0L,
    val cost: Double = 0.0
) {
    /** 压缩释放的 token 差值 */
    val tokensSaved: Long
        get() = (tokensBefore - tokensAfter).coerceAtLeast(0L)

    /** 压缩比例（百分比） */
    val savingsPercent: Int
        get() = if (tokensBefore > 0L) {
            ((tokensSaved * 100) / tokensBefore).toInt().coerceIn(0, 100)
        } else 0
}

/**
 * 压缩失败文案的分类（纯 JVM，可单测）。
 *
 * pi 在压缩被 `abort_compaction` 打断时，`compact()` 会以 `"Compaction cancelled"` 拒绝
 * （摘要流被 signal 中断时也可能是 `"This operation was aborted"`）。这不是失败，而是用户
 * 自己按了「停止压缩」的预期结果，不能弹「压缩失败」。
 */
object CompactionFailure {

    /** 用户主动停止 / 被 abort 导致的「取消」。 */
    fun isCancellation(message: String?): Boolean {
        val text = message?.lowercase()?.trim() ?: return false
        if (text.isEmpty()) return false
        return text.contains("compaction cancel") ||
            text.contains("operation was aborted") ||
            text == "aborted"
    }
}
