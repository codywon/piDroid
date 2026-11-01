package com.pi.app.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.ForkSessionResult

/**
 * `POST /api/sessions/[id]/fork` 响应的解析。
 *
 * 守护的线上缺陷：pi-web 0.13 起顶层返回的是 `{ sessionId, session }`，新建会话的
 * `path` / `name` 都挪进了 `session`（SessionInfo）里。旧实现只在顶层找
 * `path` / `name`，于是两者恒为 null —— 分叉本身能成功，但新会话在手机上被命名成
 * 「源标题 · 分叉」，刷新列表后又会变回服务端给的「源标题 · 3f9c」，看起来像丢了东西。
 *
 * 顶层字段保留做兜底：更老的网关只回 `{ sessionId, path, name }`，不该因此退化。
 */
internal object ForkSessionResponseParsing {

    /** 只在 JSON 对象上取字符串；null / 非原始类型 / 空白一律当「没有」。 */
    private fun JsonObject?.string(key: String): String? =
        this?.get(key)
            ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }

    /**
     * @return 解析不出会话 ID 时返回 null（调用方据此报「未返回有效的分叉会话ID」）。
     */
    fun parse(bodyStr: String): ForkSessionResult? {
        val root = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
            ?: return null
        val sessionId = root.string("sessionId") ?: return null
        val info = root.get("session")?.takeIf { it.isJsonObject }?.asJsonObject
        return ForkSessionResult(
            sessionId = sessionId,
            path = info.string("path") ?: root.string("path"),
            name = info.string("name") ?: root.string("name")
        )
    }
}
