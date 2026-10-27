package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
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
    val isRunning: Boolean = false,
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

    /**
     * 真实运行态（列表徽章、筛选、计数、删除保护一律以此为准）。
     *
     * pi-web 已把「谁在跑」从会话条目里的 `active` 字段搬到 `GET /api/sessions` 顶层的
     * `runningSessionIds`，条目本身不再带 `active`——所以 `PiApiClient.getSessions()` 解析后
     * 只能把它落到 [isRunning]。而本地新建会话（MainActivity 里 `active = true`）与旧缓存
     * 只有 `active` 有值。因此运行态必须两者取或：只读 `active` 会让「运行中」永远为 0、
     * 全部任务都被判成「已完成」。
     */
    val isLive: Boolean
        get() = isRunning || active
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

        fun formatCacheCompact(cacheRead: Long): String {
            if (cacheRead <= 0L) return "0 cache R"
            return "${formatTokenCompact(cacheRead)} cache R"
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
    @SerializedName("isActive") val isActive: Boolean = false,
    @SerializedName("baseUrl") val baseUrl: String? = null,
    @SerializedName("apiType") val apiType: String? = null
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
    @SerializedName("disableModelInvocation") val disableModelInvocation: Boolean = false,
    @SerializedName("content") val content: String? = null
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
    @SerializedName("status") val statusObj: McpServerStatus? = null,
    @SerializedName("command") val command: String? = null,
    @SerializedName("args") val args: List<String>? = null,
    @SerializedName("env") val env: Map<String, String>? = null,
    @SerializedName("scope") val scope: String? = "global"
) {
    val isConnected: Boolean
        get() = statusObj?.state == "connected" || enabled
}

data class McpTestToolResult(
    val success: Boolean,
    val message: String,
    val toolCount: Int = 0,
    val tools: List<String> = emptyList()
)

data class McpServerStatus(
    @SerializedName("state") val state: String? = "connected"
)

data class McpCodeModeConfig(
    val mode: String = "automatic", // automatic, always, off
    val scriptMode: Boolean = true,
    val tokenBudget: Int = 3000
)

data class RemoteDirEntry(
    val name: String,
    val path: String
)

data class RemoteBrowseData(
    val currentPath: String,
    val parentPath: String?,
    val directories: List<RemoteDirEntry>
)

/**
 * 富媒体附件模型（对标 pi-web ImageContent / SVG / PDF / 脑图预览与大图查看器）
 */
enum class MediaKind {
    IMAGE,
    SVG,
    PDF,
    DIAGRAM
}

data class MediaAttachment(
    val id: String = java.util.UUID.randomUUID().toString(),
    val kind: MediaKind = MediaKind.IMAGE,
    val mimeType: String = "image/png",
    val dataBase64: String? = null,
    val url: String? = null,
    val filePath: String? = null,
    val title: String = "",
    val svgOrCode: String? = null,
    val rawContent: String? = svgOrCode
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
    val mediaList: List<MediaAttachment> = emptyList(),
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
    val mediaList: List<MediaAttachment> = emptyList(),
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
    val mediaList: List<MediaAttachment> = emptyList(),
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

/**
 * 思考等级映射状态 (7 档：off, minimal, low, medium, high, xhigh, max)
 */
enum class ThinkingLevelMode {
    DEFAULT,   // 默认 (不在 map 中，跟随系统默认)
    DISABLED,  // 禁用 (map 中存 false)
    CUSTOM     // 自定义 (map 中存自定义字符串，如 "none", "xhigh", "max")
}

data class ThinkingLevelItem(
    val levelKey: String, // "off", "minimal", "low", "medium", "high", "xhigh", "max"
    val mode: ThinkingLevelMode = ThinkingLevelMode.DEFAULT,
    val customValue: String = ""
)

/**
 * 自定义 Header 键值对
 */
data class HeaderEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val key: String = "",
    val value: String = ""
)

/**
 * 1:1 对标 pi-web 的单个模型配置实体
 */
data class ModelConfigItem(
    val id: String,
    val name: String = "",
    val reasoning: Boolean = false,
    val hasVision: Boolean = false,
    val contextWindow: Long = 200000L,
    val maxTokens: Long = 8192L,
    val costInput: Double = 0.0,
    val costOutput: Double = 0.0,
    val costCacheRead: Double = 0.0,
    val costCacheWrite: Double = 0.0,
    val apiOverride: String = "", // 空代表默认/跟随 Provider
    val headers: List<HeaderEntry> = emptyList(),
    val deepSeekThinkingCompat: Boolean = false,
    val developerRole: Boolean = false,
    val thinkingLevel: String = "",
    val thinkingLevelMap: List<ThinkingLevelItem> = defaultThinkingLevelMap()
) {
    companion object {
        fun defaultThinkingLevelMap(): List<ThinkingLevelItem> = listOf(
            ThinkingLevelItem("off"),
            ThinkingLevelItem("minimal"),
            ThinkingLevelItem("low"),
            ThinkingLevelItem("medium"),
            ThinkingLevelItem("high"),
            ThinkingLevelItem("xhigh"),
            ThinkingLevelItem("max")
        )
    }
}

