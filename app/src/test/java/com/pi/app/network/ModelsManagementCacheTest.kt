package com.pi.app.network

import com.google.gson.JsonParser
import com.pi.app.data.model.ModelInfo
import com.pi.app.data.model.ProviderAuthState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型管理快照缓存与同步合并策略的纯 JVM 测试。
 *
 * 覆盖审查里点名的几个关键场景：
 * - 切换网关后不得读到上一台机器的缓存；
 * - 冷启动能从磁盘恢复完整列表（含内置 Provider 的模型目录）；
 * - 凭据/目录接口失败时沿用上次结果，不得把已接入的 Provider 抹掉；
 * - 缓存文件损坏不影响启动。
 */
class ModelsManagementCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val keyA = "http://192.168.1.100:4100"
    private val keyB = "http://10.0.0.9:4100"

    private fun config(provider: String = "codywon"): com.google.gson.JsonObject = JsonParser.parseString(
        """
        {
          "providers": {
            "$provider": {
              "baseUrl": "https://api.example.com/v1",
              "api": "openai-completions",
              "models": [ { "id": "custom-1", "name": "Custom 1" } ]
            }
          }
        }
        """.trimIndent()
    ).asJsonObject

    private fun authStates(vararg configured: String): Map<String, ProviderAuthState> =
        configured.associate { id ->
            id to ProviderAuthState(
                providerId = id,
                displayName = id.replaceFirstChar { it.uppercase() },
                configured = true,
                source = "stored",
                modelCount = 1
            )
        }

    private fun catalogModels() = listOf(
        ModelInfo(
            id = "deepseek-chat",
            name = "DeepSeek V3",
            provider = "deepseek",
            contextWindow = 64000L,
            maxTokens = 4096L
        )
    )

    private fun snapshot(connectionKey: String = keyA) = mergeModelsManagementSnapshot(
        connectionKey = connectionKey,
        previous = null,
        rootConfig = config(),
        authStates = authStates("deepseek"),
        catalogModels = catalogModels(),
        syncedAt = 1234L
    ).data

    @Test
    fun `stored snapshot is readable from memory`() {
        val store = ModelsManagementCacheStore(File(tmp.root, "cache"))
        runBlocking { store.store(snapshot()) }

        val peeked = store.peek(keyA)
        assertNotNull(peeked)
        assertEquals(2, peeked!!.providers.size)
        assertTrue(peeked.providers.any { it.name == "deepseek" && it.models.isNotEmpty() })
    }

    @Test
    fun `different connection key never sees another gateway cache`() {
        val store = ModelsManagementCacheStore(File(tmp.root, "cache"))
        runBlocking { store.store(snapshot(keyA)) }

        assertNull(store.peek(keyB))
        assertNull(runBlocking { store.load(keyB) })
        assertNotNull(runBlocking { store.load(keyA) })
    }

    @Test
    fun `cold start restores full snapshot from disk including catalog models`() {
        val dir = File(tmp.root, "cache")
        runBlocking { ModelsManagementCacheStore(dir).store(snapshot()) }

        // 新实例 = 进程被杀后重新启动，内存里什么都没有
        val coldStore = ModelsManagementCacheStore(dir)
        assertNull(coldStore.peek(keyA))

        val loaded = runBlocking { coldStore.load(keyA) }
        assertNotNull(loaded)
        assertEquals(1234L, loaded!!.syncedAt)
        assertEquals(1, loaded.authStates.size)

        // 关键：离线也必须能看到内置 Provider 的模型，而不是 0 个
        val deepseek = loaded.providers.firstOrNull { it.name == "deepseek" }
        assertNotNull(deepseek)
        assertEquals(1, deepseek!!.models.size)
        assertEquals("deepseek-chat", deepseek.models[0].id)
        assertEquals(64000L, deepseek.models[0].contextWindow)
        // 自定义 Provider 也照旧还原
        assertTrue(loaded.providers.any { it.name == "codywon" && it.models.size == 1 })
    }

    @Test
    fun `corrupt cache file is ignored instead of crashing`() {
        val dir = File(tmp.root, "cache")
        val store = ModelsManagementCacheStore(dir)
        runBlocking { store.store(snapshot()) }
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { it.writeText("{not json") }

        assertNull(runBlocking { ModelsManagementCacheStore(dir).load(keyA) })
    }

    @Test
    fun `previous auth is kept when auth fetch failed`() {
        val previous = snapshot()
        val merged = mergeModelsManagementSnapshot(
            connectionKey = keyA,
            previous = previous,
            rootConfig = config(),
            authStates = null,
            catalogModels = catalogModels()
        )

        assertTrue(merged.authFailed)
        assertTrue(merged.degraded)
        assertEquals(previous.authStates, merged.data.authStates)
        assertTrue("凭据接口超时不得把已接入的 Provider 抹掉", merged.data.providers.any { it.name == "deepseek" })
    }

    @Test
    fun `previous catalog is kept when models fetch failed`() {
        val previous = snapshot()
        val merged = mergeModelsManagementSnapshot(
            connectionKey = keyA,
            previous = previous,
            rootConfig = config(),
            authStates = authStates("deepseek"),
            catalogModels = null
        )

        assertTrue(merged.catalogFailed)
        assertEquals(previous.catalogModels, merged.data.catalogModels)
        val deepseek = merged.data.providers.firstOrNull { it.name == "deepseek" }
        assertNotNull(deepseek)
        assertEquals(1, deepseek!!.models.size)
    }

    @Test
    fun `successful empty auth response is treated as truth`() {
        val merged = mergeModelsManagementSnapshot(
            connectionKey = keyA,
            previous = snapshot(),
            rootConfig = config(),
            authStates = emptyMap(),
            catalogModels = catalogModels()
        )

        assertTrue(!merged.degraded)
        assertTrue("凭据真的被清空时应如实反映", merged.data.providers.none { it.name == "deepseek" })
    }

    @Test
    fun `first successful sync stores providers and flags partial failure`() {
        val merged = mergeModelsManagementSnapshot(
            connectionKey = keyA,
            previous = null,
            rootConfig = config(),
            authStates = null,
            catalogModels = null
        )

        assertTrue(merged.degraded)
        assertTrue(merged.data.providers.any { it.name == "codywon" })
        assertNull(merged.data.catalogModels)
    }

    @Test
    fun `old connection cache files are pruned`() {
        val dir = File(tmp.root, "cache")
        val store = ModelsManagementCacheStore(dir)
        repeat(6) { index ->
            runBlocking { store.store(snapshot("http://gw-$index.local:4100")) }
        }

        val files = dir.listFiles()?.filter { it.name.endsWith(".json") && !it.name.endsWith(".tmp") }
        assertEquals(4, files?.size)
    }
}
