package com.pi.app.ui.files

import com.pi.app.data.model.FileEntry
import com.pi.app.network.FileListParsing
import java.util.LinkedHashMap

/**
 * 搜索树节点（对标 pi-web lib/search-tree.ts）。
 */
data class SearchTreeNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val children: MutableList<SearchTreeNode> = mutableListOf()
)

/**
 * 针对每个工作区 cwd 的目录展开状态记忆池（LRU 20），对标 pi-web lib/file-tree-expansion.ts。
 */
class ExpandedPathsMemory(private val limit: Int = 20) {
    private val byCwd = LinkedHashMap<String, Set<String>>()

    @Synchronized
    fun save(cwd: String, paths: Set<String>) {
        byCwd.remove(cwd)
        if (paths.isEmpty()) return
        byCwd[cwd] = HashSet(paths)
        while (byCwd.size > limit) {
            val oldest = byCwd.keys.firstOrNull() ?: break
            byCwd.remove(oldest)
        }
    }

    @Synchronized
    fun restore(cwd: String): Set<String> {
        return byCwd[cwd]?.let { HashSet(it) } ?: emptySet()
    }
}

/**
 * 资源管理器目录树辅助状态与算法（纯 Kotlin，不依赖 Android 运行时）。
 */
object FileTreeState {

    const val MAX_DISPLAY_ENTRIES = 500

    /**
     * 将扁平的搜索匹配路径折叠为目录树（对标 pi-web lib/search-tree.ts）。
     */
    fun buildSearchTree(paths: List<String>): List<SearchTreeNode> {
        val roots = mutableListOf<SearchTreeNode>()
        val byPath = mutableMapOf<String, SearchTreeNode>()

        for (rawPath in paths) {
            val normalized = rawPath.replace('\\', '/').trim('/')
            if (normalized.isEmpty()) continue
            val segments = normalized.split('/').filter { it.isNotEmpty() }
            var current = roots
            var currentPath = ""
            for (i in segments.indices) {
                val seg = segments[i]
                currentPath = if (currentPath.isEmpty()) seg else "$currentPath/$seg"
                var node = byPath[currentPath]
                if (node == null) {
                    node = SearchTreeNode(
                        name = seg,
                        path = currentPath,
                        isDir = i < segments.size - 1
                    )
                    byPath[currentPath] = node
                    current.add(node)
                }
                current = node.children
            }
        }

        fun sortNodes(nodes: MutableList<SearchTreeNode>) {
            nodes.sortWith(Comparator { a, b ->
                if (a.isDir != b.isDir) {
                    if (a.isDir) -1 else 1
                } else {
                    a.name.compareTo(b.name, ignoreCase = true)
                }
            })
            for (n in nodes) {
                sortNodes(n.children)
            }
        }

        sortNodes(roots)
        return roots
    }

    /**
     * 根据 Git 变更的文件清单计算所有包含变更的祖先目录集合（对标 pi-web FileExplorer.tsx:846-861）。
     */
    fun calculateChangedDirectoryPaths(changedFiles: List<String>, cwd: String): Set<String> {
        val directories = HashSet<String>()
        val normCwd = cwd.replace('\\', '/').trimEnd('/')
        if (normCwd.isEmpty()) return emptySet()

        for (file in changedFiles) {
            var dir = FileListParsing.parentPath(file)
            while (dir != null && (dir == normCwd || dir.startsWith("$normCwd/"))) {
                directories.add(dir)
                if (dir == normCwd) break
                val parent = FileListParsing.parentPath(dir)
                if (parent == dir) break
                dir = parent
            }
        }
        return directories
    }

    /**
     * 过滤可见项，并执行单目录最大显示项数保护（防大目录卡死）。
     */
    fun filterAndCapEntries(
        entries: List<FileEntry>,
        showHidden: Boolean
    ): Pair<List<FileEntry>, Boolean> {
        val filtered = if (showHidden) {
            entries
        } else {
            entries.filter { it.hidden == null }
        }

        val isTruncated = filtered.size > MAX_DISPLAY_ENTRIES
        val capped = if (isTruncated) filtered.take(MAX_DISPLAY_ENTRIES) else filtered
        return capped to isTruncated
    }
}
