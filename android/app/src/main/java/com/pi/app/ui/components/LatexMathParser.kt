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

        if (i < len && (formula[i] == ',' || formula[i] == ';' || formula[i] == ':' || formula[i] == '!')) {
            return listOf(MathToken.Text(" ")) to (i + 1)
        }

        val cmdStart = i
        while (i < len && formula[i].isLetter()) {
            i++
        }
        val cmd = formula.substring(cmdStart, i)

        when (cmd) {
            "text", "mathrm", "mathbf", "mathit" -> {
                val (content, nextIdx) = readBracedBlock(formula, i)
                val cleanContent = cleanLatexCommandToUnicode(content)
                return listOf(MathToken.Text(" $cleanContent")) to nextIdx
            }
            "frac" -> {
                val (num, nextAfterNum) = readBracedBlock(formula, i)
                val (den, nextAfterDen) = readBracedBlock(formula, nextAfterNum)
                val cleanNum = cleanLatexCommandToUnicode(num)
                val cleanDen = cleanLatexCommandToUnicode(den)
                return listOf(MathToken.Text(" ($cleanNum) / ($cleanDen) ")) to nextAfterDen
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
            "times" -> return listOf(MathToken.Operator(" × ")) to i
            "div" -> return listOf(MathToken.Operator(" ÷ ")) to i
            "approx" -> return listOf(MathToken.Operator(" ≈ ")) to i
            "pm" -> return listOf(MathToken.Operator(" ± ")) to i
            "mp" -> return listOf(MathToken.Operator(" ∓ ")) to i
            "cdot" -> return listOf(MathToken.Operator(" · ")) to i
            "leq", "le" -> return listOf(MathToken.Operator(" ≤ ")) to i
            "geq", "ge" -> return listOf(MathToken.Operator(" ≥ ")) to i
            "neq" -> return listOf(MathToken.Operator(" ≠ ")) to i
            "sim" -> return listOf(MathToken.Operator(" ~ ")) to i
            "infty" -> return listOf(MathToken.Text("∞")) to i
            "circ", "degree" -> return listOf(MathToken.Text("°")) to i
            "Omega" -> return listOf(MathToken.Text(" Ω")) to i
            "omega" -> return listOf(MathToken.Text(" ω")) to i
            "mu", "micro" -> return listOf(MathToken.Text(" μ")) to i
            "Delta" -> return listOf(MathToken.Text(" Δ")) to i
            "delta" -> return listOf(MathToken.Text(" δ")) to i
            "phi" -> return listOf(MathToken.Text(" φ")) to i
            "theta" -> return listOf(MathToken.Text(" θ")) to i
            "alpha" -> return listOf(MathToken.Text(" α")) to i
            "beta" -> return listOf(MathToken.Text(" β")) to i
            "gamma" -> return listOf(MathToken.Text(" γ")) to i
            "pi" -> return listOf(MathToken.Text(" π")) to i
            "cos" -> return listOf(MathToken.Text("cos ")) to i
            "sin" -> return listOf(MathToken.Text("sin ")) to i
            "tan" -> return listOf(MathToken.Text("tan ")) to i
            "ln" -> return listOf(MathToken.Text("ln ")) to i
            "log" -> return listOf(MathToken.Text("log ")) to i
            else -> return listOf(MathToken.Text(cmd)) to i
        }
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

    private fun cleanLatexCommandToUnicode(raw: String): String {
        var s = raw
            .replace("\\times", " × ")
            .replace("\\div", " ÷ ")
            .replace("\\approx", " ≈ ")
            .replace("\\pm", " ± ")
            .replace("\\Omega", "Ω")
            .replace("\\mu", "μ")
            .replace("\\degree", "°")
            .replace("\\circ", "°")

        var braceIdx = s.indexOf("\\text{")
        while (braceIdx != -1) {
            val (textVal, nextIdx) = readBracedBlock(s, braceIdx + "\\text".length)
            s = s.substring(0, braceIdx) + textVal + s.substring(nextIdx)
            braceIdx = s.indexOf("\\text{")
        }

        return s.trim()
    }

    private sealed class MathToken {
        data class Text(val content: String) : MathToken()
        data class Variable(val name: String) : MathToken()
        data class Operator(val symbol: String) : MathToken()
        data class Superscript(val content: String) : MathToken()
        data class Subscript(val content: String) : MathToken()
    }
}
