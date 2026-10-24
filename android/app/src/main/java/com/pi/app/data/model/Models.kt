package com.pi.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Pi 历史会话模型
 */
data class Session(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String? = null,
    @SerializedName("project") val project: String? = null,
    @SerializedName("createdAt") val createdAt: Long = System.currentTimeMillis(),
    @SerializedName("updatedAt") val updatedAt: Long = System.currentTimeMillis(),
    @SerializedName("tokenUsage") val tokenUsage: Long = 0L,
    @SerializedName("cost") val cost: Double = 0.0,
    @SerializedName("active") val active: Boolean = false
)

/**
 * 详细 Token 与上下文用量、缓存率监控
 */
data class TokenStats(
    val contextTokens: Long = 0L,
    val maxContextWindow: Long = 200000L,
    val inputTokens: Long = 0L,
    val outputTokens: Long = 0L,
    val cacheReadTokens: Long = 0L,
    val cacheWriteTokens: Long = 0L,
    val cost: Double = 0.0
) {
    val contextUsagePercent: Float
        get() = if (maxContextWindow > 0) (contextTokens.toFloat() / maxContextWindow).coerceIn(0f, 1f) else 0f

    val cacheHitRate: Float
        get() {
            val totalPrompt = inputTokens + cacheReadTokens
            return if (totalPrompt > 0) (cacheReadTokens.toFloat() / totalPrompt) * 100f else 0f
        }
}

/**
 * 模型信息
 */
data class ModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("contextWindow") val contextWindow: Long = 200000L,
    @SerializedName("supportsThinking") val supportsThinking: Boolean = true,
    @SerializedName("isActive") val isActive: Boolean = false
)

/**
 * 思考深度级别
 */
enum class ThinkingLevel(val label: String, val budgetTokens: Int) {
    OFF("关闭思考", 0),
    LOW("轻度思考 (1k)", 1024),
    MEDIUM("适度思考 (4k)", 4096),
    HIGH("深度推理 (16k)", 16384)
}

/**
 * 技能 (Skills) 描述
 */
data class SkillInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("enabled") var enabled: Boolean = true,
    @SerializedName("category") val category: String? = "内置技能"
)

/**
 * MCP (Model Context Protocol) 服务
 */
data class McpServerInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("status") val status: String = "active", // active, connecting, error
    @SerializedName("tools") val tools: List<String> = emptyList()
)

/**
 * 项目 / 工作区
 */
data class ProjectInfo(
    @SerializedName("name") val name: String,
    @SerializedName("path") val path: String,
    @SerializedName("isCurrent") val isCurrent: Boolean = false
)

/**
 * 聊天消息项
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
    val done: Boolean = false,
    val tokenStats: TokenStats? = null
)
