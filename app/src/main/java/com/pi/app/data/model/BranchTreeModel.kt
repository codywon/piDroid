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
     * 从服务端 JSON 中解析多叉树数组（迭代实现，避免超深分支导致栈溢出）。
     */
    fun parseTreeJson(jsonArr: JsonArray?): List<BranchTreeNode> {
        if (jsonArr == null || jsonArr.size() == 0) return emptyList()
        val list = mutableListOf<BranchTreeNode>()
        for (el in jsonArr) {
            parseNodeIterative(el)?.let { list.add(it) }
        }
        return list
    }

    private class MutableParseFrame(
        val id: String,
        val parentId: String?,
        val type: String,
        val timestamp: String,
        val compressedEntryIds: List<String>,
        val role: String?,
        val previewText: String,
        val rawChildren: JsonArray?,
        var nextChildIdx: Int = 0,
        val parsedChildren: MutableList<BranchTreeNode> = mutableListOf()
    ) {
        fun toNode(): BranchTreeNode = BranchTreeNode(
            id = id,
            parentId = parentId,
            type = type,
            timestamp = timestamp,
            compressedCount = compressedEntryIds.size,
            compressedEntryIds = compressedEntryIds,
            role = role,
            previewText = previewText,
            children = parsedChildren
        )
    }

    private fun startFrame(el: JsonElement?): MutableParseFrame? {
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
        val rawChildren = obj.get("children")?.takeIf { it.isJsonArray }?.asJsonArray
        return MutableParseFrame(
            id = id,
            parentId = parentId,
            type = type,
            timestamp = timestamp,
            compressedEntryIds = compressedList,
            role = role,
            previewText = previewText,
            rawChildren = rawChildren
        )
    }

    private fun parseNodeIterative(el: JsonElement?): BranchTreeNode? {
        val rootFrame = startFrame(el) ?: return null
        val stack = ArrayDeque<MutableParseFrame>()
        stack.addLast(rootFrame)
        while (stack.isNotEmpty()) {
            val top = stack.last()
            val rawChildren = top.rawChildren
            if (rawChildren != null && top.nextChildIdx < rawChildren.size()) {
                val childEl = rawChildren[top.nextChildIdx++]
                val childFrame = startFrame(childEl)
                if (childFrame != null) {
                    stack.addLast(childFrame)
                }
            } else {
                stack.removeLast()
                val built = top.toNode()
                if (stack.isEmpty()) {
                    return built
                }
                stack.last().parsedChildren.add(built)
            }
        }
        return null
    }

    /**
     * 计算当前活跃路径的节点 ID 集合（对标 pi-web markActive，迭代 DFS 防栈溢出）。
     */
    fun buildActivePathIds(roots: List<BranchTreeNode>, activeLeafId: String?): Set<String> {
        if (activeLeafId.isNullOrBlank()) return emptySet()
        val stack = ArrayDeque<Pair<BranchTreeNode, Int>>()
        val currentPath = ArrayList<String>()

        for (root in roots) {
            stack.addLast(root to 0)
            currentPath.add(root.id)
            while (stack.isNotEmpty()) {
                val (node, nextIdx) = stack.last()
                if (nextIdx == 0 && (node.id == activeLeafId || node.compressedEntryIds.contains(activeLeafId))) {
                    return currentPath.toSet()
                }
                if (nextIdx < node.children.size) {
                    stack[stack.lastIndex] = node to (nextIdx + 1)
                    val child = node.children[nextIdx]
                    stack.addLast(child to 0)
                    currentPath.add(child.id)
                } else {
                    stack.removeLast()
                    currentPath.removeAt(currentPath.lastIndex)
                }
            }
        }
        return emptySet()
    }

    /**
     * 将树状拓扑拍平成带有连线布尔信息的线性行列表（对标 pi-web vt 组件，迭代 DFS）。
     */
    fun flattenTree(
        roots: List<BranchTreeNode>,
        activeLeafId: String?
    ): List<BranchTreeRow> {
        val activePathIds = buildActivePathIds(roots, activeLeafId)
        val rows = mutableListOf<BranchTreeRow>()
        val stack = ArrayDeque<BranchTreeRow>()

        for (idx in roots.indices.reversed()) {
            val root = roots[idx]
            val isActive = activePathIds.contains(root.id)
            val isCurrentLeaf = root.id == activeLeafId || root.compressedEntryIds.lastOrNull() == activeLeafId
            stack.addLast(
                BranchTreeRow(
                    node = root,
                    depth = 0,
                    isLastChild = idx == roots.size - 1,
                    parentLines = emptyList(),
                    isActivePath = isActive,
                    isCurrentLeaf = isCurrentLeaf
                )
            )
        }

        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            rows.add(current)
            val children = current.node.children
            val childCount = children.size
            val nextParentLines = if (current.depth > 0) {
                current.parentLines + (!current.isLastChild)
            } else {
                emptyList()
            }
            for (idx in children.indices.reversed()) {
                val child = children[idx]
                val isActive = activePathIds.contains(child.id)
                val isCurrentLeaf = child.id == activeLeafId || child.compressedEntryIds.lastOrNull() == activeLeafId
                stack.addLast(
                    BranchTreeRow(
                        node = child,
                        depth = current.depth + 1,
                        isLastChild = idx == childCount - 1,
                        parentLines = nextParentLines,
                        isActivePath = isActive,
                        isCurrentLeaf = isCurrentLeaf
                    )
                )
            }
        }
        return rows
    }
}
