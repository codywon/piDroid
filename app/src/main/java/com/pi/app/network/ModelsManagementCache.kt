package com.pi.app.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pi.app.data.model.ModelConfigConverter
import com.pi.app.data.model.ModelInfo
import com.pi.app.data.model.ProviderAuthState
import com.pi.app.data.model.ProviderAuthStateSerializer
import com.pi.app.data.model.ProviderConfigItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** URL 路径区分大小写；只移除首尾空白与末尾斜杠，避免不同网关共享配置缓存。 */
internal fun modelsManagementConnectionKey(baseUrl: String): String =
    baseUrl.trim().trimEnd('/')

/**
 * 模型管理页的完整展示快照。
 *
 * 之前只缓存了 models.json 与凭据状态，模型目录每次都要等网络，冷启动时内置 Provider
 * 会先显示 0 个模型。这里把目录也一起存下来，离线也能显示完整列表。
 *
 * @param connectionKey 连接身份（见 [PiApiClient.connectionKey]）。缓存按它隔离，
 *   切换网关后不会读到上一台机器的配置。
 * @param catalogModels 网关 `/api/models` 的模型列表；`null` 表示「从未成功获取过目录」，
 *   与「成功获取但为空」严格区分，绝不凭空伪造模型。
 *
 * 注意：`rootConfig` 是 [JsonObject]（可变、且 equals 按引用比较），所以本类型的
 * `equals` / `hashCode` 只适合诊断，不要用于业务判等（变化判定请看 `providers`）。
 * 构造快照时会 `deepCopy`，避免调用方之后改动同一个 JsonObject 而静默改掉快照。
 */
data class ModelsManagementData(
    val connectionKey: String,
    val rootConfig: JsonObject,
    val authStates: Map<String, ProviderAuthState>,
    val catalogModels: List<ModelInfo>?,
    val providers: List<ProviderConfigItem>,
    val syncedAt: Long = System.currentTimeMillis()
)

/**
 * 一轮同步的结果。[degraded] 为 true 表示有接口失败、某些部分沿用了上次结果，
 * UI 必须如实告知，而不是当成「最新且完整」。
 */
data class ModelsManagementSyncResult(
    val data: ModelsManagementData,
    val authFailed: Boolean = false,
    val catalogFailed: Boolean = false
) {
    val degraded: Boolean
        get() = authFailed || catalogFailed
}

/**
 * 把「本轮拿到的数据」与「上次成功的快照」合并成新的展示快照。
 *
 * 关键规则：`authStates` / `catalogModels` 传 `null` 表示**这次拉取失败**，
 * 此时沿用 [previous] 的结果；只有真的拿到了（哪怕是空集合）才覆盖。
 * 否则一次凭据接口超时就会把已接入的内置 Provider 连同模型一起抹掉。
 */
internal fun mergeModelsManagementSnapshot(
    connectionKey: String,
    previous: ModelsManagementData?,
    rootConfig: JsonObject,
    authStates: Map<String, ProviderAuthState>?,
    catalogModels: List<ModelInfo>?,
    syncedAt: Long = System.currentTimeMillis()
): ModelsManagementSyncResult {
    val effectiveAuth = authStates ?: previous?.authStates ?: emptyMap()
    val effectiveCatalog = catalogModels ?: previous?.catalogModels
    val providers = ModelConfigConverter.combineProviders(rootConfig, effectiveAuth, effectiveCatalog)
    return ModelsManagementSyncResult(
        data = ModelsManagementData(
            connectionKey = connectionKey,
            // 防御性拷贝：快照不与被调用方共享同一个可变 JsonObject
            rootConfig = rootConfig.deepCopy(),
            authStates = effectiveAuth,
            catalogModels = effectiveCatalog,
            providers = providers,
            syncedAt = syncedAt
        ),
        authFailed = authStates == null,
        catalogFailed = catalogModels == null
    )
}

/**
 * 模型管理快照的本地缓存：内存 + 磁盘双层，按连接身份隔离。
 *
 * - 内存快照 [peek] 是纯内存读，可在 Compose 初始化里直接调用；
 * - [load] / [store] 会碰磁盘，内部已切到 [Dispatchers.IO] 并用互斥锁串行化，
 *   避免多个同步/保存同时写同一个临时文件而互相覆盖。
 */
