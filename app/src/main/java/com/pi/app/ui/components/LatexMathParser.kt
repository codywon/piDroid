package com.pi.app.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.unit.sp

/**
 * LaTeX 数学与工程表达式解析与矢量渲染引擎
 * 采用编译器标准的“分词器 (Lexer) + 括号匹配深度栈 (Brace Stack) + 语法解析器 (Parser)”，
 * 支持深度嵌套、容错与公式排版。
 */
object LatexMathParser {

    /**
     * 整段文本就是一条公式时使用（块级公式卡片的原生兜底、`\(…\)` 之外的裸 LaTeX）。
     *
     * 之所以要有这个入口：块级公式的内容**不含 `$` 定界符**，旧实现把它塞进
     * [appendTextWithMath]，而那个函数只对 `$…$` 片段分词，于是整段 LaTeX
     * 被逐字符原样吐出（线上截图里公式卡片显示 `\text{总放线长度 } L = \Delta X…` 的来源）。
     */
    fun appendFormula(
        formula: String,
        builder: AnnotatedString.Builder,
        isBlock: Boolean = false,
        textColor: Color = Color.Unspecified
    ) {
        val raw = formula.trim()
        if (raw.isEmpty()) return
        renderFormulaToBuilder(raw, builder, isUser = false, isBlock = isBlock, textColor = textColor)
    }

    /**
     * 将包含 LaTeX 数学语法的文本解析并以带排版样式的 AnnotatedString 写入 builder
     */
    fun appendTextWithMath(
        rawText: String,
        builder: AnnotatedString.Builder,
        isUser: Boolean = false,
        textColor: Color = Color.Unspecified
    ) {
        if (!rawText.contains("$") && !rawText.contains("\\")) {
            builder.append(rawText)
            return
        }

        var index = 0
        val length = rawText.length

        while (index < length) {
            val char = rawText[index]

            // 检查转义的 \$
            if (char == '\\' && index + 1 < length && rawText[index + 1] == '$') {
                builder.append("$")
                index += 2
                continue
            }

            // 检查块级公式 $$...$$
            if (char == '$' && index + 1 < length && rawText[index + 1] == '$') {
                val endIdx = rawText.indexOf("$$", index + 2)
                if (endIdx != -1) {
                    val formula = rawText.substring(index + 2, endIdx).trim()
                    renderFormulaToBuilder(formula, builder, isUser, isBlock = true, textColor = textColor)
                    index = endIdx + 2
                    continue
                }
            }

            // 检查行内公式 $...$
            if (char == '$') {
                // 确保不是单独的货币符号（检查是否有同行成对闭合的 $）
                var endIdx = -1
                var scanIdx = index + 1
                while (scanIdx < length && rawText[scanIdx] != '\n') {
                    if (rawText[scanIdx] == '$' && rawText[scanIdx - 1] != '\\') {
                        endIdx = scanIdx
                        break
                    }
                    scanIdx++
                }

                if (endIdx != -1 && endIdx > index + 1) {
                    val formula = rawText.substring(index + 1, endIdx).trim()
                    renderFormulaToBuilder(formula, builder, isUser, isBlock = false, textColor = textColor)
                    index = endIdx + 1
                    continue
                }
            }

            builder.append(char)
            index++
        }
    }

    private fun renderFormulaToBuilder(
        formula: String,
        builder: AnnotatedString.Builder,
        isUser: Boolean,
        isBlock: Boolean,
        textColor: Color
    ) {
        if (formula.isBlank()) return

        if (isBlock) {
            builder.append("\n")
        }

        val primaryColor = textColor
        val formulaTokens = tokenizeAndParseFormula(formula)

        for (token in formulaTokens) {
            when (token) {
                is MathToken.Text -> {
                    builder.append(token.content)
                }

                is MathToken.Variable -> {
                    builder.pushStyle(
                        SpanStyle(
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryColor
                        )
                    )
                    builder.append(token.name)
                    builder.pop()
                }

                is MathToken.Operator -> {
                    builder.append(token.symbol)
                }

                is MathToken.Superscript -> {
                    builder.pushStyle(
                        SpanStyle(
                            baselineShift = BaselineShift.Superscript,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor
                        )
                    )
                    builder.append(token.content)
                    builder.pop()
                }

                is MathToken.Subscript -> {
                    builder.pushStyle(
                        SpanStyle(
                            baselineShift = BaselineShift.Subscript,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium,
                            color = primaryColor
                        )
                    )
                    builder.append(token.content)
                    builder.pop()
                }
            }
        }

        if (isBlock) {
            builder.append("\n")
        }
    }

