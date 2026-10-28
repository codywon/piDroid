package com.pi.app.ui.git

/**
 * Git 统一补丁（Unified Patch）单行模型。
 */
data class UnifiedPatchLine(
    val type: PatchLineType,
    val text: String,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null,
    val highlightRange: IntRange? = null
)

enum class PatchLineType {
    CONTEXT,
    ADDED,
    REMOVED,
    HUNK
}

data class ParsedDiffFile(
    val oldPath: String? = null,
    val newPath: String? = null,
    val lines: List<UnifiedPatchLine> = emptyList()
)

/**
 * 参考 pi-web `lib/patch.ts` 实现的 Unified Diff 解析器。
 * 能够准确区分 @@ 块头、增删行与上下文行，计算新旧行号，并忽略文件头之间的元数据行。
 */
object UnifiedDiffParser {

    private val HUNK_HEADER_REGEX = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$""")

    fun parse(patchText: String): List<ParsedDiffFile> {
        if (patchText.isBlank()) return emptyList()

        val files = mutableListOf<ParsedDiffFile>()
        var currentLines = mutableListOf<UnifiedPatchLine>()
        var pendingOldPath: String? = null
        var pendingNewPath: String? = null

        var oldLineNo = 0
        var newLineNo = 0
        var hunkOldRemaining = 0
        var hunkNewRemaining = 0

        fun flushFile() {
            if (currentLines.isNotEmpty() || pendingOldPath != null || pendingNewPath != null) {
                files.add(
                    ParsedDiffFile(
                        oldPath = pendingOldPath,
                        newPath = pendingNewPath,
                        lines = enrichWordDiffs(currentLines.toList())
                    )
                )
                currentLines = mutableListOf()
                pendingOldPath = null
                pendingNewPath = null
            }
        }

        val rawLines = patchText.split("\r\n", "\n")
        for (line in rawLines) {
            // 如果遇到明确的新文件标志，即使上一段 hunk 计数值未耗尽（如异常截断补丁），也强制结束上一文件
            if (line.startsWith("diff --git ")) {
                flushFile()
                hunkOldRemaining = 0
                hunkNewRemaining = 0
                continue
            }

            val insideHunk = hunkOldRemaining > 0 || hunkNewRemaining > 0

            // 处于 Hunk 之外时，处理文件头部信息
            if (!insideHunk) {
                if (line.startsWith("--- ")) {
                    // 兼容没有 diff --git 头部的标准 unified diff：遇到新的 --- 时结算上一文件
                    if (currentLines.isNotEmpty()) {
                        flushFile()
                    }
                    pendingOldPath = cleanPath(line.removePrefix("--- "))
                    continue
                }
                if (line.startsWith("+++ ")) {
                    pendingNewPath = cleanPath(line.removePrefix("+++ "))
                    continue
                }
                // git diff 的元数据行（如 index abc..def 100644, old mode, new file mode 等）在 hunk 之外直接跳过，不落入正文
                if (!line.startsWith("@@ ")) {
                    continue
                }
            }

            val hunkMatch = HUNK_HEADER_REGEX.find(line)
            if (hunkMatch != null) {
                oldLineNo = hunkMatch.groupValues[1].toIntOrNull() ?: 0
                val oldCount = hunkMatch.groupValues[2].ifBlank { "1" }.toIntOrNull() ?: 1
                newLineNo = hunkMatch.groupValues[3].toIntOrNull() ?: 0
                val newCount = hunkMatch.groupValues[4].ifBlank { "1" }.toIntOrNull() ?: 1

                hunkOldRemaining = oldCount
                hunkNewRemaining = newCount

                currentLines.add(
                    UnifiedPatchLine(
                        type = PatchLineType.HUNK,
                        text = line
                    )
                )
                continue
            }

            if (line.startsWith("\\ ")) {
                currentLines.add(
                    UnifiedPatchLine(
                        type = PatchLineType.HUNK,
                        text = line
                    )
                )
                continue
            }

            if (line.isEmpty()) {
                if (insideHunk) {
                    currentLines.add(
                        UnifiedPatchLine(
                            type = PatchLineType.CONTEXT,
                            text = "",
                            oldLineNo = if (hunkOldRemaining > 0) oldLineNo++ else null,
                            newLineNo = if (hunkNewRemaining > 0) newLineNo++ else null
                        )
                    )
                    if (hunkOldRemaining > 0) hunkOldRemaining--
                    if (hunkNewRemaining > 0) hunkNewRemaining--
                }
                continue
            }

            val prefix = line[0]
            val content = line.substring(1)

            when (prefix) {
                ' ' -> {
                    currentLines.add(
                        UnifiedPatchLine(
                            type = PatchLineType.CONTEXT,
                            text = content,
                            oldLineNo = oldLineNo++,
                            newLineNo = newLineNo++
                        )
                    )
                    if (hunkOldRemaining > 0) hunkOldRemaining--
                    if (hunkNewRemaining > 0) hunkNewRemaining--
                }
                '-' -> {
                    currentLines.add(
                        UnifiedPatchLine(
                            type = PatchLineType.REMOVED,
                            text = content,
                            oldLineNo = oldLineNo++,
                            newLineNo = null
                        )
                    )
                    if (hunkOldRemaining > 0) hunkOldRemaining--
                }
                '+' -> {
                    currentLines.add(
                        UnifiedPatchLine(
                            type = PatchLineType.ADDED,
                            text = content,
                            oldLineNo = null,
                            newLineNo = newLineNo++
                        )
                    )
                    if (hunkNewRemaining > 0) hunkNewRemaining--
                }
                else -> {
                    currentLines.add(
                        UnifiedPatchLine(
                            type = PatchLineType.HUNK,
                            text = line
                        )
                    )
                }
            }
        }

        flushFile()
        return files
    }

    private fun cleanPath(raw: String): String {
        val path = raw.split('\t')[0].trim()
        return when {
            path.startsWith("a/") || path.startsWith("b/") -> path.substring(2)
            else -> path
        }
    }

    /**
     * 对紧邻的删减与新增代码块（特别是 1:1 修改行）进行词级别 (Intra-line Word Diff) 对比，
     * 标注出具体被修改的字符区间（对标 GitHub Mobile）。
     */
    private fun enrichWordDiffs(lines: List<UnifiedPatchLine>): List<UnifiedPatchLine> {
        if (lines.isEmpty()) return lines
        val result = lines.toMutableList()
        var i = 0
        while (i < result.size) {
            if (result[i].type == PatchLineType.REMOVED) {
                val removedStart = i
                while (i < result.size && result[i].type == PatchLineType.REMOVED) {
                    i++
                }
                val removedCount = i - removedStart

                val addedStart = i
                while (i < result.size && result[i].type == PatchLineType.ADDED) {
                    i++
                }
                val addedCount = i - addedStart

                // 连续删改行数相等且在合理范围（1~5行）内，按行对应进行局部词级对比
                if (removedCount in 1..5 && removedCount == addedCount) {
                    for (k in 0 until removedCount) {
                        val oldLine = result[removedStart + k]
                        val newLine = result[addedStart + k]
                        val (oldRange, newRange) = computeWordDiffHighlight(oldLine.text, newLine.text)
                        if (oldRange != null) {
                            result[removedStart + k] = oldLine.copy(highlightRange = oldRange)
                        }
                        if (newRange != null) {
                            result[addedStart + k] = newLine.copy(highlightRange = newRange)
                        }
                    }
                }
                continue
            }
            i++
        }
        return result
    }

    private fun computeWordDiffHighlight(oldText: String, newText: String): Pair<IntRange?, IntRange?> {
        if (oldText == newText || oldText.isEmpty() || newText.isEmpty()) return null to null

        var prefix = 0
        val maxLen = minOf(oldText.length, newText.length)
        while (prefix < maxLen && oldText[prefix] == newText[prefix]) {
            prefix++
        }

        var suffix = 0
        while (suffix < (maxLen - prefix) &&
            oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]
        ) {
            suffix++
        }

        // 防御 Unicode 代理对（Surrogate Pairs，如 emoji 或生僻字）被截断
        if (prefix > 0 && prefix < oldText.length &&
            oldText[prefix - 1].isHighSurrogate() && oldText[prefix].isLowSurrogate()
        ) {
            prefix--
        }
        if (suffix > 0 && suffix < oldText.length &&
            oldText[oldText.length - suffix - 1].isHighSurrogate() && oldText[oldText.length - suffix].isLowSurrogate()
        ) {
            suffix--
        }

        // 仅在存在有效共有前缀或后缀（说明为局部修改）时才高亮差异词，避免整行不同时出现大面积冗余双重高亮
        if (prefix > 0 || suffix > 0) {
            val oldRange = if (prefix < oldText.length - suffix) {
                prefix until (oldText.length - suffix)
            } else null

            val newRange = if (prefix < newText.length - suffix) {
                prefix until (newText.length - suffix)
            } else null

            return oldRange to newRange
        }

        return null to null
    }
}
