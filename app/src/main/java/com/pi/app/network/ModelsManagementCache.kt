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
            rootConfig = rootConfig,
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

    suspend fun store(data: ModelsManagementData) = withContext(Dispatchers.IO) {
        lock.withLock {
            memory = data
            runCatching { writeToDisk(data) }
            Unit
        }
    }

    fun invalidateMemory() {
        memory = null
    }

    private fun cacheFile(connectionKey: String): File =
        File(dir, "$FILE_PREFIX${Integer.toHexString(connectionKey.hashCode())}.json")

    private fun readFromDisk(connectionKey: String): ModelsManagementData? {
        val file = cacheFile(connectionKey)
        if (!file.exists() || file.length() <= 0L) return null
        val root = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull() ?: return null
        if (root.get("version")?.takeIf { !it.isJsonNull }?.asInt != CACHE_VERSION) return null
        if (root.get("connectionKey")?.takeIf { !it.isJsonNull }?.asString != connectionKey) return null
        val config = root.get("modelsConfig")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val auth = root.get("auth")?.takeIf { it.isJsonObject }
            ?.let { ProviderAuthStateSerializer.parse(it.toString()) }
            ?: emptyMap()
        val models = root.get("catalogModels")?.takeIf { it.isJsonArray }?.let { arr ->
            runCatching { gson.fromJson<List<ModelInfo>>(arr, modelListType) }.getOrNull()
        }
        val syncedAt = root.get("syncedAt")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        return ModelsManagementData(
            connectionKey = connectionKey,
            rootConfig = config,
            authStates = auth,
            catalogModels = models,
            providers = ModelConfigConverter.combineProviders(config, auth, models),
            syncedAt = syncedAt
        )
    }

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

    /** 只保留最近几个连接的缓存，防止长期换网关后无限增长。 */
    private fun prune() {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(FILE_PREFIX) && it.name.endsWith(".json") }
            ?: return
        if (files.size <= MAX_FILES) return
        files.sortedByDescending { it.lastModified() }.drop(MAX_FILES).forEach { it.delete() }
    }

    private companion object {
        const val CACHE_VERSION = 2
        const val FILE_PREFIX = "models_management_"
        const val MAX_FILES = 4
    }
}
