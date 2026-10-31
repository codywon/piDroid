package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

class ProviderConfigMergeTest {

    @Test
    fun `built-in provider does not write models array without baseUrl into models json`() {
        val root = JsonParser.parseString("""
            {
              "providers": {
                "codywon": {
                  "baseUrl": "https://api.codywon.com/v1",
                  "api": "openai-completions",
                  "models": [
                    { "id": "model-1", "name": "Model 1", "contextWindow": 100000 }
                  ]
                }
              }
            }
        """.trimIndent()).asJsonObject

        val customProviders = ModelConfigConverter.parseProvidersConfig(root)
        Assert.assertEquals(1, customProviders.size)
        Assert.assertEquals("codywon", customProviders[0].name)
        Assert.assertFalse(customProviders[0].isBuiltIn)

        // Built-in provider like deepseek
        val builtInDeepSeek = ProviderConfigItem(
            name = "deepseek",
            displayName = "DeepSeek",
            baseUrl = "",
            api = "openai-completions",
            models = listOf(
                ModelConfigItem(id = "deepseek-flash", name = "DeepSeek Flash", contextWindow = 1000000L),
                ModelConfigItem(id = "deepseek-v4-pro", name = "DeepSeek Pro", contextWindow = 128000L)
            ),
            isBuiltIn = true
        )

        val combined = customProviders + builtInDeepSeek
        val serialized = ModelConfigConverter.serializeProvidersConfig(combined, root)
        val providersObj = serialized.getAsJsonObject("providers")

        // codywon should have models array
        Assert.assertTrue(providersObj.has("codywon"))
        Assert.assertTrue(providersObj.getAsJsonObject("codywon").has("models"))

        // deepseek should have modelOverrides instead of models array
        Assert.assertTrue(providersObj.has("deepseek"))
        val deepseekObj = providersObj.getAsJsonObject("deepseek")
        Assert.assertFalse("Built-in provider must not have naked models array in models.json", deepseekObj.has("models"))
        Assert.assertTrue("Built-in provider overrides should be in modelOverrides", deepseekObj.has("modelOverrides"))

        val overrides = deepseekObj.getAsJsonObject("modelOverrides")
        Assert.assertTrue(overrides.has("deepseek-flash"))
        Assert.assertEquals(1000000L, overrides.getAsJsonObject("deepseek-flash").get("contextWindow").asLong)
    }

    @Test
    fun `custom provider serialization preserves baseUrl and models`() {
        val prov = ProviderConfigItem(
            name = "custom-prov",
            displayName = "custom-prov",
            baseUrl = "https://custom.api/v1",
            apiKey = "sk-test",
            api = "openai-completions",
            models = listOf(
                ModelConfigItem(id = "custom-m1", name = "Custom M1", reasoning = true, contextWindow = 200000L)
            ),
            isBuiltIn = false
        )

        val serialized = ModelConfigConverter.serializeProvidersConfig(listOf(prov))
        val provObj = serialized.getAsJsonObject("providers").getAsJsonObject("custom-prov")

        Assert.assertEquals("https://custom.api/v1", provObj.get("baseUrl").asString)
        Assert.assertEquals("sk-test", provObj.get("apiKey").asString)
        Assert.assertTrue(provObj.has("models"))
        Assert.assertEquals(1, provObj.getAsJsonArray("models").size())
    }

