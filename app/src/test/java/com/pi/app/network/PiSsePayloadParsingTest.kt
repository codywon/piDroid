package com.pi.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 [PiSseClient.parsePiWebSsePayload] 的纯 JVM 单元测试。
 * 严禁引用任何 `android.*` 类。
 */
class PiSsePayloadParsingTest {

    @Test
    fun `json null and non-primitive fields do not throw UnsupportedOperationException`() {
        val retryStart = """{"type":"auto_retry_start","attempt":null,"maxAttempts":null,"delayMs":null,"errorMessage":null}"""
        val startEvents = PiSseClient.parsePiWebSsePayload(null, retryStart)
        assertEquals(1, startEvents.size)
        assertEquals("auto_retry_start", startEvents[0].type)
        assertEquals(2000L, startEvents[0].durationMs)
        assertNull(startEvents[0].output)

        val retryEnd = """{"type":"auto_retry_end","success":null,"attempt":null,"finalError":null,"errorMessage":null}"""
        val endEvents = PiSseClient.parsePiWebSsePayload(null, retryEnd)
        assertEquals(1, endEvents.size)
        assertEquals("auto_retry_end", endEvents[0].type)
        assertFalse(endEvents[0].isError)

        val msgStartNullRole = """{"type":"message_start","message":{"role":null,"model":{"id":1}}}"""
        val msgEvents = PiSseClient.parsePiWebSsePayload(null, msgStartNullRole)
        assertEquals(1, msgEvents.size)
        assertEquals("message_start", msgEvents[0].type)

        val msgEndObjRole = """{"type":"message_end","message":{"role":{"nested":"assistant"}}}"""
        val msgEndEvents = PiSseClient.parsePiWebSsePayload(null, msgEndObjRole)
        assertEquals(1, msgEndEvents.size)
        assertEquals("message_end_other", msgEndEvents[0].type)

        val fallbackNulls = """{"type":"custom_simplified","delta":null,"text":{"bad":1},"content":["arr"],"thinking":null,"tool":null,"toolName":null,"command":null,"input":null,"output":null,"durationMs":null}"""
        val fallbackEvents = PiSseClient.parsePiWebSsePayload(null, fallbackNulls)
        assertEquals(1, fallbackEvents.size)
        assertNull(fallbackEvents[0].delta)
        assertNull(fallbackEvents[0].thinking)
        assertNull(fallbackEvents[0].command)
        assertNull(fallbackEvents[0].output)
        assertNull(fallbackEvents[0].durationMs)
    }

    @Test
    fun `structured prompt_error extracts object error message and never leaks into delta`() {
        val structured = """{"type":"prompt_error","error":{"message":"Rate limit exceeded","code":429}}"""
        val events = PiSseClient.parsePiWebSsePayload(null, structured)
        assertEquals(1, events.size)
        assertEquals("prompt_error", events[0].type)
        assertTrue(events[0].isError)
        assertEquals("Rate limit exceeded", events[0].error)
        assertNull(events[0].delta)

        val nullError = """{"type":"prompt_error","error":null}"""
        val nullEvents = PiSseClient.parsePiWebSsePayload(null, nullError)
        assertEquals(1, nullEvents.size)
        assertEquals("prompt_error", nullEvents[0].type)
        assertEquals("消息发送失败", nullEvents[0].error)
        assertNull(nullEvents[0].delta)
    }

