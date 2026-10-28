package com.pi.app.network

import android.util.Log
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
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class SessionHistoryData(
    val messages: List<ChatItem>,
    val provider: String,
    val modelId: String,
    val thinkingLevel: ThinkingLevel,
    val stats: TokenStats,
    /** 服务端是否还有更早的记录（游标 before=oldestEntryId 继续往前翻） */
    val hasMoreOnServer: Boolean = false,
    /** 更早页的游标：本批最早一条记录的 entryId */
    val oldestEntryId: String? = null,
    /** 服务端自己统计的「还没加载的回合数」（pi 的 context.turnsBefore） */
    val turnsBefore: Int = 0,
    val leafId: String? = null
)

/**
 * 游标翻页返回的一页更早记录（GET /api/sessions/{id}/context?before=<entryId>）。
 */
data class OlderContextPage(
    val messages: List<ChatItem>,
    val oldestEntryId: String? = null,
    val hasMore: Boolean = false,
    val turnsBefore: Int = 0
)


data class DiscoveredModelItem(
    val id: String,
    val name: String = "",
    val description: String = ""
)

data class DiscoverModelsResult(
    val models: List<DiscoveredModelItem>,
    val endpoint: String = ""
)

data class DefaultModelRef(
    val provider: String,
    val modelId: String
)

data class DynamicModelCatalog(
    val models: List<ModelInfo>,
    val thinkingLevelsMap: Map<String, List<String>>,
    val defaultModel: DefaultModelRef? = null,
    val defaultThinkingLevel: String? = null
)

class PiApiClient(private val prefs: AppPreferences) {
    private val gson = Gson()

    companion object {
        /**
         * 中止类错误（非服务故障）：Node/undici 的 `This operation was aborted`、
         * `Request was aborted`、`Operation aborted`、`cancelled` 等。
         * pi 在压缩上下文、用户 Stop、自动重试换手、客户端断连时都会产生这类记录。
         */
        private val ABORT_LIKE_ERROR_REGEX = Regex(
            "(?i)(operation was aborted|request was aborted|operation aborted|request aborted)"
        )

        /** 候选 Key 联网自检时使用的一次性 provider 名后缀（避开已保存凭据的遮蔽）。 */
        private const val VERIFY_PROVIDER_SUFFIX = "-key-verify"

        /** pi 允许单条消息携带的图片上限（服务端校验：最多 10 张、每张 ≤10MB）。 */
        const val MAX_PROMPT_IMAGES = 10
    }
    private val sessionHistoryCache = java.util.concurrent.ConcurrentHashMap<String, SessionHistoryData>()

    /**
     * 模型目录（models.json + 内置注册表）里各模型的真实 contextWindow，键为 `provider:modelId` 小写。
     *
     * 用途：`GET /api/sessions/{id}` 的 `context.model` 只给 provider/modelId、**不带 contextWindow**，
     * 会话快照算不出占用率的分母。之前直接兜底 200000，于是 1M 窗口的模型一律显示 100%。
     */
    @Volatile
    private var catalogContextWindows: Map<String, Long> = emptyMap()

    fun getCachedSessionDetail(sessionId: String): SessionHistoryData? = sessionHistoryCache[sessionId]

    fun clearSessionCache(sessionId: String? = null) {
        if (sessionId == null) {
            sessionHistoryCache.clear()
        } else {
            sessionHistoryCache.remove(sessionId)
        }
    }

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
                            isRunning = runningIds.contains(s.id),
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

