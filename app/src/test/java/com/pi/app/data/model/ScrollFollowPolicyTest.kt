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
 * 3) 跟随用缓动动画追增量，每帧被新增量打断重开 = 底部闪烁（pi-web 是瞬时贴底）；
 * 4) 方向判定把「跨行」当成用户上翻 → 跟随走一下停一下。
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
    fun `跨行往下滚不会被误判成用户上翻`() {
        // 我们自己的跟随一跨行（工具卡 / 过程组 / 最终答复都是独立的行）就会让索引变大，
        // 而首条的位移会从「整行都滚过去了」的负数跳回 ~0。
        // 旧分支把「索引变大」当成上翻，于是跟随每跨一行就自我脱离一帧 —— 底部看起来在抖。
        val direction = ScrollFollowPolicy.scrollDirections(
            prevIndex = 3, prevOffset = -820,
            index = 4, offset = -6
        )
        assertTrue(direction.scrolledDown)
        assertFalse(direction.scrolledUp)
        assertTrue(
            "跨行就是往下滚，原本贴着底部就应该继续跟随",
            ScrollFollowPolicy.resolveAttached(
                wasAttached = true,
                scrolledUp = direction.scrolledUp,
                scrolledDown = direction.scrolledDown,
                distanceToBottom = 400f
            )
        )
    }

    @Test
    fun `跨行往上滚才算用户上翻`() {
        val direction = ScrollFollowPolicy.scrollDirections(
            prevIndex = 4, prevOffset = -6,
            index = 3, offset = -820
        )
        assertTrue(direction.scrolledUp)
        assertFalse(direction.scrolledDown)
        assertFalse(
            ScrollFollowPolicy.resolveAttached(true, direction.scrolledUp, direction.scrolledDown, 900f)
        )
    }

    @Test
    fun `同一行内的方向按首条可见项位移变小判往下`() {
        assertTrue(ScrollFollowPolicy.scrollDirections(2, -300, 2, -420).scrolledDown)
        assertTrue(ScrollFollowPolicy.scrollDirections(2, -420, 2, -300).scrolledUp)
        assertFalse(ScrollFollowPolicy.scrollDirections(2, -420, 2, -420).changed)
        assertFalse(
            "首帧没有上一帧可比，不能凭空猜方向",
            ScrollFollowPolicy.scrollDirections(-1, 0, 0, 0).changed
        )
    }
}
