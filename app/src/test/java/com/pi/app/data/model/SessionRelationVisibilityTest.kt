package com.pi.app.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

/**
 * 会话列表的可见性：侧聊 / 子代理不进列表。
 *
 * 对齐 pi-web `lib/session-family.ts` 的 `isHiddenRelation()`：
 * `subagent` 与 `side` 是「在另一个会话里打开」的继生对话，pi-web 自己的侧栏
 * 从不给它们画行；`fork` 仍是顶层会话。App 若不过滤，用户在 PC 上开侧聊后
 * 手机上会多出一条像普通会话的条目。
 *
 * 关键兼容点：0.13.x 及更早的 pi-web 不返回 `relation`，此时必须等于「不隐藏」。
 */
class SessionRelationVisibilityTest {

    private val gson = Gson()

    private fun session(json: String): Session =
        gson.fromJson(JsonParser.parseString(json), Session::class.java)

    @Test
    fun `side chat is hidden from the list`() {
        val s = session("""{"id":"a","relation":{"kind":"side","parentSessionId":"p"}}""")

        Assert.assertNotNull(s.relation)
        Assert.assertTrue(s.isHiddenFamilyMember())
    }

    @Test
    fun `subagent session is hidden from the list`() {
        val s = session("""{"id":"b","relation":{"kind":"subagent","parentSessionId":"p"}}""")

        Assert.assertTrue(s.isHiddenFamilyMember())
    }

    @Test
    fun `fork stays a top level session`() {
        val s = session("""{"id":"c","relation":{"kind":"fork","originSessionId":"p"}}""")

        Assert.assertFalse(s.isHiddenFamilyMember())
    }

    @Test
    fun `a plain session has no relation and stays visible`() {
        val s = session("""{"id":"d","name":"普通会话"}""")

        Assert.assertNull(s.relation)
        Assert.assertFalse(s.isHiddenFamilyMember())
    }

    @Test
    fun `an older gateway that sends no relation keeps every session visible`() {
        // 0.13.x 的真实形态：字段完全不存在
        val s = session("""{"id":"e","cwd":"D:\\p","active":true}""")

        Assert.assertFalse(s.isHiddenFamilyMember())
    }

    @Test
    fun `unknown future relation kinds are treated as visible`() {
        // 上游再加新的 kind 时，宁可多显示一行，也不要把用户正经的会话藏掉
        val s = session("""{"id":"f","relation":{"kind":"something-new"}}""")

        Assert.assertFalse(s.isHiddenFamilyMember())
    }

    @Test
    fun `relation kind matching ignores case`() {
        val s = session("""{"id":"g","relation":{"kind":"SIDE"}}""")

        Assert.assertTrue(s.isHiddenFamilyMember())
    }

    @Test
    fun `the rest of the session payload still parses`() {
        val s = session(
            """
            {"id":"h","name":"实施 pi-web 0.14 同步","cwd":"D:\\myscript\\pi-app",
             "project":"pi-app","messageCount":12,"active":true,
             "relation":{"kind":"side","parentSessionId":"parent-1"}}
            """.trimIndent()
        )

        Assert.assertEquals("实施 pi-web 0.14 同步", s.name)
        Assert.assertEquals("D:\\myscript\\pi-app", s.cwd)
        Assert.assertEquals(12, s.messageCount)
        Assert.assertTrue(s.active)
        Assert.assertEquals("parent-1", s.relation?.parentSessionId)
    }
}
