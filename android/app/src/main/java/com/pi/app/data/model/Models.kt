package com.pi.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Pi 历史会话模型
 */
data class Session(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("tokenUsage") val tokenUsage: Long = 0L,
    @SerializedName("cost") val cost: Double = 0.0,
    @SerializedName("active") val active: Boolean = false
)

/**
 * 聊天消息项（用户输入、Agent 思考、Agent 回复、工具调用）
 */
data class ChatItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val toolCall: ToolCallInfo? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
)

enum class MessageRole {
    USER,
    AGENT,
    TOOL,
    SYSTEM
}

/**
 * 工具调用与审批卡片数据
 */
data class ToolCallInfo(
    val toolName: String,
    val command: String? = null,
    val output: String? = null,
    val requiresApproval: Boolean = false,
    val isApproved: Boolean = false
)

/**
 * SSE 推送事件结构
 */
data class AgentSseEvent(
    val type: String,
    val delta: String? = null,
    val thinking: String? = null,
    val tool: String? = null,
    val command: String? = null,
    val output: String? = null,
    val error: String? = null,
    val done: Boolean = false
)
