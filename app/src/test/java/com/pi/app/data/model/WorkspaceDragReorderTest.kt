package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左侧栏长按拖动排序的回归测试。
 *
 * 守护的 bug：旧实现「跨过半个行高」就换位并**把位移读数归零**，
 * 换位后行已经下移/上移一整行，读数归零 → 行瞬间回弹半行，拖动时上下跳动。
 *
 * 这里逐条对齐新实现的约定：
 * 1) 换位必须发生在「自己的中心越过邻居的中心」之后；
 * 2) 换位后残位移 = 原读数 ∓ (邻居行高 + 行间距)，视觉位置连续（行高不等也成立）；
 * 3) 一次事件里位移足够大可以连续换多行；
 * 4) 不跨置顶 / 非置顶分组；
 * 5) 落位后的顺序能反推一条 `move-project`，且 anchorKey 永不等于自己。
 */
class WorkspaceDragReorderTest {

    private val noGap = 0f
    private val gap = 6f
    // 行高默认 100；个别用例覆盖成不对称高度
    private var heights: Map<String, Float> = emptyMap()
    private val heightOf: (String) -> Float = { path -> heights[path] ?: 100f }
    private val allSameBand: (String) -> Boolean = { true }

    private fun settle(
        order: List<String>,
        dragged: String,
        offset: Float,
        rowGap: Float = gap,
        sameBand: (String) -> Boolean = allSameBand
    ): WorkspaceDragReorder.Step =
        WorkspaceDragReorder.settle(order, dragged, offset, rowGap, heightOf, sameBand)

    private val order = listOf("d:/a", "d:/b", "d:/c")

    @Test
    fun `below the center crossing nothing moves`() {
        val step = settle(order, "d:/b", 60f)
        assertEquals(order, step.order)
        assertEquals(60f, step.offset, 0.001f)
    }

    @Test
    fun `crossing the next row center swaps once and keeps the visual position`() {
        // 阈值 = 自身半行(50) + 间距(6) + 下一行半行(50) = 106
        val step = settle(order, "d:/b", 120f)
        assertEquals(listOf("d:/a", "d:/c", "d:/b"), step.order)
        // 补偿 = 邻居行高(100) + 间距(6)
        assertEquals(14f, step.offset, 0.001f)
    }

    /** 行高不等时补偿的是**邻居**的高度，不是自己的高度（老实现的第二个坑） */
    @Test
    fun `compensation uses the neighbor height when rows differ`() {
        heights = mapOf("d:/a" to 40f, "d:/b" to 140f, "d:/c" to 40f)
        // 阈值 = 140/2 + 6 + 40/2 = 96
        val step = settle(order, "d:/b", 150f)
        assertEquals(listOf("d:/a", "d:/c", "d:/b"), step.order)
        assertEquals(150f - (40f + 6f), step.offset, 0.001f)

        heights = mapOf("d:/a" to 40f, "d:/b" to 40f, "d:/c" to 140f)
        val down = settle(listOf("d:/b", "d:/c", "d:/a"), "d:/b", 150f)
        assertEquals(listOf("d:/c", "d:/b", "d:/a"), down.order)
        assertEquals(150f - (140f + 6f), down.offset, 0.001f)
    }

    @Test
    fun `dragging upward swaps with the previous row and restores the offset`() {
        // 阈值 = -(50 + 6 + 50) = -106
        val step = settle(order, "d:/c", -120f)
        assertEquals(listOf("d:/a", "d:/c", "d:/b"), step.order)
        assertEquals(-120f + (100f + 6f), step.offset, 0.001f)
    }

    @Test
    fun `a large movement in one event swaps several rows`() {
        // 一次事件走两行：2 * (100 + 6) = 212
        val step = settle(order, "d:/a", 220f)
        assertEquals(listOf("d:/b", "d:/c", "d:/a"), step.order)
        assertEquals(220f - 2 * 106f, step.offset, 0.001f)
    }

    @Test
    fun `swapping back requires crossing a full row again, so it never oscillates`() {
        val down = settle(order, "d:/b", 106f)
        assertEquals(listOf("d:/a", "d:/c", "d:/b"), down.order)
        assertEquals(0f, down.offset, 0.001f)
        // 残留量为 0 时轻微回拖不应立刻换回
        val back = settle(down.order, "d:/b", -2f)
        assertEquals(down.order, back.order)
    }

    @Test
    fun `rows never cross a pin band boundary`() {
        // 只有 d:/a、d:/b 同组，d:/c 属于另一组
        val sameBand: (String) -> Boolean = { it != "d:/c" }
        val step = settle(order, "d:/b", 400f, sameBand = sameBand)
        assertEquals(order, step.order)
        assertEquals(400f, step.offset, 0.001f)
    }

