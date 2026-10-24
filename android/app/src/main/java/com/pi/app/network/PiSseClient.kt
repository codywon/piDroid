package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.pi.app.data.model.AgentSseEvent
import com.pi.app.data.storage.AppPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
        .readTimeout(0, TimeUnit.MILLISECONDS) // SSE 保持长连接
        .build()

    /**
     * 监听 Agent 实时流式事件
     */
    fun listenAgentEvents(sessionId: String): Flow<AgentSseEvent> = callbackFlow {
        val baseUrl = prefs.serverUrl
        val token = prefs.authToken
        val url = "$baseUrl/api/agent/$sessionId/events?token=$token"

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer $token")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d("PiSseClient", "SSE Connected for session $sessionId")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val event = gson.fromJson(data, AgentSseEvent::class.java)
                    trySend(event)
                } catch (e: Exception) {
                    // 如果不是 JSON，作为普通 delta 输出
                    trySend(AgentSseEvent(type = type ?: "message", delta = data))
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d("PiSseClient", "SSE Closed")
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.e("PiSseClient", "SSE Error: ${t?.message}, code: ${response?.code}")
                trySend(AgentSseEvent(type = "error", error = t?.message ?: "Connection closed"))
            }
        }

        val factory = EventSources.createFactory(client)
        val eventSource = factory.newEventSource(request, listener)

        awaitClose {
            eventSource.cancel()
        }
    }
}
