package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区路径写法兼容的回归测试（守护截图里的真实故障）。
 *
 * 线上现象：左侧「工作区项目」里 `termius` 出现两行 ——
 * 一行 `D:/myscript/termius`（1 个会话，来自会话/projectRoot 的正斜杠写法），
 * 一行 `D:\myscript\termius`（0 个会话，App 侧 `/api/cwd/browse` 拿到的盘符写法）。
 * 同一个目录被当成两个项目，会话只算到其中一行。
 *
 * 以 pi-web 的实现为基准：
 * - 服务端 `projectKey`（chunk 74244 `z`）：分隔符归一 + 去尾部斜杠 + win32 小写；
 * - 客户端分组（`page-*.js` 的 `f()/m()`）：分组用 `projectKey`，展示用 `projectRoot`。
 */
class WorkspacePathsTest {

    // ---- 截图场景：反斜杠写法 与 正斜杠写法 必须是同一个项目 ----

    @Test
    fun `backslash and forward slash paths from the two entry points share one key`() {
        assertEquals(
            WorkspacePaths.identityKey("D:/myscript/termius"),
            WorkspacePaths.identityKey("D:\\myscript\\termius")
        )
        assertTrue(WorkspacePaths.samePath("D:/myscript/termius", "D:\\myscript\\termius"))
    }

    @Test
    fun `drive letter case and trailing separators are ignored on windows paths`() {
        val canonical = WorkspacePaths.identityKey("D:/myscript/termius")
        assertEquals(canonical, WorkspacePaths.identityKey("d:\\myscript\\termius"))
        assertEquals(canonical, WorkspacePaths.identityKey("D:/myscript/termius/"))
        assertEquals(canonical, WorkspacePaths.identityKey("  D:\\myscript\\termius\\\\  "))
    }

    @Test
    fun `unc paths are windows paths`() {
        assertTrue(WorkspacePaths.isWindowsPath("\\\\server\\share\\proj"))
        assertTrue(WorkspacePaths.samePath("\\\\server\\share\\proj", "//server/share/proj"))
    }

    @Test
    fun `posix paths keep their case`() {
        // Linux 上 /srv/App 与 /srv/app 是两个目录，不能合并（pi-web on posix 同样不小写）。
        assertFalse(WorkspacePaths.samePath("/srv/App", "/srv/app"))
        assertNotEquals(WorkspacePaths.identityKey("/srv/App"), WorkspacePaths.identityKey("/srv/app"))
        // 但尾部斜杠仍然要忽略
        assertTrue(WorkspacePaths.samePath("/srv/app/", "/srv/app"))
    }

    // ---- 展示写法：不把数据改成另一种写法，只统一分隔符 ----

    @Test
    fun `display path folds backslashes and drops trailing separators`() {
        assertEquals("D:/myscript/termius", WorkspacePaths.displayPath("D:\\myscript\\termius"))
        assertEquals("D:/myscript/termius", WorkspacePaths.displayPath("D:/myscript/termius/"))
        assertEquals("/srv/app", WorkspacePaths.displayPath("/srv/app/"))
        assertEquals("", WorkspacePaths.displayPath("   "))
        assertEquals("", WorkspacePaths.displayPath(null))
    }

    @Test
    fun `roots survive trimming`() {
        assertEquals("/", WorkspacePaths.displayPath("/"))
        assertEquals("D:", WorkspacePaths.displayPath("D:\\"))
        assertEquals("D:", WorkspacePaths.displayPath("D:/"))
        assertTrue(WorkspacePaths.samePath("D:\\", "D:"))
    }

    // ---- 分组：会话侧 + 自定义目录侧 合成一行 ----

    private fun session(id: String, root: String?, modified: String? = "2026-10-28T10:00:00Z") = Session(
        id = id,
        projectRoot = root,
        cwd = root,
        modified = modified
    )

    @Test
    fun `screenshot scenario collapses into one workspace row`() {
        val sessions = listOf(session("s1", "D:/myscript/termius"))
        val groups = groupWorkspaces(sessions, listOf("D:\\myscript\\termius"))

        assertEquals(1, groups.size)
        val group = groups.single()
        // 展示路径沿用会话侧（projectRoot）的写法，和列表里其它项目一致
        assertEquals("D:/myscript/termius", group.path)
        assertEquals(1, group.sessions.size)
    }

    @Test
    fun `custom directory without sessions still shows up`() {
        val groups = groupWorkspaces(emptyList(), listOf("D:\\myscript\\termius"))
        assertEquals(1, groups.size)
        assertEquals("D:/myscript/termius", groups.single().path)
        assertEquals(0, groups.single().sessions.size)
    }

