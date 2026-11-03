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
        assertEquals(12, ProcessWindowPolicy.initialLimit(12))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(12, ProcessWindowPolicy.initialLimit(12)))
        assertEquals(40, ProcessWindowPolicy.initialLimit(40))
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, ProcessWindowPolicy.initialLimit(ProcessWindowPolicy.INITIAL_WINDOW))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(83, ProcessWindowPolicy.initialLimit(83)))
    }

    @Test
    fun `超长回合首屏只渲染最近 N 条`() {
        val total = ProcessWindowPolicy.INITIAL_WINDOW + 300
        val limit = ProcessWindowPolicy.initialLimit(total)
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, limit)
        assertEquals(300, ProcessWindowPolicy.hiddenCount(total, limit))
    }

    @Test
    fun `逐次展开直到全部`() {
        val total = ProcessWindowPolicy.INITIAL_WINDOW + 300
        var limit = ProcessWindowPolicy.initialLimit(total)
        var taps = 0
        while (limit < total) {
            val next = ProcessWindowPolicy.nextLimit(limit, total)
            assertEquals("窗口必须单调增长", true, next > limit)
            limit = next
            taps++
        }
        assertEquals(total, limit)
        assertEquals(0, ProcessWindowPolicy.hiddenCount(total, limit))
        // 期望次数由常量推导，改窗口/步长不该再把这条测试弄红
        val expectedTaps = (300 + ProcessWindowPolicy.WINDOW_STEP - 1) / ProcessWindowPolicy.WINDOW_STEP
        assertEquals(expectedTaps, taps)
    }

    @Test
    fun `窗口不会越过总数也不会反向缩回`() {
        assertEquals(10, ProcessWindowPolicy.nextLimit(5, 10))
        assertEquals(10, ProcessWindowPolicy.nextLimit(10, 10))
        assertEquals(0, ProcessWindowPolicy.initialLimit(0))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(0, 0))
    }

    @Test
    fun `异常入参不产出负数窗口`() {
        assertEquals(0, ProcessWindowPolicy.hiddenCount(3, 5))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(-1, -1))
        assertEquals(0, ProcessWindowPolicy.initialLimit(-5))
        assertEquals(5, ProcessWindowPolicy.nextLimit(-3, 5))
    }
}
