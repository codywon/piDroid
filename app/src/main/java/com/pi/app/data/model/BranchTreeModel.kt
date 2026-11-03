package com.pi.app.data.model

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 会话多叉分支拓扑树节点（1:1 对标 pi-web GET /api/sessions/[id]?tree=summary 的树结构）。
 */
data class BranchTreeNode(
    val id: String,
    val parentId: String? = null,
    val type: String = "message",
    val timestamp: String = "",
    val compressedCount: Int = 0,
    val compressedEntryIds: List<String> = emptyList(),
    val role: String? = null, // "user" | "assistant"
    val previewText: String = "",
    val children: List<BranchTreeNode> = emptyList()
)

/**
 * 拍平后供 Compose 渲染直角树状折线的单行数据模型。
 */
data class BranchTreeRow(
    val node: BranchTreeNode,
    val depth: Int,
    val isLastChild: Boolean,
    /** 祖先层级是否需要画连续竖线 */
    val parentLines: List<Boolean>,
    /** 是否位于当前活跃分支路径上 */
    val isActivePath: Boolean,
    /** 是否是当前分支的活跃终点 */
    val isCurrentLeaf: Boolean
)

object BranchTreeTopology {

    /**
     * 从服务端 JSON 中解析多叉树数组。
     */
    fun parseTreeJson(jsonArr: JsonArray?): List<BranchTreeNode> {
        if (jsonArr == null || jsonArr.size() == 0) return emptyList()
        val list = mutableListOf<BranchTreeNode>()
        for (el in jsonArr) {
            parseNode(el)?.let { list.add(it) }
        }
        return list
    }

    private fun parseNode(el: JsonElement?): BranchTreeNode? {
        val obj = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val entry = obj.getAsJsonObject("entry") ?: return null
        val id = entry.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val parentId = entry.get("parentId")?.takeIf { !it.isJsonNull }?.asString
        val type = entry.get("type")?.takeIf { !it.isJsonNull }?.asString ?: "message"
        val timestamp = entry.get("timestamp")?.takeIf { !it.isJsonNull }?.asString ?: ""

        val compressedList = mutableListOf<String>()
        obj.get("compressedEntryIds")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
            it.takeIf { !it.isJsonNull }?.asString?.let { s -> compressedList.add(s) }
        }

        var role: String? = null
        var previewText = ""
        val previewObj = obj.getAsJsonObject("branchPreview")
        if (previewObj != null) {
            role = previewObj.get("role")?.takeIf { !it.isJsonNull }?.asString
            previewText = previewObj.get("text")?.takeIf { !it.isJsonNull }?.asString ?: ""
        }

        val childrenList = mutableListOf<BranchTreeNode>()
        obj.get("children")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach {
            parseNode(it)?.let { child -> childrenList.add(child) }
        }

        return BranchTreeNode(
            id = id,
            parentId = parentId,
            type = type,
            timestamp = timestamp,
            compressedCount = compressedList.size,
            compressedEntryIds = compressedList,
            role = role,
            previewText = previewText,
            children = childrenList
        )
    }

    /**
     * 计算当前活跃路径的节点 ID 集合（对标 pi-web markActive）。
     */
    fun buildActivePathIds(roots: List<BranchTreeNode>, activeLeafId: String?): Set<String> {
        if (activeLeafId.isNullOrBlank()) return emptySet()
        val activeIds = mutableSetOf<String>()

        fun findPath(node: BranchTreeNode, targetId: String, currentPath: List<String>): Boolean {
            val pathWithCurrent = currentPath + node.id
            if (node.id == targetId || node.compressedEntryIds.contains(targetId)) {
                activeIds.addAll(pathWithCurrent)
                return true
            }
            for (child in node.children) {
                if (findPath(child, targetId, pathWithCurrent)) {
                    return true
                }
            }
            return false
        }

        for (root in roots) {
            if (findPath(root, activeLeafId, emptyList())) {
                break
            }
        }
        return activeIds
    }

    /**
     * 将树状拓扑拍平成带有连线布尔信息的线性行列表（对标 pi-web vt 组件）。
     */
    fun flattenTree(
        roots: List<BranchTreeNode>,
        activeLeafId: String?
    ): List<BranchTreeRow> {
        val activePathIds = buildActivePathIds(roots, activeLeafId)
        val rows = mutableListOf<BranchTreeRow>()

        fun walk(
            node: BranchTreeNode,
            depth: Int,
            isLast: Boolean,
            parentLines: List<Boolean>
        ) {
            val isActive = activePathIds.contains(node.id)
            val isCurrentLeaf = node.id == activeLeafId || node.compressedEntryIds.lastOrNull() == activeLeafId
            rows.add(
                BranchTreeRow(
                    node = node,
                    depth = depth,
                    isLastChild = isLast,
                    parentLines = parentLines,
                    isActivePath = isActive,
                    isCurrentLeaf = isCurrentLeaf
                )
            )

            val childCount = node.children.size
            node.children.forEachIndexed { idx, child ->
                val nextParentLines = if (depth > 0) parentLines + (!isLast) else emptyList()
                walk(
                    node = child,
                    depth = depth + 1,
                    isLast = idx == childCount - 1,
                    parentLines = nextParentLines
                )
            }
        }

        roots.forEachIndexed { idx, root ->
            walk(root, depth = 0, isLast = idx == roots.size - 1, parentLines = emptyList())
        }
        return rows
    }
}
