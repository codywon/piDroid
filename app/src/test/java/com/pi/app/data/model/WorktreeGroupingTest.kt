package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeGroupingTest {

    @Test
    fun `sessions from different worktrees are grouped under the same repository projectKey`() {
        val sessionMain = Session(
            id = "sess-1",
            cwd = "D:/myscript/pi-app",
            projectRoot = "D:/myscript/pi-app",
            projectKey = "d:/myscript/pi-app",
            branch = "main",
            isWorktree = false
        )
        val sessionFeature = Session(
            id = "sess-2",
            cwd = "D:/myscript/pi-app-worktrees/feat-katex",
            projectRoot = "D:/myscript/pi-app",
            projectKey = "d:/myscript/pi-app",
            branch = "feat/katex",
            isWorktree = true
        )

        val groups = groupWorkspaces(listOf(sessionMain, sessionFeature))
        assertEquals(1, groups.size)
        val group = groups[0]
        assertEquals("d:/myscript/pi-app", group.key)
        assertEquals(2, group.sessions.size)
        assertTrue(group.sessions.any { it.id == "sess-1" && !it.isWorktree })
        assertTrue(group.sessions.any { it.id == "sess-2" && it.isWorktree && it.branch == "feat/katex" })
    }

    @Test
    fun `parseWorktreesResult parses worktree list and flags correctly`() {
        val jsonStr = """
            {
              "projectRoot": "D:/myscript/pi-app",
              "projectKey": "d:/myscript/pi-app",
              "isGit": true,
              "isTopLevel": true,
              "currentWorktreePath": "D:/myscript/pi-app",
              "worktrees": [
                {
                  "path": "D:/myscript/pi-app",
                  "branch": "main",
                  "isCurrent": true,
                  "isDetached": false,
                  "isLocked": false,
                  "isPrunable": false
                },
                {
                  "path": "D:/myscript/pi-app-worktrees/katex",
                  "branch": "feat/katex",
                  "isCurrent": false,
                  "isDetached": false,
                  "isLocked": false,
                  "isPrunable": false
                }
              ]
            }
        """.trimIndent()

        val json = JsonParser.parseString(jsonStr).asJsonObject
        val res = WorktreeResponseParsing.parse(json)

        assertTrue(res.isGit)
        assertEquals("D:/myscript/pi-app", res.projectRoot)
        assertEquals("d:/myscript/pi-app", res.projectKey)
        assertEquals(2, res.worktrees.size)

        val wt1 = res.worktrees[0]
        assertEquals("main", wt1.branch)
        assertTrue(wt1.isCurrent)
        assertTrue(wt1.isMain)

        val wt2 = res.worktrees[1]
        assertEquals("feat/katex", wt2.branch)
        assertTrue(!wt2.isCurrent)
        assertTrue(!wt2.isMain)
    }

    /**
     * 回归：分组产出的展示路径必须**严格唯一**。
     *
     * v1.8.90 曾把 `workspaceKey` 改成「优先取裸 projectKey」，而 `workspacePath` 仍取 projectRoot；
     * 两者一个带反斜杠小写（`d:\\myscript\\x`）、一个带正斜杠原始大小写（`D:/myscript/x`），
     * 于是同一目录在 `groupWorkspaces` 里裂成两组，**两组都输出了 `D:/myscript/x` 这条路**。
     * 抽屉用 `items(key = { it.path })` 渲染，LazyColumn 遇到重复 key 直接抛
     * `IllegalArgumentException: Key ... was already used` —— 表现就是「打开 App 就闪退」。
     */
    @Test
    fun `groupWorkspaces never emits duplicate display paths for windows projects`() {
        val sessions = listOf(
            Session(
                id = "sess-a",
                cwd = "D:\\myscript\\pi-app",
                projectRoot = "D:\\myscript\\pi-app",
                projectKey = "d:\\myscript\\pi-app"
            ),
            Session(
                id = "sess-b",
                cwd = "D:/myscript/termius",
                projectRoot = "D:/myscript/termius",
                projectKey = "d:/myscript/termius"
            )
        )
        // 用户手动添加过的自定义目录（可能带反斜杠，也可能只存在于本地）
        val customPaths = listOf("D:\\myscript\\pi-app", "D:\\myscript\\termius", "D:\\myscript\\only-custom")

        val groups = groupWorkspaces(sessions, customPaths)
        val paths = groups.map { it.path }

        assertEquals(paths.size, paths.toSet().size)
        // 同一目录不得因为路径写法差异分组为两行
        assertEquals(3, groups.size)
        // 会话不应在合并时丢失
        assertEquals(2, groups.sumOf { it.sessions.size })
    }

    /**
     * 回归：`workspaceKey` 与 `workspacePath` 必须由**同一个** rawWorkspacePath 推导，
     * 不得各自采用不同字段优先级——那正是 1.8.90 闪退的根因。
     */
    @Test
    fun `workspaceKey and workspacePath stay consistent regardless of field casing`() {
        val mixed = Session(
            id = "sess-mixed",
            cwd = "D:\\myscript\\pi-app",
            projectRoot = "D:\\myscript\\pi-app",
            projectKey = "d:\\myscript\\pi-app"
        )
        val plain = Session(
            id = "sess-plain",
            cwd = "D:/myscript/pi-app",
            projectRoot = "D:/myscript/pi-app",
            projectKey = "d:/myscript/pi-app"
        )

        // 展示路径保留真实大小写（对齐 1.8.90 的初衷）
        assertEquals("D:/myscript/pi-app", mixed.workspacePath)
        assertEquals("D:/myscript/pi-app", plain.workspacePath)
        // 身份键归一化后必须相等，否则分组就会裂开
        assertEquals(plain.workspaceKey, mixed.workspaceKey)
    }
}
