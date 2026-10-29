package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目排序（ui-state `move-project`）回归测试。
 *
 * 守护的 bug：手机端「上移 / 下移」项目时锚点取成了**换位之后**的邻居，
 * 于是 `anchorKey == projectKey`，pi-web 回 400
 * `{"error":"projectKey and anchorKey must differ"}`（同时缺 `position` 也会 400）。
 *
 * 这里逐条对齐 pi-web `SessionSidebar` 的 moveAnchor 逻辑与服务端校验：
 * 1) 锚点必须是移动前的同分组相邻项；
 * 2) position 只能是 "before"（上移）/ "after"（下移）；
 * 3) 分组首/末不可移动；
 * 4) `add` 只携带「同分组内尚未进入 projectOrder」的 key，用来保持 PC 端展示顺序。
 */
class ProjectMovePlannerTest {

    private val band = listOf("d:/myscript/worktrees", "d:/myscript/pi-app", "d:/hotel_project/hotel_radar")

    @Test
    fun `move up anchors to the previous item with position before`() {
        val plan = ProjectMovePlanner.plan(band, "d:/myscript/pi-app", ProjectMoveDirection.UP)
        assertEquals("d:/myscript/worktrees", plan?.anchorKey)
        assertEquals("before", plan?.position)
        assertEquals("d:/myscript/pi-app", plan?.projectKey)
    }

    @Test
    fun `move down anchors to the next item with position after`() {
        val plan = ProjectMovePlanner.plan(band, "d:/myscript/pi-app", ProjectMoveDirection.DOWN)
        assertEquals("d:/hotel_project/hotel_radar", plan?.anchorKey)
        assertEquals("after", plan?.position)
    }

    /** 旧实现的失败用例：换位后再取邻居，锚点 == 自己 -> 服务端 400 */
    @Test
    fun `anchor never equals projectKey`() {
        for (key in band) {
            for (dir in ProjectMoveDirection.values()) {
                val plan = ProjectMovePlanner.plan(band, key, dir)
                if (plan != null) {
                    assertTrue("anchor must differ from projectKey for $key/$dir", plan.anchorKey != plan.projectKey)
                    assertTrue(plan.position == "before" || plan.position == "after")
                }
            }
        }
    }

    @Test
    fun `first item cannot move up and last item cannot move down`() {
        assertNull(ProjectMovePlanner.plan(band, "d:/myscript/worktrees", ProjectMoveDirection.UP))
        assertNull(ProjectMovePlanner.plan(band, "d:/hotel_project/hotel_radar", ProjectMoveDirection.DOWN))
        // 反向仍然合法
        assertEquals(
            "d:/myscript/pi-app",
            ProjectMovePlanner.plan(band, "d:/myscript/worktrees", ProjectMoveDirection.DOWN)?.anchorKey
        )
    }

    @Test
    fun `single item band cannot move at all`() {
        val single = listOf("d:/myscript/pi-app")
        assertNull(ProjectMovePlanner.plan(single, "d:/myscript/pi-app", ProjectMoveDirection.UP))
        assertNull(ProjectMovePlanner.plan(single, "d:/myscript/pi-app", ProjectMoveDirection.DOWN))
    }

    @Test
    fun `unknown key returns null instead of a self anchored request`() {
        assertNull(ProjectMovePlanner.plan(band, "d:/not/in/band", ProjectMoveDirection.UP))
        assertNull(ProjectMovePlanner.plan(emptyList(), "d:/myscript/pi-app", ProjectMoveDirection.DOWN))
    }

    /** 置顶分组内的移动只看置顶分组（跨分组服务端不接受） */
    @Test
    fun `pinned band keeps its own adjacency`() {
        val pinned = listOf("d:/a", "d:/b")
        val plan = ProjectMovePlanner.plan(pinned, "d:/b", ProjectMoveDirection.UP)
        assertEquals("d:/a", plan?.anchorKey)
        assertEquals("before", plan?.position)
    }

    @Test
    fun `collectAddKeys only carries unordered band keys and skips move participants`() {
        val ordered = listOf("d:/myscript/pi-app")
        val add = ProjectMovePlanner.collectAddKeys(
            band,
            ordered,
            "d:/myscript/pi-app",
            "d:/myscript/worktrees"
        )
        assertEquals(listOf("d:/hotel_project/hotel_radar"), add)
    }

    @Test
    fun `collectAddKeys is capped at the server limit`() {
        val wide = (1..1200).map { "d:/p$it" }
        val add = ProjectMovePlanner.collectAddKeys(wide, emptyList())
        assertEquals(ProjectMovePlanner.MAX_ADD_KEYS, add.size)
        assertEquals("d:/p1200", add.last())
        assertFalse(add.contains("d:/p1"))
    }

    @Test
    fun `collectAddKeys keeps display order`() {
        val add = ProjectMovePlanner.collectAddKeys(band, listOf("d:/myscript/pi-app"))
        assertEquals(listOf("d:/myscript/worktrees", "d:/hotel_project/hotel_radar"), add)
    }
}