/**
 * 1:1 对标 pi-web 的 Provider 配置实体
 */
data class ProviderConfigItem(
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val api: String = "openai-completions",
    val headers: List<HeaderEntry> = emptyList(),
    val models: List<ModelConfigItem> = emptyList()
)

/**
 * models-config 与 JSON 双向解析转换器 (纯正对标 pi-web models.json 规范)
 */
object ModelConfigConverter {

    private val STANDARD_LEVELS = listOf("off", "minimal", "low", "medium", "high", "xhigh", "max")

    fun parseProvidersConfig(rootObj: JsonObject): List<ProviderConfigItem> {
        val providersObj = if (rootObj.has("providers") && rootObj.get("providers").isJsonObject) {
            rootObj.getAsJsonObject("providers")
        } else {
            rootObj
        }

        val resultList = mutableListOf<ProviderConfigItem>()
        for (providerKey in providersObj.keySet()) {
            val pElement = providersObj.get(providerKey)
            if (!pElement.isJsonObject) continue
            val pObj = pElement.asJsonObject

            val baseUrl = pObj.get("baseUrl")?.asString ?: ""
            val apiKey = pObj.get("apiKey")?.asString ?: ""
            val api = pObj.get("api")?.asString ?: "openai-completions"

            // 解析 Provider 级别的 headers
            val pHeaders = mutableListOf<HeaderEntry>()
            if (pObj.has("headers") && pObj.get("headers").isJsonObject) {
                val hObj = pObj.getAsJsonObject("headers")
                for (hKey in hObj.keySet()) {
                    pHeaders.add(HeaderEntry(key = hKey, value = hObj.get(hKey)?.asString ?: ""))
                }
            }

            // 解析 Provider 下属的 models
            val modelsList = mutableListOf<ModelConfigItem>()
            if (pObj.has("models") && pObj.get("models").isJsonArray) {
                val modelsArr = pObj.getAsJsonArray("models")
                for (mElement in modelsArr) {
                    if (!mElement.isJsonObject) continue
                    val mObj = mElement.asJsonObject
                    val mId = mObj.get("id")?.asString?.trim() ?: continue
                    if (mId.isEmpty()) continue

                    val mName = mObj.get("name")?.asString ?: ""
                    val reasoning = mObj.get("reasoning")?.asBoolean ?: false

                    val hasVision = if (mObj.has("input") && mObj.get("input").isJsonArray) {
                        mObj.getAsJsonArray("input").any { it.isJsonPrimitive && it.asString == "image" }
                    } else false

                    val contextWindow = mObj.get("contextWindow")?.asLong ?: 200000L
                    val maxTokens = mObj.get("maxTokens")?.asLong ?: 8192L

                    // 价格
                    var costInput = 0.0
                    var costOutput = 0.0
                    var costCacheRead = 0.0
                    var costCacheWrite = 0.0
                    if (mObj.has("cost") && mObj.get("cost").isJsonObject) {
                        val costObj = mObj.getAsJsonObject("cost")
                        costInput = costObj.get("input")?.asDouble ?: 0.0
                        costOutput = costObj.get("output")?.asDouble ?: 0.0
                        costCacheRead = costObj.get("cacheRead")?.asDouble ?: 0.0
                        costCacheWrite = costObj.get("cacheWrite")?.asDouble ?: 0.0
                    }

                    // api override
                    val apiOverride = mObj.get("api")?.asString ?: ""

                    // headers
                    val mHeaders = mutableListOf<HeaderEntry>()
                    if (mObj.has("headers") && mObj.get("headers").isJsonObject) {
                        val hObj = mObj.getAsJsonObject("headers")
                        for (hKey in hObj.keySet()) {
                            mHeaders.add(HeaderEntry(key = hKey, value = hObj.get(hKey)?.asString ?: ""))
                        }
                    }

                    // 兼容性
                    var deepSeekThinking = false
                    var developerRole = false
                    if (mObj.has("compat") && mObj.get("compat").isJsonObject) {
                        val compatObj = mObj.getAsJsonObject("compat")
                        deepSeekThinking = compatObj.get("supportsThinking")?.asBoolean ?: false
                        developerRole = compatObj.get("developerRole")?.asBoolean ?: false
                    }

                    val thinkingLevel = mObj.get("thinkingLevel")?.asString ?: ""

                    // 思考等级映射
                    val levelMapItems = mutableListOf<ThinkingLevelItem>()
                    val rawLevelMap = if (mObj.has("thinkingLevelMap") && mObj.get("thinkingLevelMap").isJsonObject) {
                        mObj.getAsJsonObject("thinkingLevelMap")
                    } else null

                    for (lvl in STANDARD_LEVELS) {
                        if (rawLevelMap != null && rawLevelMap.has(lvl)) {
                            val elem = rawLevelMap.get(lvl)
                            if (elem.isJsonPrimitive) {
                                val prim = elem.asJsonPrimitive
                                if (prim.isBoolean && !prim.asBoolean) {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DISABLED, ""))
                                } else if (prim.isString) {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.CUSTOM, prim.asString))
                                } else {
                                    levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                                }
                            } else {
                                levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                            }
                        } else {
                            levelMapItems.add(ThinkingLevelItem(lvl, ThinkingLevelMode.DEFAULT, ""))
                        }
                    }

