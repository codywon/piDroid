package com.pi.app.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 一条会话此刻的模型身份（`GET /api/sessions/{id}/context?tail=1` 里的 `context.model`）。
 *
 * 为什么要单独做一个 1.9KB 的探针：pi-web 的 `set_model` **不会发 SSE 事件**
 * （实测 `set_model` 期间的事件流里只有 `connected`，切思考档才会有 `thinking_level_changed`），
 * 所以 PC 端切的模型只能靠主动拉。全量快照 267KB、`/state` 25KB，
 * `context?tail=1` 只要 1.9KB，适合放进 3s 一轮的对账循环。
 */
data class SessionModelRef(
    val provider: String,
    val modelId: String,
    val thinkingLevel: String? = null
)

object SessionModelRefParser {
    /**
     * 兼容两种外形：`{context:{model:{provider,modelId}},...}` 与直接的 `{model:{...}}`；
     * `modelId` 缺失时退回 `id`。拿不到完整身份（缺 provider 或 id）就返回 null ——
     * 宁可这一轮不动，也不能拿半个身份去改界面。
     */
    fun parse(raw: String?): SessionModelRef? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val ctx = root.get("context")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        return fromContext(ctx)
    }

    fun fromContext(ctx: JsonObject): SessionModelRef? {
        val model = ctx.get("model")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val provider = model.get("provider")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
        val modelId = (
            model.get("modelId")?.takeIf { !it.isJsonNull }?.asString
                ?: model.get("id")?.takeIf { !it.isJsonNull }?.asString
            )?.trim().orEmpty()
        if (provider.isEmpty() || modelId.isEmpty()) return null
        val thinking = ctx.get("thinkingLevel")?.takeIf { !it.isJsonNull }?.asString?.trim()
        return SessionModelRef(
            provider = provider,
            modelId = modelId,
            thinkingLevel = thinking?.takeIf { it.isNotEmpty() }
        )
    }
}
