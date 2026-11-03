package com.pi.app.data.model

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchTreeTopologyTest {

    @Test
    fun `parseTreeJson parses nested tree with branchPreview and compression`() {
        val jsonStr = """
            [
              {
                "entry": { "id": "root-1", "type": "message", "timestamp": "2026-10-31T08:00:00Z" },
                "compressedEntryIds": ["c1", "c2"],
                "branchPreview": { "role": "user", "text": "Root prompt" },
                "children": [
                  {
                    "entry": { "id": "child-abandoned", "parentId": "root-1", "type": "message" },
                    "branchPreview": { "role": "user", "text": "Plan A" },
                    "children": []
                  },
                  {
                    "entry": { "id": "child-active", "parentId": "root-1", "type": "message" },
                    "branchPreview": { "role": "user", "text": "Plan B" },
                    "children": []
                  }
                ]
              }
            ]
        """.trimIndent()

        val jsonArr = JsonParser.parseString(jsonStr).asJsonArray
        val tree = BranchTreeTopology.parseTreeJson(jsonArr)

        assertEquals(1, tree.size)
        val root = tree[0]
        assertEquals("root-1", root.id)
        assertEquals(2, root.compressedCount)
        assertEquals("Root prompt", root.previewText)
        assertEquals(2, root.children.size)
        assertEquals("child-abandoned", root.children[0].id)
        assertEquals("child-active", root.children[1].id)
    }

    @Test
    fun `buildActivePathIds identifies active branches and leaves correctly`() {
        val child1 = BranchTreeNode(id = "c1", previewText = "Fork 1")
        val child2 = BranchTreeNode(id = "c2", previewText = "Fork 2")
        val root = BranchTreeNode(id = "r", children = listOf(child1, child2))

        val activeIds = BranchTreeTopology.buildActivePathIds(listOf(root), "c2")
        assertTrue(activeIds.contains("r"))
        assertTrue(activeIds.contains("c2"))
        assertFalse(activeIds.contains("c1"))
    }

    @Test
    fun `flattenTree marks isCurrentLeaf and branch depth`() {
        val leaf = BranchTreeNode(id = "leaf", previewText = "Active Leaf")
        val root = BranchTreeNode(id = "root", children = listOf(leaf))

        val rows = BranchTreeTopology.flattenTree(listOf(root), "leaf")
        assertEquals(2, rows.size)

        assertEquals("root", rows[0].node.id)
        assertEquals(0, rows[0].depth)
        assertTrue(rows[0].isActivePath)
        assertFalse(rows[0].isCurrentLeaf)

        assertEquals("leaf", rows[1].node.id)
        assertEquals(1, rows[1].depth)
        assertTrue(rows[1].isActivePath)
        assertTrue(rows[1].isCurrentLeaf)
    }
}
