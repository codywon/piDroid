package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.*
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
        assertEquals(1, customProviders.size)
        assertEquals("codywon", customProviders[0].name)
        assertFalse(customProviders[0].isBuiltIn)

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
        assertTrue(providersObj.has("codywon"))
        assertTrue(providersObj.getAsJsonObject("codywon").has("models"))

        // deepseek should have modelOverrides instead of models array
        assertTrue(providersObj.has("deepseek"))
        val deepseekObj = providersObj.getAsJsonObject("deepseek")
        assertFalse("Built-in provider must not have naked models array in models.json", deepseekObj.has("models"))
        assertTrue("Built-in provider overrides should be in modelOverrides", deepseekObj.has("modelOverrides"))

        val overrides = deepseekObj.getAsJsonObject("modelOverrides")
        assertTrue(overrides.has("deepseek-flash"))
        assertEquals(1000000L, overrides.getAsJsonObject("deepseek-flash").get("contextWindow").asLong)
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

        assertEquals("https://custom.api/v1", provObj.get("baseUrl").asString)
        assertEquals("sk-test", provObj.get("apiKey").asString)
        assertTrue(provObj.has("models"))
        assertEquals(1, provObj.getAsJsonArray("models").size())
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
        assertEquals(2, combined.size)

        // 1. custom-ai
        val customProv = combined.firstOrNull { it.name == "custom-ai" }
        assertNotNull(customProv)
        assertFalse(customProv!!.isBuiltIn)
        assertEquals(1, customProv.models.size)
        assertEquals("custom-1", customProv.models[0].id)

        // 2. deepseek (configured built-in)
        val deepseekProv = combined.firstOrNull { it.name == "deepseek" }
        assertNotNull(deepseekProv)
        assertTrue(deepseekProv!!.isBuiltIn)
        assertEquals(1, deepseekProv.models.size)
        assertEquals("deepseek-chat", deepseekProv.models[0].id)
        assertEquals(64000L, deepseekProv.models[0].contextWindow)

        // 3. unconfigured-prov should NOT be in combined list
        assertNull(combined.firstOrNull { it.name == "unconfigured-prov" })
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
        assertNotNull(parsed)
        assertEquals(2, parsed!!.size)

        val deepseek = parsed["deepseek"]
        assertNotNull(deepseek)
        assertEquals("DeepSeek", deepseek!!.displayName)
        assertTrue(deepseek.configured)
        assertEquals("stored", deepseek.source)
        assertEquals("已保存", deepseek.sourceLabel)
        assertEquals(3, deepseek.modelCount)
        assertFalse(deepseek.supportsOAuth)

        val serialized = ProviderAuthStateSerializer.serialize(parsed)
        val reparsed = ProviderAuthStateSerializer.parse(serialized)
        assertNotNull(reparsed)
        assertEquals(2, reparsed!!.size)
        assertEquals(parsed["deepseek"]?.configured, reparsed["deepseek"]?.configured)
        assertEquals(parsed["openai"]?.supportsOAuth, reparsed["openai"]?.supportsOAuth)
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
        assertEquals(1, combined.size)
        assertTrue(combined[0].isBuiltIn)
        assertTrue("目录未知时不得凭空伪造模型", combined[0].models.isEmpty())
    }
}
