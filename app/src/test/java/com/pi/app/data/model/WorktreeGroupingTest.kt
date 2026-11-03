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
}
