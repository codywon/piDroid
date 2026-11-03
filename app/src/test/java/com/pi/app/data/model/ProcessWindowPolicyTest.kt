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
    fun `短回合不做任何折叠`() {
        assertEquals(12, ProcessWindowPolicy.initialLimit(12))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(12, ProcessWindowPolicy.initialLimit(12)))
        assertEquals(40, ProcessWindowPolicy.initialLimit(40))
        // 真实长回合（几十条过程项）必须一次展开看全，不能被截断
        assertEquals(137, ProcessWindowPolicy.initialLimit(137))
        assertEquals(0, ProcessWindowPolicy.hiddenCount(137, ProcessWindowPolicy.initialLimit(137)))
    }

    @Test
    fun `超长回合首屏只渲染最近 N 条`() {
        val total = 437
        val limit = ProcessWindowPolicy.initialLimit(total)
        assertEquals(ProcessWindowPolicy.INITIAL_WINDOW, limit)
        assertEquals(237, ProcessWindowPolicy.hiddenCount(total, limit))
    }

    @Test
    fun `逐次展开直到全部`() {
        val total = 437
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
        assertEquals(3, taps) // 200 -> 300 -> 400 -> 437
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
