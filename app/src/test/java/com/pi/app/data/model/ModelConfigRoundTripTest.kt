package com.pi.app.data.model

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

/**
 * models.json 往返：界面上只能编辑一部分字段，保存时**没建模的字段必须原样留着**。
 *
 * 背景：pi 0.13 起 models.json 支持单模型 `baseUrl`、`compat.forceAdaptiveThinking`、
 * `inputLimits.images.resize.maxBytes` 等字段；旧实现把自定义 provider 整段重建，
 * 用户在 PC 上配好后只要用手机改一次参数就会把它们抹掉。
 */
class ModelConfigRoundTripTest {

    private fun parse(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun roundTrip(source: String, edit: (MutableList<ProviderConfigItem>) -> Unit = {}): JsonObject {
        val root = parse(source)
        val providers = ModelConfigConverter.parseProvidersConfig(root).toMutableList()
        edit(providers)
        return ModelConfigConverter.serializeProvidersConfig(providers, root)
    }

    @Test
    fun `unknown per-model fields survive a save`() {
        val saved = roundTrip(
            """
            {
              "providers": {
                "relay": {
                  "baseUrl": "https://relay.example/v1",
                  "api": "anthropic-messages",
                  "models": [
                    {
                      "id": "claude-x",
                      "name": "Claude X",
                      "contextWindow": 200000,
                      "maxTokens": 8192,
                      "baseUrl": "https://relay.example/anthropic",
                      "compat": { "forceAdaptiveThinking": true, "supportsThinking": true },
                      "inputLimits": { "images": { "resize": { "maxBytes": 1048576 } } }
                    }
                  ]
                }
              }
            }
            """.trimIndent()
        )

        val model = saved.getAsJsonObject("providers").getAsJsonObject("relay")
            .getAsJsonArray("models")[0].asJsonObject

        Assert.assertEquals("https://relay.example/anthropic", model.get("baseUrl").asString)
        Assert.assertTrue(model.getAsJsonObject("compat").get("forceAdaptiveThinking").asBoolean)
        Assert.assertTrue(model.getAsJsonObject("compat").get("supportsThinking").asBoolean)
        Assert.assertEquals(
            1048576,
            model.getAsJsonObject("inputLimits").getAsJsonObject("images")
                .getAsJsonObject("resize").get("maxBytes").asInt
        )
    }

    @Test
    fun `unknown provider level fields survive a save`() {
        val saved = roundTrip(
            """
            {
              "providers": {
                "relay": {
                  "baseUrl": "https://relay.example/v1",
                  "api": "openai-completions",
                  "name": "Relay",
                  "compat": { "authHeader": true },
                  "modelOverrides": { "gpt-x": { "contextWindow": 400000 } },
                  "models": [ { "id": "gpt-x" } ]
                }
              }
            }
            """.trimIndent()
        )

        val provider = saved.getAsJsonObject("providers").getAsJsonObject("relay")
        Assert.assertEquals("Relay", provider.get("name").asString)
        Assert.assertTrue(provider.getAsJsonObject("compat").get("authHeader").asBoolean)
        Assert.assertEquals(
            400000,
            provider.getAsJsonObject("modelOverrides").getAsJsonObject("gpt-x").get("contextWindow").asInt
        )
    }

    @Test
    fun `editing a field still overwrites the stored value`() {
        val saved = roundTrip(
            """
            { "providers": { "relay": {
                "baseUrl": "https://relay.example/v1",
                "models": [ { "id": "m", "contextWindow": 1000, "maxTokens": 100 } ]
            } } }
            """.trimIndent()
        ) { providers ->
            val provider = providers.first()
            providers[0] = provider.copy(
                models = provider.models.map { it.copy(contextWindow = 123456L, maxTokens = 4096L) }
            )
        }

        val model = saved.getAsJsonObject("providers").getAsJsonObject("relay")
            .getAsJsonArray("models")[0].asJsonObject
        Assert.assertEquals(123456L, model.get("contextWindow").asLong)
        Assert.assertEquals(4096L, model.get("maxTokens").asLong)
    }

    @Test
    fun `removed provider and model disappear from the saved file`() {
        val saved = roundTrip(
            """
            { "providers": {
                "keep": { "baseUrl": "https://keep/v1", "models": [ { "id": "a" }, { "id": "b" } ] },
                "gone": { "baseUrl": "https://gone/v1", "models": [ { "id": "c" } ] }
            } }
            """.trimIndent()
        ) { providers ->
            val keep = providers.first { it.name == "keep" }
            val remaining = providers.filter { it.name == "keep" }
                .map { keep.copy(models = keep.models.filter { m -> m.id == "a" }) }
            providers.clear()
            providers.addAll(remaining)
        }

        val providers = saved.getAsJsonObject("providers")
        Assert.assertEquals(setOf("keep"), providers.keySet())
        Assert.assertEquals(
            listOf("a"),
            providers.getAsJsonObject("keep").getAsJsonArray("models").map { it.asJsonObject.get("id").asString }
        )
    }

    @Test
    fun `cleared api and headers are removed instead of left stale`() {
        val saved = roundTrip(
            """
            { "providers": { "relay": {
                "baseUrl": "https://relay/v1",
                "models": [ { "id": "m", "api": "openai-responses", "headers": { "x-a": "1" } } ]
            } } }
            """.trimIndent()
        ) { providers ->
            val provider = providers.first()
            providers[0] = provider.copy(
                models = provider.models.map { it.copy(apiOverride = "", headers = emptyList()) }
            )
        }

        val model = saved.getAsJsonObject("providers").getAsJsonObject("relay")
            .getAsJsonArray("models")[0].asJsonObject
        Assert.assertFalse(model.has("api"))
        Assert.assertFalse(model.has("headers"))
    }

    @Test
    fun `built-in provider keeps its proxy base url even without model overrides`() {
        val root = parse(
            """
            { "providers": { "deepseek": { "baseUrl": "https://proxy.example/v1" } } }
            """.trimIndent()
        )
        val saved = ModelConfigConverter.serializeProvidersConfig(
            listOf(
                ProviderConfigItem(
                    name = "deepseek",
                    displayName = "DeepSeek",
                    baseUrl = "",
                    api = "openai-completions",
                    models = listOf(ModelConfigItem(id = "deepseek-chat", contextWindow = 128000L)),
                    isBuiltIn = true
                )
            ),
            root
        )

        val provider = saved.getAsJsonObject("providers").getAsJsonObject("deepseek")
        Assert.assertEquals("https://proxy.example/v1", provider.get("baseUrl").asString)
        Assert.assertFalse("built-in provider must not grow a models array", provider.has("models"))
        Assert.assertEquals(
            128000L,
            provider.getAsJsonObject("modelOverrides").getAsJsonObject("deepseek-chat").get("contextWindow").asLong
        )
    }

    @Test
    fun `saved payload is a top level config, never an envelope`() {
        val saved = roundTrip("""{ "providers": { "relay": { "baseUrl": "https://relay/v1" } } }""")

        Assert.assertTrue(saved.has("providers"))
        Assert.assertFalse(saved.has("config"))
        // 顶层其他键也不能被丢掉
        val withExtra = roundTrip("""{ "providers": {}, "schemaVersion": 3 }""")
        Assert.assertEquals(3, withExtra.get("schemaVersion").asInt)
    }
}
