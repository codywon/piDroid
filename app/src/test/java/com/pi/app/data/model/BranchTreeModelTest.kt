package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BranchTreeModelTest {

    @Test
    fun `BranchTreeTopology handles deep chain without stack overflow`() {
        val depth = 5000
        var childObj: JsonObject? = null
        for (i in depth - 1 downTo 0) {
            // 真实响应形状是 {entry, branchPreview, children}（见 BranchTreeTopology.startFrame）
            val node = JsonObject().apply {
                add("entry", JsonObject().apply {
                    addProperty("id", "n$i")
                    addProperty("type", "message")
                })
                add("branchPreview", JsonObject().apply {
                    addProperty("role", if (i % 2 == 0) "user" else "assistant")
                    addProperty("text", "msg-$i")
                })
                val arr = JsonArray()
                if (childObj != null) arr.add(childObj)
                add("children", arr)
            }
            childObj = node
        }
        val rootsJson = JsonArray().apply { add(childObj) }

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
        var childNode: JsonObject? = null
        for (i in depth - 1 downTo 0) {
            val entry = JsonObject().apply {
                addProperty("id", "e$i")
                addProperty("type", "message")
            }
            val node = JsonObject().apply {
                add("entry", entry)
                val arr = JsonArray()
                if (childNode != null) arr.add(childNode)
                add("children", arr)
            }
            childNode = node
        }
        val roots = JsonArray().apply { add(childNode) }

        val flattened = SessionBranchWindow.flattenTree(roots)
        assertNotNull(flattened)
        assertEquals(depth, flattened!!.size())
        assertEquals("e0", flattened[0].asJsonObject.get("id").asString)
        assertEquals("e${depth - 1}", flattened[depth - 1].asJsonObject.get("id").asString)
    }
}