    @Test
    fun `combineProviders combines custom providers and configured auth states with dynamic catalog`() {
        val root = JsonParser.parseString("""
            {
              "providers": {
                "custom-ai": {
                  "baseUrl": "https://custom.ai/v1",
                  "models": [
                    { "id": "custom-1", "name": "Custom 1" }
                  ]
                }
              }
            }
        """.trimIndent()).asJsonObject

        val authStates = mapOf(
            "deepseek" to ProviderAuthState(
                providerId = "deepseek",
                displayName = "DeepSeek",
                configured = true,
                source = "stored"
            ),
            "unconfigured-prov" to ProviderAuthState(
                providerId = "unconfigured-prov",
                displayName = "Unconfigured",
                configured = false
            )
        )

        val catalogModels = listOf(
            ModelInfo(
                id = "deepseek-chat",
                name = "DeepSeek V3",
                provider = "deepseek",
                contextWindow = 64000L,
                maxTokens = 4096L,
                supportsThinking = false,
                supportsVision = false
            ),
            ModelInfo(
                id = "other-model",
                name = "Other",
                provider = "other",
                contextWindow = 32000L,
                maxTokens = 2048L
            )
        )

        val combined = ModelConfigConverter.combineProviders(root, authStates, catalogModels)
        Assert.assertEquals(2, combined.size)

        // 1. custom-ai
        val customProv = combined.firstOrNull { it.name == "custom-ai" }
        Assert.assertNotNull(customProv)
        Assert.assertFalse(customProv!!.isBuiltIn)
        Assert.assertEquals(1, customProv.models.size)
        Assert.assertEquals("custom-1", customProv.models[0].id)

        // 2. deepseek (configured built-in)
        val deepseekProv = combined.firstOrNull { it.name == "deepseek" }
        Assert.assertNotNull(deepseekProv)
        Assert.assertTrue(deepseekProv!!.isBuiltIn)
        Assert.assertEquals(1, deepseekProv.models.size)
        Assert.assertEquals("deepseek-chat", deepseekProv.models[0].id)
        Assert.assertEquals(64000L, deepseekProv.models[0].contextWindow)

        // 3. unconfigured-prov should NOT be in combined list
        Assert.assertNull(combined.firstOrNull { it.name == "unconfigured-prov" })
    }

    @Test
    fun `ProviderAuthStateSerializer parses and serializes correctly`() {
        val jsonStr = """
            {
              "apiKeyProviders": [
                {
                  "id": "deepseek",
                  "displayName": "DeepSeek",
                  "configured": true,
                  "source": "stored",
                  "modelCount": 3,
                  "supportsOAuth": false
                },
                {
                  "id": "openai",
                  "displayName": "OpenAI",
                  "configured": false,
                  "source": null,
                  "modelCount": 0,
                  "supportsOAuth": true
                }
              ]
            }
        """.trimIndent()

        val parsed = ProviderAuthStateSerializer.parse(jsonStr)
        Assert.assertNotNull(parsed)
        Assert.assertEquals(2, parsed!!.size)

        val deepseek = parsed["deepseek"]
        Assert.assertNotNull(deepseek)
        Assert.assertEquals("DeepSeek", deepseek!!.displayName)
        Assert.assertTrue(deepseek.configured)
        Assert.assertEquals("stored", deepseek.source)
        Assert.assertEquals("已保存", deepseek.sourceLabel)
        Assert.assertEquals(3, deepseek.modelCount)
        Assert.assertFalse(deepseek.supportsOAuth)

        val serialized = ProviderAuthStateSerializer.serialize(parsed)
        val reparsed = ProviderAuthStateSerializer.parse(serialized)
        Assert.assertNotNull(reparsed)
        Assert.assertEquals(2, reparsed!!.size)
        Assert.assertEquals(parsed["deepseek"]?.configured, reparsed["deepseek"]?.configured)
        Assert.assertEquals(parsed["openai"]?.supportsOAuth, reparsed["openai"]?.supportsOAuth)
    }

    @Test
    fun `combineProviders without catalog keeps configured built-in provider but does not fabricate models`() {
        val root = JsonParser.parseString("""
            { "providers": {} }
        """.trimIndent()).asJsonObject

        val authStates = mapOf(
            "deepseek" to ProviderAuthState(providerId = "deepseek", displayName = "DeepSeek", configured = true)
        )

        val combined = ModelConfigConverter.combineProviders(root, authStates, null)
        Assert.assertEquals(1, combined.size)
        Assert.assertTrue(combined[0].isBuiltIn)
        Assert.assertTrue("目录未知时不得凭空伪造模型", combined[0].models.isEmpty())
    }

    @Test
    fun `parseProvidersConfig handles explicit null fields safely without UnsupportedOperationException`() {
        val root = JsonParser.parseString("""
            {
              "providers": {
                "null-prov": {
                  "baseUrl": null,
                  "apiKey": null,
                  "api": null,
                  "headers": {
                    "h1": null
                  },
                  "models": [
                    {
                      "id": "model-1",
                      "name": null,
                      "reasoning": null,
                      "contextWindow": null,
                      "maxTokens": null,
                      "cost": {
                        "input": null,
                        "output": null
                      },
                      "api": null,
                      "thinkingLevel": null
                    }
                  ]
                }
              }
            }
        """.trimIndent()).asJsonObject

        val parsed = ModelConfigConverter.parseProvidersConfig(root)
        Assert.assertEquals(1, parsed.size)
        val prov = parsed[0]
        Assert.assertEquals("null-prov", prov.name)
        Assert.assertEquals("", prov.baseUrl)
        Assert.assertEquals("", prov.apiKey)
        Assert.assertEquals("openai-completions", prov.api)
        Assert.assertEquals(1, prov.models.size)
        val m = prov.models[0]
        Assert.assertEquals("model-1", m.id)
        Assert.assertEquals("", m.name)
        Assert.assertFalse(m.reasoning)
        Assert.assertEquals(200000L, m.contextWindow)
        Assert.assertEquals(8192L, m.maxTokens)
        Assert.assertEquals(0.0, m.costInput, 0.0001)
    }

