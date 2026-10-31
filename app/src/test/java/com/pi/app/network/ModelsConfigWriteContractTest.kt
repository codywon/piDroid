package com.pi.app.network

import com.google.gson.JsonObject
import org.junit.Assert
import org.junit.Test

/**
 * `PUT /api/models-config` 的 payload 与失败文案契约。
 *
 * 这条约束来自一次实测：同一份无效 models.json（只有 `api`、没有 `baseUrl` 的空 provider）
 * 直接提交会被 pi-web 拒绝，而包上一层 `{config: …}` 后服务端的 provider 校验读不到
 * `providers`，反而放行并写盘 —— 于是「兼容重试」把本该失败的保存变成了写坏配置。
 */
class ModelsConfigWriteContractTest {

    @Test
    fun `payload keeps providers at the top level`() {
        val config = JsonObject().apply {
            add("providers", JsonObject().apply {
                add("relay", JsonObject().apply { addProperty("baseUrl", "https://relay/v1") })
            })
        }

        val payload = ModelsConfigWriteContract.payloadOf(config)

        Assert.assertTrue(payload.has("providers"))
        Assert.assertFalse("绝不能再退回 {config: …} 包装", payload.has("config"))
        Assert.assertEquals("https://relay/v1", payload.getAsJsonObject("providers")
            .getAsJsonObject("relay").get("baseUrl").asString)
    }

    @Test
    fun `legacy config wrapper is stripped from the payload`() {
        val config = JsonObject().apply {
            add("providers", JsonObject())
            add("config", JsonObject().apply { add("providers", JsonObject()) })
        }

        val payload = ModelsConfigWriteContract.payloadOf(config)

        Assert.assertFalse(payload.has("config"))
        Assert.assertTrue(payload.has("providers"))
    }

    @Test
    fun `server error text is surfaced instead of a bare status`() {
        val body = """{"error":"Provider new-provider needs a Base URL or at least one model"}"""

        Assert.assertEquals(
            "Provider new-provider needs a Base URL or at least one model",
            ModelsConfigWriteContract.errorMessage(body, 400)
        )
    }

    @Test
    fun `unreadable error bodies fall back to the status code`() {
        Assert.assertEquals("HTTP 409", ModelsConfigWriteContract.errorMessage(null, 409))
        Assert.assertEquals("HTTP 500", ModelsConfigWriteContract.errorMessage("", 500))
        Assert.assertEquals("HTTP 502", ModelsConfigWriteContract.errorMessage("<html>Bad Gateway</html>", 502))
        Assert.assertEquals("HTTP 400", ModelsConfigWriteContract.errorMessage("""{"error":null}""", 400))
    }

    @Test
    fun `an image limit refusal names the model it refused`() {
        val body = """{"error":"Image size limit of relay/one must be a positive whole number of bytes"}"""

        Assert.assertEquals(
            "Image size limit of relay/one must be a positive whole number of bytes",
            ModelsConfigWriteContract.errorMessage(body, 400)
        )
    }
}
