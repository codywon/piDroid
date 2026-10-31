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
}