    @Test
    fun `combineProviders preserves user modelOverrides and builtIn baseUrl from models json`() {
        val root = JsonParser.parseString("""
            {
              "providers": {
                "deepseek": {
                  "baseUrl": "https://deepseek-proxy.internal/v1",
                  "modelOverrides": {
                    "deepseek-chat": {
                      "contextWindow": 1000000,
                      "maxTokens": 16384,
                      "reasoning": true,
                      "name": "DeepSeek Turbo"
                    }
                  }
                }
              }
            }
        """.trimIndent()).asJsonObject

        val authStates = mapOf(
            "deepseek" to ProviderAuthState(
                providerId = "deepseek",
                displayName = "DeepSeek",
                configured = true,
                source = "stored"
            )
        )

        val catalog = listOf(
            ModelInfo(
                id = "deepseek-chat",
                name = "DeepSeek V3",
                provider = "deepseek",
                contextWindow = 64000L,
                maxTokens = 4096L,
                supportsThinking = false,
                supportsVision = true
            ),
            ModelInfo(
                id = "deepseek-reasoner",
                name = "DeepSeek R1",
                provider = "deepseek",
                contextWindow = 128000L,
                maxTokens = 8192L
            )
        )

        val combined = ModelConfigConverter.combineProviders(root, authStates, catalog)
        Assert.assertEquals(1, combined.size)
        val deepseek = combined[0]
        Assert.assertTrue(deepseek.isBuiltIn)
        Assert.assertEquals("https://deepseek-proxy.internal/v1", deepseek.baseUrl)
        Assert.assertEquals(2, deepseek.models.size)

        // deepseek-chat should have user overrides applied!
        val chat = deepseek.models.first { it.id == "deepseek-chat" }
        Assert.assertEquals("DeepSeek Turbo", chat.name)
        Assert.assertEquals(1000000L, chat.contextWindow)
        Assert.assertEquals(16384L, chat.maxTokens)
        Assert.assertTrue(chat.reasoning)
        Assert.assertTrue(chat.hasVision)

        // deepseek-reasoner without override keeps catalog default
        val reasoner = deepseek.models.first { it.id == "deepseek-reasoner" }
        Assert.assertEquals("DeepSeek R1", reasoner.name)
        Assert.assertEquals(128000L, reasoner.contextWindow)
        Assert.assertEquals(8192L, reasoner.maxTokens)
    }
    // ── P0 回归（2026-11-01）：改 deepseek 参数把 codywon 的 models 数组整家删掉 ──

    private val p0Root = JsonParser.parseString("""
        {
          "providers": {
            "deepseek": {
              "modelOverrides": { "deepseek-flash": { "contextWindow": 1048576, "maxTokens": 393216 } }
            },
            "codywon": {
              "baseUrl": "https://dl.codywon.top:8317/v1",
              "api": "openai-completions",
              "apiKey": "codywon",
              "models": [
                { "id": "deepseek-flash", "name": "deepseek-flash", "contextWindow": 1000000, "maxTokens": 384000,
                  "reasoning": true, "input": ["text", "image"] },
                { "id": "gemini-3.8-flash-high", "name": "Gemini 3.8 Flash High", "contextWindow": 1000000,
                  "maxTokens": 65536, "reasoning": true, "input": ["text", "image"] }
              ]
            }
          }
        }
    """.trimIndent()).asJsonObject

    private fun p0Auth() = mapOf(
        "deepseek" to ProviderAuthState(providerId = "deepseek", configured = true, source = "stored", modelCount = 2),
        "codywon" to ProviderAuthState(providerId = "codywon", configured = true, source = "stored", modelCount = 2)
    )

