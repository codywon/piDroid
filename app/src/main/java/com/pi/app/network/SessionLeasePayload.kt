package com.pi.app.network

import com.google.gson.JsonParser

/**
 * `POST /api/agent/{id}/lease` 的回执（pi-web 0.13，`app/api/agent/[id]/lease/route.ts`）。
 *
 * 两件事同时发生：
 * 1. **保活**：每次续租把该会话的空闲倒计时重置成整整一个 idle 超时（10 分钟）。
 *    0.13 起「持有 SSE 流」不再保活（`session-liveness.ts` 删掉了浏览器租约），
 *    只有这个请求能挡住空闲回收 —— 不续租，选中的空闲会话（以及扩展状态）会被回收；
 * 2. **报告脚下在干什么**：`running`（有回合/压缩/bash）、`awaitingInput`（扩展对话框在等人）、
 *    `subagentsActive`（子代理还能在本会话开新回合）。隐藏页面据此决定要不要重新挂流。
 *
 * `supported=false`：旧网关没有这个路由（404/405）。此时什么都不做 —— 旧的保活机制
 * 由 SSE 流自己承担，不能因为新请求失败就当成错误刷屏。
 */
data class SessionLeaseReply(
    val supported: Boolean,
    val running: Boolean = false,
    val awaitingInput: Boolean = false,
    val subagentsActive: Boolean = false
) {
    /** 与 pi-web 的 `sessionLeaseShowsActivity()` 同口径：这件事值得把流重新挂上。 */
    val showsActivity: Boolean
        get() = supported && (running || awaitingInput || subagentsActive)
}

object SessionLeasePayload {

    fun of(body: String?): SessionLeaseReply {
        val obj = body?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        } ?: return SessionLeaseReply(supported = true)
        fun flag(key: String): Boolean =
            obj.get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean == true
        return SessionLeaseReply(
            supported = true,
            running = flag("running"),
            awaitingInput = flag("awaitingInput"),
            subagentsActive = flag("subagentsActive")
        )
    }
}
