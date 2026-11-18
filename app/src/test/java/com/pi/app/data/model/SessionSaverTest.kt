package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SessionSaver` 的线格式回归。
 *
 * 它是**位置化**编码：`save` 输出的下标与 `restore` 里的 `text(n)` 必须一一对应。
 * 这类代码的失败方式很隐蔽 —— 存进去的 `true` 被读到别的字段上，界面只是「状态不对」，
 * 不崩、不报错、也不抛异常。2026-11-06 移除三个从未被赋值的 token 字段时就动过这批下标，
 * 所以把顺序钉成测试。
 *
 * 只调用 `restore(list)`（纯函数），不碰 `save` 需要的 `SaverScope`，保持纯 JVM。
 */
class SessionSaverTest {

    /** 按当前线格式造一份 18 个槽位的存档（后 2 位可空），只覆盖需要断言的位置。 */
    private fun slotValues(vararg overrides: Pair<Int, String>): List<String> {
        val base = MutableList(18) { "" }
        overrides.forEach { (index, value) -> base[index] = value }
        return base
    }

    @Test
    fun `wire order keeps the runtime flags in their documented slots`() {
        val saved = slotValues(
            0 to "s1",
            1 to "标题",
            4 to "D:/myscript/pi-app",
            11 to "7",
            12 to "true", // active
            13 to "true", // awaitingInput
            14 to "true", // isRunning
            15 to "true" // isWorktree
        )

        val restored = SessionSaver.restore(saved)!!

        assertEquals("s1", restored.id)
        assertEquals("标题", restored.name)
        assertEquals("D:/myscript/pi-app", restored.cwd)
        assertEquals(7, restored.messageCount)
        assertTrue(restored.active)
        assertTrue(restored.awaitingInput)
        assertTrue(restored.isRunning)
        assertTrue(restored.isWorktree)
    }

    @Test
    fun `flags default to false when their slots are blank`() {
        val restored = SessionSaver.restore(slotValues(0 to "s1"))!!

        assertTrue(!restored.active)
        assertTrue(!restored.awaitingInput)
        assertTrue(!restored.isRunning)
        assertTrue(!restored.isWorktree)
        assertEquals(0, restored.messageCount)
    }

    @Test
    fun `empty state restores to no session`() {
        assertNull(SessionSaver.restore(emptyList<String>()))
    }

    @Test
    fun `truncated state falls back to defaults instead of crashing`() {
        val restored = SessionSaver.restore(listOf<String>("s1"))!!

        assertEquals("s1", restored.id)
        assertEquals(0, restored.messageCount)
        assertTrue(!restored.isRunning)
    }

    @Test
    fun `legacy 16-slot wire archive restores relation as null`() {
        val legacy = MutableList(16) { "" }.apply { set(0, "s_legacy") }
        val restored = SessionSaver.restore(legacy)!!

        assertEquals("s_legacy", restored.id)
        assertNull(restored.relation)
    }

    @Test
    fun `wire order preserves relation kind and parentSessionId`() {
        val saved = slotValues(
            0 to "s_child",
            16 to "subagent",
            17 to "s_parent"
        )
        val restored = SessionSaver.restore(saved)!!

        assertEquals(SessionRelation(kind = "subagent", parentSessionId = "s_parent"), restored.relation)

        val forkOnly = slotValues(
            0 to "s_fork",
            16 to "fork"
        )
        val restoredFork = SessionSaver.restore(forkOnly)!!
        assertEquals(SessionRelation(kind = "fork", parentSessionId = null), restoredFork.relation)

        val noRelation = slotValues(0 to "s_plain")
        assertNull(SessionSaver.restore(noRelation)!!.relation)
    }
}