                    modelsList.add(
                        ModelConfigItem(
                            id = mId,
                            name = mName,
                            reasoning = reasoning,
                            hasVision = hasVision,
                            contextWindow = contextWindow,
                            maxTokens = maxTokens,
                            costInput = costInput,
                            costOutput = costOutput,
                            costCacheRead = costCacheRead,
                            costCacheWrite = costCacheWrite,
                            apiOverride = apiOverride,
                            headers = mHeaders,
                            deepSeekThinkingCompat = deepSeekThinking,
                            developerRole = developerRole,
                            thinkingLevel = thinkingLevel,
                            thinkingLevelMap = levelMapItems
                        )
                    )
                }
            }

            resultList.add(
                ProviderConfigItem(
                    name = providerKey,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                    api = api,
                    headers = pHeaders,
                    models = modelsList
                )
            )
        }
        return resultList
    }

    fun serializeProvidersConfig(providers: List<ProviderConfigItem>, existingRoot: JsonObject? = null): JsonObject {
        val root = existingRoot?.deepCopy() ?: JsonObject()
        val providersObj = JsonObject()

        for (provider in providers) {
            val pKey = provider.name.trim()
            if (pKey.isEmpty()) continue

            val pObj = JsonObject()
            if (provider.baseUrl.isNotBlank()) pObj.addProperty("baseUrl", provider.baseUrl.trim())
            if (provider.apiKey.isNotBlank()) pObj.addProperty("apiKey", provider.apiKey.trim())
            if (provider.api.isNotBlank()) pObj.addProperty("api", provider.api.trim())

            // Provider 级别 headers
            val validPHeaders = provider.headers.filter { it.key.isNotBlank() }
            if (validPHeaders.isNotEmpty()) {
                val hObj = JsonObject()
                for (h in validPHeaders) {
                    hObj.addProperty(h.key.trim(), h.value)
                }
                pObj.add("headers", hObj)
            }

            // models
            val modelsArr = JsonArray()
            for (m in provider.models) {
                val mId = m.id.trim()
                if (mId.isEmpty()) continue

                val mObj = JsonObject()
                mObj.addProperty("id", mId)
                if (m.name.isNotBlank()) mObj.addProperty("name", m.name.trim())
                if (m.reasoning) mObj.addProperty("reasoning", true)

                // input
                val inputArr = JsonArray()
                inputArr.add("text")
                if (m.hasVision) {
                    inputArr.add("image")
                }
                mObj.add("input", inputArr)

                // contextWindow & maxTokens 真实写入！
                mObj.addProperty("contextWindow", m.contextWindow)
                mObj.addProperty("maxTokens", m.maxTokens)

                // 价格 (cost)
                val costObj = JsonObject().apply {
                    addProperty("input", m.costInput)
                    addProperty("output", m.costOutput)
                    addProperty("cacheRead", m.costCacheRead)
                    addProperty("cacheWrite", m.costCacheWrite)
                }
                mObj.add("cost", costObj)

                // api override
                if (m.apiOverride.isNotBlank() && m.apiOverride != "none" && m.apiOverride != "— 默认 / none —") {
                    mObj.addProperty("api", m.apiOverride.trim())
                }

                // headers
                val validMHeaders = m.headers.filter { it.key.isNotBlank() }
                if (validMHeaders.isNotEmpty()) {
                    val hObj = JsonObject()
                    for (h in validMHeaders) {
                        hObj.addProperty(h.key.trim(), h.value)
                    }
                    mObj.add("headers", hObj)
                }

                // compat
                val compatObj = JsonObject()
                if (m.deepSeekThinkingCompat) compatObj.addProperty("supportsThinking", true)
                if (m.developerRole) compatObj.addProperty("developerRole", true)
                if (compatObj.size() > 0) {
                    mObj.add("compat", compatObj)
                }

                if (m.thinkingLevel.isNotBlank()) {
                    mObj.addProperty("thinkingLevel", m.thinkingLevel.trim())
                }

                // thinkingLevelMap
                val levelMapObj = JsonObject()
                for (item in m.thinkingLevelMap) {
                    when (item.mode) {
                        ThinkingLevelMode.DISABLED -> levelMapObj.addProperty(item.levelKey, false)
                        ThinkingLevelMode.CUSTOM -> {
                            if (item.customValue.isNotBlank()) {
                                levelMapObj.addProperty(item.levelKey, item.customValue.trim())
                            }
                        }
                        ThinkingLevelMode.DEFAULT -> {} // 不写入
                    }
                }
                if (levelMapObj.size() > 0) {
                    mObj.add("thinkingLevelMap", levelMapObj)
                }

                modelsArr.add(mObj)
            }

            pObj.add("models", modelsArr)
            providersObj.add(pKey, pObj)
        }

        root.add("providers", providersObj)
        // 移除可能造成破坏的顶层残留
        root.remove("config")
        return root
    }
}
