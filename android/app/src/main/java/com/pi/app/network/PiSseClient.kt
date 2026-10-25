package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pi.app.data.model.AgentSseEvent
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

            val url = "$baseUrl/api/agent/$sessionId/events?token=$token"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Connection", "keep-alive")
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
                "heartbeat" -> emptyList()

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

                "message_start" -> {
                    // pi-web 在流开始或重连时下发当前正在生成的 message 快照
                    val msgObj = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (msgObj != null && msgObj.get("role")?.asString == "assistant") {
                        parseAssistantMessageSnapshot(msgObj, isFinal = false)
                    } else {
                        emptyList()
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
                                if (!full.isNullOrEmpty()) listOf(AgentSseEvent(type = "text_replace", content = full)) else emptyList()
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
                            "toolcall_end" -> {
                                val tcObj = ame.get("toolCall")?.takeIf { it.isJsonObject }?.asJsonObject
                                val tcId = tcObj?.get("id")?.takeIf { !it.isJsonNull }?.asString
                                val tName = tcObj?.get("name")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                                val argsElem = tcObj?.get("arguments")
                                val preview = extractPreview(argsElem)
                                val cmdStr = argsElem?.let { if (it.isJsonPrimitive) it.asString else gson.toJson(it) }
                                listOf(
                                    AgentSseEvent(
                                        type = "tool_call_ready",
                                        toolCallId = tcId,
                                        toolName = tName,
                                        preview = preview,
                                        command = cmdStr
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
                        emptyList()
                    }
                }

                "tool_execution_start" -> {
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    listOf(
                        AgentSseEvent(
                            type = "tool_execution_start",
                            toolCallId = tcId,
                            toolName = tName,
                            phaseLabel = "正在执行 $tName..."
                        )
                    )
                }

                "tool_execution_update" -> {
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    val partial = obj.get("partialResult")?.takeIf { it.isJsonObject }?.asJsonObject
                    val outText = extractContentText(partial?.get("content"))
                    val isErr = partial?.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    listOf(
                        AgentSseEvent(
                            type = "tool_result",
                            toolCallId = tcId,
                            toolName = tName,
                            output = outText,
                            isError = isErr,
                            phaseLabel = "正在执行 $tName..."
                        )
                    )
                }

                "tool_execution_end" -> {
                    val tcId = obj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    val tName = obj.get("toolName")?.takeIf { !it.isJsonNull }?.asString ?: "tool"
                    listOf(
                        AgentSseEvent(
                            type = "tool_execution_end",
                            toolCallId = tcId,
                            toolName = tName,
                            phaseLabel = "正在等待模型..."
                        )
                    )
                }

                "agent_end", "agent_settled", "prompt_done", "turn_completed", "done" -> {
                    listOf(AgentSseEvent(type = "turn_completed", done = true))
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

        if (isFinal) {
            events.add(
                AgentSseEvent(
                    type = "message_end_sync",
                    modelName = modelName,
                    usageSummary = usageSummary,
                    phaseLabel = "正在等待模型..."
                )
            )
        }
        return events
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
}