    /**
     * 轻量运行态对账：GET /api/agent/running（实测约 230 字节 / 4ms）。
     *
     * 返回正在运行 / 等待输入的会话 id 集合与 `sessionListVersion`。列表页和会话页
     * 都用它做秒级同步；只有 version 变化时才值得重新拉一次 200KB+ 的完整会话列表。
     */
    suspend fun getRunningSessions(): Result<RunningSessionsSnapshot> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/agent/running").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("读取运行态失败 (${response.code})"))
                }
                val root = runCatching {
                    JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                }.getOrNull() ?: return@withContext Result.failure(IOException("运行态响应无法解析"))
                fun idSet(key: String): Set<String> =
                    root.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                        ?.mapNotNull { runCatching { it.asString }.getOrNull() }
                        ?.toSet()
                        ?: emptySet()
                Result.success(
                    RunningSessionsSnapshot(
                        sessionListVersion = root.get("sessionListVersion")
                            ?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                        runningIds = idSet("runningSessionIds"),
                        awaitingInputIds = idSet("awaitingInputSessionIds")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 2. 深入任务：加载完整任务历史 (GET /api/sessions/[id])
    // 对标 pi-web MessageView：配对 toolCall 与 toolResult，提取参数预览与每回合 Token 用量
    // ==========================================

    /**
     * 解析 `GET /api/sessions/{id}` 的快照 JSON。
     *
     * 网络首屏与磁盘离线缓存共用同一套解析：打开会话时先用上次落盘的 body 回放，
     * 再让网络结果覆盖，避免「每次点进去都要等一次网络才有正文」。
     */
    private fun parseSessionHistoryBody(
        sessionId: String,
        body: String,
        tail: Int
    ): SessionHistoryData {
        val baseUrl = prefs.serverUrl
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
            if (tail > 100) {
                // 仅当明确需要加载大批量早期历史时才执行向上翻页拉取，首屏极速秒开模式仅使用当前返回的尾部
                val pagedMessages = fetchFullContextPages(baseUrl, sessionId, leafId, tail, initialCtxMsgs, ctxObj)
                for (m in pagedMessages) rawContextMessages.add(m)
            } else if (initialCtxMsgs != null) {
                for (m in initialCtxMsgs) rawContextMessages.add(m)
            }
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
            if (tail <= 100 && normalizedEntryMessages.size() > tail) {
                // 首屏快速分段：默认截取最近 tail 条；若尾部全是工具调用，向前延伸以确保包含用户对话
                var startIndex = (normalizedEntryMessages.size() - tail).coerceAtLeast(0)
                val nonToolCount = (startIndex until normalizedEntryMessages.size()).count { idx ->
                    val el = normalizedEntryMessages.get(idx)
                    if (el.isJsonObject) {
                        val r = el.asJsonObject.get("role")?.takeIf { !it.isJsonNull }?.asString
                        r == "user" || r == "assistant"
                    } else false
                }
                if (nonToolCount < 3 && startIndex > 0) {
                    startIndex = (startIndex - 40).coerceAtLeast(0)
                }
                val sliced = JsonArray()
                for (i in startIndex until normalizedEntryMessages.size()) {
                    sliced.add(normalizedEntryMessages.get(i))
                }
                sliced
            } else {
                normalizedEntryMessages
            }
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
            cost = stats.get("cost")?.takeIf { !it.isJsonNull }?.let { costElem ->
                if (costElem.isJsonPrimitive) {
                    runCatching { costElem.asDouble }.getOrDefault(0.0)
                } else if (costElem.isJsonObject) {
                    val cObj = costElem.asJsonObject
                    val total = cObj.get("total") ?: cObj.get("cost")
                    runCatching { total?.asDouble }.getOrNull() ?: runCatching {
                        val inp = cObj.get("input")?.asDouble ?: 0.0
                        val out = cObj.get("output")?.asDouble ?: 0.0
                        inp + out
                    }.getOrDefault(0.0)
                } else 0.0
            } ?: 0.0
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
        val catalogWindow = catalogContextWindows["${currentProvider.lowercase()}:${currentModelId.lowercase()}"]
            ?: catalogContextWindows.entries.firstOrNull {
                it.key.substringAfter(':') == currentModelId.lowercase()
            }?.value
            ?: 0L
        val effectiveMaxContext = when {
            overrideModel != null && overrideModel.contextWindow > 0L -> overrideModel.contextWindow
            modelContextWindow > 0L -> modelContextWindow
            catalogWindow > 0L -> catalogWindow
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

        return SessionHistoryData(
            messages = chatItems,
            provider = currentProvider,
            modelId = currentModelId,
            thinkingLevel = currentThinkingLevel,
            stats = tokenStats,
            hasMoreOnServer = initialHasMore,
            oldestEntryId = extractOldestEntryId(ctxObj, initialCtxMsgs),
            turnsBefore = ctxObj?.get("turnsBefore")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            leafId = leafId
        )
    }

    suspend fun getSessionDetail(sessionId: String, tail: Int = 60): Result<SessionHistoryData> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            // 与 pi-web 前端同一套参数：deferMedia 不带历史图片、tree=summary 只要摘要树。
            // 这两个开关把首屏快照从 ~200KB 压到 ~150KB，手机端打开会话明显更快。
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$sessionId?tail=$tail&deferMedia=1&tree=summary")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("Failed to load session details: ${response.code}"))
                }
                val body = response.body?.string() ?: "{}"
                val historyData = parseSessionHistoryBody(sessionId, body, tail)
                sessionHistoryCache[sessionId] = historyData
                persistSessionSnapshot(sessionId, body)
                Result.success(historyData)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── 会话快照磁盘缓存：再次打开会话时先回放上次的正文，不必等网络 ──
    private val snapshotLastWriteAt = mutableMapOf<String, Long>()

    private fun snapshotDir(): File {
        val dir = File(prefs.cacheDir, "session-snapshots")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun snapshotFile(sessionId: String): File = File(snapshotDir(), "$sessionId.json")

    /**
     * 把最近一次快照 body 落盘（同一会话 10s 内最多写一次；只保留最近 8 个会话）。
     * 存的是服务端原始 JSON，回放时走同一套解析，不另建一份容易走样的模型。
     */
    private fun persistSessionSnapshot(sessionId: String, body: String) {
        if (body.length < 64) return
        val now = System.currentTimeMillis()
        val last = snapshotLastWriteAt[sessionId] ?: 0L
        if (now - last < 10_000L) return
        snapshotLastWriteAt[sessionId] = now
        runCatching {
            val dir = snapshotDir()
            val target = snapshotFile(sessionId)
            val tmp = File(dir, "$sessionId.json.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.writeText(body)
                tmp.delete()
            }
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(8)
                ?.forEach { it.delete() }
        }
    }

    /** 读取上次落盘的会话快照（进程被回收后再打开也能秒出正文）。没有/解析失败返回 null。 */
    suspend fun getDiskCachedSessionDetail(sessionId: String): SessionHistoryData? = withContext(Dispatchers.IO) {
        runCatching {
            val file = snapshotFile(sessionId)
            if (!file.exists() || file.length() < 64L) return@runCatching null
            parseSessionHistoryBody(sessionId, file.readText(), 60).also {
                sessionHistoryCache[sessionId] = it
            }
        }.getOrNull()
    }

    /**
     * 游标翻页拉取「更早的一页」记录：
     * GET /api/sessions/{id}/context?tail=<n>&before=<oldestEntryId>
     *
     * pi-web 官方前端就是这么往前翻的（`before` 传当前最早一条的 entryId，服务端
     * `excludeLeaf=true` 保证页与页不重叠），返回里带 `hasMore` / `turnsBefore` / 新的
     * `oldestEntryId` 继续往前翻的游标。
     *
     * 拉回来的这页只追加到「更早记录」独立列表，**不**混进主快照 messages，
     * 免得破坏 MainActivity 按下标对账的合并逻辑。
     */
    suspend fun getOlderContextPage(
        sessionId: String,
        beforeEntryId: String,
        tail: Int = 60,
        leafId: String? = null
    ): Result<OlderContextPage> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (beforeEntryId.isBlank()) return@withContext Result.failure(Exception("缺少翻页游标"))
        try {
            val encBefore = URLEncoder.encode(beforeEntryId, "UTF-8")
            val leafParam = if (!leafId.isNullOrBlank()) "&leafId=${URLEncoder.encode(leafId, "UTF-8")}" else ""
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$sessionId/context?tail=$tail&before=$encBefore$leafParam")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("加载更早记录失败 (${resp.code})"))
                }
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@withContext Result.failure(IOException("更早记录响应无法解析"))
                val ctx = root.get("context")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
                val arr = ctx.get("messages")?.takeIf { it.isJsonArray }?.asJsonArray
                val parsed = parseRawMessagesToChatItems(
                    sessionId = sessionId,
                    msgs = arr ?: JsonArray(),
                    defaultModelId = ""
                )
                // 每页的 id 都是以页内序号生成的，直接拼进同一个列表会和主列表撞 key
                // （LazyColumn 重复 key 会直接崩），所以统一加游标前缀
                val prefix = beforeEntryId.take(8)
                val items = parsed.items.map { it.copy(id = "older_${prefix}_${it.id}") }
                Result.success(
                    OlderContextPage(
                        messages = items,
                        oldestEntryId = extractOldestEntryId(ctx, arr),
                        hasMore = ctx.get("hasMore")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        turnsBefore = ctx.get("turnsBefore")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    )
                )
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
                    // pi-web 只在 display 为真时渲染 custom_message（如 auto-continue-after-compaction
                    // 这条注入指令是 display:false），否则会把内部注入的 prompt 当系统消息展示出来。
                    val explicitlyHidden = entry.get("display")
                        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                        ?.asBoolean == false
                    val contentText = extractTextFromContent(entry.get("content"))
                    if (!explicitlyHidden && contentText.isNotBlank()) {
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
            val timestamp: Long,
            /** pi 把长命令输出落到临时文件时给出的路径（可用 bash-output 取全量） */
            val fullOutputPath: String? = null
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
                val rawOut = extractTextFromContent(contentElem)
                val tOut = if (rawOut.length > 12000) {
                    rawOut.take(12000) + "\n\n... [工具输出过长，已截断显示前 12,000 字符] ..."
                } else rawOut
                val tMedia = extractMediaFromContent(contentElem, tOut)
                val dur = msgObj.get("durationMs")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                val isErr = msgObj.get("isError")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                val ts = parseFlexibleTimestamp(msgObj.get("timestamp") ?: rawObj.get("timestamp"))
                val fullOutPath = msgObj.get("fullOutputPath")?.takeIf { !it.isJsonNull }?.asString
                    ?: msgObj.get("outputPath")?.takeIf { !it.isJsonNull }?.asString
                    ?: rawObj.get("fullOutputPath")?.takeIf { !it.isJsonNull }?.asString
                if (!tcId.isNullOrBlank()) {
                    toolResultById[tcId] =
                        ParsedToolResult(tcId, tName, tOut, tMedia, dur, isErr, ts, fullOutPath)
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
                                        val argMedia = MediaAttachmentParsing.fromToolArgs(toolName, preview, inputElem)
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
                                                isError = paired?.isError ?: false,
                                                fullOutputPath = paired?.fullOutputPath
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
                    val errMsg = msgObj.get("errorMessage")?.takeIf { !it.isJsonNull }?.asString
                        ?: (if (msgObj.get("stopReason")?.takeIf { !it.isJsonNull }?.asString == "error") "上游模型异常中断" else null)
                    // 「This operation was aborted」这类中止并非服务故障：pi 在压缩上下文、用户停止、
                    // 自动重试换手时都会 abort 在途请求，并落一条空正文的 assistant 记录。
                    // 历史回放时不应把它渲染成刺眼的「⚠️ 模型服务异常」。
                    val isAbortLike = errMsg != null && ABORT_LIKE_ERROR_REGEX.containsMatchIn(errMsg)

                    if (text.isNotBlank() || !thinking.isNullOrBlank() || assistantMedia.isNotEmpty()) {
                        val estSec = thinking?.let { (it.length / 45).coerceIn(1, 90) }
                        val fullContent = when {
                            errMsg.isNullOrBlank() || text.contains(errMsg) -> text
                            isAbortLike -> (text + "\n\n⏸ 本轮生成已被中断").trim()
                            else -> (text + "\n\n⚠️ 模型服务异常: $errMsg").trim()
                        }
                        chatItems.add(ChatItem(
                            id = "hist_${sessionId}_${chatItems.size}_agent",
                            role = MessageRole.AGENT,
                            content = fullContent,
                            thinking = thinking,
                            thinkingDurationSec = estSec,
                            mediaList = assistantMedia,
                            modelName = msgModelName,
                            usageSummary = if (toolCalls.isEmpty()) turnUsageStr else null,
                            timestamp = timestamp
                        ))
                    } else if (!errMsg.isNullOrBlank() && toolCalls.isEmpty() && !isAbortLike) {
                        // 彻底解决上游报错且内容为空时静默吞掉的问题 (如 HTTP 422 developer 角色拒收报错)
                        chatItems.add(ChatItem(
                            id = "hist_${sessionId}_${chatItems.size}_error",
                            role = MessageRole.AGENT,
                            content = "⚠️ 模型服务异常:\n$errMsg",
                            modelName = msgModelName,
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
                        val rawToolOutput = extractTextFromContent(contentElem)
                        val toolOutput = if (rawToolOutput.length > 12000) {
                            rawToolOutput.take(12000) + "\n\n... [工具输出过长，已截断显示前 12,000 字符] ..."
                        } else rawToolOutput
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
        MediaAttachmentParsing.firstInlineSvg(outputText)?.let { list.add(it) }
        return list
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

                // 解析服务端当前默认模型与默认思考档位 (对标 pi-web /api/models 返回的 defaultModel)
                var defaultModelRef: DefaultModelRef? = null
                if (json.has("defaultModel") && json.get("defaultModel").isJsonObject) {
                    val dObj = json.getAsJsonObject("defaultModel")
                    val prov = dObj.get("provider")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val mId = dObj.get("modelId")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    if (prov.isNotBlank() && mId.isNotBlank()) {
                        defaultModelRef = DefaultModelRef(provider = prov, modelId = mId)
                        prefs.defaultProvider = prov
                        prefs.defaultModelId = mId
                    }
                }
                val defaultThinking = json.get("defaultThinkingLevel")?.takeIf { !it.isJsonNull }?.asString
                if (!defaultThinking.isNullOrBlank()) {
                    prefs.defaultThinkingLevel = defaultThinking
                }

                // 同时拉取 /api/models-config (models.json) 与本地保存的模型参数覆盖，确保用户修改的 contextWindow 等参数100%生效且不回退
                val serverModelsConfig = fetchServerModelsConfigObject(baseUrl)
                val baseCatalog = DynamicModelCatalog(
                    models = models.ifEmpty { getDefaultCatalog().models },
                    thinkingLevelsMap = levelsMap,
                    defaultModel = defaultModelRef,
                    defaultThinkingLevel = defaultThinking
                )
                val merged = applyModelOverrides(baseCatalog, serverModelsConfig)

                // ── 过滤「已经不存在」的 provider ──
                // pi 的模型清单会把**历史会话里用过的模型**也并进来（刷不掉，实测 refresh 返回 changed:false），
                // 于是像已删除的 CloseAI 这类幽灵 provider 会一直出现在模型的弹窗里。
                // 判定「还活着」的标准：models.json 里声明过，或者 pi 凭据库里已配置 Key。
                val liveProviders = mutableSetOf<String>()
                serverModelsConfig?.get("providers")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.keySet()?.forEach { liveProviders.add(it.lowercase()) }
                val authStates = getProviderAuthStates().getOrNull()
                authStates?.forEach { (id, st) -> if (st.configured) liveProviders.add(id.lowercase()) }
                val finalModels = if (liveProviders.isEmpty()) {
                    merged.models
                } else {
                    merged.models.filter { liveProviders.contains(it.provider.lowercase()) }
                }

                catalogContextWindows = finalModels
                    .filter { it.contextWindow > 0L }
                    .associate { "${it.provider.lowercase()}:${it.id.lowercase()}" to it.contextWindow }

                // 服务端默认模型若落在被过滤掉的 provider 上（例如默认还是 CloseAI/deepseek-flash），
                // 回退到「同名模型」或第一个可用模型，避免选择器一进来就是幽灵模型。
                var effectiveDefault = defaultModelRef
                if (effectiveDefault != null && finalModels.none {
                        it.provider.equals(effectiveDefault!!.provider, ignoreCase = true) &&
                            it.id.equals(effectiveDefault!!.modelId, ignoreCase = true)
                    }
                ) {
                    val sameId = finalModels.firstOrNull { it.id.equals(effectiveDefault!!.modelId, ignoreCase = true) }
                    val fallback = sameId ?: finalModels.firstOrNull()
                    if (fallback != null) {
                        effectiveDefault = DefaultModelRef(provider = fallback.provider, modelId = fallback.id)
                        prefs.defaultProvider = fallback.provider
                        prefs.defaultModelId = fallback.id
                    } else {
                        effectiveDefault = null
                    }
                }

                Result.success(
                    merged.copy(
                        models = finalModels,
                        defaultModel = effectiveDefault
                    )
                )
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

        // 2. 收集服务端当前所有合法的 Provider 集合 (不区分大小写)
        val validProviders = mutableSetOf<String>()
        if (serverConfig != null && serverConfig.has("providers") && serverConfig.get("providers").isJsonObject) {
            validProviders.addAll(serverConfig.getAsJsonObject("providers").keySet().map { it.lowercase() })
        }
        merged.forEach { validProviders.add(it.provider.lowercase()) }

        // 3. 应用用户在 App 内部针对具体模型保存的持久化配置
        val localOverrides = prefs.getModelOverrides()
        for ((_, ov) in localOverrides) {
            val idx = merged.indexOfFirst {
                it.provider.equals(ov.provider, ignoreCase = true) && it.id.equals(ov.modelId, ignoreCase = true)
            }.takeIf { it >= 0 } ?: merged.indexOfFirst { it.id.equals(ov.modelId, ignoreCase = true) }

            // 如果该模型所属的 Provider 根本不在当前服务端的有效列表中 (已被用户在模型管理中彻底删除)，
            // 坚决不复活孤儿模型，并同步从本地清除该已失效的持久化条目，消除幽灵模型
            if (validProviders.isNotEmpty() && !validProviders.contains(ov.provider.lowercase())) {
                prefs.removeModelOverride(ov.provider, ov.modelId)
                continue
            }

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
            }
        }
        return catalog.copy(models = merged)
    }

    // ==========================================
    // 4. 命令发送与交互动作 (1:1 匹配 pi-web)
    // ==========================================

    suspend fun sendCommand(
        sessionId: String,
        prompt: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "message" to prompt,
            "text" to prompt
        )
        if (images.isNotEmpty()) {
            payload["images"] = imagesToJson(images)
        }
        sendAgentAction(sessionId, payload)
    }

    suspend fun sendSteer(
        sessionId: String,
        text: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "streamingBehavior" to "steer",
            "message" to text,
            "text" to text
        )
        if (images.isNotEmpty()) {
            payload["images"] = imagesToJson(images)
        }
        sendAgentAction(sessionId, payload)
    }

    suspend fun sendFollowUp(
        sessionId: String,
        text: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "streamingBehavior" to "followUp",
            "message" to text,
            "text" to text
        )
        if (images.isNotEmpty()) {
            payload["images"] = imagesToJson(images)
        }
        sendAgentAction(sessionId, payload)
    }

    /**
     * 读取一次 bash 的**完整输出**（GET /api/agent/{id}/bash-output?path=<输出文件>）。
     *
     * pi 会把长命令输出落到临时文件，聊天里只塞截断预览；`path` 就是工具结果里的
     * `fullOutputPath`。实测契约：`{success:true, data:{output:"…"}}`，
     * 过大时返回 413（`Full output is too large`）。
     */
    suspend fun getBashFullOutput(sessionId: String, outputPath: String): Result<String> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            if (outputPath.isBlank()) return@withContext Result.failure(Exception("缺少输出路径"))
            try {
                val req = Request.Builder()
                    .url(
                        "$baseUrl/api/agent/$sessionId/bash-output?path=" +
                            URLEncoder.encode(outputPath, "UTF-8")
                    )
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    if (!resp.isSuccessful || root == null) {
                        val err = root?.get("error")?.asString ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IOException(err))
                    }
                    val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    val out = data?.get("output")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    Result.success(out)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 文件元信息：GET /api/files/<绝对路径>?type=meta → {size, language, mime, previewKind} */
    suspend fun getFileMeta(absPath: String): Result<FileMeta> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (absPath.isBlank()) return@withContext Result.failure(Exception("缺少文件路径"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/files/${encodeFilePath(absPath)}?type=meta")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (!resp.isSuccessful || root == null) {
                    val err = root?.get("error")?.asString ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                Result.success(
                    FileMeta(
                        size = root.get("size")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                        language = root.get("language")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        mime = root.get("mime")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 文件文本内容：GET /api/files/<绝对路径>?type=read → {content} */
    suspend fun getFileText(absPath: String): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/files/${encodeFilePath(absPath)}?type=read")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (!resp.isSuccessful || root == null) {
                    val err = root?.get("error")?.asString ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                Result.success(root.get("content")?.takeIf { !it.isJsonNull }?.asString ?: "")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 文件原始字节（图片预览用）：GET /api/files/<绝对路径>?type=download */
    suspend fun getFileBytes(absPath: String, maxBytes: Long = 12L * 1024 * 1024): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            try {
                val req = Request.Builder()
                    .url("$baseUrl/api/files/${encodeFilePath(absPath)}?type=download")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("读取文件失败 (${resp.code})"))
                    }
                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    if (bytes.size > maxBytes) {
                        return@withContext Result.failure(IOException("文件过大（${bytes.size / 1024 / 1024} MB），暂不预览"))
                    }
                    Result.success(bytes)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 把绝对路径编码进 /api/files/<path> 的单段路径（保留分隔符语义，只转义特殊字符） */
    private fun encodeFilePath(absPath: String): String =
        absPath.trim().replace('\\', '/').split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    /** 开关 pi 的自动上下文压缩（agent {"type":"set_auto_compaction","enabled":bool}） */
    suspend fun setAutoCompaction(sessionId: String, enabled: Boolean): Result<Boolean> =
        sendAgentAction(sessionId, mapOf("type" to "set_auto_compaction", "enabled" to enabled))

    /** 开关 pi 的自动重试（agent {"type":"set_auto_retry","enabled":bool}） */
    suspend fun setAutoRetry(sessionId: String, enabled: Boolean): Result<Boolean> =
        sendAgentAction(sessionId, mapOf("type" to "set_auto_retry", "enabled" to enabled))

    /**
     * 读取工作目录的 git 变更：GET /api/git/status?cwd=<绝对路径>
     * → {isGitRepository, repositoryRoot, files:[{filePath,status,code,...}], additions, deletions}
     */
    suspend fun getGitStatus(cwd: String): Result<GitStatusResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (cwd.isBlank()) return@withContext Result.failure(Exception("会话缺少工作目录"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/git/status?cwd=${URLEncoder.encode(cwd, "UTF-8")}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("读取 git 状态失败 (${resp.code})"))
                }
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@withContext Result.failure(IOException("git 状态响应无法解析"))
                val files = root.get("files")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { el ->
                    runCatching {
                        val o = el.asJsonObject
                        GitFileChange(
                            filePath = o.get("filePath")?.asString ?: "",
                            status = o.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            code = o.get("code")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        )
                    }.getOrNull()
                } ?: emptyList()
                Result.success(
                    GitStatusResult(
                        isGitRepository = root.get("isGitRepository")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        repositoryRoot = root.get("repositoryRoot")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        files = files,
                        additions = root.get("additions")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        deletions = root.get("deletions")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 读取单个文件的 diff：GET /api/git/diff?cwd=&path= → {supported, status, patch} */
    suspend fun getGitDiff(cwd: String, path: String): Result<GitDiffResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (cwd.isBlank() || path.isBlank()) return@withContext Result.failure(Exception("缺少 cwd 或文件路径"))
        try {
            val req = Request.Builder()
                .url(
                    "$baseUrl/api/git/diff?cwd=${URLEncoder.encode(cwd, "UTF-8")}" +
                        "&path=${URLEncoder.encode(path, "UTF-8")}"
                )
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                if (!resp.isSuccessful || root == null) {
                    val err = root?.get("error")?.asString ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException("读取 diff 失败：$err"))
                }
                Result.success(
                    GitDiffResult(
                        supported = root.get("supported")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        status = root.get("status")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        patch = root.get("patch")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 服务端全文搜索会话内容：GET /api/sessions/search?q=<≤200 字符>
     *
     * 返回 `{results:[{session, entryId, blockIndex, match, before, after}], truncated}`，
     * 每条命中都带会话本身与命中片段，手机端不必自己再扫一遍 jsonl。
     * 实测：q=Wren 返回 25 条 / 29.8 KB / 3.0s（服务端要扫全部会话，所以 UI 要做防抖）。
     */
    suspend fun searchSessions(query: String): Result<List<SessionSearchHit>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        val q = query.trim()
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (q.isEmpty()) return@withContext Result.success(emptyList())
        if (q.length > 200) return@withContext Result.failure(IllegalArgumentException("搜索词过长（最多 200 字符）"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/search?q=${URLEncoder.encode(q, "UTF-8")}")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("搜索失败 (${resp.code})"))
                }
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@withContext Result.success(emptyList())
                val arr = root.get("results")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return@withContext Result.success(emptyList())
                val out = mutableListOf<SessionSearchHit>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val sessionEl = o.get("session")?.takeIf { it.isJsonObject } ?: continue
                    val session = runCatching { gson.fromJson(sessionEl, Session::class.java) }.getOrNull()
                        ?: continue
                    out.add(
                        SessionSearchHit(
                            session = session,
                            entryId = o.get("entryId")?.takeIf { !it.isJsonNull }?.asString,
                            match = o.get("match")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            before = o.get("before")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            after = o.get("after")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        )
                    )
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读取斜杠命令列表：POST /api/agent/{id} {"type":"get_commands"}
     * → {commands:[{name, description, source: extension|prompt|skill}]}
     */
    suspend fun getCommands(sessionId: String): Result<List<SlashCommand>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            val body = gson.toJson(mapOf("type" to "get_commands")).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("读取命令列表失败 (${resp.code})"))
                }
                val raw = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                    ?: return@withContext Result.success(emptyList())
                val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
                val arr = data.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return@withContext Result.success(emptyList())
                val out = mutableListOf<SlashCommand>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val o = el.asJsonObject
                    val name = o.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                    if (name.isBlank()) continue
                    out.add(
                        SlashCommand(
                            name = name,
                            description = o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                            source = o.get("source")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        )
                    )
                }
                Result.success(out)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 按 pi-web 的校验规则序列化图片：最多 10 张，data 必须是纯 base64、不带 data: 前缀。 */
    private fun imagesToJson(images: List<ChatImageInput>): JsonArray {
        val arr = JsonArray()
        images.take(MAX_PROMPT_IMAGES).forEach { img ->
            val b64 = img.base64Data.substringAfter("base64,", img.base64Data).trim()
            if (b64.isEmpty()) return@forEach
            arr.add(JsonObject().apply {
                addProperty("type", "image")
                addProperty("data", b64)
                addProperty("mimeType", img.mimeType.ifBlank { "image/png" })
            })
        }
        return arr
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

    /**
     * 读取会话运行时的权威状态 (GET /api/sessions/{id}/state)。
     *
     * 一次调用同时解决三件事，取代原先三个各自为政的来源：
     * 1. **是否在跑**：`running`（等价 `/api/sessions` 里 runningSessionIds 的判定）。原先每次
     *    GET `/api/sessions` 实测 **273 KB / 390 条会话**，现在 39 KB 且不用遍历全表；
     * 2. **上下文占用**：`contextUsage {percent, contextWindow, tokens}`，contextWindow 是模型
     *    真实窗口（如 1,000,000），不再用 20 万硬默认值算出「恒为 100%」；
     * 3. **排队消息**：`queuedMessages {steering, followUp}` + `pendingMessageCount`，
     *    手机离开会话页再回来时队列不会凭空消失。
     *
     * 顺带还有 model / thinkingLevel，可用于对齐 PC 端切过的模型与思考档位。
     */
    suspend fun getAgentState(sessionId: String): Result<AgentRuntimeState> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/sessions/$sessionId/state").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("读取 agent 状态失败 (${response.code})"))
                }
                val raw = response.body?.string() ?: ""
                val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                    ?: return@withContext Result.failure(IOException("agent 状态响应无法解析"))
                // 兼容两种形态：{running, state:{...}} 或直接是 state 本体
                val data = root.get("state")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: root
                val usage = data.get("contextUsage")?.takeIf { it.isJsonObject }?.asJsonObject
                val modelObj = data.get("model")?.takeIf { it.isJsonObject }?.asJsonObject
                val queue = data.get("queuedMessages")?.takeIf { it.isJsonObject }?.asJsonObject
                fun queueTexts(key: String): List<String> {
                    val arr = queue?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
                    return arr.mapNotNull { el ->
                        when {
                            el.isJsonPrimitive -> el.asString
                            el.isJsonObject -> el.asJsonObject.get("message")?.takeIf { !it.isJsonNull }?.asString
                                ?: el.asJsonObject.get("text")?.takeIf { !it.isJsonNull }?.asString
                            else -> null
                        }
                    }.filter { it.isNotBlank() }
                }
                val isStreaming = data.get("isStreaming")?.takeIf { !it.isJsonNull }?.asBoolean == true
                val isPromptRunning = data.get("isPromptRunning")?.takeIf { !it.isJsonNull }?.asBoolean == true
                val isBashRunning = data.get("isBashRunning")?.takeIf { !it.isJsonNull }?.asBoolean == true
                // 顶层 running = 「pi-web 里有没有活着的 agent 运行时」，不等于正在执行。
                // 兼容 POST /api/agent/{id} 的 {success,data} 形态（没有顶层 running 字段）。
                val runtimeAlive = root.get("running")?.takeIf { !it.isJsonNull }?.asBoolean
                    ?: data.has("isStreaming")
                Result.success(
                    AgentRuntimeState(
                        percent = usage?.get("percent")?.takeIf { !it.isJsonNull }?.asDouble ?: 0.0,
                        contextWindow = usage?.get("contextWindow")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                        contextTokens = usage?.get("tokens")?.takeIf { !it.isJsonNull }?.asLong ?: 0L,
                        hasContextUsage = usage != null,
                        provider = modelObj?.get("provider")?.takeIf { !it.isJsonNull }?.asString,
                        modelId = modelObj?.get("id")?.takeIf { !it.isJsonNull }?.asString,
                        thinkingLevel = data.get("thinkingLevel")?.takeIf { !it.isJsonNull }?.asString,
                        isStreaming = isStreaming,
                        isCompacting = data.get("isCompacting")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        isBashRunning = isBashRunning,
                        pendingMessageCount = data.get("pendingMessageCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
                        // 与 pi-web 前端同一口径：运行态 = 有活跃运行时 且（流式生成 / 提示词在跑 / bash 在跑）
                        running = runtimeAlive && (isStreaming || isPromptRunning || isBashRunning),
                        runtimeAlive = runtimeAlive,
                        autoCompactionEnabled =
                            data.get("autoCompactionEnabled")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        autoRetryEnabled =
                            data.get("autoRetryEnabled")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                        steering = queueTexts("steering"),
                        followUp = queueTexts("followUp")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读取 pi-web 的会话 UI 状态：GET /api/sessions/ui-state
     *
     * 一份状态里同时有：会话的置顶/归档（sessions）、项目的置顶（projects）、项目排序（projectOrder）。
     * pi-web 把它存在自己的状态文件里并带 revision，App 之前只在本地 SharedPreferences 自建一份，
     * 所以「手机上置顶了、PC 上没置顶」。
     */
    suspend fun getSessionUiState(): Result<SessionUiSnapshot> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/sessions/ui-state").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("读取置顶/归档状态失败 (${response.code})"))
                }
                val raw = response.body?.string() ?: "{}"
                Result.success(parseUiState(raw))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUiState(raw: String): SessionUiSnapshot {
        val root = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: JsonObject()
        val state = root.get("state")?.takeIf { it.isJsonObject }?.asJsonObject ?: root
        val flags = mutableMapOf<String, SessionUiFlags>()
        state.get("sessions")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (id, el) ->
            if (el.isJsonObject) {
                val o = el.asJsonObject
                flags[id] = SessionUiFlags(
                    pinned = o.get("pinnedAt")?.takeIf { !it.isJsonNull } != null,
                    archived = o.get("archivedAt")?.takeIf { !it.isJsonNull } != null
                )
            }
        }
        val pinnedProjects = mutableSetOf<String>()
        state.get("projects")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, el) ->
            if (el.isJsonObject && el.asJsonObject.get("pinnedAt")?.takeIf { !it.isJsonNull } != null) {
                pinnedProjects.add(key.lowercase())
            }
        }
        val order = state.get("projectOrder")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()
        return SessionUiSnapshot(
            revision = state.get("revision")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            sessionFlags = flags,
            pinnedProjectKeys = pinnedProjects,
            projectOrder = order
        )
    }

    /**
     * 置顶 / 取消置顶（写 pi-web 的 ui-state，与 PC 端共用同一份状态）
     * POST /api/sessions/ui-state {"action":"set","ids":[...],"pinned":true|false}
     */
    suspend fun setSessionPinned(sessionId: String, pinned: Boolean): Result<Boolean> =
        setSessionUiFlag(listOf(sessionId), "pinned", pinned)

    /**
     * 归档 / 取消归档
     * POST /api/sessions/ui-state {"action":"set","ids":[...],"archived":true|false}
     */
    suspend fun setSessionArchived(sessionId: String, archived: Boolean): Result<Boolean> =
        setSessionUiFlag(listOf(sessionId), "archived", archived)

    /** 批量归档 / 取消归档（例如「归档 7 天前的会话」一次提交全部 id） */
    suspend fun setSessionsArchived(sessionIds: List<String>, archived: Boolean): Result<Boolean> =
        setSessionUiFlag(sessionIds, "archived", archived)

    /**
     * 项目（工作区）置顶 / 取消置顶：{"action":"pin-project","projectKey":..,"root":..,"pinned":bool}
     * （实测：缺 root 会 400 "root must be a non-empty string"）
     */
    suspend fun setProjectPinned(projectKey: String, root: String, pinned: Boolean): Result<Boolean> =
        postUiState(
            JsonObject().apply {
                addProperty("action", "pin-project")
                addProperty("projectKey", projectKey)
                addProperty("root", root)
                addProperty("pinned", pinned)
            }
        )

    /** 项目排序：把 projectKey 移到 anchorKey 之前/之后（pi 侧 move-project） */
    suspend fun moveProject(projectKey: String, anchorKey: String): Result<Boolean> =
        postUiState(
            JsonObject().apply {
                addProperty("action", "move-project")
                addProperty("projectKey", projectKey)
                addProperty("anchorKey", anchorKey)
            }
        )

    private suspend fun postUiState(payload: JsonObject): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/ui-state")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    val detail = resp.body?.string()?.take(200) ?: ""
                    Result.failure(
                        IOException("同步到 PC 失败 (${resp.code})" + if (detail.isNotBlank()) "：$detail" else "")
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 重命名会话：POST /api/agent/{id} {"type":"set_session_name","name":"..."}
     *
     * pi 把它写进会话 jsonl 的 header，`GET /api/sessions` 的 `name` 字段随即变化 ——
     * App 之前只写本地 SharedPreferences，所以在 PC 上看不到手机改的名字。
     */
    suspend fun setSessionName(sessionId: String, name: String): Result<Boolean> {
        val clean = name.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("会话名称不能为空"))
        return sendAgentAction(
            sessionId,
            mapOf("type" to "set_session_name", "name" to clean)
        )
    }

    /**
     * 把文件上传到 PC 上的工作目录：
     * POST /api/files/<目录路径>?type=upload&conflict=overwrite
     * multipart/form-data，字段名固定为 `files`（可重复）。
     * 服务端限制：单文件 ≤25MB，合计 ≤100MB；返回 {uploaded, skipped, errors}（有失败时 207）。
     */
    suspend fun uploadFiles(
        directoryPath: String,
        files: List<UploadFileInput>,
        conflict: String = "overwrite"
    ): Result<UploadFilesResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (directoryPath.isBlank()) return@withContext Result.failure(Exception("会话缺少工作目录，无法上传"))
        if (files.isEmpty()) return@withContext Result.success(UploadFilesResult())
        try {
            val dirSegment = URLEncoder.encode(directoryPath.trim().replace('\\', '/'), "UTF-8")
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            files.forEach { f ->
                builder.addFormDataPart(
                    "files",
                    f.name,
                    f.bytes.toRequestBody("application/octet-stream".toMediaType())
                )
            }
            val req = Request.Builder()
                .url("$baseUrl/api/files/$dirSegment?type=upload&conflict=$conflict")
                .post(builder.build())
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(bodyStr).asJsonObject }.getOrNull()
                if (json == null) {
                    return@withContext Result.failure(IOException("上传响应无法解析 (${resp.code})"))
                }
                if (!resp.isSuccessful && resp.code != 207) {
                    val err = json.get("error")?.takeIf { !it.isJsonNull }?.asString ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException("上传失败：$err"))
                }
                fun strList(key: String) = json.get(key)?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { runCatching { it.asString }.getOrNull() } ?: emptyList()
                val errors = json.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { el ->
                        runCatching {
                            val o = el.asJsonObject
                            (o.get("name")?.asString ?: "?") + ": " + (o.get("error")?.asString ?: "")
                        }.getOrNull()
                    } ?: emptyList()
                Result.success(
                    UploadFilesResult(
                        uploaded = strList("uploaded"),
                        skipped = strList("skipped"),
                        errors = errors
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun setSessionUiFlag(sessionIds: List<String>, key: String, value: Boolean): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            val ids = sessionIds.filter { it.isNotBlank() }
            if (ids.isEmpty()) return@withContext Result.failure(Exception("会话ID不能为空"))
            try {
                val payload = JsonObject().apply {
                    addProperty("action", "set")
                    add("ids", JsonArray().apply { ids.forEach { add(it) } })
                    addProperty(key, value)
                }
                val req = Request.Builder()
                    .url("$baseUrl/api/sessions/ui-state")
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        Result.success(true)
                    } else {
                        val detail = resp.body?.string()?.take(200) ?: ""
                        Result.failure(
                            IOException(
                                "同步到 PC 失败 (${resp.code})" + if (detail.isNotBlank()) "：$detail" else ""
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun sendAgentAction(sessionId: String, payload: Map<String, Any>): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/agent/$sessionId").post(body).build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    // 优先用服务端的人类可读错误（如 "Model not found: CloseAI/deepseek-flash"），
                    // 否则原样显示 {error:…} 的 JSON 片段
                    val msg = runCatching {
                        JsonParser.parseString(errBody).asJsonObject.get("error")
                            ?.takeIf { !it.isJsonNull }?.asString
                    }.getOrNull() ?: "HTTP ${response.code}: $errBody"
                    Result.failure(IOException(msg))
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
        if (trimmed.isNotBlank() && trimmed != "默认工作区" && trimmed != "全部工作区" && (trimmed.contains('/') || trimmed.contains('\\'))) {
            return trimmed
        }
        val last = prefs.lastSelectedCwd.trim()
        if (last.isNotBlank() && last != "默认工作区" && last != "全部工作区" && (last.contains('/') || last.contains('\\'))) {
            return last
        }
        return ""
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

                        val installObj = obj.get("install")?.takeIf { it.isJsonObject }?.asJsonObject
                        val install = installObj?.let { runCatching { gson.fromJson(it, SkillInstallInfo::class.java) }.getOrNull() }

                        val sourceInfoObj = obj.get("sourceInfo")?.takeIf { it.isJsonObject }?.asJsonObject
                        val sourceInfo = sourceInfoObj?.let { runCatching { gson.fromJson(it, SkillSourceInfo::class.java) }.getOrNull() }

                        val scopeStr = install?.scope?.takeIf { it.isNotBlank() }
                            ?: obj.get("scope")?.takeIf { !it.isJsonNull }?.asString
                            ?: sourceInfo?.scope
                            ?: ""

                        val normPath = (resolvedFilePath ?: "").replace('\\', '/').lowercase()
                        val normCwd = validCwd.replace('\\', '/').lowercase().trimEnd('/')

                        val isProject = when {
                            scopeStr.equals("project", true) -> true
                            scopeStr.equals("workspace", true) -> true
                            sourceInfo?.source.equals("project", true) -> true
                            scopeStr.equals("global", true) || scopeStr.equals("user", true) -> false
                            normCwd.isNotBlank() && normPath.startsWith(normCwd) -> true
                            normPath.contains("/.pi/skills/") || (normPath.contains("/skills/") && !normPath.contains("/.pi/agent/") && !normPath.contains("/.agents/")) -> true
                            else -> false
                        }
                        val resolvedScope = if (isProject) "project" else "global"

                        list.add(
                            SkillInfo(
                                name = name,
                                description = description,
                                filePath = resolvedFilePath,
                                baseDir = baseDir,
                                enabled = !disabled,
                                category = if (isProject) "项目技能" else "全局技能",
                                disableModelInvocation = disabled,
                                content = inlineContent,
                                scope = resolvedScope,
                                install = install,
                                sourceInfo = sourceInfo
                            )
                        )
                    }
                    list
                }
                if (!parsedList.isNullOrEmpty()) {
                    return@withContext Result.success(parsedList)
                }
            } catch (e: Exception) {
                // 逐个候选 URL 回退属正常流程；全部失败才会回退到内置示例数据，
                // 这里必须留痕，否则「接口挂了」会伪装成「加载成功」。
                Log.w("PiApiClient", "技能接口候选 URL 失败: ${e.message}")
            }
        }
        Log.w("PiApiClient", "加载技能列表失败，回退到内置示例数据")
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
            } catch (e: Exception) {
                Log.w("PiApiClient", "插件接口候选 URL 失败: ${e.message}")
            }
        }
        Log.w("PiApiClient", "加载插件列表失败，回退到内置示例数据")
        Result.success(getDefaultPlugins())
    }

    // ==========================================
    // 7. MCP 协议 (GET /api/mcp)
    // ==========================================

    suspend fun getMcpServers(cwd: String = ""): Result<Pair<McpCodeModeConfig, List<McpServerInfo>>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(Pair(McpCodeModeConfig(), emptyList()))
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
        // 这里曾经回退到一份写死的「hindsight / wren 已连接」列表：URL 指向
        // 127.0.0.1（在手机上等于手机自己），status 却是 connected，toolExposureCount
        // 也是编的。任何一次网络抖动都会让界面显示出一台根本不存在的健康服务，
        // 比直接显示「加载失败」糟糕得多。改为返回空列表。
        Log.w("PiApiClient", "加载 MCP 配置失败，返回空列表（不再伪造默认服务器）")
        Result.success(Pair(McpCodeModeConfig(), emptyList()))
    }

    private fun getDefaultCatalog() = DynamicModelCatalog(
        models = listOf(
            ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", 200000L, 8192L, supportsThinking = true, supportsVision = true, isActive = true),
            ModelInfo("deepseek-flash", "DeepSeek Flash", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("gemini-3.8-flash-high", "Gemini 3.8 Flash High", "codywon", 1000000L, 16384L, supportsThinking = true, supportsVision = true)
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
                                    val isRunning = sObj.get("isRunning")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    val isStreaming = sObj.get("isStreaming")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                    return@withContext Result.success(isRunning || isStreaming)
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
        } catch (e: Exception) {
            // 刷新失败仍返回当前目录，但要说清「这次刷新没生效」
            Log.w("PiApiClient", "刷新模型目录失败，返回本地已缓存目录: ${e.message}")
        }
        getDynamicModels()
    }

    /**
     * 读取 PC 上 pi 凭据库的真实配置状态 (GET /api/auth/providers)。
     *
     * 这是「Key 到底配没配」的唯一可信来源：pi 把凭据存在 `~/.pi/agent/auth.json`
     * （source = stored），也可能来自环境变量（env）或 models.json（models_json_key），
     * 而 models.json 里的 `apiKey` 字段往往根本不存在。此前设置页只读 models.json，
     * 所以在 pi-web 里配好 Key 之后，App 仍然一直显示「未配置 Key」。
     *
     * 返回 map：providerId(小写) -> ProviderAuthState。
     */
    suspend fun getProviderAuthStates(): Result<Map<String, ProviderAuthState>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/auth/providers").get().build()
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("读取凭据状态失败 (${response.code})"))
                }
                val body = response.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: return@withContext Result.failure(IOException("凭据状态响应无法解析"))
                val result = mutableMapOf<String, ProviderAuthState>()
                val arr = json.get("apiKeyProviders")?.takeIf { it.isJsonArray }?.asJsonArray
                if (arr != null) {
                    for (el in arr) {
                        if (!el.isJsonObject) continue
                        val o = el.asJsonObject
                        val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                        if (id.isBlank()) continue
                        result[id.lowercase()] = ProviderAuthState(
                            providerId = id,
                            configured = o.get("configured")?.takeIf { !it.isJsonNull }?.asBoolean == true,
                            source = o.get("source")?.takeIf { !it.isJsonNull }?.asString,
                            modelCount = o.get("modelCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                        )
                    }
                }
                Result.success(result)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 清除某个 Provider 已保存的 API Key (DELETE /api/auth/api-key/{provider})。
     *
     * 手机端一旦写入过错误的 Key，pi 会把每一个请求都拿它去发 → PC 上所有会话
     * 直接 401 "Invalid API key"。没有这个删除入口，用户只能去 PC 上手工改
     * auth.json 才能恢复。
     */
    suspend fun deleteProviderApiKey(provider: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val encodedProvider = URLEncoder.encode(provider.trim(), "UTF-8")
            val req = Request.Builder().url("$baseUrl/api/auth/api-key/$encodedProvider").delete().build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) Result.success(true)
                else Result.failure(IOException("清除 API Key 失败 (${response.code})"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 校验一个「候选」 API Key 是否真的能被该 Provider 接受。
     *
     * 实现方式：借用 pi-web 的 POST /api/models-config/test —— 它会用临时 models.json
     * 建一个一次性 ModelRuntime 并真发一轮 16 token 的补全请求。
     *
     * 关键细节：providerName 必须换成一次性名字（见 VERIFY_PROVIDER_SUFFIX）。若沿用真实
     * provider 名，pi 的 getAuth 会优先取**已保存**的凭据，请求里带的候选 Key 会被忽略，
     * 于是「错 Key 也能测通过」——这个坑已经实测确认过。用临时名字则没有任何已存凭据可
     * 遮蔽，候选 Key 才真正生效（实测：错 Key 0.2s 内返回 401 "Invalid API key"）。
     */
    suspend fun verifyProviderApiKey(
        provider: String,
        baseUrlEndpoint: String,
        apiType: String,
        modelId: String,
        candidateKey: String
    ): Result<ModelTestResult> {
        if (baseUrlEndpoint.isBlank() || modelId.isBlank()) {
            return Result.failure(Exception("该 Provider 缺少 Base URL 或模型，无法联网校验"))
        }
        val provObj = JsonObject().apply {
            addProperty("baseUrl", baseUrlEndpoint.trim())
            if (apiType.isNotBlank()) addProperty("api", apiType.trim())
            addProperty("apiKey", candidateKey.trim())
        }
        val modelObj = JsonObject().apply {
            addProperty("id", modelId.trim())
            addProperty("name", modelId.trim())
            addProperty("contextWindow", 128000)
            addProperty("maxTokens", 1024)
        }
        return testModelConfig("${provider.trim()}${VERIFY_PROVIDER_SUFFIX}", provObj, modelObj)
    }

    /**
     * 配置指定 Provider 的 API Key (POST /api/auth/api-key/{provider})
     *
     * 写入点只有这一个，所以格式自检也放在这里：pi 的 auth 路由不做任何校验就把值存进
     * auth.json，之后每个请求都拿它当 Bearer Token。一旦存进去的是错的/粘了一半的值，
     * PC 上所有会话立刻 401 "Invalid API key"（本机 2026-10-27 16:35 就这样被打挂过）。
     * 因此 UI 层必须先走 [verifyProviderApiKey] 实测，这里再兜一层基础自检。
     */
    suspend fun setProviderApiKey(provider: String, apiKey: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val cleanKey = apiKey.trim()
        checkApiKeyFormat(cleanKey)?.let { return@withContext Result.failure(IllegalArgumentException(it)) }
        try {
            val encodedProvider = URLEncoder.encode(provider.trim(), "UTF-8")
            val body = gson.toJson(mapOf("apiKey" to cleanKey)).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/auth/api-key/$encodedProvider").post(body).build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    // pi-web 的失败原因（如 does not support API key login）在响应体里，别丢掉
                    val detail = response.body?.string()?.takeIf { it.isNotBlank() }?.take(200) ?: ""
                    Result.failure(
                        IOException(
                            "配置 API Key 失败 (${response.code})" + if (detail.isNotBlank()) "：$detail" else ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 写入前的格式自检。返回 null 表示通过，否则返回给用户看的拒绝原因。
     *
     * 只做「显然不是密钥」的拦截，不做长度/字符集规则：中转站的 Key 可能非常短
     * （本机实际使用的就是一个 7 位的自定义串，甚至与 Provider 同名），任何长度或
     * 形似规则都会把合法 Key 拦在门外。真正判断 Key 对不对的，是 [verifyProviderApiKey]
     * 那一步的联网实测。
     */
    private fun checkApiKeyFormat(key: String): String? {
        if (key.isEmpty()) return "API Key 不能为空"
        if (key.any { it.isWhitespace() }) return "API Key 里不能有空格或换行，请重新粘贴"
        return null
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
     * 批量切换技能启用/禁用状态 (PATCH /api/skills)
     * 支持项目或全局技能一键批量对模型可见/隐藏
     */
    suspend fun toggleSkillsBatchOnServer(filePaths: List<String>, disableModelInvocation: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank() || filePaths.isEmpty()) return@withContext Result.success(true)
        try {
            val payload = mapOf(
                "filePaths" to filePaths,
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
                } catch (e: Exception) {
                    Log.w("PiApiClient", "读取 SKILL.md 候选路径失败: ${e.message}")
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
     * 搜索 skills.sh 技能市场所有技能 (POST /api/skills/search)
     */
    suspend fun searchSkills(query: String): Result<List<SkillSearchResult>> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext Result.success(emptyList())
        val baseUrl = prefs.serverUrl

        // 1. 优先通过网关与 pi-web 的 /api/skills/search 代理搜索
        if (baseUrl.isNotBlank()) {
            try {
                val payload = mapOf("query" to q)
                val req = Request.Builder()
                    .url("$baseUrl/api/skills/search")
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()
                val parsed = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use null
                    val resultsArr = root.getAsJsonArray("results") ?: return@use null
                    val list = mutableListOf<SkillSearchResult>()
                    for (el in resultsArr) {
                        if (!el.isJsonObject) continue
                        val obj = el.asJsonObject
                        val pkg = obj.get("package")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val installs = obj.get("installs")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val url = obj.get("url")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        list.add(SkillSearchResult(`package` = pkg, installs = installs, url = url))
                    }
                    list
                }
                if (!parsed.isNullOrEmpty()) {
                    return@withContext Result.success(parsed)
                }
            } catch (e: Exception) {
                Log.w("PiApiClient", "本地网关技能搜索失败，尝试直连 skills.sh: ${e.message}")
            }
        }

        // 2. 兜底高可用：直接请求官方 skills.sh 开放 API
        try {
            val encQ = URLEncoder.encode(q, "UTF-8")
            val req = Request.Builder()
                .url("https://skills.sh/api/search?q=$encQ&limit=50")
                .header("User-Agent", "piDroid")
                .get()
                .build()
            val list = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use null
                val skillsArr = root.getAsJsonArray("skills") ?: return@use null
                val resList = mutableListOf<SkillSearchResult>()
                for (el in skillsArr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString?.trim() ?: continue
                    val source = obj.get("source")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString?.trim()
                    val repo = source ?: id ?: continue
                    val installsNum = obj.get("installs")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asLong ?: 0L
                    val installsStr = when {
                        installsNum >= 1_000_000 -> "${String.format("%.1f", installsNum / 1_000_000.0).replace(".0", "")}M installs"
                        installsNum >= 1_000 -> "${String.format("%.1f", installsNum / 1_000.0).replace(".0", "")}K installs"
                        installsNum > 0 -> "$installsNum installs"
                        else -> ""
                    }
                    val url = if (!id.isNullOrBlank()) "https://skills.sh/$id" else ""
                    resList.add(SkillSearchResult(`package` = "$repo@$name", installs = installsStr, url = url))
                }
                resList
            }
            if (list != null) return@withContext Result.success(list)
        } catch (e: Exception) {
            Log.w("PiApiClient", "直连 skills.sh 失败: ${e.message}")
        }

        Result.success(emptyList())
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
                "cwd" to sanitizeCwd(cwd)
            )
            val req = Request.Builder()
                .url("$baseUrl/api/skills/install")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    val errMsg = runCatching {
                        JsonParser.parseString(bodyStr).asJsonObject.get("error")?.asString
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "安装技能失败 (${resp.code})"
                    Result.failure(IOException(errMsg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 检查技能更新 (POST /api/skills/check)
     */
    suspend fun checkSkillUpdates(packageSource: String? = null, scope: String? = null, cwd: String = ""): Result<List<SkillUpdateInfo>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(emptyList())
        try {
            val payload = mutableMapOf<String, Any?>("cwd" to sanitizeCwd(cwd))
            if (!packageSource.isNullOrBlank() && !scope.isNullOrBlank()) {
                payload["package"] = packageSource
                payload["scope"] = scope
            }
            val req = Request.Builder()
                .url("$baseUrl/api/skills/check")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            val list = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<SkillUpdateInfo>()
                val body = resp.body?.string() ?: return@use emptyList<SkillUpdateInfo>()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return@use emptyList<SkillUpdateInfo>()
                val arr = root.getAsJsonArray("updates") ?: return@use emptyList<SkillUpdateInfo>()
                val resList = mutableListOf<SkillUpdateInfo>()
                for (el in arr) {
                    if (!el.isJsonObject) continue
                    val obj = el.asJsonObject
                    val pkg = obj.get("package")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    val sc = obj.get("scope")?.takeIf { !it.isJsonNull }?.asString ?: "global"
                    val st = obj.get("state")?.takeIf { !it.isJsonNull }?.asString ?: "up-to-date"
                    val cur = obj.get("currentVersion")?.takeIf { !it.isJsonNull }?.asString
                    val lat = obj.get("latestVersion")?.takeIf { !it.isJsonNull }?.asString
                    val msg = obj.get("message")?.takeIf { !it.isJsonNull }?.asString
                    resList.add(SkillUpdateInfo(`package` = pkg, scope = sc, state = st, currentVersion = cur, latestVersion = lat, message = msg))
                }
                resList
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 更新指定技能 (POST /api/skills/update)
     */
    suspend fun updateSkill(packageSource: String, scope: String = "global", cwd: String = ""): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = mapOf(
                "package" to packageSource.trim(),
                "scope" to scope,
                "cwd" to sanitizeCwd(cwd)
            )
            val req = Request.Builder()
                .url("$baseUrl/api/skills/update")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (resp.isSuccessful) {
                    Result.success(true)
                } else {
                    val errMsg = runCatching {
                        JsonParser.parseString(bodyStr).asJsonObject.get("error")?.asString
                    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "更新技能失败 (${resp.code})"
                    Result.failure(IOException(errMsg))
                }
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
                    // pi 在会话运行中会明确拒绝（HTTP 500 + "Cannot change tools while the session is running"），
                    // 这不是故障，翻译成人话，别把 500 直接糊到用户脸上
                    val serverMsg = runCatching {
                        JsonParser.parseString(bodyStr).asJsonObject.get("error")?.asString
                    }.getOrNull() ?: ""
                    val friendly = when {
                        serverMsg.contains("while the session is running", ignoreCase = true) ->
                            "任务运行中不能切换权限模式，请等本轮结束后再切换"
                        serverMsg.isNotBlank() -> serverMsg
                        else -> "权限切换失败 (HTTP ${resp.code})"
                    }
                    Result.failure(IOException(friendly))
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

    // ==========================================
    // 3.1 设置全局默认模型与思考档位 (1:1 对标 pi-web 的 PUT /api/models/default)
    // 持久化写入服务端的 ~/.pi/agent/settings.json
    // ==========================================

    
    /**
     * 1:1 对标 pi-web 的 POST /api/models-config/discover 接口：
     * 从服务商端点自动拉取 /v1/models 可用模型列表，支持双引擎 (网关代理 + 客户端直连 fallback)
     */
    suspend fun discoverProviderModels(
        providerName: String,
        baseUrl: String,
        apiKey: String,
        api: String = "openai-completions"
    ): Result<DiscoverModelsResult> = withContext(Dispatchers.IO) {
        val serverUrl = prefs.serverUrl
        // 尝试 1：优先通过网关 /api/models-config/discover 转发
        if (serverUrl.isNotBlank()) {
            try {
                val reqBody = JsonObject().apply {
                    addProperty("providerName", providerName)
                    val provObj = JsonObject().apply {
                        if (baseUrl.isNotBlank()) addProperty("baseUrl", baseUrl)
                        if (apiKey.isNotBlank()) addProperty("apiKey", apiKey)
                        if (api.isNotBlank()) addProperty("api", api)
                    }
                    add("provider", provObj)
                }
                val req = Request.Builder()
                    .url("$serverUrl/api/models-config/discover")
                    .post(reqBody.toString().toRequestBody(jsonMediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JsonParser.parseString(body).asJsonObject
                        val list = mutableListOf<DiscoveredModelItem>()
                        if (json.has("models") && json.get("models").isJsonArray) {
                            for (item in json.getAsJsonArray("models")) {
                                if (!item.isJsonObject) continue
                                val obj = item.asJsonObject
                                val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                val name = obj.get("name")?.takeIf { !it.isJsonNull }?.asString ?: id
                                if (id.isNotBlank()) {
                                    list.add(DiscoveredModelItem(id = id, name = name))
                                }
                            }
                        }
                        if (list.isNotEmpty()) {
                            val ep = json.get("endpoint")?.takeIf { !it.isJsonNull }?.asString ?: baseUrl
                            return@withContext Result.success(DiscoverModelsResult(models = list, endpoint = ep))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PiApiClient", "上游 /api/models/discover 失败，转直连 fallback: ${e.message}")
            }
        }

        // 尝试 2：客户端直接向上游发起 /v1/models 请求 (标准 OpenAI 协议 fallback)
        if (baseUrl.isNotBlank()) {
            try {
                val cleanBase = baseUrl.trim().trimEnd('/')
                val targetUrl = if (cleanBase.endsWith("/v1")) "$cleanBase/models" else if (cleanBase.endsWith("/models")) cleanBase else "$cleanBase/v1/models"
                val reqBuilder = Request.Builder().url(targetUrl).get()
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer ${apiKey.trim()}")
                }
                val directClient = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                directClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JsonParser.parseString(body).asJsonObject
                        val list = mutableListOf<DiscoveredModelItem>()
                        if (json.has("data") && json.get("data").isJsonArray) {
                            for (item in json.getAsJsonArray("data")) {
                                if (!item.isJsonObject) continue
                                val id = item.asJsonObject.get("id")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                if (id.isNotBlank()) {
                                    list.add(DiscoveredModelItem(id = id, name = id))
                                }
                            }
                        }
                        if (list.isNotEmpty()) {
                            return@withContext Result.success(DiscoverModelsResult(models = list, endpoint = targetUrl))
                        }
                    } else {
                        return@withContext Result.failure(Exception("上游服务返回 HTTP ${resp.code}"))
                    }
                }
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("拉取模型失败: ${e.message}"))
            }
        }

        Result.failure(Exception("未能从提供商端点拉取到模型列表，请检查 Base URL 与 API Key"))
    }

    suspend fun setDefaultModel(provider: String, modelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val bodyObj = JsonObject().apply {
                addProperty("provider", provider)
                addProperty("modelId", modelId)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/models/default")
                .put(bodyObj.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    prefs.defaultProvider = provider
                    prefs.defaultModelId = modelId
                    Result.success(true)
                } else {
                    val errBody = resp.body?.string() ?: ""
                    val msg = runCatching { JsonParser.parseString(errBody).asJsonObject.get("error").asString }.getOrNull() ?: "HTTP ${resp.code}"
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun setDefaultThinkingLevel(level: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            val bodyObj = JsonObject().apply {
                addProperty("thinkingLevel", level)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/models/default")
                .put(bodyObj.toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    prefs.defaultThinkingLevel = level
                    Result.success(true)
                } else {
                    val errBody = resp.body?.string() ?: ""
                    val msg = runCatching { JsonParser.parseString(errBody).asJsonObject.get("error").asString }.getOrNull() ?: "HTTP ${resp.code}"
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

}
