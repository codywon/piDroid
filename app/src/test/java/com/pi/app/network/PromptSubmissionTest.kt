package com.pi.app.network

import com.google.gson.JsonObject
import org.junit.Assert
import org.junit.Test

/**
 * 验证提示词提交 ACK、退回消息解析、错误透出以及 lease 响应解析（纯 JVM 单测）。
 */
class PromptSubmissionTest {

    @Test
    fun `ack identifies queued status from data object`() {
        val data = JsonObject().apply { addProperty("queued", true) }
        val ack = AgentCommandPayload.ackOf(data)
        Assert.assertTrue(ack.queued)

        val emptyAck = AgentCommandPayload.ackOf(JsonObject())
        Assert.assertFalse(emptyAck.queued)
        Assert.assertFalse(AgentCommandPayload.ackOf(null).queued)
    }

    @Test
    fun `recalled prompts extract steering followUp and images`() {
        val data = JsonObject().apply {
            val steerArr = com.google.gson.JsonArray().apply { add("first steer") }
            val followArr = com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("message", "second follow") })
            }
            val imgArr = com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("data", "b64data")
                    addProperty("mimeType", "image/jpeg")
                })
            }
            add("steering", steerArr)
            add("followUp", followArr)
            add("images", imgArr)
        }

        val recalled = AgentCommandPayload.recalledOf(data)
        Assert.assertTrue(recalled.hasAnything)
        Assert.assertEquals(listOf("first steer"), recalled.steering)
        Assert.assertEquals(listOf("second follow"), recalled.followUp)
        Assert.assertEquals("first steer\n\nsecond follow", recalled.text)
        Assert.assertEquals(1, recalled.images.size)
        Assert.assertEquals("image/jpeg", recalled.images[0].mimeType)
        Assert.assertEquals("b64data", recalled.images[0].base64Data)
    }

    @Test
    fun `cwd missing failure is parsed with path`() {
        val body = """{"error":"Directory does not exist: /tmp/missing","reason":"cwd-missing","cwd":"/tmp/missing"}"""
        val ex = AgentCommandPayload.failureOf(body, 400)

        Assert.assertEquals("/tmp/missing", ex.missingCwd)
        Assert.assertFalse(ex.isPromptRejected)
        Assert.assertEquals("Directory does not exist: /tmp/missing", ex.message)
    }

    @Test
    fun `prompt rejection failure is identified`() {
        val body = """{"error":"Session is busy","code":"prompt_rejected","accepted":false}"""
        val ex = AgentCommandPayload.failureOf(body, 500)

        Assert.assertTrue(ex.isPromptRejected)
        Assert.assertNull(ex.missingCwd)
        Assert.assertEquals("Session is busy", ex.message)
    }

    @Test
    fun `only pi prompt rejection is treated as definitively rejected`() {
        // pi 内核拒收（例如运行中发了裸 prompt）会回 code=prompt_rejected / accepted=false
        val rejected = AgentCommandPayload.failureOf(
            """{"error":"Agent is already processing. Specify streamingBehavior ('steer' or 'followUp') to queue the message.","code":"prompt_rejected","accepted":false}""",
            500
        )
        Assert.assertEquals(PromptRecovery.DEFINITELY_REJECTED, PromptRecoveryPolicy.of(rejected))

        // 会话目录已不存在：服务端点名了原因，也是确定没送达
        val missingCwd = AgentCommandPayload.failureOf(
            """{"error":"Directory does not exist: /tmp/gone","reason":"cwd-missing","cwd":"/tmp/gone"}""",
            400
        )
        Assert.assertEquals(PromptRecovery.DEFINITELY_REJECTED, PromptRecoveryPolicy.of(missingCwd))
    }

    @Test
    fun `proxy error pages and transport failures stay unconfirmed`() {
        // frp 隧道离线时用它自己的 HTML 404 页应答每个路径：正文不是 pi 的 JSON，
        // 既没有 code 也没有 accepted，绝不能当成“服务端拒收”把气泡撤掉。
        val tunnel404 = AgentCommandPayload.failureOf(
            "<html><body>404 page not found</body></html>",
            404
        )
        Assert.assertEquals("HTTP 404", tunnel404.message)
        Assert.assertEquals(PromptRecovery.UNCONFIRMED, PromptRecoveryPolicy.of(tunnel404))

        // 其余带状态码的失败（nginx 502 / pi-web 未启动）同样只能是对账，不能当拒收
        val gateway502 = AgentCommandPayload.failureOf("", 502)
        Assert.assertEquals(PromptRecovery.UNCONFIRMED, PromptRecoveryPolicy.of(gateway502))

        // 纯传输层异常（超时 / 连接断）也属于结果未知
        Assert.assertEquals(
            PromptRecovery.UNCONFIRMED,
            PromptRecoveryPolicy.of(java.net.SocketTimeoutException("timeout"))
        )
    }

    @Test
    fun `lease payload parses flags correctly`() {
        val active = """{"success":true,"running":true,"awaitingInput":false,"subagentsActive":true}"""
        val reply = SessionLeasePayload.of(active)
        Assert.assertTrue(reply.supported)
        Assert.assertTrue(reply.running)
        Assert.assertFalse(reply.awaitingInput)
        Assert.assertTrue(reply.subagentsActive)
        Assert.assertTrue(reply.showsActivity)

        val idle = """{"success":true,"running":false,"awaitingInput":false,"subagentsActive":false}"""
        val idleReply = SessionLeasePayload.of(idle)
        Assert.assertFalse(idleReply.showsActivity)
    }
}
