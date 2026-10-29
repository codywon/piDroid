package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话树分支回退（navigate_tree / 「从此处编辑」）数据流回归测试。
 *
 * 守护三件事：
 * 1) 服务端 `context.entryIds` 必须正确绑定到平行数组 `context.messages` 上（否则「从此处编辑」拿不到 entryId）；
 * 2) 快照对账必须为本地乐观消息补齐 entryId，同时保持 LazyColumn key 稳定；
 * 3) 分支回退 / 权威替换后，被放弃分支上的工具输出与中断过程必须从列表里彻底消失。
 */
class SessionTreeNavigationTest {

    /** 与 PiApiClient.attachEntryIds 同语义的纯函数（生产代码在 PiApiClient 私有方法内，此处复刻契约做守护）。 */
    private fun attachEntryIds(msgs: JsonArray?, ids: JsonArray?) {
        if (msgs == null || ids == null) return
        val count = minOf(msgs.size(), ids.size())
        for (i in 0 until count) {
            val m = msgs.get(i)
            val id = ids.get(i)?.takeIf { !it.isJsonNull }?.asString
            if (m != null && m.isJsonObject && !id.isNullOrBlank()) {
                val obj = m.asJsonObject
                if (!obj.has("entryId")) obj.addProperty("entryId", id)
            }
        }
    }

    /** 复刻 MainActivity 中 synced 的合并/截断编排（replaceHistory = false 的增量路径）。 */
    private fun incrementalSync(
        local: MutableList<ChatItem>,
        synced: List<ChatItem>,
        effectiveRunning: Boolean
    ) {
        when {
            local.isEmpty() -> local.addAll(synced)
            synced.size > local.size -> {
                for (i in local.indices) local[i] = SessionSnapshotMerge.mergeItem(local[i], synced[i])
                for (i in local.size until synced.size) local.add(synced[i])
            }
            synced.size == local.size -> {
                for (i in synced.indices) local[i] = SessionSnapshotMerge.mergeItem(local[i], synced[i])
            }
            !effectiveRunning -> {
                for (i in synced.indices) local[i] = SessionSnapshotMerge.mergeItem(local[i], synced[i])
                while (local.size > synced.size) local.removeAt(local.lastIndex)
            }
            // 运行中且只多一条流式尾部：保留本地尾部，不截断
        }
    }

    /** 复刻 replaceHistory = true 的权威替换路径（pi-web 的 loadSession）。 */
    private fun authoritativeSync(local: MutableList<ChatItem>, synced: List<ChatItem>) {
        local.clear()
        local.addAll(synced)
    }

    @Test
    fun `attachEntryIds pairs parallel context messages with entryIds`() {
        val msgs = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "user"); addProperty("content", "hello") })
            add(JsonObject().apply { addProperty("role", "assistant"); addProperty("content", "hi") })
        }
        val ids = JsonArray().apply {
            add("entry_user_1")
            add("entry_assistant_1")
        }

        attachEntryIds(msgs, ids)

        assertEquals("entry_user_1", msgs.get(0).asJsonObject.get("entryId").asString)
        assertEquals("entry_assistant_1", msgs.get(1).asJsonObject.get("entryId").asString)
    }

    @Test
    fun `attachEntryIds tolerates mismatched lengths without crashing`() {
        val msgs = JsonArray().apply {
            add(JsonObject().apply { addProperty("role", "user") })
            add(JsonObject().apply { addProperty("role", "assistant") })
        }
        val ids = JsonArray().apply { add("only_one") }

        attachEntryIds(msgs, ids)

        assertEquals("only_one", msgs.get(0).asJsonObject.get("entryId").asString)
        assertNull(msgs.get(1).asJsonObject.get("entryId"))
    }

    @Test
    fun `merge backfills entryId for optimistic user message and keeps local id`() {
        val local = ChatItem(id = "local_1", entryId = null, role = MessageRole.USER, content = "测试提问")
        val disk = ChatItem(id = "disk_1", entryId = "u1", role = MessageRole.USER, content = "测试提问")

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("local_1", merged.id)
        assertEquals("u1", merged.entryId)
    }

    @Test
    fun `merge without entryId keeps the very same instance (no needless recompose)`() {
        val local = ChatItem(id = "l", entryId = "u1", role = MessageRole.USER, content = "x")
        val disk = ChatItem(id = "d", entryId = "u1", role = MessageRole.USER, content = "x")
        assertSame(local, SessionSnapshotMerge.mergeItem(local, disk))
    }

    @Test
    fun `mergeContentChange takes server text but keeps local key when role matches`() {
        val local = ChatItem(id = "l", role = MessageRole.AGENT, content = "半截", isStreaming = true, timestamp = 100L)
        val disk = ChatItem(id = "d", role = MessageRole.AGENT, content = "完整回复", isStreaming = false, timestamp = 200L)

        val merged = SessionSnapshotMerge.mergeItem(local, disk)

        assertEquals("完整回复", merged.content)
        assertEquals("l", merged.id)
        assertEquals(100L, merged.timestamp)
    }

    @Test
    fun `editing the first turn truncates everything when branch goes back to root`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一句提问"),
            ChatItem(id = "2", entryId = "t1", role = MessageRole.TOOL, content = "bash 输出..."),
            ChatItem(id = "3", entryId = "a1", role = MessageRole.AGENT, content = "半截回答")
        )

        // leaf 回到根：服务端活跃分支为空
        authoritativeSync(local, emptyList())

        assertTrue(local.isEmpty())
    }

    @Test
    fun `editing the second turn drops that turn's process output but keeps turn one`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答"),
            ChatItem(id = "3", entryId = "u2", role = MessageRole.USER, content = "第二轮提问"),
            ChatItem(id = "4", entryId = "t2", role = MessageRole.TOOL, content = "第二轮命令输出..."),
            ChatItem(id = "5", entryId = "a2", role = MessageRole.AGENT, content = "第二轮半截回答")
        )
        // leaf 回退到 u2 的父节点 a1
        val synced = listOf(
            ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "第一轮提问"),
            ChatItem(id = "h2", entryId = "a1", role = MessageRole.AGENT, content = "第一轮回答")
        )

        authoritativeSync(local, synced)

        assertEquals(2, local.size)
        assertEquals("第一轮提问", local[0].content)
        assertEquals("第一轮回答", local[1].content)
    }

    @Test
    fun `authoritative sync also discards stale items even when locally marked running`() {
        // 回归：若沿用增量路径，isStreaming 尾部的 inFlightTail 会把旧过程重新拼回来
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "中断输出", isStreaming = true)
        )

        authoritativeSync(local, listOf(ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "提问")))

        assertEquals(1, local.size)
        assertEquals("提问", local[0].content)
    }

    @Test
    fun `incremental sync keeps streaming tail while the run is still going`() {
        val local = mutableListOf(
            ChatItem(id = "1", entryId = "u1", role = MessageRole.USER, content = "提问"),
            ChatItem(id = "2", entryId = "a1", role = MessageRole.AGENT, content = "流式中", isStreaming = true)
        )
        val synced = listOf(ChatItem(id = "h1", entryId = "u1", role = MessageRole.USER, content = "提问"))

        incrementalSync(local, synced, effectiveRunning = true)

        assertEquals(2, local.size)
    }
}
