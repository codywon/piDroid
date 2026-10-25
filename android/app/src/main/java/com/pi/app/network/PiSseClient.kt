package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.pi.app.data.model.AgentSseEvent
import com.pi.app.data.storage.AppPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 保持长连接无超时
        .pingInterval(10, TimeUnit.SECONDS)   // 发送心跳维持 TCP 活跃
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 监听 Agent 实时流式事件 (带异常断线自愈重连机制)
     */
    fun listenAgentEvents(sessionId: String): Flow<AgentSseEvent> = callbackFlow {
        var eventSource: EventSource? = null
        var isClosedByUser = false

        fun connect() {
            if (isClosedByUser) return
            val baseUrl = prefs.serverUrl
            val token = prefs.authToken
            if (baseUrl.isBlank()) return

            val url = "$baseUrl/api/agent/$sessionId/events?token=$token"
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .header("Authorization", "Bearer $token")
                .build()

            val listener = object : EventSourceListener() {
                override fun onOpen(es: EventSource, response: Response) {
                    Log.d("PiSseClient", "SSE Connected for session $sessionId")
                }

                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    try {
                        val event = gson.fromJson(data, AgentSseEvent::class.java)
                        trySend(event)
                    } catch (e: Exception) {
                        trySend(AgentSseEvent(type = type ?: "message", delta = data))
                    }
                }

                override fun onClosed(es: EventSource) {
                    Log.d("PiSseClient", "SSE Closed")
                    if (!isClosedByUser) {
                        // 尝试重新连接
                        launch {
                            delay(1500)
                            connect()
                        }
                    }
                }

                override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                    Log.w("PiSseClient", "SSE Disconnected: ${t?.message}, retrying in 2s...")
                    if (!isClosedByUser) {
                        launch {
                            delay(2000)
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
            eventSource?.cancel()
        }
    }
}
