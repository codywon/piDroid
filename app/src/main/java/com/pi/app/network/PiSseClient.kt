package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.AgentSseEvent
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaKind
import com.pi.app.data.model.SseConnectionState
import com.pi.app.data.model.TokenStats
import com.pi.app.data.storage.AppPreferences
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

class PiSseClient(private val prefs: AppPreferences) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 长连接无读取超时
        .pingInterval(15, TimeUnit.SECONDS)   // TCP 保活心跳
        .retryOnConnectionFailure(true)
        .build()

    private val _connectionState = MutableStateFlow(SseConnectionState.DISCONNECTED)
    val connectionState: StateFlow<SseConnectionState> = _connectionState.asStateFlow()

    /**
     * 监听 pi-web Agent 实时流式事件
     * 完整支持 pi-web 的 `/api/agent/[id]/events` 协议（connected, agent_start, message_start,
     * message_update -> assistantMessageEvent, message_end, tool_execution_start/update/end, agent_end）
     * 并具备指数退避自动重连与重连后快照对账触发能力。
     */
    fun listenAgentEvents(sessionId: String): Flow<AgentSseEvent> = callbackFlow {
        var eventSource: EventSource? = null
        var isClosedByUser = false
        var retryAttempt = 0

        fun connect() {
            if (isClosedByUser) return
            val baseUrl = prefs.serverUrl.trimEnd('/')
            val token = prefs.authToken
            if (baseUrl.isBlank()) {
                _connectionState.value = SseConnectionState.DISCONNECTED
                return
            }

            _connectionState.value = if (retryAttempt == 0) {
                SseConnectionState.CONNECTING
            } else {
                SseConnectionState.RECONNECTING
            }
            trySend(AgentSseEvent(type = "connection_state", connectionState = _connectionState.value.name))

            // token 只走 Authorization 头，绝不放进 URL：
            // Cloudflare Tunnel / 反向代理会把完整 URL 写进访问日志，
            // query 里的 token 等于把网关密钥明文落盘。
            val url = "$baseUrl/api/agent/$sessionId/events"
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

            val listener = object : EventSourceListener() {
                override fun onOpen(es: EventSource, response: Response) {
                    val wasReconnecting = retryAttempt > 0
                    retryAttempt = 0
                    _connectionState.value = SseConnectionState.CONNECTED
                    Log.d("PiSseClient", "SSE Connected for session $sessionId")
                    trySend(AgentSseEvent(type = "connection_state", connectionState = SseConnectionState.CONNECTED.name))
                    if (wasReconnecting) {
                        // 断线重连成功后通知上层立即同步一次完整会话快照，弥补断流期间遗漏的事件
                        trySend(AgentSseEvent(type = "reconnected_sync"))
                    }
                }

                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    val trimmed = data.trim()
                    if (trimmed.isEmpty() || trimmed == "[DONE]") return
                    val parsedList = parsePiWebSsePayload(type, trimmed)
                    for (ev in parsedList) {
                        trySend(ev)
                    }
                }

                override fun onClosed(es: EventSource) {
                    Log.d("PiSseClient", "SSE Closed for session $sessionId")
                    if (!isClosedByUser) {
                        scheduleReconnect()
                    }
                }

                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    Log.w("PiSseClient", "SSE Disconnected (${response?.code ?: t?.message}), scheduling reconnect...")
                    // 网关在拒绝我们手里的 Token（轮换过）：退避重连只会一直 401，把结论交给
                    // 全局信号后就停手 —— 界面会提示重新扫码，重扫成功后按 state 变化重新挂流。
                    if (AuthRejection.isGatewayInvalidToken(
                            response?.code ?: 0,
                            response?.header("WWW-Authenticate")
                        )
                    ) {
                        AuthRejectionSignal.reportRejected()
                        _connectionState.value = SseConnectionState.DISCONNECTED
                        trySend(
                            AgentSseEvent(
                                type = "connection_state",
                                connectionState = SseConnectionState.DISCONNECTED.name
                            )
                        )
                        return
                    }
                    if (!isClosedByUser) {
                        scheduleReconnect()
                    }
                }

                private fun scheduleReconnect() {
                    if (isClosedByUser) return
                    retryAttempt = (retryAttempt + 1).coerceAtMost(6)
                    _connectionState.value = SseConnectionState.RECONNECTING
                    trySend(AgentSseEvent(type = "connection_state", connectionState = SseConnectionState.RECONNECTING.name))
                    val backoffMs = (1000L * (1L shl (retryAttempt - 1).coerceAtMost(4))).coerceAtMost(10_000L)
                    launch {
                        delay(backoffMs)
                        if (!isClosedByUser) {
                            eventSource?.cancel()
                            connect()
                        }
                    }
                }
            }

            val factory = EventSources.createFactory(client)
            eventSource = factory.newEventSource(request, listener)
        }

        connect()

        awaitClose {
            isClosedByUser = true
            _connectionState.value = SseConnectionState.DISCONNECTED
            eventSource?.cancel()
        }
    }

    /**
     * 深度解析 pi-web `/api/agent/[id]/events` 推送的各种 JSON 事件结构
     */
    private fun parsePiWebSsePayload(eventTypeHeader: String?, rawData: String): List<AgentSseEvent> {
        return try {
            val element = JsonParser.parseString(rawData)
            if (!element.isJsonObject) {
                return listOf(AgentSseEvent(type = eventTypeHeader ?: "delta", delta = rawData))
            }
            val obj = element.asJsonObject
            val resolvedType = obj.get("type")?.takeIf { !it.isJsonNull }?.asString
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
                    val streaming = obj.get("isStreaming")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
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
                    val attempt = obj.get("attempt")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                    val maxAttempts = obj.get("maxAttempts")?.takeIf { !it.isJsonNull }?.asInt ?: 3
                    val delayMs = obj.get("delayMs")?.takeIf { !it.isJsonNull }?.asLong ?: 2000L
                    val errMsg = obj.get("errorMessage")?.takeIf { !it.isJsonNull }?.asString
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
                    val success = obj.get("success")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                    val attempt = obj.get("attempt")?.takeIf { !it.isJsonNull }?.asInt ?: 1
                    val finalErr = obj.get("finalError")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("errorMessage")?.takeIf { !it.isJsonNull }?.asString
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

                "auto_compaction_start", "compaction_start" -> {
                    listOf(
                        AgentSseEvent(
                            type = "auto_compaction_start",
                            isStreaming = true,
                            phaseLabel = "正在自动压缩上下文..."
                        )
                    )
                }

                "auto_compaction_end", "compaction_end" -> {
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
                    val msgObj = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (msgObj != null && msgObj.get("role")?.asString == "assistant") {
                        parseAssistantMessageSnapshot(msgObj, isFinal = false)
                    } else {
                        listOf(AgentSseEvent(type = "message_start", isStreaming = true))
                    }
                }

                "message_update" -> {
                    // pi-web 核心增量事件：{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"..."}}
                    val ame = obj.get("assistantMessageEvent")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (ame != null) {
                        val subType = ame.get("type")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        when (subType) {
                            "text_delta" -> {
                                val d = ame.get("delta")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                if (d.isNotEmpty()) listOf(AgentSseEvent(type = "delta", delta = d)) else emptyList()
                            }
                            "text_end" -> {
                                val full = ame.get("content")?.takeIf { !it.isJsonNull }?.asString
                                // isStreaming 必须继续为 true：text_end 只代表「这一段正文讲完了」，
                                // 后面往往还有工具调用 / 下一段正文，整个回合的开始与结束由
                                // agent_end + /state 巡检判定。之前这里是 null（被上层当成 false），
                                // 会让流式指标在回合中途被摘掉，速度显示重新从 0 起跳。
                                if (!full.isNullOrEmpty()) {
                                    listOf(AgentSseEvent(type = "text_replace", content = full, isStreaming = true))
                                } else emptyList()
                            }
                            "thinking_delta" -> {
                                val d = ame.get("delta")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                if (d.isNotEmpty()) listOf(AgentSseEvent(type = "thinking", thinking = d)) else emptyList()
                            }
                            "thinking_end" -> {
                                val full = ame.get("content")?.takeIf { !it.isJsonNull }?.asString
                                if (!full.isNullOrEmpty()) listOf(AgentSseEvent(type = "thinking_replace", thinking = full)) else emptyList()
                            }
                            "toolcall_start" -> {
                                val tcId = ame.get("id")?.takeIf { !it.isJsonNull }?.asString
                                val tName = ame.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
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
                                val tcObj = ame.get("toolCall")?.takeIf { it.isJsonObject }?.asJsonObject
                                val tcId = tcObj?.get("id")?.takeIf { !it.isJsonNull }?.asString
                                val tName = tcObj?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                                val argsElem = tcObj?.get("arguments")
                                val preview = extractPreview(argsElem)
                                val cmdStr = argsElem?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                                val pathMedia = MediaAttachmentParsing.fromToolArgs(tName, preview, argsElem)
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
                    val msgObj = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (msgObj != null && msgObj.get("role")?.asString == "assistant") {
                        parseAssistantMessageSnapshot(msgObj, isFinal = true)
                    } else {
                        listOf(AgentSseEvent(type = "message_end_other", isStreaming = true))
                    }
                }

                "tool_execution_start" -> {
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    val argsElem = obj.get("args") ?: obj.get("arguments") ?: obj.get("input")
                    val preview = extractPreview(argsElem)
                    val cmdStr = argsElem?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                    val pathMedia = MediaAttachmentParsing.fromToolArgs(tName, preview, argsElem)
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
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    val partial = obj.get("partialResult")?.takeIf { it.isJsonObject }?.asJsonObject
                    val contentElem = partial?.get("content")
                    val outText = extractContentText(contentElem)
                    val media = extractMediaAttachments(contentElem, outText)
                    val isErr = partial?.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
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
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    val resObj = obj.get("result")?.takeIf { it.isJsonObject }?.asJsonObject
                    val contentElem = resObj?.get("content")
                    val outText = extractContentText(contentElem)
                    val media = extractMediaAttachments(contentElem, outText)
                    val isErr = obj.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean
                        ?: resObj?.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean
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

                "agent_end", "agent_settled", "prompt_done", "agent_idle" -> {
                    val isAborted = (obj.get("aborted")?.takeIf { !it.isJsonNull }?.asBoolean == true) ||
                        (obj.get("status")?.takeIf { !it.isJsonNull }?.asString.equals("aborted", ignoreCase = true))
                    listOf(AgentSseEvent(type = "agent_end", done = true, isStreaming = false, aborted = isAborted))
                }

                "turn_completed", "done", "message_stop" -> {
                    // 单条消息流结束，不代表整个多步会话结束
                    listOf(AgentSseEvent(type = "message_stop", isStreaming = false))
                }

                else -> {
                    // 兼容简化格式 (delta / thinking / tool)
                    val deltaVal = obj.get("delta")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("text")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("content")?.takeIf { it.isJsonPrimitive }?.asString
                    val thinkingVal = obj.get("thinking")?.takeIf { !it.isJsonNull }?.asString
                    val toolVal = obj.get("tool")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString
                    val cmdVal = obj.get("command")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("input")?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                    val outVal = obj.get("output")?.takeIf { !it.isJsonNull }?.asString
                    val durVal = obj.get("durationMs")?.takeIf { !it.isJsonNull }?.asLong

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
            listOf(AgentSseEvent(type = eventTypeHeader ?: "delta", delta = rawData))
        }
    }

    private fun parseAssistantMessageSnapshot(msgObj: JsonObject, isFinal: Boolean): List<AgentSseEvent> {
        val events = mutableListOf<AgentSseEvent>()
        val modelName = msgObj.get("model")?.takeIf { !it.isJsonNull }?.asString
        var usageSummary: String? = null
        if (msgObj.has("usage") && msgObj.get("usage").isJsonObject) {
            val uObj = msgObj.getAsJsonObject("usage")
            val uIn = uObj.get("input")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val uOut = uObj.get("output")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val uCacheR = uObj.get("cacheRead")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val uCacheW = uObj.get("cacheWrite")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
            val uCost = if (uObj.has("cost") && uObj.get("cost").isJsonObject) {
                uObj.getAsJsonObject("cost").get("total")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
            } else 0.0
            usageSummary = TokenStats.formatTurnUsage(uIn, uOut, uCacheR, uCacheW, uCost)
        }

        val stopReason = msgObj.get("stopReason")?.takeIf { !it.isJsonNull }?.asString
        val hasToolCalls = stopReason == "toolUse" || stopReason == "tool_calls" ||
            (msgObj.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.any {
                it.isJsonObject && it.asJsonObject.get("type")?.takeIf { !it.isJsonNull }?.asString == "toolCall"
            } == true)

        if (!isFinal) {
            // 重连时 pi-web 会下发「当前正在生成的 message 快照」（message_start）。
            // 对齐 pi-web 客户端：快照 = 整条消息覆盖（`dispatch({type:"snapshot"})`），
            // 因此即使正文为空也要上抛，让上层先把在途消息身份记下来。
            val sb = StringBuilder()
            msgObj.get("content")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { item ->
                if (item.isJsonObject) {
                    val o = item.asJsonObject
                    val t = o.get("type")?.takeIf { !it.isJsonNull }?.asString
                    if (t == "text" || t == "output_text") {
                        val str = o.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        if (str.isNotEmpty()) {
                            if (sb.isNotEmpty()) sb.append('\n')
                            sb.append(str)
                        }
                    }
                }
            }
            return listOf(
                AgentSseEvent(
                    type = "message_snapshot",
                    content = sb.toString(),
                    isStreaming = true,
                    modelName = modelName,
                    messageId = assistantMessageId(msgObj)
                )
            )
        }

        events.add(
            AgentSseEvent(
                type = "message_end_sync",
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
            val v = msgObj.get(key)?.takeIf { !it.isJsonNull }
            if (v != null && v.isJsonPrimitive) {
                val s = v.asString.trim()
                if (s.isNotEmpty()) return s
            }
        }
        val ts = msgObj.get("timestamp")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
        return ts?.asString?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun extractPreview(element: JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString.replace('\n', ' ').trim().take(120)
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        for (k in listOf("command", "path", "file_path", "filePath", "pattern", "query", "url")) {
            if (obj.has(k) && !obj.get(k).isJsonNull) {
                val v = obj.get(k)
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
                if (item.isJsonObject && item.asJsonObject.has("text") && !item.asJsonObject.get("text").isJsonNull) {
                    sb.append(item.asJsonObject.get("text").asString).append("\n")
                }
            }
            return sb.toString().trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun extractMediaAttachments(contentElem: JsonElement?, outputText: String? = null): List<MediaAttachment> {
        val list = mutableListOf<MediaAttachment>()
        if (contentElem != null && contentElem.isJsonArray) {
            for (item in contentElem.asJsonArray) {
                if (!item.isJsonObject) continue
                val obj = item.asJsonObject
                val type = obj.get("type")?.takeIf { !it.isJsonNull }?.asString ?: ""
                if (type == "image" || type == "image_url" || type == "document" || obj.has("data") || obj.has("source")) {
                    val srcObj = obj.get("source")?.takeIf { it.isJsonObject }?.asJsonObject
                    val mime = obj.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("mediaType")?.takeIf { !it.isJsonNull }?.asString
                        ?: srcObj?.get("media_type")?.takeIf { !it.isJsonNull }?.asString
                        ?: srcObj?.get("mimeType")?.takeIf { !it.isJsonNull }?.asString
                        ?: "image/png"
                    val b64 = obj.get("data")?.takeIf { !it.isJsonNull }?.asString
                        ?: srcObj?.get("data")?.takeIf { !it.isJsonNull }?.asString
                    val urlVal = obj.get("url")?.takeIf { !it.isJsonNull }?.asString
                        ?: obj.get("image_url")?.takeIf { it.isJsonObject }?.asJsonObject?.get("url")?.takeIf { !it.isJsonNull }?.asString
                    val kind = when {
                        mime.contains("pdf", ignoreCase = true) -> MediaKind.PDF
                        mime.contains("svg", ignoreCase = true) -> MediaKind.SVG
                        else -> MediaKind.IMAGE
                    }
                    if (!b64.isNullOrBlank() || !urlVal.isNullOrBlank()) {
                        list.add(
                            MediaAttachment(
                                kind = kind,
                                mimeType = mime,
                                dataBase64 = b64?.substringAfter("base64,", b64),
                                url = urlVal
                            )
                        )
                    }
                }
            }
        }
        MediaAttachmentParsing.firstInlineSvg(outputText)?.let { list.add(it) }
        return list
    }

}