    private fun tokenizeAndParseFormula(formula: String): List<MathToken> {
        val tokens = mutableListOf<MathToken>()
        var i = 0
        val len = formula.length

        while (i < len) {
            val c = formula[i]

            if (c.isWhitespace()) {
                if (tokens.isNotEmpty() && tokens.last() !is MathToken.Operator) {
                    val last = tokens.last()
                    if (last is MathToken.Text && !last.content.endsWith(" ")) {
                        tokens.add(MathToken.Text(" "))
                    }
                }
                i++
                continue
            }

            if (c == '\\') {
                val (cmdTokenList, nextIdx) = parseCommand(formula, i)
                tokens.addAll(cmdTokenList)
                i = nextIdx
                continue
            }

            if (c == '^') {
                val (supText, nextIdx) = readScriptContent(formula, i + 1)
                if (supText == "\\circ" || supText == "°") {
                    var finalNext = nextIdx
                    if (finalNext < len && formula.startsWith("\\text{C}", finalNext)) {
                        tokens.add(MathToken.Text(" ℃"))
                        finalNext += "\\text{C}".length
                    } else if (finalNext < len && formula[finalNext] == 'C') {
                        tokens.add(MathToken.Text(" ℃"))
                        finalNext += 1
                    } else {
                        tokens.add(MathToken.Text("°"))
                    }
                    i = finalNext
                    continue
                }

                val cleanSup = cleanLatexCommandToUnicode(supText)
                tokens.add(MathToken.Superscript(cleanSup))
                i = nextIdx
                continue
            }

            if (c == '_') {
                val (subText, nextIdx) = readScriptContent(formula, i + 1)
                val cleanSub = cleanLatexCommandToUnicode(subText)
                tokens.add(MathToken.Subscript(cleanSub))
                i = nextIdx
                continue
            }

            when (c) {
                '=' -> { tokens.add(MathToken.Operator(" = ")); i++; continue }
                '+' -> { tokens.add(MathToken.Operator(" + ")); i++; continue }
                '-' -> { tokens.add(MathToken.Operator(" - ")); i++; continue }
                '*' -> { tokens.add(MathToken.Operator(" × ")); i++; continue }
                '/' -> { tokens.add(MathToken.Operator(" / ")); i++; continue }
                '~' -> { tokens.add(MathToken.Operator(" ~ ")); i++; continue }
                ',' -> { tokens.add(MathToken.Text(", ")); i++; continue }
                // 环境里的对齐制表符 `&` 与纯分组用的 `{}`：都不该出现在正文里
                '&' -> { tokens.add(MathToken.Text(" ")); i++; continue }
                '{', '}' -> { i++; continue }
            }

            if (c.isLetter()) {
                val (word, nextIdx) = readWord(formula, i)
                if (isMathFunctionName(word)) {
                    tokens.add(MathToken.Text("$word "))
                } else if (word.length == 1) {
                    tokens.add(MathToken.Variable(word))
                } else {
                    tokens.add(MathToken.Text(word))
                }
                i = nextIdx
                continue
            }

            if (c.isDigit() || c == '.') {
                val (numStr, nextIdx) = readNumber(formula, i)
                tokens.add(MathToken.Text(numStr))
                i = nextIdx
                continue
            }

            tokens.add(MathToken.Text(c.toString()))
            i++
        }

        return tokens
    }

