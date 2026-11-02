package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「未确认提问」的结算规则：既不能提前把刚发的提问抹掉，也不能让它永久挂在列表尾部。
 *
 * 回归背景（v1.8.46）：压缩开头会 `abort()` 掉正在跑的回合，被丢掉的提问留下一条 `entryId`
 * 永远为空的乐观气泡；`reconcileList` 对这类气泡只保护不回收，于是它既抢走下一次同文本提问的
 * 服务端匹配（同一条指令显示两遍），又让 `hasUncommittedUserMessage` 永远为真（本轮永不收尾）。
 */
class PendingPromptSettlementTest {

    private val now = 1_000_000L
    private val grace = 15_000L

    private fun user(id: String, text: String, entryId: String? = null) =
        ChatItem(id = id, entryId = entryId, role = MessageRole.USER, content = text)

    private fun stale(
        items: List<ChatItem>,
        sentAtById: Map<String, Long>,
        snapshotStartedAt: Long = now,
        serverRunning: Boolean = false,
        queuedTexts: Collection<String> = emptyList(),
        at: Long = now
    ) = PendingPromptSettlement.staleUnconfirmed(
        items = items,
        sentAtById = sentAtById,
        snapshotStartedAt = snapshotStartedAt,
        serverRunning = serverRunning,
        queuedTexts = queuedTexts,
        now = at,
        graceMs = grace
    )

    @Test
    fun `a prompt the server never received is withdrawn after the grace period`() {
        val items = listOf(user("opt", "继续"))
        val result = stale(items, mapOf("opt" to now - grace - 1))

        assertEquals(listOf("opt"), result.map { it.id })
    }

    @Test
    fun `a fresh prompt is never withdrawn before the grace period`() {
        val items = listOf(user("opt", "继续"))
        val result = stale(items, mapOf("opt" to now - 1_000L))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a snapshot started before the send cannot prove the prompt was lost`() {
        // 快照是在发送之前发起的：它看不到这条提问是正常的，不能据此判定未送达。
        val items = listOf(user("opt", "继续"))
        val result = stale(
            items = items,
            sentAtById = mapOf("opt" to now - grace - 1),
            snapshotStartedAt = now - grace - 2
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a prompt still running on the server is kept`() {
        val items = listOf(user("opt", "继续"))
        val result = stale(items, mapOf("opt" to now - grace - 1), serverRunning = true)

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a prompt parked during compaction is kept while it sits in the queue`() {
        val items = listOf(user("opt", "继续"))
        val result = stale(
            items = items,
            sentAtById = mapOf("opt" to now - grace - 1),
            queuedTexts = listOf("继续")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a persisted prompt is never treated as unconfirmed`() {
        // 已经落盘（entryId 已回填）的提问不是「乐观气泡」，任何情况下都不能撤回。
        val items = listOf(user("opt", "继续", entryId = "e1"))
        val result = stale(items, mapOf("opt" to now - grace - 1))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a prompt without a recorded send time is left alone`() {
        // 历史恢复 / 旧版本遗留的气泡没有发送时刻，无法判断，保守不动。
        val items = listOf(user("opt", "继续"))
        val result = stale(items, emptyMap())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `steer prefix is normalized when comparing against the queue`() {
        val items = listOf(user("opt", "➔ [引导] 先跑测试"))
        val result = stale(
            items = items,
            sentAtById = mapOf("opt" to now - grace - 1),
            queuedTexts = listOf("先跑测试")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a prompt whose submit request has not returned yet is kept`() {
        // 弱网下 POST 可能十几秒才回来；请求还没返回时结果未定，不能先把它抹掉。
        val items = listOf(user("opt", "继续"))
        val result = PendingPromptSettlement.staleUnconfirmed(
            items = items,
            sentAtById = mapOf("opt" to now - grace - 1),
            snapshotStartedAt = now,
            serverRunning = false,
            queuedTexts = emptyList(),
            now = now,
            graceMs = grace,
            inFlightSendIds = listOf("opt")
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `composer restore is skipped when the same text was already resent`() {
        val items = listOf(user("stale", "继续"), user("fresh", "继续"))
        val staleItem = items.first()

        assertFalse(PendingPromptSettlement.shouldRestoreToComposer(items, staleItem))
    }

    @Test
    fun `composer restore happens when no other copy exists`() {
        val items = listOf(user("stale", "继续"), user("other", "别的话题"))
        val staleItem = items.first()

        assertTrue(PendingPromptSettlement.shouldRestoreToComposer(items, staleItem))
    }
}