    @Test
    fun `duplicate legacy spellings of one directory collapse into a single empty group`() {
        // 旧版本把两种写法都存进过自定义目录集合
        val groups = groupWorkspaces(
            emptyList(),
            listOf("D:\\myscript\\termius", "d:/myscript/termius/")
        )
        assertEquals(1, groups.size)
        assertEquals("D:/myscript/termius", groups.single().path)
    }

    @Test
    fun `sessions whose roots differ only by separator land in the same group`() {
        val sessions = listOf(
            session("s1", "D:/myscript/termius", "2026-10-28T09:00:00Z"),
            session("s2", "D:\\myscript\\termius", "2026-10-28T12:00:00Z")
        )
        val groups = groupWorkspaces(sessions)
        assertEquals(1, groups.size)
        assertEquals(2, groups.single().sessions.size)
        // 展示路径取组内最近修改的那条（pi-web 客户端 m() 同样取最新 root）
        assertEquals("D:/myscript/termius", groups.single().path)
    }

    @Test
    fun `different projects stay separate`() {
        val sessions = listOf(
            session("s1", "D:/myscript/termius"),
            session("s2", "D:/myscript/pi-app")
        )
        val groups = groupWorkspaces(sessions, listOf("D:\\myscript\\sqlrest"))
        assertEquals(3, groups.size)
    }

    @Test
    fun `session workspace path keeps a readable display form`() {
        val s = Session(id = "s1", projectRoot = "D:\\myscript\\termius")
        assertEquals("D:/myscript/termius", s.workspacePath)
        assertEquals("termius", s.workspaceName)
        assertEquals("d:/myscript/termius", s.workspaceKey)

        // 没有 projectRoot / cwd 时退回 projectKey（pi-web 的 projectKey 本身是全小写反斜杠写法）
        val fromKey = Session(id = "s2", projectKey = "d:\\myscript\\termius")
        assertEquals("d:/myscript/termius", fromKey.workspacePath)
        assertTrue(WorkspacePaths.samePath(fromKey.workspacePath, "D:/myscript/termius"))
    }

    // ---- 工作区初始选区恢复与兜底策略 ----

    @Test
    fun `resolveInitialWorkspaceSelection respects preferred path regardless of slash or case`() {
        val sortedList = listOf("D:/myscript/pi-app", "D:/myscript/termius")

        // 候选是反斜杠且小写盘符
        val selected = resolveInitialWorkspaceSelection("d:\\myscript\\termius", sortedList)
        assertEquals("D:/myscript/termius", selected)
    }

    @Test
    fun `resolveInitialWorkspaceSelection keeps pinned workspace key`() {
        val sortedList = listOf("D:/myscript/pi-app", "D:/myscript/termius")
        val selected = resolveInitialWorkspaceSelection(PINNED_WORKSPACE_KEY, sortedList)
        assertEquals(PINNED_WORKSPACE_KEY, selected)
    }

    @Test
    fun `resolveInitialWorkspaceSelection falls back to first sorted item instead of blindly picking other projects`() {
        // 用户排在第 1 位的是 pi-app，哪怕后面有历史会话数更多的 termius，在候选失效时也必须回退到第一项
        val sortedList = listOf("D:/myscript/pi-app", "D:/myscript/termius")

        // 候选为空
        assertEquals("D:/myscript/pi-app", resolveInitialWorkspaceSelection("", sortedList))
        assertEquals("D:/myscript/pi-app", resolveInitialWorkspaceSelection("   ", sortedList))
        assertEquals("D:/myscript/pi-app", resolveInitialWorkspaceSelection(null, sortedList))

        // 候选项目已被删除
        val deletedProject = "D:/myscript/old-deleted-project"
        assertEquals("D:/myscript/pi-app", resolveInitialWorkspaceSelection(deletedProject, sortedList))
    }

    @Test
    fun `resolveInitialWorkspaceSelection returns empty string when sorted list is empty`() {
        assertEquals("", resolveInitialWorkspaceSelection("", emptyList()))
        assertEquals("", resolveInitialWorkspaceSelection("D:/myscript/pi-app", emptyList()))
        assertEquals(PINNED_WORKSPACE_KEY, resolveInitialWorkspaceSelection(PINNED_WORKSPACE_KEY, emptyList()))
    }

    // ---- 冷启动 / 返回列表的工作区选区仲裁（回归：每次重进 App 都落到 termius）----

