package com.pi.app.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert
import org.junit.Test

/**
 * DeepSeek 思考模式的 `reasoning_content` 回传标志。
 *
 * 这条约束来自一次真实故障：`codywon/deepseek-flash`（中转站）连续 400 —
 * `The reasoning_content in the thinking mode must be passed back to the API.`
 * 根因是 pi-ai 只按 `provider === "deepseek"` 或 baseUrl 含 `deepseek.com` 判定 DeepSeek，
 * 中转站的组合被漏判，重放历史时不写该字段。写盘前补标志就是为了让「App 里新加的中转站
 * DeepSeek 模型」不再复发。
 */
class DeepSeekReasoningCompatTest {

    private fun relayModel(
        id: String,
        reasoning: Boolean? = true,
        compat: JsonObject? = null,
    ): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", id)
        if (reasoning != null) addProperty("reasoning", reasoning)
        if (compat != null) add("compat", compat)
    }

    private fun config(providerKey: String, baseUrl: String, models: List<JsonObject>): JsonObject =
        JsonObject().apply {
            add("providers", JsonObject().apply {
                add(providerKey, JsonObject().apply {
                    addProperty("baseUrl", baseUrl)
                    addProperty("api", "openai-completions")
                    add("models", JsonArray().apply { models.forEach { add(it) } })
                })
            })
        }

    private fun modelOf(config: JsonObject, providerKey: String, index: Int = 0): JsonObject =
        config.getAsJsonObject("providers").getAsJsonObject(providerKey)
            .getAsJsonArray("models")[index].asJsonObject

    private fun flagOf(model: JsonObject): Boolean? =
        model.get("compat")?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get(DeepSeekReasoningCompat.FLAG)?.takeIf { !it.isJsonNull }?.asBoolean

    @Test
    fun `relay hosted deepseek model gets the replay flag`() {
        // 就是出事的那条配置：provider 名与域名都不带 deepseek，只有模型 id 带
        val config = config("codywon", "https://dl.codywon.top:8317/v1", listOf(relayModel("deepseek-flash")))

        val patched = DeepSeekReasoningCompat.heal(config)

        Assert.assertEquals(listOf("codywon/deepseek-flash"), patched)
        Assert.assertEquals(true, flagOf(modelOf(config, "codywon")))
    }

    @Test
    fun `existing compat keys are preserved`() {
        val compat = JsonObject().apply { addProperty("supportsDeveloperRole", false) }
        val config = config("codywon", "https://dl.codywon.top:8317/v1", listOf(relayModel("deepseek-flash", compat = compat)))

        DeepSeekReasoningCompat.heal(config)

        val healed = modelOf(config, "codywon").getAsJsonObject("compat")
        Assert.assertEquals(false, healed.get("supportsDeveloperRole").asBoolean)
        Assert.assertEquals(true, healed.get(DeepSeekReasoningCompat.FLAG).asBoolean)
    }

    @Test
    fun `an explicit false is respected`() {
        val compat = JsonObject().apply { addProperty(DeepSeekReasoningCompat.FLAG, false) }
        val config = config("codywon", "https://dl.codywon.top:8317/v1", listOf(relayModel("deepseek-flash", compat = compat)))

        val patched = DeepSeekReasoningCompat.heal(config)

        Assert.assertTrue("已显式配置过就不该再改", patched.isEmpty())
        Assert.assertEquals(false, flagOf(modelOf(config, "codywon")))
    }

    @Test
    fun `non thinking models are left alone`() {
        // pi-ai 注入空 reasoning_content 的前置条件是 model.reasoning，非思考模型补了也白补
        val config = config(
            "codywon",
            "https://dl.codywon.top:8317/v1",
            listOf(relayModel("deepseek-chat", reasoning = false), relayModel("deepseek-flash", reasoning = null)),
        )

        val patched = DeepSeekReasoningCompat.heal(config)

        Assert.assertTrue(patched.isEmpty())
        Assert.assertNull(flagOf(modelOf(config, "codywon", 0)))
        Assert.assertNull(flagOf(modelOf(config, "codywon", 1)))
    }

    @Test
    fun `other families on the same relay are untouched`() {
        val config = config("codywon", "https://dl.codywon.top:8317/v1", listOf(relayModel("gpt-6-sol"), relayModel("claude-opus-5-5-high")))

        val patched = DeepSeekReasoningCompat.heal(config)

        Assert.assertTrue(patched.isEmpty())
        Assert.assertNull(flagOf(modelOf(config, "codywon", 0)))
        Assert.assertNull(flagOf(modelOf(config, "codywon", 1)))
    }

    @Test
    fun `official deepseek provider and domain are recognised`() {
        val byProvider = config("DeepSeek", "https://relay/any", listOf(relayModel("v4-pro")))
        val byDomain = config("some-relay", "https://api.deepseek.com/v1", listOf(relayModel("v4-pro")))

        Assert.assertEquals(listOf("DeepSeek/v4-pro"), DeepSeekReasoningCompat.heal(byProvider))
        Assert.assertEquals(listOf("some-relay/v4-pro"), DeepSeekReasoningCompat.heal(byDomain))
        Assert.assertTrue(DeepSeekReasoningCompat.isDeepSeekTarget("deepseek", "anything", null))
        Assert.assertTrue(DeepSeekReasoningCompat.isDeepSeekTarget(null, "deepseek-v4-pro", null))
        Assert.assertFalse(DeepSeekReasoningCompat.isDeepSeekTarget("codywon", "gpt-6-sol", "https://dl.codywon.top/v1"))
    }

    @Test
    fun `model overrides are not rewritten`() {
        // modelOverrides 只是「覆盖内置模型参数」，不该被改造成协议声明
        val config = JsonObject().apply {
            add("providers", JsonObject().apply {
                add("deepseek", JsonObject().apply {
                    add("modelOverrides", JsonObject().apply {
                        add("deepseek-flash", JsonObject().apply { addProperty("contextWindow", 1048576) })
                    })
                })
            })
        }

        val patched = DeepSeekReasoningCompat.heal(config)

        Assert.assertTrue(patched.isEmpty())
        val overrides = config.getAsJsonObject("providers").getAsJsonObject("deepseek")
            .getAsJsonObject("modelOverrides").getAsJsonObject("deepseek-flash")
        Assert.assertFalse(overrides.has("compat"))
    }

    @Test
    fun `malformed entries never throw`() {
        val config = JsonObject().apply {
            addProperty("unexpected", "scalar")
            add("providers", JsonObject().apply {
                addProperty("scalar-provider", 42)
                add("relay", JsonObject().apply {
                    addProperty("baseUrl", "https://dl.codywon.top/v1")
                    add("models", JsonArray().apply {
                        add("not-an-object")
                        add(JsonObject().apply { addProperty("name", "no id") })
                    })
                })
            })
        }

        Assert.assertTrue(DeepSeekReasoningCompat.heal(config).isEmpty())
        Assert.assertTrue(DeepSeekReasoningCompat.heal(JsonObject()).isEmpty())
    }

    @Test
    fun `the write contract heals on the only path that persists models json`() {
        // 5 条写入路径最后都走 payloadOf，标志必须在这里出现，否则新加的模型照样 400
        val config = config("codywon", "https://dl.codywon.top:8317/v1", listOf(relayModel("deepseek-flash")))

        val payload = ModelsConfigWriteContract.payloadOf(config)

        Assert.assertEquals(
            true,
            payload.getAsJsonObject("providers").getAsJsonObject("codywon")
                .getAsJsonArray("models")[0].asJsonObject
                .getAsJsonObject("compat").get(DeepSeekReasoningCompat.FLAG).asBoolean,
        )
    }
}
