package com.pi.app.ui.git

/**
 * Git 统一补丁（Unified Patch）单行模型。
 */
data class UnifiedPatchLine(
    val type: PatchLineType,
    val text: String,
    val oldLineNo: Int? = null,
    val newLineNo: Int? = null
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
                        lines = currentLines.toList()
                    )
                )
                currentLines = mutableListOf()
                pendingOldPath = null
                pendingNewPath = null
            }
        }

        val rawLines = patchText.split("\r\n", "\n")
        for (line in rawLines) {
            val insideHunk = hunkOldRemaining > 0 || hunkNewRemaining > 0

            // 处于 Hunk 之外时，处理文件头部信息
            if (!insideHunk) {
                if (line.startsWith("diff --git ")) {
                    flushFile()
                    continue
                }
                if (line.startsWith("--- ")) {
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
}