    @Test
    fun `top and bottom of the list just clamp`() {
        assertEquals(order, settle(order, "d:/a", -400f).order)
        assertEquals(order, settle(order, "d:/c", 400f).order)
    }

    @Test
    fun `unknown path is returned untouched`() {
        val step = settle(order, "d:/missing", 500f)
        assertEquals(order, step.order)
        assertEquals(500f, step.offset, 0.001f)
    }

    @Test
    fun `zero gap still swaps once per row height`() {
        val step = settle(order, "d:/a", 105f, rowGap = noGap)
        assertEquals(listOf("d:/b", "d:/a", "d:/c"), step.order)
        assertEquals(5f, step.offset, 0.001f)
    }

    @Test
    fun `anchor points to the previous neighbor with position after`() {
        val anchor = WorkspaceDragReorder.anchorFor(listOf("d:/a", "d:/c", "d:/b"), "d:/b")
        assertEquals("d:/c", anchor?.anchorKey)
        assertEquals(ProjectMovePlan.AFTER, anchor?.position)
    }

    @Test
    fun `anchor points to the next neighbor with position before when dropped first`() {
        val anchor = WorkspaceDragReorder.anchorFor(listOf("d:/b", "d:/a", "d:/c"), "d:/b")
        assertEquals("d:/a", anchor?.anchorKey)
        assertEquals(ProjectMovePlan.BEFORE, anchor?.position)
    }

    @Test
    fun `anchor is null when the band cannot express the move`() {
        assertNull(WorkspaceDragReorder.anchorFor(listOf("d:/a"), "d:/a"))
        assertNull(WorkspaceDragReorder.anchorFor(listOf("d:/a", "d:/b"), "d:/missing"))
        assertNull(WorkspaceDragReorder.anchorFor(emptyList(), "d:/a"))
    }

    @Test
    fun `anchor never equals the dragged key for any drop position`() {
        for (drop in order.indices) {
            val finalOrder = order.filterNot { it == "d:/b" }.toMutableList()
            finalOrder.add(drop, "d:/b")
            val anchor = WorkspaceDragReorder.anchorFor(finalOrder, "d:/b")
            assertTrue("drop $drop should be expressible", anchor != null)
            assertNotEquals("d:/b", anchor!!.anchorKey)
            assertTrue(anchor.position == ProjectMovePlan.BEFORE || anchor.position == ProjectMovePlan.AFTER)
        }
    }

    @Test
    fun `canSwapUp returns true only when predecessor exists and is in the same band`() {
        val testOrder = listOf("d:/pin1", "d:/pin2", "d:/unpin1", "d:/unpin2")
        val isPinned: (String) -> Boolean = { it.contains("pin") && !it.contains("unpin") }

        // pin1 已经在置顶顶端
        assertTrue(!WorkspaceDragReorder.canSwapUp(testOrder, "d:/pin1") { isPinned(it) })
        // pin2 上面是 pin1，同为置顶分组
        assertTrue(WorkspaceDragReorder.canSwapUp(testOrder, "d:/pin2") { isPinned(it) })
        // unpin1 上面是 pin2，不同分组，不能向上跨组
        assertTrue(!WorkspaceDragReorder.canSwapUp(testOrder, "d:/unpin1") { !isPinned(it) })
        // unpin2 上面是 unpin1，同为非置顶分组
        assertTrue(WorkspaceDragReorder.canSwapUp(testOrder, "d:/unpin2") { !isPinned(it) })
        // 不存在的项
        assertTrue(!WorkspaceDragReorder.canSwapUp(testOrder, "d:/missing") { true })
    }

    @Test
    fun `canSwapDown returns true only when successor exists and is in the same band`() {
        val testOrder = listOf("d:/pin1", "d:/pin2", "d:/unpin1", "d:/unpin2")
        val isPinned: (String) -> Boolean = { it.contains("pin") && !it.contains("unpin") }

        // pin1 下面是 pin2，同为置顶分组
        assertTrue(WorkspaceDragReorder.canSwapDown(testOrder, "d:/pin1") { isPinned(it) })
        // pin2 下面是 unpin1，不同分组，不能向下跨组
        assertTrue(!WorkspaceDragReorder.canSwapDown(testOrder, "d:/pin2") { isPinned(it) })
        // unpin1 下面是 unpin2，同为非置顶分组
        assertTrue(WorkspaceDragReorder.canSwapDown(testOrder, "d:/unpin1") { !isPinned(it) })
        // unpin2 已经在非置顶底端
        assertTrue(!WorkspaceDragReorder.canSwapDown(testOrder, "d:/unpin2") { !isPinned(it) })
        // 不存在的项
        assertTrue(!WorkspaceDragReorder.canSwapDown(testOrder, "d:/missing") { true })
    }

