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
