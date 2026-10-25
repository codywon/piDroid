package com.pi.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Pi 历史会话模型
 */
data class Session(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("firstMessage") val firstMessage: String? = null,
    @SerializedName("cwd") val cwd: String? = null,
    @SerializedName("project") val project: String? = null,
    @SerializedName("created") val created: String? = null,
    @SerializedName("modified") val modified: String? = null,
    @SerializedName("messageCount") val messageCount: Int = 0,
    @SerializedName("tokenUsage") val tokenUsage: Long = 0L,
    @SerializedName("cost") val cost: Double = 0.0,
    @SerializedName("active") val active: Boolean = false
) {
    val displayTitle: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: firstMessage?.take(30)
            ?: "任务: ${id.take(8)}"
}

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
 * 真实 pi-web 模型信息
 */
data class ModelInfo(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("provider") val provider: String,
    @SerializedName("contextWindow") val contextWindow: Long = 200000L,
    @SerializedName("maxTokens") val maxTokens: Long = 8192L,
    @SerializedName("supportsThinking") val supportsThinking: Boolean = true,
    @SerializedName("supportsVision") val supportsVision: Boolean = false,
    @SerializedName("input") val input: List<String> = emptyList(),
    @SerializedName("isActive") val isActive: Boolean = false
) {
    val displayName: String
        get() = name.ifBlank { id }

    val hasVision: Boolean
        get() = supportsVision || input.contains("image")
}

/**
 * 思考深度级别（与 pi-web 官方档位完全一致）
 */
enum class ThinkingLevel(val value: String, val label: String, val budgetTokens: Int) {
    OFF("off", "关闭", 0),
    MINIMAL("minimal", "微弱 (512)", 512),
    LOW("low", "轻度 (1k)", 1024),
    MEDIUM("medium", "适度 (4k)", 4096),
    HIGH("high", "深度 (16k)", 16384),
    XHIGH("xhigh", "超深 (32k)", 32768),
    MAX("max", "极限 (64k)", 65536);

    companion object {
        fun fromValue(v: String?): ThinkingLevel {
            return entries.firstOrNull { it.value.equals(v, ignoreCase = true) } ?: HIGH
        }
    }
}

/**
 * 排队中的消息
 */
data class QueuedMessage(
    val kind: String, // "steer" or "followUp"
    val text: String,
    val images: List<String> = emptyList()
)

/**
 * 技能 (Skills) - 对应 pi-web GET /api/skills
 */
data class SkillInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("alias") var alias: String? = null, // 用户别名
    @SerializedName("filePath") val filePath: String? = null,
    @SerializedName("baseDir") val baseDir: String? = null,
    @SerializedName("enabled") var enabled: Boolean = true,
    @SerializedName("category") val category: String? = "全局技能",
    @SerializedName("disableModelInvocation") val disableModelInvocation: Boolean = false
) {
    val displayTitle: String
        get() = alias?.takeIf { it.isNotBlank() } ?: name
}

/**
 * 插件扩展 (Plugins) - 对应 pi-web GET /api/plugins
 */
data class PluginPackage(
    @SerializedName("packageName") val packageName: String,
    @SerializedName("version") val version: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("source") val source: String? = null,
    @SerializedName("scope") val scope: String? = "global",
    @SerializedName("status") val status: String? = "loaded",
    @SerializedName("disabled") val disabled: Boolean = false,
    @SerializedName("counts") val counts: PluginCounts? = null
)

data class PluginCounts(
    @SerializedName("extensions") val extensions: Int = 0,
    @SerializedName("skills") val skills: Int = 0,
    @SerializedName("prompts") val prompts: Int = 0,
    @SerializedName("themes") val themes: Int = 0
)

/**
 * MCP (Model Context Protocol)
 */
data class McpServerInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("transport") val transport: String? = "http",
    @SerializedName("url") val url: String? = null,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("toolExposureCount") val toolExposureCount: Int = 0,
    @SerializedName("status") val statusObj: McpServerStatus? = null
) {
    val isConnected: Boolean
        get() = statusObj?.state == "connected" || enabled
}

data class McpServerStatus(
    @SerializedName("state") val state: String? = "connected"
)

data class McpCodeModeConfig(
    val mode: String = "automatic", // automatic, always, off
    val scriptMode: Boolean = true,
    val tokenBudget: Int = 3000
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
    val durationMs: Long = 0L,
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
