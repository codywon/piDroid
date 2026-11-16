package com.pi.app.network

import com.google.gson.JsonObject
import com.pi.app.data.model.ExtensionUiDialog

/**
 * `extension_ui_request` 的字段解析（纯 JVM，可单测）。
 *
 * 为什么单独抽出来：PiSseClient 对**不认识**的类型有一层兼容 fallback，会把载荷里的
 * `text` / `content` 当正文增量。`extension_ui_request` 里恰好有 `set_editor_text`（带 `text`）、
 * `custom`（带 `lines`）这类方法，落在 fallback 上就会把扩展的文本印成模型输出。
 * 所以这个类型必须被显式认出来，解析结果也只从这条路径产生。
 *
 * 权威依据：pi-web `lib/types.ts` 的 `ExtensionUiRequest`、`lib/extension-ui-queue.ts`
 * 的 `isBlockingExtensionUiRequest()`。
 */
object ExtensionUiParsing {

    /** 解析一条 `extension_ui_request`；`id` 或 `method` 缺失时返回 null（无法回答的请求不算对话框）。 */
    fun of(obj: JsonObject): ExtensionUiDialog? {
        val id = obj.stringOrNull("id") ?: return null
        val method = obj.stringOrNull("method") ?: return null
        return ExtensionUiDialog(
            id = id,
            method = method,
            title = obj.stringOrNull("title") ?: "",
            message = obj.stringOrNull("message") ?: "",
            options = obj.stringList("options"),
            placeholder = obj.stringOrNull("placeholder") ?: "",
            prefill = obj.stringOrNull("prefill") ?: "",
            lines = obj.stringList("lines"),
            notifyType = obj.stringOrNull("notifyType") ?: "",
            // 只有显式 true 才算已关闭：缺字段（旧版本、或非 custom）一律当未关闭处理。
            closed = obj.get("closed")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean == true
        )
    }
}

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.takeIf { it.isNotEmpty() }

/** 只收字符串元素：`lines` / `options` 里万一混进非字符串，跳过它而不是整条请求失败。 */
private fun JsonObject.stringList(key: String): List<String> =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString }
        ?: emptyList()
