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
                    var runningIds = emptySet<String>()
                    var awaitingIds = emptySet<String>()

                    val rawList: List<Session> = if (jsonElement.isJsonObject) {
                        val obj = jsonElement.asJsonObject
                        if (obj.has("runningSessionIds") && obj.get("runningSessionIds").isJsonArray) {
                            runningIds = obj.getAsJsonArray("runningSessionIds")
                                .mapNotNull { runCatching { it.asString }.getOrNull() }
                                .toSet()
                        }
                        if (obj.has("awaitingInputSessionIds") && obj.get("awaitingInputSessionIds").isJsonArray) {
                            awaitingIds = obj.getAsJsonArray("awaitingInputSessionIds")
                                .mapNotNull { runCatching { it.asString }.getOrNull() }
                                .toSet()
                        }
                        if (obj.has("sessions")) {
                            gson.fromJson(obj.get("sessions"), type) ?: emptyList()
                        } else {
                            emptyList()
                        }
                    } else if (jsonElement.isJsonArray) {
                        gson.fromJson(jsonElement, type) ?: emptyList()
                    } else {
                        emptyList()
                    }

                    val enrichedList = rawList.map { s ->
                        s.copy(
                            active = s.active || runningIds.contains(s.id),
                            awaitingInput = s.awaitingInput || awaitingIds.contains(s.id)
                        )
                    }
                    Result.success(enrichedList)
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
    // 对标 pi-web MessageView：配对 toolCall 与 toolResult，提取参数预览与每回合 Token 用量
    // ==========================================

    suspend fun getSessionDetail(sessionId: String, tail: Int = 5000): Result<SessionHistoryData> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val req = Request.Builder().url("$baseUrl/api/sessions/$sessionId?tail=$tail&limit=$tail").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to load session details: ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject

                var currentProvider = "codywon"
                var currentModelId = "gpt-6.1-sol"
                var currentThinkingLevel = ThinkingLevel.HIGH
                var modelContextWindow = 0L

                var lastAssistantContextTokens = 0L
                var lastAssistantInTokens = 0L
                var lastAssistantOutTokens = 0L

                val ctxObj = if (json.has("context") && json.get("context").isJsonObject) {
                    json.getAsJsonObject("context")
                } else null

                if (ctxObj != null) {
                    if (ctxObj.has("model") && ctxObj.get("model").isJsonObject) {
                        val mObj = ctxObj.getAsJsonObject("model")
                        currentProvider = mObj.get("provider")?.takeIf { !it.isJsonNull }?.asString ?: currentProvider
                        currentModelId = mObj.get("modelId")?.takeIf { !it.isJsonNull }?.asString
                            ?: mObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                            ?: currentModelId
                        modelContextWindow = mObj.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    }
                    if (ctxObj.has("thinkingLevel") && !ctxObj.get("thinkingLevel").isJsonNull) {
                        currentThinkingLevel = ThinkingLevel.fromValue(ctxObj.get("thinkingLevel")?.asString)
                    }
                }

                // 收集完整的原始消息数组：
                // 1) 如果服务端 /api/sessions/[id] 默认只返回最近 50 条 (hasMore == true)，主动通过 /api/sessions/[id]/context?tail=... 及 before=... 向上翻页拉取全量历史
                // 2) 同时支持解析 entries（包含上下文压缩 Compaction 之前的完整早期对话与压缩摘要节点），确保用户点击“展开全部”或“加载前50条”能看到从第 1 条提问开始的整个任务全貌
                val rawContextMessages = JsonArray()
                val initialCtxMsgs = ctxObj?.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
                val initialHasMore = (ctxObj?.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean == true) ||
                    (json.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean == true)
                val leafId = ctxObj?.get("leafId")?.takeIf { !it.isJsonNull }?.asString
                    ?: json.get("leafId")?.takeIf { !it.isJsonNull }?.asString

                if (initialHasMore) {
                    // 优先直接请求大窗口 context
                    val pagedMessages = fetchFullContextPages(baseUrl, sessionId, leafId, tail, initialCtxMsgs, ctxObj)
                    for (m in pagedMessages) rawContextMessages.add(m)
                } else if (initialCtxMsgs != null) {
                    for (m in initialCtxMsgs) rawContextMessages.add(m)
                }

                // 检查是否存在完整 entries 树（包含被 compaction 折叠前的早期历史记录）
                val entriesArr = when {
                    json.has("entries") && json.get("entries").isJsonArray -> json.getAsJsonArray("entries")
                    json.has("tree") && json.get("tree").isJsonObject && json.getAsJsonObject("tree").has("entries") ->
                        json.getAsJsonObject("tree").getAsJsonArray("entries")
                    json.has("messages") && json.get("messages").isJsonArray -> json.getAsJsonArray("messages")
                    else -> null
                }

                val normalizedEntryMessages = if (entriesArr != null && entriesArr.size() > 0) {
                    extractChronologicalMessagesFromEntries(entriesArr, leafId)
                } else JsonArray()

                val chosenMessages = if (normalizedEntryMessages.size() > rawContextMessages.size()) {
                    normalizedEntryMessages
                } else {
                    rawContextMessages
                }

                val parsedResult = parseRawMessagesToChatItems(
                    sessionId = sessionId,
                    msgs = chosenMessages,
                    defaultModelId = currentModelId
                )
                val chatItems = parsedResult.items
                if (parsedResult.lastContextTokens > 0) {
                    lastAssistantContextTokens = parsedResult.lastContextTokens
                    lastAssistantInTokens = parsedResult.lastInTokens
                    lastAssistantOutTokens = parsedResult.lastOutTokens
                }

                // 2. 解析详细 Token 与监控用量
                var inTokens = 0L
                var outTokens = 0L
                var cacheRead = 0L
                var cacheWrite = 0L
                var cost = 0.0

                if (json.has("stats") && json.get("stats").isJsonObject) {
                    val stats = json.getAsJsonObject("stats")
                    cost = stats.get("cost")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                    if (stats.has("tokens") && stats.get("tokens").isJsonObject) {
                        val tok = stats.getAsJsonObject("tokens")
                        inTokens = tok.get("input")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        outTokens = tok.get("output")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        cacheRead = tok.get("cacheRead")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        cacheWrite = tok.get("cacheWrite")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    }
                }

                // 动态匹配该模型配置的上下文窗口上限（优先读取用户保存的模型覆盖配置）
                val overrides = prefs.getModelOverrides()
                val overrideModel = overrides["$currentProvider:$currentModelId"]
                    ?: overrides.values.firstOrNull { it.modelId.equals(currentModelId, ignoreCase = true) }
                val effectiveMaxContext = when {
                    overrideModel != null && overrideModel.contextWindow > 0L -> overrideModel.contextWindow
                    modelContextWindow > 0L -> modelContextWindow
                    else -> 200000L
                }

                val realContext = if (lastAssistantContextTokens > 0) {
                    lastAssistantContextTokens
                } else if (inTokens > effectiveMaxContext) {
                    (inTokens % 80000L).coerceAtLeast(24000L)
                } else {
                    inTokens.coerceAtLeast(0L)
                }

                val currentInTokens = if (lastAssistantInTokens > 0) lastAssistantInTokens else inTokens
                val currentOutTokens = if (lastAssistantOutTokens > 0) lastAssistantOutTokens else outTokens

                val tokenStats = TokenStats(
                    contextTokens = realContext,
                    maxContextWindow = effectiveMaxContext,
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

    private data class ParsedMessagesResult(
        val items: MutableList<ChatItem>,
        val lastContextTokens: Long,
        val lastInTokens: Long,
        val lastOutTokens: Long
    )

    /**
     * 当服务端默认分页截断时，通过 /api/sessions/[id]/context?tail=... 以及 before=<oldestEntryId> 拉取全部早期历史页
     */
    private fun fetchFullContextPages(
        baseUrl: String,
        sessionId: String,
        leafId: String?,
        tail: Int,
        initialMsgs: JsonArray?,
        initialCtxObj: JsonObject?
    ): List<com.google.gson.JsonElement> {
        val collected = mutableListOf<com.google.gson.JsonElement>()
        var oldestEntryId: String? = null
        var hasMore = false

        // 1. 先尝试直接通过 /api/sessions/$sessionId/context?tail=$tail 获取大窗口数据
        val leafParam = if (!leafId.isNullOrBlank()) "&leafId=${URLEncoder.encode(leafId, "UTF-8")}" else ""
        val bigPageJson = runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$sessionId/context?tail=$tail$leafParam")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val str = resp.body?.string() ?: "{}"
                    JsonParser.parseString(str).takeIf { it.isJsonObject }?.asJsonObject
                } else null
            }
        }.getOrNull()

        val activeCtxObj = if (bigPageJson != null) {
            if (bigPageJson.has("context") && bigPageJson.get("context").isJsonObject) {
                bigPageJson.getAsJsonObject("context")
            } else bigPageJson
        } else initialCtxObj

        val baseMsgs = activeCtxObj?.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray ?: initialMsgs
        if (baseMsgs != null) {
            for (el in baseMsgs) collected.add(el)
        }

        hasMore = activeCtxObj?.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean == true
        oldestEntryId = extractOldestEntryId(activeCtxObj, baseMsgs)

        // 2. 若仍有更早的分页 (hasMore == true)，使用 before=<oldestEntryId> 向上翻页拉取并拼接到最前面
        var pageGuard = 0
        while (hasMore && !oldestEntryId.isNullOrBlank() && pageGuard < 12) {
            pageGuard++
            val encBefore = URLEncoder.encode(oldestEntryId, "UTF-8")
            val olderJson = runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/sessions/$sessionId/context?tail=200&before=$encBefore$leafParam")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val str = resp.body?.string() ?: "{}"
                        JsonParser.parseString(str).takeIf { it.isJsonObject }?.asJsonObject
                    } else null
                }
            }.getOrNull() ?: break

            val olderCtx = if (olderJson.has("context") && olderJson.get("context").isJsonObject) {
                olderJson.getAsJsonObject("context")
            } else olderJson

            val olderMsgs = olderCtx.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
            if (olderMsgs == null || olderMsgs.size() == 0) break

            val prependList = mutableListOf<com.google.gson.JsonElement>()
            for (el in olderMsgs) prependList.add(el)
            collected.addAll(0, prependList)

            val nextOldest = extractOldestEntryId(olderCtx, olderMsgs)
            hasMore = olderCtx.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean == true && nextOldest != oldestEntryId
            oldestEntryId = nextOldest
        }

        return collected
    }

    private fun extractOldestEntryId(ctxObj: JsonObject?, msgs: JsonArray?): String? {
        if (ctxObj != null) {
            if (ctxObj.has("entryIds") && ctxObj.get("entryIds").isJsonArray) {
                val ids = ctxObj.getAsJsonArray("entryIds")
                if (ids.size() > 0 && !ids.get(0).isJsonNull) {
                    return ids.get(0).asString
                }
            }
            if (ctxObj.has("firstEntryId") && !ctxObj.get("firstEntryId").isJsonNull) {
                return ctxObj.get("firstEntryId").asString
            }
        }
        if (msgs != null && msgs.size() > 0 && msgs.get(0).isJsonObject) {
            val firstMsg = msgs.get(0).asJsonObject
            return firstMsg.get("entryId")?.takeIf { !it.isJsonNull }?.asString
                ?: firstMsg.get("id")?.takeIf { !it.isJsonNull }?.asString
        }
        return null
    }

    /**
     * 从会话完整 entries 树中按时间顺序提取所有消息（包含被 compaction 压缩之前的全部早期消息与压缩摘要里程碑）
     */
    private fun extractChronologicalMessagesFromEntries(entriesArr: JsonArray, leafId: String?): JsonArray {
        val rawEntries = mutableListOf<JsonObject>()
        val byId = mutableMapOf<String, JsonObject>()
        for (el in entriesArr) {
            if (!el.isJsonObject) continue
            val obj = el.asJsonObject
            rawEntries.add(obj)
            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString
            if (!id.isNullOrBlank()) {
                byId[id] = obj
            }
        }

        // 如果存在 parentId 树结构且能够回溯到根，则提取当前活跃分支的完整根到叶路径（不跳过 compaction 之前的节点！）
        val orderedEntries: List<JsonObject> = if (byId.isNotEmpty()) {
            val startNode = if (!leafId.isNullOrBlank() && byId.containsKey(leafId)) {
                byId[leafId]
            } else {
                rawEntries.lastOrNull { it.has("id") }
            }
            if (startNode != null) {
                val branchReversed = mutableListOf<JsonObject>()
                val visited = mutableSetOf<String>()
                var curr: JsonObject? = startNode
                while (curr != null) {
                    branchReversed.add(curr)
                    val cid = curr.get("id")?.takeIf { !it.isJsonNull }?.asString
                    if (cid != null) visited.add(cid)
                    val pid = curr.get("parentId")?.takeIf { !it.isJsonNull }?.asString
                    curr = if (!pid.isNullOrBlank() && !visited.contains(pid)) byId[pid] else null
                }
                val branch = branchReversed.asReversed()
                if (branch.size >= rawEntries.size / 2) branch else rawEntries
            } else {
                rawEntries
            }
        } else {
            rawEntries
        }

        val result = JsonArray()
        for (entry in orderedEntries) {
            val entryType = entry.get("type")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val entryTs = parseFlexibleTimestamp(entry.get("timestamp"))
            when {
                entryType == "message" && entry.has("message") && entry.get("message").isJsonObject -> {
                    val mObj = entry.getAsJsonObject("message").deepCopy()
                    if (!mObj.has("timestamp") && entryTs > 0L) {
                        mObj.addProperty("timestamp", entryTs)
                    }
                    result.add(mObj)
                }
                entryType == "compaction" -> {
                    val summary = entry.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val tokensBefore = entry.get("tokensBefore")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    val synthetic = JsonObject().apply {
                        addProperty("role", "compactionSummary")
                        addProperty("summary", summary)
                        addProperty("tokensBefore", tokensBefore)
                        if (entryTs > 0L) addProperty("timestamp", entryTs)
                    }
                    result.add(synthetic)
                }
                entryType == "branch_summary" -> {
                    val summary = entry.get("summary")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (summary.isNotBlank()) {
                        val synthetic = JsonObject().apply {
                            addProperty("role", "branchSummary")
                            addProperty("summary", summary)
                            if (entryTs > 0L) addProperty("timestamp", entryTs)
                        }
                        result.add(synthetic)
                    }
                }
                entryType == "custom_message" -> {
                    val contentText = extractTextFromContent(entry.get("content"))
                    if (contentText.isNotBlank()) {
                        val synthetic = JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", contentText)
                            if (entryTs > 0L) addProperty("timestamp", entryTs)
                        }
                        result.add(synthetic)
                    }
                }
                entry.has("role") -> {
                    result.add(entry)
                }
            }
        }
        return result
    }

    private fun parseFlexibleTimestamp(el: com.google.gson.JsonElement?): Long {
        if (el == null || el.isJsonNull) return System.currentTimeMillis()
        if (el.isJsonPrimitive) {
            val prim = el.asJsonPrimitive
            if (prim.isNumber) return prim.asLong
            if (prim.isString) {
                val str = prim.asString
                str.toLongOrNull()?.let { return it }
                return runCatching { java.time.Instant.parse(str).toEpochMilli() }.getOrDefault(System.currentTimeMillis())
            }
        }
        return System.currentTimeMillis()
    }

    private fun parseRawMessagesToChatItems(
        sessionId: String,
        msgs: JsonArray,
        defaultModelId: String
    ): ParsedMessagesResult {
        val chatItems = mutableListOf<ChatItem>()
        var lastAssistantContextTokens = 0L
        var lastAssistantInTokens = 0L
        var lastAssistantOutTokens = 0L

        data class ParsedToolResult(
            val toolCallId: String?,
            val toolName: String,
            val output: String,
            val mediaList: List<MediaAttachment>,
            val durationMs: Long,
            val isError: Boolean,
            val timestamp: Long
        )
        val toolResultById = mutableMapOf<String, ParsedToolResult>()
        val consumedResultIds = mutableSetOf<String>()

        for (element in msgs) {
            if (!element.isJsonObject) continue
            val rawObj = element.asJsonObject
            val msgObj = if (rawObj.has("message") && rawObj.get("message").isJsonObject) {
                rawObj.getAsJsonObject("message")
            } else rawObj
            val roleStr = msgObj.get("role")?.takeIf { !it.isJsonNull }?.asString ?: ""
            if (roleStr == "toolResult" || roleStr == "tool") {
                val tcId = msgObj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                    ?: msgObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                val tName = msgObj.get("toolName")?.takeIf { !it.isJsonNull }?.asString
                    ?: msgObj.get("name")?.takeIf { !it.isJsonNull }?.asString
                    ?: "tool"
                val contentElem = msgObj.get("content") ?: msgObj.get("output")
                val tOut = extractTextFromContent(contentElem)
                val tMedia = extractMediaFromContent(contentElem, tOut)
                val dur = msgObj.get("durationMs")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val isErr = msgObj.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                val ts = parseFlexibleTimestamp(msgObj.get("timestamp") ?: rawObj.get("timestamp"))
                if (!tcId.isNullOrBlank()) {
                    toolResultById[tcId] = ParsedToolResult(tcId, tName, tOut, tMedia, dur, isErr, ts)
                }
            }
        }

        for (element in msgs) {
            if (!element.isJsonObject) continue
            val rawObj = element.asJsonObject
            val msgObj = if (rawObj.has("message") && rawObj.get("message").isJsonObject) {
                rawObj.getAsJsonObject("message")
            } else rawObj
            val roleStr = msgObj.get("role")?.takeIf { !it.isJsonNull }?.asString ?: ""
            val timestamp = parseFlexibleTimestamp(msgObj.get("timestamp") ?: rawObj.get("timestamp"))

            var turnUsageStr: String? = null
            if (roleStr == "assistant" && msgObj.has("usage") && msgObj.get("usage").isJsonObject) {
                val uObj = msgObj.getAsJsonObject("usage")
                val uIn = uObj.get("input")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val uOut = uObj.get("output")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val uCacheR = uObj.get("cacheRead")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val uCacheW = uObj.get("cacheWrite")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val uCost = if (uObj.has("cost") && uObj.get("cost").isJsonObject) {
                    uObj.getAsJsonObject("cost").get("total")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0
                } else 0.0
                if (uIn > 0) {
                    lastAssistantContextTokens = uIn + uCacheR
                    lastAssistantInTokens = uIn
                    lastAssistantOutTokens = uOut
                }
                turnUsageStr = TokenStats.formatTurnUsage(uIn, uOut, uCacheR, uCacheW, uCost)
            }

            val msgModelName = msgObj.get("model")?.takeIf { !it.isJsonNull }?.asString
                ?: defaultModelId

            when (roleStr) {
                "user" -> {
                    val contentElem = msgObj.get("content") ?: msgObj.get("text")
                    val userText = extractTextFromContent(contentElem)
                    val userMedia = extractMediaFromContent(contentElem, userText)
                    if (userText.isNotBlank() || userMedia.isNotEmpty()) {
                        // 识别是否为 [Compaction summary] 摘要节点
                        if (userText.startsWith("[Compaction summary]") || userText.startsWith("<compaction_summary>")) {
                            val cleanSummary = userText
                                .removePrefix("[Compaction summary]")
                                .removePrefix("<compaction_summary>")
                                .removeSuffix("</compaction_summary>")
                                .trim()
                            chatItems.add(ChatItem(
                                id = "hist_${sessionId}_${chatItems.size}_compaction",
                                role = MessageRole.SYSTEM,
                                content = "📦 上下文已归档压缩" + if (cleanSummary.isNotBlank()) "\n$cleanSummary" else "",
                                timestamp = timestamp
                            ))
                        } else {
                            chatItems.add(ChatItem(
                                id = "hist_${sessionId}_${chatItems.size}_user",
                                role = MessageRole.USER,
                                content = userText,
                                mediaList = userMedia,
                                timestamp = timestamp
                            ))
                        }
                    }
                }
                "assistant" -> {
                    var text = ""
                    var thinking: String? = msgObj.get("thinking")?.takeIf { !it.isJsonNull }?.asString
                    val toolCalls = mutableListOf<ToolCallInfo>()
                    val assistantMedia = mutableListOf<MediaAttachment>()

                    if (msgObj.has("content")) {
                        val contentElem = msgObj.get("content")
                        assistantMedia.addAll(extractMediaFromContent(contentElem, null))
                        if (contentElem.isJsonArray) {
                            for (cItem in contentElem.asJsonArray) {
                                if (!cItem.isJsonObject) continue
                                val cObj = cItem.asJsonObject
                                when (cObj.get("type")?.takeIf { !it.isJsonNull }?.asString) {
                                    "text", "output_text", "input_text" -> {
                                        val t = cObj.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                        text = if (text.isEmpty()) t else "$text\n$t"
                                    }
                                    "thinking" -> {
                                        val th = cObj.get("thinking")?.takeIf { !it.isJsonNull }?.asString
                                            ?: cObj.get("text")?.takeIf { !it.isJsonNull }?.asString
                                            ?: ""
                                        thinking = if (thinking.isNullOrEmpty()) th else "$thinking\n$th"
                                    }
                                    "toolCall", "tool_use", "tool_call" -> {
                                        val tcId = cObj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                                            ?: cObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                                        val toolName = cObj.get("toolName")?.takeIf { !it.isJsonNull }?.asString
                                            ?: cObj.get("name")?.takeIf { !it.isJsonNull }?.asString
                                            ?: "tool"
                                        val inputElem = cObj.get("input") ?: cObj.get("arguments") ?: cObj.get("args")
                                        val preview = extractToolPreview(inputElem)
                                        val fields = extractToolFields(inputElem)
                                        val formattedInput = formatToolInput(inputElem)
                                        val paired = if (!tcId.isNullOrBlank()) {
                                            consumedResultIds.add(tcId)
                                            toolResultById[tcId]
                                        } else null
                                        val argMedia = extractMediaFromToolArgs(toolName, preview, inputElem)
                                        val combinedMedia = mergeMediaLists(paired?.mediaList ?: emptyList(), argMedia)

                                        toolCalls.add(
                                            ToolCallInfo(
                                                toolCallId = tcId,
                                                toolName = toolName,
                                                preview = preview,
                                                command = formattedInput,
                                                fields = fields,
                                                output = paired?.output,
                                                mediaList = combinedMedia,
                                                durationMs = paired?.durationMs ?: 0L,
                                                isError = paired?.isError ?: false
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (contentElem.isJsonPrimitive) {
                            text = contentElem.asString
                        }
                    }

                    val startIndexForTurn = chatItems.size
                    if (text.isNotBlank() || !thinking.isNullOrBlank() || assistantMedia.isNotEmpty()) {
                        val estSec = thinking?.let { (it.length / 45).coerceIn(1, 90) }
                        chatItems.add(ChatItem(
                            id = "hist_${sessionId}_${chatItems.size}_agent",
                            role = MessageRole.AGENT,
                            content = text,
                            thinking = thinking,
                            thinkingDurationSec = estSec,
                            mediaList = assistantMedia,
                            modelName = msgModelName,
                            usageSummary = if (toolCalls.isEmpty()) turnUsageStr else null,
                            timestamp = timestamp
                        ))
                    }
                    for ((idx, tc) in toolCalls.withIndex()) {
                        val isFirstItemInTurn = (chatItems.size == startIndexForTurn)
                        val isLastToolInTurn = (idx == toolCalls.lastIndex)
                        chatItems.add(ChatItem(
                            id = "hist_${sessionId}_${chatItems.size}_tool_${tc.toolCallId ?: idx}",
                            role = MessageRole.TOOL,
                            content = "",
                            toolCall = tc,
                            modelName = if (isFirstItemInTurn) msgModelName else null,
                            usageSummary = if (isLastToolInTurn) turnUsageStr else null,
                            timestamp = timestamp
                        ))
                    }
                }
                "toolResult", "tool" -> {
                    val tcId = msgObj.get("toolCallId")?.takeIf { !it.isJsonNull }?.asString
                        ?: msgObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                    if (tcId != null && consumedResultIds.contains(tcId)) {
                        // 已与对应的 toolCall 卡片配对合并，不再重复渲染第二张卡片
                    } else {
                        val toolName = msgObj.get("toolName")?.takeIf { !it.isJsonNull }?.asString
                            ?: msgObj.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?: "tool"
                        val contentElem = msgObj.get("content") ?: msgObj.get("output")
                        val toolOutput = extractTextFromContent(contentElem)
                        val toolMedia = extractMediaFromContent(contentElem, toolOutput)
                        val duration = msgObj.get("durationMs")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        val isErr = msgObj.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        val recentIdx = chatItems.indexOfLast {
                            it.role == MessageRole.TOOL &&
                                it.toolCall?.toolName == toolName &&
                                it.toolCall.output.isNullOrBlank()
                        }
                        if (recentIdx >= 0) {
                            val prev = chatItems[recentIdx]
                            val prevTool = prev.toolCall
                            chatItems[recentIdx] = prev.copy(
                                toolCall = prevTool?.copy(
                                    output = toolOutput,
                                    mediaList = mergeMediaLists(toolMedia, prevTool.mediaList),
                                    durationMs = duration,
                                    isError = isErr
                                )
                            )
                        } else {
                            chatItems.add(ChatItem(
                                id = "hist_${sessionId}_${chatItems.size}_toolres_${tcId ?: toolName}",
                                role = MessageRole.TOOL,
                                content = "",
                                toolCall = ToolCallInfo(
                                    toolCallId = tcId,
                                    toolName = toolName,
                                    output = toolOutput,
                                    mediaList = toolMedia,
                                    durationMs = duration,
                                    isError = isErr
                                ),
                                timestamp = timestamp
                            ))
                        }
                    }
                }
                "compactionSummary", "branchSummary" -> {
                    val summary = msgObj.get("summary")?.takeIf { !it.isJsonNull }?.asString
                        ?: extractTextFromContent(msgObj.get("content"))
                    val tokensBefore = msgObj.get("tokensBefore")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    val header = if (tokensBefore > 0) {
                        "📦 上下文已归档压缩 (压缩前 ${TokenStats.formatTokenCompact(tokensBefore)} tokens)"
                    } else {
                        "📦 上下文已归档压缩"
                    }
                    chatItems.add(ChatItem(
                        id = "hist_${sessionId}_${chatItems.size}_compaction",
                        role = MessageRole.SYSTEM,
                        content = if (summary.isNotBlank()) "$header\n$summary" else header,
                        timestamp = timestamp
                    ))
                }
                "custom", "system" -> {
                    val sysText = extractTextFromContent(msgObj.get("content") ?: msgObj.get("text"))
                    if (sysText.isNotBlank()) {
                        chatItems.add(ChatItem(
                            id = "hist_${sessionId}_${chatItems.size}_sys",
                            role = MessageRole.SYSTEM,
                            content = sysText,
                            timestamp = timestamp
                        ))
                    }
                }
            }
        }

        return ParsedMessagesResult(
            items = chatItems,
            lastContextTokens = lastAssistantContextTokens,
            lastInTokens = lastAssistantInTokens,
            lastOutTokens = lastAssistantOutTokens
        )
    }

    /**
     * 对标 pi-web MessageView.tsx 中的 getToolPreview(block)：
     * 提取工具调用最核心的单行参数摘要（如文件路径、shell 命令、搜索词）
     */
    private fun extractToolPreview(element: com.google.gson.JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString.replace('\n', ' ').trim().take(120)
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val priorityKeys = listOf("command", "path", "file_path", "filePath", "pattern", "query", "url", "script")
        for (k in priorityKeys) {
            if (obj.has(k) && !obj.get(k).isJsonNull) {
                val v = obj.get(k)
                val str = if (v.isJsonPrimitive) v.asString else v.toString()
                return str.replace('\n', ' ').trim().take(120)
            }
        }
        val firstEntry = obj.entrySet().firstOrNull() ?: return null
        val v = firstEntry.value
        val str = if (v.isJsonPrimitive) v.asString else v.toString()
        return str.replace('\n', ' ').trim().take(120)
    }

    private fun extractToolFields(element: com.google.gson.JsonElement?): List<Pair<String, String>> {
        if (element == null || !element.isJsonObject) return emptyList()
        val obj = element.asJsonObject
        return obj.entrySet().mapNotNull { (k, v) ->
            if (v == null || v.isJsonNull) null
            else k to (if (v.isJsonPrimitive) v.asString else v.toString())
        }
    }

    private fun formatToolInput(element: com.google.gson.JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive) return element.asString
        if (element.isJsonObject) {
            val obj = element.asJsonObject
            if (obj.size() == 1 && obj.has("command") && obj.get("command").isJsonPrimitive) {
                return obj.get("command").asString
            }
        }
        return gson.toJson(element)
    }

    private fun extractTextFromContent(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString
        if (element.isJsonArray) {
            val sb = StringBuilder()
            for (item in element.asJsonArray) {
                if (item.isJsonObject && item.asJsonObject.has("text") && !item.asJsonObject.get("text").isJsonNull) {
                    sb.append(item.asJsonObject.get("text").asString).append("\n")
                } else if (item.isJsonPrimitive) {
                    sb.append(item.asString).append("\n")
                }
            }
            return sb.toString().trim()
        }
        return element.toString()
    }

    private fun extractMediaFromContent(
        element: com.google.gson.JsonElement?,
        outputText: String? = null
    ): List<MediaAttachment> {
        val list = mutableListOf<MediaAttachment>()
        if (element != null && element.isJsonArray) {
            for (item in element.asJsonArray) {
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
        if (!outputText.isNullOrBlank() && outputText.contains("<svg", ignoreCase = true) && outputText.contains("</svg>", ignoreCase = true)) {
            val svgMatch = Regex("""<svg[\s\S]*?</svg>""", RegexOption.IGNORE_CASE).find(outputText)
            if (svgMatch != null) {
                list.add(
                    MediaAttachment(
                        kind = MediaKind.SVG,
                        mimeType = "image/svg+xml",
                        svgOrCode = svgMatch.value,
                        title = "SVG 矢量图"
                    )
                )
            }
        }
        return list
    }

    private fun extractMediaFromToolArgs(
        toolName: String,
        preview: String?,
        argsElem: com.google.gson.JsonElement?
    ): List<MediaAttachment> {
        val rawPath = preview?.takeIf { it.isNotBlank() } ?: run {
            if (argsElem != null && argsElem.isJsonObject) {
                val obj = argsElem.asJsonObject
                (obj.get("path") ?: obj.get("file_path") ?: obj.get("filePath"))
                    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
            } else null
        } ?: return emptyList()

        val cleanPath = rawPath.trim().removeSurrounding("\"").removeSurrounding("'")
        val lower = cleanPath.lowercase()
        val isReadOrFileTool = toolName.lowercase() in setOf("read", "view_file", "read_file", "write", "write_file", "open")
        if (!isReadOrFileTool && !lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg") &&
            !lower.endsWith(".webp") && !lower.endsWith(".gif") && !lower.endsWith(".svg") && !lower.endsWith(".pdf")
        ) {
            return emptyList()
        }

        val (kind, mime) = when {
            lower.endsWith(".png") -> MediaKind.IMAGE to "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> MediaKind.IMAGE to "image/jpeg"
            lower.endsWith(".webp") -> MediaKind.IMAGE to "image/webp"
            lower.endsWith(".gif") -> MediaKind.IMAGE to "image/gif"
            lower.endsWith(".bmp") -> MediaKind.IMAGE to "image/bmp"
            lower.endsWith(".svg") -> MediaKind.SVG to "image/svg+xml"
            lower.endsWith(".pdf") -> MediaKind.PDF to "application/pdf"
            else -> return emptyList()
        }

        return listOf(
            MediaAttachment(
                kind = kind,
                mimeType = mime,
                filePath = cleanPath,
                title = cleanPath.substringAfterLast('/').substringAfterLast('\\')
            )
        )
    }

    private fun mergeMediaLists(
        primary: List<MediaAttachment>,
        secondary: List<MediaAttachment>
    ): List<MediaAttachment> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val fallbackMeta = secondary.firstOrNull()
        return primary.map { item ->
            item.copy(
                filePath = item.filePath ?: fallbackMeta?.filePath,
                title = item.title.ifBlank { fallbackMeta?.title ?: "" }
            )
        }
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
                    return@withContext Result.success(applyModelOverrides(getDefaultCatalog(), null))
                }
                val body = response.body?.string() ?: "{}"
                val json = JsonParser.parseString(body).asJsonObject

                val models = mutableListOf<ModelInfo>()
                if (json.has("modelList") && json.get("modelList").isJsonArray) {
                    val array = json.getAsJsonArray("modelList")
                    for (item in array) {
                        if (!item.isJsonObject) continue
                        val obj = item.asJsonObject
                        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: id
                        val provider = obj.get("provider")?.takeIf { !it.isJsonNull }?.asString ?: "Default"
                        val ctxWin = obj.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong ?: 200000L
                        val maxTok = obj.get("maxTokens")?.takeIf { !it.isJsonNull }?.asLong ?: 8192L
                        val reasoning = obj.get("reasoning")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                        val input = obj.get("input")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                            runCatching { it.asString }.getOrNull()
                        } ?: emptyList()
                        models.add(ModelInfo(
                            id = id,
                            name = name,
                            provider = provider,
                            contextWindow = ctxWin,
                            maxTokens = maxTok,
                            supportsThinking = reasoning,
                            supportsVision = input.contains("image"),
                            input = input
                        ))
                    }
                }

                val levelsMap = mutableMapOf<String, List<String>>()
                if (json.has("thinkingLevels") && json.get("thinkingLevels").isJsonObject) {
                    val obj = json.getAsJsonObject("thinkingLevels")
                    for ((k, v) in obj.entrySet()) {
                        if (v.isJsonArray) {
                            levelsMap[k] = v.asJsonArray.mapNotNull { runCatching { it.asString }.getOrNull() }
                        }
                    }
                }

                // 同时拉取 /api/models-config (models.json) 与本地保存的模型参数覆盖，确保用户修改的 contextWindow 等参数100%生效且不回退
                val serverModelsConfig = fetchServerModelsConfigObject(baseUrl)
                val baseCatalog = DynamicModelCatalog(
                    models = models.ifEmpty { getDefaultCatalog().models },
                    thinkingLevelsMap = levelsMap
                )
                Result.success(applyModelOverrides(baseCatalog, serverModelsConfig))
            }
        } catch (e: Exception) {
            Result.success(applyModelOverrides(getDefaultCatalog(), null))
        }
    }

    private data class ModelsConfigFetchResult(
        val config: JsonObject,
        val wrappedInConfigKey: Boolean,
        val wasHealed: Boolean
    )

    private fun fetchServerModelsConfigWithEnvelope(baseUrl: String, autoPushHeal: Boolean = true): ModelsConfigFetchResult? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url("$baseUrl/api/models-config").get().build()
            val fetched = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val str = resp.body?.string()?.trim() ?: return@use null
                if (str.isEmpty()) return@use ModelsConfigFetchResult(JsonObject().apply { add("providers", JsonObject()) }, false, false)
                val parsed = runCatching { JsonParser.parseString(str) }.getOrNull() ?: return@use null
                if (!parsed.isJsonObject) return@use null
                val root = parsed.asJsonObject
                var wrappedInConfig = false
                val targetObj: JsonObject = when {
                    root.has("providers") && root.get("providers").isJsonObject -> {
                        // 如果同时存在残留的顶层 config 字段，说明曾被旧版写入污染，后续由 sanitizeAndHealModelsConfig 清理
                        root.deepCopy()
                    }
                    root.has("config") && root.get("config").isJsonObject -> {
                        wrappedInConfig = true
                        root.getAsJsonObject("config").deepCopy()
                    }
                    root.has("config") && root.get("config").isJsonPrimitive -> {
                        wrappedInConfig = true
                        val innerStr = root.get("config").asString
                        runCatching { JsonParser.parseString(innerStr).asJsonObject }.getOrNull() ?: JsonObject()
                    }
                    root.has("content") && root.get("content").isJsonPrimitive -> {
                        val innerStr = root.get("content").asString
                        runCatching { JsonParser.parseString(innerStr).asJsonObject }.getOrNull() ?: JsonObject()
                    }
                    root.has("raw") && root.get("raw").isJsonPrimitive -> {
                        val innerStr = root.get("raw").asString
                        runCatching { JsonParser.parseString(innerStr).asJsonObject }.getOrNull() ?: JsonObject()
                    }
                    else -> root.deepCopy()
                }
                val healed = sanitizeAndHealModelsConfig(targetObj)
                ModelsConfigFetchResult(targetObj, wrappedInConfig, healed)
            } ?: return@runCatching null

            if (fetched.wasHealed && autoPushHeal) {
                runCatching {
                    putModelsConfigToServer(baseUrl, fetched.config, fetched.wrappedInConfigKey)
                }
            }
            fetched
        }.getOrNull()
    }

    private fun fetchServerModelsConfigObject(baseUrl: String): JsonObject? {
        return fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = true)?.config
    }

    /**
     * 自动检测并无损修复损坏的 models.json：
     * 1. 若顶层意外残留了 "config" 递归包装，抢救其中的 providers 后移除顶层 "config"（修复 schema 校验报错）
     * 2. 若某个 provider 下存在 "models" 数组但却没有 "baseUrl"（此前修改单个内置模型如 Gemini 上下文时误写入了 1 项 models 数组，导致该提供商其他模型被清空且缺失 baseUrl 引起 pi 无法工作），
     *    自动将其中的模型参数无损迁移到 pi-coding-agent 官方标准的 "modelOverrides" 字段下，并移除非法的无 baseUrl "models" 数组！
     */
    private fun sanitizeAndHealModelsConfig(rootConfig: JsonObject): Boolean {
        var modified = false

        val providersObj = if (rootConfig.has("providers") && rootConfig.get("providers").isJsonObject) {
            rootConfig.getAsJsonObject("providers")
        } else {
            JsonObject().also { rootConfig.add("providers", it) }
        }

        // 1. 抢救并移除顶层可能残留的 "config" 污染字段
        if (rootConfig.has("config")) {
            val nested = rootConfig.get("config")
            if (nested != null && nested.isJsonObject) {
                val nestedObj = nested.asJsonObject
                if (nestedObj.has("providers") && nestedObj.get("providers").isJsonObject) {
                    val nestedProviders = nestedObj.getAsJsonObject("providers")
                    for ((k, v) in nestedProviders.entrySet()) {
                        if (!providersObj.has(k) && v.isJsonObject) {
                            providersObj.add(k, v)
                        }
                    }
                }
            }
            rootConfig.remove("config")
            modified = true
        }

        // 2. 检查每个 provider：若没有 baseUrl 却定义了 models 数组，无损转换为 modelOverrides
        for ((_, provElem) in providersObj.entrySet()) {
            if (!provElem.isJsonObject) continue
            val pObj = provElem.asJsonObject
            val baseUrlVal = pObj.get("baseUrl")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim() ?: ""
            val hasModelsArr = pObj.has("models") && pObj.get("models").isJsonArray
            if (hasModelsArr && baseUrlVal.isEmpty()) {
                val mArr = pObj.getAsJsonArray("models")
                val overridesObj = if (pObj.has("modelOverrides") && pObj.get("modelOverrides").isJsonObject) {
                    pObj.getAsJsonObject("modelOverrides")
                } else {
                    JsonObject().also { pObj.add("modelOverrides", it) }
                }

                for (mElem in mArr) {
                    if (!mElem.isJsonObject) continue
                    val mObj = mElem.asJsonObject
                    val mId = mObj.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                    val ovItem = if (overridesObj.has(mId) && overridesObj.get(mId).isJsonObject) {
                        overridesObj.getAsJsonObject(mId)
                    } else {
                        JsonObject().also { overridesObj.add(mId, it) }
                    }
                    mObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }?.let {
                        ovItem.addProperty("name", it)
                    }
                    mObj.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong?.let {
                        ovItem.addProperty("contextWindow", it)
                    }
                    mObj.get("maxTokens")?.takeIf { !it.isJsonNull }?.asLong?.let {
                        ovItem.addProperty("maxTokens", it)
                    }
                    mObj.get("reasoning")?.takeIf { !it.isJsonNull }?.asBoolean?.let {
                        ovItem.addProperty("reasoning", it)
                    }
                    if (mObj.has("input") && mObj.get("input").isJsonArray) {
                        ovItem.add("input", mObj.getAsJsonArray("input").deepCopy())
                    }
                }

                pObj.remove("models")
                // 若没有 baseUrl，移除之前误加的默认 api: "openai-completions" 以免破坏 google/anthropic 等原生协议
                if (!pObj.has("baseUrl")) {
                    pObj.remove("api")
                }
                modified = true
            }
        }

        return modified
    }

    /**
     * 将纯净的 models.json 配置写回网关并触发模型目录刷新
     */
    private fun putModelsConfigToServer(
        baseUrl: String,
        cleanRootConfig: JsonObject,
        wrappedInConfigKey: Boolean
    ): Boolean {
        cleanRootConfig.remove("config")
        val primaryPayload = if (wrappedInConfigKey) {
            JsonObject().apply { add("config", cleanRootConfig) }
        } else {
            cleanRootConfig
        }
        val req1 = Request.Builder()
            .url("$baseUrl/api/models-config")
            .put(gson.toJson(primaryPayload).toRequestBody(jsonMediaType))
            .build()
        val ok1 = runCatching {
            client.newCall(req1).execute().use { it.isSuccessful }
        }.getOrDefault(false)

        if (!ok1) {
            val fallbackPayload = if (wrappedInConfigKey) {
                cleanRootConfig
            } else {
                JsonObject().apply { add("config", cleanRootConfig) }
            }
            val req2 = Request.Builder()
                .url("$baseUrl/api/models-config")
                .put(gson.toJson(fallbackPayload).toRequestBody(jsonMediaType))
                .build()
            val ok2 = runCatching {
                client.newCall(req2).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (!ok2) return false
        }

        runCatching {
            val refreshReq = Request.Builder()
                .url("$baseUrl/api/models/refresh")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(refreshReq).execute().close()
        }
        return true
    }

    /**
     * 将服务端 models.json 中的自定义配置 (models 与 modelOverrides) 以及本地持久化的用户修改参数合并至模型列表中
     */
    private fun applyModelOverrides(
        catalog: DynamicModelCatalog,
        serverConfig: JsonObject?
    ): DynamicModelCatalog {
        val merged = catalog.models.toMutableList()

        // 1. 应用服务端 models.json 中的 providers.<prov>.models 与 providers.<prov>.modelOverrides 配置
        if (serverConfig != null && serverConfig.has("providers") && serverConfig.get("providers").isJsonObject) {
            val providersObj = serverConfig.getAsJsonObject("providers")
            for ((provName, provElem) in providersObj.entrySet()) {
                if (!provElem.isJsonObject) continue
                val pObj = provElem.asJsonObject
                val provBaseUrl = pObj.get("baseUrl")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()
                val provApi = pObj.get("api")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()

                // 为该提供商下的所有现有模型补充 baseUrl / apiType 元信息，方便编辑弹窗回填
                if (!provBaseUrl.isNullOrBlank() || !provApi.isNullOrBlank()) {
                    for (i in merged.indices) {
                        if (merged[i].provider.equals(provName, ignoreCase = true)) {
                            merged[i] = merged[i].copy(
                                baseUrl = provBaseUrl ?: merged[i].baseUrl,
                                apiType = provApi ?: merged[i].apiType
                            )
                        }
                    }
                }

                // 1a. 处理自定义提供商的 models 数组
                val mArr = pObj.get("models")?.takeIf { it.isJsonArray }?.asJsonArray
                if (mArr != null) {
                    for (mElem in mArr) {
                        if (!mElem.isJsonObject) continue
                        val mObj = mElem.asJsonObject
                        val mId = mObj.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                        val mName = mObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: mId
                        val ctxWin = mObj.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong
                        val maxTok = mObj.get("maxTokens")?.takeIf { !it.isJsonNull }?.asLong
                        val reasoning = mObj.get("reasoning")?.takeIf { !it.isJsonNull }?.asBoolean
                        val inputList = mObj.get("input")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                            runCatching { it.asString }.getOrNull()
                        }
                        val idx = merged.indexOfFirst {
                            it.provider.equals(provName, ignoreCase = true) && it.id.equals(mId, ignoreCase = true)
                        }.takeIf { it >= 0 } ?: merged.indexOfFirst { it.id.equals(mId, ignoreCase = true) }

                        if (idx >= 0) {
                            val old = merged[idx]
                            val newInputs = inputList ?: old.input
                            merged[idx] = old.copy(
                                name = mName.ifBlank { old.name },
                                contextWindow = ctxWin ?: old.contextWindow,
                                maxTokens = maxTok ?: old.maxTokens,
                                supportsThinking = reasoning ?: old.supportsThinking,
                                supportsVision = if (inputList != null) inputList.contains("image") else old.supportsVision,
                                input = newInputs,
                                baseUrl = provBaseUrl ?: old.baseUrl,
                                apiType = provApi ?: old.apiType
                            )
                        } else {
                            val inputs = inputList ?: listOf("text")
                            merged.add(
                                ModelInfo(
                                    id = mId,
                                    name = mName,
                                    provider = provName,
                                    contextWindow = ctxWin ?: 200000L,
                                    maxTokens = maxTok ?: 8192L,
                                    supportsThinking = reasoning ?: true,
                                    supportsVision = inputs.contains("image"),
                                    input = inputs,
                                    baseUrl = provBaseUrl,
                                    apiType = provApi
                                )
                            )
                        }
                    }
                }

                // 1b. 处理 pi-coding-agent 标准 modelOverrides 字典 (无损覆盖内置或扩展模型参数)
                val ovObj = pObj.get("modelOverrides")?.takeIf { it.isJsonObject }?.asJsonObject
                if (ovObj != null) {
                    for ((mIdKey, ovElem) in ovObj.entrySet()) {
                        if (!ovElem.isJsonObject) continue
                        val mObj = ovElem.asJsonObject
                        val mId = mIdKey.trim()
                        val mName = mObj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim()
                        val ctxWin = mObj.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong
                        val maxTok = mObj.get("maxTokens")?.takeIf { !it.isJsonNull }?.asLong
                        val reasoning = mObj.get("reasoning")?.takeIf { !it.isJsonNull }?.asBoolean
                        val inputList = mObj.get("input")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull {
                            runCatching { it.asString }.getOrNull()
                        }
                        val idx = merged.indexOfFirst {
                            it.provider.equals(provName, ignoreCase = true) && it.id.equals(mId, ignoreCase = true)
                        }.takeIf { it >= 0 } ?: merged.indexOfFirst { it.id.equals(mId, ignoreCase = true) }

                        if (idx >= 0) {
                            val old = merged[idx]
                            val newInputs = inputList ?: old.input
                            merged[idx] = old.copy(
                                name = if (!mName.isNullOrBlank()) mName else old.name,
                                contextWindow = ctxWin ?: old.contextWindow,
                                maxTokens = maxTok ?: old.maxTokens,
                                supportsThinking = reasoning ?: old.supportsThinking,
                                supportsVision = if (inputList != null) inputList.contains("image") else old.supportsVision,
                                input = newInputs,
                                baseUrl = provBaseUrl ?: old.baseUrl,
                                apiType = provApi ?: old.apiType
                            )
                        }
                    }
                }
            }
        }

        // 2. 再应用用户在 App 内保存的持久化覆盖配置（确保即使修改内置模型参数也 100% 永久生效）
        val localOverrides = prefs.getModelOverrides()
        for ((_, ov) in localOverrides) {
            val idx = merged.indexOfFirst {
                it.provider.equals(ov.provider, ignoreCase = true) && it.id.equals(ov.modelId, ignoreCase = true)
            }.takeIf { it >= 0 } ?: merged.indexOfFirst { it.id.equals(ov.modelId, ignoreCase = true) }

            val inputs = if (ov.supportsVision) listOf("text", "image") else listOf("text")
            if (idx >= 0) {
                val old = merged[idx]
                merged[idx] = old.copy(
                    provider = ov.provider.ifBlank { old.provider },
                    name = ov.name.ifBlank { old.name },
                    contextWindow = ov.contextWindow,
                    maxTokens = ov.maxTokens,
                    supportsThinking = ov.reasoning,
                    supportsVision = ov.supportsVision,
                    input = inputs,
                    baseUrl = ov.baseUrl.takeIf { it.isNotBlank() } ?: old.baseUrl,
                    apiType = ov.apiType.takeIf { it.isNotBlank() } ?: old.apiType
                )
            } else if (ov.baseUrl.isNotBlank()) {
                merged.add(
                    ModelInfo(
                        id = ov.modelId,
                        name = ov.name.ifBlank { ov.modelId },
                        provider = ov.provider,
                        contextWindow = ov.contextWindow,
                        maxTokens = ov.maxTokens,
                        supportsThinking = ov.reasoning,
                        supportsVision = ov.supportsVision,
                        input = inputs,
                        baseUrl = ov.baseUrl,
                        apiType = ov.apiType
                    )
                )
            }
        }

        return catalog.copy(models = merged)
    }

    // ==========================================
    // 4. 命令发送与交互动作 (1:1 匹配 pi-web)
    // ==========================================

    suspend fun sendCommand(sessionId: String, prompt: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "message" to prompt,
            "text" to prompt
        )
        if (images.isNotEmpty()) {
            payload["images"] = images
        }
        sendAgentAction(sessionId, payload)
    }

    suspend fun sendSteer(sessionId: String, text: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "streamingBehavior" to "steer",
            "message" to text,
            "text" to text
        )
        if (images.isNotEmpty()) {
            payload["images"] = images
        }
        sendAgentAction(sessionId, payload)
    }

    suspend fun sendFollowUp(sessionId: String, text: String, images: List<String> = emptyList()): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "streamingBehavior" to "followUp",
            "message" to text,
            "text" to text
        )
        if (images.isNotEmpty()) {
            payload["images"] = images
        }
        sendAgentAction(sessionId, payload)
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
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Result.failure(IOException("Server HTTP ${response.code}: $errBody"))
                } else {
                    Result.success(true)
                }
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

    private fun sanitizeCwd(cwd: String): String {
        val trimmed = cwd.trim()
        if (trimmed.isBlank() || trimmed == "默认工作区" || trimmed == "全部工作区") return ""
        if (!trimmed.contains('/') && !trimmed.contains('\\')) return ""
        return trimmed
    }

    // ==========================================
    // 5. 技能 (GET /api/skills)
    // ==========================================

    suspend fun getSkills(cwd: String = ""): Result<List<SkillInfo>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(getDefaultSkills())
        val validCwd = sanitizeCwd(cwd)
        val urlsToTry = if (validCwd.isNotBlank()) {
            val encodedCwd = URLEncoder.encode(validCwd, "UTF-8")
            listOf("$baseUrl/api/skills?cwd=$encodedCwd", "$baseUrl/api/skills")
        } else {
            listOf("$baseUrl/api/skills")
        }

        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).get().build()
                val parsedList = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: "{}"
                    val jsonEl = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return@use null
                    val array = when {
                        jsonEl.isJsonArray -> jsonEl.asJsonArray
                        jsonEl.isJsonObject && jsonEl.asJsonObject.has("skills") && jsonEl.asJsonObject.get("skills").isJsonArray ->
                            jsonEl.asJsonObject.getAsJsonArray("skills")
                        jsonEl.isJsonObject && jsonEl.asJsonObject.has("items") && jsonEl.asJsonObject.get("items").isJsonArray ->
                            jsonEl.asJsonObject.getAsJsonArray("items")
                        else -> null
                    } ?: return@use null

                    val list = mutableListOf<SkillInfo>()
                    for (item in array) {
                        if (!item.isJsonObject) continue
                        val obj = item.asJsonObject
                        val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("id")?.takeIf { !it.isJsonNull }?.asString
                            ?: continue
                        val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
                        val baseDir = obj.get("baseDir")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("dir")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("directory")?.takeIf { !it.isJsonNull }?.asString
                        val rawFilePath = obj.get("filePath")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("path")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("skillPath")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("location")?.takeIf { !it.isJsonNull }?.asString
                        val resolvedFilePath = rawFilePath?.takeIf { it.isNotBlank() }
                            ?: baseDir?.takeIf { it.isNotBlank() }?.let { dir ->
                                val sep = if (dir.contains('\\')) "\\" else "/"
                                dir.trimEnd('/', '\\') + sep + "SKILL.md"
                            }
                        val inlineContent = obj.get("content")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("markdown")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("rawContent")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj.get("body")?.takeIf { !it.isJsonNull }?.asString
                        val disabled = obj.get("disableModelInvocation")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: obj.get("disabled")?.takeIf { !it.isJsonNull }?.asBoolean
                            ?: false
                        val scopeStr = obj.get("scope")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val isGlobal = scopeStr.equals("global", true) ||
                            scopeStr.equals("user", true) ||
                            resolvedFilePath?.contains(".pi", true) == true ||
                            resolvedFilePath?.contains("global", true) == true
                        list.add(
                            SkillInfo(
                                name = name,
                                description = description,
                                filePath = resolvedFilePath,
                                baseDir = baseDir,
                                enabled = !disabled,
                                category = if (isGlobal) "全局技能" else "工作区技能",
                                disableModelInvocation = disabled,
                                content = inlineContent
                            )
                        )
                    }
                    list
                }
                if (!parsedList.isNullOrEmpty()) {
                    return@withContext Result.success(parsedList)
                }
            } catch (_: Exception) {
            }
        }
        Result.success(getDefaultSkills())
    }

    // ==========================================
    // 6. 插件扩展 (GET /api/plugins)
    // ==========================================

    suspend fun getPlugins(cwd: String = ""): Result<List<PluginPackage>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(getDefaultPlugins())
        val validCwd = sanitizeCwd(cwd)
        val urlsToTry = if (validCwd.isNotBlank()) {
            val encodedCwd = URLEncoder.encode(validCwd, "UTF-8")
            listOf("$baseUrl/api/plugins?cwd=$encodedCwd", "$baseUrl/api/plugins")
        } else {
            listOf("$baseUrl/api/plugins")
        }

        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).get().build()
                val parsedList = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use null
                    val list = mutableListOf<PluginPackage>()
                    if (json.has("packages") && json.get("packages").isJsonArray) {
                        val array = json.getAsJsonArray("packages")
                        for (item in array) {
                            if (!item.isJsonObject) continue
                            val obj = item.asJsonObject
                            val packageName = obj.get("packageName")?.takeIf { !it.isJsonNull }?.asString ?: "plugin"
                            val version = obj.get("version")?.takeIf { !it.isJsonNull }?.asString ?: "1.0.0"
                            val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
                            val source = obj.get("source")?.takeIf { !it.isJsonNull }?.asString
                            val scope = obj.get("scope")?.takeIf { !it.isJsonNull }?.asString ?: "global"
                            val status = obj.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "loaded"
                            val disabled = obj.get("disabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false

                            list.add(
                                PluginPackage(
                                    packageName = packageName,
                                    version = version,
                                    description = description,
                                    source = source,
                                    scope = scope,
                                    status = status,
                                    disabled = disabled
                                )
                            )
                        }
                    }
                    list
                }
                if (!parsedList.isNullOrEmpty()) {
                    return@withContext Result.success(parsedList)
                }
            } catch (_: Exception) {
            }
        }
        Result.success(getDefaultPlugins())
    }

    // ==========================================
    // 7. MCP 协议 (GET /api/mcp)
    // ==========================================

    suspend fun getMcpServers(cwd: String = ""): Result<Pair<McpCodeModeConfig, List<McpServerInfo>>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(Pair(McpCodeModeConfig(), getDefaultMcpServers()))
        val validCwd = sanitizeCwd(cwd)
        val urlsToTry = if (validCwd.isNotBlank()) {
            val encodedCwd = URLEncoder.encode(validCwd, "UTF-8")
            listOf("$baseUrl/api/mcp?cwd=$encodedCwd", "$baseUrl/api/mcp")
        } else {
            listOf("$baseUrl/api/mcp")
        }

        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).get().build()
                val resultPair = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use null

                    var codeMode = "automatic"
                    var budget = 3000
                    if (json.has("codemode") && json.get("codemode").isJsonObject) {
                        val cm = json.getAsJsonObject("codemode")
                        codeMode = cm.get("preference")?.takeIf { !it.isJsonNull }?.asString ?: "automatic"
                        if (cm.has("inlineBudget") && cm.get("inlineBudget").isJsonObject) {
                            budget = cm.getAsJsonObject("inlineBudget").get("default")?.takeIf { !it.isJsonNull }?.asInt ?: 3000
                        }
                    }
                    val config = McpCodeModeConfig(mode = codeMode, scriptMode = true, tokenBudget = budget)

                    val servers = mutableListOf<McpServerInfo>()
                    if (json.has("servers") && json.get("servers").isJsonArray) {
                        val array = json.getAsJsonArray("servers")
                        for (item in array) {
                            if (!item.isJsonObject) continue
                            val obj = item.asJsonObject
                            val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString
                            val rawTransport = obj.get("transport")?.takeIf { !it.isJsonNull }?.asString
                            val cmd = obj.get("command")?.takeIf { !it.isJsonNull }?.asString
                            val transport = when {
                                rawTransport?.equals("stdio", ignoreCase = true) == true -> "stdio"
                                rawTransport != null -> "http"
                                !cmd.isNullOrBlank() -> "stdio"
                                else -> "http"
                            }
                            val urlVal = obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: run {
                                val args = if (obj.has("args") && obj.get("args").isJsonArray) {
                                    obj.getAsJsonArray("args").mapNotNull { runCatching { it.asString }.getOrNull() }.joinToString(" ")
                                } else ""
                                if (!cmd.isNullOrBlank()) "$cmd $args".trim() else null
                            }
                            val enabled = obj.get("enabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                            val count = obj.get("toolExposureCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                            val state = if (obj.has("status") && obj.get("status").isJsonObject) {
                                obj.getAsJsonObject("status")?.get("state")?.takeIf { !it.isJsonNull }?.asString
                            } else "connected"

                            servers.add(
                                McpServerInfo(
                                    name = name,
                                    description = description,
                                    transport = transport,
                                    url = urlVal,
                                    enabled = enabled,
                                    toolExposureCount = count,
                                    statusObj = McpServerStatus(state)
                                )
                            )
                        }
                    }
                    Pair(config, servers)
                }
                if (resultPair != null && resultPair.second.isNotEmpty()) {
                    return@withContext Result.success(resultPair)
                }
            } catch (_: Exception) {
            }
        }
        Result.success(Pair(McpCodeModeConfig(), getDefaultMcpServers()))
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

    private fun getBuiltInSkillMarkdown(skillName: String, description: String?): String {
        return when (skillName) {
            "hindsight-coding-agent" -> """
                ---
                name: hindsight-coding-agent
                description: Hindsight 长效记忆与反思决策系统，自动检索历史架构决策并在任务完成后沉淀经验。
                ---

                # Hindsight 长效记忆与反思决策系统

                ## 适用场景 (When to Use)
                - 当需要回顾当前工作区过往的架构选型、踩坑记录或历史修复方案时。
                - 当遇到重复出现的编译错误、环境依赖冲突或跨模块接口变更时。
                - 在完成复杂重构任务后，需要将关键技术决策沉淀至长期记忆库时。

                ## 核心工作流 (Workflow)
                1. **前置检索 (`recall`)**：
                   - 在修改核心架构文件前，先检索与当前模块相关的历史记忆条目。
                   - 优先遵循已验证的工程约束，避免重复引入曾被废弃的实现方案。
                2. **执行期对比 (`reflect`)**：
                   - 当工具调用或构建失败超过 1 次时，结合历史失败模式快速定位根因。
                3. **经验归档 (`retain`)**：
                   - 任务验证通过后，将新增的配置约定、关键修复路径以精简摘要写入长效记忆。
            """.trimIndent()

            "context-mode" -> """
                ---
                name: context-mode
                description: 智能上下文隔离与代码语义压缩模式，优先拉取符号签名与局部片段以降低 Token 消耗。
                ---

                # 智能上下文隔离与代码语义压缩模式 (Context Mode)

                ## 适用场景 (When to Use)
                - 处理大型代码仓库（单文件超过 500 行或涉及多模块调用链）时。
                - 当前会话上下文占用率超过 60%，需要精细控制每次文件读取范围时。

                ## 核心指令与规范 (Guidelines)
                1. **精准切片读取**：
                   - 优先通过 `grep` 定位目标函数或类的行号区间，再按需读取对应行范围，避免整文件盲读。
                2. **工具输出降噪**：
                   - 执行构建或日志查询命令时，优先过滤关键错误行，避免将冗长无关日志注入主上下文。
                3. **增量编辑优先**：
                   - 修改代码时仅替换最小必要的连续代码块，保持周围上下文稳定。
            """.trimIndent()

            "terminal-controller" -> """
                ---
                name: terminal-controller
                description: 安全沙箱终端交互与批处理执行器，规范后台进程管理、超时控制与高危命令审批。
                ---

                # 安全沙箱终端交互与批处理执行器 (Terminal Controller)

                ## 适用场景 (When to Use)
                - 需要执行构建编译、单元测试、依赖检查或脚本自动化任务时。
                - 需要启动或管理后台常驻服务进程时。

                ## 执行守则 (Execution Rules)
                1. **非交互式优先**：
                   - 所有终端命令必须附带非交互参数（如 `-y`、`--no-pager`），防止进程挂起等待标准输入。
                2. **工作目录约束**：
                   - 显式指定命令执行的工作目录 (`cwd`)，禁止依赖隐式 `cd` 状态。
                3. **安全边界**：
                   - 涉及删除文件、重置仓库或修改系统级配置的操作，必须先经过安全确认。
            """.trimIndent()

            "ui-plugin-navigation" -> """
                ---
                name: ui-plugin-navigation
                description: UI 插件面板与生成式交互看板导航，支持富组件状态展示与多端布局自适应。
                ---

                # UI 插件面板与生成式交互看板导航

                ## 适用场景 (When to Use)
                - 构建或调整多端自适应界面、配置弹窗、状态监控看板与可交互卡片时。

                ## 设计与实现规范 (Design Rules)
                1. **高信息密度与清晰层级**：
                   - 采用统一的圆角卡片、清晰的色彩语义（运行中、已完成、告警）与单行防折行标签。
                2. **响应式布局**：
                   - 表单输入框标签保持简短不折行，长示例文本放入 `placeholder` 占位提示中。
                3. **状态即时反馈**：
                   - 所有配置保存、开关切换与远程同步操作均需提供即时视觉反馈。
            """.trimIndent()

            else -> """
                ---
                name: $skillName
                description: ${description ?: "自定义智能体技能指令包"}
                ---

                # $skillName

                ## 技能说明
                ${description ?: "在此编写该技能的核心职责、触发条件与执行规范。"}

                ## 触发条件 (When to Use)
                - 当用户请求涉及本技能相关领域的分析、编码或工具调用时自动加载。

                ## 核心执行指令 (Instructions)
                1. 分析当前任务上下文与目标路径。
                2. 按步骤执行最小化验证与变更。
                3. 输出清晰、可验证的执行结果。
            """.trimIndent()
        }
    }

    private fun getDefaultSkills() = listOf(
        SkillInfo(
            name = "hindsight-coding-agent",
            description = "Hindsight 长效记忆与反思决策系统",
            alias = "记忆反思",
            filePath = "~/.pi/agent/skills/hindsight-coding-agent/SKILL.md",
            baseDir = "~/.pi/agent/skills/hindsight-coding-agent",
            enabled = true,
            category = "全局技能",
            content = getBuiltInSkillMarkdown("hindsight-coding-agent", "Hindsight 长效记忆与反思决策系统")
        ),
        SkillInfo(
            name = "context-mode",
            description = "智能上下文隔离与代码语义压缩模式",
            alias = "上下文节能",
            filePath = "~/.pi/agent/skills/context-mode/SKILL.md",
            baseDir = "~/.pi/agent/skills/context-mode",
            enabled = true,
            category = "全局技能",
            content = getBuiltInSkillMarkdown("context-mode", "智能上下文隔离与代码语义压缩模式")
        ),
        SkillInfo(
            name = "terminal-controller",
            description = "安全沙箱终端交互与批处理执行器",
            alias = "沙箱终端",
            filePath = "~/.pi/agent/skills/terminal-controller/SKILL.md",
            baseDir = "~/.pi/agent/skills/terminal-controller",
            enabled = true,
            category = "全局技能",
            content = getBuiltInSkillMarkdown("terminal-controller", "安全沙箱终端交互与批处理执行器")
        ),
        SkillInfo(
            name = "ui-plugin-navigation",
            description = "UI 插件面板与生成式交互看板导航",
            alias = "UI看板",
            filePath = "~/.pi/agent/skills/ui-plugin-navigation/SKILL.md",
            baseDir = "~/.pi/agent/skills/ui-plugin-navigation",
            enabled = true,
            category = "全局技能",
            content = getBuiltInSkillMarkdown("ui-plugin-navigation", "UI 插件面板与生成式交互看板导航")
        )
    )

    private fun getDefaultPlugins() = listOf(
        PluginPackage("pi-smart-compact", "9.3.1", "智能上下文压缩扩展，具备验证与安全检查机制", "npm:pi-smart-compact@9.3.1"),
        PluginPackage("pi-generative-ui", "2.1.0", "生成式 UI 与富交互微组件渲染支持", "npm:pi-generative-ui@2.1.0")
    )

    private fun getDefaultMcpServers() = listOf(
        McpServerInfo(
            name = "hindsight",
            description = "Hindsight long-term memory system for recalling past decisions",
            transport = "http",
            url = "http://127.0.0.1:8888/mcp",
            enabled = true,
            toolExposureCount = 4,
            statusObj = McpServerStatus("connected"),
            scope = "global"
        ),
        McpServerInfo(
            name = "wren",
            description = "Wren 语义模型与分析层协议",
            transport = "http",
            url = "http://127.0.0.1:9090/mcp",
            enabled = true,
            toolExposureCount = 6,
            statusObj = McpServerStatus("connected"),
            scope = "global"
        )
    )

    suspend fun getAgentRunningState(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("Server URL is empty"))
        try {
            val req = Request.Builder().url("$baseUrl/api/sessions").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to query session running state: ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val jsonElement = JsonParser.parseString(body)
                if (jsonElement.isJsonObject) {
                    val obj = jsonElement.asJsonObject
                    if (obj.has("runningSessionIds") && obj.get("runningSessionIds").isJsonArray) {
                        val runningIds = obj.getAsJsonArray("runningSessionIds")
                            .mapNotNull { runCatching { it.asString }.getOrNull() }
                            .toSet()
                        if (runningIds.contains(sessionId)) {
                            return@withContext Result.success(true)
                        }
                    }
                    if (obj.has("sessions") && obj.get("sessions").isJsonArray) {
                        val sessionsArr = obj.getAsJsonArray("sessions")
                        for (el in sessionsArr) {
                            if (el.isJsonObject) {
                                val sObj = el.asJsonObject
                                val id = sObj.get("id")?.takeIf { !it.isJsonNull }?.asString
                                if (id == sessionId) {
                                    val active = sObj.get("active")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    val isRunning = sObj.get("isRunning")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    val isStreaming = sObj.get("isStreaming")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    return@withContext Result.success(active || isRunning || isStreaming)
                                }
                            }
                        }
                    }
                    if (obj.has("runningSessionIds")) {
                        return@withContext Result.success(false)
                    }
                }
                Result.failure(Exception("Unrecognized running state payload"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 8. 对标 pi-web 的模型、提供商、技能、MCP、插件可编辑管理接口
    // ==========================================

    /**
     * 刷新网关模型目录 (POST /api/models/refresh)
     */
    suspend fun refreshModelsCatalog(): Result<DynamicModelCatalog> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext getDynamicModels()
        try {
            val emptyBody = "{}".toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/models/refresh").post(emptyBody).build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
        getDynamicModels()
    }

    /**
     * 配置指定 Provider 的 API Key (POST /api/auth/api-key/{provider})
     */
    suspend fun setProviderApiKey(provider: String, apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val encodedProvider = URLEncoder.encode(provider.trim(), "UTF-8")
            val body = gson.toJson(mapOf("apiKey" to apiKey.trim())).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/auth/api-key/$encodedProvider").post(body).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException("配置 API Key 失败 (${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 新增或编辑自定义模型与提供商配置 (GET & PUT /api/models-config + POST /api/models/refresh)
     */
    /**
     * 新增或编辑模型与提供商配置 (1:1 严格对齐 pi-coding-agent / pi-web models.schema.json 规范)：
     * - 当编辑内置/现有提供商的模型参数（未提供新的自定义 baseUrl 且该 provider 在 models.json 中无自定义 models 列表）时，
     *   使用官方无损 `providers.<provider>.modelOverrides.<modelId>`，绝不写入单元素的 `models: [...]` 数组，防止清空同厂商其他模型或因缺少 baseUrl 导致 pi 校验失败！
     * - 当编辑已有的自定义提供商模型（该 provider 在 models.json 中已有 baseUrl 与 models 数组）或新增自定义提供商模型时，
     *   仅在原地增量更新/追加目标模型，100% 保留该提供商下其他模型以及所有其他提供商配置！
     */
    suspend fun upsertCustomProviderModel(
        providerName: String,
        baseUrlEndpoint: String,
        apiKey: String,
        apiType: String = "openai-completions",
        modelId: String,
        modelName: String,
        contextWindow: Long = 128000L,
        maxTokens: Long = 16384L,
        reasoning: Boolean = true,
        supportsVision: Boolean = false
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val pKeyInput = providerName.trim()
        val mId = modelId.trim()
        val cleanBaseUrl = baseUrlEndpoint.trim()
        val cleanApiKey = apiKey.trim()
        val cleanApiType = apiType.trim()

        // 0. 立即持久化到本地覆盖配置表，确保无论是内置提供商模型还是自定义提供商模型，重进设置页都 100% 保持最新参数
        prefs.saveModelOverride(
            AppPreferences.StoredModelOverride(
                provider = pKeyInput,
                modelId = mId,
                name = modelName.trim().ifBlank { mId },
                baseUrl = cleanBaseUrl,
                apiType = if (cleanBaseUrl.isNotBlank()) cleanApiType else "",
                contextWindow = contextWindow,
                maxTokens = maxTokens,
                reasoning = reasoning,
                supportsVision = supportsVision
            )
        )

        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            // 1. 先安全读取当前 models.json 配置；若网关读取失败，绝不盲目用空对象覆盖服务端文件！
            val fetched = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = false)
                ?: return@withContext Result.success(true) // 本地已保存覆盖，避免网络异常时覆盖服务端配置

            val rootConfig = fetched.config
            val providersObj = if (rootConfig.has("providers") && rootConfig.get("providers").isJsonObject) {
                rootConfig.getAsJsonObject("providers")
            } else {
                JsonObject().also { rootConfig.add("providers", it) }
            }

            // 大小写不敏感匹配已有 provider key，防止产生大小写重复节点
            val actualProviderKey = providersObj.keySet().firstOrNull { it.equals(pKeyInput, ignoreCase = true) } ?: pKeyInput
            val providerObj = if (providersObj.has(actualProviderKey) && providersObj.get(actualProviderKey).isJsonObject) {
                providersObj.getAsJsonObject(actualProviderKey)
            } else {
                JsonObject().also { providersObj.add(actualProviderKey, it) }
            }

            val existingBaseUrl = providerObj.get("baseUrl")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim() ?: ""
            val hasExistingCustomModelsArray = providerObj.has("models") && providerObj.get("models").isJsonArray && existingBaseUrl.isNotBlank()

            if (cleanBaseUrl.isNotBlank()) {
                providerObj.addProperty("baseUrl", cleanBaseUrl)
            }
            if (cleanApiKey.isNotBlank()) {
                providerObj.addProperty("apiKey", cleanApiKey)
            }
            if (cleanApiType.isNotBlank() && cleanBaseUrl.isNotBlank()) {
                providerObj.addProperty("api", cleanApiType)
            }

            val inputArr = JsonArray().apply {
                add("text")
                if (supportsVision) add("image")
            }

            val effectiveBaseUrl = cleanBaseUrl.ifBlank { existingBaseUrl }

            if (hasExistingCustomModelsArray || cleanBaseUrl.isNotBlank()) {
                // 场景 A：自定义提供商（拥有有效 baseUrl）— 在 models 数组中无损原地更新或追加该模型，保留所有其它模型与其它字段
                if (!providerObj.has("api") && cleanApiType.isNotBlank()) {
                    providerObj.addProperty("api", cleanApiType)
                }
                val modelsArr = if (providerObj.has("models") && providerObj.get("models").isJsonArray) {
                    providerObj.getAsJsonArray("models")
                } else {
                    JsonArray().also { providerObj.add("models", it) }
                }

                var replaced = false
                for (i in 0 until modelsArr.size()) {
                    val elem = modelsArr.get(i)
                    if (elem.isJsonObject && elem.asJsonObject.get("id")?.asString.equals(mId, ignoreCase = true)) {
                        val existingModelObj = elem.asJsonObject.deepCopy()
                        existingModelObj.addProperty("id", mId)
                        existingModelObj.addProperty("name", modelName.trim().ifBlank { mId })
                        existingModelObj.addProperty("contextWindow", contextWindow)
                        existingModelObj.addProperty("maxTokens", maxTokens)
                        existingModelObj.addProperty("reasoning", reasoning)
                        existingModelObj.add("input", inputArr)
                        modelsArr.set(i, existingModelObj)
                        replaced = true
                        break
                    }
                }
                if (!replaced) {
                    val newModelObj = JsonObject().apply {
                        addProperty("id", mId)
                        addProperty("name", modelName.trim().ifBlank { mId })
                        addProperty("contextWindow", contextWindow)
                        addProperty("maxTokens", maxTokens)
                        addProperty("reasoning", reasoning)
                        add("input", inputArr)
                    }
                    modelsArr.add(newModelObj)
                }
            } else if (effectiveBaseUrl.isBlank()) {
                // 场景 B：内置提供商（如 google / gemini / anthropic / openai 等，在 models.json 中无自定义 baseUrl）
                // 严格使用 pi-coding-agent 的 `modelOverrides` 机制覆盖该模型参数，绝不写入 `models` 数组！
                val overridesObj = if (providerObj.has("modelOverrides") && providerObj.get("modelOverrides").isJsonObject) {
                    providerObj.getAsJsonObject("modelOverrides")
                } else {
                    JsonObject().also { providerObj.add("modelOverrides", it) }
                }
                val actualModelKey = overridesObj.keySet().firstOrNull { it.equals(mId, ignoreCase = true) } ?: mId
                val ovModelObj = if (overridesObj.has(actualModelKey) && overridesObj.get(actualModelKey).isJsonObject) {
                    overridesObj.getAsJsonObject(actualModelKey)
                } else {
                    JsonObject().also { overridesObj.add(actualModelKey, it) }
                }
                ovModelObj.addProperty("name", modelName.trim().ifBlank { mId })
                ovModelObj.addProperty("contextWindow", contextWindow)
                ovModelObj.addProperty("maxTokens", maxTokens)
                ovModelObj.addProperty("reasoning", reasoning)
                ovModelObj.add("input", inputArr)
            }

            // 写入前再次执行自愈校验，确保生成 100% 合法的 models.json
            sanitizeAndHealModelsConfig(rootConfig)

            val savedOk = putModelsConfigToServer(baseUrl, rootConfig, fetched.wrappedInConfigKey)
            if (!savedOk) {
                return@withContext Result.failure(IOException("保存模型配置至网关失败"))
            }

            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 切换技能启用/禁用状态 (PATCH /api/skills)
     */
    suspend fun toggleSkillOnServer(filePath: String?, disableModelInvocation: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank() || filePath.isNullOrBlank()) return@withContext Result.success(true)
        try {
            val payload = mapOf(
                "filePath" to filePath,
                "disableModelInvocation" to disableModelInvocation
            )
            val req = Request.Builder()
                .url("$baseUrl/api/skills")
                .patch(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读取技能完整 SKILL.md 文档内容 (多策略兼容 pi-web /api/skills/content 与伴侣网关 + 本地持久化覆盖)
     */
    suspend fun getSkillContent(skill: SkillInfo, cwd: String = ""): Result<String> = withContext(Dispatchers.IO) {
        // 1. 若用户此前在 App 内编辑并保存过该技能的 SKILL.md，则优先返回用户保存的最新源码
        prefs.getSkillContentOverride(skill.name)?.let { saved ->
            return@withContext Result.success(saved)
        }

        val baseUrl = prefs.serverUrl
        val validCwd = sanitizeCwd(cwd)

        if (baseUrl.isNotBlank()) {
            // 2. 若当前 skill 缺少 filePath，先尝试从服务端重新拉取一次真实技能列表匹配 filePath / baseDir / content
            var resolvedPath = skill.filePath?.takeIf { it.isNotBlank() && !it.startsWith("~/.pi/agent/skills/") } ?: skill.filePath
            var resolvedBaseDir = skill.baseDir
            if (resolvedPath.isNullOrBlank() || resolvedPath.startsWith("~/.pi/agent/skills/")) {
                runCatching {
                    val freshSkills = getSkills(validCwd).getOrNull()
                    val matched = freshSkills?.firstOrNull { it.name.equals(skill.name, ignoreCase = true) }
                    if (matched != null) {
                        if (!matched.content.isNullOrBlank()) {
                            return@withContext Result.success(matched.content)
                        }
                        if (!matched.filePath.isNullOrBlank()) resolvedPath = matched.filePath
                        if (!matched.baseDir.isNullOrBlank()) resolvedBaseDir = matched.baseDir
                    }
                }
            }

            // 3. 多路由、多参数兼容请求网关与 pi-web 的 /api/skills/content
            val encName = URLEncoder.encode(skill.name, "UTF-8")
            val encPath = URLEncoder.encode(resolvedPath ?: "", "UTF-8")
            val encBaseDir = URLEncoder.encode(resolvedBaseDir ?: "", "UTF-8")
            val encCwd = URLEncoder.encode(validCwd, "UTF-8")

            val candidateUrls = buildList {
                if (!resolvedPath.isNullOrBlank()) {
                    if (validCwd.isNotBlank()) {
                        add("$baseUrl/api/skills/content?filePath=$encPath&path=$encPath&name=$encName&skill=$encName&cwd=$encCwd")
                    }
                    add("$baseUrl/api/skills/content?filePath=$encPath&path=$encPath&name=$encName&skill=$encName")
                    add("$baseUrl/api/skills/content?filePath=$encPath&name=$encName&document=SKILL.md")
                }
                if (!resolvedBaseDir.isNullOrBlank()) {
                    add("$baseUrl/api/skills/content?baseDir=$encBaseDir&name=$encName&skill=$encName")
                }
                if (validCwd.isNotBlank()) {
                    add("$baseUrl/api/skills/content?name=$encName&skill=$encName&skillName=$encName&cwd=$encCwd")
                }
                add("$baseUrl/api/skills/content?name=$encName&skill=$encName&skillName=$encName")
                if (!resolvedPath.isNullOrBlank()) {
                    add("$baseUrl/api/agent/skills/content?filePath=$encPath&name=$encName")
                }
            }.distinct()

            for (url in candidateUrls) {
                try {
                    val req = Request.Builder().url(url).get().build()
                    val loadedText = client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val body = resp.body?.string()?.trim() ?: return@use null
                        if (body.isEmpty()) return@use null
                        extractSkillMarkdownFromBody(body)
                    }
                    if (!loadedText.isNullOrBlank()) {
                        return@withContext Result.success(loadedText)
                    }
                } catch (_: Exception) {
                }
            }
        }

        // 4. 若服务端已经内联携带 content 或属于内置技能包，则返回完整的 SKILL.md 源码模板
        val fallback = skill.content?.takeIf { it.isNotBlank() }
            ?: getBuiltInSkillMarkdown(skill.name, skill.description)
        Result.success(fallback)
    }

    private fun extractSkillMarkdownFromBody(body: String): String? {
        if (!body.startsWith("{") && !body.startsWith("[")) {
            return if (!body.startsWith("<!DOCTYPE", ignoreCase = true) && !body.startsWith("<html", ignoreCase = true)) {
                body
            } else null
        }
        return runCatching {
            val json = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
            val directKeys = listOf("content", "markdown", "rawContent", "raw", "body", "text", "source")
            for (k in directKeys) {
                val v = json.get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                if (!v.isNullOrBlank()) return@runCatching v
            }
            for (nestedKey in listOf("skill", "data", "document")) {
                if (json.has(nestedKey) && json.get(nestedKey).isJsonObject) {
                    val sub = json.getAsJsonObject(nestedKey)
                    for (k in directKeys) {
                        val v = sub.get(k)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                        if (!v.isNullOrBlank()) return@runCatching v
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * 安装新技能包 (POST /api/skills/install)
     */
    suspend fun installSkill(packageSource: String, scope: String = "global", cwd: String = ""): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = mapOf(
                "package" to packageSource.trim(),
                "scope" to scope,
                "cwd" to cwd
            )
            val req = Request.Builder()
                .url("$baseUrl/api/skills/install")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(IOException("安装技能失败 (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 管理 MCP 服务器：新增、启用/禁用、移除 (POST /api/mcp)
     */
    suspend fun addMcpServer(
        name: String,
        transport: String,
        endpointOrCommand: String,
        description: String = "",
        scope: String = "user",
        cwd: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val serverCfg = mutableMapOf<String, Any>(
                "transport" to transport,
                "enabled" to true
            )
            if (description.isNotBlank()) serverCfg["description"] = description.trim()
            if (transport == "stdio") {
                val parts = endpointOrCommand.trim().split(Regex("\\s+"))
                serverCfg["command"] = parts.firstOrNull() ?: endpointOrCommand.trim()
                if (parts.size > 1) serverCfg["args"] = parts.drop(1)
            } else {
                serverCfg["url"] = endpointOrCommand.trim()
            }
            val payload = mapOf(
                "action" to "add",
                "scope" to scope,
                "name" to name.trim(),
                "cwd" to cwd,
                "config" to serverCfg
            )
            val req = Request.Builder()
                .url("$baseUrl/api/mcp")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(IOException("添加 MCP 节点失败 (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun manageMcpServerAction(
        action: String, // "enable", "disable", "remove"
        name: String,
        scope: String = "user",
        cwd: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = mapOf(
                "action" to action,
                "scope" to scope,
                "name" to name,
                "cwd" to cwd
            )
            val req = Request.Builder()
                .url("$baseUrl/api/mcp")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(IOException("MCP 操作失败 (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 管理插件扩展：安装、启用、禁用、更新、卸载 (POST /api/plugins)
     */
    suspend fun managePluginAction(
        action: String, // "install", "enable", "disable", "update", "remove"
        source: String,
        scope: String = "global",
        cwd: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = mapOf(
                "action" to action,
                "source" to source.trim(),
                "scope" to scope,
                "cwd" to cwd
            )
            val req = Request.Builder()
                .url("$baseUrl/api/plugins")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(true)
                else Result.failure(IOException("插件操作失败 (${resp.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读取网关 models.json 原始配置 JSON (GET /api/models-config，自动清理污染字段)
     */

data class ModelTestResult(
    val ok: Boolean,
    val latencyMs: Long? = null,
    val status: Int? = null,
    val responseText: String? = null,
    val error: String? = null
)

data class CatalogPreset(
    val name: String? = null,
    val reasoning: Boolean? = null,
    val input: List<String>? = null,
    val contextWindow: Long? = null,
    val maxTokens: Long? = null,
    val costInput: Double? = null,
    val costOutput: Double? = null,
    val costCacheRead: Double? = null,
    val costCacheWrite: Double? = null
)

    /**
     * 直接获取 models.json 顶层 JsonObject（包含 providers 映射，1:1 对标 pi-web models-config）
     */
    suspend fun getModelsConfigObject(): Result<JsonObject> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val fetched = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = true)
                ?: return@withContext Result.failure(IOException("读取 models.json 失败"))
            Result.success(fetched.config)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存 models.json 顶层 JsonObject 并触发服务端刷新 (PUT /api/models-config + POST /api/models/refresh)
     */
    suspend fun saveModelsConfigObject(configObj: JsonObject): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val cloned = configObj.deepCopy()
            sanitizeAndHealModelsConfig(cloned)
            val existingEnvelope = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = false)
            val wrapped = existingEnvelope?.wrappedInConfigKey ?: false
            val ok = putModelsConfigToServer(baseUrl, cloned, wrapped)
            if (!ok) {
                return@withContext Result.failure(IOException("保存 models.json 失败"))
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 测试指定 Provider 下的模型连通性 (POST /api/models-config/test)
     */
    suspend fun testModelConfig(
        providerName: String,
        providerObj: JsonObject,
        modelObj: JsonObject
    ): Result<ModelTestResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = JsonObject().apply {
                addProperty("providerName", providerName.trim())
                add("provider", providerObj)
                add("model", modelObj)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/models-config/test")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                val json = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                if (json == null) {
                    return@withContext Result.success(
                        ModelTestResult(
                            ok = false,
                            status = resp.code,
                            error = "HTTP ${resp.code}: $bodyStr"
                        )
                    )
                }
                val ok = json.get("ok")?.asBoolean ?: false
                val latencyMs = json.get("latencyMs")?.asLong
                val status = json.get("status")?.asInt ?: resp.code
                val responseText = json.get("responseText")?.asString
                val error = json.get("error")?.asString
                Result.success(
                    ModelTestResult(
                        ok = ok,
                        latencyMs = latencyMs,
                        status = status,
                        responseText = responseText,
                        error = error
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 models.dev 自动补全模型规格与价格推荐 (GET /api/models-config/catalog)
     */
    suspend fun getCatalogModelPreset(
        modelId: String,
        providerName: String,
        baseUrlParam: String = ""
    ): Result<CatalogPreset?> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val encQ = java.net.URLEncoder.encode(modelId, "UTF-8")
            val encProv = java.net.URLEncoder.encode(providerName, "UTF-8")
            val encBase = java.net.URLEncoder.encode(baseUrlParam, "UTF-8")
            val url = "$baseUrl/api/models-config/catalog?q=$encQ&provider=$encProv&baseUrl=$encBase"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${resp.code}"))
                }
                val bodyStr = resp.body?.string() ?: ""
                val json = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                    ?: return@withContext Result.success(null)
                val rec = json.getAsJsonObject("recommendation") ?: return@withContext Result.success(null)
                val preset = rec.getAsJsonObject("preset") ?: return@withContext Result.success(null)

                val name = preset.get("name")?.asString
                val reasoning = preset.get("reasoning")?.asBoolean
                val inputArr = preset.getAsJsonArray("input")?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
                val contextWindow = preset.get("contextWindow")?.asLong
                val maxTokens = preset.get("maxTokens")?.asLong

                val cost = preset.getAsJsonObject("cost")
                val costInput = cost?.get("input")?.asDouble
                val costOutput = cost?.get("output")?.asDouble
                val costCacheRead = cost?.get("cacheRead")?.asDouble
                val costCacheWrite = cost?.get("cacheWrite")?.asDouble

                Result.success(
                    CatalogPreset(
                        name = name,
                        reasoning = reasoning,
                        input = inputArr,
                        contextWindow = contextWindow,
                        maxTokens = maxTokens,
                        costInput = costInput,
                        costOutput = costOutput,
                        costCacheRead = costCacheRead,
                        costCacheWrite = costCacheWrite
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getModelsRawConfig(): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val fetched = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = true)
                ?: return@withContext Result.failure(IOException("读取 models.json 失败"))
            val pretty = com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(fetched.config)
            Result.success(pretty)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存网关 models.json 原始配置 JSON (PUT /api/models-config)
     */
    suspend fun saveModelsRawConfig(rawJson: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val configObj = JsonParser.parseString(rawJson).asJsonObject
            sanitizeAndHealModelsConfig(configObj)
            val existingEnvelope = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = false)
            val wrapped = existingEnvelope?.wrappedInConfigKey ?: false
            val ok = putModelsConfigToServer(baseUrl, configObj, wrapped)
            if (!ok) {
                return@withContext Result.failure(IOException("保存 models.json 失败"))
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从 models.json 中删除指定自定义模型或重置该模型的参数覆盖 (GET & PUT /api/models-config)
     */
    suspend fun deleteCustomModel(providerName: String, modelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        prefs.removeModelOverride(providerName, modelId)
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(true)
        try {
            val fetched = fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = false)
                ?: return@withContext Result.success(true)
            val rootConfig = fetched.config
            if (rootConfig.has("providers") && rootConfig.get("providers").isJsonObject) {
                val providersObj = rootConfig.getAsJsonObject("providers")
                val pKey = providersObj.keySet().firstOrNull { it.equals(providerName.trim(), ignoreCase = true) }
                if (pKey != null && providersObj.get(pKey).isJsonObject) {
                    val pObj = providersObj.getAsJsonObject(pKey)
                    if (pObj.has("models") && pObj.get("models").isJsonArray) {
                        val arr = pObj.getAsJsonArray("models")
                        val newArr = JsonArray()
                        for (item in arr) {
                            if (item.isJsonObject && item.asJsonObject.get("id")?.asString.equals(modelId.trim(), ignoreCase = true)) {
                                continue
                            }
                            newArr.add(item)
                        }
                        if (newArr.size() > 0) {
                            pObj.add("models", newArr)
                        } else {
                            pObj.remove("models")
                        }
                    }
                    if (pObj.has("modelOverrides") && pObj.get("modelOverrides").isJsonObject) {
                        val ovObj = pObj.getAsJsonObject("modelOverrides")
                        val mKey = ovObj.keySet().firstOrNull { it.equals(modelId.trim(), ignoreCase = true) }
                        if (mKey != null) ovObj.remove(mKey)
                        if (ovObj.size() == 0) pObj.remove("modelOverrides")
                    }
                    if (pObj.size() == 0) {
                        providersObj.remove(pKey)
                    }
                }
            }
            sanitizeAndHealModelsConfig(rootConfig)
            putModelsConfigToServer(baseUrl, rootConfig, fetched.wrappedInConfigKey)
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存编辑后的 SKILL.md 文档内容 (本地持久化覆盖 + PUT /api/skills/content & PATCH /api/skills)
     */
    suspend fun saveSkillContent(skill: SkillInfo, content: String, cwd: String = ""): Result<Boolean> = withContext(Dispatchers.IO) {
        prefs.saveSkillContentOverride(skill.name, content)
        val baseUrl = prefs.serverUrl
        val filePath = skill.filePath
        if (baseUrl.isBlank() || filePath.isNullOrBlank()) return@withContext Result.success(true)
        try {
            val validCwd = sanitizeCwd(cwd)
            val payload = mapOf(
                "name" to skill.name,
                "filePath" to filePath,
                "path" to filePath,
                "content" to content,
                "cwd" to validCwd
            )
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val putReq = Request.Builder().url("$baseUrl/api/skills/content").put(body).build()
            val putOk = runCatching {
                client.newCall(putReq).execute().use { it.isSuccessful }
            }.getOrDefault(false)

            if (!putOk) {
                val patchReq = Request.Builder().url("$baseUrl/api/skills").patch(body).build()
                runCatching { client.newCall(patchReq).execute().close() }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    /**
     * 更新 MCP Code Mode 与 Token 预算配置 (POST /api/mcp)
     */
    suspend fun updateMcpCodeMode(
        mode: String,
        scriptMode: Boolean,
        tokenBudget: Int,
        cwd: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(true)
        try {
            val payload = mapOf(
                "action" to "set-codemode",
                "preference" to mode,
                "scriptMode" to scriptMode,
                "inlineBudget" to mapOf("default" to tokenBudget),
                "cwd" to cwd
            )
            val req = Request.Builder()
                .url("$baseUrl/api/mcp")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 9. 远程工作区项目目录浏览、创建与初始化 (对标 pi-web /api/cwd/browse, /api/cwd/validate, /api/agent/new)
    // ==========================================

    /**
     * 远程浏览 PC 上的可读目录结构 (GET /api/cwd/browse?path=...)
     */
    suspend fun browseRemoteDirectories(path: String = ""): Result<RemoteBrowseData> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val query = if (path.isNotBlank()) {
                val enc = URLEncoder.encode(path.trim(), "UTF-8")
                "?path=$enc&dir=$enc"
            } else ""
            val req = Request.Builder().url("$baseUrl/api/cwd/browse$query").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("无法浏览远程目录 (${resp.code})"))
                }
                val str = resp.body?.string() ?: "{}"
                val json = JsonParser.parseString(str).asJsonObject
                val currentPath = json.get("current")?.takeIf { !it.isJsonNull }?.asString
                    ?: json.get("path")?.takeIf { !it.isJsonNull }?.asString
                    ?: json.get("dir")?.takeIf { !it.isJsonNull }?.asString
                    ?: path
                val parentPath = json.get("parentPath")?.takeIf { !it.isJsonNull }?.asString
                    ?: json.get("parent")?.takeIf { !it.isJsonNull }?.asString

                val dirs = mutableListOf<RemoteDirEntry>()
                val arrKeys = listOf("directories", "dirs", "entries", "children", "subdirs", "items")
                for (k in arrKeys) {
                    if (json.has(k) && json.get(k).isJsonArray) {
                        val arr = json.getAsJsonArray(k)
                        for (el in arr) {
                            if (el.isJsonObject) {
                                val o = el.asJsonObject
                                val isDir = if (o.has("isDirectory") && !o.get("isDirectory").isJsonNull) {
                                    o.get("isDirectory").asBoolean
                                } else true
                                if (!isDir) continue
                                val dName = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                val dPath = o.get("path")?.takeIf { !it.isJsonNull }?.asString
                                    ?: if (currentPath.isNotBlank() && dName.isNotBlank()) {
                                        val sep = if (currentPath.contains('\\')) "\\" else "/"
                                        currentPath.trimEnd('/', '\\') + sep + dName
                                    } else dName
                                if (dName.isNotBlank()) {
                                    dirs.add(RemoteDirEntry(name = dName, path = dPath))
                                }
                            } else if (el.isJsonPrimitive) {
                                val dName = el.asString
                                val sep = if (currentPath.contains('\\')) "\\" else "/"
                                val dPath = if (dName.contains('/') || dName.contains('\\')) {
                                    dName
                                } else if (currentPath.isNotBlank()) {
                                    currentPath.trimEnd('/', '\\') + sep + dName
                                } else dName
                                val shortName = dName.substringAfterLast('\\').substringAfterLast('/').ifBlank { dName }
                                dirs.add(RemoteDirEntry(name = shortName, path = dPath))
                            }
                        }
                        if (dirs.isNotEmpty()) break
                    }
                }
                Result.success(RemoteBrowseData(currentPath = currentPath, parentPath = parentPath, directories = dirs))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 在远程 PC 上指定或创建新工作区目录并校验生效 (POST /api/cwd/browse & POST /api/cwd/validate)
     */
    suspend fun addOrCreateRemoteWorkspace(
        targetPath: String,
        newChildFolderName: String? = null,
        createIfMissing: Boolean = true
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseClean = targetPath.trim()
        if (baseClean.isBlank()) return@withContext Result.failure(Exception("目录路径不能为空"))
        val sep = if (baseClean.contains('\\')) "\\" else "/"
        val finalPath = if (!newChildFolderName.isNullOrBlank()) {
            baseClean.trimEnd('/', '\\') + sep + newChildFolderName.trim().trim('/', '\\')
        } else {
            baseClean
        }

        // 先在本地工作区列表中持久化保存，确保抽屉与工作区选择器立即可见
        prefs.addCustomWorkspace(finalPath)

        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(finalPath)

        try {
            // 1) 如果指定了新建子目录或开启了自动创建目录，调用网关/pi-web 的目录创建接口
            if (createIfMissing || !newChildFolderName.isNullOrBlank()) {
                val parentDir = if (!newChildFolderName.isNullOrBlank()) {
                    baseClean
                } else {
                    val lastSlash = finalPath.lastIndexOfAny(charArrayOf('/', '\\'))
                    if (lastSlash > 0) finalPath.substring(0, lastSlash) else finalPath
                }
                val folderName = if (!newChildFolderName.isNullOrBlank()) {
                    newChildFolderName.trim()
                } else {
                    finalPath.substringAfterLast('\\').substringAfterLast('/')
                }

                // 调用网关原生递归创建接口 (若伴侣网关支持) 及 pi-web /api/cwd/browse POST
                runCatching {
                    val mkdirPayload = mapOf(
                        "path" to parentDir,
                        "dir" to parentDir,
                        "name" to folderName,
                        "fullPath" to finalPath,
                        "cwd" to finalPath
                    )
                    val req = Request.Builder()
                        .url("$baseUrl/api/cwd/browse")
                        .post(gson.toJson(mkdirPayload).toRequestBody(jsonMediaType))
                        .build()
                    client.newCall(req).execute().close()
                }
            }

            // 2) 调用 /api/cwd/validate 验证并激活该工作区路径
            var validatedPath = finalPath
            runCatching {
                val valPayload = mapOf(
                    "cwd" to finalPath,
                    "path" to finalPath,
                    "createIfMissing" to createIfMissing
                )
                val valReq = Request.Builder()
                    .url("$baseUrl/api/cwd/validate")
                    .post(gson.toJson(valPayload).toRequestBody(jsonMediaType))
                    .build()
                client.newCall(valReq).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                        val resolved = obj?.get("cwd")?.takeIf { !it.isJsonNull }?.asString
                            ?: obj?.get("path")?.takeIf { !it.isJsonNull }?.asString
                        if (!resolved.isNullOrBlank()) {
                            validatedPath = resolved
                            prefs.addCustomWorkspace(validatedPath)
                        }
                    }
                }
            }

            Result.success(validatedPath)
        } catch (e: Exception) {
            Result.success(finalPath)
        }
    }

    /**
     * 在指定远程工作区目录 (cwd) 下初始化新任务会话 (对标 pi-web POST /api/agent/new)
     */
    suspend fun createRemoteSessionInWorkspace(
        cwd: String,
        provider: String? = null,
        modelId: String? = null,
        thinkingLevel: ThinkingLevel? = null,
        toolNames: List<String>? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (cwd.isBlank()) return@withContext Result.failure(Exception("工作区路径不能为空"))
        try {
            val payload = mutableMapOf<String, Any>(
                "cwd" to cwd,
                "type" to "ensure_session"
            )
            if (!provider.isNullOrBlank() && !modelId.isNullOrBlank()) {
                payload["provider"] = provider
                payload["modelId"] = modelId
            }
            if (thinkingLevel != null && thinkingLevel != ThinkingLevel.OFF) {
                payload["thinkingLevel"] = thinkingLevel.value
            }
            if (toolNames != null) {
                payload["toolNames"] = toolNames
            }
            val req = Request.Builder()
                .url("${baseUrl}/api/agent/new")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (resp.isSuccessful) {
                    val sid = json?.get("sessionId")?.takeIf { !it.isJsonNull }?.asString
                        ?: json?.get("id")?.takeIf { !it.isJsonNull }?.asString
                    if (!sid.isNullOrBlank()) {
                        return@withContext Result.success(sid)
                    }
                }
                val err = json?.get("error")?.takeIf { !it.isJsonNull }?.asString
                    ?: "创建新任务失败 (${resp.code})"
                Result.failure(IOException(err))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 调用大模型结合任务上下文智能生成会话标题 (对标 pi-web POST /api/sessions/{id}/auto-name)
     */
    /**
     * 动态切换当前会话内置工具权限 (对标 pi-web POST /api/agent/[id] {"type":"set_tools","toolNames":[...]})
     */
    suspend fun setSessionTools(sessionId: String, toolNames: List<String>?): Result<SetToolsResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            val payload = mutableMapOf<String, Any>("type" to "set_tools")
            if (toolNames != null) {
                payload["toolNames"] = toolNames
            }
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId").post(body).build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    Result.failure(IOException("Server HTTP ${resp.code}: $bodyStr"))
                } else {
                    val json = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                    val dataObj = json?.getAsJsonObject("data")
                    val newSid = dataObj?.get("sessionId")?.takeIf { !it.isJsonNull }?.asString ?: sessionId
                    val recreated = dataObj?.get("recreated")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    Result.success(SetToolsResult(sessionId = newSid, recreated = recreated))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除指定会话 (1:1 对标 pi-web POST /api/sessions/delete {"ids":[sessionId]})
     */
    suspend fun deleteSessions(sessionIds: List<String>): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionIds.isEmpty()) return@withContext Result.success(true)
        try {
            val payload = mapOf("ids" to sessionIds)
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/sessions/delete").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string() ?: ""
                    Result.failure(IOException("Server HTTP ${resp.code}: $errBody"))
                } else {
                    Result.success(true)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 从指定会话分叉出新的分支任务 (1:1 对标 pi-web POST /api/sessions/[id]/fork)
     */

    /**
     * 测试指定 MCP 服务节点连通性 (1:1 源码级对标 pi-web POST /api/mcp/test)
     * 返回结构化工具解析结果及详细信息
     */
    suspend fun testMcpServer(
        name: String,
        scope: String = "global",
        cwd: String? = null,
        config: JsonObject? = null
    ): Result<McpTestToolResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = JsonObject().apply {
                addProperty("name", name)
                addProperty("scope", scope)
                if (!cwd.isNullOrBlank()) {
                    addProperty("cwd", cwd)
                }
                if (config != null && config.size() > 0) {
                    add("config", config)
                }
            }
            val req = Request.Builder()
                .url("$baseUrl/api/mcp/test")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    val root = try {
                        com.google.gson.JsonParser.parseString(bodyStr).asJsonObject
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    val resultObj = if (root.has("result") && root.get("result").isJsonObject) {
                        root.getAsJsonObject("result")
                    } else root
                    val toolNames = mutableListOf<String>()
                    if (resultObj.has("tools") && resultObj.get("tools").isJsonArray) {
                        val arr = resultObj.getAsJsonArray("tools")
                        for (item in arr) {
                            if (item.isJsonObject) {
                                val tName = item.asJsonObject.get("name")?.asString
                                if (!tName.isNullOrBlank()) toolNames.add(tName)
                            } else if (item.isJsonPrimitive) {
                                toolNames.add(item.asString)
                            }
                        }
                    }
                    Result.success(
                        McpTestToolResult(
                            success = true,
                            message = if (toolNames.isNotEmpty()) "连通成功，共发现 ${toolNames.size} 个工具" else "连通成功，未发现导出工具",
                            toolCount = toolNames.size,
                            tools = toolNames
                        )
                    )
                } else {
                    Result.failure(IOException("连通失败 (HTTP ${resp.code}): $bodyStr"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun forkSession(sessionId: String): Result<ForkSessionResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            val body = "".toRequestBody(null)
            val req = Request.Builder().url("$baseUrl/api/sessions/$sessionId/fork").post(body).build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    Result.failure(IOException("Server HTTP ${resp.code}: $bodyStr"))
                } else {
                    val json = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                    val newSid = json?.get("sessionId")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@withContext Result.failure(IOException("未返回有效的分叉会话ID"))
                    val path = json.get("path")?.takeIf { !it.isJsonNull }?.asString
                    val name = json.get("name")?.takeIf { !it.isJsonNull }?.asString
                    Result.success(ForkSessionResult(sessionId = newSid, path = path, name = name))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun autoGenerateSessionTitle(sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            val req = Request.Builder()
                .url("${baseUrl}/api/sessions/${sessionId}/auto-name")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (resp.isSuccessful) {
                    val title = json?.get("title")?.takeIf { !it.isJsonNull }?.asString
                    if (!title.isNullOrBlank()) {
                        return@withContext Result.success(title)
                    }
                }
                val errorMsg = json?.get("error")?.takeIf { !it.isJsonNull }?.asString
                    ?: "大模型生成标题响应异常 (${resp.code})"
                Result.failure(IOException(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