    @Test
    fun `provider that declares models in models json stays custom even with a stored api key`() {
        val combined = ModelConfigConverter.combineProviders(p0Root, p0Auth(), null)
        val codywon = combined.first { it.name == "codywon" }
        Assert.assertFalse("models.json 里声明了 models 的 provider 不能当成内置", codywon.isBuiltIn)
        Assert.assertEquals(listOf("deepseek-flash", "gemini-3.8-flash-high"), codywon.models.map { it.id })

        val deepseek = combined.first { it.name == "deepseek" }
        Assert.assertTrue("没有 models 数组的内置 provider 保持内置", deepseek.isBuiltIn)
    }

    @Test
    fun `saving after editing a built-in provider keeps every custom model`() {
        val combined = ModelConfigConverter.combineProviders(p0Root, p0Auth(), null)
        // 模拟用户在模型管理里改了 deepseek 的上下文窗口后保存
        val edited = combined.map { prov ->
            if (prov.name == "deepseek") {
                prov.copy(models = prov.models + ModelConfigItem(id = "deepseek-flash", name = "x", contextWindow = 1048576L, maxTokens = 393216L))
            } else prov
        }
        val serialized = ModelConfigConverter.serializeProvidersConfig(edited, p0Root)
        val providers = serialized.getAsJsonObject("providers")
        val codywon = providers.getAsJsonObject("codywon")
        Assert.assertTrue("codywon 必须保留 models 数组", codywon.has("models"))
        Assert.assertEquals(
            listOf("deepseek-flash", "gemini-3.8-flash-high"),
            codywon.getAsJsonArray("models").map { it.asJsonObject.get("id").asString }
        )
        // 名字/vision 也不能被抹掉
        val gemini = codywon.getAsJsonArray("models").map { it.asJsonObject }.first { it.get("id").asString == "gemini-3.8-flash-high" }
        Assert.assertEquals("Gemini 3.8 Flash High", gemini.get("name").asString)
        Assert.assertEquals(2, gemini.getAsJsonArray("input").size())
        // deepseek（真内置）仍然是 modelOverrides
        val deepseek = providers.getAsJsonObject("deepseek")
        Assert.assertFalse(deepseek.has("models"))
        Assert.assertTrue(deepseek.getAsJsonObject("modelOverrides").has("deepseek-flash"))
    }

    @Test
    fun `built-in branch never deletes an existing models array`() {
        // 防御：即便上层误把 codywon 判成内置，序列化也不许删它的 models 数组
        val misclassified = ProviderConfigItem(
            name = "codywon",
            displayName = "codywon",
            baseUrl = "https://dl.codywon.top:8317/v1",
            api = "openai-completions",
            models = listOf(ModelConfigItem(id = "deepseek-flash", name = "deepseek-flash", contextWindow = 1000000L, maxTokens = 384000L)),
            isBuiltIn = true
        )
        val serialized = ModelConfigConverter.serializeProvidersConfig(listOf(misclassified), p0Root)
        val codywon = serialized.getAsJsonObject("providers").getAsJsonObject("codywon")
        Assert.assertTrue("误判为内置也不许删 models", codywon.has("models"))
        Assert.assertEquals(2, codywon.getAsJsonArray("models").size())
    }
    @Test
    fun `custom model thinking level map survives a save round trip`() {
        // pi-ai 的规则：xhigh / max 只有在 thinkingLevelMap 里显式写了才存在；
        // 丢掉这个 map，模型就只剩到 high（P0 那次把整段 models 删掉时顺手丢了它）。
        val root = JsonParser.parseString("""
            {"providers": {"codywon": {"baseUrl": "https://x/v1", "api": "openai-completions", "apiKey": "k",
              "models": [
                {"id": "deepseek-flash", "name": "deepseek-flash", "reasoning": true,
                 "contextWindow": 1000000, "maxTokens": 384000, "input": ["text","image"],
                 "thinkingLevelMap": {"low": "low", "medium": "medium", "high": "high", "max": "max"}}
              ]}}}
        """.trimIndent()).asJsonObject

        val providers = ModelConfigConverter.combineProviders(root, emptyMap(), null)
        val saved = ModelConfigConverter.serializeProvidersConfig(providers, root)
            .getAsJsonObject("providers").getAsJsonObject("codywon")
            .getAsJsonArray("models").get(0).asJsonObject

        val map = saved.getAsJsonObject("thinkingLevelMap")
        Assert.assertNotNull("thinkingLevelMap 不能被丢掉", map)
        Assert.assertEquals("max", map.get("max").asString)
        Assert.assertEquals("high", map.get("high").asString)
        Assert.assertEquals("deepseek-flash", saved.get("name").asString)
        Assert.assertEquals(2, saved.getAsJsonArray("input").size())
    }
}
