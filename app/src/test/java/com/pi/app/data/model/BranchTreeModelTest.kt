package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 深链安全回归：服务端树可以深到几千层（长会话 + 多次压缩），
 * 递归实现会在这里 StackOverflowError —— 解析与拍平都必须走迭代。
 */
class BranchTreeModelTest {

    /** 真实形状是 `{entry, branchPreview, children}`（见 `BranchTreeTopology.startFrame`）。 */
    private fun treeNode(id: String, role: String, preview: String, child: JsonObject?): JsonObject =
        JsonObject().apply {
            add(
                "entry",
                JsonObject().apply {
                    addProperty("id", id)
                    addProperty("type", "message")
                }
            )
            add(
                "branchPreview",
                JsonObject().apply {
                    addProperty("role", role)
                    addProperty("text", preview)
                }
            )
            val children = JsonArray()
            if (child != null) children.add(child)
            add("children", children)
        }

    @Test
    fun `BranchTreeTopology handles deep chain without stack overflow`() {
        val depth = 5000
        var child: JsonObject? = null
        for (i in depth - 1 downTo 0) {
            child = treeNode("n$i", if (i % 2 == 0) "user" else "assistant", "msg-$i", child)
        }
        val root = requireNotNull(child) { "深链构造失败" }
        val rootsJson = JsonArray().apply { add(root) }

        val roots = BranchTreeTopology.parseTreeJson(rootsJson)
        assertEquals(1, roots.size)

        val leafId = "n${depth - 1}"
        val activeIds = BranchTreeTopology.buildActivePathIds(roots, leafId)
        assertEquals(depth, activeIds.size)
        assertTrue(activeIds.contains("n0"))
        assertTrue(activeIds.contains(leafId))

        val rows = BranchTreeTopology.flattenTree(roots, leafId)
        assertEquals(depth, rows.size)
        assertEquals("n0", rows.first().node.id)
        assertEquals(0, rows.first().depth)
        assertEquals(leafId, rows.last().node.id)
        assertEquals(depth - 1, rows.last().depth)
        assertTrue(rows.last().isCurrentLeaf)
    }

    @Test
    fun `SessionBranchWindow flattenTree handles deep chain in pre-order without stack overflow`() {
        val depth = 5000
        var child: JsonObject? = null
        for (i in depth - 1 downTo 0) {
            val entry = JsonObject().apply {
                addProperty("id", "e$i")
                addProperty("type", "message")
            }
            val existing = child
            val node = JsonObject().apply {
                add("entry", entry)
                val children = JsonArray()
                if (existing != null) children.add(existing)
                add("children", children)
            }
            child = node
        }
        val root = requireNotNull(child) { "深链构造失败" }
        val roots = JsonArray().apply { add(root) }

        val flattened = SessionBranchWindow.flattenTree(roots)
        val flat = requireNotNull(flattened) { "flattenTree 不该返回 null" }
        assertEquals(depth, flat.size())
        assertEquals("e0", flat[0].asJsonObject.get("id").asString)
        assertEquals("e${depth - 1}", flat[depth - 1].asJsonObject.get("id").asString)
    }
}
