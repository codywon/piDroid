package com.pi.app.ui.components

/**
 * 超宽块级公式的「换行」预处理器（纯 Kotlin，不碰 Android / Compose，可在 app/src/test 直接断言）。
 *
 * 为什么需要它：KaTeX 排版完只是**一个不可断行的行内盒子**，卡片宽度有限时只能整体等比缩小
 * （见 [KaTeXPageTemplate] 的 `fit()`）。一条「水平 61.87m + 垂直 0.5m + … = 87.18m」的长公式
 * 会被压到 30% 左右，字高 15px 缩成 4~5px，在手机上等于不可读。
 *
 * 这里换成另一种排版策略：**先把公式在顶层运算符处断成几行，再交给 KaTeX 排**。
 * 每行都能按原字号铺满卡片，`fit()` 因此几乎不再触发缩放；确实没有可断点（或被判定为不安全）
 * 的公式则原样返回，行为与改动前完全一致 —— 宁可不动，也不能把一条好公式切成坏公式。
 *
 * 安全边界（任何一条不满足就放弃换行）：
 *  - 已经有多行/对齐结构（`\begin` / `\end` / `\\`）、对齐标记 `&`、标签 `\tag`；
 *  - 成对结构 `\left` / `\right`：从中间切开会让每行括号都不配平；
 *  - 花括号不配平，或 `}` 出现在 `{` 之前。
 *
 * 断点只取**花括号外的** `+`、`=`、`,` 与「两侧带空格的 `-`」：`\text{…}` 里的加号、
 * 数字里的负号（`-1`、`e-5`）都不会被当成断点。
 */
object LatexFormulaWrapper {

    /** LaTeX 的换行符（两个反斜杠），`gathered` 环境用它分隔行。 */
    const val ROW_BREAK: String = "\\\\"

    /** 开闭包裹：`gathered` 不需要 `&` 对齐标记，各行居中，是侵入性最小的换行容器。 */
    private const val ROW_OPEN: String = "\\begin{gathered}"
    private const val ROW_CLOSE: String = "\\end{gathered}"

    /** CJK / 全角字符占一个 em。 */
    private const val CJK_WEIGHT = 2.0

    /** 空格在数学排版里偏窄。 */
    private const val SPACE_WEIGHT = 0.6

    /** 标点与窄字符（`i`、`l`、`.` 之类）占半个字母宽。 */
    private const val NARROW_WEIGHT = 0.5

    /** `\text`、`\cdot` 这类命令：按一个略窄的字母估。 */
    private const val COMMAND_WEIGHT = 0.7

    private const val NARROW_CHARS = ".,:;'|!iljI()[]"

    /**
     * 估算不是精确测量：留 8% 余量，宁可早一行，也不要「刚好卡在边界」又被 KaTeX 缩一次。
     */
    private const val BUDGET_SAFETY = 0.92

    /**
     * 需要换行时返回包了 `gathered` 的新 LaTeX；不需要 / 不安全时**原样返回**入参。
     *
     * @param latex 原始 LaTeX（不含 `$$` 定界符）
     * @param availableWidthDp 卡片里真正能用的宽度（dp，已扣掉内边距）
     * @param fontSizeDp 正文字号（dp）；WebView 里 1 CSS px == 1 dp
     */
    fun wrap(latex: String, availableWidthDp: Float, fontSizeDp: Float): String {
        val src = latex.trim()
        if (src.isEmpty()) return latex
        val budget = rowBudgetWeight(availableWidthDp, fontSizeDp) ?: return latex
        if (!isSplittable(src)) return latex
        val pieces = splitAtTopLevelBreaks(src)
        if (pieces.size < 2) return latex
        if (pieces.sumOf { weightOf(it) } <= budget) return latex
        val rows = greedyRows(pieces, budget)
        if (rows.size < 2) return latex
        return ROW_OPEN + rows.joinToString(ROW_BREAK) + ROW_CLOSE
    }

