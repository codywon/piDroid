package com.pi.app.network

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.pi.app.data.model.ChatImageInput
import java.io.IOException

/**
 * 一次 `prompt` 提交的回执。
 *
 * pi-web 0.13 起，压缩上下文期间发出的提示词不再报错：`AgentSession.prompt()` 会拒绝
 * （`Cannot submit a prompt while compaction is in progress`），wrapper 把这次提交 park 住，
 * 压缩结束按 FIFO 发出，并回 `{success:true, data:{queued:true}}`。
 * `queued` 为真表示「还没成为一条已发送消息」：乐观气泡要撤掉，交给队列行显示。
 */
data class PromptSubmitAck(val queued: Boolean = false)

/**
 * `abort` 与 `clear_queue` 退回的提示词，以及压缩期间被 park 的提示词带来的图片。
 * 对齐 pi-web 的 `RecalledPrompts`：`{steering?: string[], followUp?: string[], images?: [{data, mimeType}]}`。
 * 退回的内容必须还给输入框，否则用户刚打的字就没了（TUI 的 abort/recall 也是这么做的）。
 */
data class RecalledPrompts(
    val steering: List<String> = emptyList(),
    val followUp: List<String> = emptyList(),
    val images: List<ChatImageInput> = emptyList()
) {
    val hasAnything: Boolean
        get() = steering.isNotEmpty() || followUp.isNotEmpty() || images.isNotEmpty()

    /** 退回正文：引导与后续消息按服务端顺序拼接（与 pi-web 一致）。 */
    val text: String
        get() = (steering + followUp).joinToString("\n\n")
}

/**
 * `/api/agent/{id}` 的命令失败。
 *
 * 三种失败必须区别对待，否则会把「网络丢了回执」当成「服务端没收到」而重发或丢草稿：
 * - 服务端明确拒绝（4xx，带 `{error, code?, accepted?}`）：消息确定没进去，正文要退回输入框；
 * - 传输层异常（连接断、超时）：结果未知，只能先等状态/快照确认，绝不自动重发；
 * - `reason: "cwd-missing"`：会话目录已被移动或删除（#1422），不是模型问题。
 */
class AgentCommandException(
    message: String,
    val code: String? = null,
    val accepted: Boolean? = null,
    val missingCwd: String? = null
) : IOException(message) {

    /** pi-web 的 `isPromptRejectedError()`：服务端明确回了 `code: "prompt_rejected", accepted: false`。 */
    val isPromptRejected: Boolean
        get() = code == "prompt_rejected" && accepted == false
}

/**
 * `/api/agent/{id}` 响应体的纯解析（可单测）。
 *
 * 权威格式（pi-web `lib/agent-client.ts`）：
 * - 成功：`{success: true, data: <结果>}`
 * - 失败（非 2xx）：`{error: string, code?, accepted?, reason?, cwd?}`
 */
object AgentCommandPayload {

    const val CWD_MISSING_REASON = "cwd-missing"

    fun ackOf(data: JsonElement?): PromptSubmitAck {
        val obj = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return PromptSubmitAck()
        val queued = obj.get("queued")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean == true
        return PromptSubmitAck(queued = queued)
    }

    fun recalledOf(data: JsonElement?): RecalledPrompts {
        val obj = data?.takeIf { it.isJsonObject }?.asJsonObject ?: return RecalledPrompts()
        return RecalledPrompts(
            steering = textsOf(obj.get("steering")),
            followUp = textsOf(obj.get("followUp")),
            images = imagesOf(obj.get("images"))
        )
    }

    fun failureOf(body: String?, httpCode: Int): AgentCommandException {
        val obj = body?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
            runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        }
        val error = obj?.get("error")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            ?.takeIf { it.isNotBlank() }
            ?: "HTTP $httpCode"
        val reason = obj?.get("reason")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        val cwd = obj?.get("cwd")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        return AgentCommandException(
            message = error,
            code = obj?.get("code")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString,
            accepted = obj?.get("accepted")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean,
            missingCwd = cwd?.takeIf { reason == CWD_MISSING_REASON && it.isNotBlank() }
        )
    }

    /**
     * 排队消息的正文。pi 的队列元素可能是字符串，也可能是对象（`{message}` / `{text}`），
     * 两种都认；空串与纯空白不占队列行。
     */
    private fun textsOf(element: JsonElement?): List<String> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            when {
                el.isJsonPrimitive -> el.asString
                el.isJsonObject -> el.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString
                    ?: el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString
                else -> null
            }
        }.filter { it.isNotBlank() }
    }

    private fun imagesOf(element: JsonElement?): List<ChatImageInput> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val data = obj.get("data")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                ?: return@mapNotNull null
            val mime = obj.get("mimeType")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            ChatImageInput(mimeType = mime?.takeIf { it.isNotBlank() } ?: "image/png", base64Data = data)
        }
    }
}