    @Test
    fun `calculateScrollSpeed returns zero in the middle zone`() {
        val speed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 300f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(0f, speed, 0.001f)
    }

    @Test
    fun `calculateScrollSpeed accelerates towards top edge when canScrollUp is true`() {
        // 刚好在顶部感应区边界 (100f): 起步速度
        val entrySpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 100f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(-200f, entrySpeed, 0.001f)

        // 顶部中段 (50f): 线性插值 200 + 0.5 * 600 = 500
        val midSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 50f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(-500f, midSpeed, 0.001f)

        // 达到或超出顶部 (<= 0f): 最大向上速度
        val topSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 0f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(-800f, topSpeed, 0.001f)

        val overshootTopSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = -50f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(-800f, overshootTopSpeed, 0.001f)
    }

    @Test
    fun `calculateScrollSpeed accelerates towards bottom edge when canScrollDown is true`() {
        // viewport = 1000, edgeZone = 100, bottom trigger = 900
        val entrySpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 900f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(200f, entrySpeed, 0.001f)

        // 950f: 线性插值 200 + 0.5 * 600 = 500
        val midSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 950f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(500f, midSpeed, 0.001f)

        // 1000f 或超出底部: 最大向下速度
        val bottomSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 1000f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(800f, bottomSpeed, 0.001f)

        val overshootBottomSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 1100f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(800f, overshootBottomSpeed, 0.001f)
    }

    @Test
    fun `calculateScrollSpeed respects canScroll flags and handles invalid bounds`() {
        // 在顶部但 canScrollUp = false
        val noUp = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 20f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = false,
            canScrollDown = true
        )
        assertEquals(0f, noUp, 0.001f)

        // 在底部但 canScrollDown = false
        val noDown = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 980f,
            viewportHeight = 1000f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = false
        )
        assertEquals(0f, noDown, 0.001f)

        // 视口高度无效
        val invalidViewport = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 20f,
            viewportHeight = 0f,
            edgeZonePx = 100f,
            maxSpeedPxPerSec = 800f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(0f, invalidViewport, 0.001f)

        // 小视口（高 100，感应区 80），有效感应区自动截断至 50，避免上下感应区重合冲突
        // fingerY = 50 刚好位于正中间，两侧都不触发
        val midSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 50f,
            viewportHeight = 100f,
            edgeZonePx = 80f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(0f, midSpeed, 0.001f)

        // fingerY = 25 位于前半段 (25 < 50)，按 effectiveZone=50 线性插值
        val upSpeed = WorkspaceDragReorder.calculateScrollSpeed(
            fingerY = 25f,
            viewportHeight = 100f,
            edgeZonePx = 80f,
            maxSpeedPxPerSec = 800f,
            minSpeedPxPerSec = 200f,
            canScrollUp = true,
            canScrollDown = true
        )
        assertEquals(-500f, upSpeed, 0.001f)
    }

    @Test
    fun `clampResidualOffset restricts overshoot only when cannot swap further`() {
        val maxOvershoot = 50f

        // 能向上换位时，不限制负向位移
        assertEquals(-200f, WorkspaceDragReorder.clampResidualOffset(-200f, canSwapUp = true, canSwapDown = false, maxOvershoot), 0.001f)
        // 不能向上换位时，负向位移被截断至 -maxOvershoot
        assertEquals(-50f, WorkspaceDragReorder.clampResidualOffset(-200f, canSwapUp = false, canSwapDown = false, maxOvershoot), 0.001f)
        assertEquals(-30f, WorkspaceDragReorder.clampResidualOffset(-30f, canSwapUp = false, canSwapDown = false, maxOvershoot), 0.001f)

        // 能向下换位时，不限制正向位移
        assertEquals(200f, WorkspaceDragReorder.clampResidualOffset(200f, canSwapUp = false, canSwapDown = true, maxOvershoot), 0.001f)
        // 不能向下换位时，正向位移被截断至 maxOvershoot
        assertEquals(50f, WorkspaceDragReorder.clampResidualOffset(200f, canSwapUp = false, canSwapDown = false, maxOvershoot), 0.001f)
        assertEquals(30f, WorkspaceDragReorder.clampResidualOffset(30f, canSwapUp = false, canSwapDown = false, maxOvershoot), 0.001f)

        // 传入负数 maxOvershoot 时自动取绝对值保护
        assertEquals(-50f, WorkspaceDragReorder.clampResidualOffset(-200f, canSwapUp = false, canSwapDown = false, -50f), 0.001f)
        assertEquals(50f, WorkspaceDragReorder.clampResidualOffset(200f, canSwapUp = false, canSwapDown = false, -50f), 0.001f)
    }
}