    private fun parseCommand(formula: String, startIndex: Int): Pair<List<MathToken>, Int> {
        var i = startIndex + 1
        val len = formula.length
        if (i >= len) return listOf(MathToken.Text("")) to i

        // 单字符命令：`\\`（换行/分隔）、`\,` `\;` `\:` `\!`（间距）、`\{` `\%` 等转义。
        // 必须在读字母命令之前拦下，否则 `\\` 会被读成一个空命令名。
        when (val single = formula[i]) {
            '\\' -> return listOf(MathToken.Text(" ; ")) to (i + 1)
            ',', ';', ':', '!', ' ' -> return listOf(MathToken.Text(" ")) to (i + 1)
            '{', '}', '%', '$', '#', '&', '_' -> return listOf(MathToken.Text(single.toString())) to (i + 1)
        }

        val cmdStart = i
        while (i < len && formula[i].isLetter()) {
            i++
        }
        val cmd = formula.substring(cmdStart, i)
        if (cmd.isEmpty()) return listOf(MathToken.Text("")) to i

        when (cmd) {
            "text", "mbox", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "operatorname" -> {
                val (content, nextIdx) = readBracedBlock(formula, i)
                // 不做 trim：`\text{总放线长度 }` 的尾随空格是原文的一部分，
                // 抹掉以后中文与后面的变量会粘成一串。
                return listOf(MathToken.Text(decodeLatexContent(content))) to nextIdx
            }
            "vec", "bar", "overline", "underline", "hat", "widehat", "tilde", "dot", "ddot" -> {
                val (content, nextIdx) = readBracedBlock(formula, i)
                val accent = when (cmd) {
                    "vec" -> "\u20D7"
                    "hat", "widehat" -> "\u0302"
                    "tilde" -> "\u0303"
                    "dot" -> "\u0307"
                    "ddot" -> "\u0308"
                    "bar", "overline" -> "\u0304"
                    "underline" -> "\u0332"
                    else -> ""
                }
                return listOf(MathToken.Text(decodeLatexContent(content) + accent)) to nextIdx
            }
            "begin", "end" -> {
                // 环境名（aligned / cases / array …）本身不需要出现在正文里
                val (_, nextIdx) = readBracedBlock(formula, i)
                return listOf(MathToken.Text("")) to nextIdx
            }
            "frac", "dfrac", "tfrac", "cfrac" -> {
                val (num, nextAfterNum) = readBracedBlock(formula, i)
                val (den, nextAfterDen) = readBracedBlock(formula, nextAfterNum)
                val cleanNum = cleanLatexCommandToUnicode(num)
                val cleanDen = cleanLatexCommandToUnicode(den)
                return listOf(MathToken.Text("($cleanNum) / ($cleanDen)")) to nextAfterDen
            }
            "sqrt" -> {
                var currentIdx = i
                var rootDegree = ""
                if (currentIdx < len && formula[currentIdx] == '[') {
                    val closeBracket = formula.indexOf(']', currentIdx)
                    if (closeBracket != -1) {
                        rootDegree = formula.substring(currentIdx + 1, closeBracket).trim()
                        currentIdx = closeBracket + 1
                    }
                }
                val (radicand, nextIdx) = readBracedBlock(formula, currentIdx)
                val cleanRadicand = cleanLatexCommandToUnicode(radicand)
                val resultText = if (rootDegree.isNotEmpty()) "$rootDegree√($cleanRadicand)" else "√($cleanRadicand)"
                return listOf(MathToken.Text(resultText)) to nextIdx
            }
        }

        // 符号表统一放在 LatexSymbols（纯 Kotlin，有单元测试守着）：之前这里是一长串
        // `when` 分支，没登记的命令就落到 `else` 把命令名当正文输出 —— 线上那句
        // “基站 rightarrow 机柜” 就是 `\rightarrow` 走出来的。
        LatexSymbols.resolve(cmd)?.let { symbol ->
            return listOf(MathToken.Text(symbol.text)) to i
        }
        if (LatexSymbols.isFunction(cmd)) {
            return listOf(MathToken.Text(LatexSymbols.functionText(cmd))) to i
        }
        if (LatexSymbols.isLayoutOnly(cmd)) {
            return listOf(MathToken.Text("")) to i
        }
        if (LatexSymbols.takesDroppedArgument(cmd)) {
            val (_, nextIdx) = readBracedBlock(formula, i)
            return listOf(MathToken.Text("")) to nextIdx
        }
        // 认不出来的命令：宁可不出字，也不再往外吐命令名。
        return listOf(MathToken.Text("")) to i
    }

