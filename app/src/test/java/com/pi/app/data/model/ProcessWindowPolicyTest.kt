package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 长回合过程组渲染窗口的回归测试。
 *
 * 守护的线上现象：一个回合上百次工具调用的会话（如 CAD 出图流水线），点开过程组时
 * 上百张卡片（连同其中的图片）在同一帧全部组合，手机直接卡住几百毫秒。
 *
 * 另一面的回归：窗口开得太小（曾经是 40）会让普通长回合的过程项被截掉一大截，
 * 看上去就像“App 只同步了最近几步”——pi-web 的 ProcessDetailsGroup 展开时是全量渲染的。
 */
class ProcessWindowPolicyTest {

    @Test
    fun `长回合一次展开就能看全`() {
        // 40 条窗口时代的回归：真实长回合（几十条过程项）被截掉一大截，
        // 看上去就像“App 只同步了最近几步”（pi-web 的 ProcessDetailsGroup 展开是全量的）。
        assertEquals(12, ProcessWindowPolicy.limitFor(12, 0))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(12, ProcessWindowPolicy.limitFor(12, 0)))
        assertEquals(40, ProcessWindowPolicy.limitFor(40, 0))
        // 窗口边界：刚好等于窗口、窗口 +1 各自的表现
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, ProcessWindowPolicy.limitFor(ProcessWindowPolicy.INITIAL_WINDOW, 0))
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, ProcessWindowPolicy.limitFor(ProcessWindowPolicy.INITIAL_WINDOW + 1, 0))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(83, ProcessWindowPolicy.limitFor(83, 0)))
    }

    @Test
    fun `超长回合首屏只渲染最近 N 条`() {
        val total = ProcessWindowPolicy.INITIAL_WINDOW + 300
        val limit = ProcessWindowPolicy.limitFor(total, 0)
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, limit)
        assertEquals(300, ProcessWindowPolicy.hiddenCount(total, limit))
    }

    @Test
    fun `逐次展开直到全部`() {
        val total = ProcessWindowPolicy.INITIAL_WINDOW + 300
        var limit = ProcessWindowPolicy.limitFor(total, 0)
        var steps = 0
        while (limit < total) {
            val next = ProcessWindowPolicy.limitFor(total, steps + 1)
            assertEquals("窗口必须单调增长", true, next > limit)
            limit = next
            steps++
        }
        assertEquals(total, limit)
        assertEquals(0, ProcessWindowPolicy.hiddenCount(total, limit))
        // 期望步数由常量推导，改窗口/步长不该再把这条测试弄红
        val expectedSteps = (300 + ProcessWindowPolicy.WINDOW_STEP - 1) / ProcessWindowPolicy.WINDOW_STEP
        assertEquals(expectedSteps, steps)
    }

    @Test
    fun `回合在跑时长回合的窗口不会冻结在首次那条`() {
        // 回归：以前 remember 的是 initialLimit(total)，回合刚起时只有 1 条，
        // 窗口就永远停在 1 —— 界面显示“12 条消息”却只渲染最新 1 条 + “显示更早的 11 条”。
        assertEquals(1, ProcessWindowPolicy.limitFor(1, 0))
        assertEquals(2, ProcessWindowPolicy.limitFor(2, 0))
        assertEquals(12, ProcessWindowPolicy.limitFor(12, 0))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(12, ProcessWindowPolicy.limitFor(12, 0)))
        // 超长回合：默认就看最近一个窗口，多展开一步再多一段
        val long = ProcessWindowPolicy.INITIAL_WINDOW + ProcessWindowPolicy.WINDOW_STEP + 40
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, ProcessWindowPolicy.limitFor(long, 0))
        assertEquals(
            ProcessWindowPolicy.INITIAL_WINDOW + ProcessWindowPolicy.WINDOW_STEP,
            ProcessWindowPolicy.limitFor(long, 1)
        )
        assertEquals(long, ProcessWindowPolicy.limitFor(long, 2))
        assertEquals("已封顶，不会越过总数", long, ProcessWindowPolicy.limitFor(long, 99))
    }

    @Test
    fun `窗口不会越过总数也不会反向缩回`() {
        val total = ProcessWindowPolicy.INITIAL_WINDOW + 15
        assertEquals(total, ProcessWindowPolicy.limitFor(total, 0))
        assertEquals(total, ProcessWindowPolicy.limitFor(total, 5))
        assertEquals(0, ProcessWindowPolicy.limitFor(0, 0))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(0, 0))
        assertEquals(0, ProcessWindowPolicy.limitFor(0, 3))
    }

    @Test
    fun `异常入参不产出负数窗口`() {
        assertEquals(0, ProcessWindowPolicy.hiddenCount(3, 5))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(-1, -1))
        assertEquals(0, ProcessWindowPolicy.limitFor(-5, 0))
        assertEquals(10, ProcessWindowPolicy.limitFor(10, -3))
        assertEquals(10, ProcessWindowPolicy.limitFor(10, 0, window = 0))
    }
}