class ModelsManagementCacheStore(
    private val dir: File,
    private val gson: Gson = Gson()
) {
    private val lock = Mutex()
    private val modelListType = object : TypeToken<List<ModelInfo>>() {}.type

    /** 本地变更序号（受 [lock] 保护）：用于让更早发起的网络同步落盘失效。 */
    private var localMutationSeq = 0L

    @Volatile
    private var memory: ModelsManagementData? = null

    /** 只读内存快照：连接身份不匹配就当作没有缓存。 */
    fun peek(connectionKey: String): ModelsManagementData? =
        memory?.takeIf { it.connectionKey == connectionKey }

    /** 内存未命中时读磁盘；磁盘内容损坏/版本不符/连接身份不符一律当没有缓存。 */
    suspend fun load(connectionKey: String): ModelsManagementData? = withContext(Dispatchers.IO) {
        lock.withLock {
            peek(connectionKey)?.let { return@withContext it }
            val fromDisk = readFromDisk(connectionKey) ?: return@withContext null
            memory = fromDisk
            fromDisk
        }
    }

    /** 当前本地变更序号。同步开始前记下，落盘时用它判定期间有没有本地保存。 */
    suspend fun currentMutationSeq(): Long = withContext(Dispatchers.IO) {
        lock.withLock { localMutationSeq }
    }

    /**
     * 本地保存写穿：先递增变更序号再落盘，代表它比任何已发起的同步都新。
     *
     * @return 磁盘是否真的写成功。内存快照无论如何已更新，但磁盘写失败（满盘 / 无权限）
     *   会让重启后回到旧数据，调用方不能当成「一切正常」静默吞掉。
     */
    suspend fun store(data: ModelsManagementData): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            localMutationSeq++
            memory = data
            runCatching { writeToDisk(data) }.isSuccess
        }
    }

    /**
     * 网络同步落盘：若同步期间发生过本地保存（序号已变），直接丢弃这次结果。
     *
     * 否则「同步发起 → 用户保存 → 旧同步返回」会把用户刚保存的配置从缓存里抹掉，
     * 下次冷启动就回到保存前的状态。UI 侧另有 saveEpoch 守卫，缓存侧必须有这一道。
     *
     * @return 是否真的落盘。`false` 有两种含义：被更新的本地保存超越，或磁盘写入失败。
     */
    suspend fun storeSynced(data: ModelsManagementData, observedMutationSeq: Long): Boolean =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (localMutationSeq != observedMutationSeq) return@withLock false
                memory = data
                runCatching { writeToDisk(data) }.isSuccess
            }
        }

    /**
     * 缓存文件名：用 SHA-256 前 8 字节（16 个十六进制字符）。
     *
     * 之前用 `String.hashCode()`（32 位），不同网关撞到同一文件名的概率实际不可忽略，
     * 一旦撞上就会静默地把另一台机器的缓存挤掉。
     */
    private fun cacheFile(connectionKey: String): File =
        File(dir, "$FILE_PREFIX${stableHash(connectionKey)}.json")

    private fun stableHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /**
     * 读磁盘。整段用 [runCatching] 兜底：缓存只是加速层，内容再离谱
     * （磁盘损坏、用户在 raw JSON 里把 baseUrl 写成对象/把数字字段写成字符串）
     * 也只能退化成「没命中」，绝不允许把异常抛给调用方/UI。
     */
    private fun readFromDisk(connectionKey: String): ModelsManagementData? = runCatching {
        val file = cacheFile(connectionKey)
        if (!file.exists() || file.length() <= 0L) return@runCatching null
        val root = JsonParser.parseString(file.readText()).asJsonObject
        if (root.get("version")?.takeIf { !it.isJsonNull }?.asInt != CACHE_VERSION) return@runCatching null
        if (root.get("connectionKey")?.takeIf { !it.isJsonNull }?.asString != connectionKey) return@runCatching null
        val config = root.get("modelsConfig")?.takeIf { it.isJsonObject }?.asJsonObject ?: return@runCatching null
        val auth = root.get("auth")?.takeIf { it.isJsonObject }
            ?.let { ProviderAuthStateSerializer.parse(it.toString()) }
            ?: emptyMap()
        val models = root.get("catalogModels")?.takeIf { it.isJsonArray }?.let { arr ->
            gson.fromJson<List<ModelInfo>>(arr, modelListType)
        }
        val syncedAt = root.get("syncedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        ModelsManagementData(
            connectionKey = connectionKey,
            rootConfig = config,
            authStates = auth,
            catalogModels = models,
            providers = ModelConfigConverter.combineProviders(config, auth, models),
            syncedAt = syncedAt
        )
    }.getOrNull()

    private fun writeToDisk(data: ModelsManagementData) {
        if (!dir.exists()) dir.mkdirs()
        val payload = JsonObject().apply {
            addProperty("version", CACHE_VERSION)
            addProperty("connectionKey", data.connectionKey)
            addProperty("syncedAt", data.syncedAt)
            add("modelsConfig", data.rootConfig)
            add("auth", JsonParser.parseString(ProviderAuthStateSerializer.serialize(data.authStates)))
            data.catalogModels?.let { add("catalogModels", gson.toJsonTree(it, modelListType)) }
        }
        val target = cacheFile(data.connectionKey)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(payload.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(payload.toString())
            tmp.delete()
        }
        prune()
    }

    /**
     * 只保留最近使用的几个连接缓存，防止长期换网关后无限增长；清理中断留下的临时文件。
     *
     * 淘汰依据是 `lastModified`（≈ 最近一次写入/使用）。保留 8 份而不是 4 份：
     * 在几台机器之间轮换的用户，否则一回到旧网关就又要冷启动等一次网络。
     */
    private fun prune() {
        val allFiles = dir.listFiles() ?: return
        val now = System.currentTimeMillis()
        // 清理超过 1 小时的孤立 .tmp 临时文件（防进程意外中断残留）
        allFiles.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".tmp") }
            .forEach { if (now - it.lastModified() > 3600_000L) it.delete() }

        val jsonFiles = allFiles.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".json") }
        if (jsonFiles.size <= MAX_FILES) return
        jsonFiles.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    private companion object {
        const val CACHE_VERSION = 2
        const val FILE_PREFIX = "models_management_"
        const val MAX_FILES = 8
    }
}
