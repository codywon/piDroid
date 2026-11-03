package com.pi.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「是否跟随聊天底部」的回归测试。
 *
 * 守护的线上现象（对齐 pi-web 之前）：
 * 1) 重新进入会话时不在底部（首屏只定位一次，之后新内容补下来没人重新锚定）；
 * 2) 任务在跑时，用户往上翻历史会被每个流式增量拽回底部（App 用 `isAgentBusy` 当“该跟随”，
 *    pi-web 用“用户是否贴底”）；
 * 3) 落差几屏时逐屏动画 = “自动刷屏往下滚”（pi-web 的 `scrollTo` 是一次性跳）。
 */
class ScrollFollowPolicyTest {

    private val tail = ScrollFollowPolicy.TAIL_TOLERANCE_PX
    private val reattach = ScrollFollowPolicy.REATTACH_TOLERANCE_PX

    @Test
    fun `贴着底部就跟随`() {
        assertTrue(ScrollFollowPolicy.resolveAttached(false, scrolledUp = false, scrolledDown = false, distanceToBottom = 0f))
        assertTrue(ScrollFollowPolicy.resolveAttached(false, false, false, tail))
        assertFalse(ScrollFollowPolicy.resolveAttached(false, false, false, tail + 1f))
    }

    @Test
    fun `用户往上滚就立刻脱离，哪怕原本贴着底部`() {
        assertFalse(ScrollFollowPolicy.resolveAttached(true, scrolledUp = true, scrolledDown = false, distanceToBottom = 2_000f))
        // 上滚后停在离底很近的位置，下一帧没有方向变化也不能自作主张跟随
        assertFalse(ScrollFollowPolicy.resolveAttached(false, false, false, reattach + 200f))
    }

    @Test
    fun `向下滚回底部附近会重新接上`() {
        assertTrue(ScrollFollowPolicy.resolveAttached(false, false, scrolledDown = true, distanceToBottom = reattach))
        assertFalse(
            "离底部超过重新接上的容差时仍保持脱离",
            ScrollFollowPolicy.resolveAttached(false, false, true, reattach + 1f)
        )
    }

    @Test
    fun `内容在下方自然增长不会关掉跟随`() {
        // 用户没动过（scrolledUp=false），只是新内容让“离底距离”变大 → 保持跟随
        assertTrue(ScrollFollowPolicy.resolveAttached(true, false, false, distanceToBottom = 5_000f))
    }

    @Test
    fun `悬浮按钮只在离开底部时出现`() {
        assertFalse(ScrollFollowPolicy.shouldShowScrollToLatest(0f))
        assertFalse(ScrollFollowPolicy.shouldShowScrollToLatest(tail))
        assertTrue(ScrollFollowPolicy.shouldShowScrollToLatest(tail + 1f))
        assertTrue(ScrollFollowPolicy.shouldShowScrollToLatest(ScrollFollowPolicy.FAR_FROM_BOTTOM_PX))
    }

    @Test
    fun `长落差直接瞬移，短落差才平滑`() {
        val viewport = 1_800f
        assertFalse(ScrollFollowPolicy.needsInstantJump(120f, viewport))
        assertFalse(ScrollFollowPolicy.needsInstantJump(viewport, viewport))
        assertTrue(ScrollFollowPolicy.needsInstantJump(viewport + 1f, viewport))
        assertTrue(
            "最后一条还没渲染出来时无法算距离，必须直接跳而不是逐屏动画",
            ScrollFollowPolicy.needsInstantJump(ScrollFollowPolicy.FAR_FROM_BOTTOM_PX, viewport)
        )
        assertTrue("视口高度未知时保守地直接跳", ScrollFollowPolicy.needsInstantJump(10f, 0f))
    }
}
