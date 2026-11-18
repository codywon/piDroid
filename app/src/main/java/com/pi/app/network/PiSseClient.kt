package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.AgentSseEvent
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaLoadPolicy
import com.pi.app.data.model.SseConnectionState
import com.pi.app.data.model.TokenStats
import com.pi.app.data.storage.AppPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PiSseClient(private val prefs: AppPreferences) {
    private class StreamSlot(
        val active: AtomicBoolean = AtomicBoolean(true),
        @Volatile var es: EventSource? = null
    )

    companion object {
        /**
         * pi-web events 路由的心跳周期（`lib/agent-event-wire.ts` 的 `AGENT_EVENT_HEARTBEAT_MS`）。
         *
         * 判断"流已就绪但长时间没有任何数据（心跳也算）→ 已死，需要重挂"时，
         * 阈值一律写成这个值的倍数（pi-web 是 2.5×），不要在调用处再写孤立秒数。
         */
        const val EVENT_HEARTBEAT_MS = 30_000L

        private val gson = Gson()

        private data class AssistantContentBlocks(val text: String, val thinking: String?)

        private fun JsonObject.safeString(key: String): String? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

        private fun JsonObject.safeBoolean(key: String): Boolean? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asBoolean }.getOrNull()
            }

        private fun JsonObject.safeInt(key: String): Int? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asInt }.getOrNull()
            }

        private fun JsonObject.safeLong(key: String): Long? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asLong }.getOrNull()
            }

        private fun JsonObject.safeDouble(key: String): Double? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asDouble }.getOrNull()
            }

        private fun JsonObject.safeObject(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.safeArray(key: String): JsonArray? =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray

        /**
         * 深度解析 pi-web `/api/agent/[id]/events` 推送的各种 JSON 事件结构（纯 JVM，严禁调用 `Log.*`）。
         */
        internal fun parsePiWebSsePayload(eventTypeHeader: String?, rawData: String): List<AgentSseEvent> {
            return try {
                val element = JsonParser.parseString(rawData)
                if (!element.isJsonObject) {
                    return listOf(AgentSseEvent(type = eventTypeHeader ?: "delta", delta = rawData))
                }
                val obj = element.asJsonObject
                val resolvedType = obj.safeString("type")
                    ?: eventTypeHeader
                    ?: "message"

                when (resolvedType) {
                    "heartbeat" -> {
                        // 心跳必须上报：pi 的 heartbeat 是 30s 一次（events 路由里 `NN=30000`）。
                        // 以前这里直接返回空列表，上层 lastSseActivityAt 就不会被刷新，
                        // 「流静默 15s 就重挂」的健康检查会把心跳误判成断流 —— 每重挂一次，
                        // 服务端就补发一份 streamingMessage 快照，正是过程输出成段重复的放大器。
                        listOf(AgentSseEvent(type = "heartbeat", isStreaming = true))
                    }

                    "connected" -> {
                        val streaming = obj.safeBoolean("isStreaming") ?: false
                        listOf(
                            AgentSseEvent(
                                type = "connected",
                                isStreaming = streaming,
                                phaseLabel = if (streaming) "正在等待模型..." else null
                            )
                        )
                    }

                    "agent_start" -> {
                        listOf(
                            AgentSseEvent(
                                type = "agent_start",
                                isStreaming = true,
                                phaseLabel = "正在等待模型..."
                            )
                        )
                    }

                    "turn_start" -> {
                        listOf(
                            AgentSseEvent(
                                type = "turn_start",
                                isStreaming = true,
                                phaseLabel = "正在等待模型..."
                            )
                        )
                    }

                    "turn_end" -> {
                        // 单轮回合结束（如模型刚执行完一个命令即将进入下一轮对话），并非整个会话结束
                        listOf(
                            AgentSseEvent(
                                type = "turn_end",
                                isStreaming = true,
                                phaseLabel = "正在等待模型..."
                            )
                        )
                    }

                    "auto_retry_start" -> {
                        val attempt = obj.safeInt("attempt") ?: 1
                        val maxAttempts = obj.safeInt("maxAttempts") ?: 3
                        val delayMs = obj.safeLong("delayMs") ?: 2000L
                        val errMsg = obj.safeString("errorMessage")
                        listOf(
                            AgentSseEvent(
                                type = "auto_retry_start",
                                isStreaming = true,
                                durationMs = delayMs,
                                output = errMsg,
                                phaseLabel = "正在自动重试 ($attempt/$maxAttempts)..."
                            )
                        )
                    }

                    "auto_retry_end" -> {
                        val success = obj.safeBoolean("success") ?: true
                        val attempt = obj.safeInt("attempt") ?: 1
                        val finalErr = obj.safeString("finalError") ?: obj.safeString("errorMessage")
                        listOf(
                            AgentSseEvent(
                                type = "auto_retry_end",
                                done = !success,
                                isStreaming = success,
                                isError = !success,
                                output = if (!success) (finalErr ?: "自动重试 ($attempt 次) 后仍失败，任务已终止") else null,
                                phaseLabel = if (success) "重试成功，正在继续..." else null
                            )
                        )
                    }

                    // 手动压缩与自动压缩必须分开：pi 的 `compact()` 是「先 abort 当前回合 → 生成摘要
                    // → 写 compaction entry」，结束后**不会**继续跑到一半的回合；而自动压缩发生在
                    // turn 边界，压缩完还要接着跑。以前两者都被折叠成 auto_compaction_*，上层就无法
                    // 区分「压缩结束 = 这一轮彻底结束」还是「压缩结束 = 继续等模型」。
                    "compaction_start" -> {
                        listOf(
                            AgentSseEvent(
                                type = "compaction_start",
                                isStreaming = true,
                                phaseLabel = "正在压缩上下文..."
                            )
                        )
                    }

                    "compaction_end" -> {
                        listOf(AgentSseEvent(type = "compaction_end", isStreaming = false))
                    }

                    "auto_compaction_start" -> {
                        listOf(
                            AgentSseEvent(
                                type = "auto_compaction_start",
                                isStreaming = true,
                                phaseLabel = "正在自动压缩上下文..."
                            )
                        )
                    }

                    "auto_compaction_end" -> {
                        listOf(
                            AgentSseEvent(
                                type = "auto_compaction_end",
                                isStreaming = true,
                                phaseLabel = "正在等待模型..."
                            )
                        )
                    }

                    "message_start" -> {
                        // pi-web 在流开始或重连时下发当前正在生成的 message 快照（`message`）。
                        // 与 pi-web 客户端一致：这是「整条消息的权威快照」，语义是**覆盖替换**，
                        // 所以即使正文为空也要带 messageId 上抛，让上层记住这条在途消息的身份。
                        val msgObj = obj.safeObject("message")
                        if (msgObj != null && msgObj.safeString("role") == "assistant") {
                            parseAssistantMessageSnapshot(msgObj, isFinal = false)
                        } else {
                            listOf(AgentSseEvent(type = "message_start", isStreaming = true))
                        }
                    }

                    "message_update" -> {
                        // pi-web 核心增量事件：{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"..."}}
                        val ame = obj.safeObject("assistantMessageEvent")
                        if (ame != null) {
                            val subType = ame.safeString("type") ?: ""
                            when (subType) {
                                "text_delta" -> {
                                    val d = ame.safeString("delta") ?: ""
                                    if (d.isNotEmpty()) listOf(AgentSseEvent(type = "delta", delta = d)) else emptyList()
                                }
                                "text_end" -> {
                                    val msgObj = obj.safeObject("message")
                                    val full = if (msgObj != null) {
                                        extractAssistantContent(msgObj).text
                                    } else {
                                        ame.safeString("content")
                                    }
                                    val msgId = msgObj?.let { assistantMessageId(it) }
                                    // isStreaming 必须继续为 true：text_end 只代表「这一段正文讲完了」，
                                    // 后面往往还有工具调用 / 下一段正文，整个回合的开始与结束由
                                    // agent_end + /state 巡检判定。之前这里是 null（被上层当成 false），
                                    // 会让流式指标在回合中途被摘掉，速度显示重新从 0 起跳。
                                    if (!full.isNullOrEmpty()) {
                                        listOf(
                                            AgentSseEvent(
                                                type = "text_replace",
                                                content = full,
                                                isStreaming = true,
                                                messageId = msgId
                                            )
                                        )
                                    } else emptyList()
                                }
                                "thinking_delta" -> {
                                    val d = ame.safeString("delta") ?: ""
                                    if (d.isNotEmpty()) listOf(AgentSseEvent(type = "thinking", thinking = d)) else emptyList()
                                }
                                "thinking_end" -> {
                                    val full = ame.safeString("content")
                                    if (!full.isNullOrEmpty()) listOf(AgentSseEvent(type = "thinking_replace", thinking = full)) else emptyList()
                                }
                                "toolcall_start" -> {
                                    val tcId = ame.safeString("id")
                                    val tName = ame.safeString("toolName") ?: "tool"
                                    listOf(
                                        AgentSseEvent(
                                            type = "tool_call",
                                            toolCallId = tcId,
                                            toolName = tName,
                                            preview = "正在生成工具参数...",
                                            phaseLabel = "正在准备 $tName..."
                                        )
                                    )
                                }
                                "toolcall_delta" -> {
                                    listOf(
                                        AgentSseEvent(
                                            type = "toolcall_delta",
                                            isStreaming = true,
                                            phaseLabel = "正在生成工具参数..."
                                        )
                                    )
                                }
                                "toolcall_end" -> {
                                    val tcObj = ame.safeObject("toolCall")
                                    val tcId = tcObj?.safeString("id")
                                    val tName = tcObj?.safeString("name") ?: "tool"
                                    val argsElem = tcObj?.get("arguments")?.takeIf { !it.isJsonNull }
                                    val preview = extractPreview(argsElem)
                                    val cmdStr = argsElem?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                                    val pathMedia = MediaAttachmentParsing.fromToolArgs(
                                        tName, preview, argsElem,
                                        MediaLoadPolicy.toolIdPrefix(tcId)
                                    )
                                    listOf(
                                        AgentSseEvent(
                                            type = "tool_call_ready",
                                            toolCallId = tcId,
                                            toolName = tName,
                                            preview = preview,
                                            command = cmdStr,
                                            mediaList = pathMedia
                                        )
                                    )
                                }
                                else -> emptyList()
                            }
                        } else {
                            emptyList()
                        }
                    }

                    "message_end" -> {
                        val msgObj = obj.safeObject("message")
                        if (msgObj != null && msgObj.safeString("role") == "assistant") {
                            parseAssistantMessageSnapshot(msgObj, isFinal = true)
                        } else {
                            listOf(AgentSseEvent(type = "message_end_other", isStreaming = true))
                        }
                    }

                    "tool_execution_start" -> {
                        val tcId = obj.safeString("toolCallId")
                        val tName = obj.safeString("toolName") ?: "tool"
                        val argsElem = (obj.get("args") ?: obj.get("arguments") ?: obj.get("input"))
                            ?.takeIf { !it.isJsonNull }
                        val preview = extractPreview(argsElem)
                        val cmdStr = argsElem?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                        val pathMedia = MediaAttachmentParsing.fromToolArgs(
                            tName, preview, argsElem,
                            MediaLoadPolicy.toolIdPrefix(tcId)
                        )
                        listOf(
                            AgentSseEvent(
                                type = "tool_execution_start",
                                toolCallId = tcId,
                                toolName = tName,
                                preview = preview,
                                command = cmdStr,
                                mediaList = pathMedia,
                                isStreaming = true,
                                phaseLabel = "正在执行 $tName..."
                            )
                        )
                    }

                    "tool_execution_update" -> {
                        val tcId = obj.safeString("toolCallId")
                        val tName = obj.safeString("toolName") ?: "tool"
                        val partial = obj.safeObject("partialResult")
                        val contentElem = partial?.get("content")
                        val outText = extractContentText(contentElem)
                        val media = extractMediaAttachments(contentElem, outText, tcId)
                        val isErr = partial?.safeBoolean("isError") ?: false
                        listOf(
                            AgentSseEvent(
                                type = "tool_result",
                                toolCallId = tcId,
                                toolName = tName,
                                output = outText,
                                mediaList = media,
                                isError = isErr,
                                isStreaming = true,
                                phaseLabel = "正在执行 $tName..."
                            )
                        )
                    }

                    "tool_execution_end" -> {
                        val tcId = obj.safeString("toolCallId")
                        val tName = obj.safeString("toolName") ?: "tool"
                        val resObj = obj.safeObject("result")
                        val contentElem = resObj?.get("content")
                        val outText = extractContentText(contentElem)
                        val media = extractMediaAttachments(contentElem, outText, tcId)
                        val isErr = obj.safeBoolean("isError")
                            ?: resObj?.safeBoolean("isError")
                            ?: false
                        listOf(
                            AgentSseEvent(
                                type = "tool_execution_end",
                                toolCallId = tcId,
                                toolName = tName,
                                output = outText,
                                mediaList = media,
                                isError = isErr,
                                isStreaming = false,
                                phaseLabel = "正在等待模型..."
                            )
                        )
                    }

                    // 排队消息的真实状态（pi-web 的 `queue_update`，压缩期间被 park 的提示词也在里面）。
                    // 以前靠 3s 一次的状态轮询补，撤回/停止后本地队列会先残留一轮。
                    "queue_update" -> {
                        listOf(
                            AgentSseEvent(
                                type = "queue_update",
                                steering = stringList(obj.get("steering")),
                                followUp = stringList(obj.get("followUp"))
                            )
                        )
                    }

                    // 投递已受理、但异步失败（压缩结束后的重申被拒等）：必须让用户看见错误。
                    "prompt_error" -> {
                        val errorElem = obj.get("error")
                        val errorFromField = when {
                            errorElem == null || errorElem.isJsonNull -> null
                            errorElem.isJsonPrimitive -> errorElem.asString
                            errorElem.isJsonObject -> errorElem.asJsonObject.safeString("message")
                            else -> null
                        }
                        val message = obj.safeString("errorMessage")?.takeIf { it.isNotBlank() }
                            ?: errorFromField?.takeIf { it.isNotBlank() }
                            ?: "消息发送失败"
                        listOf(AgentSseEvent(type = "prompt_error", error = message, isError = true))
                    }

                    "agent_end", "agent_settled", "prompt_done", "agent_idle" -> {
                        val isAborted = (obj.safeBoolean("aborted") == true) ||
                            (obj.safeString("status").equals("aborted", ignoreCase = true))
                        // 子代理还在跑时不能当“任务完成”处理（避免提前震动/推送）
                        val subagentsActive = obj.safeBoolean("subagentsActive") == true
                        listOf(
                            AgentSseEvent(
                                type = "agent_end",
                                done = true,
                                isStreaming = false,
                                aborted = isAborted,
                                subagentsActive = subagentsActive
                            )
                        )
                    }

                    "turn_completed", "done", "message_stop" -> {
                        // 单条消息流结束，不代表整个多步会话结束
                        listOf(AgentSseEvent(type = "message_stop", isStreaming = false))
                    }
                    // 扩展的对话框 / 状态推送。必须显式认出来，不能落到下面的兼容 fallback：
                    // fallback 会把载荷里的 `text` 当正文增量，而 set_editor_text 就带一个 text。
                    "extension_ui_request" -> ExtensionUiParsing.of(obj)
                        ?.let { listOf(AgentSseEvent(type = "extension_ui_request", extensionUi = it)) }
                        ?: emptyList()
                    "extension_ui_closed" -> obj.safeString("id")
                        ?.let { listOf(AgentSseEvent(type = "extension_ui_closed", extensionUiId = it)) }
                        ?: emptyList()
                    // 扩展内部报错：与 pi-web 一致，不把它渲染成模型正文。
                    "extension_error" -> emptyList()

                    else -> {
                        // 兼容简化格式 (delta / thinking / tool)
                        val deltaVal = obj.safeString("delta")
                            ?: obj.safeString("text")
                            ?: obj.safeString("content")
                        val thinkingVal = obj.safeString("thinking")
                        val toolVal = obj.safeString("tool")
                            ?: obj.safeString("toolName")
                        val cmdVal = obj.safeString("command")
                            ?: obj.get("input")?.takeIf { !it.isJsonNull }?.let {
                                if (it.isJsonPrimitive) it.asString else gson.toJson(it)
                            }
                        val outVal = obj.safeString("output")
                        val durVal = obj.safeLong("durationMs")

                        listOf(
                            AgentSseEvent(
                                type = resolvedType,
                                delta = deltaVal,
                                thinking = thinkingVal,
                                tool = toolVal,
                                toolName = toolVal,
                                command = cmdVal,
                                output = outVal,
                                durationMs = durVal
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                if (rawData.trimStart().startsWith("{")) {
                    emptyList()
                } else {
                    listOf(AgentSseEvent(type = eventTypeHeader ?: "delta", delta = rawData))
                }
            }
        }

        private fun extractAssistantContent(msgObj: JsonObject): AssistantContentBlocks {
            val textSb = StringBuilder()
            val thinkingSb = StringBuilder()
            msgObj.safeArray("content")?.forEach { item ->
                if (item.isJsonObject) {
                    val o = item.asJsonObject
                    when (o.safeString("type")) {
                        "text", "output_text" -> {
                            val str = o.safeString("text") ?: ""
                            if (str.isNotEmpty()) {
                                if (textSb.isNotEmpty()) textSb.append('\n')
                                textSb.append(str)
                            }
                        }
                        "thinking" -> {
                            val th = o.safeString("thinking") ?: o.safeString("text") ?: ""
                            if (th.isNotEmpty()) {
                                if (thinkingSb.isNotEmpty()) thinkingSb.append('\n')
                                thinkingSb.append(th)
                            }
                        }
                    }
                }
            }
            return AssistantContentBlocks(
                text = textSb.toString(),
                thinking = thinkingSb.toString().takeIf { it.isNotEmpty() }
            )
        }

        private fun parseAssistantMessageSnapshot(msgObj: JsonObject, isFinal: Boolean): List<AgentSseEvent> {
            val events = mutableListOf<AgentSseEvent>()
            val modelName = msgObj.safeString("model")
            var usageSummary: String? = null
            val uObj = msgObj.safeObject("usage")
            if (uObj != null) {
                val uIn = uObj.safeLong("input") ?: 0L
                val uOut = uObj.safeLong("output") ?: 0L
                val uCacheR = uObj.safeLong("cacheRead") ?: 0L
                val uCacheW = uObj.safeLong("cacheWrite") ?: 0L
                val uCost = uObj.safeObject("cost")?.safeDouble("total") ?: 0.0
                usageSummary = TokenStats.formatTurnUsage(uIn, uOut, uCacheR, uCacheW, uCost)
            }

            val stopReason = msgObj.safeString("stopReason")
            val hasToolCalls = stopReason == "toolUse" || stopReason == "tool_calls" ||
                (msgObj.safeArray("content")?.any {
                    it.isJsonObject && it.asJsonObject.safeString("type") == "toolCall"
                } == true)

            val contentBlocks = extractAssistantContent(msgObj)
            val fullText = contentBlocks.text
            val fullThinking = contentBlocks.thinking ?: msgObj.safeString("thinking")?.takeIf { it.isNotEmpty() }

            if (!isFinal) {
                // 重连时 pi-web 会下发「当前正在生成的 message 快照」（message_start）。
                // 对齐 pi-web 客户端：快照 = 整条消息覆盖（`dispatch({type:"snapshot"})`），
                // 因此即使正文为空也要上抛，让上层先把在途消息身份记下来。
                return listOf(
                    AgentSseEvent(
                        type = "message_snapshot",
                        content = fullText,
                        thinking = fullThinking,
                        isStreaming = true,
                        modelName = modelName,
                        messageId = assistantMessageId(msgObj)
                    )
                )
            }

            // 对齐 pi-web (useAgentSession.ts case "message_end"):
            // message_end 携带着该消息定稿时的完整权威文本（event.message），
            // 必须回传 content，防止流式期间网络丢包/背压导致的气泡截断无法被定稿更新。
            events.add(
                AgentSseEvent(
                    type = "message_end_sync",
                    content = fullText.takeIf { it.isNotEmpty() },
                    thinking = fullThinking,
                    modelName = modelName,
                    usageSummary = usageSummary,
                    isStreaming = hasToolCalls,
                    phaseLabel = if (hasToolCalls) "正在准备执行工具..." else "正在等待模型...",
                    messageId = assistantMessageId(msgObj)
                )
            )
            return events
        }

        /**
         * pi assistant message 的身份：对齐 pi-web 的 streamingMessage 身份判定。
         * pi 的 message 对象没有稳定的 id（协议里只保证 role/content/timestamp），
         * 所以优先 id/responseId，最后用 timestamp 作为身份 —— 同一条消息的重连补发时间戳不变，
         * 而新的一条消息时间戳必然不同。
         */
        private fun assistantMessageId(msgObj: JsonObject): String? {
            for (key in listOf("id", "responseId", "messageId")) {
                val s = msgObj.safeString(key)?.trim()
                if (!s.isNullOrEmpty()) return s
            }
            return msgObj.safeString("timestamp")?.trim()?.takeIf { it.isNotEmpty() }
        }

        /** 队列类的字符串数组字段（`steering` / `followUp`）：元素可能是串，也可能是 `{message}` 对象。 */
        private fun stringList(element: JsonElement?): List<String> {
            val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
            return array.mapNotNull { el ->
                when {
                    el.isJsonPrimitive -> el.asString
                    el.isJsonObject -> el.asJsonObject.safeString("message")
                        ?: el.asJsonObject.safeString("text")
                    else -> null
                }
            }.filter { it.isNotBlank() }
        }

        private fun extractPreview(element: JsonElement?): String? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonPrimitive) return element.asString.replace('\n', ' ').trim().take(120)
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            for (k in listOf("command", "path", "file_path", "filePath", "pattern", "query", "url")) {
                val v = obj.get(k)?.takeIf { !it.isJsonNull }
                if (v != null) {
                    return (if (v.isJsonPrimitive) v.asString else v.toString()).replace('\n', ' ').trim().take(120)
                }
            }
            val first = obj.entrySet().firstOrNull()?.value ?: return null
            return (if (first.isJsonPrimitive) first.asString else first.toString()).replace('\n', ' ').trim().take(120)
        }

        private fun extractContentText(element: JsonElement?): String? {
            if (element == null || element.isJsonNull) return null
            if (element.isJsonPrimitive) return element.asString
            if (element.isJsonArray) {
                val sb = StringBuilder()
                for (item in element.asJsonArray) {
                    if (item.isJsonObject) {
                        val text = item.asJsonObject.safeString("text")
                        if (text != null) {
                            sb.append(text).append("\n")
                        }
                    }
                }
                return sb.toString().trim().takeIf { it.isNotEmpty() }
            }
            return null
        }

        private fun extractMediaAttachments(
            contentElem: JsonElement?,
            outputText: String? = null,
            toolCallId: String? = null
        ): List<MediaAttachment> = MediaAttachmentParsing.fromContent(
            contentElem,
            outputText,
            MediaLoadPolicy.toolIdPrefix(toolCallId)
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接无读取超时
        .pingInterval(15, TimeUnit.SECONDS)   // TCP 保活心跳
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(SseConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SseConnectionState> = _connectionState.asStateFlow()
    private val streamTokenCounter = AtomicLong(0L)
    @Volatile
    private var activeStreamToken: Long = 0L

    /**
     * 监听 pi-web Agent 实时流式事件
     * 完整支持 pi-web 的 `/api/agent/[id]/events` 协议（connected, agent_start, message_start,
     * message_update -> assistantMessageEvent, message_end, tool_execution_start/update/end, agent_end）
     * 并具备指数退避自动重连与重连后快照对账触发能力。
     */
    fun listenAgentEvents(sessionId: String): Flow<AgentSseEvent> = callbackFlow {
        val streamToken = streamTokenCounter.incrementAndGet()
        activeStreamToken = streamToken
        val closedByUser = AtomicBoolean(false)
        // pi-web 0.16.0：会话结束时服务端先推 session_ended，再主动关流。
        // 之后的 onClosed 不是断线，不该重挂（见 AgentEventReconnectPolicy）。
        val sessionEnded = AtomicBoolean(false)
        val retryAttempt = AtomicInteger(0)
        val currentSlot = AtomicReference<StreamSlot?>(null)
        val reconnectJob = AtomicReference<Job?>(null)

        fun updateState(state: SseConnectionState) {
            if (activeStreamToken == streamToken) {
                _connectionState.value = state
            }
        }

        fun connect() {
            if (closedByUser.get()) return
            val baseUrl = prefs.serverUrl.trim().trimEnd('/')
            val token = prefs.authToken.trim()
            if (baseUrl.isBlank()) {
                updateState(SseConnectionState.DISCONNECTED)
                return
            }

            val attempt = retryAttempt.get()
            val nextState = if (attempt == 0) {
                SseConnectionState.CONNECTING
            } else {
                SseConnectionState.RECONNECTING
            }
            updateState(nextState)
            trySend(AgentSseEvent(type = "connection_state", connectionState = nextState.name))

            // token 只走 Authorization 头，绝不放进 URL：
            // Cloudflare Tunnel / 反向代理会把完整 URL 写进访问日志，
            // query 里的 token 等于把网关密钥明文落盘。
            val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
            val url = "$baseUrl/api/agent/$encodedSessionId/events"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
                // 关键：必须显式声明 identity。不写这一行 OkHttp 会自动加
                // Accept-Encoding: gzip，而 gzip 流必须攒满缓冲区才能解压，
                // SSE 就会被「打包」成一批一批到达 —— 表现为正文挤牙膏、
                // 流式速率经常读到 0。
                .header("Accept-Encoding", "identity")
                .header("Authorization", "Bearer $token")
                .build()

            val slot = StreamSlot()
            val listener = object : EventSourceListener() {
                private fun isCurrentSource(): Boolean {
                    return !closedByUser.get() && slot.active.get() && currentSlot.get() === slot
                }

                override fun onOpen(es: EventSource, response: Response) {
                    if (!isCurrentSource()) return
                    val wasReconnecting = retryAttempt.getAndSet(0) > 0
                    updateState(SseConnectionState.CONNECTED)
                    Log.d("PiSseClient", "SSE Connected for session $sessionId")
                    trySend(AgentSseEvent(type = "connection_state", connectionState = SseConnectionState.CONNECTED.name))
                    if (wasReconnecting) {
                        // 断线重连成功后通知上层立即同步一次完整会话快照，弥补断流期间遗漏的事件
                        trySend(AgentSseEvent(type = "reconnected_sync"))
                    }
                }

                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    if (!isCurrentSource()) return
                    val trimmed = data.trim()
                    if (trimmed.isEmpty() || trimmed == "[DONE]") return
                    val parsedList = parsePiWebSsePayload(type, trimmed)
                    for (ev in parsedList) {
                        if (ev.type == "session_ended") sessionEnded.set(true)
                        trySend(ev)
                    }
                }

                override fun onClosed(es: EventSource) {
                    if (!isCurrentSource()) return
                    Log.d("PiSseClient", "SSE Closed for session $sessionId")
                    scheduleReconnect()
                }

                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    if (!isCurrentSource()) return
                    Log.w("PiSseClient", "SSE Disconnected (${response?.code ?: t?.message}), scheduling reconnect...")
                    // 网关在拒绝我们手里的 Token（轮换过）：退避重连只会一直 401，把结论交给
                    // 全局信号后就停手 —— 界面会提示重新扫码，重扫成功后按 state 变化重新挂流。
                    if (AuthRejection.isGatewayInvalidToken(
                            response?.code ?: 0,
                            response?.header("WWW-Authenticate")
                        )
                    ) {
                        if (prefs.serverUrl.trim().trimEnd('/') == baseUrl && prefs.authToken.trim() == token) {
                            AuthRejectionSignal.reportRejected()
                            updateState(SseConnectionState.DISCONNECTED)
                            trySend(
                                AgentSseEvent(
                                    type = "connection_state",
                                    connectionState = SseConnectionState.DISCONNECTED.name
                                )
                            )
                            return
                        }
                    }
                    scheduleReconnect()
                }

                private fun scheduleReconnect() {
                    if (!AgentEventReconnectPolicy.shouldReconnect(sessionEnded.get(), closedByUser.get())) {
                        // 会话已结束（或调用方已关流）：服务端已经收流，重连只会空转，
                        // 还会把状态拖进 RECONNECTING 误报「连不上网关」。收成 DISCONNECTED 即可。
                        updateState(SseConnectionState.DISCONNECTED)
                        trySend(
                            AgentSseEvent(
                                type = "connection_state",
                                connectionState = SseConnectionState.DISCONNECTED.name
                            )
                        )
                        return
                    }
                    val attemptCount = retryAttempt.updateAndGet { (it + 1).coerceAtMost(6) }
                    updateState(SseConnectionState.RECONNECTING)
                    trySend(AgentSseEvent(type = "connection_state", connectionState = SseConnectionState.RECONNECTING.name))
                    val backoffMs = (1000L * (1L shl (attemptCount - 1).coerceAtMost(4))).coerceAtMost(10_000L)
                    reconnectJob.getAndSet(
                        launch {
                            delay(backoffMs)
                            if (!closedByUser.get() && slot.active.get() && currentSlot.get() === slot) {
                                slot.active.set(false)
                                slot.es?.cancel()
                                currentSlot.compareAndSet(slot, null)
                                connect()
                            }
                        }
                    )?.cancel()
                }
            }

            val factory = EventSources.createFactory(client)
            // 先让新 slot 就位，再调 newEventSource：即使 newEventSource 同步触发 onFailure，
            // isCurrentSource() 也能识别当前流并安排重连，同时防止 awaitClose 漏掉 cancel。
            currentSlot.getAndSet(slot)?.let { oldSlot ->
                oldSlot.active.set(false)
                oldSlot.es?.cancel()
            }
            if (closedByUser.get()) {
                slot.active.set(false)
                currentSlot.compareAndSet(slot, null)
                return
            }
            val created = factory.newEventSource(request, listener)
            slot.es = created
            if (closedByUser.get() || !slot.active.get()) {
                created.cancel()
            }
        }

        connect()

        awaitClose {
            closedByUser.set(true)
            reconnectJob.getAndSet(null)?.cancel()
            currentSlot.getAndSet(null)?.let { oldSlot ->
                oldSlot.active.set(false)
                oldSlot.es?.cancel()
            }
            updateState(SseConnectionState.DISCONNECTED)
        }
    }

    internal fun parsePiWebSsePayload(eventTypeHeader: String?, rawData: String): List<AgentSseEvent> =
        Companion.parsePiWebSsePayload(eventTypeHeader, rawData)
}
