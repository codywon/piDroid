package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话快照与本地列表的对账语义（纯 JVM）。
 *
 * 「快照是不是新鲜的」不再由这里判断：那件事交给服务端的 `snapshotRevision`
 * （见 [SessionRevision]）。这里只保证：乐观提问、在途流式消息、正在跑的工具卡
 * 不会被一次边界重读抹掉；服务端已经写下来的东西（含工具结果）一定会生效。
 */
class SessionSnapshotMergeTest {

    private fun item(
        id: String,
        role: MessageRole,
        content: String,
        entryId: String? = null,
        timestamp: Long = 0L
    ) = ChatItem(id = id, role = role, content = content, entryId = entryId, timestamp = timestamp)

    @Test
    fun `快照里的服务端版本生效但沿用本地稳定 id`() {
        val local = listOf(item("l1", MessageRole.AGENT, "本地版本", entryId = "e1", timestamp = 9_000L))
        val synced = listOf(item("s1", MessageRole.AGENT, "服务端版本（已完成）", entryId = "e1", timestamp = 9_000L))

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)

        assertEquals(1, merged.size)
        assertEquals("服务端版本（已完成）", merged[0].content)
        assertEquals("本地稳定 id 仍要沿用，避免 LazyColumn 重建", "l1", merged[0].id)
    }

    @Test
    fun `服务端已完成的工具结果覆盖本地仍转圈的卡片`() {
        // SSE 断在 tool_execution_end 之前时：边界重读必须把结果补进来，卡片不能永远转圈。
        val local = listOf(
            item("t1", MessageRole.TOOL, "", entryId = null, timestamp = 5_000L).copy(
                toolCall = ToolCallInfo(toolCallId = "call_1", toolName = "bash", command = "npm test", isRunning = true)
            )
        )
        val synced = listOf(
            item("s1", MessageRole.TOOL, "", entryId = null, timestamp = 5_000L).copy(
                toolCall = ToolCallInfo(
                    toolCallId = "call_1",
                    toolName = "bash",
                    command = "npm test",
                    output = "90 passed",
                    durationMs = 1_200L
                )
            )
        )

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)

        assertEquals(1, merged.size)
        val tool = requireNotNull(merged[0].toolCall)
        assertEquals("90 passed", tool.output)
        assertFalse("服务端已经有结果，卡片就不能继续转圈", tool.isRunning)
        assertEquals(1_200L, tool.durationMs)
    }

    @Test
    fun `尚未落盘的乐观提问绝不被快照抹掉`() {
        val local = listOf(
            item("u0", MessageRole.USER, "新指令", entryId = null, timestamp = 20_000L),
            item("l1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L)
        )
        val synced = listOf(item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L))

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = false)

        assertTrue("乐观提问必须保留", merged.any { it.id == "u0" })
        assertEquals("已匹配条目不该重复出现", 2, merged.size)
    }

    @Test
    fun `正在跑的工具卡片不会被快照里的旧版本覆盖掉`() {
        val local = listOf(
            item("t1", MessageRole.TOOL, "", entryId = null, timestamp = 5_000L).copy(
                toolCall = ToolCallInfo(toolCallId = "call_1", toolName = "bash", isRunning = true)
            )
        )
        val synced = listOf(item("s0", MessageRole.AGENT, "上一句", entryId = "e0", timestamp = 1_000L))

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.id == "t1" })
        assertNull(merged.first { it.id == "t1" }.entryId)
    }
}