    /**
     * 一行能容纳多少个「权重单位」。1 em ≈ 2 个权重单位（CJK 记 2、拉丁字母记 1）。
     * 宽度或字号非法时返回 null，调用方据此放弃换行。
     */
    internal fun rowBudgetWeight(availableWidthDp: Float, fontSizeDp: Float): Double? {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return null
        if (!fontSizeDp.isFinite() || fontSizeDp <= 0f) return null
        val emPerRow = availableWidthDp / fontSizeDp
        return emPerRow * 2.0 * BUDGET_SAFETY
    }

    /** 这段 LaTeX 的结构是否允许按运算符切成多行。 */
    internal fun isSplittable(src: String): Boolean {
        // 已有多行 / 对齐 / 标签结构：切开必然破坏语义
        val forbidden = listOf("\\begin", "\\end", ROW_BREAK, "&", "\\tag", "\\left", "\\right")
        if (forbidden.any { src.contains(it) }) return false

        var depth = 0
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '{') depth++
            if (ch == '}') {
                depth--
                if (depth < 0) return false
            }
            i++
        }
        return depth == 0
    }

    /**
     * 在顶层运算符「之后」切段：操作符留在上一行末尾，是数学排版的常规断法
     * （`a + b +` 换行 `c`），也避免下一行以操作符开头。
     */
    internal fun splitAtTopLevelBreaks(src: String): List<String> {
        val pieces = mutableListOf<String>()
        var start = 0
        var depth = 0
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch == '\\') {
                i += 2
                continue
            }
            if (ch == '{') depth++ else if (ch == '}') depth--
            if (depth == 0 && isBreakOperator(src, i)) {
                var end = i + 1
                while (end < src.length && src[end] == ' ') end++
                // 末尾的操作符后面没有内容，不构成断点
                if (end < src.length) {
                    pieces.add(src.substring(start, end))
                    start = end
                }
                i = end
                continue
            }
            i++
        }
        if (start < src.length) pieces.add(src.substring(start))
        return pieces
    }

    /** 该位置是否是一个「安全的顶层断点操作符」。 */
    private fun isBreakOperator(src: String, index: Int): Boolean {
        return when (src[index]) {
            '+' -> true
            ',' -> true
            // `>=`、`<=`、`!=`、`==` 的等号不是断点：切开会留下半个比较符
            '=' -> index == 0 || (
                src[index - 1] != '<' && src[index - 1] != '>' &&
                    src[index - 1] != '!' && src[index - 1] != '='
                )
            // 减号只在两侧带空格时才算二元减号；`-1`、`e-5`、`x-y` 都不切
            '-' -> index > 0 && src[index - 1] == ' ' && index + 1 < src.length && src[index + 1] == ' '
            else -> false
        }
    }

    /** 估算一段 LaTeX 的显示宽度（单位：半个字母宽）。 */
    internal fun weightOf(text: String): Double {
        var total = 0.0
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\') {
                var j = i + 1
                while (j < text.length && text[j].isLetter()) j++
                total += if (j > i + 1) COMMAND_WEIGHT else NARROW_WEIGHT
                i = j
                continue
            }
            total += when {
                ch == ' ' -> SPACE_WEIGHT
                ch.code >= 0x2E80 -> CJK_WEIGHT
                NARROW_CHARS.indexOf(ch) >= 0 -> NARROW_WEIGHT
                else -> 1.0
            }
            i++
        }
        return total
    }

    /** 贪心装行：每段整体搬走，只有「已装进东西」时才允许折行，避免超长单段被撕开。 */
    private fun greedyRows(pieces: List<String>, budget: Double): List<String> {
        val rows = mutableListOf<String>()
        val current = StringBuilder()
        var currentWeight = 0.0
        for (piece in pieces) {
            val w = weightOf(piece)
            if (current.isNotEmpty() && currentWeight + w > budget) {
                rows.add(current.toString().trim())
                current.setLength(0)
                currentWeight = 0.0
            }
            current.append(piece)
            currentWeight += w
        }
        if (current.isNotEmpty()) rows.add(current.toString().trim())
        return rows.filter { it.isNotEmpty() }
    }
}