    /**
     * 线上故障：冷启动首帧只有离线自定义目录（用户曾浏览添加过 `termius`），
     * 上面那句 `if (isLoading && sessions.isEmpty()) return` 门禁又对静默拉取无效，
     * 于是「上次停留的工作区」被误判为已失效，选区被钉到 termius。
     */
    @Test
    fun `workspace arbitration defers until the first full snapshot arrives`() {
        val decision = decideWorkspaceSelection(
            currentSelection = "D:/myscript/pi-app",
            hint = "D:/myscript/pi-app",
            // 首帧只有离线自定义目录
            sortedWorkspacePaths = listOf("D:/myscript/termius"),
            hintAlreadyApplied = false,
            dataReady = false
        )
        assertEquals(WorkspaceSelectionDecision.Defer, decision)
    }

    @Test
    fun `workspace arbitration applies the remembered workspace in the list spelling`() {
        val list = listOf("D:/myscript/pi-app", "D:/myscript/termius")
        val decision = decideWorkspaceSelection(
            currentSelection = "D:/myscript/termius",
            hint = "d:\\myscript\\pi-app",
            sortedWorkspacePaths = list,
            hintAlreadyApplied = false,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.ApplyHint("D:/myscript/pi-app"), decision)
    }

    @Test
    fun `workspace arbitration keeps the pinned zone hint`() {
        val decision = decideWorkspaceSelection(
            currentSelection = "",
            hint = PINNED_WORKSPACE_KEY,
            sortedWorkspacePaths = listOf("D:/myscript/pi-app"),
            hintAlreadyApplied = false,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.ApplyHint(PINNED_WORKSPACE_KEY), decision)
    }

    @Test
    fun `workspace arbitration does not fight a manual switch after the hint was applied`() {
        val decision = decideWorkspaceSelection(
            currentSelection = "D:/myscript/termius",
            hint = "D:/myscript/pi-app",
            sortedWorkspacePaths = listOf("D:/myscript/pi-app", "D:/myscript/termius"),
            hintAlreadyApplied = true,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.Keep, decision)
    }

    @Test
    fun `workspace arbitration falls back only when the remembered workspace is really gone`() {
        val list = listOf("D:/myscript/pi-app", "D:/myscript/termius")
        val decision = decideWorkspaceSelection(
            currentSelection = "D:/myscript/deleted-project",
            hint = "D:/myscript/deleted-project",
            sortedWorkspacePaths = list,
            hintAlreadyApplied = false,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.Fallback("D:/myscript/pi-app"), decision)
    }

    @Test
    fun `workspace arbitration keeps a selection the data has confirmed`() {
        val decision = decideWorkspaceSelection(
            // 没有持久化记忆（例如刚断开重连后清过本地配置）
            currentSelection = "D:/myscript/termius",
            hint = "",
            sortedWorkspacePaths = listOf("D:/myscript/pi-app", "D:/myscript/termius"),
            hintAlreadyApplied = false,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.Keep, decision)
    }

    @Test
    fun `workspace arbitration keeps the selection when nothing is known yet`() {
        val decision = decideWorkspaceSelection(
            currentSelection = "",
            hint = "",
            sortedWorkspacePaths = emptyList(),
            hintAlreadyApplied = false,
            dataReady = true
        )
        assertEquals(WorkspaceSelectionDecision.Keep, decision)
    }

    @Test
    fun `workspaces comparator prioritizes latest modified over session count (align with pi-web)`() {
        val oldProjectManySessions = WorkspaceInfo(
            path = "D:/myscript/old-archive",
            name = "old-archive",
            sessionCount = 200,
            runningCount = 0,
            latestModified = "2026-10-01T12:00:00Z"
        )
        val recentProjectFewSessions = WorkspaceInfo(
            path = "D:/myscript/recent-active",
            name = "recent-active",
            sessionCount = 3,
            runningCount = 0,
            latestModified = "2026-11-04T01:30:00Z"
        )
        val runningProject = WorkspaceInfo(
            path = "D:/myscript/running-task",
            name = "running-task",
            sessionCount = 1,
            runningCount = 1,
            latestModified = "2026-11-04T00:00:00Z"
        )

        val list = listOf(oldProjectManySessions, recentProjectFewSessions, runningProject)
        val sorted = list.sortedWith(
            compareByDescending<WorkspaceInfo> { it.runningCount }
                .thenByDescending { it.latestModified }
                .thenByDescending { it.sessionCount }
        )

        assertEquals("D:/myscript/running-task", sorted[0].path)
        assertEquals("D:/myscript/recent-active", sorted[1].path)
        assertEquals("D:/myscript/old-archive", sorted[2].path)
    }
}