    @Test
    fun `malformed json object payload returns emptyList instead of delta`() {
        val malformed = """  {"type":"message_update","assistantMessageEvent": """
        val events = PiSseClient.parsePiWebSsePayload("message", malformed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `text_end prefers outer message multi-block content and attaches messageId`() {
        val multiBlockPayload = """
            {
              "type": "message_update",
              "assistantMessageEvent": {
                "type": "text_end",
                "content": "second chunk only"
              },
              "message": {
                "id": "msg-42",
                "role": "assistant",
                "content": [
                  {"type": "thinking", "thinking": "inner thought"},
                  {"type": "text", "text": "first chunk"},
                  {"type": "output_text", "text": "second chunk only"}
                ]
              }
            }
        """.trimIndent()
        val events = PiSseClient.parsePiWebSsePayload(null, multiBlockPayload)
        assertEquals(1, events.size)
        assertEquals("text_replace", events[0].type)
        assertEquals("first chunk\nsecond chunk only", events[0].content)
        assertEquals("msg-42", events[0].messageId)
        assertEquals(true, events[0].isStreaming)

        // 无外层 message 时回退到 assistantMessageEvent.content
        val legacyPayload = """
            {
              "type": "message_update",
              "assistantMessageEvent": {
                "type": "text_end",
                "content": "fallback single block"
              }
            }
        """.trimIndent()
        val legacyEvents = PiSseClient.parsePiWebSsePayload(null, legacyPayload)
        assertEquals(1, legacyEvents.size)
        assertEquals("text_replace", legacyEvents[0].type)
        assertEquals("fallback single block", legacyEvents[0].content)
        assertNull(legacyEvents[0].messageId)
    }

    @Test
    fun `assistant snapshots extract thinking blocks for message_start and message_end`() {
        val msgStartPayload = """
            {
              "type": "message_start",
              "message": {
                "role": "assistant",
                "model": "claude-sonnet",
                "timestamp": 1700000000,
                "content": [
                  {"type": "thinking", "thinking": "step 1"},
                  {"type": "thinking", "thinking": "step 2"},
                  {"type": "text", "text": "answer"}
                ]
              }
            }
        """.trimIndent()
        val startEvents = PiSseClient.parsePiWebSsePayload(null, msgStartPayload)
        assertEquals(1, startEvents.size)
        assertEquals("message_snapshot", startEvents[0].type)
        assertEquals("step 1\nstep 2", startEvents[0].thinking)
        assertEquals("answer", startEvents[0].content)
        assertEquals("1700000000", startEvents[0].messageId)

        val msgEndPayload = """
            {
              "type": "message_end",
              "message": {
                "id": "msg-end-1",
                "role": "assistant",
                "model": "claude-sonnet",
                "content": [
                  {"type": "thinking", "thinking": "final thought"},
                  {"type": "output_text", "text": "final reply"}
                ],
                "usage": {
                  "input": 100,
                  "output": 50,
                  "cacheRead": 200,
                  "cacheWrite": 0,
                  "cost": {"total": 0.01}
                }
              }
            }
        """.trimIndent()
        val endEvents = PiSseClient.parsePiWebSsePayload(null, msgEndPayload)
        assertEquals(1, endEvents.size)
        assertEquals("message_end_sync", endEvents[0].type)
        assertEquals("final thought", endEvents[0].thinking)
        assertEquals("final reply", endEvents[0].content)
        assertEquals("msg-end-1", endEvents[0].messageId)
    }

    /**
     * pi-web 0.18.0 给 run 中投递的 user 消息补发 `user_message_entry`（#1746，只用于那条消息上的
     * 「新会话」）。手机端没有这个入口，必须显式忽略：落到兼容 fallback 会变成一条 delta 为空的
     * 事件，被当成正文增量处理。
     */
    @Test
    fun `user message entry is ignored instead of becoming an empty delta`() {
        val payload = """{"type":"user_message_entry","entryId":"e1","timestamp":1763000000000}"""
        val events = PiSseClient.parsePiWebSsePayload("user_message_entry", payload)
        assertTrue(events.isEmpty())
    }

    /**
     * 模型可能把终端转义写进正文/思考（#1753）：对齐 pi-web 的 TextBlock / ThinkingText，
     * 入库前就剥掉，否则手机端显示成 `[38;5;5mThinking:[39m`。
     */
    @Test
    fun `assistant text and thinking drop terminal escapes`() {
        val payload = """
            {
              "type": "message_end",
              "message": {
                "id": "msg-ansi",
                "role": "assistant",
                "content": [
                  {"type": "thinking", "thinking": "\u001b[2mthinking\u001b[0m"},
                  {"type": "output_text", "text": "\u001b[38;5;5manswer\u001b[39m"}
                ]
              }
            }
        """.trimIndent()
        val events = PiSseClient.parsePiWebSsePayload(null, payload)
        assertEquals(1, events.size)
        assertEquals("thinking", events[0].thinking)
        assertEquals("answer", events[0].content)
    }
}