    private fun readBracedBlock(formula: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        val len = formula.length

        while (i < len && formula[i].isWhitespace()) {
            i++
        }

        if (i >= len || formula[i] != '{') {
            if (i < len) return formula[i].toString() to (i + 1)
            return "" to i
        }

        var depth = 1
        val contentStart = i + 1
        i++

        while (i < len && depth > 0) {
            val c = formula[i]
            if (c == '\\' && i + 1 < len) {
                i += 2
                continue
            }
            if (c == '{') depth++ else if (c == '}') depth--
            i++
        }

        val content = if (depth == 0) formula.substring(contentStart, i - 1) else formula.substring(contentStart, i)
        return content to i
    }

    private fun readScriptContent(formula: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        val len = formula.length
        while (i < len && formula[i].isWhitespace()) i++
        if (i >= len) return "" to i

        if (formula[i] == '{') return readBracedBlock(formula, i)
        if (formula[i] == '\\') {
            var cmdEnd = i + 1
            while (cmdEnd < len && formula[cmdEnd].isLetter()) cmdEnd++
            return formula.substring(i, cmdEnd) to cmdEnd
        }
        return formula[i].toString() to (i + 1)
    }

    private fun readWord(formula: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        val len = formula.length
        while (i < len && formula[i].isLetter()) i++
        return formula.substring(startIndex, i) to i
    }

    private fun readNumber(formula: String, startIndex: Int): Pair<String, Int> {
        var i = startIndex
        val len = formula.length
        while (i < len && (formula[i].isDigit() || formula[i] == '.')) i++
        return formula.substring(startIndex, i) to i
    }

    private fun isMathFunctionName(word: String): Boolean {
        return word in listOf("sin", "cos", "tan", "cot", "sec", "csc", "log", "ln", "lg", "exp", "min", "max", "lim", "det")
    }

    /**
     * 把一段带命令/转义的片段降成纯文本（供 `\text{…}`、上下标这类子表达式使用）。
     *
     * 统一走 [LatexSymbols]：之前的实现是十几个 `String.replace`，没登记的符号
     * （箭头、希腊字母、大型运算符…）会原封不动留在文本里。
     */
    private fun decodeLatexContent(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        val len = raw.length
        while (i < len) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= len) {
                sb.append(c)
                i++
                continue
            }
            val next = raw[i + 1]
            when (next) {
                '\\' -> { sb.append(' '); i += 2 }
                '{', '}', '%', '$', '#', '&', '_' -> { sb.append(next); i += 2 }
                ',', ';', ':', '!', ' ' -> { sb.append(' '); i += 2 }
                else -> {
                    var j = i + 1
                    while (j < len && raw[j].isLetter()) j++
                    val name = raw.substring(i + 1, j)
                    val symbol = LatexSymbols.resolve(name)
                    when {
                        // `\text{…}` 嵌套：吃掉花括号，内容递归解码（不能把括号留在正文里）
                        TEXT_LIKE_COMMANDS.contains(name) -> {
                            val (inner, nextIdx) = readBracedBlock(raw, j)
                            sb.append(decodeLatexContent(inner))
                            j = nextIdx
                        }
                        symbol != null -> sb.append(symbol.text)
                        LatexSymbols.isFunction(name) -> sb.append(LatexSymbols.functionText(name))
                        LatexSymbols.isLayoutOnly(name) -> Unit
                        LatexSymbols.takesDroppedArgument(name) -> {
                            val (_, nextIdx) = readBracedBlock(raw, j)
                            j = nextIdx
                        }
                        else -> Unit
                    }
                    i = j
                }
            }
        }
        return sb.toString()
    }

    private fun cleanLatexCommandToUnicode(raw: String): String = decodeLatexContent(raw).trim()

    private val TEXT_LIKE_COMMANDS = setOf(
        "text", "mbox", "mathrm", "mathbf", "mathit", "mathsf", "mathtt", "operatorname"
    )

    private sealed class MathToken {
        data class Text(val content: String) : MathToken()
        data class Variable(val name: String) : MathToken()
        data class Operator(val symbol: String) : MathToken()
        data class Superscript(val content: String) : MathToken()
        data class Subscript(val content: String) : MathToken()
    }
}
