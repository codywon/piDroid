package com.pi.app.ui.files

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.pi.app.ui.theme.AppTheme
import java.util.Locale

/**
 * 语法词元类别。
 */
enum class CodeTokenType {
    KEYWORD,
    STRING,
    COMMENT,
    NUMBER,
    FUNCTION_CALL,
    PROPERTY_KEY,
    TYPE_OR_ANNOTATION,
    PUNCTUATION,
    PLAIN_TEXT
}

/**
 * 纯 Kotlin 词元对象（纯 JVM 测试友好）。
 */
data class CodeToken(
    val text: String,
    val type: CodeTokenType
)

/**
 * 轻量级纯 Kotlin 语法着色分词引擎（零第三方依赖，对齐 pi-web 视觉体验）。
 */
object CodeSyntaxHighlighter {

    /** 超过 1000 行按 pi-web 规范降级为纯文本，防长文件性能抖动 */
    const val MAX_HIGHLIGHT_LINES = 1000

    private val PYTHON_KEYWORDS = setOf(
        "and", "as", "assert", "async", "await", "break", "class", "continue", "def",
        "del", "elif", "else", "except", "finally", "for", "from", "global", "if",
        "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise",
        "return", "try", "while", "with", "yield", "True", "False", "None"
    )

    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "val", "var", "fun",
        "private", "public", "protected", "internal", "override", "if", "else", "when",
        "for", "while", "return", "true", "false", "null", "this", "super", "try",
        "catch", "finally", "throw", "companion", "data", "enum", "sealed", "suspend"
    )

    private val JS_KEYWORDS = setOf(
        "import", "from", "export", "default", "function", "const", "let", "var", "class",
        "return", "if", "else", "for", "while", "switch", "case", "break", "true", "false",
        "null", "undefined", "new", "this", "async", "await", "try", "catch", "throw"
    )

    private val BASH_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "while", "until",
        "do", "done", "in", "function", "return", "exit", "echo", "set", "export"
    )

    fun resolveLanguage(extOrLang: String): String {
        return when (extOrLang.lowercase(Locale.ROOT)) {
            "py", "python" -> "python"
            "yml", "yaml" -> "yaml"
            "kt", "kts", "kotlin" -> "kotlin"
            "java" -> "kotlin"
            "js", "jsx", "ts", "tsx", "javascript", "typescript" -> "javascript"
            "json" -> "json"
            "sh", "bash" -> "bash"
            else -> "text"
        }
    }

    /**
     * 对单行文本进行纯词法分词。
     */
    fun tokenizeLine(line: String, lang: String): List<CodeToken> {
        val trimmed = line.trimStart()
        val indentLen = line.length - trimmed.length
        val result = mutableListOf<CodeToken>()

        if (indentLen > 0) {
            result.add(CodeToken(line.substring(0, indentLen), CodeTokenType.PLAIN_TEXT))
        }
        if (trimmed.isEmpty()) return result

        // 全行注释优先
        if (lang == "python" || lang == "yaml" || lang == "bash") {
            if (trimmed.startsWith("#")) {
                result.add(CodeToken(trimmed, CodeTokenType.COMMENT))
                return result
            }
        } else if (trimmed.startsWith("//")) {
            result.add(CodeToken(trimmed, CodeTokenType.COMMENT))
            return result
        }

        when (lang) {
            "yaml" -> tokenizeYaml(trimmed, result)
            "python" -> tokenizeGeneral(trimmed, PYTHON_KEYWORDS, result, supportDocstrings = true)
            "kotlin" -> tokenizeGeneral(trimmed, KOTLIN_KEYWORDS, result)
            "javascript" -> tokenizeGeneral(trimmed, JS_KEYWORDS, result)
            "json" -> tokenizeJson(trimmed, result)
            "bash" -> tokenizeGeneral(trimmed, BASH_KEYWORDS, result)
            else -> result.add(CodeToken(trimmed, CodeTokenType.PLAIN_TEXT))
        }

        return result
    }

    private fun tokenizeYaml(line: String, result: MutableList<CodeToken>) {
        val colonIdx = line.indexOf(':')
        if (colonIdx > 0 && !line.startsWith("- ")) {
            val key = line.substring(0, colonIdx)
            result.add(CodeToken(key, CodeTokenType.PROPERTY_KEY))
            result.add(CodeToken(":", CodeTokenType.PUNCTUATION))

            val rest = line.substring(colonIdx + 1)
            val trimmedVal = rest.trimStart()
            val spaceCount = rest.length - trimmedVal.length
            if (spaceCount > 0) {
                result.add(CodeToken(rest.substring(0, spaceCount), CodeTokenType.PLAIN_TEXT))
            }
            if (trimmedVal.isNotEmpty()) {
                if (trimmedVal.startsWith("'") || trimmedVal.startsWith("\"")) {
                    result.add(CodeToken(trimmedVal, CodeTokenType.STRING))
                } else if (trimmedVal.startsWith("#")) {
                    result.add(CodeToken(trimmedVal, CodeTokenType.COMMENT))
                } else {
                    result.add(CodeToken(trimmedVal, CodeTokenType.PLAIN_TEXT))
                }
            }
        } else {
            result.add(CodeToken(line, CodeTokenType.PLAIN_TEXT))
        }
    }

    private fun tokenizeJson(line: String, result: MutableList<CodeToken>) {
        var idx = 0
        val len = line.length
        while (idx < len && line[idx].isWhitespace()) idx++
        if (idx > 0) {
            result.add(CodeToken(line.substring(0, idx), CodeTokenType.PLAIN_TEXT))
        }
        if (idx >= len) return

        // JSON key-value 行：以双引号开头且闭合引号后面是 ':'
        if (line[idx] == '"') {
            var keyEnd = idx + 1
            while (keyEnd < len) {
                val ch = line[keyEnd]
                if (ch == '\\' && keyEnd + 1 < len) {
                    keyEnd += 2
                    continue
                }
                if (ch == '"') {
                    keyEnd++
                    break
                }
                keyEnd++
            }
            var colonSearch = keyEnd
            while (colonSearch < len && line[colonSearch].isWhitespace()) colonSearch++
            if (colonSearch < len && line[colonSearch] == ':') {
                result.add(CodeToken(line.substring(idx, keyEnd), CodeTokenType.PROPERTY_KEY))
                if (colonSearch > keyEnd) {
                    result.add(CodeToken(line.substring(keyEnd, colonSearch), CodeTokenType.PLAIN_TEXT))
                }
                result.add(CodeToken(":", CodeTokenType.PUNCTUATION))
                tokenizeJsonValueTail(line.substring(colonSearch + 1), result)
                return
            }
        }
        tokenizeJsonValueTail(line.substring(idx), result)
    }

    private fun tokenizeJsonValueTail(tail: String, result: MutableList<CodeToken>) {
        if (tail.isEmpty()) return
        var i = 0
        val len = tail.length
        while (i < len) {
            val c = tail[i]
            when {
                c.isWhitespace() -> {
                    val start = i
                    while (i < len && tail[i].isWhitespace()) i++
                    result.add(CodeToken(tail.substring(start, i), CodeTokenType.PLAIN_TEXT))
                }
                c == '"' -> {
                    val start = i
                    i++
                    while (i < len) {
                        val ch = tail[i]
                        if (ch == '\\' && i + 1 < len) {
                            i += 2
                            continue
                        }
                        if (ch == '"') {
                            i++
                            break
                        }
                        i++
                    }
                    result.add(CodeToken(tail.substring(start, i), CodeTokenType.STRING))
                }
                c == '-' || c.isDigit() -> {
                    val start = i
                    if (tail[i] == '-') i++
                    while (i < len && (tail[i].isDigit() || tail[i] == '.' || tail[i] == 'e' || tail[i] == 'E' || tail[i] == '+' || tail[i] == '-')) {
                        i++
                    }
                    result.add(CodeToken(tail.substring(start, i), CodeTokenType.NUMBER))
                }
                c.isLetter() -> {
                    val start = i
                    while (i < len && tail[i].isLetterOrDigit()) i++
                    val word = tail.substring(start, i)
                    val type = if (word == "true" || word == "false" || word == "null") {
                        CodeTokenType.KEYWORD
                    } else {
                        CodeTokenType.PLAIN_TEXT
                    }
                    result.add(CodeToken(word, type))
                }
                c in "{}[]," -> {
                    result.add(CodeToken(c.toString(), CodeTokenType.PUNCTUATION))
                    i++
                }
                else -> {
                    result.add(CodeToken(c.toString(), CodeTokenType.PLAIN_TEXT))
                    i++
                }
            }
        }
    }

    private fun tokenizeGeneral(
        line: String,
        keywords: Set<String>,
        result: MutableList<CodeToken>,
        supportDocstrings: Boolean = false
    ) {
        var i = 0
        val len = line.length

        while (i < len) {
            val c = line[i]

            // 1. Docstrings 或普通字符串
            if (supportDocstrings && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                val quote = line.substring(i, i + 3)
                val endIdx = line.indexOf(quote, i + 3)
                val chunk = if (endIdx >= 0) line.substring(i, endIdx + 3) else line.substring(i)
                result.add(CodeToken(chunk, CodeTokenType.STRING))
                i += chunk.length
                continue
            } else if (c == '"' || c == '\'') {
                var end = i + 1
                while (end < len) {
                    val ch = line[end]
                    if (ch == '\\' && end + 1 < len) {
                        end += 2
                        continue
                    }
                    if (ch == c) {
                        end++
                        break
                    }
                    end++
                }
                val chunk = line.substring(i, end)
                result.add(CodeToken(chunk, CodeTokenType.STRING))
                i += chunk.length
                continue
            }

            // 2. 行尾注释
            if (c == '#' || (c == '/' && i + 1 < len && line[i + 1] == '/')) {
                result.add(CodeToken(line.substring(i), CodeTokenType.COMMENT))
                break
            }

            // 3. 标识符 / 关键字 / 函数调用
            if (c.isLetter() || c == '_') {
                val startIdx = i
                while (i < len && (line[i].isLetterOrDigit() || line[i] == '_')) {
                    i++
                }
                val word = line.substring(startIdx, i)
                if (keywords.contains(word)) {
                    result.add(CodeToken(word, CodeTokenType.KEYWORD))
                } else if (i < len && line[i] == '(') {
                    result.add(CodeToken(word, CodeTokenType.FUNCTION_CALL))
                } else {
                    result.add(CodeToken(word, CodeTokenType.PLAIN_TEXT))
                }
                continue
            }

            // 4. 数字
            if (c.isDigit()) {
                val startIdx = i
                while (i < len && (line[i].isDigit() || line[i] == '.' || line[i] == 'x' || line[i] == 'L')) {
                    i++
                }
                result.add(CodeToken(line.substring(startIdx, i), CodeTokenType.NUMBER))
                continue
            }

            // 5. 符号标点
            result.add(CodeToken(c.toString(), CodeTokenType.PUNCTUATION))
            i++
        }
    }

    /**
     * 将单行的词元列表转换为带样式的 [AnnotatedString]。
     */
    fun buildLineAnnotatedString(tokens: List<CodeToken>, theme: AppTheme): AnnotatedString {
        return buildAnnotatedString {
            for (token in tokens) {
                val start = length
                append(token.text)
                val style = when (token.type) {
                    CodeTokenType.KEYWORD -> SpanStyle(color = theme.primary, fontWeight = FontWeight.Bold)
                    CodeTokenType.PROPERTY_KEY -> SpanStyle(color = theme.primary, fontWeight = FontWeight.SemiBold)
                    CodeTokenType.STRING -> SpanStyle(color = theme.success)
                    CodeTokenType.COMMENT -> SpanStyle(color = theme.textMuted, fontStyle = FontStyle.Italic)
                    CodeTokenType.NUMBER -> SpanStyle(color = theme.warning)
                    CodeTokenType.FUNCTION_CALL -> SpanStyle(color = theme.featureThinking)
                    CodeTokenType.PUNCTUATION -> SpanStyle(color = theme.textSecondary)
                    CodeTokenType.TYPE_OR_ANNOTATION -> SpanStyle(color = theme.featureThinking)
                    CodeTokenType.PLAIN_TEXT -> SpanStyle(color = theme.textPrimary)
                }
                addStyle(style, start, length)
            }
        }
    }

    /**
     * 将代码字符串转换为带主题样式的 [AnnotatedString]。
     */
    fun highlight(
        code: String,
        extOrLang: String,
        theme: AppTheme
    ): AnnotatedString {
        val lines = code.split('\n')
        if (lines.size > MAX_HIGHLIGHT_LINES) {
            return buildAnnotatedString {
                append(code)
                addStyle(SpanStyle(color = theme.textPrimary, fontFamily = FontFamily.Monospace), 0, code.length)
            }
        }

        val lang = resolveLanguage(extOrLang)
        return buildAnnotatedString {
            lines.forEachIndexed { idx, line ->
                val tokens = tokenizeLine(line, lang)
                for (token in tokens) {
                    val start = length
                    append(token.text)
                    val style = when (token.type) {
                        CodeTokenType.KEYWORD -> SpanStyle(color = theme.primary, fontWeight = FontWeight.Bold)
                        CodeTokenType.PROPERTY_KEY -> SpanStyle(color = theme.primary, fontWeight = FontWeight.SemiBold)
                        CodeTokenType.STRING -> SpanStyle(color = theme.success)
                        CodeTokenType.COMMENT -> SpanStyle(color = theme.textMuted, fontStyle = FontStyle.Italic)
                        CodeTokenType.NUMBER -> SpanStyle(color = theme.warning)
                        CodeTokenType.FUNCTION_CALL -> SpanStyle(color = theme.featureThinking)
                        CodeTokenType.PUNCTUATION -> SpanStyle(color = theme.textSecondary)
                        CodeTokenType.TYPE_OR_ANNOTATION -> SpanStyle(color = theme.featureThinking)
                        CodeTokenType.PLAIN_TEXT -> SpanStyle(color = theme.textPrimary)
                    }
                    addStyle(style, start, length)
                }
                if (idx < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }
}
