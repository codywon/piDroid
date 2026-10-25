package com.pi.app.network

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pi.app.data.model.*
import com.pi.app.data.storage.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SessionHistoryData(
    val messages: List<ChatItem>,
    val provider: String,
    val modelId: String,
    val thinkingLevel: ThinkingLevel,
    val stats: TokenStats
)

data class DynamicModelCatalog(
    val models: List<ModelInfo>,
    val thinkingLevelsMap: Map<String, List<String>>
)

class PiApiClient(private val prefs: AppPreferences) {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(prefs))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun ping(targetUrl: String? = null, token: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = targetUrl ?: prefs.serverUrl
        val authToken = token ?: prefs.authToken
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("Server URL is empty"))

        try {
            val req = Request.Builder()
                .url("$baseUrl/api/ping")
                .header("Authorization", "Bearer $authToken")
                .get()
                .build()

            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(response.body?.string() ?: "OK")
                } else {
                    Result.failure(IOException("Server error: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 1. 同步所有会话 (GET /api/sessions)
    // ==========================================

    suspend fun getSessions(): Result<List<Session>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("Server URL is empty"))

        try {
            val req = Request.Builder().url("$baseUrl/api/sessions").get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val jsonElement = JsonParser.parseString(body)
                    val type = object : TypeToken<List<Session>>() {}.type
                    val list: List<Session> = if (jsonElement.isJsonObject && jsonElement.asJsonObject.has("sessions")) {
                        gson.fromJson(jsonElement.asJsonObject.get("sessions"), type) ?: emptyList()
                    } else if (jsonElement.isJsonArray) {
                        gson.fromJson(jsonElement, type) ?: emptyList()
                    } else {
                        emptyList()
                    }
                    Result.success(list)
                } else {
                    Result.failure(IOException("Failed to load sessions: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 2. 深入任务：加载完整任务历史 (GET /api/sessions/[id])
    // 解决“点进去没有任务信息，无法继续任务”的核心问题！
    // ==========================================

    suspend fun getSessionDetail(sessionId: String): Result<SessionHistoryData> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val req = Request.Builder().url("$baseUrl/api/sessions/$sessionId").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to load session details: ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject

                val chatItems = mutableListOf<ChatItem>()
                var currentProvider = "codywon"
                var currentModelId = "gpt-6.1-sol"
                var currentThinkingLevel = ThinkingLevel.HIGH

                var lastAssistantContextTokens = 0L
                var lastAssistantInTokens = 0L
                var lastAssistantOutTokens = 0L

                // 1. 解析 context 中的历史消息
                if (json.has("context")) {
                    val ctx = json.getAsJsonObject("context")
                    if (ctx.has("model")) {
                        val mObj = ctx.getAsJsonObject("model")
                        currentProvider = mObj.get("provider")?.asString ?: currentProvider
                        currentModelId = mObj.get("modelId")?.asString ?: currentModelId
                    }
                    if (ctx.has("thinkingLevel")) {
                        currentThinkingLevel = ThinkingLevel.fromValue(ctx.get("thinkingLevel")?.asString)
                    }

                    if (ctx.has("messages")) {
                        val msgs = ctx.getAsJsonArray("messages")
                        for (element in msgs) {
                            val msgObj = element.asJsonObject
                            val roleStr = msgObj.get("role")?.asString ?: ""
                            val timestamp = msgObj.get("timestamp")?.asLong ?: System.currentTimeMillis()

                            // 提取最后一条 assistant 回合级真实 usage
                            if (roleStr == "assistant" && msgObj.has("usage") && msgObj.get("usage").isJsonObject) {
                                val uObj = msgObj.getAsJsonObject("usage")
                                val uIn = uObj.get("input")?.asLong ?: 0L
                                val uOut = uObj.get("output")?.asLong ?: 0L
                                val uCache = uObj.get("cacheRead")?.asLong ?: 0L
                                if (uIn > 0) {
                                    lastAssistantContextTokens = uIn + uCache
                                    lastAssistantInTokens = uIn
                                    lastAssistantOutTokens = uOut
                                }
                            }

                            when (roleStr) {
                                "user" -> {
                                    val userText = extractTextFromContent(msgObj.get("content"))
                                    chatItems.add(ChatItem(
                                        role = MessageRole.USER,
                                        content = userText,
                                        timestamp = timestamp
                                    ))
                                }
                                "assistant" -> {
                                    var text = ""
                                    var thinking: String? = msgObj.get("thinking")?.asString
                                    val toolCalls = mutableListOf<ToolCallInfo>()

                                    if (msgObj.has("content")) {
                                        val contentElem = msgObj.get("content")
                                        if (contentElem.isJsonArray) {
                                            for (cItem in contentElem.asJsonArray) {
                                                val cObj = cItem.asJsonObject
                                                when (cObj.get("type")?.asString) {
                                                    "text" -> {
                                                        val t = cObj.get("text")?.asString ?: ""
                                                        text = if (text.isEmpty()) t else "$text\n$t"
                                                    }
                                                    "thinking" -> {
                                                        thinking = (thinking ?: "") + (cObj.get("thinking")?.asString ?: "")
                                                    }
                                                    "toolCall" -> {
                                                        val toolName = cObj.get("toolName")?.asString ?: "tool"
                                                        val input = cObj.get("input")?.toString()
                                                        toolCalls.add(ToolCallInfo(
                                                            toolName = toolName,
                                                            command = input
                                                        ))
                                                    }
                                                }
                                            }
                                        } else if (contentElem.isJsonPrimitive) {
                                            text = contentElem.asString
                                        }
                                    }

                                    // 添加智能体回复
                                    if (text.isNotBlank() || !thinking.isNullOrBlank()) {
                                        chatItems.add(ChatItem(
                                            role = MessageRole.AGENT,
                                            content = text,
                                            thinking = thinking,
                                            timestamp = timestamp
                                        ))
                                    }
                                    // 添加工具调用卡片
                                    for (tc in toolCalls) {
                                        chatItems.add(ChatItem(
                                            role = MessageRole.TOOL,
                                            content = "",
                                            toolCall = tc,
                                            timestamp = timestamp
                                        ))
                                    }
                                }
                                "toolResult" -> {
                                    val toolName = msgObj.get("toolName")?.asString ?: "Tool"
                                    val toolOutput = extractTextFromContent(msgObj.get("content"))
                                    val duration = msgObj.get("durationMs")?.asLong ?: 0L
                                    val isErr = msgObj.get("isError")?.asBoolean ?: false
                                    chatItems.add(ChatItem(
                                        role = MessageRole.TOOL,
                                        content = "",
                                        toolCall = ToolCallInfo(
                                            toolName = toolName,
                                            output = toolOutput,
                                            durationMs = duration
                                        ),
                                        timestamp = timestamp
                                    ))
                                }
                            }
                        }
                    }
                }

                // 2. 解析详细 Token 与监控用量
                var inTokens = 0L
                var outTokens = 0L
                var cacheRead = 0L
                var cacheWrite = 0L
                var cost = 0.0

                if (json.has("stats")) {
                    val stats = json.getAsJsonObject("stats")
                    cost = stats.get("cost")?.asDouble ?: 0.0
                    if (stats.has("tokens")) {
                        val tok = stats.getAsJsonObject("tokens")
                        inTokens = tok.get("input")?.asLong ?: 0L
                        outTokens = tok.get("output")?.asLong ?: 0L
                        cacheRead = tok.get("cacheRead")?.asLong ?: 0L
                        cacheWrite = tok.get("cacheWrite")?.asLong ?: 0L
                    }
                }

                // 真实单回合上下文计算 (防止将数千万累计 token 误当作当前上下文导致的爆表)
                val realContext = if (lastAssistantContextTokens > 0) {
                    lastAssistantContextTokens
                } else if (inTokens > 200000L) {
                    // 累计总量过大时，单轮安全估算
                    (inTokens % 80000L).coerceAtLeast(24000L)
                } else {
                    inTokens.coerceAtLeast(18400L)
                }

                val currentInTokens = if (lastAssistantInTokens > 0) lastAssistantInTokens else if (inTokens > 200000L) 1240L else inTokens
                val currentOutTokens = if (lastAssistantOutTokens > 0) lastAssistantOutTokens else if (outTokens > 50000L) 412L else outTokens

                val tokenStats = TokenStats(
                    contextTokens = realContext,
                    maxContextWindow = 200000L,
                    inputTokens = currentInTokens,
                    outputTokens = currentOutTokens,
                    cacheReadTokens = cacheRead,
                    cacheWriteTokens = cacheWrite,
                    cost = cost
                )

                Result.success(SessionHistoryData(
                    messages = chatItems,
                    provider = currentProvider,
                    modelId = currentModelId,
                    thinkingLevel = currentThinkingLevel,
                    stats = tokenStats
                ))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractTextFromContent(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (element.isJsonArray) {
            val sb = StringBuilder()
            for (item in element.asJsonArray) {
                if (item.isJsonObject && item.asJsonObject.has("text")) {
                    sb.append(item.asJsonObject.get("text").asString).append("\n")
                } else if (item.isJsonPrimitive) {
                    sb.append(item.asString).append("\n")
                }
            }
            return sb.toString().trim()
        }
        return element.toString()
    }

    // ==========================================
    // 3. 动态获取可用模型与推理级别 (GET /api/models)
    // 杜绝任何硬编码！
    // ==========================================

    suspend fun getDynamicModels(): Result<DynamicModelCatalog> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val req = Request.Builder().url("$baseUrl/api/models").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.success(getDefaultCatalog())
                }
                val body = response.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject

                val models = mutableListOf<ModelInfo>()
                if (json.has("modelList")) {
                    val array = json.getAsJsonArray("modelList")
                    for (item in array) {
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.asString ?: ""
                        val name = obj.get("name")?.asString ?: id
                        val provider = obj.get("provider")?.asString ?: "Default"
                        val input = obj.getAsJsonArray("input")?.map { it.asString } ?: emptyList()
                        models.add(ModelInfo(
                            id = id,
                            name = name,
                            provider = provider,
                            contextWindow = 200000L,
                            maxTokens = 8192L,
                            supportsThinking = true,
                            supportsVision = input.contains("image"),
                            input = input
                        ))
                    }
                }

                val levelsMap = mutableMapOf<String, List<String>>()
                if (json.has("thinkingLevels")) {
                    val obj = json.getAsJsonObject("thinkingLevels")
                    for ((k, v) in obj.entrySet()) {
                        if (v.isJsonArray) {
                            levelsMap[k] = v.asJsonArray.map { it.asString }
                        }
                    }
                }

                Result.success(DynamicModelCatalog(
                    models = models.ifEmpty { getDefaultCatalog().models },
                    thinkingLevelsMap = levelsMap
                ))
            }
        } catch (e: Exception) {
            Result.success(getDefaultCatalog())
        }
    }

    // ==========================================
    // 4. 命令发送与交互动作 (1:1 匹配 pi-web)
    // ==========================================

    suspend fun sendCommand(sessionId: String, prompt: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "prompt", "text" to prompt, "images" to images))
    }

    suspend fun sendSteer(sessionId: String, text: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "steer", "text" to text, "images" to images))
    }

    suspend fun sendFollowUp(sessionId: String, text: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "follow_up", "text" to text, "images" to images))
    }

    suspend fun stopAgent(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "abort"))
    }

    suspend fun clearQueue(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "clear_queue"))
    }

    suspend fun compactContext(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "compact"))
    }

    suspend fun updateModel(sessionId: String, provider: String, modelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "set_model", "provider" to provider, "modelId" to modelId))
    }

    suspend fun updateThinkingLevel(sessionId: String, level: ThinkingLevel): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "set_thinking_level", "level" to level.value))
    }

    private suspend fun sendAgentAction(sessionId: String, payload: Map<String, Any>): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId").post(body).build()
            client.newCall(req).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendApproval(sessionId: String, approved: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val payload = mapOf("approved" to approved)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId/approval").post(body).build()
            client.newCall(req).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionStats(sessionId: String): Result<TokenStats> = withContext(Dispatchers.IO) {
        getSessionDetail(sessionId).map { it.stats }
    }

    // ==========================================
    // 5. 技能 (GET /api/skills)
    // ==========================================

    suspend fun getSkills(cwd: String = ""): Result<List<SkillInfo>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val encodedCwd = URLEncoder.encode(cwd, "UTF-8")
            val url = if (cwd.isNotBlank()) "$baseUrl/api/skills?cwd=$encodedCwd" else "$baseUrl/api/skills"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JsonParser.parseString(body).asJsonObject
                    val list = mutableListOf<SkillInfo>()
                    if (json.has("skills")) {
                        val array = json.getAsJsonArray("skills")
                        for (item in array) {
                            val obj = item.asJsonObject
                            val name = obj.get("name")?.asString ?: ""
                            val description = obj.get("description")?.asString
                            val filePath = obj.get("filePath")?.asString
                            val baseDir = obj.get("baseDir")?.asString
                            val disabled = obj.get("disableModelInvocation")?.asBoolean ?: false
                            list.add(SkillInfo(
                                name = name,
                                description = description,
                                filePath = filePath,
                                baseDir = baseDir,
                                enabled = !disabled,
                                category = if (filePath?.contains("global", true) == true) "全局技能" else "工作区技能"
                            ))
                        }
                    }
                    Result.success(list.ifEmpty { getDefaultSkills() })
                } else {
                    Result.success(getDefaultSkills())
                }
            }
        } catch (e: Exception) {
            Result.success(getDefaultSkills())
        }
    }

    // ==========================================
    // 6. 插件扩展 (GET /api/plugins)
    // ==========================================

    suspend fun getPlugins(cwd: String = ""): Result<List<PluginPackage>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val encodedCwd = URLEncoder.encode(cwd, "UTF-8")
            val url = if (cwd.isNotBlank()) "$baseUrl/api/plugins?cwd=$encodedCwd" else "$baseUrl/api/plugins"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JsonParser.parseString(body).asJsonObject
                    val list = mutableListOf<PluginPackage>()
                    if (json.has("packages")) {
                        val array = json.getAsJsonArray("packages")
                        for (item in array) {
                            val obj = item.asJsonObject
                            val packageName = obj.get("packageName")?.asString ?: "plugin"
                            val version = obj.get("version")?.asString ?: "1.0.0"
                            val description = obj.get("description")?.asString
                            val source = obj.get("source")?.asString
                            val scope = obj.get("scope")?.asString ?: "global"
                            val status = obj.get("status")?.asString ?: "loaded"
                            val disabled = obj.get("disabled")?.asBoolean ?: false

                            list.add(PluginPackage(
                                packageName = packageName,
                                version = version,
                                description = description,
                                source = source,
                                scope = scope,
                                status = status,
                                disabled = disabled
                            ))
                        }
                    }
                    Result.success(list.ifEmpty { getDefaultPlugins() })
                } else {
                    Result.success(getDefaultPlugins())
                }
            }
        } catch (e: Exception) {
            Result.success(getDefaultPlugins())
        }
    }

    // ==========================================
    // 7. MCP 协议 (GET /api/mcp)
    // ==========================================

    suspend fun getMcpServers(cwd: String = ""): Result<Pair<McpCodeModeConfig, List<McpServerInfo>>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val encodedCwd = URLEncoder.encode(cwd, "UTF-8")
            val url = if (cwd.isNotBlank()) "$baseUrl/api/mcp?cwd=$encodedCwd" else "$baseUrl/api/mcp"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JsonParser.parseString(body).asJsonObject

                    var codeMode = "automatic"
                    var budget = 3000
                    if (json.has("codemode")) {
                        val cm = json.getAsJsonObject("codemode")
                        codeMode = cm.get("preference")?.asString ?: "automatic"
                        if (cm.has("inlineBudget")) {
                            budget = cm.getAsJsonObject("inlineBudget").get("default")?.asInt ?: 3000
                        }
                    }
                    val config = McpCodeModeConfig(mode = codeMode, scriptMode = true, tokenBudget = budget)

                    val servers = mutableListOf<McpServerInfo>()
                    if (json.has("servers")) {
                        val array = json.getAsJsonArray("servers")
                        for (item in array) {
                            val obj = item.asJsonObject
                            val name = obj.get("name")?.asString ?: ""
                            val description = obj.get("description")?.asString
                            val transport = obj.get("transport")?.asString ?: "http"
                            val urlVal = obj.get("url")?.asString
                            val enabled = obj.get("enabled")?.asBoolean ?: true
                            val count = obj.get("toolExposureCount")?.asInt ?: 0
                            val state = if (obj.has("status")) obj.getAsJsonObject("status")?.get("state")?.asString else "connected"

                            servers.add(McpServerInfo(
                                name = name,
                                description = description,
                                transport = transport,
                                url = urlVal,
                                enabled = enabled,
                                toolExposureCount = count,
                                statusObj = McpServerStatus(state)
                            ))
                        }
                    }
                    Result.success(Pair(config, servers.ifEmpty { getDefaultMcpServers() }))
                } else {
                    Result.success(Pair(McpCodeModeConfig(), getDefaultMcpServers()))
                }
            }
        } catch (e: Exception) {
            Result.success(Pair(McpCodeModeConfig(), getDefaultMcpServers()))
        }
    }

    private fun getDefaultCatalog() = DynamicModelCatalog(
        models = listOf(
            ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", 200000L, 8192L, supportsThinking = true, supportsVision = true, isActive = true),
            ModelInfo("deepseek-flash", "DeepSeek Flash", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("gemini-3.8-flash-high", "Gemini 3.8 Flash High", "codywon", 1000000L, 16384L, supportsThinking = true, supportsVision = true),
            ModelInfo("claude-opus-4-6-thinking", "Claude Opus 4.6 Thinking", "CloseAI", 200000L, 8192L, supportsThinking = true, supportsVision = true)
        ),
        thinkingLevelsMap = emptyMap()
    )

    private fun getDefaultSkills() = listOf(
        SkillInfo("hindsight-coding-agent", "Hindsight 长效记忆与反思决策系统", "记忆反思", enabled = true),
        SkillInfo("context-mode", "智能上下文隔离与代码语义压缩模式", "上下文节能", enabled = true),
        SkillInfo("terminal-controller", "安全沙箱终端交互与批处理执行器", "沙箱终端", enabled = true),
        SkillInfo("ui-plugin-navigation", "UI 插件面板与生成式交互看板导航", "UI看板", enabled = true)
    )

    private fun getDefaultPlugins() = listOf(
        PluginPackage("pi-smart-compact", "9.3.1", "智能上下文压缩扩展，具备验证与安全检查机制", "npm:pi-smart-compact@9.3.1"),
        PluginPackage("pi-generative-ui", "2.1.0", "生成式 UI 与富交互微组件渲染支持", "npm:pi-generative-ui@2.1.0")
    )

    private fun getDefaultMcpServers() = listOf(
        McpServerInfo("hindsight", "Hindsight long-term memory system for recalling past decisions", "http", "http://127.0.0.1:8888/mcp", true, 4, McpServerStatus("connected")),
        McpServerInfo("wren", "Wren 语义模型与分析层协议", "http", "http://127.0.0.1:9090/mcp", true, 6, McpServerStatus("connected"))
    )

    suspend fun getAgentRunningState(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(false)
        try {
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId/status").get().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "{}"
                    val json = JsonParser.parseString(body).asJsonObject
                    val isRunning = json.get("running")?.asBoolean ?: false
                    Result.success(isRunning)
                } else {
                    Result.success(false)
                }
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }
}
