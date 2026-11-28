package com.pi.app.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pi.app.data.model.*
import com.pi.app.data.storage.AppPreferences
import com.pi.app.prompt.PromptEnhancementCapability
import com.pi.app.prompt.PromptEnhancementContextMessage
import com.pi.app.prompt.PromptEnhancementException
import com.pi.app.prompt.PromptEnhancementResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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
    val leafId: String? = null,
    /**
     * 本会话钉住的工具清单（pi-web `GET /api/sessions/{id}` 的 `toolNames`）。
     * `null` = 会话没有钉住，跟随 settings.json 的 defaultTools；空清单 = 仅聊天。
     */
    val toolNames: List<String>? = null,
    /**
     * 服务端给的不透明新鲜度令牌（pi-web `lib/session-revision.ts`）。
     *
     * 只做等值比较：相同 = 磁盘/运行时都没变（本地已翻页的历史可以按住不动）；
     * `null` = 服务端判定快照不可靠，一律重取。见 [SessionRevision]。
     */
    val snapshotRevision: String? = null,
    val compactionRecords: List<CompactionRecord> = emptyList(),
    val branchTreeNodes: List<BranchTreeNode> = emptyList()
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

        /** 非 2xx 时随异常带出的正文长度：足够判断是 HTML 还是 JSON，又不至于把日志撑爆。 */
        private const val BODY_PREVIEW_CHARS = 512

        /** 缓存文件名里不能出现的字符（与 FileDownloader / SessionExporter 同一套规则）。 */
        private val CACHE_FILE_NAME_ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|]")

        /** `stdio` 传输的 MCP 命令按连续空白切成「命令 + 参数」。 */
        private val STDIO_COMMAND_WHITESPACE_RUN = Regex("\\s+")

        /** 候选 Key 联网自检时使用的一次性 provider 名后缀（避开已保存凭据的遮蔽）。 */
        private const val VERIFY_PROVIDER_SUFFIX = "-key-verify"

        /** pi 允许单条消息携带的图片上限（服务端校验：最多 10 张、每张 ≤10MB）。 */
        const val MAX_PROMPT_IMAGES = 10

        /** 大文件传输的重试次数（首次 + 1 次重试）。见 [getFileBytes]。 */
        private const val FILE_TRANSFER_ATTEMPTS = 2

        /** 内存快照缓存上限（见 [cacheSessionDetail]）。 */
        private const val MAX_CACHED_SESSIONS = 4

        private fun JsonObject.safeString(key: String): String? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

        private fun JsonObject.safeBoolean(key: String): Boolean? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asBoolean }.getOrNull()
            }

        private fun JsonObject.safeInt(key: String): Int? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asInt }.getOrNull()
            }

        private fun JsonObject.safeLong(key: String): Long? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asLong }.getOrNull()
            }

        private fun JsonObject.safeDouble(key: String): Double? =
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.let {
                runCatching { it.asDouble }.getOrNull()
            }

        private fun JsonObject.safeObject(key: String): JsonObject? =
            get(key)?.takeIf { it.isJsonObject }?.asJsonObject

        private fun JsonObject.safeArray(key: String): JsonArray? =
            get(key)?.takeIf { it.isJsonArray }?.asJsonArray

        private fun JsonElement?.asSafeString(): String? =
            this?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

        private fun JsonElement?.asSafeObject(): JsonObject? =
            this?.takeIf { it.isJsonObject }?.asJsonObject
    }
    private val sessionHistoryCache = object : LinkedHashMap<String, SessionHistoryData>(
        MAX_CACHED_SESSIONS + 1,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SessionHistoryData>?): Boolean {
            return size > MAX_CACHED_SESSIONS
        }
    }

    /**
     * 内存里的会话快照缓存（已解析的 ChatItem 列表，含工具输出/媒体）只留最近几次打开的会话。
     *
     * 不设上限的话它会随「打开过的会话数」线性增长 —— 长时间使用后进程被系统回收，
     * 用户看到的就是「看着一个任务，过一会直接闪退」。采用按访问顺序淘汰的 LRU。
     */
    private fun cacheSessionDetail(sessionId: String, data: SessionHistoryData) {
        synchronized(sessionHistoryCache) {
            sessionHistoryCache[sessionId] = data
        }
    }

    /** 会话树形状认不出来的日志只报一次（进程内），见 [warnUnknownTreeShape]。 */
    @Volatile
    private var warnedUnknownTreeShape = false

    /**
     * 模型目录（models.json + 内置注册表）里各模型的真实 contextWindow，键为 `provider:modelId` 小写。
     *
     * 用途：`GET /api/sessions/{id}` 的 `context.model` 只给 provider/modelId、**不带 contextWindow**，
     * 会话快照算不出占用率的分母。之前直接兜底 200000，于是 1M 窗口的模型一律显示 100%。
     */
    @Volatile
    private var catalogContextWindows: Map<String, Long> = emptyMap()

    fun getCachedSessionDetail(sessionId: String): SessionHistoryData? =
        synchronized(sessionHistoryCache) { sessionHistoryCache[sessionId] }

    fun clearSessionCache(sessionId: String? = null) {
        synchronized(sessionHistoryCache) {
            if (sessionId == null) {
                sessionHistoryCache.clear()
            } else {
                sessionHistoryCache.remove(sessionId)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(prefs))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        // 经 Cloudflare 隧道时是 HTTP/2：ping 让 OkHttp 自己发现「半开连接」，
        // 同时把运营商 NAT 映射踩醒。没有它时，被静默丢弃的旧连接只能靠下一次请求
        // 撞上去、等超时才能发现 —— 那正是「获取会话失败: timeout」的直接来源。
        // HTTP/1.1（局域网直连）下该参数无副作用。
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    internal val okHttpClient: OkHttpClient get() = client
    internal val fileOkHttpClient: OkHttpClient get() = fileClient

    /** 文件变更监听 SSE 专用：长连接无读取超时 */
    private val sseStreamClient = client.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * 轮询 / 首屏读取专用客户端：共用连接池与鉴权，但给「整条请求（含读完 body）」加硬超时。
     *
     * 没有 callTimeout 时，readTimeout=120s 只在「一个字节都读不到」才触发。隧道半死、CDN 卡住
     * 或者服务端边算边吐的情况下，一次 300KB 的会话列表 / 快照可以被拖成两分钟的「没有进展的转圈」。
     * 12s 的依据：实测同一条 303KB 列表经 Cloudflare 固定隧道 gzip 后 81KB、中位 1.7s；
     * 留给弱网 4G 一个 7 倍余量。超时后由 [readGet] 换新连接重试一次。
     *
     * 这里只给这些**幂等只读**请求加时限；写操作（发消息 / 压缩 / 生成标题等可能长时间等模型）
     * 仍走 [client]，不受影响。
     */
    private val readClient = client.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    /**
     * 提示词增强专用客户端。
     * 不能复用 [readClient]（12s 会先于模型返回把请求掐断），也不能无限等。
     */
    private val enhanceClient = client.newBuilder()
        // 服务端硬超时 30s（实测轻量问答 2~4s）。客户端 35s 作为「服务端没回话」的兜底，
        // 比服务端多 5s，避免两边同时掐断时拿到半截响应。
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    /**
     * 首屏会话快照专用：同样带重试，但给更长预算。
     *
     * 单个会话可能很大（本机实测最大的会话文件 64MB，首屏快照要解完整个 jsonl：直连 1.1s /
     * 隧道 4.4s，线上 gzip 后 109KB），12s 对这种会话偏紧，给到 30s。
     */
    private val snapshotClient = client.newBuilder()
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 大文件传输专用客户端（图片 / PDF / Office 的原始字节）。
     *
     * 为什么不能用 [client]：
     * 公网链路是「App → nginx(h2, TLS) → frp → 网关 → pi-web」。实测经由 nginx 的 HTTP/2
     * 在向前端持续推送几 MB 的二进制流时，偶发 `stream was reset: INTERNAL_ERROR`
     * （ngx_http_v2 在 upstream 分段写出/超时时会发 RST_STREAM），而同一请求改成
     * HTTP/1.1 则稳定返回完整 200 正文。文件下载走的是幂等 GET、没有多路复用收益，
     * 因此这里显式降级到 HTTP/1.1；另外 readTimeout 放宽到 90s（弱网下 6MB 图片
     * 单次 read 间隔可能远超主 client 的 120s 之外的写入停顿）。
     */
    private val fileClient = client.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * 手动压缩上下文专用。
     *
     * **刻意没有任何客户端超时**，与 pi-web 完全一致：它的 agent 命令客户端就是一个裸
     * `fetch()`（无 AbortController / 无 signal / 无超时），压缩跑多久就等多久。
     * 对齐依据（pi-web 0.13.0 前端包）：
     * ```js
     * async function aI(id, payload) {
     *   const res = await fetch(`/api/agent/${id}`, { method: "POST", ... })
     *   ...
     * }
     * ```
     * 而这正是之前「压缩假失败」的来源：主 [client] 的 readTimeout 只有 120s，而本机实测同一
     * 大会话的四次压缩耗时 71s / 189s / 296s / 333s（摘要沿用会话 thinkingLevel，max 档更慢），
     * 超过一半会被客户端判成 `timeout`，界面报「压缩失败」，服务端却写盘成功。
     *
     * OkHttp 的 `readTimeout(0)` 就是「不超时」。真正的逃生口与 pi-web 一致：压缩中把压缩
     * 按钮换成「停止压缩」，发 `{"type":"abort_compaction"}`（见 [abortCompaction]）。
     */
    private val compactClient = client.newBuilder()
        .readTimeout(0, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /** 只读 GET 的结果：状态码 + 已解压的响应正文。 */
    private data class ReadPayload(val code: Int, val body: String)

    /**
     * 所有幂等只读 GET 的唯一出口：硬超时 + 传输层失败自动重试一次 + 可排查的日志。
     *
     * 为什么必须重试：OkHttp 不会重试读超时（`isRecoverable` 里 `SocketTimeoutException`
     * 且请求已开始发送就直接放弃），而手机现实里最常遇到的失败恰恰是「连接被静默弄丢」——
     * 隧道重建 / 蜂窝网切基站 / 运营商 NAT 把旧的 TCP 映射回收，旧 socket 不发 RST，
     * 于是请求写进黑洞，只能等到超时。重连一次就能好，但旧代码一次不重试，直接把
     * “获取会话失败: timeout” 甩给用户。
     *
     * 重试前 `evictAll()`：把连接池里的可疑旧连接全部清掉，保证第二次一定是新连接，
     * 否则复用同一条半开连接只会再超一次。SSE 用的是独立连接池（PiSseClient），不受影响。
     *
     * [attempts] 供已有自己重试策略的调用方传 1（避免一次同步叠加成 4 个请求 × 30s）。
     */
    private fun readGet(
        req: Request,
        httpClient: OkHttpClient = readClient,
        attempts: Int = 2
    ): ReadPayload {
        val maxAttempts = attempts.coerceIn(1, 2)
        var lastError: IOException? = null
        for (attempt in 1..maxAttempts) {
            val startedAt = System.currentTimeMillis()
            try {
                httpClient.newCall(req).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    val elapsed = System.currentTimeMillis() - startedAt
                    if (elapsed > 5_000L) {
                        // 慢但成功：留一条样本，便于判断“超时”到底是隧道还是服务端
                        Log.i("PiNet", "GET ${req.url.encodedPath} ok in ${elapsed}ms (${body.length}B, attempt $attempt)")
                    }
                    return ReadPayload(response.code, body)
                }
            } catch (e: IOException) {
                lastError = e
                val elapsed = System.currentTimeMillis() - startedAt
                Log.w(
                    "PiNet",
                    "GET ${req.url.encodedPath} failed in ${elapsed}ms (attempt $attempt/$maxAttempts): " +
                        "${e.javaClass.simpleName}: ${e.message}"
                )
                if (attempt == maxAttempts) break
                httpClient.connectionPool.evictAll()
            }
        }
        throw lastError ?: IOException("读取失败")
    }

    /**
     * 非 2xx 的统一失败：把状态码 + 路径 + 正文片段一起带出去。
     *
     * 必须带上正文：隧道掉线时 frp 用自己的 404 HTML 页面应答，而接口真不存在时
     * pi-web 回的是 JSON。只看状态码会把“隧道还没连回来”误判成“地址写错”。
     * 归类逻辑见 [GatewayErrorClassifier]。
     */
    private fun readFailure(payload: ReadPayload, path: String): IOException =
        GatewayHttpException(payload.code, path, payload.body.take(BODY_PREVIEW_CHARS))

    suspend fun ping(targetUrl: String? = null, token: String? = null): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = (targetUrl ?: prefs.serverUrl).trim().trimEnd('/')
        val authToken = (token ?: prefs.authToken).trim()
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
                    // 配对/试连也走同一套语义：这里的 404 代表“隧道没起来”，不是密钥问题
                    Result.failure(
                        GatewayHttpException(
                            response.code,
                            "/api/ping",
                            runCatching { response.peekBody(BODY_PREVIEW_CHARS.toLong()).string() }.getOrNull()
                        )
                    )
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
            // 只读轮询：走 readGet（单次 12s 硬超时 + 传输失败换新连接重试一次）
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(readFailure(payload, "/api/sessions"))
            }
            val jsonElement = JsonParser.parseString(payload.body)
            val type = object : TypeToken<List<Session>>() {}.type
            var runningIds = emptySet<String>()
            var awaitingIds = emptySet<String>()

            val rawList: List<Session> = if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                obj.safeArray("runningSessionIds")?.let { arr ->
                    runningIds = arr.mapNotNull { it.asSafeString() }.toSet()
                }
                obj.safeArray("awaitingInputSessionIds")?.let { arr ->
                    awaitingIds = arr.mapNotNull { it.asSafeString() }.toSet()
                }
                obj.safeArray("sessions")?.let { arr ->
                    gson.fromJson<List<Session>>(arr, type)
                } ?: emptyList()
            } else if (jsonElement.isJsonArray) {
                gson.fromJson(jsonElement, type) ?: emptyList()
            } else {
                emptyList()
            }

            // 侧聊（session relation.kind = "side"）与子代理（"subagent"）在 pi-web 里
            // 从不是侧栏行 —— 它自己的 `isHiddenRelation()` 就是按这两个 kind 过滤的。
            // 不过滤的话，用户在 PC 上开一个侧聊，手机上就会多出一条看起来像普通会话
            // 的条目（同一段对话两份入口）。
            // 旧版 pi-web 不下发 `relation` 时为 null，过滤等于不生效，向后兼容。
            val visibleList = rawList.filter { !it.isHiddenFamilyMember() }

            val enrichedList = visibleList.map { s ->
                s.copy(
                    isRunning = runningIds.contains(s.id),
                    awaitingInput = s.awaitingInput || awaitingIds.contains(s.id)
                )
            }
            Result.success(enrichedList)
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
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(readFailure(payload, "/api/agent/running"))
            }
            val root = runCatching {
                JsonParser.parseString(payload.body).asSafeObject()
            }.getOrNull() ?: return@withContext Result.failure(IOException("运行态响应无法解析"))
            fun idSet(key: String): Set<String> =
                root.safeArray(key)
                    ?.mapNotNull { it.asSafeString() }
                    ?.toSet()
                    ?: emptySet()
            Result.success(
                RunningSessionsSnapshot(
                    sessionListVersion = root.safeLong("sessionListVersion") ?: 0L,
                    runningIds = idSet("runningSessionIds"),
                    awaitingInputIds = idSet("awaitingInputSessionIds"),
                    subagentsActiveIds = idSet("subagentsActiveSessionIds"),
                    completionSuppressedIds = idSet("completionNotificationSuppressedSessionIds")
                )
            )
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
        val json = JsonParser.parseString(body).asSafeObject()
            ?: throw IOException("会话快照响应格式异常")

        // 快照没有模型时保持未知，不能捏造 GPT 后再覆盖真实会话模型。
        var currentProvider = ""
        var currentModelId = ""
        var currentThinkingLevel = ThinkingLevel.HIGH
        var modelContextWindow = 0L

        var lastAssistantContextTokens = 0L
        var lastAssistantInTokens = 0L
        var lastAssistantOutTokens = 0L

        val ctxObj = json.safeObject("context")

        if (ctxObj != null) {
            ctxObj.safeObject("model")?.let { mObj ->
                currentProvider = mObj.safeString("provider") ?: currentProvider
                currentModelId = mObj.safeString("modelId")
                    ?: mObj.safeString("id")
                    ?: currentModelId
                modelContextWindow = mObj.safeLong("contextWindow") ?: 0L
            }
            ctxObj.safeString("thinkingLevel")?.let {
                currentThinkingLevel = ThinkingLevel.fromValue(it)
            }
        }

        // 收集完整的原始消息数组：
        // 1) 如果服务端 /api/sessions/[id] 默认只返回最近 50 条 (hasMore == true)，主动通过 /api/sessions/[id]/context?tail=... 及 before=... 向上翻页拉取全量历史
        // 2) 同时支持解析 entries（包含上下文压缩 Compaction 之前的完整早期对话与压缩摘要节点），确保用户点击“展开全部”或“加载前50条”能看到从第 1 条提问开始的整个任务全貌
        val rawContextMessages = JsonArray()
        val initialCtxMsgs = ctxObj?.safeArray("messages")
        val initialEntryIds = ctxObj?.safeArray("entryIds")
        attachEntryIds(initialCtxMsgs, initialEntryIds)
        val initialHasMore = (ctxObj?.safeBoolean("hasMore") == true) ||
            (json.safeBoolean("hasMore") == true)
        // 活跃叶：与 pi-web `loadSession` 一致，直接用服务端给的 leafId（缺失就没有活跃叶）。
        val leafId = ctxObj?.safeString("leafId")
            ?: json.safeString("leafId")

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

        // 会话树：pi-web 0.13+ 的 `tree` 是**嵌套数组** `[{entry, children}]`（treeFormat 标记形态），
        // 旧版是 `tree.entries` / 顶层 `entries`。三者统一拍平——
        // 以前只认平铺形状，嵌套树直接落空，`entries` 分支形同死码：App 永远只能看到
        // `context.messages` 的尾部窗口，PC 上的整段执行过程在手机上没有入口。
        val treeEntries = SessionBranchWindow.flattenTree(json.get("tree"))
        if (treeEntries == null) {
            val rawTree = json.get("tree")
            val nonEmptyArray = rawTree != null && rawTree.isJsonArray && rawTree.asJsonArray.size() > 0
            if (nonEmptyArray) warnUnknownTreeShape(json)
        }
        val entriesArr = when {
            treeEntries != null && treeEntries.size() > 0 -> treeEntries
            else -> json.safeArray("entries") ?: json.safeArray("messages")
        }

        val normalizedEntryMessages = if (entriesArr != null && entriesArr.size() > 0) {
            extractChronologicalMessagesFromEntries(entriesArr, leafId)
        } else JsonArray()

        // 条目树比 context 窗口更完整时才用它（摘要树拍平后只有 id/parentId/type，不会命中）。
        val usedEntries = normalizedEntryMessages.size() > rawContextMessages.size()
        var droppedEntryCount = 0
        val chosenMessages = if (usedEntries) {
            // 切窗按 pi 的 tail 语义：**只数可见消息**（user/assistant/压缩里程碑），不数 toolResult。
            // 旧实现直接切最后 `tail` 个原始条目，工具密集的回合会被切成一副骨架。
            // 同时保证窗口不比 context 窗口更短（条目树可用不该成为首屏缩水）。
            // 注：`tail > 100` 是大批量路径（见上面 fetchFullContextPages），此时预算装不满，
            // 窗口会退化成整条分支 —— 这是有意的「全量」语义，不是疏漏。
            val roles = (0 until normalizedEntryMessages.size()).map { i ->
                roleOfMessage(normalizedEntryMessages.get(i))
            }
            val visibleStart = SessionBranchWindow.visibleTailStart(roles, tail)
            val floorStart = (normalizedEntryMessages.size() - rawContextMessages.size()).coerceAtLeast(0)
            val startIndex = minOf(visibleStart, floorStart)
            val sliced = JsonArray()
            for (i in startIndex until normalizedEntryMessages.size()) {
                sliced.add(normalizedEntryMessages.get(i))
            }
            droppedEntryCount = startIndex
            sliced
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
        // 「会话累计」这块到底有没有真的解析出来。汇总口径的回退必须**整体**判断，
        // 不能逐字段看 > 0：整段 prompt 全命中缓存时 `stats.tokens.input` 合法地为 0，
        // 逐字段回退会把单轮 input 拼进累计 cacheRead 的分母，命中率又被钉回 100%。
        var hasCumulativeTokens = false

        json.safeObject("stats")?.let { stats ->
            cost = stats.get("cost")?.takeIf { !it.isJsonNull }?.let { costElem ->
                if (costElem.isJsonPrimitive) {
                    runCatching { costElem.asDouble }.getOrDefault(0.0)
                } else if (costElem.isJsonObject) {
                    val cObj = costElem.asJsonObject
                    val total = cObj.safeDouble("total") ?: cObj.safeDouble("cost")
                    total ?: ((cObj.safeDouble("input") ?: 0.0) + (cObj.safeDouble("output") ?: 0.0))
                } else 0.0
            } ?: 0.0
            stats.safeObject("tokens")?.let { tok ->
                inTokens = tok.safeLong("input") ?: 0L
                outTokens = tok.safeLong("output") ?: 0L
                cacheRead = tok.safeLong("cacheRead") ?: 0L
                cacheWrite = tok.safeLong("cacheWrite") ?: 0L
                hasCumulativeTokens = true
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
            else -> ModelDefaults.DEFAULT_CONTEXT_WINDOW
        }

        // 上下文占用只能有一个真实来源：最后一条 assistant 消息的 usage（input + cacheRead）。
        // 它缺失时才退回会话**累计**输入（stats.tokens.input）—— 两者语义不同，但都是
        // 服务端实际给出的数值。旧实现还有第三跳：fallback 超窗时返回 `inTokens % 80000`
        // （下限 24000），把真实的 340K 显示成 20K，纯粹为了「看起来不爆表」。
        // 那是在 App 侧编造运行态，既让用户看不到超窗，也让排查“上下文怎么爆的”失去依据。
        val realContext = TokenStats.resolveContextTokens(
            authoritativeContextTokens = lastAssistantContextTokens,
            fallbackInputTokens = inTokens
        )

        // 输入/输出必须与 cacheRead/cacheWrite 同口径（会话累计），否则缓存率就是拿
        // 「累计命中」除以「单轮输入」：本会话 27.5 M cache R 配 169 in，比值 99.9994%，
        // 四舍五入后永远顶格 100.0%（详见 `TokenStats.cacheHitRate`）。上游 pi-web 的
        // 「平均缓存命中率」三个数全部取自 sessionStats.tokens，这里同源。
        // 累计缺失（旧网关不返回 stats.tokens）时才整体退回最后一条 assistant 消息的 usage，
        // 那是「有真实来源的退路」，而不是编造。上下文占用仍单独用 lastAssistant* 计算。
        val (summaryInTokens, summaryOutTokens) = TokenStats.resolveSummaryTokens(
            hasCumulativeTokens = hasCumulativeTokens,
            cumulativeInput = inTokens,
            cumulativeOutput = outTokens,
            lastAssistantInput = lastAssistantInTokens,
            lastAssistantOutput = lastAssistantOutTokens
        )

        val tokenStats = TokenStats(
            contextTokens = realContext,
            maxContextWindow = effectiveMaxContext,
            inputTokens = summaryInTokens,
            outputTokens = summaryOutTokens,
            cacheReadTokens = cacheRead,
            cacheWriteTokens = cacheWrite,
            cost = cost
        )

        // 翻页游标必须跟着**实际渲染窗口的第一条**走：用了条目树时窗口起点可能早于 context 窗口，
        // 继续用 context 的 oldestEntryId 会让下一页和已渲染内容重叠（同一段过程显示两遍）。
        val chosenOldestEntryId = if (usedEntries) {
            messageEntryId(chosenMessages) ?: extractOldestEntryId(ctxObj, initialCtxMsgs)
        } else {
            extractOldestEntryId(ctxObj, initialCtxMsgs)
        }
        val droppedTurns = if (droppedEntryCount > 0) {
            (0 until droppedEntryCount).count { i -> roleOfMessage(normalizedEntryMessages.get(i)) == "user" }
        } else 0
        val serverTurnsBefore = ctxObj?.safeInt("turnsBefore") ?: 0

        val compactions = extractCompactionRecords(json, ctxObj)
        val treeNodes = BranchTreeTopology.parseTreeJson(json.safeArray("tree"))

        return SessionHistoryData(
            messages = chatItems,
            provider = currentProvider,
            modelId = currentModelId,
            thinkingLevel = currentThinkingLevel,
            stats = tokenStats,
            // 窗口被条目树截过（droppedEntryCount > 0）就一定有更早的历史，
            // 不依赖服务端 hasMore 单一信号（旧版网关缺字段 / 游标刚好落页尾都会把它报成 false）。
            hasMoreOnServer = initialHasMore || droppedEntryCount > 0,
            oldestEntryId = chosenOldestEntryId,
            turnsBefore = if (droppedEntryCount > 0) maxOf(droppedTurns, serverTurnsBefore) else serverTurnsBefore,
            leafId = leafId,
            toolNames = readPinnedToolNames(json),
            // 不透明新鲜度令牌：只做等值比较（pi-web 的 revisionUnchanged 判据）
            snapshotRevision = json.safeString("snapshotRevision"),
            compactionRecords = compactions,
            branchTreeNodes = treeNodes
        )
    }

    private fun extractCompactionRecords(json: JsonObject, ctxObj: JsonObject?): List<CompactionRecord> {
        val records = mutableListOf<CompactionRecord>()
        val entriesArr = json.safeArray("entries") ?: ctxObj?.safeArray("entries")
        if (entriesArr != null) {
            for (el in entriesArr) {
                val obj = el.asSafeObject() ?: continue
                val type = obj.safeString("type")
                if (type == "compaction" || type == "compactionSummary") {
                    val id = obj.safeString("id") ?: ""
                    val summary = obj.safeString("summary") ?: ""
                    val ts = parseFlexibleTimestamp(obj.get("timestamp"))
                    val tokensBefore = obj.safeLong("tokensBefore") ?: 0L
                    val tokensAfter = obj.safeLong("tokensAfter") ?: 0L
                    val cost = obj.safeDouble("cost") ?: 0.0
                    records.add(CompactionRecord(id, ts, summary, tokensBefore, tokensAfter, cost))
                }
            }
        }
        return records
    }

    /**
     * 读会话钉住的工具清单：字段缺失 = 没钉住（`null`），数组 = 钉住的清单（可能是空数组 = 仅聊天）。
     * 不把「缺失」当成空清单，否则每个跟随 settings.json 的会话都会被显示成「仅聊天」。
     */
    private fun readPinnedToolNames(json: JsonObject): List<String>? {
        val arr = json.safeArray("toolNames") ?: return null
        return arr.mapNotNull { it.asSafeString() }
    }

    /**
     * 读一次会话快照（pi-web 的 `loadSession` 的单次请求）。
     *
     * 参数与 pi-web `useAgentSession.ts:786-790` 一致：`deferMedia=1&tree=summary`，
     * `force=1` 只由调用方在**挂载 / 手动刷新 / 分支导航**时传（让 pi-web 做
     * `evictIfDiskAhead()`，从磁盘而不是滞后的运行时 wrapper 重读）。
     *
     * 这里不再做任何"滞后检测/自动重试/冷却"：快照新不新鲜由服务端下发的
     * `snapshotRevision` 决定，见 [SessionRevision]。
     */
    /**
     * 把上一次的崩溃堆栈上传给伴侣网关（`POST /api/mobile/crash` → PC 侧 `server/logs/crash-*.log`）。
     *
     * 纯诊断通道：失败一律静默（不影响任何业务路径），成功与否都不重试。
     */
    suspend fun reportCrash(stackTrace: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank() || stackTrace.isBlank()) return@withContext Result.success(false)
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/mobile/crash")
                .post(stackTrace.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                .build()
            // 诊断通道不重试（readGet 会重试一次）—— 失败就下次启动再报。
            client.newCall(req).execute().use { resp -> Result.success(resp.isSuccessful) }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessionDetail(
        sessionId: String,
        tail: Int = 60,
        force: Boolean = false
    ): Result<SessionHistoryData> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        try {
            // 与 pi-web 前端同一套参数：deferMedia 不带历史图片、tree=summary 只要摘要树。
            val forceParam = if (force) "&force=1" else ""
            val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$encodedSessionId?tail=$tail&deferMedia=1&tree=summary$forceParam")
                .get()
                .build()
            val payload = readGet(req, snapshotClient)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(readFailure(payload, "/api/sessions/{id}"))
            }
            val historyData = parseSessionHistoryBody(sessionId, payload.body, tail)
            cacheSessionDetail(sessionId, historyData)
            persistSessionSnapshot(sessionId, payload.body)
            Result.success(historyData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 会话树格式认不出来时只报一次（进程内）。
     *
     * 必要性：pi-web 上次把 `tree` 改成嵌套数组时，旧解析静默回落 `context.messages`，
     * 结果「PC 跑了几十步、手机只到中途」这种缺陷只能靠肉眼看出来。一条 W 日志就够了。
     */
    private fun warnUnknownTreeShape(json: JsonObject) {
        if (warnedUnknownTreeShape) return
        warnedUnknownTreeShape = true
        val format = json.safeString("treeFormat")
        Log.w("PiSync", "unrecognized session tree shape (treeFormat=$format) → falling back to context.messages")
    }

    // ── 会话快照磁盘缓存：再次打开会话时先回放上次的正文，不必等网络 ──

    /**
     * 快照落盘的「按会话串行化」锁。
     *
     * [persistSessionSnapshot] 会被多个 IO 协程同时调用（首屏、轮询、SSE 对账），
     * 而「查时间戳 → 写 .tmp → rename → 记时间戳」必须是一段临界区：不加锁时同一会话
     * 可能同时写同一个 `.tmp`，后开始写的把先写的覆盖掉或读到半个文件。
     * 用固定 16 把锁（按会话 id 哈希取模，不随会话数增长），不同会话之间仍然并行。
     */
    private val snapshotWriteLocks = Array(16) { Any() }

    /** 上次成功落盘时间；各分片锁并发访问同一 Map，使用 ConcurrentHashMap 防止结构损坏。 */
    private val snapshotLastWriteAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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
        val lock = snapshotWriteLocks[(sessionId.hashCode() and Int.MAX_VALUE) % snapshotWriteLocks.size]
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val last = snapshotLastWriteAt[sessionId] ?: 0L
            if (now - last < 10_000L) return
            val written = runCatching {
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
            }.isSuccess
            // 只有真的写成了才记时间戳：写失败也记的话，失败会被当成「刚写过」，
            // 接下来 10 秒内不再重试，离线启动就一直是空的。
            if (written) snapshotLastWriteAt[sessionId] = now
        }
    }

    /** 读取上次落盘的会话快照（进程被回收后再打开也能秒出正文）。没有/解析失败返回 null。 */
    suspend fun getDiskCachedSessionDetail(sessionId: String): SessionHistoryData? = withContext(Dispatchers.IO) {
        runCatching {
            val file = snapshotFile(sessionId)
            if (!file.exists() || file.length() < 64L) return@runCatching null
            parseSessionHistoryBody(sessionId, file.readText(), 60).also {
                cacheSessionDetail(sessionId, it)
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
            val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
            val leafParam = if (!leafId.isNullOrBlank()) "&leafId=${URLEncoder.encode(leafId, "UTF-8")}" else ""
            // deferMedia=1 必须带：不带时这一页会把工具结果图的 base64 全塞回来。
            // 实测同一会话最近 60 条消息 = 6.45MB JSON（其中 6.36MB 是图片 base64），
            // 往上翻一页就是一次这种量级的请求。pi-web `/context` 原生支持该参数
            // （`deferToolResultImages`，只对 toolResult 生效，≤10MB 的图改写成 URL）。
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$encodedSessionId/context?tail=$tail&before=$encBefore&deferMedia=1$leafParam")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(
                        GatewayHttpException(
                            resp.code,
                            "/api/sessions/{id}/context",
                            runCatching { resp.peekBody(BODY_PREVIEW_CHARS.toLong()).string() }.getOrNull()
                        )
                    )
                }
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.failure(IOException("更早记录响应无法解析"))
                val ctx = root.safeObject("context") ?: root
                val arr = ctx.safeArray("messages")
                val entryIds = ctx.safeArray("entryIds")
                attachEntryIds(arr, entryIds)
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
                        hasMore = ctx.safeBoolean("hasMore") == true,
                        turnsBefore = ctx.safeInt("turnsBefore") ?: 0
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
        val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
        val leafParam = if (!leafId.isNullOrBlank()) "&leafId=${URLEncoder.encode(leafId, "UTF-8")}" else ""
        val bigPageJson = runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/$encodedSessionId/context?tail=$tail&deferMedia=1$leafParam")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val str = resp.body?.string() ?: "{}"
                    JsonParser.parseString(str).asSafeObject()
                } else null
            }
        }.getOrNull()

        val activeCtxObj = bigPageJson?.let { it.safeObject("context") ?: it } ?: initialCtxObj

        val baseMsgs = activeCtxObj?.safeArray("messages") ?: initialMsgs
        val baseEntryIds = activeCtxObj?.safeArray("entryIds")
        attachEntryIds(baseMsgs, baseEntryIds)
        if (baseMsgs != null) {
            for (el in baseMsgs) collected.add(el)
        }

        hasMore = activeCtxObj?.safeBoolean("hasMore") == true
        oldestEntryId = extractOldestEntryId(activeCtxObj, baseMsgs)

        // 2. 若仍有更早的分页 (hasMore == true)，使用 before=<oldestEntryId> 向上翻页拉取并拼接到最前面
        var pageGuard = 0
        while (hasMore && !oldestEntryId.isNullOrBlank() && pageGuard < 12) {
            pageGuard++
            val encBefore = URLEncoder.encode(oldestEntryId, "UTF-8")
            val olderJson = runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/api/sessions/$encodedSessionId/context?tail=200&before=$encBefore&deferMedia=1$leafParam")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val str = resp.body?.string() ?: "{}"
                        JsonParser.parseString(str).asSafeObject()
                    } else null
                }
            }.getOrNull() ?: break

            val olderCtx = olderJson.safeObject("context") ?: olderJson

            val olderMsgs = olderCtx.safeArray("messages")
            val olderEntryIds = olderCtx.safeArray("entryIds")
            attachEntryIds(olderMsgs, olderEntryIds)
            if (olderMsgs == null || olderMsgs.size() == 0) break

            val prependList = mutableListOf<com.google.gson.JsonElement>()
            for (el in olderMsgs) prependList.add(el)
            collected.addAll(0, prependList)

            val nextOldest = extractOldestEntryId(olderCtx, olderMsgs)
            hasMore = olderCtx.safeBoolean("hasMore") == true && nextOldest != oldestEntryId
            oldestEntryId = nextOldest
        }

        return collected
    }

    private fun attachEntryIds(msgs: JsonArray?, ids: JsonArray?) {
        if (msgs == null || ids == null) return
        val count = minOf(msgs.size(), ids.size())
        for (i in 0 until count) {
            val m = msgs.get(i)
            val id = ids.get(i).asSafeString()
            if (m != null && m.isJsonObject && !id.isNullOrBlank()) {
                val obj = m.asJsonObject
                if (!obj.has("entryId")) {
                    obj.addProperty("entryId", id)
                }
            }
        }
    }

    /** 一条消息条目（可能是裸消息，也可能是 `{message:{...}}`）的角色。 */
    private fun roleOfMessage(el: com.google.gson.JsonElement?): String? {
        val obj = el.asSafeObject() ?: return null
        val msg = obj.safeObject("message") ?: obj
        return msg.safeString("role")
    }

    /** 消息数组里第一条带 entryId 的 id（向前翻页的游标）。 */
    private fun messageEntryId(msgs: JsonArray?): String? {
        if (msgs == null || msgs.size() <= 0) return null
        for (i in 0 until msgs.size()) {
            val obj = msgs.get(i).asSafeObject() ?: continue
            val id = obj.safeString("entryId") ?: obj.safeString("id")
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    private fun extractOldestEntryId(ctxObj: JsonObject?, msgs: JsonArray?): String? {
        if (ctxObj != null) {
            val ids = ctxObj.safeArray("entryIds")
            if (ids != null && ids.size() > 0) {
                ids.get(0).asSafeString()?.let { return it }
            }
            ctxObj.safeString("firstEntryId")?.let { return it }
        }
        if (msgs != null && msgs.size() > 0 && msgs.get(0).isJsonObject) {
            val firstMsg = msgs.get(0).asJsonObject
            return firstMsg.safeString("entryId") ?: firstMsg.safeString("id")
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
            val id = obj.safeString("id")
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
                    val cid = curr.safeString("id")
                    if (cid != null) visited.add(cid)
                    val pid = curr.safeString("parentId")
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
            val entryType = entry.safeString("type") ?: ""
            val entryTs = parseFlexibleTimestamp(entry.get("timestamp"))
            val eId = entry.safeString("id")
            when {
                entryType == "message" -> {
                    entry.safeObject("message")?.deepCopy()?.let { mObj ->
                        if (!mObj.has("timestamp") && entryTs > 0L) {
                            mObj.addProperty("timestamp", entryTs)
                        }
                        if (!eId.isNullOrBlank()) {
                            mObj.addProperty("entryId", eId)
                        }
                        result.add(mObj)
                    }
                }
                entryType == "compaction" -> {
                    val summary = entry.safeString("summary") ?: ""
                    val tokensBefore = entry.safeLong("tokensBefore")
                        ?: entry.safeObject("details")?.safeLong("tokensBefore")
                        ?: 0L
                    val synthetic = JsonObject().apply {
                        addProperty("role", "compactionSummary")
                        addProperty("summary", summary)
                        addProperty("tokensBefore", tokensBefore)
                        if (entryTs > 0L) addProperty("timestamp", entryTs)
                        if (!eId.isNullOrBlank()) addProperty("entryId", eId)
                    }
                    result.add(synthetic)
                }
                entryType == "branch_summary" -> {
                    val summary = entry.safeString("summary") ?: ""
                    if (summary.isNotBlank()) {
                        val synthetic = JsonObject().apply {
                            addProperty("role", "branchSummary")
                            addProperty("summary", summary)
                            if (entryTs > 0L) addProperty("timestamp", entryTs)
                            if (!eId.isNullOrBlank()) addProperty("entryId", eId)
                        }
                        result.add(synthetic)
                    }
                }
                entryType == "custom_message" -> {
                    // pi-web 只在 display 为**真**时才渲染 custom_message（`rz(e){return "custom"===e.role && !e.display}`）：
                    // auto-continue-after-compaction 这类扩展的续跑指令是 display:false，
                    // 渲染出来就是用户没说过的话。缺字段同样按隐藏处理（JS 里 undefined 是 falsy）。
                    val visible = entry.get("display")
                        ?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                        ?.asBoolean == true
                    val contentText = extractTextFromContent(entry.get("content"))
                    if (visible && contentText.isNotBlank()) {
                        // 折成 pi-web 服务端下发的同一形态（`{role:"custom", customType, content, display:true}`），
                        // 下游只认这一种形状，避免「entries 分支能显示、messages 分支被隐藏」两套语义。
                        val synthetic = JsonObject().apply {
                            addProperty("role", "custom")
                            addProperty("content", contentText)
                            addProperty("display", true)
                            entry.safeString("customType")?.let {
                                addProperty("customType", it)
                            }
                            if (entryTs > 0L) addProperty("timestamp", entryTs)
                            if (!eId.isNullOrBlank()) addProperty("entryId", eId)
                        }
                        result.add(synthetic)
                    }
                }
                entry.has("role") -> {
                    val copy = entry.deepCopy()
                    if (!eId.isNullOrBlank() && !copy.has("entryId")) {
                        copy.addProperty("entryId", eId)
                    }
                    result.add(copy)
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
            val fullOutputPath: String? = null,
            /** 落盘失败的原因（pi 1.6.1 的 fullOutputError）：有它且无路径就是真取不回了 */
            val fullOutputError: String? = null
        )
        val toolResultById = mutableMapOf<String, ParsedToolResult>()
        val consumedResultIds = mutableSetOf<String>()

        for (element in msgs) {
            if (!element.isJsonObject) continue
            val rawObj = element.asJsonObject
            val msgObj = rawObj.safeObject("message") ?: rawObj
            val roleStr = msgObj.safeString("role") ?: ""
            if (roleStr == "toolResult" || roleStr == "tool") {
                val tcId = msgObj.safeString("toolCallId") ?: msgObj.safeString("id")
                val tName = msgObj.safeString("toolName") ?: msgObj.safeString("name") ?: "tool"
                val contentElem = msgObj.get("content") ?: msgObj.get("output")
                val rawOut = extractTextFromContent(contentElem)
                val tOut = if (rawOut.length > 12000) {
                    rawOut.take(12000) + "\n\n... [工具输出过长，已截断显示前 12,000 字符] ..."
                } else rawOut
                val tMedia = extractMediaFromContent(contentElem, tOut, tcId)
                val dur = msgObj.safeLong("durationMs") ?: 0L
                val isErr = msgObj.safeBoolean("isError") ?: false
                val ts = parseFlexibleTimestamp(msgObj.get("timestamp") ?: rawObj.get("timestamp"))
                val fullOutPath = msgObj.safeString("fullOutputPath")
                    ?: msgObj.safeString("outputPath")
                    ?: rawObj.safeString("fullOutputPath")
                val fullOutError = msgObj.safeString("fullOutputError")
                    ?: rawObj.safeString("fullOutputError")
                if (!tcId.isNullOrBlank()) {
                    toolResultById[tcId] =
                        ParsedToolResult(tcId, tName, tOut, tMedia, dur, isErr, ts, fullOutPath, fullOutError)
                }
            }
        }

        for (element in msgs) {
            if (!element.isJsonObject) continue
            val rawObj = element.asJsonObject
            val msgObj = rawObj.safeObject("message") ?: rawObj
            val roleStr = msgObj.safeString("role") ?: ""
            val customType = msgObj.safeString("customType") ?: rawObj.safeString("customType")
            // 哪些消息该渲染见 [SessionMessageVisibility]：pi-web 会丢掉 system 角色与
            // display 非真的 custom 消息，漏对齐就会把扩展注入的内部指令印成用户可见卡片。
            val displayFlag = (msgObj.get("display") ?: rawObj.get("display"))
                ?.takeIf { !it.isJsonNull && it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }
                ?.asBoolean
            if (!SessionMessageVisibility.isVisible(roleStr, displayFlag)) continue
            // 压缩摘要在 >0.13 的快照里是 `custom/customType="compaction"`（正文即 Markdown 摘要，首行恒为
            // "## Goal"）。折回 compactionSummary 形态，交给同一条「📦 上下文已归档压缩」卡片渲染，
            // 否则它会掉进通用注入消息卡片，标题取首行 → 界面上满屏 "Goal"。
            val effectiveRole = if (SessionMessageVisibility.isCompactionView(roleStr, customType)) {
                "compactionSummary"
            } else {
                roleStr
            }
            val timestamp = parseFlexibleTimestamp(msgObj.get("timestamp") ?: rawObj.get("timestamp"))
            // pi 会话条目的真实 ID（entry.id）：从历史快照过来的消息都要带上，
            // 「从此处编辑」才能调 navigate_tree 把会话树切到这个节点。
            val entryId = rawObj.safeString("entryId")
                ?: rawObj.safeString("id")
                ?: msgObj.safeString("entryId")
                ?: msgObj.safeString("id")

            var turnUsageStr: String? = null
            if (roleStr == "assistant") {
                msgObj.safeObject("usage")?.let { uObj ->
                    val uIn = uObj.safeLong("input") ?: 0L
                    val uOut = uObj.safeLong("output") ?: 0L
                    val uCacheR = uObj.safeLong("cacheRead") ?: 0L
                    val uCacheW = uObj.safeLong("cacheWrite") ?: 0L
                    val uCost = uObj.safeObject("cost")?.safeDouble("total") ?: 0.0
                    val turnContext = uIn + uCacheR + uCacheW
                    if (turnContext > 0) {
                        lastAssistantContextTokens = turnContext
                        lastAssistantInTokens = uIn
                        lastAssistantOutTokens = uOut
                    }
                    turnUsageStr = TokenStats.formatTurnUsage(uIn, uOut, uCacheR, uCacheW, uCost)
                }
            }

            val msgModelName = msgObj.safeString("model") ?: defaultModelId

            when (effectiveRole) {
                "user" -> {
                    val contentElem = msgObj.get("content") ?: msgObj.get("text")
                    // 剥掉 pi 为缩放后的图片追加的尺寸提示行（`[Image: original 1220x2656, …]`）：
                    // 那是给模型看的元数据，不该出现在用户气泡里，也不该被「从此处编辑」回填进输入框。
                    val userText = UserPromptText.stripImageHints(extractTextFromContent(contentElem))
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
                                // key 的取值规则见 [HistoryItemKeys]（对齐 pi-web 的 messageKeys）：
                                // 身份 = 条目 id，不是窗口内下标 —— 下标会随窗口起点前移而重排，
                                // 两次快照就能给两条不同消息铸出同一个 key，LazyColumn 直接崩。
                                id = HistoryItemKeys.of(sessionId, entryId, "compaction", timestamp),
                                entryId = entryId,
                                role = MessageRole.SYSTEM,
                                content = "📦 上下文已归档压缩" + if (cleanSummary.isNotBlank()) "\n$cleanSummary" else "",
                                timestamp = timestamp
                            ))
                        } else {
                            chatItems.add(ChatItem(
                                id = HistoryItemKeys.of(sessionId, entryId, "user", timestamp),
                                entryId = entryId,
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
                    var thinking: String? = msgObj.safeString("thinking")
                    val toolCalls = mutableListOf<ToolCallInfo>()
                    val assistantMedia = mutableListOf<MediaAttachment>()

                    if (msgObj.has("content")) {
                        val contentElem = msgObj.get("content")
                        assistantMedia.addAll(extractMediaFromContent(contentElem, null))
                        if (contentElem.isJsonArray) {
                            for (cItem in contentElem.asJsonArray) {
                                if (!cItem.isJsonObject) continue
                                val cObj = cItem.asJsonObject
                                when (cObj.safeString("type")) {
                                    "text", "output_text", "input_text" -> {
                                        val t = cObj.safeString("text") ?: ""
                                        text = if (text.isEmpty()) t else "$text\n$t"
                                    }
                                    "thinking" -> {
                                        val th = cObj.safeString("thinking") ?: cObj.safeString("text") ?: ""
                                        thinking = if (thinking.isNullOrEmpty()) th else "$thinking\n$th"
                                    }
                                    "toolCall", "tool_use", "tool_call" -> {
                                        val tcId = cObj.safeString("toolCallId") ?: cObj.safeString("id")
                                        val toolName = cObj.safeString("toolName") ?: cObj.safeString("name") ?: "tool"
                                        val inputElem = cObj.get("input") ?: cObj.get("arguments") ?: cObj.get("args")
                                        val preview = extractToolPreview(inputElem)
                                        val fields = extractToolFields(inputElem)
                                        val formattedInput = formatToolInput(inputElem)
                                        val paired = if (!tcId.isNullOrBlank()) {
                                            consumedResultIds.add(tcId)
                                            toolResultById[tcId]
                                        } else null
                                        val argMedia = MediaAttachmentParsing.fromToolArgs(
                                            toolName, preview, inputElem,
                                            MediaLoadPolicy.toolIdPrefix(tcId)
                                        )
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
                                                fullOutputPath = paired?.fullOutputPath,
                                                fullOutputError = paired?.fullOutputError
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (contentElem.isJsonPrimitive) {
                            text = contentElem.asString
                        }
                    }

                    // 模型可能把终端转义写进正文/思考（#1753）：与 pi-web 的 TextBlock / ThinkingText
                    // 同口径，入库前就剥掉，下游的渲染、复制、thinking 预览都不再需要各自处理。
                    text = stripAnsi(text)
                    thinking = thinking?.let { stripAnsi(it) }

                    val startIndexForTurn = chatItems.size
                    val errMsg = msgObj.safeString("errorMessage")
                        ?: (if (msgObj.safeString("stopReason") == "error") "上游模型异常中断" else null)
                    // 「This operation was aborted」这类中止并非服务故障：pi 在压缩上下文、用户停止、
                    // 自动重试换手时都会 abort 在途请求，并落一条空正文的 assistant 记录。
                    // 历史回放时不应把它渲染成刺眼的「⚠️ 模型服务异常」。
                    val isAbortLike = errMsg != null && ABORT_LIKE_ERROR_REGEX.containsMatchIn(errMsg)
                    // pi 的提示行（缓存未命中 / 预热计费 / 摘要计费 / 推理被丢弃）：
                    // 挂在消息自己的 `transcriptNotices` 上，由 pi 的 notices.* 设置控制（默认全关）。
                    val notices = TranscriptNoticeParsing.of(msgObj)

                    if (text.isNotBlank() || !thinking.isNullOrBlank() || assistantMedia.isNotEmpty()) {
                        val estSec = thinking?.let { (it.length / 45).coerceIn(1, 90) }
                        val fullContent = when {
                            errMsg.isNullOrBlank() || text.contains(errMsg) -> text
                            isAbortLike -> (text + "\n\n⏸ 本轮生成已被中断").trim()
                            else -> (text + "\n\n⚠️ 模型服务异常: $errMsg").trim()
                        }
                        chatItems.add(ChatItem(
                            id = HistoryItemKeys.of(sessionId, entryId, "agent", timestamp),
                            entryId = entryId,
                            role = MessageRole.AGENT,
                            content = fullContent,
                            thinking = thinking,
                            thinkingDurationSec = estSec,
                            streamMessageId = assistantMessageIdentity(msgObj),
                            mediaList = assistantMedia,
                            modelName = msgModelName,
                            usageSummary = if (toolCalls.isEmpty()) turnUsageStr else null,
                            notices = notices,
                            timestamp = timestamp
                        ))
                    } else if (!errMsg.isNullOrBlank() && toolCalls.isEmpty() && !isAbortLike) {
                        // 彻底解决上游报错且内容为空时静默吞掉的问题 (如 HTTP 422 developer 角色拒收报错)
                        chatItems.add(ChatItem(
                            id = HistoryItemKeys.of(sessionId, entryId, "error", timestamp),
                            entryId = entryId,
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
                            // 工具卡与正文气泡同属一个 assistant entry，用 kind 区分（role 不够细）
                            id = HistoryItemKeys.of(
                                sessionId,
                                entryId,
                                "tool_${tc.toolCallId ?: idx}",
                                timestamp
                            ),
                            role = MessageRole.TOOL,
                            content = "",
                            toolCall = tc,
                            modelName = if (isFirstItemInTurn) msgModelName else null,
                            usageSummary = if (isLastToolInTurn) turnUsageStr else null,
                            // 正文为空、只出了工具卡时，提示行落到这一条上，避免整条消息的提示丢失
                            notices = if (isFirstItemInTurn) notices else emptyList(),
                            timestamp = timestamp
                        ))
                    }
                }
                "toolResult", "tool" -> {
                    val tcId = msgObj.safeString("toolCallId") ?: msgObj.safeString("id")
                    if (tcId != null && consumedResultIds.contains(tcId)) {
                        // 已与对应的 toolCall 卡片配对合并，不再重复渲染第二张卡片
                    } else {
                        val toolName = msgObj.safeString("toolName") ?: msgObj.safeString("name") ?: "tool"
                        val contentElem = msgObj.get("content") ?: msgObj.get("output")
                        val rawToolOutput = extractTextFromContent(contentElem)
                        val toolOutput = if (rawToolOutput.length > 12000) {
                            rawToolOutput.take(12000) + "\n\n... [工具输出过长，已截断显示前 12,000 字符] ..."
                        } else rawToolOutput
                        val toolMedia = extractMediaFromContent(contentElem, toolOutput, tcId)
                        val duration = msgObj.safeLong("durationMs") ?: 0L
                        val isErr = msgObj.safeBoolean("isError") ?: false
                        val fullOutPath = msgObj.safeString("fullOutputPath") ?: msgObj.safeString("outputPath")
                        val fullOutError = msgObj.safeString("fullOutputError")
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
                                    isError = isErr,
                                    fullOutputPath = prevTool.fullOutputPath ?: fullOutPath,
                                    fullOutputError = fullOutError
                                )
                            )
                        } else {
                            chatItems.add(ChatItem(
                                id = HistoryItemKeys.of(
                                    sessionId,
                                    entryId,
                                    "toolres_${tcId ?: toolName}",
                                    timestamp
                                ),
                                entryId = entryId,
                                role = MessageRole.TOOL,
                                content = "",
                                toolCall = ToolCallInfo(
                                    toolCallId = tcId,
                                    toolName = toolName,
                                    output = toolOutput,
                                    mediaList = toolMedia,
                                    durationMs = duration,
                                    isError = isErr,
                                    fullOutputPath = fullOutPath,
                                    fullOutputError = fullOutError
                                ),
                                timestamp = timestamp
                            ))
                        }
                    }
                }
                "compactionSummary", "branchSummary" -> {
                    val summary = msgObj.safeString("summary")
                        ?: extractTextFromContent(msgObj.get("content"))
                    // pi-web 把 token 数放在 details（`details:{tokensBefore}`），旧形态是顶层字段，两者都认。
                    val tokensBefore = msgObj.safeLong("tokensBefore")
                        ?: msgObj.safeObject("details")?.safeLong("tokensBefore")
                        ?: 0L
                    val header = if (tokensBefore > 0) {
                        "📦 上下文已归档压缩 (压缩前 ${TokenStats.formatTokenCompact(tokensBefore)} tokens)"
                    } else {
                        "📦 上下文已归档压缩"
                    }
                    chatItems.add(ChatItem(
                        id = HistoryItemKeys.of(sessionId, entryId, "compaction", timestamp),
                        entryId = entryId,
                        role = MessageRole.SYSTEM,
                        content = if (summary.isNotBlank()) "$header\n$summary" else header,
                        timestamp = timestamp
                    ))
                }
                // 走到这里的 custom 消息一定是 display:true（其余已在上面按 [SessionMessageVisibility] 丢掉）。
                "custom" -> {
                    val sysText = extractTextFromContent(msgObj.get("content") ?: msgObj.get("text"))
                    if (sysText.isNotBlank()) {
                        chatItems.add(ChatItem(
                            id = HistoryItemKeys.of(sessionId, entryId, "sys", timestamp),
                            entryId = entryId,
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
            if (obj.size() == 1) {
                obj.safeString("command")?.let { return it }
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
                if (item.isJsonObject) {
                    item.asJsonObject.safeString("text")?.let { sb.append(it).append("\n") }
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
        outputText: String? = null,
        toolCallId: String? = null
    ): List<MediaAttachment> = MediaAttachmentParsing.fromContent(
        element,
        outputText,
        MediaLoadPolicy.toolIdPrefix(toolCallId)
    )


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

    /**
     * 公开入口：拿不到目录时**只在未配置网关时**回退到内置默认目录（聊天侧选择器需要「总有东西可选」）。
     *
     * 为什么要把两种失败分开：一旦连上了网关，失败就只能如实返回 failure。
     * 旧实现在这里无条件 `getOrElse { 内置目录 }` 并 `Result.success(...)`，于是
     * 「隧道 502 / pi-web 没起来 / 密钥失效」全部被伪装成「加载成功」，界面上只剩一份
     * 与 PC 毫无关系的假模型列表 —— 用户既看不到故障，也无法排查。
     * 未配置网关（首次启动、刚断开）是唯一合理的内置目录兜底：那时本来就没有可问的地方。
     */
    suspend fun getDynamicModels(): Result<DynamicModelCatalog> = withContext(Dispatchers.IO) {
        val result = getDynamicModelsInternal(prefs.serverUrl, null, null)
        if (result.isSuccess) return@withContext result
        if (prefs.serverUrl.isBlank()) {
            return@withContext Result.success(applyModelOverrides(getDefaultCatalog(), null))
        }
        result
    }

    /**
     * 拉取并解析 `/api/models` 目录；失败返回 [Result.failure]，不再把网络失败伪装成成功。
     *
     * @param preFetchedConfig 已获取的 models.json；null 时自行拉取，避免同一轮同步重复请求
     * @param preFetchedAuth 已获取的凭据状态；null 时自行拉取
     */
    private suspend fun getDynamicModelsInternal(
        baseUrl: String,
        preFetchedConfig: JsonObject?,
        preFetchedAuth: Map<String, ProviderAuthState>?,
        // 是否把服务端默认模型/思考档位写回 App 全局偏好。只读同步路径必须传 false，
        // 否则开一次模型管理页就会悄悄改掉用户的全局默认模型
        applyServerDefaults: Boolean = true
    ): Result<DynamicModelCatalog> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/models").get().build()
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(readFailure(payload, "/api/models"))
            }
            val body = payload.body
            val json = JsonParser.parseString(body).asSafeObject()
                ?: return@withContext Result.failure(IOException("模型目录响应无法解析"))

                val models = mutableListOf<ModelInfo>()
                json.safeArray("modelList")?.let { array ->
                    for (item in array) {
                        val obj = item.asSafeObject() ?: continue
                        val id = obj.safeString("id") ?: ""
                        val name = obj.safeString("name") ?: id
                        val provider = obj.safeString("provider") ?: "Default"
                        val ctxWin = obj.safeLong("contextWindow")
                            ?: ModelDefaults.DEFAULT_CONTEXT_WINDOW
                        val maxTok = obj.safeLong("maxTokens") ?: 8192L
                        val reasoning = obj.safeBoolean("reasoning") ?: true
                        val input = obj.safeArray("input")?.mapNotNull { it.asSafeString() } ?: emptyList()
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
                json.safeObject("thinkingLevels")?.let { obj ->
                    for ((k, v) in obj.entrySet()) {
                        if (v.isJsonArray) {
                            levelsMap[k] = v.asJsonArray.mapNotNull { it.asSafeString() }
                        }
                    }
                }

                // 解析服务端当前默认模型与默认思考档位 (对标 pi-web /api/models 返回的 defaultModel)
                var defaultModelRef: DefaultModelRef? = null
                json.safeObject("defaultModel")?.let { dObj ->
                    val prov = dObj.safeString("provider") ?: ""
                    val mId = dObj.safeString("modelId") ?: ""
                    if (prov.isNotBlank() && mId.isNotBlank()) {
                        defaultModelRef = DefaultModelRef(provider = prov, modelId = mId)
                        if (applyServerDefaults) {
                            prefs.defaultProvider = prov
                            prefs.defaultModelId = mId
                        }
                    }
                }
                val defaultThinking = json.safeString("defaultThinkingLevel")
                if (applyServerDefaults && !defaultThinking.isNullOrBlank()) {
                    prefs.defaultThinkingLevel = defaultThinking
                }

                // 同时拉取 /api/models-config (models.json) 与本地保存的模型参数覆盖，确保用户修改的 contextWindow 等参数100%生效且不回退
                val serverModelsConfig = preFetchedConfig ?: fetchServerModelsConfigObject(baseUrl)
                // 目录为空就是空：不允许用内置模型列表顶替一个**真实存在但为空**的目录，
                // 那会让「网关一个模型都没配」看起来像「一切正常」。内置目录只在未配置网关时
                // 使用（见 [getDynamicModels]）。
                val baseCatalog = DynamicModelCatalog(
                    models = models,
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
                val authStates = preFetchedAuth ?: getProviderAuthStates().getOrNull()
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
                        if (applyServerDefaults) {
                            prefs.defaultProvider = fallback.provider
                            prefs.defaultModelId = fallback.id
                        }
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
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private data class ModelsConfigFetchResult(
        val config: JsonObject,
        val wasHealed: Boolean
    )

    /**
     * 读 models.json。**读**可以宽容（旧版本可能写过 `{config: …}` / `{content: …}` / `{raw: …}` 包装，
     * 这里负责拆开并抢救），但**写**只有一种结构（见 [putModelsConfigToServer]）：
     * 不再因为读到过包装就给写请求也套上包装。
     */
    private fun fetchServerModelsConfigWithEnvelope(baseUrl: String, autoPushHeal: Boolean = true): ModelsConfigFetchResult? {
        if (baseUrl.isBlank()) return null
        return runCatching {
            val req = Request.Builder().url("$baseUrl/api/models-config").get().build()
            val payload = readGet(req)
            if (payload.code !in 200..299) return@runCatching null
            val str = payload.body.trim()
            if (str.isEmpty()) return@runCatching ModelsConfigFetchResult(JsonObject().apply { add("providers", JsonObject()) }, false)
            val parsed = runCatching { JsonParser.parseString(str) }.getOrNull() ?: return@runCatching null
            if (!parsed.isJsonObject) return@runCatching null
            val root = parsed.asJsonObject
            val targetObj: JsonObject = when {
                root.safeObject("providers") != null -> {
                    // 如果同时存在残留的顶层 config 字段，说明曾被旧版写入污染，后续由 sanitizeAndHealModelsConfig 清理
                    root.deepCopy()
                }
                root.safeObject("config") != null -> {
                    root.safeObject("config")!!.deepCopy()
                }
                root.safeString("config") != null -> {
                    val innerStr = root.safeString("config")!!
                    runCatching { JsonParser.parseString(innerStr).asSafeObject() }.getOrNull() ?: JsonObject()
                }
                root.safeString("content") != null -> {
                    val innerStr = root.safeString("content")!!
                    runCatching { JsonParser.parseString(innerStr).asSafeObject() }.getOrNull() ?: JsonObject()
                }
                root.safeString("raw") != null -> {
                    val innerStr = root.safeString("raw")!!
                    runCatching { JsonParser.parseString(innerStr).asSafeObject() }.getOrNull() ?: JsonObject()
                }
                else -> root.deepCopy()
            }
            val healed = sanitizeAndHealModelsConfig(targetObj)
            val fetched = ModelsConfigFetchResult(targetObj, healed)

            if (fetched.wasHealed && autoPushHeal) {
                // 自愈推送是顺手修的：失败不能影响本次读取（错误文案已经在 put 里落到 Result 上）。
                runCatching { putModelsConfigToServer(baseUrl, fetched.config) }
            }
            fetched
        }.getOrNull()
    }

    private fun fetchServerModelsConfigObject(baseUrl: String): JsonObject? {
        return fetchServerModelsConfigWithEnvelope(baseUrl, autoPushHeal = true)?.config
    }

    /** 每次写 models.json 前落一份本机快照（cacheDir/models-config-backups，保留最近 10 份）。 */
    private fun persistModelsConfigBackup(payload: JsonObject) {
        val dir = java.io.File(prefs.cacheDir, "models-config-backups")
        if (!dir.exists() && !dir.mkdirs()) return
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US)
            .format(java.util.Date())
        java.io.File(dir, "models-$stamp.json").writeText(gson.toJson(payload))
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(10)
            ?.forEach { it.delete() }
    }

    /**
     * 自动检测并无损修复损坏的 models.json：
     * 1. 若顶层意外残留了 "config" 递归包装，抢救其中的 providers 后移除顶层 "config"（修复 schema 校验报错）
     * 2. 若某个 provider 下存在 "models" 数组但却没有 "baseUrl"（此前修改单个内置模型如 Gemini 上下文时误写入了 1 项 models 数组，导致该提供商其他模型被清空且缺失 baseUrl 引起 pi 无法工作），
     *    自动将其中的模型参数无损迁移到 pi-coding-agent 官方标准的 "modelOverrides" 字段下，并移除非法的无 baseUrl "models" 数组！
     */
    private fun sanitizeAndHealModelsConfig(rootConfig: JsonObject): Boolean {
        var modified = false

        val providersObj = rootConfig.safeObject("providers")
            ?: JsonObject().also { rootConfig.add("providers", it) }

        // 1. 抢救并移除顶层可能残留的 "config" 污染字段
        if (rootConfig.has("config")) {
            rootConfig.safeObject("config")?.safeObject("providers")?.let { nestedProviders ->
                for ((k, v) in nestedProviders.entrySet()) {
                    if (!providersObj.has(k) && v.isJsonObject) {
                        providersObj.add(k, v)
                    }
                }
            }
            rootConfig.remove("config")
            modified = true
        }

        // 2. 检查每个 provider：若没有 baseUrl 却定义了 models 数组，无损转换为 modelOverrides
        for ((_, provElem) in providersObj.entrySet()) {
            val pObj = provElem.asSafeObject() ?: continue
            val baseUrlVal = pObj.safeString("baseUrl")?.trim() ?: ""
            val mArr = pObj.safeArray("models")
            if (mArr != null && baseUrlVal.isEmpty()) {
                val overridesObj = pObj.safeObject("modelOverrides")
                    ?: JsonObject().also { pObj.add("modelOverrides", it) }

                for (mElem in mArr) {
                    val mObj = mElem.asSafeObject() ?: continue
                    val mId = mObj.safeString("id")?.trim() ?: continue
                    val ovItem = overridesObj.safeObject(mId)
                        ?: JsonObject().also { overridesObj.add(mId, it) }
                    mObj.safeString("name")?.takeIf { it.isNotBlank() }?.let {
                        ovItem.addProperty("name", it)
                    }
                    mObj.safeLong("contextWindow")?.let {
                        ovItem.addProperty("contextWindow", it)
                    }
                    mObj.safeLong("maxTokens")?.let {
                        ovItem.addProperty("maxTokens", it)
                    }
                    mObj.safeBoolean("reasoning")?.let {
                        ovItem.addProperty("reasoning", it)
                    }
                    mObj.safeArray("input")?.let { inputArr ->
                        ovItem.add("input", inputArr.deepCopy())
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
     * 将纯净的 models.json 配置写回网关并触发模型目录刷新。
     *
     * 契约以 pi-web 为准（`app/api/models-config/route.ts`）：PUT 的 body 就是 models.json 本身，
     * 服务端直接读顶层 `providers` 做校验后写盘，**不识别任何包装**。因此这里只发一种结构，
     * 绝不在失败后换成 `{config: …}` 再试一次：`{config: …}` 在 0.13.0 里读不到 `providers`，
     * 服务端的 provider / 图片上限校验会整体跳过（实测同一份无效配置直发被拒、包一层却被放行），
     * 结果是把本该被拒绝的配置写成 pi 无法加载的结构。
     *
     * 失败一律把服务端的 `error` 文案带出（如 `Provider new-provider needs a Base URL or at least one model`），
     * 界面才能指出是哪一项不合格。网络异常也不自动重放写请求：结果未知时重放只会掩盖真实状态。
     */
    private fun putModelsConfigToServer(baseUrl: String, cleanRootConfig: JsonObject): Result<Unit> {
        val payload = ModelsConfigWriteContract.payloadOf(cleanRootConfig)
        // 写盘前先在本机留一份快照：models.json 是不可逆的整体覆写，一旦算错（例如 2026-11-01
        // 那次把 codywon 的 models 数组整家抹掉）就只能去猜历史值。保留最近 10 份便于手动找回。
        runCatching { persistModelsConfigBackup(payload) }
        val req = Request.Builder()
            .url("$baseUrl/api/models-config")
            .put(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()
        val failureMessage: String? = try {
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    null
                } else {
                    val text = runCatching { resp.body?.string() }.getOrNull()
                    ModelsConfigWriteContract.errorMessage(text, resp.code)
                }
            }
        } catch (e: Exception) {
            return Result.failure(
                IOException("网络异常，保存结果未知，请刷新后确认：${e.message ?: e.javaClass.simpleName}")
            )
        }
        if (failureMessage != null) return Result.failure(IOException(failureMessage))

        // 目录刷新是尽力而为：配置已经写盘，刷新失败不能反过来报「保存失败」。
        runCatching {
            val refreshReq = Request.Builder()
                .url("$baseUrl/api/models/refresh")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(refreshReq).execute().close()
        }
        return Result.success(Unit)
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
        serverConfig?.safeObject("providers")?.let { providersObj ->
            for ((provName, provElem) in providersObj.entrySet()) {
                val pObj = provElem.asSafeObject() ?: continue
                val provBaseUrl = pObj.safeString("baseUrl")?.trim()
                val provApi = pObj.safeString("api")?.trim()

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
                val mArr = pObj.safeArray("models")
                if (mArr != null) {
                    for (mElem in mArr) {
                        val mObj = mElem.asSafeObject() ?: continue
                        val mId = mObj.safeString("id")?.trim() ?: continue
                        val mName = mObj.safeString("name")?.trim() ?: mId
                        val ctxWin = mObj.safeLong("contextWindow")
                        val maxTok = mObj.safeLong("maxTokens")
                        val reasoning = mObj.safeBoolean("reasoning")
                        val inputList = mObj.safeArray("input")?.mapNotNull { it.asSafeString() }
                        val idx = ModelCatalogMerge.indexOf(merged, provName, mId)

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
                                    contextWindow = ctxWin ?: ModelDefaults.DEFAULT_CONTEXT_WINDOW,
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
                val ovObj = pObj.safeObject("modelOverrides")
                if (ovObj != null) {
                    for ((mIdKey, ovElem) in ovObj.entrySet()) {
                        val mObj = ovElem.asSafeObject() ?: continue
                        val mId = mIdKey.trim()
                        val mName = mObj.safeString("name")?.trim()
                        val ctxWin = mObj.safeLong("contextWindow")
                        val maxTok = mObj.safeLong("maxTokens")
                        val reasoning = mObj.safeBoolean("reasoning")
                        val inputList = mObj.safeArray("input")?.mapNotNull { it.asSafeString() }
                        val idx = ModelCatalogMerge.indexOf(merged, provName, mId)

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
        serverConfig?.safeObject("providers")?.let { pObj ->
            validProviders.addAll(pObj.keySet().map { it.lowercase() })
        }
        merged.forEach { validProviders.add(it.provider.lowercase()) }

        // 3. 应用用户在 App 内部针对具体模型保存的持久化配置
        val localOverrides = prefs.getModelOverrides()
        for ((_, ov) in localOverrides) {
            val idx = ModelCatalogMerge.indexOf(merged, ov.provider, ov.modelId)

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

    /**
     * 提交一条普通提示词。
     *
     * 不在客户端预判「忙不忙」：pi 的 `AgentSession.prompt()` 自己决定是排队给当前回合，
     * 还是（回合已结束时）开新一轮；压缩期间则由 pi-web 的 wrapper park 住并回 `queued:true`。
     * 客户端预判反而会把消息拦在外面（旧实现就是一句「任务执行中不能回退分支」的误导提示）。
     */
    suspend fun sendCommand(
        sessionId: String,
        prompt: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<PromptSubmitAck> = withContext(Dispatchers.IO) {
        submitPrompt(sessionId, prompt, images, streamingBehavior = null)
    }

    suspend fun sendSteer(
        sessionId: String,
        text: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<PromptSubmitAck> = withContext(Dispatchers.IO) {
        submitPrompt(sessionId, text, images, streamingBehavior = "steer")
    }

    suspend fun sendFollowUp(
        sessionId: String,
        text: String,
        images: List<ChatImageInput> = emptyList()
    ): Result<PromptSubmitAck> = withContext(Dispatchers.IO) {
        submitPrompt(sessionId, text, images, streamingBehavior = "followUp")
    }

    private suspend fun submitPrompt(
        sessionId: String,
        prompt: String,
        images: List<ChatImageInput>,
        streamingBehavior: String?
    ): Result<PromptSubmitAck> {
        val payload = mutableMapOf<String, Any>(
            "type" to "prompt",
            "message" to prompt,
            "text" to prompt
        )
        if (streamingBehavior != null) {
            payload["streamingBehavior"] = streamingBehavior
        }
        if (images.isNotEmpty()) {
            payload["images"] = imagesToJson(images)
        }
        return sendAgentCommandData(sessionId, payload).map { data ->
            AgentCommandPayload.ackOf(data)
        }
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
                        "$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}/bash-output?path=" +
                            URLEncoder.encode(outputPath, "UTF-8")
                    )
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: "{}"
                    val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                    if (!resp.isSuccessful || root == null) {
                        val err = root?.safeString("error") ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IOException(err))
                    }
                    val data = root.safeObject("data")
                    val out = data?.safeString("output") ?: ""
                    Result.success(out)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** 文件元信息：GET /api/files/<绝对路径>?type=meta → {size, language, mime, previewKind, mtime} */
    suspend fun getFileMeta(absPath: String, sessionId: String? = null): Result<FileMeta> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (absPath.isBlank()) return@withContext Result.failure(Exception("缺少文件路径"))
        try {
            val req = Request.Builder()
                .url(fileApiUrl(absPath, "meta", sessionId))
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                if (!resp.isSuccessful || root == null) {
                    val err = root?.safeString("error") ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                Result.success(
                    FileMeta(
                        size = root.safeLong("size") ?: 0L,
                        language = root.safeString("language") ?: "",
                        mime = root.safeString("mime") ?: "",
                        mtime = root.safeString("mtime"),
                        previewKind = root.safeString("previewKind")
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 目录列表：GET /api/files/<绝对路径>?type=list[&hidden=1]
     */
    suspend fun listFileDirectory(
        dirPath: String,
        showHidden: Boolean = false,
        sessionId: String? = null
    ): Result<FileDirectoryListing> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (dirPath.isBlank()) return@withContext Result.failure(Exception("缺少目录路径"))
        try {
            val hiddenQuery = if (showHidden) "&hidden=1" else ""
            val url = fileApiUrl(dirPath, "list", sessionId) + hiddenQuery
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val err = FileListParsing.parseError(body) ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                val listing = FileListParsing.parseListing(body)
                    ?: return@withContext Result.failure(IOException("解析目录列表失败"))
                Result.success(listing)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 文件索引搜索：GET /api/file-index?cwd=<工作目录>&q=<搜索词>
     */
    suspend fun searchFileIndex(
        cwd: String,
        query: String
    ): Result<List<FileIndexMatch>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (cwd.isBlank()) return@withContext Result.failure(Exception("缺少工作目录"))
        try {
            val encCwd = URLEncoder.encode(cwd, "UTF-8")
            val encQ = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl/api/file-index?cwd=$encCwd&q=$encQ"
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val err = FileListParsing.parseError(body) ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                val matches = FileListParsing.parseIndexMatches(body)
                Result.success(matches)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 文件文本内容：GET /api/files/<绝对路径>?type=read → {content} */
    suspend fun getFileText(absPath: String, sessionId: String? = null): Result<String> =
        getFileTextChunk(absPath, 0L, sessionId).map { it.content }

    /**
     * 文本预览分块（对应 pi-web `readTextPreviewChunk`）：
     * GET /api/files/<绝对路径>?type=read&offset=<字节> →
     * { content, nextOffset, truncated, size, language, mtime }
     *
     * 服务端一次最多返回 256KB 并保留 UTF-8 字符边界，所以超长报告要点「加载更多」
     * 用上一块的 nextOffset 续读；只读 content 会把截断当成读完了。
     */
    suspend fun getFileTextChunk(
        absPath: String,
        offset: Long = 0L,
        sessionId: String? = null
    ): Result<FileTextChunk> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            try {
                val offsetQuery = if (offset > 0L) "&offset=$offset" else ""
                val req = Request.Builder()
                    .url(fileApiUrl(absPath, "read", sessionId) + offsetQuery)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val contentType = resp.header("Content-Type").orEmpty()
                    val body = resp.body?.string() ?: "{}"
                    val isJsonBody = FileListParsing.isJsonBody(contentType, body)

                    // pi-web 的 `type=read` 只对「文本类」回 JSON；`getImageMime()` 命中时
                    // （**包括 SVG**）直接回原文件流。以前这里无脑当 JSON 解，于是 SVG 一律
                    // 报「读取文件失败: HTTP 200」—— 状态码 200 却读不出内容，就是这个原因。
                    if (resp.isSuccessful && !isJsonBody) {
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val isBinary = FileListParsing.looksBinary(body)
                        return@withContext Result.success(
                            FileTextChunk(
                                content = if (isBinary) "" else body,
                                nextOffset = bytes.size.toLong(),
                                truncated = false,
                                size = resp.header("Content-Length")?.toLongOrNull() ?: bytes.size.toLong(),
                                binary = isBinary,
                                mtime = null
                            )
                        )
                    }

                    val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                    if (!resp.isSuccessful || root == null) {
                        val err = root?.safeString("error") ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IOException(err))
                    }
                    val content = root.safeString("content") ?: ""
                    val isBinary = root.safeBoolean("binary") == true
                    val mtime = root.safeString("mtime")
                    Result.success(
                        FileTextChunk(
                            content = content,
                            nextOffset = root.safeLong("nextOffset")
                                ?: (offset + content.toByteArray(Charsets.UTF_8).size),
                            truncated = root.safeBoolean("truncated") ?: false,
                            size = root.safeLong("size") ?: 0L,
                            binary = isBinary,
                            mtime = mtime
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 文件 API 的绝对 URL。媒体播放器（MediaPlayer / VideoView）自己发请求，
     * 走不了 OkHttp 客户端，所以需要单独拿 URL + 鉴权头的入口。
     */
    fun fileApiUrl(absPath: String, type: String = "read", sessionId: String? = null): String {
        val baseUrl = prefs.serverUrl.trimEnd('/')
        if (baseUrl.isBlank()) return ""
        val encoded = FileListParsing.encodeFilePath(absPath)
        return "$baseUrl/api/files/$encoded?type=$type" + sessionQuery(sessionId)
    }

    /**
     * `?sessionId=<id>`：pi-web `GET /api/files/<path>` 对「不在任何会话工作目录内」的文件只放行
     * 被该会话引用过的那一个（`isFilePathReferencedBySession`）。pi-web 的 FileViewer 一直带
     * 着 `sourceSessionId`（见 `getFileApiUrl`），App 之前不带，于是这类文件一律 403
     * Access denied —— 文件明明在本次会话里刚被工具读写过。
     */
    private fun sessionQuery(sessionId: String?): String {
        val sid = sessionId?.trim().orEmpty()
        if (sid.isEmpty()) return ""
        return "&sessionId=" + URLEncoder.encode(sid, "UTF-8")
    }

    /** 网关只认 `Authorization: Bearer`（除 /events 外不接受 ?token=），播放器要显式带上 */
    fun authHeaders(): Map<String, String> {
        val token = prefs.authToken.trim()
        return if (token.isEmpty()) emptyMap() else mapOf("Authorization" to "Bearer $token")
    }

    /**
     * Word（.docx）预览：GET /api/files/<路径>?type=preview
     *
     * pi-web 在服务端用 mammoth 把 docx 转成整页 HTML（图片内联为 data URI，
     * `DOCX_PREVIEW_MAX_BYTES = 10MB` 上限），前端只是把它塞进 <iframe>。
     * 手机端复用同一个接口，拿到的就是现成的 HTML 字符串。
     */
    suspend fun getFilePreviewHtml(absPath: String, sessionId: String? = null): Result<String> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            try {
                val req = Request.Builder()
                    .url(fileApiUrl(absPath, "preview", sessionId))
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) {
                        val err = runCatching {
                            JsonParser.parseString(body).asSafeObject()?.safeString("error")
                        }.getOrNull()
                        return@withContext Result.failure(IOException(err ?: "HTTP ${resp.code}"))
                    }
                    Result.success(body)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 把远端文件流式落到应用缓存目录，用于「用其他应用打开」（WPS / 系统 PDF 阅读器）。
     * 外部阅读器需要完整的本地文件，所以这里不做 256KB 分块；超过上限则直接报错。
     */
    suspend fun downloadFileToCache(
        absPath: String,
        maxBytes: Long = 128L * 1024 * 1024,
        sessionId: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val name = absPath.replace('\\', '/').substringAfterLast('/').ifBlank { "file" }
            .replace(CACHE_FILE_NAME_ILLEGAL_CHARS, "_")
        val dir = File(prefs.cacheDir, "opened")
        if (!dir.exists() && !dir.mkdirs()) {
            return@withContext Result.failure(IOException("无法创建缓存目录"))
        }
        // 缓存键带「连接身份 + 完整路径」的哈希：不同目录、不同网关下的同名文件
        // （README.md、image.png）以前会互相覆盖，后下载的静默替掉前一个。
        val target = File(dir, "${MediaLoadPolicy.sha1Hex("${connectionKey()}\u0000$absPath").take(12)}_$name")
        var lastError: Throwable? = null
        // 与 [getFileBytes] 同理：HTTP/1.1 + 一次重试，避开 nginx h2 的 RST_STREAM。
        repeat(FILE_TRANSFER_ATTEMPTS) { attempt ->
            try {
                val req = Request.Builder().url(fileApiUrl(absPath, "download", sessionId)).get().build()
                fileClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("下载失败 (${resp.code})"))
                    }
                    val body = resp.body ?: return@withContext Result.failure(IOException("响应为空"))
                    if (body.contentLength() > maxBytes) {
                        return@withContext Result.failure(IOException("文件过大，暂不支持外部打开"))
                    }
                    body.byteStream().use { input ->
                        target.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                total += read
                                if (total > maxBytes) throw IOException("文件过大，暂不支持外部打开")
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                // 控制 opened/ 缓存文件数量（保留最近 20 个），避免长期使用无限占满缓存目录
                runCatching {
                    dir.listFiles()
                        ?.filter { it.isFile && it != target }
                        ?.sortedByDescending { it.lastModified() }
                        ?.drop(19)
                        ?.forEach { it.delete() }
                }
                return@withContext Result.success(target)
            } catch (e: Exception) {
                lastError = e
                runCatching { target.delete() }
                if (attempt < FILE_TRANSFER_ATTEMPTS - 1) delay(250L * (attempt + 1))
            }
        }
        Result.failure(lastError ?: IOException("下载失败"))
    }

    /**
     * 文件原始字节（图片/Office 预览用）：GET /api/files/<绝对路径>?type=download
     *
     * 传输层使用 [fileClient]（HTTP/1.1）并在 `stream was reset` / 连接中断时重试一次；
     * 这两点都是公网隧道下大文件必需的，否则 6MB 的 4K 图片会随机失败。
     */
    suspend fun getFileBytes(
        absPath: String,
        maxBytes: Long = 12L * 1024 * 1024,
        sessionId: String? = null
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val url = fileApiUrl(absPath, "download", sessionId)
        var lastError: Throwable? = null
        repeat(FILE_TRANSFER_ATTEMPTS) { attempt ->
            try {
                val req = Request.Builder().url(url).get().build()
                fileClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IOException("读取文件失败 (${resp.code})"))
                    }
                    val body = resp.body ?: return@withContext Result.failure(IOException("响应为空"))
                    val cl = body.contentLength()
                    if (cl > maxBytes) {
                        return@withContext Result.failure(IOException("文件过大（${cl / 1024 / 1024} MB），暂不预览"))
                    }
                    val stream = body.byteStream()
                    val output = java.io.ByteArrayOutputStream(cl.coerceIn(0L, maxBytes).toInt().coerceAtLeast(8 * 1024))
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = try {
                            stream.read(buffer)
                        } catch (e: IOException) {
                            // 读到一半被 reset：已收到的字节不完整，只能整份重试
                            throw FileStreamResetException(e)
                        }
                        if (read < 0) break
                        if (read == 0) continue
                        total += read
                        if (total > maxBytes) {
                            return@withContext Result.failure(IOException("文件过大，暂不预览"))
                        }
                        output.write(buffer, 0, read)
                    }
                    return@withContext Result.success(output.toByteArray())
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < FILE_TRANSFER_ATTEMPTS - 1) delay(250L * (attempt + 1))
            }
        }
        Result.failure(lastError ?: IOException("读取文件失败"))
    }

    /** 读到一半被链路重置（HTTP/2 RST_STREAM / 隧道断流），整份重试即可恢复 */
    private class FileStreamResetException(cause: Throwable) :
        IOException("stream was reset", cause)

    /** 开关 pi 的自动上下文压缩（agent {"type":"set_auto_compaction","enabled":bool}） */
    suspend fun setAutoCompaction(sessionId: String, enabled: Boolean): Result<Boolean> =
        sendAgentAction(sessionId, mapOf("type" to "set_auto_compaction", "enabled" to enabled))

    /**
     * 读 pi 当前的自动压缩开关（`get_state` RPC，`POST /api/agent/{id}`）。
     *
     * 为什么不用 [getAgentState]：那条走 `GET /api/agent/{id}?systemPrompt=0`，**不会启动 wrapper**，
     * 没有活跃运行时就根本不返回 `autoCompactionEnabled`。而 `/auto-compact` 是「读当前值 → 取反 →
     * 回写」，读不到就必须算失败：把「读不到」当成「已关闭」会把开关每次执行都往关的方向扫，
     * 而且写的是全局 settings。这里与 pi-web 的 `/auto-compact` 走同一条 RPC 通道（`get_state`）。
     *
     * 返回 `null` = 服务器返回了状态但没有这个字段（旧网关）；调用侧按内核默认 `true` 兜底。
     */
    suspend fun getAutoCompactionEnabled(sessionId: String): Result<Boolean?> =
        sendAgentCommandData(sessionId, mapOf("type" to "get_state")).map { data ->
            data.asSafeObject()?.safeBoolean("autoCompactionEnabled")
        }

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
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.failure(IOException("git 状态响应无法解析"))
                val files = (root.safeArray("files")?.mapNotNull { el ->
                    val o = el.asSafeObject() ?: return@mapNotNull null
                    GitFileChange(
                        filePath = o.safeString("filePath") ?: "",
                        status = o.safeString("status") ?: "",
                        code = o.safeString("code") ?: ""
                    )
                } ?: emptyList()).filter { it.filePath.isNotBlank() }.distinctBy { it.filePath }
                Result.success(
                    GitStatusResult(
                        isGitRepository = root.safeBoolean("isGitRepository") == true,
                        repositoryRoot = root.safeString("repositoryRoot") ?: "",
                        files = files,
                        additions = root.safeInt("additions") ?: 0,
                        deletions = root.safeInt("deletions") ?: 0
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
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                if (!resp.isSuccessful || root == null) {
                    val err = root?.safeString("error") ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException("读取 diff 失败：$err"))
                }
                Result.success(
                    GitDiffResult(
                        supported = root.safeBoolean("supported") == true,
                        status = root.safeString("status") ?: "",
                        patch = root.safeString("patch") ?: ""
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
     * 0.17.0 起命中可能来自侧聊，见 [openableAsSession]。
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
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.success(emptyList())
                val arr = root.safeArray("results")
                    ?: return@withContext Result.success(emptyList())
                val out = mutableListOf<SessionSearchHit>()
                for (el in arr) {
                    val o = el.asSafeObject() ?: continue
                    val sessionEl = o.safeObject("session") ?: continue
                    val session = runCatching { gson.fromJson(sessionEl, Session::class.java) }.getOrNull()
                        ?: continue
                    out.add(
                        SessionSearchHit(
                            session = session,
                            entryId = o.safeString("entryId"),
                            match = o.safeString("match") ?: "",
                            before = o.safeString("before") ?: "",
                            after = o.safeString("after") ?: ""
                        )
                    )
                }
                Result.success(out.openableAsSession())
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
            val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
            val req = Request.Builder().url("$baseUrl/api/agent/$encodedSessionId").post(body).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("读取命令列表失败 (${resp.code})"))
                }
                val raw = resp.body?.string() ?: "{}"
                val root = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.success(emptyList())
                val data = root.safeObject("data") ?: root
                val arr = data.safeArray("commands")
                    ?: return@withContext Result.success(emptyList())
                val out = mutableListOf<SlashCommand>()
                for (el in arr) {
                    val o = el.asSafeObject() ?: continue
                    val name = o.safeString("name")?.trim() ?: continue
                    if (name.isBlank()) continue
                    out.add(
                        SlashCommand(
                            name = name,
                            description = o.safeString("description") ?: "",
                            source = o.safeString("source") ?: ""
                        )
                    )
                }
                Result.success(
                    out.filter { it.name.isNotBlank() }.distinctBy { "${it.source}:${it.name}" }
                )
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

    /**
     * 停止本轮。服务端会把压缩期间被 park 住的提示词一并退回（`{steering, followUp, images}`），
     * 这些内容必须还给输入框 —— 与 pi 的 TUI、pi-web 的 handleAbort 一致。
     */
    suspend fun stopAgent(sessionId: String): Result<RecalledPrompts> = withContext(Dispatchers.IO) {
        sendAgentCommandData(sessionId, mapOf("type" to "abort"))
            .map { data -> AgentCommandPayload.recalledOf(data) }
    }

    /** 撤回排队消息（含压缩期间 park 住的）。返回被退回的正文与图片，由界面还给输入框。 */
    suspend fun clearQueue(sessionId: String): Result<RecalledPrompts> = withContext(Dispatchers.IO) {
        sendAgentCommandData(sessionId, mapOf("type" to "clear_queue"))
            .map { data -> AgentCommandPayload.recalledOf(data) }
    }

    /**
     * 手动压缩上下文（可带自定义摘要指令）：`POST /api/agent/{id} {"type":"compact","customInstructions":"…"}`。
     *
     * 对齐 pi-web 的 `/compact [instructions]`：pi 的 `compact(customInstructions)` 会把它原样交给
     * 摘要器。不设客户端超时（见 [compactClient]）。
     *
     * 带 `?ttfb=long`：这条命令的响应要等一次完整的摘要生成（十几秒起），而 `/api/agent/{id}`
     * 上还挂着 prompt / get_state 这类毫秒级命令，网关无法从流式 body 分辨命令类型，
     * 所以由这条命令自己标注，免得网关 5s 就回 503、界面报「压缩失败」而服务端仍在压缩。
     */
    suspend fun compactContext(
        sessionId: String,
        customInstructions: String? = null
    ): Result<Boolean> {
        val payload = buildMap<String, Any> {
            put("type", "compact")
            customInstructions?.takeIf { it.isNotBlank() }?.let { put("customInstructions", it) }
        }
        return sendAgentAction(sessionId, payload, httpClient = compactClient, query = "ttfb=long")
    }

    /**
     * 停止正在进行的压缩（含自动压缩）：`POST /api/agent/{id} {"type":"abort_compaction"}`。
     *
     * 对齐 pi-web 的 `abortCompaction()`（wrapper：`case "abort_compaction": this.inner.abortCompaction()`，
     * 返回 null）：pi 会同时 abort 手动与自动两条压缩控制器，随后 `compact()` 以
     * "Compaction cancelled" 拒绝，并照常发 `compaction_end`。
     *
     * 这是「压缩很久/看起来卡住」的正确出口 —— pi-web 的前端也是把压缩按钮就地变成
     * 「停止压缩」，而不是给请求加超时。
     */
    suspend fun abortCompaction(sessionId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "abort_compaction"))
    }

    /**
     * 回答扩展的对话框：`POST /api/agent/{id}` `{"type":"extension_ui_response"}`。
     *
     * pi-web 的三种回执形态（`lib/types.ts` 的 `ExtensionUiResponse`）：
     *  - select / input / editor / custom → `{id, value}`；
     *  - confirm → `{id, confirmed}`；
     *  - 用户关掉不答 → `{id, cancelled:true}`。
     *
     * 上游按 id 找回等待中的 promise，找不到就静默忽略（`resolveExtensionUiResponse`），
     * 所以 SSE 重连后被重放的那条请求再答一次也无害。
     *
     * 为什么带 `?ttfb=long`：扩展在等这次回答的同时，会话可能正卡在 `session_start` 里，
     * 上游回包要等扩展被唤醒后才可能——用 5s 默认线会把它误判成卡死。
     */
    suspend fun respondExtensionUi(
        sessionId: String,
        requestId: String,
        value: String? = null,
        confirmed: Boolean? = null,
        cancelled: Boolean = false
    ): Result<Boolean> {
        if (requestId.isBlank()) return Result.failure(IllegalArgumentException("缺少请求ID"))
        val payload = mutableMapOf<String, Any>(
            "type" to "extension_ui_response",
            "id" to requestId
        )
        when {
            cancelled -> payload["cancelled"] = true
            confirmed != null -> payload["confirmed"] = confirmed
            else -> payload["value"] = value.orEmpty()
        }
        return sendAgentAction(sessionId, payload, query = "ttfb=long")
    }

    suspend fun updateModel(sessionId: String, provider: String, modelId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "set_model", "provider" to provider, "modelId" to modelId))
    }

    suspend fun updateThinkingLevel(sessionId: String, level: ThinkingLevel): Result<Boolean> = withContext(Dispatchers.IO) {
        sendAgentAction(sessionId, mapOf("type" to "set_thinking_level", "level" to level.value))
    }

    /**
     * 读取会话运行时的权威状态 (`GET /api/agent/{id}?systemPrompt=0`)。
     *
     * 为什么改了路由 (对齐 pi-web 0.13 的 `agentStatusUrl()`，#1306)：`get_state` 的
     * `systemPrompt` 几乎就是整个回包（大 AGENTS.md / 技能 / 工具说明，上游实测 300 KB → <2 KB），
     * 而我们每 1.5–3s 拉一次。`?systemPrompt=0` 是同一路由的官方精简参数；
     * `GET /api/sessions/{id}/state` 没有这个参数，之前那点字节全靠白花。
     * 两个路由都不启动 wrapper（无活运行时一律 `{running:false}`），语义一致。
     *
     * 一次调用同时解决三件事：
     * 1. **是否在跑**：`running`（等价 `/api/sessions` 里 runningSessionIds 的判定）；
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
            val req = Request.Builder()
                .url("$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}?systemPrompt=0")
                .get()
                .build()
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(IOException("读取 agent 状态失败 (${payload.code})"))
            }
            val raw = payload.body
            val root = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull()
                ?: return@withContext Result.failure(IOException("agent 状态响应无法解析"))
            // 兼容两种形态：{running, state:{...}} 或直接是 state 本体
            val data = root.safeObject("state") ?: root.safeObject("data") ?: root
            val usage = data.safeObject("contextUsage")
            val modelObj = data.safeObject("model")
            val queue = data.safeObject("queuedMessages")
            fun queueTexts(key: String): List<String> {
                val arr = queue?.safeArray(key) ?: return emptyList()
                return arr.mapNotNull { el ->
                    when {
                        el.isJsonPrimitive -> el.asString
                        el.isJsonObject -> el.asJsonObject.safeString("message")
                            ?: el.asJsonObject.safeString("text")
                        else -> null
                    }
                }.filter { it.isNotBlank() }
            }
            val isStreaming = data.safeBoolean("isStreaming") == true
            val isPromptRunning = data.safeBoolean("isPromptRunning") == true
            val isBashRunning = data.safeBoolean("isBashRunning") == true
            // 顶层 running = 「pi-web 里有没有活着的 agent 运行时」，不等于正在执行。
            // 兼容 POST /api/agent/{id} 的 {success,data} 形态（没有顶层 running 字段）。
            val runtimeAlive = root.safeBoolean("running") ?: data.has("isStreaming")
            Result.success(
                AgentRuntimeState(
                    percent = usage?.safeDouble("percent") ?: 0.0,
                    contextWindow = usage?.safeLong("contextWindow") ?: 0L,
                    contextTokens = usage?.safeLong("tokens") ?: 0L,
                    hasContextUsage = usage != null,
                    provider = modelObj?.safeString("provider"),
                    modelId = modelObj?.safeString("id") ?: modelObj?.safeString("modelId"),
                    thinkingLevel = data.safeString("thinkingLevel"),
                    isStreaming = isStreaming,
                    isCompacting = data.safeBoolean("isCompacting") == true,
                    isBashRunning = isBashRunning,
                    pendingMessageCount = data.safeInt("pendingMessageCount") ?: 0,
                    // 与 pi-web 同一口径（rpc-manager 的 `isRunning()`）：流式生成 / 提示词在跑 /
                    // bash 在跑 / **正在压缩上下文**。之前漏了压缩：手动 compact 期间服务端算「在跑」，
                    // 本地却算「空闲」，于是压缩刚开就被判完成（通知 / 唤醒锁 / 排队消息都对不上）。
                    running = runtimeAlive &&
                        (isStreaming || isPromptRunning || isBashRunning ||
                            data.safeBoolean("isCompacting") == true),
                    runtimeAlive = runtimeAlive,
                    autoCompactionEnabled = data.safeBoolean("autoCompactionEnabled") == true,
                    autoRetryEnabled = data.safeBoolean("autoRetryEnabled") == true,
                    steering = queueTexts("steering"),
                    followUp = queueTexts("followUp")
                )
            )
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
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(IOException("读取置顶/归档状态失败 (${payload.code})"))
            }
            Result.success(parseUiState(payload.body))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUiState(raw: String): SessionUiSnapshot {
        val root = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull() ?: JsonObject()
        val state = root.safeObject("state") ?: root
        val flags = mutableMapOf<String, SessionUiFlags>()
        state.safeObject("sessions")?.entrySet()?.forEach { (id, el) ->
            el.asSafeObject()?.let { o ->
                flags[id] = SessionUiFlags(
                    pinned = o.get("pinnedAt")?.takeIf { !it.isJsonNull } != null,
                    archived = o.get("archivedAt")?.takeIf { !it.isJsonNull } != null
                )
            }
        }
        val pinnedProjects = mutableSetOf<String>()
        state.safeObject("projects")?.entrySet()?.forEach { (key, el) ->
            if (el.isJsonObject && el.asJsonObject.get("pinnedAt")?.takeIf { !it.isJsonNull } != null) {
                pinnedProjects.add(key.lowercase())
            }
        }
        val order = state.safeArray("projectOrder")
            ?.mapNotNull { it.asSafeString() } ?: emptyList()
        return SessionUiSnapshot(
            revision = state.safeInt("revision") ?: 0,
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

    /**
     * 项目排序：把 projectKey 移到 anchorKey 之前/之后（pi 侧 move-project）
     *
     * 对齐 pi-web `SessionSidebar` 的 payload：
     * `{"action":"move-project","projectKey":..,"anchorKey":..,"position":"before"|"after","add":[..]}`
     * —— 漏掉 `position` 会被服务端 400 `position must be "before" or "after"`；
     * `anchorKey` 若等于 `projectKey`（例如锚点取成了换位后的自己）则 400
     * `projectKey and anchorKey must differ`。
     *
     * @param position "before" 或 "after"（见 [com.pi.app.data.model.ProjectMovePlan]）
     * @param addKeys 同分组内尚未写进 projectOrder 的项目 key，用于保持 PC 端展示顺序不跳变
     */
    suspend fun moveProject(
        projectKey: String,
        anchorKey: String,
        position: String,
        addKeys: List<String> = emptyList()
    ): Result<Boolean> =
        postUiState(
            JsonObject().apply {
                addProperty("action", "move-project")
                addProperty("projectKey", projectKey)
                addProperty("anchorKey", anchorKey)
                addProperty("position", position)
                if (addKeys.isNotEmpty()) {
                    add("add", gson.toJsonTree(addKeys))
                }
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
     * 服务端限制：单次合计 ≤100MB（pi-web 0.17.0 起不再限单文件 25MB）；App 侧单文件上限
     * 50MB（`ChatScreen.MAX_UPLOAD_FILE_BYTES`，整块读入内存的代价）。
     * 返回 {uploaded, skipped, errors}（有失败时 207）。
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
            val dirSegment = FileListParsing.encodeFilePath(directoryPath)
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
                val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                if (json == null) {
                    return@withContext Result.failure(IOException("上传响应无法解析 (${resp.code})"))
                }
                if (!resp.isSuccessful && resp.code != 207) {
                    val err = json.safeString("error") ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException("上传失败：$err"))
                }
                fun strList(key: String) = json.safeArray(key)
                    ?.mapNotNull { it.asSafeString() } ?: emptyList()
                val errors = json.safeArray("errors")
                    ?.mapNotNull { el ->
                        val o = el.asSafeObject() ?: return@mapNotNull null
                        (o.safeString("name") ?: "?") + ": " + (o.safeString("error") ?: "")
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

    /**
     * `POST /api/agent/{id}` 的唯一出口：所有命令都走这里，保证错误类型与回执解析只有一份。
     *
     * 成功时返回响应体的 `data`（可能不存在，如 `abort` 没有东西可退回时为 null）；
     * 失败时返回 [AgentCommandException]（带 `code` / `accepted` / `missingCwd`），
     * 传输层异常原样上抛 —— 调用方据此区分「确定没收到」与「回执丢了」。
     */
    private suspend fun sendAgentCommandData(
        sessionId: String,
        payload: Map<String, Any>,
        httpClient: OkHttpClient = client,
        query: String? = null
    ): Result<JsonElement?> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            try {
                val body = gson.toJson(payload).toRequestBody(jsonMediaType)
                val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
                val url = "$baseUrl/api/agent/$encodedSessionId" + (query?.let { "?$it" } ?: "")
                val req = Request.Builder().url(url).post(body).build()
                httpClient.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errBody = runCatching { response.body?.string() }.getOrNull()
                        return@withContext Result.failure(
                            AgentCommandPayload.failureOf(errBody, response.code)
                        )
                    }
                    val bodyStr = runCatching { response.body?.string() }.getOrNull().orEmpty()
                    val root = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    Result.success(root?.get("data")?.takeIf { !it.isJsonNull })
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun sendAgentAction(
        sessionId: String,
        payload: Map<String, Any>,
        httpClient: OkHttpClient = client,
        query: String? = null
    ): Result<Boolean> =
        sendAgentCommandData(sessionId, payload, httpClient, query).map { true }

    /**
     * 续租会话（`POST /api/agent/{id}/lease`）：重置服务端的空闲倒计时，并回报脚下在干什么。
     *
     * pi-web 0.13 起只有这个请求能挡空闲回收（10 分钟）；持着 SSE 流不再保活。
     * 旧网关没有该路由（404/405）时返回 `supported=false`，调用方应停止循环、不报错。
     */
    suspend fun renewSessionLease(sessionId: String): Result<SessionLeaseReply> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}/lease")
                .post("".toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.isSuccessful -> Result.success(
                        SessionLeasePayload.of(runCatching { resp.body?.string() }.getOrNull())
                    )
                    // 旧网关：没有这个路由。不是错误，只是不支持。
                    resp.code == 404 || resp.code == 405 -> Result.success(SessionLeaseReply(supported = false))
                    else -> Result.failure(IOException("续租失败 (HTTP ${resp.code})"))
                }
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
        // 未配置网关就是没有技能可列。旧实现这里返回一份写死的「hindsight / context-mode /
        // terminal-controller …」假技能表，每个还带着编造的 SKILL.md 正文 —— 用户以为
        // 这些真的装在他电脑上，点进去看到的却是本地模板。
        if (baseUrl.isBlank()) return@withContext Result.success(emptyList())
        val validCwd = sanitizeCwd(cwd)
        val urlsToTry = if (validCwd.isNotBlank()) {
            val encodedCwd = URLEncoder.encode(validCwd, "UTF-8")
            listOf("$baseUrl/api/skills?cwd=$encodedCwd", "$baseUrl/api/skills")
        } else {
            listOf("$baseUrl/api/skills")
        }

        // 记住最后一次非 2xx 的状态码：失败时要把 HTTP 码带出去（对齐 pi-web 的
        // `Failed to load skills (HTTP 502)`），而不是只给一句笼统的“加载失败”。
        var lastHttpCode: Int? = null
        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).get().build()
                val parsedList = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastHttpCode = response.code
                        return@use null
                    }
                    val body = response.body?.string() ?: "{}"
                    val jsonEl = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return@use null
                    val array = when {
                        jsonEl.isJsonArray -> jsonEl.asJsonArray
                        jsonEl.isJsonObject -> {
                            val rootObj = jsonEl.asJsonObject
                            rootObj.safeArray("skills") ?: rootObj.safeArray("items")
                        }
                        else -> null
                    } ?: return@use null

                    val list = mutableListOf<SkillInfo>()
                    for (item in array) {
                        val obj = item.asSafeObject() ?: continue
                        val name = obj.safeString("name")
                            ?: obj.safeString("id")
                            ?: continue
                        val description = obj.safeString("description")
                        val baseDir = obj.safeString("baseDir")
                            ?: obj.safeString("dir")
                            ?: obj.safeString("directory")
                        val rawFilePath = obj.safeString("filePath")
                            ?: obj.safeString("path")
                            ?: obj.safeString("skillPath")
                            ?: obj.safeString("location")
                        val resolvedFilePath = rawFilePath?.takeIf { it.isNotBlank() }
                            ?: baseDir?.takeIf { it.isNotBlank() }?.let { dir ->
                                val sep = if (dir.contains('\\')) "\\" else "/"
                                dir.trimEnd('/', '\\') + sep + "SKILL.md"
                            }
                        val inlineContent = obj.safeString("content")
                            ?: obj.safeString("markdown")
                            ?: obj.safeString("rawContent")
                            ?: obj.safeString("body")
                        val disabled = obj.safeBoolean("disableModelInvocation")
                            ?: obj.safeBoolean("disabled")
                            ?: false

                        val installObj = obj.safeObject("install")
                        val install = installObj?.let { runCatching { gson.fromJson(it, SkillInstallInfo::class.java) }.getOrNull() }

                        val sourceInfoObj = obj.safeObject("sourceInfo")
                        val sourceInfo = sourceInfoObj?.let { runCatching { gson.fromJson(it, SkillSourceInfo::class.java) }.getOrNull() }

                        val scopeStr = install?.scope?.takeIf { it.isNotBlank() }
                            ?: obj.safeString("scope")
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
                // 逐个候选 URL 回退属正常流程；全部失败必须如实报错，
                // 否则「接口挂了」会伪装成「加载成功」（带上假的技能列表）。
                Log.w("PiApiClient", "技能接口候选 URL 失败: ${e.message}")
            }
        }
        Log.w("PiApiClient", "加载技能列表失败")
        Result.failure(IOException(lastHttpCode?.let { "HTTP $it" } ?: "网络错误"))
    }

    // ==========================================
    // 6. 插件扩展 (GET /api/plugins)
    // ==========================================

    suspend fun getPlugins(cwd: String = ""): Result<List<PluginPackage>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        // 同技能：未配置网关就是没有插件，不给编造清单
        if (baseUrl.isBlank()) return@withContext Result.success(emptyList())
        val validCwd = sanitizeCwd(cwd)
        val urlsToTry = if (validCwd.isNotBlank()) {
            val encodedCwd = URLEncoder.encode(validCwd, "UTF-8")
            listOf("$baseUrl/api/plugins?cwd=$encodedCwd", "$baseUrl/api/plugins")
        } else {
            listOf("$baseUrl/api/plugins")
        }

        // 同 getSkills：把 HTTP 码带出去，供界面显示并决定是否需要重试。
        var lastHttpCode: Int? = null
        for (url in urlsToTry) {
            try {
                val req = Request.Builder().url(url).get().build()
                val parsedList = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastHttpCode = response.code
                        return@use null
                    }
                    val body = response.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull() ?: return@use null
                    val list = mutableListOf<PluginPackage>()
                    json.safeArray("packages")?.let { array ->
                        for (item in array) {
                            val obj = item.asSafeObject() ?: continue
                            val packageName = obj.safeString("packageName") ?: "plugin"
                            val version = obj.safeString("version") ?: "1.0.0"
                            val description = obj.safeString("description")
                            val source = obj.safeString("source")
                            val scope = obj.safeString("scope") ?: "global"
                            val status = obj.safeString("status") ?: "loaded"
                            val disabled = obj.safeBoolean("disabled") ?: false

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
        Log.w("PiApiClient", "加载插件列表失败")
        Result.failure(IOException(lastHttpCode?.let { "HTTP $it" } ?: "网络错误"))
    }

    // ==========================================
    // 7. MCP 协议 (GET /api/mcp)
    // ==========================================

    suspend fun getMcpServers(cwd: String = ""): Result<List<McpServerInfo>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.success(emptyList<McpServerInfo>())
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
                val resultServers = client.newCall(req).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull() ?: return@use null

                    // 代码模式不在 /api/mcp 里（上游也从来没在这里）：旧代码在此“读不到就
                    // 默认 automatic/3000”，等于把编造的值当成用户配置显示。现在只读服务器。
                    val servers = mutableListOf<McpServerInfo>()
                    json.safeArray("servers")?.let { array ->
                        for (item in array) {
                            val obj = item.asSafeObject() ?: continue
                            val name = obj.safeString("name") ?: ""
                            val description = obj.safeString("description")
                            val rawTransport = obj.safeString("transport")
                            val cmd = obj.safeString("command")
                            val transport = when {
                                rawTransport?.equals("stdio", ignoreCase = true) == true -> "stdio"
                                rawTransport != null -> "http"
                                !cmd.isNullOrBlank() -> "stdio"
                                else -> "http"
                            }
                            val urlVal = obj.safeString("url") ?: run {
                                val args = obj.safeArray("args")
                                    ?.mapNotNull { it.asSafeString() }?.joinToString(" ") ?: ""
                                if (!cmd.isNullOrBlank()) "$cmd $args".trim() else null
                            }
                            val enabled = obj.safeBoolean("enabled") ?: true
                            val count = obj.safeInt("toolExposureCount") ?: 0
                            val state = obj.safeObject("status")?.safeString("state") ?: "connected"

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
                    servers
                }
                if (resultServers != null && resultServers.isNotEmpty()) {
                    return@withContext Result.success(resultServers)
                }
            } catch (_: Exception) {
            }
        }
        // 这里曾经回退到一份写死的「hindsight / wren 已连接」列表：URL 指向
        // 127.0.0.1（在手机上等于手机自己），status 却是 connected，toolExposureCount
        // 也是编的。任何一次网络抖动都会让界面显示出一台根本不存在的健康服务，
        // 比直接显示「加载失败」糟糕得多。改为返回空列表。
        Log.w("PiApiClient", "加载 MCP 配置失败，返回空列表（不再伪造默认服务器）")
        Result.success(emptyList<McpServerInfo>())
    }

    private fun getDefaultCatalog() = DynamicModelCatalog(
        models = listOf(
            ModelInfo("gpt-6.1-sol", "GPT-6.1 Sol", "codywon", ModelDefaults.DEFAULT_CONTEXT_WINDOW, ModelDefaults.DEFAULT_MAX_OUTPUT_TOKENS, supportsThinking = true, supportsVision = true, isActive = true),
            ModelInfo("deepseek-flash", "DeepSeek Flash", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro", "codywon", 128000L, 8192L, supportsThinking = true, supportsVision = false),
            ModelInfo("gemini-3.8-flash-high", "Gemini 3.8 Flash High", "codywon", 1000000L, 16384L, supportsThinking = true, supportsVision = true)
        ),
        thinkingLevelsMap = emptyMap()
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
                    obj.safeArray("runningSessionIds")?.let { arr ->
                        val runningIds = arr.mapNotNull { it.asSafeString() }.toSet()
                        if (runningIds.contains(sessionId)) {
                            return@withContext Result.success(true)
                        }
                    }
                    obj.safeArray("sessions")?.let { sessionsArr ->
                        for (el in sessionsArr) {
                            val sObj = el.asSafeObject() ?: continue
                            val id = sObj.safeString("id")
                            if (id == sessionId) {
                                val isRunning = sObj.safeBoolean("isRunning") ?: false
                                val isStreaming = sObj.safeBoolean("isStreaming") ?: false
                                return@withContext Result.success(isRunning || isStreaming)
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
    suspend fun getProviderAuthStates(baseUrl: String = prefs.serverUrl): Result<Map<String, ProviderAuthState>> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/auth/providers").get().build()
            val payload = readGet(req)
            if (payload.code !in 200..299) {
                return@withContext Result.failure(IOException("读取凭据状态失败 (${payload.code})"))
            }
            val result = ProviderAuthStateSerializer.parse(payload.body)
                ?: return@withContext Result.failure(IOException("凭据状态响应无法解析"))
            Result.success(result)
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
            val providersObj = rootConfig.safeObject("providers")
                ?: JsonObject().also { rootConfig.add("providers", it) }

            // 大小写不敏感匹配已有 provider key，防止产生大小写重复节点
            val actualProviderKey = providersObj.keySet().firstOrNull { it.equals(pKeyInput, ignoreCase = true) } ?: pKeyInput
            val providerObj = providersObj.safeObject(actualProviderKey)
                ?: JsonObject().also { providersObj.add(actualProviderKey, it) }

            val existingBaseUrl = providerObj.safeString("baseUrl")?.trim() ?: ""
            val hasExistingCustomModelsArray = providerObj.safeArray("models") != null && existingBaseUrl.isNotBlank()

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
                val modelsArr = providerObj.safeArray("models")
                    ?: JsonArray().also { providerObj.add("models", it) }

                var replaced = false
                for (i in 0 until modelsArr.size()) {
                    val elem = modelsArr.get(i)
                    if (elem.isJsonObject && elem.asJsonObject.safeString("id").equals(mId, ignoreCase = true)) {
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
                val overridesObj = providerObj.safeObject("modelOverrides")
                    ?: JsonObject().also { providerObj.add("modelOverrides", it) }
                val actualModelKey = overridesObj.keySet().firstOrNull { it.equals(mId, ignoreCase = true) } ?: mId
                val ovModelObj = overridesObj.safeObject(actualModelKey)
                    ?: JsonObject().also { overridesObj.add(actualModelKey, it) }
                ovModelObj.addProperty("name", modelName.trim().ifBlank { mId })
                ovModelObj.addProperty("contextWindow", contextWindow)
                ovModelObj.addProperty("maxTokens", maxTokens)
                ovModelObj.addProperty("reasoning", reasoning)
                ovModelObj.add("input", inputArr)
            }

            // 写入前再次执行自愈校验，确保生成 100% 合法的 models.json
            sanitizeAndHealModelsConfig(rootConfig)

            val saved = putModelsConfigToServer(baseUrl, rootConfig)
            if (saved.isFailure) {
                return@withContext Result.failure(
                    IOException("保存模型配置失败：${saved.exceptionOrNull()?.message ?: "未知错误"}")
                )
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

        // 4. 最后只认服务端内联携带的 content，读不到就如实报错。
        // 旧实现这里会拿 getBuiltInSkillMarkdown() 生成一份「看起来像真的」SKILL.md 模板：
        // 用户看到的是编造的技能正文，而且一旦点保存就会真的写回磁盘（把假内容变成真文件）。
        val inline = skill.content?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IOException("读取技能内容失败：${skill.name}"))
        Result.success(inline)
    }

    private fun extractSkillMarkdownFromBody(body: String): String? {
        if (!body.startsWith("{") && !body.startsWith("[")) {
            return if (!body.startsWith("<!DOCTYPE", ignoreCase = true) && !body.startsWith("<html", ignoreCase = true)) {
                body
            } else null
        }
        return runCatching {
            val json = JsonParser.parseString(body).asSafeObject() ?: return@runCatching null
            val directKeys = listOf("content", "markdown", "rawContent", "raw", "body", "text", "source")
            for (k in directKeys) {
                val v = json.safeString(k)
                if (!v.isNullOrBlank()) return@runCatching v
            }
            for (nestedKey in listOf("skill", "data", "document")) {
                json.safeObject(nestedKey)?.let { sub ->
                    for (k in directKeys) {
                        val v = sub.safeString(k)
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
                    val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull() ?: return@use null
                    val resultsArr = root.safeArray("results") ?: return@use null
                    val list = mutableListOf<SkillSearchResult>()
                    for (el in resultsArr) {
                        val obj = el.asSafeObject() ?: continue
                        val pkg = obj.safeString("package") ?: continue
                        val installs = obj.safeString("installs") ?: ""
                        val url = obj.safeString("url") ?: ""
                        list.add(SkillSearchResult(`package` = pkg, installs = installs, url = url))
                    }
                    list.filter { it.`package`.isNotBlank() }.distinctBy { it.`package` }
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
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull() ?: return@use null
                val skillsArr = root.safeArray("skills") ?: return@use null
                val resList = mutableListOf<SkillSearchResult>()
                for (el in skillsArr) {
                    val obj = el.asSafeObject() ?: continue
                    val name = obj.safeString("name")?.trim() ?: continue
                    val source = obj.safeString("source")?.trim()
                    val id = obj.safeString("id")?.trim()
                    val repo = source ?: id ?: continue
                    val installsNum = obj.safeLong("installs") ?: 0L
                    val installsStr = when {
                        installsNum >= 1_000_000 -> "${String.format("%.1f", installsNum / 1_000_000.0).replace(".0", "")}M installs"
                        installsNum >= 1_000 -> "${String.format("%.1f", installsNum / 1_000.0).replace(".0", "")}K installs"
                        installsNum > 0 -> "$installsNum installs"
                        else -> ""
                    }
                    val url = if (!id.isNullOrBlank()) "https://skills.sh/$id" else ""
                    resList.add(SkillSearchResult(`package` = "$repo@$name", installs = installsStr, url = url))
                }
                resList.filter { it.`package`.isNotBlank() }.distinctBy { it.`package` }
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
                        JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error")
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
                val root = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull() ?: return@use emptyList<SkillUpdateInfo>()
                val arr = root.safeArray("updates") ?: return@use emptyList<SkillUpdateInfo>()
                val resList = mutableListOf<SkillUpdateInfo>()
                for (el in arr) {
                    val obj = el.asSafeObject() ?: continue
                    val pkg = obj.safeString("package") ?: continue
                    val sc = obj.safeString("scope") ?: "global"
                    val st = obj.safeString("state") ?: "up-to-date"
                    val cur = obj.safeString("currentVersion")
                    val lat = obj.safeString("latestVersion")
                    val msg = obj.safeString("message")
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
                        JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error")
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
                val parts = endpointOrCommand.trim().split(STDIO_COMMAND_WHITESPACE_RUN)
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

    // ── 模型管理配置缓存 (models.json + 凭据状态 + 模型目录) ──
    // 页面先渲染缓存，网络同步回来再平滑替换；缓存按连接身份隔离。
    private val modelsManagementCache = ModelsManagementCacheStore(File(prefs.cacheDir, "models-management"))

    /** 连接身份：缓存与请求结果都按它隔离，切换网关后不会读到上一台机器的配置。 */
    fun connectionKey(baseUrl: String = prefs.serverUrl): String =
        modelsManagementConnectionKey(baseUrl)

    /** 当前连接的内存快照（不碰磁盘，可放心在 Compose 初始化里调用）。 */
    fun peekCachedModelsManagementData(): ModelsManagementData? =
        modelsManagementCache.peek(connectionKey())

    /** 读取当前连接的历史快照：内存未命中才读磁盘，需在协程里调用。 */
    suspend fun loadCachedModelsManagementData(): ModelsManagementData? =
        modelsManagementCache.load(connectionKey())

    /**
     * 一轮完整同步：并发拉 models.json 与凭据状态，再用两者拉模型目录（共 3 次请求，
     * 目录不再重复拉配置与凭据）。
     *
     * 部分接口失败时保留上一次成功的结果（见 [mergeModelsManagementSnapshot]）；
     * 请求期间切换了网关则丢弃结果，绝不写进另一台机器的缓存。
     */
    suspend fun syncModelsManagementData(): Result<ModelsManagementSyncResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val key = connectionKey(baseUrl)
        // 合并阶段要解析网关下发的 models.json，非法字段类型会抛异常：
        // 这里必须兰住，否则会冒到 Compose 的 LaunchedEffect 里直接闪退
        try {
            val observedMutationSeq = modelsManagementCache.currentMutationSeq()
            val previous = modelsManagementCache.load(key)

            val (configResult, authResult) = coroutineScope {
                val configDeferred = async { getModelsConfigObject(baseUrl) }
                val authDeferred = async { getProviderAuthStates(baseUrl) }
                configDeferred.await() to authDeferred.await()
            }

            val rootObj = configResult.getOrNull()
                ?: return@withContext Result.failure(
                    configResult.exceptionOrNull() ?: IOException("读取 models.json 失败")
                )

            val freshAuth = authResult.getOrNull()
            // 目录要判断「provider 是否还活着」，凭据拉取失败时用上次的有效值，避免多请求一次
            val effectiveAuth = freshAuth ?: previous?.authStates ?: emptyMap()
            // 只读同步：既不回退硬编码目录，也不改动 App 全局默认模型偏好
            val catalogResult = getDynamicModelsInternal(
                baseUrl = baseUrl,
                preFetchedConfig = rootObj,
                preFetchedAuth = effectiveAuth,
                applyServerDefaults = false
            )

            // 请求期间用户切了网关：这批结果属于上一台机器
            if (connectionKey() != key) {
                return@withContext Result.failure(IOException("连接已变更，已丢弃过期同步结果"))
            }

            val merged = mergeModelsManagementSnapshot(
                connectionKey = key,
                previous = previous,
                rootConfig = rootObj,
                authStates = freshAuth,
                catalogModels = catalogResult.getOrNull()?.models
            )
            // 同步期间用户保存过 → 这次（更早的）结果不得盖掉刚保存的配置
            modelsManagementCache.storeSynced(merged.data, observedMutationSeq)
            Result.success(merged)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 保存类操作成功后把新配置写回本地快照（沿用已知的凭据状态与模型目录），
     * 让「保存 → 再次打开」直接命中新数据。
     *
     * 缓存只是加速层：写穿失败绝不能把「已经保存成功」报成保存失败，所以整段兰住。
     */
    private suspend fun writeThroughLocalConfig(baseUrl: String, config: JsonObject) {
        runCatching {
            // 保存请求属于发起时的网关；切换连接后也只能更新原网关的快照。
            val key = connectionKey(baseUrl)
            // 没有历史快照就不凭空造一个「凭据为空 + 目录未知」的假快照，
            // 否则冷启动会先把内置 Provider 显示成缺失（比没缓存更误导）
            val base = modelsManagementCache.load(key) ?: return@runCatching
            val merged = mergeModelsManagementSnapshot(
                connectionKey = key,
                previous = base,
                rootConfig = config,
                authStates = base.authStates,
                catalogModels = base.catalogModels
            )
            if (!modelsManagementCache.store(merged.data)) {
                Log.w("PiApiClient", "模型管理快照写穿失败（内存已更新，重启后会回到旧快照）")
            }
        }
    }

    /**
     * 直接获取 models.json 顶层 JsonObject（包含 providers 映射，1:1 对标 pi-web models-config）
     */
    suspend fun getModelsConfigObject(baseUrl: String = prefs.serverUrl): Result<JsonObject> = withContext(Dispatchers.IO) {
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
            val saved = putModelsConfigToServer(baseUrl, cloned)
            if (saved.isFailure) {
                return@withContext Result.failure(
                    IOException("保存 models.json 失败：${saved.exceptionOrNull()?.message ?: "未知错误"}")
                )
            }
            writeThroughLocalConfig(baseUrl, cloned)
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
                val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                if (json == null) {
                    return@withContext Result.success(
                        ModelTestResult(
                            ok = false,
                            status = resp.code,
                            error = "HTTP ${resp.code}: $bodyStr"
                        )
                    )
                }
                val ok = json.get("ok")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asBoolean }.getOrNull() } ?: false
                val latencyMs = json.get("latencyMs")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() }
                val status = json.get("status")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asInt }.getOrNull() } ?: resp.code
                val responseText = json.get("responseText")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                val error = json.get("error")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
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
                val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.success(null)
                val rec = json.get("recommendation")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@withContext Result.success(null)
                val preset = rec.get("preset")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: return@withContext Result.success(null)

                val name = preset.get("name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                val reasoning = preset.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asBoolean }.getOrNull() }
                val inputArr = preset.get("input")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { if (!it.isJsonNull && it.isJsonPrimitive) it.asString else null }
                val contextWindow = preset.get("contextWindow")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() }
                val maxTokens = preset.get("maxTokens")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asLong }.getOrNull() }

                val cost = preset.get("cost")?.takeIf { it.isJsonObject }?.asJsonObject
                val costInput = cost?.get("input")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() }
                val costOutput = cost?.get("output")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() }
                val costCacheRead = cost?.get("cacheRead")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() }
                val costCacheWrite = cost?.get("cacheWrite")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }
                    ?.let { runCatching { it.asDouble }.getOrNull() }

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
            val configObj = JsonParser.parseString(rawJson).asSafeObject()
                ?: return@withContext Result.failure(IllegalArgumentException("models.json 必须是 JSON 对象"))
            sanitizeAndHealModelsConfig(configObj)
            val saved = putModelsConfigToServer(baseUrl, configObj)
            if (saved.isFailure) {
                return@withContext Result.failure(
                    IOException("保存 models.json 失败：${saved.exceptionOrNull()?.message ?: "未知错误"}")
                )
            }
            writeThroughLocalConfig(baseUrl, configObj)
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
                ?: return@withContext Result.failure(IOException("读取网关 models.json 失败，删除未生效"))
            val rootConfig = fetched.config
            rootConfig.safeObject("providers")?.let { providersObj ->
                val pKey = providersObj.keySet().firstOrNull { it.equals(providerName.trim(), ignoreCase = true) }
                val pObj = pKey?.let { providersObj.safeObject(it) }
                if (pKey != null && pObj != null) {
                    pObj.safeArray("models")?.let { arr ->
                        val newArr = JsonArray()
                        for (item in arr) {
                            if (item.isJsonObject && item.asJsonObject.safeString("id").equals(modelId.trim(), ignoreCase = true)) {
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
                    pObj.safeObject("modelOverrides")?.let { ovObj ->
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
            val saved = putModelsConfigToServer(baseUrl, rootConfig)
            // 服务端没写成功就不能报成功：否则界面显示「已删除」，下次同步模型又回来了
            if (saved.isFailure) {
                return@withContext Result.failure(
                    IOException("网关上删除未生效，本地缓存未改动：${saved.exceptionOrNull()?.message ?: "未知错误"}")
                )
            }
            writeThroughLocalConfig(baseUrl, rootConfig)
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
                val patchOk = runCatching {
                    client.newCall(patchReq).execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (!patchOk) {
                    return@withContext Result.failure(IOException("同步保存到网关失败，已保留本地修改"))
                }
            }
            Result.success(true)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 更新 MCP Code Mode 与 Token 预算配置 (POST /api/mcp)
     */
    /**
     * 读工具全局设置 (GET /api/tools/settings)。
     * 代码模式（codemode / codemodeMode / codemodeInlineBudget）在这里，不在 `/api/mcp`。
     */
    suspend fun getToolSettings(): Result<ToolSettings> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/tools/settings").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("读取工具设置失败 (HTTP ${resp.code})"))
                }
                val json = runCatching {
                    JsonParser.parseString(resp.body?.string() ?: "{}").asSafeObject()
                }.getOrNull() ?: return@withContext Result.failure(IOException("读取工具设置失败：响应不是 JSON"))
                Result.success(parseToolSettings(json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读 pi 的排队送达模式（GET /api/queue-modes，pi-web 0.19.0 / pi 1.6.1 新增）。
     *
     * 旧版 pi-web 没有这条路由：404/405 按「不支持」处理，返回 null，
     * 不让设置页因为一个可选开关而报错。
     */
    suspend fun getQueueModes(): Result<QueueModes?> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val req = Request.Builder().url("$baseUrl/api/queue-modes").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 404 || resp.code == 405) return@withContext Result.success(null)
                if (!resp.isSuccessful) {
                    return@withContext Result.failure(IOException("读取排队模式失败 (HTTP ${resp.code})"))
                }
                val json = runCatching {
                    JsonParser.parseString(resp.body?.string() ?: "{}").asSafeObject()
                }.getOrNull() ?: return@withContext Result.failure(IOException("读取排队模式失败：响应不是 JSON"))
                Result.success(
                    QueueModes(
                        steeringMode = json.safeString("steeringMode") ?: QueueModes.MODE_ONE_AT_A_TIME,
                        followUpMode = json.safeString("followUpMode") ?: QueueModes.MODE_ONE_AT_A_TIME
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 写排队送达模式（PUT /api/queue-modes）。只传要改的那一项，与上游一致。
     *
     * 设置写的是 pi 的全局 settings.json：开了“一次全部送达”后，本轮会把排队的消息一次性送进模型。
     */
    suspend fun setQueueModes(
        steeringMode: String? = null,
        followUpMode: String? = null
    ): Result<QueueModes> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (steeringMode == null && followUpMode == null) {
            return@withContext Result.failure(IllegalArgumentException("没有要修改的排队模式"))
        }
        try {
            val payload = mutableMapOf<String, Any>()
            steeringMode?.let { payload["steeringMode"] = it }
            followUpMode?.let { payload["followUpMode"] = it }
            val body = gson.toJson(payload).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/queue-modes").put(body).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull()
                if (!resp.isSuccessful) {
                    val err = json?.safeString("error") ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException("修改排队模式失败：$err"))
                }
                if (json == null) return@withContext Result.failure(IOException("修改排队模式失败：响应不是 JSON"))
                Result.success(
                    QueueModes(
                        steeringMode = json.safeString("steeringMode") ?: QueueModes.MODE_ONE_AT_A_TIME,
                        followUpMode = json.safeString("followUpMode") ?: QueueModes.MODE_ONE_AT_A_TIME
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 改一项工具全局设置 (PUT /api/tools/settings)。
     * pi-web 严格要求「一次一项」，所以 [ToolSettingsChange] 只带一个字段。
     */
    suspend fun updateToolSettings(change: ToolSettingsChange): Result<ToolSettings> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val body = gson.toJson(change.toPayload()).toRequestBody(jsonMediaType)
            val req = Request.Builder().url("$baseUrl/api/tools/settings").put(body).build()
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    // pi-web 的拒绝带 {error, reason}，原样透出原因，不编造成功
                    val json = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull()
                    val msg = json?.safeString("error")
                    return@withContext Result.failure(
                        IOException(msg ?: "保存工具设置失败 (HTTP ${resp.code})")
                    )
                }
                val json = runCatching { JsonParser.parseString(raw).asSafeObject() }.getOrNull()
                    ?: return@withContext Result.failure(IOException("保存工具设置失败：响应不是 JSON"))
                Result.success(parseToolSettings(json))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseToolSettings(json: JsonObject): ToolSettings = ToolSettings.fromJson(json)

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
                val json = JsonParser.parseString(str).asSafeObject()
                    ?: return@withContext Result.failure(IOException("目录响应格式异常"))
                val currentPath = json.safeString("current")
                    ?: json.safeString("path")
                    ?: json.safeString("dir")
                    ?: path
                val parentPath = json.safeString("parentPath")
                    ?: json.safeString("parent")

                val dirs = mutableListOf<RemoteDirEntry>()
                val arrKeys = listOf("directories", "dirs", "drives", "entries", "children", "subdirs", "items")
                for (k in arrKeys) {
                    val arr = json.safeArray(k) ?: continue
                    for (el in arr) {
                        if (el.isJsonObject) {
                            val o = el.asJsonObject
                            val isDir = o.safeBoolean("isDirectory") ?: true
                            if (!isDir) continue
                            val dName = o.safeString("name") ?: ""
                            val dPath = o.safeString("path")
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
                Result.success(RemoteBrowseData(currentPath = currentPath, parentPath = parentPath, directories = dirs))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 校验远程目录并加入服务端可读写白名单 (POST /api/cwd/validate)
     */
    suspend fun validateRemoteDirectory(dirPath: String): Result<String> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val clean = dirPath.trim()
        if (clean.isBlank()) return@withContext Result.failure(Exception("路径不能为空"))
        try {
            val payload = mapOf("cwd" to clean)
            val req = Request.Builder()
                .url("$baseUrl/api/cwd/validate")
                .post(gson.toJson(payload).toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val err = runCatching { JsonParser.parseString(body).asSafeObject()?.safeString("error") }.getOrNull()
                        ?: "HTTP ${resp.code}"
                    return@withContext Result.failure(IOException(err))
                }
                val obj = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                val resolved = obj?.safeString("cwd")
                    ?: obj?.safeString("path")
                    ?: clean
                Result.success(resolved)
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
                        val obj = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                        val resolved = obj?.safeString("cwd")
                            ?: obj?.safeString("path")
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
                val json = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                if (resp.isSuccessful) {
                    val sid = json?.safeString("sessionId")
                        ?: json?.safeString("id")
                    if (!sid.isNullOrBlank()) {
                        return@withContext Result.success(sid)
                    }
                }
                val err = json?.safeString("error")
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
            val encodedSessionId = FileListParsing.encodePathSegment(sessionId)
            val req = Request.Builder().url("$baseUrl/api/agent/$encodedSessionId").post(body).build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    // 这三种拒绝都不是故障（运行中 / 子代理固定 / 只读拦截），翻译成人话，
                    // 别把 500 原文直接糊到用户脸上。
                    val serverMsg = runCatching {
                        JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error")
                    }.getOrNull() ?: ""
                    val friendly = ToolErrorMessages.explain(serverMsg)
                        ?: serverMsg.takeIf { it.isNotBlank() }
                        ?: "权限切换失败 (HTTP ${resp.code})"
                    Result.failure(IOException(friendly))
                } else {
                    val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    val dataObj = json?.safeObject("data")
                    val newSid = dataObj?.safeString("sessionId") ?: sessionId
                    val recreated = dataObj?.safeBoolean("recreated") ?: false
                    Result.success(SetToolsResult(sessionId = newSid, recreated = recreated))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 读本会话的工具定义 (POST /api/agent/{id} {"type":"get_tools"})。
     * 空闲会话也能答（服务端会临时起一个 wrapper），但不该为此新建会话，
     * 所以调用方要保证 sessionId 非空。
     */
    suspend fun getSessionTools(sessionId: String): Result<List<ToolDefinition>> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("没有活跃会话"))
        try {
            val body = gson.toJson(mapOf("type" to "get_tools")).toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url("$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val msg = runCatching {
                        JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error")
                    }.getOrNull()
                    return@withContext Result.failure(
                        IOException(msg?.takeIf { it.isNotBlank() } ?: "读取工具定义失败 (HTTP ${resp.code})")
                    )
                }
                val tools = ToolDefinitionsParsing.parse(bodyStr)
                    ?: return@withContext Result.failure(IOException("读取工具定义失败：响应格式不符"))
                Result.success(tools)
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
                    val root = runCatching {
                        JsonParser.parseString(bodyStr).asSafeObject()
                    }.getOrNull() ?: JsonObject()
                    val resultObj = root.safeObject("result") ?: root
                    val toolNames = mutableListOf<String>()
                    resultObj.safeArray("tools")?.let { arr ->
                        for (item in arr) {
                            if (item.isJsonObject) {
                                val tName = item.asJsonObject.safeString("name")
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

    suspend fun forkSession(sessionId: String, targetCwd: String? = null): Result<ForkSessionResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            // pi-web 的 POST /api/sessions/[id]/fork 在解析 params 之前就校验
            // hasJsonContentType()，不满足直接回 415（body 本身并不读）。
            // 之前这里发的是空 body + 无 Content-Type，于是稳定吃 415。
            val body = forkRequestBody(targetCwd).toRequestBody(jsonMediaType)
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${FileListParsing.encodePathSegment(sessionId)}/fork")
                .post(body)
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    Result.failure(IOException("Server HTTP ${resp.code}: $bodyStr"))
                } else {
                    // 响应是 { sessionId, session }：path / name 在 session 里（见
                    // ForkSessionResponseParsing 的注释），顶层只剩兜底。
                    val parsed = ForkSessionResponseParsing.parse(bodyStr)
                        ?: return@withContext Result.failure(IOException("未返回有效的分叉会话ID"))
                    Result.success(parsed)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 「从此处编辑」的底层动作：把会话树切到指定条目（1:1 对标 pi-web 的
     * `POST /api/agent/{id}` `{type:"navigate_tree", targetId}`）。
     *
     * 与 fork 不同，它**不新建会话文件**：后续条目仍留在同一个 .jsonl 里，
     * 但不再是活跃分支（变成被放弃的分支），所以刷新快照后就只看得到编辑点之前的内容。
     * 服务端在 shell 命令执行中会直接报错，上层策略是「跑着就不给点」。
     */
    suspend fun navigateTree(sessionId: String, targetEntryId: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
            if (targetEntryId.isBlank()) return@withContext Result.failure(Exception("缺少条目 ID"))
            try {
                val payload = JsonObject().apply {
                    addProperty("type", "navigate_tree")
                    addProperty("targetId", targetEntryId)
                }
                val req = Request.Builder()
                    .url("$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}")
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    if (!resp.isSuccessful || json?.get("error")?.takeIf { !it.isJsonNull } != null) {
                        val err = json?.safeString("error")
                            ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IOException(err))
                    }
                    val data = json?.safeObject("data")
                    val cancelled = data?.safeBoolean("cancelled") ?: false
                    if (cancelled) Result.failure(IOException("服务端取消了本次分支切换"))
                    else Result.success(true)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * 消息级「新会话」分叉：1:1 对标 pi-web 的 `handleFork` → `POST /api/agent/{id}`
     * `{type:"fork", entryId}`。它在磁盘上**新建一个 .jsonl**，只拷贝该提问之前的历史，
     * 源会话完全不受影响（可以边跑边分叉），与新会话各自独立演进。
     *
     * 注意：这与 `/api/sessions/{id}/fork`（侧边栏的 Clone，整条当前分支拷贝）不同。
     */
    suspend fun forkAtEntry(sessionId: String, targetEntryId: String): Result<String> =
        withContext(Dispatchers.IO) {
            val baseUrl = prefs.serverUrl
            if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
            if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
            if (targetEntryId.isBlank()) return@withContext Result.failure(Exception("缺少条目 ID"))
            try {
                val payload = JsonObject().apply {
                    addProperty("type", "fork")
                    addProperty("entryId", targetEntryId)
                }
                val req = Request.Builder()
                    .url("$baseUrl/api/agent/${FileListParsing.encodePathSegment(sessionId)}")
                    .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    if (!resp.isSuccessful || json?.get("error")?.takeIf { !it.isJsonNull } != null) {
                        val err = json?.safeString("error")
                            ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(IOException(err))
                    }
                    val data = json?.safeObject("data")
                    val cancelled = data?.safeBoolean("cancelled") ?: false
                    val newSid = data?.safeString("newSessionId")
                    if (cancelled) Result.failure(IOException("服务端取消了本次分叉"))
                    else if (newSid.isNullOrBlank()) Result.failure(IOException("未返回有效的分叉会话ID"))
                    else Result.success(newSid)
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
                .url("${baseUrl}/api/sessions/${FileListParsing.encodePathSegment(sessionId)}/auto-name")
                .post("{}".toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: "{}"
                val json = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                if (resp.isSuccessful) {
                    val title = json?.safeString("title")
                    if (!title.isNullOrBlank()) {
                        return@withContext Result.success(title)
                    }
                }
                val errorMsg = json?.safeString("error")
                    ?: "大模型生成标题响应异常 (${resp.code})"
                Result.failure(IOException(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ==========================================
    // 3.0.1 输入框提示词增强（伴侣网关自有能力，不是 pi-web 协议）
    // ==========================================

    /**
     * 读取网关能力探测结果。旧网关没有 `capabilities` 字段，返回 null 表示「不支持」。
     * 这里复用 `/api/ping`，不额外制造一个探测请求。
     */
    suspend fun getPromptEnhancementCapability(): Result<PromptEnhancementCapability?> = withContext(Dispatchers.IO) {
        // 三种结果必须分开，不能都压成 null：
        //   success(capability) —— 网关报了这个能力（可能 available=false）
        //   success(null)       —— 网关正常应答但没有 capabilities 字段（旧网关 / 直连 pi-web）→ 入口隐藏
        //   failure             —— 这次没连上（网络抖动）→ 调用方应保留上一次结果，别误关入口
        val pingResult = ping()
        val body = pingResult.getOrNull()
            ?: return@withContext Result.failure(
                pingResult.exceptionOrNull() ?: IOException("能力探测失败")
            )
        Result.success(com.pi.app.prompt.PromptEnhancementCapabilityParser.parse(body))
    }

    /**
     * 发起一次增强。
     *
     * 用 `enqueue` + `suspendCancellableCoroutine` 而不是 `execute()`：用户在增强期间
     * 继续输入/切会话时，[kotlinx.coroutines.Job] 取消必须真正 cancel 掉这条连接，
     * 否则服务端会一直占着生成名额。
     */
    suspend fun enhancePrompt(
        requestId: String,
        sessionId: String,
        draftRevision: Long,
        branchRevision: Long,
        provider: String,
        modelId: String,
        draft: String,
        context: List<PromptEnhancementContextMessage>
    ): Result<PromptEnhancementResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        val payload = JsonObject().apply {
            addProperty("requestId", requestId)
            addProperty("sessionId", sessionId)
            addProperty("draftRevision", draftRevision)
            addProperty("branchRevision", branchRevision)
            addProperty("provider", provider)
            addProperty("modelId", modelId)
            addProperty("draft", draft)
            add("context", JsonArray().apply {
                context.forEach { item ->
                    add(JsonObject().apply {
                        addProperty("role", item.role)
                        addProperty("text", item.text)
                    })
                }
            })
        }
        val httpRequest = Request.Builder()
            .url("$baseUrl/api/mobile/prompt-enhance")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        suspendCancellableCoroutine { cont ->
            val call = enhanceClient.newCall(httpRequest)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isActive) return
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { resp ->
                        val body = resp.body?.string() ?: ""
                        val json = runCatching { JsonParser.parseString(body).asSafeObject() }.getOrNull()
                        if (resp.isSuccessful && json != null) {
                            val text = json.safeString("enhancedText")
                            if (!text.isNullOrBlank()) {
                                val modelObj = json.safeObject("model")
                                Result.success(
                                    PromptEnhancementResult(
                                        requestId = json.safeString("requestId") ?: requestId,
                                        draftRevision = json.safeLong("draftRevision") ?: draftRevision,
                                        branchRevision = json.safeLong("branchRevision") ?: branchRevision,
                                        enhancedText = text,
                                        changed = json.safeBoolean("changed") ?: true,
                                        provider = modelObj?.safeString("provider") ?: provider,
                                        modelId = modelObj?.safeString("modelId") ?: modelId,
                                        contextUsed = json.safeBoolean("contextUsed") ?: false
                                    )
                                )
                            } else {
                                Result.failure(PromptEnhancementException("INVALID_OUTPUT", "增强结果为空"))
                            }
                        } else {
                            // 499（网关侧判定「客户端已断开」）通常是因为本地 callTimeout 先到、
                            // 或用户切走而连接被关掉；对用户而言就是「已取消」，不该出现裸状态码。
                            val statusCode = resp.code
                            val code = json?.safeString("code")
                                ?: if (statusCode == 499) "CANCELLED" else "HTTP_$statusCode"
                            val message = json?.safeString("message")
                                ?: if (statusCode == 499) "增强已取消" else "增强失败 (HTTP $statusCode)"
                            Result.failure(PromptEnhancementException(code, message))
                        }
                    }
                    if (cont.isActive) cont.resume(result)
                }
            })
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
                        val json = JsonParser.parseString(body).asSafeObject() ?: JsonObject()
                        val list = mutableListOf<DiscoveredModelItem>()
                        json.safeArray("models")?.let { arr ->
                            for (item in arr) {
                                val obj = item.asSafeObject() ?: continue
                                val id = obj.safeString("id") ?: ""
                                val name = obj.safeString("name") ?: id
                                if (id.isNotBlank()) {
                                    list.add(DiscoveredModelItem(id = id, name = name))
                                }
                            }
                        }
                        val deduped = list.filter { it.id.isNotBlank() }.distinctBy { it.id }
                        if (deduped.isNotEmpty()) {
                            val ep = json.safeString("endpoint") ?: baseUrl
                            return@withContext Result.success(DiscoverModelsResult(models = deduped, endpoint = ep))
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
                // 仅复用全局 connectionPool 与 dispatcher，不继承 AuthInterceptor（避免向第三方上游泄漏网关 Token）
                val directClient = OkHttpClient.Builder()
                    .connectionPool(client.connectionPool)
                    .dispatcher(client.dispatcher)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build()
                directClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = JsonParser.parseString(body).asSafeObject() ?: JsonObject()
                        val list = mutableListOf<DiscoveredModelItem>()
                        json.safeArray("data")?.let { arr ->
                            for (item in arr) {
                                val id = item.asSafeObject()?.safeString("id") ?: ""
                                if (id.isNotBlank()) {
                                    list.add(DiscoveredModelItem(id = id, name = id))
                                }
                            }
                        }
                        val deduped = list.filter { it.id.isNotBlank() }.distinctBy { it.id }
                        if (deduped.isNotEmpty()) {
                            return@withContext Result.success(DiscoverModelsResult(models = deduped, endpoint = targetUrl))
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
                    val msg = runCatching { JsonParser.parseString(errBody).asSafeObject()?.safeString("error") }.getOrNull() ?: "HTTP ${resp.code}"
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
                    val msg = runCatching { JsonParser.parseString(errBody).asSafeObject()?.safeString("error") }.getOrNull() ?: "HTTP ${resp.code}"
                    Result.failure(Exception(msg))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 导出会话为自包含独立 HTML 文件（GET /api/sessions/[id]/export）。
     */
    suspend fun downloadSessionExportHtml(
        sessionId: String,
        targetFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (sessionId.isBlank()) return@withContext Result.failure(Exception("会话ID不能为空"))
        try {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${FileListParsing.encodePathSegment(sessionId)}/export")
                .get()
                .build()
            fileClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val rawBody = resp.body?.string()?.take(300) ?: ""
                    val err = runCatching { JsonParser.parseString(rawBody).asSafeObject()?.safeString("error") }.getOrNull()
                        ?: rawBody.ifBlank { "HTTP ${resp.code}" }
                    return@withContext Result.failure(IOException("导出失败 (${resp.code})：$err"))
                }
                val body = resp.body ?: return@withContext Result.failure(IOException("导出响应为空"))
                targetFile.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
            Result.success(targetFile)
        } catch (e: Exception) {
            runCatching { targetFile.delete() }
            Result.failure(e)
        }
    }

    /**
     * 监听远端文件变更事件（GET /api/files/[...path]?type=watch 的 SSE 长连接）。
     */
    fun watchFileChanges(absPath: String, sessionId: String? = null): Flow<Unit> = callbackFlow {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank() || absPath.isBlank()) {
            close()
            return@callbackFlow
        }
        val url = fileApiUrl(absPath, "watch", sessionId)
        if (url.isBlank()) {
            close()
            return@callbackFlow
        }
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .header("Accept-Encoding", "identity")
            .build()

        val call = sseStreamClient.newCall(req)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!call.isCanceled()) {
                    close()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close(IOException("Watch file failed (${resp.code})"))
                        return
                    }
                    val body = resp.body
                    if (body == null) {
                        close(IOException("Response body is null"))
                        return
                    }
                    try {
                        body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val currentLine = line ?: break
                                if (currentLine.startsWith("event: change") || currentLine.startsWith("data: {\"mtime\"")) {
                                    trySend(Unit)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // 正常流断开
                    } finally {
                        close()
                    }
                }
            }
        })

        awaitClose {
            call.cancel()
        }
    }

    /**
     * 读取指定工作目录的 Git Worktree 检出集合（GET /api/worktrees?cwd=...）。
     */
    suspend fun getWorktrees(cwd: String): Result<WorktreesResult> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val encodedCwd = URLEncoder.encode(cwd, "UTF-8")
            val req = Request.Builder()
                .url("$baseUrl/api/worktrees?cwd=$encodedCwd")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val err = runCatching { JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error") }.getOrNull()
                        ?: "HTTP ${resp.code}"
                    Result.failure(IOException(err))
                } else {
                    val json = JsonParser.parseString(bodyStr).asSafeObject()
                        ?: return@withContext Result.failure(IOException("worktrees 响应格式异常"))
                    Result.success(WorktreeResponseParsing.parse(json))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 为新分支创建 Worktree 检出（POST /api/worktrees）。
     */
    suspend fun createWorktree(cwd: String, branch: String): Result<WorktreeItem> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        if (cwd.isBlank() || branch.isBlank()) return@withContext Result.failure(IllegalArgumentException("路径或分支名不能为空"))
        try {
            val payload = JsonObject().apply {
                addProperty("cwd", cwd)
                addProperty("branch", branch.trim())
            }
            val req = Request.Builder()
                .url("$baseUrl/api/worktrees")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val err = runCatching { JsonParser.parseString(bodyStr).asSafeObject()?.safeString("error") }.getOrNull()
                        ?: "HTTP ${resp.code}"
                    Result.failure(IOException(err))
                } else {
                    val json = JsonParser.parseString(bodyStr).asSafeObject()
                        ?: return@withContext Result.failure(IOException("worktree 响应格式异常"))
                    val path = json.safeString("path") ?: ""
                    val b = json.safeString("branch") ?: branch
                    Result.success(WorktreeItem(path = path, branch = b, isCurrent = true))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 移除 Worktree 检出（DELETE /api/worktrees，保留 Git 分支，安全清理本地工作树目录）。
     */
    suspend fun deleteWorktree(cwd: String, path: String, force: Boolean = false): Result<Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = prefs.serverUrl
        if (baseUrl.isBlank()) return@withContext Result.failure(Exception("未配置网关地址"))
        try {
            val payload = JsonObject().apply {
                addProperty("cwd", cwd)
                addProperty("path", path)
                if (force) addProperty("force", true)
            }
            val req = Request.Builder()
                .url("$baseUrl/api/worktrees")
                .delete(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) {
                    val json = runCatching { JsonParser.parseString(bodyStr).asSafeObject() }.getOrNull()
                    val dirty = json?.safeBoolean("dirty") ?: false
                    val err = json?.safeString("error") ?: "HTTP ${resp.code}"
                    if (dirty) {
                        Result.failure(IOException("WORKTREE_DIRTY:$err"))
                    } else {
                        Result.failure(IOException(err))
                    }
                } else {
                    Result.success(true)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
