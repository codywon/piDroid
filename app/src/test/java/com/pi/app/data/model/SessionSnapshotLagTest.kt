package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「滞后快照」对账的回归测试。
 *
 * 线上现象（2026-11-03，会话 01a19138）：PC 端一路跑到第 96 条消息，
 * 手机端聊天流永久停在「90 个测试全绿…」（21:44:03），而底部状态栏却是 21:47:55 的统计
 * ——同一个快照里 `stats` 是新的、`context` 是旧的（pi-web 的 live wrapper 内存 leaf 滞后于磁盘）。
 * 旧实现无条件以快照为权威，把本地已经滚动到、但快照里没有的条目当成“过期分支”删掉，
 * 再加一句 `while (messages.size > reconciled.size) removeAt(lastIndex)` 直接截尾，
 * 于是本回合的真相只能往回退、不能往前走。
 */
class SessionSnapshotLagTest {

    private fun item(
        id: String,
        role: MessageRole,
        content: String,
        entryId: String? = null,
        timestamp: Long = 0L
    ) = ChatItem(id = id, role = role, content = content, entryId = entryId, timestamp = timestamp)

    @Test
    fun `快照尾部比本地旧时判定为滞后`() {
        val local = listOf(
            item("l1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L),
            item("l2", MessageRole.AGENT, "第二步", entryId = "e2", timestamp = 9_000L)
        )
        val synced = listOf(
            item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L)
        )
        assertTrue(SessionSnapshotMerge.isSnapshotOlderThanLocal(local, synced))
    }

    @Test
    fun `快照更新或无法比较时不判定滞后`() {
        val local = listOf(item("l1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 9_000L))
        val newer = listOf(item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 10_000L))
        val same = listOf(item("s2", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 9_000L))
        assertFalse(SessionSnapshotMerge.isSnapshotOlderThanLocal(local, newer))
        assertFalse(SessionSnapshotMerge.isSnapshotOlderThanLocal(local, same))
        // 没有任何已落盘条目（全新会话 / 只有乐观消息）时不做判断
        assertFalse(SessionSnapshotMerge.isSnapshotOlderThanLocal(local, emptyList()))
    }

    @Test
    fun `滞后快照不得删掉本地已落盘的更新内容`() {
        val local = listOf(
            item("l1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L),
            item("l2", MessageRole.TOOL, "", entryId = "e2", timestamp = 5_000L).copy(
                toolCall = ToolCallInfo(toolName = "bash", command = "ls", output = "ok")
            ),
            item("l3", MessageRole.AGENT, "第二步", entryId = "e3", timestamp = 9_000L)
        )
        val synced = listOf(
            item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(
            local = local,
            synced = synced,
            effectiveRunning = true,
            snapshotStale = true
        )

        assertEquals("滞后快照不能截掉本地更新的尾部", 3, merged.size)
        assertEquals(listOf("l1", "l2", "l3"), merged.map { it.id })
        assertEquals("第二步", merged.last().content)
    }

    @Test
    fun `滞后快照下同一条消息保留本地更完整的正文`() {
        val local = listOf(
            item("l1", MessageRole.AGENT, "第一步：已经流到第十行", entryId = "e1", timestamp = 9_000L)
        )
        // 快照里同一条 entryId 只落到第三行（服务端 .jsonl 还没写完 / 分支滞后）
        val synced = listOf(
            item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 9_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(
            local = local,
            synced = synced,
            effectiveRunning = true,
            snapshotStale = true
        )

        assertEquals(1, merged.size)
        assertEquals("已经流下来的正文不能被滞后快照回滚", "第一步：已经流到第十行", merged[0].content)
        assertEquals("本地稳定 id 与 entryId 都要保留", "l1", merged[0].id)
        assertEquals("e1", merged[0].entryId!!)
    }

    @Test
    fun `快照追上之后恢复正常以服务端为准`() {
        val local = listOf(
            item("l1", MessageRole.AGENT, "本地版本", entryId = "e1", timestamp = 9_000L)
        )
        val synced = listOf(
            item("s1", MessageRole.AGENT, "服务端版本（已完成）", entryId = "e1", timestamp = 9_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(
            local = local,
            synced = synced,
            effectiveRunning = true,
            snapshotStale = false
        )

        assertEquals(1, merged.size)
        assertEquals("服务端版本（已完成）", merged[0].content)
        assertEquals("本地稳定 id 仍要沿用，避免 LazyColumn 重建", "l1", merged[0].id)
    }

    @Test
    fun `滞后快照不会让乐观提问或已作废分支复活`() {
        // 本地有一条尚未落盘的乐观提问：滞后时仍然保留（既有契约）
        val local = listOf(
            item("u0", MessageRole.USER, "新指令", entryId = null, timestamp = 20_000L),
            item("l1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L)
        )
        val synced = listOf(
            item("s1", MessageRole.AGENT, "第一步", entryId = "e1", timestamp = 1_000L)
        )

        val merged = SessionSnapshotMerge.reconcileList(
            local = local,
            synced = synced,
            effectiveRunning = false,
            snapshotStale = true
        )

        assertTrue("乐观提问必须保留", merged.any { it.id == "u0" })
        assertEquals("已匹配条目不该重复出现", 2, merged.size)
    }
}
