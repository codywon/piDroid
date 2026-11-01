package com.pi.app.ui.files

import com.pi.app.data.model.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 [FileTreeState] 与 [ExpandedPathsMemory] 的纯 JVM 单元测试。
 */
class FileTreeStateTest {

    @Test
    fun `ExpandedPathsMemory saves, restores and evicts oldest entries at limit`() {
        val memory = ExpandedPathsMemory(limit = 3)
        memory.save("/cwd1", setOf("/cwd1/src", "/cwd1/docs"))
        memory.save("/cwd2", setOf("/cwd2/app"))
        memory.save("/cwd3", setOf("/cwd3/res"))

        assertEquals(2, memory.restore("/cwd1").size)
        assertEquals(1, memory.restore("/cwd2").size)

        // 插入第 4 个，应淘汰最早的 /cwd1
        memory.save("/cwd4", setOf("/cwd4/pkg"))
        assertTrue("Oldest /cwd1 should be evicted", memory.restore("/cwd1").isEmpty())
        assertEquals(1, memory.restore("/cwd2").size)
        assertEquals(1, memory.restore("/cwd4").size)

        // 保存空集合不计入
        memory.save("/cwd5", emptySet())
        assertTrue(memory.restore("/cwd5").isEmpty())
    }

    @Test
    fun `buildSearchTree constructs directory hierarchy and sorts accurately`() {
        val paths = listOf(
            "src/components/Button.tsx",
            "src/App.tsx",
            "README.md",
            "docs/manual.md"
        )

        val tree = FileTreeState.buildSearchTree(paths)
        assertEquals(3, tree.size) // docs, src, README.md

        // 目录排在前面
        assertEquals("docs", tree[0].name)
        assertTrue(tree[0].isDir)
        assertEquals("src", tree[1].name)
        assertTrue(tree[1].isDir)
        assertEquals("README.md", tree[2].name)
        assertFalse(tree[2].isDir)

        // 检查 src 内部子级
        val src = tree[1]
        assertEquals(2, src.children.size) // components (dir), App.tsx (file)
        assertEquals("components", src.children[0].name)
        assertTrue(src.children[0].isDir)
        assertEquals("App.tsx", src.children[1].name)
        assertFalse(src.children[1].isDir)

        val components = src.children[0]
        assertEquals(1, components.children.size)
        assertEquals("Button.tsx", components.children[0].name)
        assertFalse(components.children[0].isDir)
    }

    @Test
    fun `calculateChangedDirectoryPaths bubbles up changed ancestors to cwd`() {
        val cwd = "D:/project"
        val changedFiles = listOf(
            "D:/project/app/src/main/App.kt",
            "D:/project/README.md"
        )

        val dirs = FileTreeState.calculateChangedDirectoryPaths(changedFiles, cwd)
        assertTrue(dirs.contains("D:/project"))
        assertTrue(dirs.contains("D:/project/app"))
        assertTrue(dirs.contains("D:/project/app/src"))
        assertTrue(dirs.contains("D:/project/app/src/main"))

        // 不应包含 cwd 以外的目录
        assertFalse(dirs.contains("D:/other"))
    }

    @Test
    fun `filterAndCapEntries respects hidden switch and max caps`() {
        val entries = (1..600).map { i ->
            FileEntry(
                name = "file_$i.txt",
                isDir = false,
                hidden = if (i % 2 == 0) "ignored" else null
            )
        }

        // showHidden = false: 只剩 300 个奇数项，不超 500
        val (visibleHiddenOff, truncatedOff) = FileTreeState.filterAndCapEntries(entries, showHidden = false)
        assertEquals(300, visibleHiddenOff.size)
        assertFalse(truncatedOff)

        // showHidden = true: 600 项超上限，截断至 500
        val (visibleHiddenOn, truncatedOn) = FileTreeState.filterAndCapEntries(entries, showHidden = true)
        assertEquals(500, visibleHiddenOn.size)
        assertTrue(truncatedOn)
    }
}
