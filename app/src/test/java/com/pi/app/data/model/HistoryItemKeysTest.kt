package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历史消息 key 规则（对齐 pi-web 的 `messageKeys`）。
 *
 * 这组用例钉的是**一个导致闪退的不变式**：历史消息的 id 必须由「会话条目」决定，
 * 不能由「渲染窗口内的下标」决定。窗口起点会随会话增长 / 压缩 / 解析路径切换而前移，
 * 下标会随之整体重排 —— 两次快照就能给两条不同消息铸出同一个 key，
 * 而 `LazyColumn` 对重复 key 抛的是主线程致命异常（进程被杀）。
 */
class HistoryItemKeysTest {

    /** 模拟一次快照解析：把一段条目按某个窗口起点切成若干个渲染单元。 */
    private fun parseWindow(
        entries: List<Pair<String, String>>,
        start: Int,
        count: Int
    ): List<ChatItem> = entries
        .drop(start)
        .take(count)
        .map { (entryId, text) ->
            ChatItem(
                id = HistoryItemKeys.of("S1", entryId, "agent", fallbackSeed = 0L),
                entryId = entryId,
                role = MessageRole.AGENT,
                content = text
            )
        }

    /** 缺陷版本（v2.0.8 及以前）的规则：id 里塞窗口内下标，仅用于说明回归为何会发生。 */
    private fun parseWindowWithIndexId(
        entries: List<Pair<String, String>>,
        start: Int,
        count: Int
    ): List<ChatItem> = entries
        .drop(start)
        .take(count)
        .mapIndexed { idx, (entryId, text) ->
            ChatItem(
                id = "hist_S1_${idx}_agent",
                entryId = entryId,
                role = MessageRole.AGENT,
                content = text
            )
        }

    @Test
    fun `同一条目在不同窗口里得到同一个 id`() {
        val all = (0 until 120).map { "e$it" to "第 $it 条" }

        val oldWindow = parseWindow(all, start = 20, count = 80)
        val newWindow = parseWindow(all, start = 45, count = 80)

        val oldById = oldWindow.associateBy { it.entryId }
        val newById = newWindow.associateBy { it.entryId }
        val overlap = oldById.keys intersect newById.keys
        assertTrue("两个窗口必须有重叠条目，否则这个用例证明不了什么", overlap.isNotEmpty())
        for (entryId in overlap) {
            assertEquals(
                "条目 $entryId 的 key 不能因为窗口起点前移而改变",
                oldById.getValue(entryId).id,
                newById.getValue(entryId).id
            )
        }
    }

    @Test
    fun `旧的下标规则在窗口前移后会给同一条消息两个不同 id`() {
        // 这不是「测试旧实现」，而是把根因钉死：只要 id 里含窗口内下标，
        // 同一条消息在两个窗口里的 key 必然不同，对账时就会两套 id 并存。
        val all = (0 until 120).map { "e$it" to "第 $it 条" }
        val oldWindow = parseWindowWithIndexId(all, start = 20, count = 80)
        val newWindow = parseWindowWithIndexId(all, start = 45, count = 80)
        val entryId = "e60"
        assertNotEquals(
            oldWindow.first { it.entryId == entryId }.id,
            newWindow.first { it.entryId == entryId }.id
        )
    }

    @Test
    fun `同一 entry 拆出的渲染单元互不撞 key`() {
        val agent = HistoryItemKeys.of("S1", "e1", "agent", 0L)
        val toolA = HistoryItemKeys.of("S1", "e1", "tool_call_a", 0L)
        val toolB = HistoryItemKeys.of("S1", "e1", "tool_call_b", 0L)
        val error = HistoryItemKeys.of("S1", "e1", "error", 0L)

        val ids = listOf(agent, toolA, toolB, error)
        assertEquals("一个 assistant entry 会拆成正文气泡 + 多张工具卡，key 必须各不相同", 4, ids.toSet().size)
    }

    @Test
    fun `不同会话的同一条目也不会撞 key`() {
        assertNotEquals(
            HistoryItemKeys.of("S1", "e1", "agent", 0L),
            HistoryItemKeys.of("S2", "e1", "agent", 0L)
        )
    }

    @Test
    fun `entryId 缺失时退化成确定性锚点而不是下标`() {
        val first = HistoryItemKeys.of("S1", null, "agent", fallbackSeed = 1_760_000_000_000L)
        val second = HistoryItemKeys.of("S1", null, "agent", fallbackSeed = 1_760_000_000_000L)
        val other = HistoryItemKeys.of("S1", null, "agent", fallbackSeed = 1_760_000_000_001L)
        val blank = HistoryItemKeys.of("S1", "  ", "agent", fallbackSeed = 1_760_000_000_000L)

        assertEquals("同样的时间戳必须得到同样的 id（可重算、可对账）", first, second)
        assertNotEquals("不同消息不能共用 id", first, other)
        assertEquals("空白 entryId 等同缺失", first, blank)
    }

    @Test
    fun `窗口前进后对账结果里不会残留重复 key`() {
        // 端到端回归：本地持有旧窗口，服务端回了窗口前移后的快照。
        // 用旧的「下标 id」会在这里产出两条 hist_S1_75_agent（一条配对成功、一条未配对），
        // 也就是线上那条 `Key "hist_..._78_agent" was already used`。
        val all = (0 until 120).map { "e$it" to "第 $it 条" }
        val local = parseWindow(all, start = 0, count = 80)
        val synced = parseWindow(all, start = 5, count = 80)

        val reconciled = SessionSnapshotMerge.reconcileList(local, synced, effectiveRunning = true)
        val guarded = SessionSnapshotMerge.ensureUniqueIds(reconciled)

        val ids = guarded.map { it.id }
        assertEquals("对账结果不能出现重复 id（LazyColumn 会直接崩进程）", ids.size, ids.toSet().size)
        // 内容也不能因为兜底去重而丢失
        assertEquals(reconciled.size, guarded.size)
    }
}
