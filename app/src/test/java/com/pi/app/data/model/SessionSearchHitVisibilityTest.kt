package com.pi.app.data.model

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert
import org.junit.Test

/**
 * 全文搜索命中的可打开性：侧聊 / 子代理的命中不进结果。
 *
 * pi-web 0.17.0 的 `GET /api/sessions/search` 不再过滤 `relation.kind = "side"`
 * （上游 `app/api/sessions/search/route.ts`：侧聊只读边界消息之后的正文，父会话的历史不会重复命中）。
 * 于是命中的 `session` 可能是侧聊，而侧聊在上游 ADR 0007 里只在父会话的右栏打开。
 *
 * 手机端没有右栏，把这些命中当主会话点开就会重现「同一段对话两份入口」——
 * 正是列表页用 `isHiddenFamilyMember()` 挡掉的那种入口。
 */
class SessionSearchHitVisibilityTest {

    private val gson = Gson()

    private fun hit(sessionJson: String, match: String = "命中"): SessionSearchHit =
        SessionSearchHit(
            session = gson.fromJson(JsonParser.parseString(sessionJson), Session::class.java),
            match = match
        )

    @Test
    fun `a side chat hit is not openable as a main session`() {
        val hits = listOf(
            hit("""{"id":"a","relation":{"kind":"side","parentSessionId":"p"}}"""),
            hit("""{"id":"b","name":"普通会话"}""")
        )

        val openable = hits.openableAsSession()

        Assert.assertEquals(1, openable.size)
        Assert.assertEquals("b", openable.single().session.id)
    }

    @Test
    fun `a subagent hit is not openable either`() {
        val hits = listOf(hit("""{"id":"a","relation":{"kind":"subagent","parentSessionId":"p"}}"""))

        Assert.assertTrue(hits.openableAsSession().isEmpty())
    }

    @Test
    fun `a fork hit stays openable`() {
        val hits = listOf(hit("""{"id":"c","relation":{"kind":"fork","originSessionId":"p"}}"""))

        Assert.assertEquals(1, hits.openableAsSession().size)
    }

    @Test
    fun `an older gateway that sends no relation keeps every hit`() {
        // 0.13.x ~ 0.16.x 的真实形态：命中里的 session 没有 relation 字段
        val hits = listOf(
            hit("""{"id":"d","cwd":"D:\\p","active":true}"""),
            hit("""{"id":"e","name":"另一条"}""")
        )

        Assert.assertEquals(2, hits.openableAsSession().size)
    }

    @Test
    fun `the hits that stay keep their order and snippets`() {
        val hits = listOf(
            hit("""{"id":"a","relation":{"kind":"side","parentSessionId":"p"}}""", match = "被丢掉的"),
            hit("""{"id":"b","name":"第一条"}""", match = "第一条命中"),
            hit("""{"id":"c","name":"第二条"}""", match = "第二条命中")
        )

        val openable = hits.openableAsSession()

        Assert.assertEquals(listOf("b", "c"), openable.map { it.session.id })
        Assert.assertEquals("…第一条命中…", openable.first().snippet)
    }
}
