package com.pi.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `POST /api/sessions/[id]/fork` 响应解析的回归测试。
 *
 * 守护的线上缺陷：pi-web 0.13 的响应是 `{ sessionId, session }`，
 * 新会话的 `path` / `name` 在 `session` 里；旧实现只读顶层，恒取不到，
 * 于是手机上分叉出来的会话被改名成「源标题 · 分叉」。
 */
class ForkSessionResponseParsingTest {

    @Test
    fun `pi-web 0_13 的 session 对象里能取到 path 和 name`() {
        val parsed = ForkSessionResponseParsing.parse(
            """
            {"sessionId":"newsid","session":{"id":"newsid","path":"/sessions/new.jsonl",
             "cwd":"/work","name":"修一下主题 · 3f9c","created":"2026-11-01T22:30:00Z",
             "modified":"2026-11-01T22:30:05Z","messageCount":4,"firstMessage":"修一下主题",
             "parentSessionId":"oldsid","relation":{"kind":"fork","originSessionId":"oldsid"}}}
            """.trimIndent()
        )
        assertNotNull(parsed)
        assertEquals("newsid", parsed!!.sessionId)
        assertEquals("/sessions/new.jsonl", parsed.path)
        assertEquals("修一下主题 · 3f9c", parsed.name)
    }

    @Test
    fun `旧网关的顶层 path 和 name 仍能兜底`() {
        val parsed = ForkSessionResponseParsing.parse(
            """{"sessionId":"s2","path":"/sessions/s2.jsonl","name":"旧形状"}"""
        )
        assertNotNull(parsed)
        assertEquals("s2", parsed!!.sessionId)
        assertEquals("/sessions/s2.jsonl", parsed.path)
        assertEquals("旧形状", parsed.name)
    }

    @Test
    fun `源会话没有标题时 name 保持为空`() {
        // pi-web 对无名源会话不写 session_info，拷贝也就不命名 —— 这里绝不能伪造，
        // 上层据此回退到「源标题 · 分叉」。
        val parsed = ForkSessionResponseParsing.parse(
            """{"sessionId":"s3","session":{"path":"/sessions/s3.jsonl","messageCount":2}}"""
        )
        assertNotNull(parsed)
        assertEquals("/sessions/s3.jsonl", parsed!!.path)
        assertNull(parsed.name)
    }

    @Test
    fun `session 里的空串 name 视为没有并回退顶层`() {
        val parsed = ForkSessionResponseParsing.parse(
            """{"sessionId":"s4","name":"顶层名","session":{"path":"/s.jsonl","name":"   "}}"""
        )
        assertNotNull(parsed)
        assertEquals("顶层名", parsed!!.name)
    }

    @Test
    fun `缺少 sessionId 时返回 null`() {
        assertNull(ForkSessionResponseParsing.parse("""{"session":{"path":"/s.jsonl"}}"""))
        assertNull(ForkSessionResponseParsing.parse("""{"sessionId":null}"""))
    }

    @Test
    fun `非对象响应返回 null 而不是抛异常`() {
        assertNull(ForkSessionResponseParsing.parse(""))
        assertNull(ForkSessionResponseParsing.parse("<html>502</html>"))
        assertNull(ForkSessionResponseParsing.parse("[1,2,3]"))
    }
}
