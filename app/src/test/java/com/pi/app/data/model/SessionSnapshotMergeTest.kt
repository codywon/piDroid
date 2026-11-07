package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    // ── key 不变式（ensureUniqueIds）──────────────────────────────────────────
    // LazyColumn 对重复 key 是主线程致命异常（进程被杀 = 闪退），不是 React 的 warning。

    @Test
    fun `重复 key 只改 id 不裁内容`() {
        val items = listOf(
            item("hist_S_75_agent", MessageRole.AGENT, "第一条"),
            item("hist_S_75_agent", MessageRole.AGENT, "第二条"),
            item("hist_S_75_agent", MessageRole.AGENT, "第三条")
        )

        val guarded = SessionSnapshotMerge.ensureUniqueIds(items)

        assertEquals("一条内容都不能丢", 3, guarded.size)
        assertEquals(listOf("第一条", "第二条", "第三条"), guarded.map { it.content })
        assertEquals("重复的 id 必须被改掉", 3, guarded.map { it.id }.toSet().size)
        assertEquals("首次出现的 id 保持不变", "hist_S_75_agent", guarded[0].id)
    }

    @Test
    fun `id 唯一时幂等并原样返回`() {
        val items = listOf(
            item("a", MessageRole.USER, "x"),
            item("b", MessageRole.AGENT, "y")
        )
        assertSame("不制造无意义的重组", items, SessionSnapshotMerge.ensureUniqueIds(items))
    }

    @Test
    fun `后缀 id 本身与已有 id 撞车时继续顺延`() {
        val items = listOf(
            item("k", MessageRole.AGENT, "1"),
            item("dup2_k", MessageRole.AGENT, "2"),
            item("k", MessageRole.AGENT, "3")
        )

        val guarded = SessionSnapshotMerge.ensureUniqueIds(items)

        assertEquals(3, guarded.size)
        assertEquals(3, guarded.map { it.id }.toSet().size)
    }

    @Test
    fun `旧下标 id 对账出的重复在兜底后唯一`() {
        // 场景：本地持旧窗口（下标 id 0..N），新快照窗口前移 k 位后也铸了下标 id 0..N，
        // 于是未配对的那几条会跟已配对的本地条目撞 key。
        val local = (0 until 10).map { item("hist_S_${it}_agent", MessageRole.AGENT, "本地 $it", entryId = "e$it") }
        val synced = (3 until 13).map { i ->
            item("hist_S_${i - 3}_agent", MessageRole.AGENT, "服务端 e$i", entryId = "e$i")
        }

        val reconciled = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)
        val guarded = SessionSnapshotMerge.ensureUniqueIds(reconciled)

        assertTrue(
            "兜底前确实会撞（证明这个用例能复现问题）",
            reconciled.size != reconciled.map { it.id }.toSet().size
        )
        assertEquals("兜底后必须唯一", guarded.size, guarded.map { it.id }.toSet().size)
        assertEquals(reconciled.size, guarded.size)
    }

    // ── 压缩卡片去重与在途保护（dedupCompactionCards）───────────────────────

    @Test
    fun `压缩卡片在运行中对账不会因 entryId 缺失或在途推导产生重复`() {
        val compactionContent = "📦 上下文已归档压缩 (压缩前 419K tokens)\n## Goal\n推进平台重构"
        // 场景：本地已有压缩卡片（即使 entryId 缺失），快照再次返回同内容压缩卡片，且此时处于运行中
        val local = listOf(
            item("hist_S_compaction", MessageRole.SYSTEM, compactionContent, entryId = null, timestamp = 10_000L)
        )
        val synced = listOf(
            item("hist_S_compaction", MessageRole.SYSTEM, compactionContent, entryId = null, timestamp = 10_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)

        assertEquals("压缩卡片绝不能被对账翻倍成 2 张", 1, merged.size)
        assertEquals(compactionContent, merged[0].content)
    }

    @Test
    fun `紧邻或完全相同的压缩卡片会被 dedupCompactionCards 自动去重`() {
        val header = "📦 上下文已归档压缩 (压缩前 419K tokens)"
        val summary = "## Goal\n推进架构优化"
        val card1 = item("c1", MessageRole.SYSTEM, "$header\n$summary", entryId = "eid_1", timestamp = 1_000L)
        val card2 = item("c2", MessageRole.SYSTEM, "$header\n$summary", entryId = "eid_1", timestamp = 1_000L)
        val normalMsg = item("u1", MessageRole.USER, "继续", entryId = "eid_2", timestamp = 2_000L)

        val deduped = SessionSnapshotMerge.dedupCompactionCards(listOf(card1, card2, normalMsg))

        assertEquals("相同 entryId 或紧邻的重复压缩卡片必须去重", 2, deduped.size)
        assertEquals(listOf("c1", "u1"), deduped.map { it.id })
    }

    @Test
    fun `不同轮次的合法历史压缩卡片被完整保留`() {
        val compaction1 = item("c1", MessageRole.SYSTEM, "📦 上下文已归档压缩 (压缩前 138K tokens)\n轮次1", entryId = "e1")
        val normalMsg = item("u1", MessageRole.USER, "下一步", entryId = "e2")
        val compaction2 = item("c2", MessageRole.SYSTEM, "📦 上下文已归档压缩 (压缩前 419K tokens)\n轮次2", entryId = "e3")

        val deduped = SessionSnapshotMerge.dedupCompactionCards(listOf(compaction1, normalMsg, compaction2))

        assertEquals("不同轮次的历史压缩卡片必须全部保留", 3, deduped.size)
    }

    @Test
    fun `不同 entryId 即使内容相同的历史压缩卡片也被保留`() {
        val compaction1 = item("c1", MessageRole.SYSTEM, "📦 上下文已归档压缩", entryId = "comp_1")
        val normalMsg = item("u1", MessageRole.USER, "继续", entryId = "u_1")
        val compaction2 = item("c2", MessageRole.SYSTEM, "📦 上下文已归档压缩", entryId = "comp_2")

        val deduped = SessionSnapshotMerge.dedupCompactionCards(listOf(compaction1, normalMsg, compaction2))

        assertEquals("不同 entryId 的合法历史压缩卡片绝不被全局内容误删", 3, deduped.size)
    }

    @Test
    fun `跨角色相同的 entryId 不会被对账错误配对覆盖`() {
        // 极端场景：若本地有个工具卡被误标相同 entryId，与快照中的助手消息不该跨角色认领
        val local = listOf(
            item("t1", MessageRole.TOOL, "", entryId = "shared_id", timestamp = 1_000L)
        )
        val synced = listOf(
            item("s1", MessageRole.AGENT, "回复正文", entryId = "shared_id", timestamp = 1_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = false)

        assertEquals("不同角色不应互相跨角色顶替", 1, merged.size)
        assertEquals(MessageRole.AGENT, merged[0].role)
        assertEquals("s1", merged[0].id)
    }
}
