package com.pi.app.data.model

import com.google.gson.annotations.SerializedName
import java.text.NumberFormat
import java.util.Locale

/**
 * 工作区模型（按项目根目录划分，对标 pi-web SessionTree GroupHeader + ActivitySummary）
 */
data class WorkspaceInfo(
    val path: String,
    val name: String,
    val sessionCount: Int = 0,
    val runningCount: Int = 0,
    val awaitingCount: Int = 0,
    val isCurrent: Boolean = false
) {
    val displayName: String
        get() = name.ifBlank { path.replace('\\', '/').substringAfterLast('/') }
}

data class ProjectInfo(
    val name: String,
    val path: String,
    val isCurrent: Boolean = false
)

/**
 * Pi 历史任务会话模型（对标 pi-web SessionInfo & Runtime 状态）
 */
data class Session(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("firstMessage") val firstMessage: String? = null,
    @SerializedName("cwd") val cwd: String? = null,
    @SerializedName("project") val project: String? = null,
    @SerializedName("projectRoot") val projectRoot: String? = null,
    @SerializedName("projectKey") val projectKey: String? = null,
    @SerializedName("branch") val branch: String? = null,
    @SerializedName("created") val created: String? = null,
    @SerializedName("modified") val modified: String? = null,
    @SerializedName("messageCount") val messageCount: Int = 0,
    @SerializedName("tokenUsage") val tokenUsage: Long = 0L,
    @SerializedName("cost") val cost: Double = 0.0,
    @SerializedName("active") val active: Boolean = false,
    val awaitingInput: Boolean = false
) {
    val displayTitle: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: firstMessage?.replace('\n', ' ')?.trim()?.take(42)
            ?: "任务: ${id.take(8)}"

    val workspacePath: String
        get() {
            val raw = projectRoot?.takeIf { it.isNotBlank() }
                ?: cwd?.takeIf { it.isNotBlank() }
                ?: projectKey?.takeIf { it.isNotBlank() }
                ?: "默认工作区"
            return raw.replace('\\', '/').trimEnd('/')
        }

    val workspaceName: String
        get() {
            val p = workspacePath
            return p.substringAfterLast('\\').substringAfterLast('/').ifBlank { p }
        }
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

    companion object {
        fun formatTurnUsage(
            input: Long,
            output: Long,
            cacheRead: Long = 0L,
            cacheWrite: Long = 0L,
            cost: Double = 0.0
        ): String? {
            val nf = NumberFormat.getIntegerInstance(Locale.US)
            val parts = mutableListOf<String>()
            if (input > 0) parts.add("${nf.format(input)} in")
            if (output > 0) parts.add("${nf.format(output)} out")
            if (cacheRead > 0) parts.add("${nf.format(cacheRead)} cache R")
            if (cacheWrite > 0) parts.add("${nf.format(cacheWrite)} cache W")
            if (cost >= 0.0001) parts.add(String.format(Locale.US, "$%.4f", cost))
            return if (parts.isEmpty()) null else parts.joinToString(" · ")
        }

        fun formatTokenStandard(tokens: Long): String {
            if (tokens <= 0L) return "0"
            return when {
                tokens >= 1_000_000_000_000_000L -> String.format(Locale.US, "%.1f PB", tokens / 1_000_000_000_000_000.0).replace(".0 PB", " PB")
                tokens >= 1_000_000_000_000L -> String.format(Locale.US, "%.1f TB", tokens / 1_000_000_000_000.0).replace(".0 TB", " TB")
                tokens >= 1_000_000_000L -> String.format(Locale.US, "%.1f B", tokens / 1_000_000_000.0).replace(".0 B", " B")
                tokens >= 1_000_000L -> String.format(Locale.US, "%.1f M", tokens / 1_000_000.0).replace(".0 M", " M")
                tokens >= 1_000L -> String.format(Locale.US, "%.1f K", tokens / 1000.0).replace(".0 K", " K")
                else -> tokens.toString()
            }
        }

        fun formatTokenCompact(tokens: Long): String {
            if (tokens <= 0L) return "0"
            return when {
                tokens >= 1_000_000_000_000_000L -> String.format(Locale.US, "%.1fP", tokens / 1_000_000_000_000_000.0).replace(".0P", "P")
                tokens >= 1_000_000_000_000L -> String.format(Locale.US, "%.1fT", tokens / 1_000_000_000_000.0).replace(".0T", "T")
                tokens >= 1_000_000_000L -> String.format(Locale.US, "%.1fB", tokens / 1_000_000_000.0).replace(".0B", "B")
                tokens >= 1_000_000L -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0).replace(".0M", "M")
                tokens >= 10_000L -> "${tokens / 1000}K"
                tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1000.0).replace(".0K", "K")
                else -> tokens.toString()
            }
        }
    }
}

/**
 * 真实 pi-web 动态模型信息
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
 * 思考深度级别（7档官方规格）
 */
enum class ThinkingLevel(val value: String, val label: String, val budgetTokens: Int) {
    OFF("off", "关闭思考", 0),
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
 * 技能 (Skills)
 */
data class SkillInfo(
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String? = null,
    @SerializedName("alias") var alias: String? = null, // 用户自定义别名
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
 * 插件扩展 (Plugins)
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
 * MCP 协议
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
 * 聊天消息项（对标 pi-web MessageView）
 */
data class ChatItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val thinkingDurationSec: Int? = null,
    val toolCall: ToolCallInfo? = null,
    val modelName: String? = null,
    val usageSummary: String? = null,
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
 * 工具调用与审批卡片数据（对标 pi-web ToolCallBlock）
 */
data class ToolCallInfo(
    val toolCallId: String? = null,
    val toolName: String,
    val preview: String? = null,
    val command: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    val output: String? = null,
    val durationMs: Long = 0L,
    val isError: Boolean = false,
    val isRunning: Boolean = false,
    val requiresApproval: Boolean = false,
    val isApproved: Boolean = false
) {
    val formattedDuration: String?
        get() {
            if (durationMs < 100L) return null
            val seconds = durationMs / 1000.0
            if (seconds < 60.0) return String.format(Locale.US, "%.1fs", seconds)
            val totalSeconds = seconds.toLong()
            val minutes = totalSeconds / 60L
            val rem = totalSeconds % 60L
            if (minutes < 60L) return "${minutes}m ${rem}s"
            return "${minutes / 60L}h ${minutes % 60L}m ${rem}s"
        }
}

/**
 * SSE 实时流连接状态
 */
enum class SseConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING
}

/**
 * SSE 推送事件结构（兼容 pi-web 全量事件流）
 */
data class AgentSseEvent(
    val type: String,
    val delta: String? = null,
    val text: String? = null,
    val content: String? = null,
    val thinking: String? = null,
    val tool: String? = null,
    val toolName: String? = null,
    val toolCallId: String? = null,
    val command: String? = null,
    val input: String? = null,
    val preview: String? = null,
    val output: String? = null,
    val durationMs: Long? = null,
    val isError: Boolean = false,
    val error: String? = null,
    val done: Boolean = false,
    val isStreaming: Boolean? = null,
    val phaseLabel: String? = null,
    val modelName: String? = null,
    val usageSummary: String? = null,
    val tokenStats: TokenStats? = null,
    val connectionState: String? = null
) {
    val effectiveText: String
        get() = delta ?: text ?: content ?: ""

    val effectiveToolName: String
        get() = tool ?: toolName ?: "bash"

    val effectiveCommand: String?
        get() = command ?: input
}
