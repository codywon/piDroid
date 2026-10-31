package com.pi.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.data.model.MediaAttachment
import com.pi.app.data.model.MediaKind
import com.pi.app.ui.theme.LocalAppTheme
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.Image as MdImage
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MdText
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.util.Locale

/**
 * 大模型友好型 CJK Markdown 与多行块级公式规整引擎
 */
object CjkMarkdownNormalizer {
    // 仅保护三反引号/三波浪线多行围栏代码块，避免单反引号保护导致外部粘连/孤立反引号把大片正文误识别为代码块
    private val FENCED_CODE_BLOCK_PATTERN = Regex("""(?m)^[ \t]*(`{3,}|~{3,})[^\n]*\n[\s\S]*?\n[ \t]*\1[ \t]*$|```[\s\S]*?```|~~~[\s\S]*?~~~""")
    private val BLOCK_MATH_DOLLAR_REGEX = Regex("""\$\$\s*([\s\S]+?)\s*\$\$""")
    private val BLOCK_MATH_BRACKET_REGEX = Regex("""\\\[\s*([\s\S]+?)\s*\\\]""")
    private val INLINE_MATH_PAREN_REGEX = Regex("""\\\(\s*([^\n]+?)\s*\\\)""")
    private val PSEUDO_LIST_REGEX = Regex("""(?m)^([\t ]*)[•◦▪◆]\s+""")
    private val INNER_BOLD_WHITESPACE_ASTERISK = Regex("""\*\*[\t ]*([^*]+?)[\t ]*\*\*""")
    private val INNER_BOLD_WHITESPACE_UNDERSCORE = Regex("""__[\t ]*([^_]+?)[\t ]*__""")

    private val TABLE_STICKY_ROW_REGEX = Regex("""\s\|\|\s""")

    /** 行内分隔行（如 `|---|`）——仅在它之前还有表头内容、且后面再无内容时才会被断行。 */
    private val TABLE_INLINE_DELIMITER = Regex("""\|(?:\s*:?-{2,}:?\s*\|)+""")
    private val TABLE_ATTACHED_HEADER_REGEX = Regex("""(?m)^([^\n|]+)\n(\|[^\n]+\|\s*\n\|[\s:\-|]+\|)""")
    private val SQL_BACKTICK_QUOTE_1 = Regex("""`([^`'\n]+)'""")
    private val SQL_BACKTICK_QUOTE_2 = Regex("""'([^`'\n]+)`""")
    private val CHINESE_ISOLATED_BACKTICK_REGEX = Regex("""(?<=[\u4e00-\u9fa5，。！？；：“”‘’（）\s]|^)`(?=[\u4e00-\u9fa5，。！？；：“”‘’（）\s]|$)""")

    fun normalize(rawMarkdown: String): String {
        if (rawMarkdown.isEmpty()) return rawMarkdown

        val matches = FENCED_CODE_BLOCK_PATTERN.findAll(rawMarkdown).toList()
        if (matches.isEmpty()) {
            return fixSegment(rawMarkdown)
        }

        val sb = StringBuilder()
        var lastEnd = 0
        for (m in matches) {
            val start = m.range.first
            val end = m.range.last + 1
            if (start > lastEnd) {
                sb.append(fixSegment(rawMarkdown.substring(lastEnd, start)))
            }
            sb.append(m.value)
            lastEnd = end
        }
        if (lastEnd < rawMarkdown.length) {
            sb.append(fixSegment(rawMarkdown.substring(lastEnd)))
        }
        return sb.toString()
    }

    private val RAW_SVG_BLOCK_REGEX = Regex("""<svg\b[\s\S]*?</svg>""", RegexOption.IGNORE_CASE)

    /** 已成对的行内代码（不跨行）。 */
    private val INLINE_CODE_SPAN = Regex("""`[^`\n]+`""")

    /**
     * 修复孤立的反引号，但**先保护已成对的行内代码**。
     *
     * 旧实现在全文上直接跑 SQL 引号修复（如 status=`0'），会把行内代码自己拆掉：
     * 反引号包裹的 SQL 里带单引号是常态，被改写后反引号奇偶被打乱，
     * 解析器只能把后面一大段正文当成 Code 节点——正文里那些「粉色大块」就是这么来的。
     */
    private fun healBrokenInlineBackticks(text: String): String {
        if (!text.contains('`')) return text
        val spans = INLINE_CODE_SPAN.findAll(text).toList()
        if (spans.isEmpty()) return healLooseBackticks(text)

        val sb = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.range.first < cursor) continue
            sb.append(healLooseBackticks(text.substring(cursor, span.range.first)))
            sb.append(span.value)
            cursor = span.range.last + 1
        }
        if (cursor < text.length) sb.append(healLooseBackticks(text.substring(cursor)))
        return sb.toString()
    }

    /** 仅作用于「不在成对行内代码里」的自由文本。 */
    private fun healLooseBackticks(text: String): String {
        if (!text.contains('`')) return text
        // 1. 修复 SQL 单引号与反引号混写（如 status=`0' 或 status='0` ）
        val s = text.replace(SQL_BACKTICK_QUOTE_1, "'$1'")
            .replace(SQL_BACKTICK_QUOTE_2, "'$1'")

        // 2. 检查跨中文句逗标点未闭合的孤立反引号，避免整段正文被误染成单行 Code 节点
        val lines = s.split("\n")
        val healed = lines.map { line ->
            val count = line.count { it == '`' }
            if (count % 2 != 0) {
                val cleaned = line.replace(CHINESE_ISOLATED_BACKTICK_REGEX, "'")
                if (cleaned.count { it == '`' } % 2 != 0) {
                    val lastIdx = cleaned.lastIndexOf('`')
                    if (lastIdx >= 0) cleaned.substring(0, lastIdx) + "'" + cleaned.substring(lastIdx + 1) else cleaned
                } else {
                    cleaned
                }
            } else {
                line
            }
        }
        return healed.joinToString("\n")
    }

    /**
     * 只修复「确定被压平」的表格，绝不动合法表格：
     *
     * 旧实现的两条正则会切碎合法表格：
     * 1) `\|\s*\|(?=\s*\w)` 会把表头的空首列 `| | SaaS …` 拆成两行；
     * 2) 分隔行插换行正则的 `\s*` 会吃掉表头行末的换行，在「表头行」与「|---|」之间插入空行，
     *    于是 GFM 不再识别为表格，整张表退化成普通段落（管道符原样显示）。
     *
     * 现在仅处理两种真实形变：行内 ` || ` 行边界、表头与分隔行粘在同一行。
     */
    private fun repairJammedTables(text: String): String {
        if (!text.contains('|')) return text
        val out = ArrayList<String>()
        for (rawLine in text.split("\n")) {
            val pieces: List<String> = if (
                rawLine.count { it == '|' } >= 4 && TABLE_STICKY_ROW_REGEX.containsMatchIn(rawLine)
            ) {
                rawLine.replace(TABLE_STICKY_ROW_REGEX, " |\n| ")
                    .split("\n")
                    .map { it.trimEnd() }
            } else {
                listOf(rawLine)
            }
            for (piece in pieces) {
                val delimiter = TABLE_INLINE_DELIMITER.find(piece)
                val head = delimiter?.let { piece.substring(0, it.range.first) }
                val tail = delimiter?.let { piece.substring(it.range.last + 1) }
                // 仅当分隔行前面确实还有表格内容（含管道符）时才断行；
                // 否则 `> | :--- | :--- |` 这类引用块内的分隔行会被误拆掉引用前缀。
                if (delimiter != null && head != null && tail != null &&
                    head.contains('|') && tail.isBlank()
                ) {
                    val prefix = head.substring(0, head.indexOf('|'))
                    out.add(head.trimEnd())
                    out.add(prefix + delimiter.value)
                } else {
                    out.add(piece)
                }
            }
        }
        return out.joinToString("\n")
    }

    private fun fixSegment(text: String): String {
        var s = healBrokenInlineBackticks(text)

        // 0.0 将独立内联 <svg>...</svg> 转为 ```svg 代码块以便统一渲染矢量图与全屏预览
        if (s.contains("<svg", ignoreCase = true) && s.contains("</svg>", ignoreCase = true)) {
            s = RAW_SVG_BLOCK_REGEX.replace(s) { m ->
                "\n```svg\n${m.value.trim()}\n```\n"
            }
        }

        // 0. 将独立块级公式 $$...$$ 与 \[...\] 转为 ```math 代码块以便渲染独立公式卡片
        if (s.contains("$$")) {
            s = BLOCK_MATH_DOLLAR_REGEX.replace(s) { m ->
                val inner = m.groupValues[1].trim()
                if (inner.contains("\n") || inner.length > 18) {
                    "\n```math\n$inner\n```\n"
                } else {
                    "$$${inner}$$"
                }
            }
        }
        if (s.contains("\\[")) {
            s = BLOCK_MATH_BRACKET_REGEX.replace(s) { m ->
                "\n```math\n${m.groupValues[1].trim()}\n```\n"
            }
        }
        if (s.contains("\\(")) {
            s = INLINE_MATH_PAREN_REGEX.replace(s) { m ->
                "$${m.groupValues[1].trim()}$"
            }
        }

        // 1. 自动修复表格：若首行紧贴上方普通文本，自动补充换行空行以触发 GFM 表格解析
        s = s.replace(TABLE_ATTACHED_HEADER_REGEX) { m ->
            "${m.groupValues[1]}\n\n${m.groupValues[2]}"
        }
        if (s.contains("|") && s.contains("-")) {
            s = repairJammedTables(s)
        }

        // 2. 规范化伪列表符号
        if (s.contains("•") || s.contains("◦") || s.contains("▪")) {
            s = PSEUDO_LIST_REGEX.replace(s) { "${it.groupValues[1]}- " }
        }

        // 3. 粗体归一化与孤立清理（移除有损的强制插入空格正则，杜绝 ** ( 裸露）
        if (s.contains("*") || s.contains("_")) {
            s = INNER_BOLD_WHITESPACE_ASTERISK.replace(s) { "**${it.groupValues[1]}**" }
            s = INNER_BOLD_WHITESPACE_UNDERSCORE.replace(s) { "__${it.groupValues[1]}__" }
            val count = s.windowed(2).count { it == "**" }
            if (count % 2 != 0) {
                val lastIdx = s.lastIndexOf("**")
                if (lastIdx >= 0) {
                    s = s.substring(0, lastIdx) + s.substring(lastIdx + 2)
                }
            }
        }

        return s
    }
}

/**
 * 现代标准 CommonMark AST 富文本、公式、流程图、脑图、统计图表与表格渲染器
 */

/**
 * 扩展与 Parser 建为单例：解析器构造包含扩展初始化，不能在每个流式 token
 * 到达时重建（原先它在 remember(content) 内部，等于每来一个 token 都新建一套）。
 */
private val MARKDOWN_EXTENSIONS = listOf(
    TablesExtension.create(),
    StrikethroughExtension.create(),
    AutolinkExtension.create()
)

private val MARKDOWN_PARSER: Parser by lazy {
    Parser.builder().extensions(MARKDOWN_EXTENSIONS).build()
}

/** 超过此长度才做块级切分；短消息整体解析更省事，也避开跨块引用定义的差异。 */
private const val BLOCK_CACHE_MIN_CHARS = 1200

/** 这些多行 HTML 容器可能跨空行包裹内容，出现时整篇解析，不切分。 */
private val MULTILINE_HTML_CONTAINER = Regex(
    "<(details|summary|table|thead|tbody|div|section|script|style|pre|textarea)\\b",
    RegexOption.IGNORE_CASE
)
private val BLOCK_FENCE_MARKER = Regex("^ {0,3}(`{3,}|~{3,})")
private val BLOCK_LIST_ITEM = Regex("^(?:[*+-]|\\d{1,9}[.)])(?:[ \\t]|$)")
private val BLOCK_INLINE_CODE = Regex("`[^`]*`")
private val BLOCK_FRONT_MATTER_OPEN = Regex("^---[ \\t]*\\r?$")

/**
 * 把规范化后的 Markdown 按「顶层块边界」切开。
 *
 * 只有同时满足以下条件才切：空行之后、从第 0 列开始、不是列表项、不在代码围栏内、
 * 不在块公式内。这样已完成的块在重组时字节不变，能命中 [remember] 缓存，
 * 流式输出只需重解析末尾变化的那一块——否则每个 token 都要重解析整篇，是 O(n²)。
 *
 * 代价：跨块的链接引用定义（`[ref]: url`）不再共享。LLM 输出中极少出现，
 * 且短消息走整篇解析路径。
 */
internal fun splitTopLevelMarkdownBlocks(markdown: String): List<String> {
    if (markdown.isEmpty()) return emptyList()
    if (MULTILINE_HTML_CONTAINER.containsMatchIn(markdown)) return listOf(markdown)

    val lines = markdown.split('\n')
    if (lines.isNotEmpty() && BLOCK_FRONT_MATTER_OPEN.matches(lines[0])) return listOf(markdown)

    val blocks = mutableListOf<String>()
    var current = mutableListOf<String>()
    var fence: Pair<Char, Int>? = null
    var inMath = false
    var afterBlank = false

    for (line in lines) {
        val blank = line.isBlank()
        if (afterBlank && !blank && fence == null && !inMath && current.isNotEmpty() &&
            !line.startsWith(" ") && !line.startsWith("\t") && !BLOCK_LIST_ITEM.containsMatchIn(line)
        ) {
            blocks.add(current.joinToString("\n") + "\n")
            current = mutableListOf()
        }
        current.add(line)

        val fenceMatch = BLOCK_FENCE_MARKER.find(line)
        if (fenceMatch != null) {
            val marker = fenceMatch.groupValues[1][0]
            val size = fenceMatch.groupValues[1].length
            val open = fence
            if (open == null) {
                fence = marker to size
            } else if (marker == open.first && size >= open.second) {
                fence = null
            }
        } else if (fence == null) {
            // 行内代码里的 $$ 不算块公式界符
            val prose = BLOCK_INLINE_CODE.replace(line, "")
            var odd = false
            var idx = prose.indexOf("$$")
            while (idx >= 0) {
                odd = !odd
                idx = prose.indexOf("$$", idx + 2)
            }
            if (odd) inMath = !inMath
        }
        afterBlank = blank
    }
    if (current.isNotEmpty()) blocks.add(current.joinToString("\n"))
    return blocks.ifEmpty { listOf(markdown) }
}

@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isUser: Boolean = false,
    /**
     * 链接点击回调：由调用方决定「本地文件 → 打开预览 / 网页 → 浏览器」。
     * 复刻 pi-web MarkdownBody 的 `onOpenFile`：链接在 pi-web 里不是跳转，而是拦下来开预览。
     * 传 null 时链接只做样式（保持旧行为），设置面板等场景不需要可点。
     */
    onOpenLink: ((href: String) -> Unit)? = null
) {
    if (content.isBlank()) return

    val blocks = remember(content) {
        val normalized = CjkMarkdownNormalizer.normalize(content)
        if (normalized.length >= BLOCK_CACHE_MIN_CHARS) {
            splitTopLevelMarkdownBlocks(normalized)
        } else {
            listOf(normalized)
        }
    }

    SelectionContainer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                MarkdownBlock(block, isUser, onOpenLink)
            }
        }
    }
}

/** 一个顶层块。只在此块文本变化时重解析，流式输出时其余块命中缓存。 */
@Composable
private fun MarkdownBlock(
    markdown: String,
    isUser: Boolean,
    onOpenLink: ((String) -> Unit)? = null
) {
    // 解析结果与回调无关，链接点击在渲染时注入，所以 remember 的 key 里不放 onOpenLink。
    val document = remember(markdown) { MARKDOWN_PARSER.parse(markdown) }
    var childNode = document.firstChild
    while (childNode != null) {
        RenderAstBlockNode(node = childNode, isUser = isUser, onOpenLink = onOpenLink)
        childNode = childNode.next
    }
}

@Composable
private fun RenderAstBlockNode(
    node: Node,
    isUser: Boolean,
    onOpenLink: ((String) -> Unit)? = null
) {
    val theme = LocalAppTheme.current

    when (node) {
        is Heading -> {
            val fontSize = when (node.level) {
                1 -> 16.sp
                2 -> 15.sp
                3 -> 14.sp
                else -> 13.sp
            }
            val annotated = buildInlineAnnotatedString(node, isUser, onOpenLink)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 2.dp)
            ) {
                Text(
                    text = annotated,
                    style = TextStyle(
                        fontSize = fontSize,
                        fontWeight = FontWeight.Bold,
                        color = theme.textPrimary,
                        letterSpacing = (-0.2).sp,
                        lineHeight = (fontSize.value * 1.35f).sp
                    )
                )
                if (node.level <= 2) {
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider(
                        color = theme.outline.copy(alpha = 0.2f),
                        thickness = 0.6.dp
                    )
                }
            }
        }

        is Paragraph -> {
            val inlineMedia = remember(node) { extractInlineMediaFromNode(node) }
            // 音频/视频不归相册管：交给已有的全屏查看器（那里有真正的播放器）
            val playbackLinks = remember(node) { extractPlaybackLinksFromNode(node) }
            val annotated = buildInlineAnnotatedString(node, isUser, onOpenLink)
            val rawTrimmed = annotated.text.trim()
            if (rawTrimmed.startsWith("$$") && rawTrimmed.endsWith("$$") && rawTrimmed.length > 4) {
                MathFormulaBlockCard(formula = rawTrimmed.removeSurrounding("$$").trim())
            } else {
                if (rawTrimmed.isNotBlank()) {
                    Text(
                        text = annotated,
                        style = TextStyle(
                            fontSize = 13.5.sp,
                            color = theme.textPrimary,
                            lineHeight = 20.sp,
                            letterSpacing = 0.1.sp
                        )
                    )
                }
                if (playbackLinks.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        playbackLinks.forEach { (href, label, isVideo) ->
                            MediaPlaybackChip(
                                label = label,
                                isVideo = isVideo,
                                onOpen = { onOpenLink?.invoke(href) }
                            )
                        }
                    }
                }
                if (inlineMedia.isNotEmpty()) {
                    MediaAttachmentGallery(mediaList = inlineMedia)
                }
            }
        }

        is HtmlBlock -> {
            val rawHtml = node.literal?.trim() ?: ""
            if (rawHtml.contains("<svg", ignoreCase = true) && rawHtml.contains("</svg>", ignoreCase = true)) {
                val svgMedia = remember(rawHtml) {
                    listOf(
                        MediaAttachment(
                            id = "html_svg_${rawHtml.hashCode()}",
                            kind = MediaKind.SVG,
                            mimeType = "image/svg+xml",
                            title = "SVG 矢量图",
                            rawContent = rawHtml
                        )
                    )
                }
                MediaAttachmentGallery(mediaList = svgMedia)
            } else if (rawHtml.isNotBlank()) {
                // 其余 HTML 按原文展示（pi-web #1444）：用户贴的 `<Component … />`、
                // 模型回显的一小段 HTML 以前会直接消失，看着就像“内容丢了”。
                // 不当富文本渲染 —— 不执行、不解释，只当代码看。
                CodeBlockCard(
                    code = rawHtml,
                    language = "html"
                )
            }
        }

        is FencedCodeBlock -> {
            val lang = (node.info ?: "").trim().lowercase(Locale.ROOT)
            val codeText = node.literal.trimEnd()
            // SVG 可能是 ```svg、也可能是 ```html / ```xml / 无语言标记的贴图，
            // 甚至带 `<?xml ...?>` 头（`.svg` 文件本体就是这样）。这些都应该画出来，
            // 而不是当成一段源码展示。
            val looksLikeSvgMarkup = codeText.contains("<svg", ignoreCase = true) &&
                codeText.contains("</svg>", ignoreCase = true)
            when {
                lang in listOf("svg", "xml+svg") ||
                    (codeText.trimStart().startsWith("<svg", ignoreCase = true) && looksLikeSvgMarkup) ||
                    (looksLikeSvgMarkup && lang in listOf("", "html", "htm", "xml")) -> {
                    val svgAttachment = remember(codeText) {
                        listOf(
                            MediaAttachment(
                                id = "fenced_svg_${codeText.hashCode()}",
                                kind = MediaKind.SVG,
                                mimeType = "image/svg+xml",
                                title = "SVG 矢量图形",
                                rawContent = codeText
                            )
                        )
                    }
                    MediaAttachmentGallery(mediaList = svgAttachment)
                }
                lang in listOf("mermaid", "flowchart", "mindmap", "sequence") || isLikelyMermaidCode(codeText) -> {
                    MermaidDiagramCard(code = codeText, language = lang.ifBlank { "mermaid" })
                }
                lang in listOf("math", "latex", "tex", "katex") -> {
                    MathFormulaBlockCard(formula = codeText)
                }
                lang in listOf("chart", "pie", "bar", "echarts") -> {
                    MermaidDiagramCard(code = codeText, language = "chart")
                }
                lang in listOf("diff", "patch") -> {
                    DiffCodeBlockCard(code = codeText, language = lang)
                }
                else -> {
                    CodeBlockCard(code = codeText, language = node.info ?: "")
                }
            }
        }

        is IndentedCodeBlock -> {
            CodeBlockCard(code = node.literal.trimEnd(), language = "")
        }

        is TableBlock -> {
            AstTableCard(tableNode = node, isUser = isUser, onOpenLink = onOpenLink)
        }

        is ThematicBreak -> {
            HorizontalDivider(
                color = theme.outline.copy(alpha = 0.2f),
                thickness = 0.8.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        is BlockQuote -> {
            // 检测 GitHub Callouts: > [!NOTE], > [!TIP], > [!IMPORTANT], > [!WARNING], > [!CAUTION]
            val firstParaText = extractFirstParagraphText(node)
            val calloutMatch = CALLOUT_HEADER_REGEX.find(firstParaText)
            if (calloutMatch != null) {
                val calloutType = calloutMatch.groupValues[1].uppercase(Locale.ROOT)
                val (accentColor, labelZh, iconVec) = when (calloutType) {
                    "TIP" -> Triple(Color(0xFF16A34A), "提示 (Tip)", Icons.Default.Lightbulb)
                    "IMPORTANT" -> Triple(Color(0xFF8B5CF6), "重要 (Important)", Icons.Default.PriorityHigh)
                    "WARNING" -> Triple(Color(0xFFD97706), "警告 (Warning)", Icons.Default.Warning)
                    "CAUTION" -> Triple(Color(0xFFDC2626), "危险 (Caution)", Icons.Default.ErrorOutline)
                    else -> Triple(Color(0xFF2563EB), "说明 (Note)", Icons.Default.Info)
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = accentColor.copy(alpha = if (theme.isDark) 0.12f else 0.07f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(iconVec, contentDescription = null, tint = accentColor, modifier = Modifier.size(15.dp))
                            Text(
                                text = labelZh,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                        val bodyCleaned = firstParaText.replaceFirst(CALLOUT_HEADER_REGEX, "").trim()
                        if (bodyCleaned.isNotBlank()) {
                            Text(
                                text = bodyCleaned,
                                fontSize = 13.sp,
                                color = theme.textPrimary,
                                lineHeight = 19.sp
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                    color = theme.surfaceContainer.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.5.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(theme.primary.copy(alpha = 0.8f))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            var quoteChild = node.firstChild
                            while (quoteChild != null) {
                                RenderAstBlockNode(node = quoteChild, isUser = isUser, onOpenLink = onOpenLink)
                                quoteChild = quoteChild.next
                            }
                        }
                    }
                }
            }
        }

        is BulletList -> {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        val firstPara = item.firstChild as? Paragraph
                        val firstLiteral = (firstPara?.firstChild as? MdText)?.literal ?: ""
                        val isTaskChecked = firstLiteral.startsWith("[x] ", ignoreCase = true)
                        val isTaskUnchecked = firstLiteral.startsWith("[ ] ")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (isTaskChecked || isTaskUnchecked) {
                                Icon(
                                    imageVector = if (isTaskChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                    contentDescription = null,
                                    tint = if (isTaskChecked) Color(0xFF16A34A) else theme.textMuted,
                                    modifier = Modifier
                                        .padding(top = 2.5.dp, start = 2.dp, end = 7.dp)
                                        .size(15.dp)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .padding(top = 7.5.dp, start = 4.dp, end = 9.dp)
                                        .size(5.dp)
                                        .clip(CircleShape)
                                        .background(theme.primary)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                var itemChild = item.firstChild
                                while (itemChild != null) {
                                    RenderAstBlockNode(node = itemChild, isUser = isUser, onOpenLink = onOpenLink)
                                    itemChild = itemChild.next
                                }
                            }
                        }
                    }
                    item = item.next
                }
            }
        }

        is OrderedList -> {
            var index = node.startNumber
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                var item = node.firstChild
                while (item != null) {
                    if (item is ListItem) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "$index.",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textSecondary
                                ),
                                modifier = Modifier
                                    .widthIn(min = 20.dp)
                                    .padding(top = 1.dp, end = 6.dp)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                var itemChild = item.firstChild
                                while (itemChild != null) {
                                    RenderAstBlockNode(node = itemChild, isUser = isUser, onOpenLink = onOpenLink)
                                    itemChild = itemChild.next
                                }
                            }
                        }
                        index++
                    }
                    item = item.next
                }
            }
        }
    }
}

private val CALLOUT_HEADER_REGEX = Regex("""^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\]\s*""", RegexOption.IGNORE_CASE)
private val TASK_PREFIX_REGEX = Regex("""^\[[ xX]\]\s+""")

private fun extractFirstParagraphText(blockQuote: BlockQuote): String {
    val first = blockQuote.firstChild as? Paragraph ?: return ""
    val sb = StringBuilder()
    var c = first.firstChild
    while (c != null) {
        when (c) {
            is MdText -> sb.append(c.literal)
            is SoftLineBreak, is HardLineBreak -> sb.append("\n")
        }
        c = c.next
    }
    return sb.toString()
}

private fun isLikelyMermaidCode(code: String): Boolean {
    val firstLine = code.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return false
    return firstLine.startsWith("graph ") ||
        firstLine.startsWith("flowchart ") ||
        firstLine.startsWith("sequenceDiagram") ||
        firstLine.startsWith("mindmap") ||
        firstLine.startsWith("pie") ||
        firstLine.startsWith("stateDiagram") ||
        firstLine.startsWith("classDiagram") ||
        firstLine.startsWith("erDiagram") ||
        firstLine.startsWith("gantt")
}

private val AUDIO_EXTS = setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "opus")
private val VIDEO_EXTS = setOf("mp4", "webm", "mkv", "mov", "avi")

private data class PlaybackLink(val href: String, val label: String, val isVideo: Boolean)

private fun extractPlaybackLinksFromNode(parentNode: Node): List<PlaybackLink> {
    val list = mutableListOf<PlaybackLink>()
    fun checkHref(dest: String, labelCandidate: String?) {
        val clean = dest.trim()
        if (clean.isBlank()) return
        val ext = clean.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)
        val isAudio = ext in AUDIO_EXTS
        val isVideo = ext in VIDEO_EXTS
        if (isAudio || isVideo) {
            val label = labelCandidate?.ifBlank { null }
                ?: clean.substringAfterLast('/').substringBefore('?').ifBlank { if (isVideo) "视频媒体" else "音频媒体" }
            list.add(PlaybackLink(clean, label, isVideo))
        }
    }
    fun walk(n: Node) {
        var c = n.firstChild
        while (c != null) {
            when (c) {
                is MdImage -> {
                    val dest = c.destination?.trim().orEmpty()
                    val alt = c.title?.ifBlank { null } ?: (c.firstChild as? MdText)?.literal?.ifBlank { null }
                    checkHref(dest, alt)
                }
                is Link -> {
                    val href = c.destination?.trim().orEmpty()
                    val text = (c.firstChild as? MdText)?.literal?.ifBlank { null }
                    checkHref(href, text)
                }
                else -> walk(c)
            }
            c = c.next
        }
    }
    walk(parentNode)
    return list
}

@Composable
private fun MediaPlaybackChip(
    label: String,
    isVideo: Boolean,
    onOpen: () -> Unit
) {
    val theme = LocalAppTheme.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, theme.outline.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = if (isVideo) "播放视频" else "播放音频",
                tint = theme.primary,
                modifier = Modifier.size(16.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = theme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isVideo) "点击在内置播放器中打开视频" else "点击在内置播放器中播放音频",
                    fontSize = 10.5.sp,
                    color = theme.textMuted
                )
            }
        }
    }
}

private fun extractInlineMediaFromNode(parentNode: Node): List<MediaAttachment> {
    val list = mutableListOf<MediaAttachment>()
    fun walk(n: Node) {
        var c = n.firstChild
        while (c != null) {
            if (c is MdImage) {
                val dest = c.destination?.trim() ?: ""
                if (dest.isNotBlank()) {
                    val altTitle = c.title?.ifBlank { null }
                        ?: (c.firstChild as? MdText)?.literal?.ifBlank { null }
                        ?: dest.substringAfterLast('/').substringBefore('?').ifBlank { "图像预览" }
                    val lower = dest.lowercase(Locale.ROOT).substringBefore('?')
                    val ext = lower.substringAfterLast('.', "")
                    // 音频与视频有专门的播放入口（见 extractPlaybackLinksFromNode），不当静态图片塞进画廊
                    if (ext in AUDIO_EXTS || ext in VIDEO_EXTS) {
                        walk(c)
                        c = c.next
                        continue
                    }
                    val kind = when {
                        lower.endsWith(".svg") || lower.startsWith("data:image/svg") -> MediaKind.SVG
                        lower.endsWith(".pdf") || lower.startsWith("data:application/pdf") -> MediaKind.PDF
                        else -> MediaKind.IMAGE
                    }
                    val mime = when (kind) {
                        MediaKind.SVG -> "image/svg+xml"
                        MediaKind.PDF -> "application/pdf"
                        else -> "image/png"
                    }
                    list.add(
                        MediaAttachment(
                            id = "md_img_${dest.hashCode()}_${list.size}",
                            kind = kind,
                            mimeType = mime,
                            title = altTitle,
                            filePath = dest
                        )
                    )
                }
            } else {
                walk(c)
            }
            c = c.next
        }
    }
    walk(parentNode)
    return list
}

private fun buildInlineAnnotatedString(
    parentNode: Node,
    isUser: Boolean,
    onOpenLink: ((String) -> Unit)? = null
): AnnotatedString {
    val initial = buildAnnotatedString {
        appendInlineChildren(parentNode, this, isUser, onOpenLink)
    }
    return fixUnrenderedBoldInAnnotatedString(initial)
}

private val GLOBAL_FALLBACK_BOLD_REGEX = Regex("""\*\*[\t ]*([^*]+?)[\t ]*\*\*""")

private fun fixUnrenderedBoldInAnnotatedString(source: AnnotatedString): AnnotatedString {
    val rawText = source.text
    if (!rawText.contains("**")) return source
    if (!GLOBAL_FALLBACK_BOLD_REGEX.containsMatchIn(rawText)) return source

    return buildAnnotatedString {
        var cursor = 0
        GLOBAL_FALLBACK_BOLD_REGEX.findAll(rawText).forEach { match ->
            val matchStart = match.range.first
            val matchEnd = match.range.last + 1
            val innerBoldContent = match.groupValues[1]

            if (matchStart > cursor) {
                append(source.subSequence(cursor, matchStart))
            }

            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(innerBoldContent)
            pop()

            cursor = matchEnd
        }
        if (cursor < rawText.length) {
            append(source.subSequence(cursor, rawText.length))
        }
    }
}

private fun appendInlineChildren(
    parent: Node,
    builder: AnnotatedString.Builder,
    isUser: Boolean,
    onOpenLink: ((String) -> Unit)? = null
) {
    var child = parent.firstChild
    var isFirstChild = true
    while (child != null) {
        when (child) {
            is MdText -> {
                val literal = if (isFirstChild && parent.parent is ListItem) {
                    child.literal.replaceFirst(TASK_PREFIX_REGEX, "")
                } else {
                    child.literal
                }
                appendMdTextWithFallbacks(literal, builder, isUser)
            }
            is StrongEmphasis -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineChildren(child, builder, isUser, onOpenLink)
                builder.pop()
            }
            is Emphasis -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlineChildren(child, builder, isUser, onOpenLink)
                builder.pop()
            }
            is Strikethrough -> {
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInlineChildren(child, builder, isUser, onOpenLink)
                builder.pop()
            }
            is Code -> {
                // 行内代码只做「等宽 + 轻底纹」，不再染成玫红色：旧实现的 #F43F5E 前景色
                // 使 SQL/标识符在正文里像一块“红色告警”，反而更像没渲染。过长的行内代码
                // （如整段 WHERE 子句）不加底纹，避免折行后形成大色块。
                val literal = child.literal
                val withTint = literal.length <= 40 && !literal.contains('\n')
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        background = if (withTint) Color(0x1F88888F) else Color.Transparent
                    )
                )
                if (withTint) builder.append(" $literal ") else builder.append(literal)
                builder.pop()
            }
            is Link -> {
                val href = child.destination?.trim().orEmpty()
                val linkStyle = SpanStyle(
                    color = Color(0xFF3B82F6),
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline
                )
                val linkHandler = onOpenLink
                builder.pushStyle(linkStyle)
                if (linkHandler != null && href.isNotBlank()) {
                    // 对标 pi-web MarkdownBody 的 <a onClick>：链接不跳转，而是拦下来开预览。
                    // 用 AnnotatedString 上的 LinkAnnotation（Compose 1.7 的官方链接 API，
                    // 文字自带点击命中与无障碍「链接」语义），范围用 addLink 显式标出。
                    val linkStart = builder.length
                    appendInlineChildren(child, builder, isUser, onOpenLink)
                    if (builder.length > linkStart) {
                        builder.addLink(
                            LinkAnnotation.Clickable(
                                tag = href,
                                styles = TextLinkStyles(style = linkStyle),
                                linkInteractionListener = { linkHandler(href) }
                            ),
                            start = linkStart,
                            end = builder.length
                        )
                    }
                } else {
                    appendInlineChildren(child, builder, isUser, onOpenLink)
                }
                builder.pop()
            }
            is MdImage -> {
                // 图片已由 extractInlineMediaFromNode 渲染为缩略图卡片，此处不重复输出文本
            }
            is SoftLineBreak -> builder.append(" ")
            is HardLineBreak -> builder.append("\n")
            else -> appendInlineChildren(child, builder, isUser, onOpenLink)
        }
        isFirstChild = false
        child = child.next
    }
}

private fun appendMdTextWithFallbacks(
    literal: String,
    builder: AnnotatedString.Builder,
    isUser: Boolean
) {
    if (!literal.contains("**")) {
        LatexMathParser.appendTextWithMath(literal, builder, isUser)
        return
    }

    var lastIndex = 0
    GLOBAL_FALLBACK_BOLD_REGEX.findAll(literal).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (start > lastIndex) {
            val plain = literal.substring(lastIndex, start)
            LatexMathParser.appendTextWithMath(plain, builder, isUser)
        }
        val boldContent = match.groupValues[1]
        builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        LatexMathParser.appendTextWithMath(boldContent, builder, isUser)
        builder.pop()
        lastIndex = end
    }
    if (lastIndex < literal.length) {
        val plain = literal.substring(lastIndex)
        LatexMathParser.appendTextWithMath(plain, builder, isUser)
    }
}

// ==========================================
// 独立块级数学公式卡片 ($$...$$ / ```math / ```latex)
// ==========================================
@Composable
private fun MathFormulaBlockCard(formula: String) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    val rendered = remember(formula) {
        buildAnnotatedString {
            LatexMathParser.appendTextWithMath(formula, this, isUser = false)
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, theme.primary.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Surface(
                        color = theme.primary.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "∑ 数学公式",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = theme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("formula", formula))
                    },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制公式",
                        tint = theme.textMuted,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rendered,
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Medium,
                        color = theme.textPrimary,
                        lineHeight = 22.sp
                    )
                )
            }
        }
    }
}

// ==========================================
// 原生可视化图表渲染引擎：支持流程图 (Flowchart)、脑图 (Mindmap)、时序图 (Sequence)、统计图表 (Pie/Bar)
// ==========================================
@Composable
fun MermaidDiagramCard(code: String, language: String) {
    val theme = LocalAppTheme.current
    val context = LocalContext.current
    var showRawSource by remember { mutableStateOf(false) }
    var showFullscreenDiagram by remember { mutableStateOf(false) }

    val lines = remember(code) {
        code.lines().map { it.trimEnd() }.filter { it.isNotBlank() && !it.trim().startsWith("%%") }
    }
    val headerLine = lines.firstOrNull()?.trim()?.lowercase(Locale.ROOT) ?: ""

    val diagramTypeLabel = when {
        headerLine.startsWith("mindmap") || language == "mindmap" -> "思维脑图 (Mindmap)"
        headerLine.startsWith("sequencediagram") || language == "sequence" -> "交互时序图 (Sequence)"
        headerLine.startsWith("pie") || language == "chart" || headerLine.startsWith("xychart") -> "数据统计图表 (Chart)"
        else -> "架构流程图 (Flowchart)"
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = theme.surfaceContainerLow,
        border = BorderStroke(1.dp, theme.outline),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 顶部工具栏：图表类型标识 + 视图/源码切换 + 全屏查看 + 复制
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(theme.surfaceContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(
                        imageVector = Icons.Default.AccountTree,
                        contentDescription = null,
                        tint = theme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = diagramTypeLabel,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = theme.textPrimary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = theme.primary.copy(alpha = 0.14f),
                        modifier = Modifier.clickable { showRawSource = !showRawSource }
                    ) {
                        Text(
                            text = if (showRawSource) "图形视图" else "源码",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = theme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = theme.surfaceContainerHigh,
                        modifier = Modifier.clickable { showFullscreenDiagram = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = "全屏查看脑图/图表",
                                tint = theme.textPrimary,
                                modifier = Modifier.size(11.dp)
                            )
                            Text(
                                text = "全屏",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = theme.textPrimary
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("diagram", code))
                        },
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = theme.textMuted,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }

            if (showRawSource) {
                Text(
                    text = code,
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = theme.textPrimary,
                        lineHeight = 16.sp
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showFullscreenDiagram = true }
                        .padding(12.dp)
                ) {
                    when {
                        headerLine.startsWith("mindmap") || language == "mindmap" -> {
                            RenderVisualMindmap(lines)
                        }
                        headerLine.startsWith("sequencediagram") || language == "sequence" -> {
                            RenderVisualSequenceDiagram(lines)
                        }
                        headerLine.startsWith("pie") || language == "chart" || headerLine.startsWith("xychart") -> {
                            RenderVisualChart(lines)
                        }
                        else -> {
                            RenderVisualFlowchart(lines)
                        }
                    }
                }
            }
        }
    }

    if (showFullscreenDiagram) {
        FullscreenMediaLightboxDialog(
            attachment = MediaAttachment(
                id = "diagram_${code.hashCode()}",
                kind = MediaKind.DIAGRAM,
                mimeType = "text/vnd.mermaid",
                title = diagramTypeLabel,
                rawContent = code
            ),
            onDismiss = { showFullscreenDiagram = false },
            customDiagramComposable = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = theme.surfaceContainerLow,
                    border = BorderStroke(1.dp, theme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        when {
                            headerLine.startsWith("mindmap") || language == "mindmap" -> RenderVisualMindmap(lines)
                            headerLine.startsWith("sequencediagram") || language == "sequence" -> RenderVisualSequenceDiagram(lines)
                            headerLine.startsWith("pie") || language == "chart" || headerLine.startsWith("xychart") -> RenderVisualChart(lines)
                            else -> RenderVisualFlowchart(lines)
                        }
                    }
                }
            }
        )
    }
}

/**
 * 1. 思维脑图 (Mindmap) 原生树形可视化组件
 */
@Composable
private fun RenderVisualMindmap(lines: List<String>) {
    val theme = LocalAppTheme.current
    val branchColors = listOf(
        Color(0xFF3B82F6),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFFEC4899),
        Color(0xFF06B6D4)
    )

    val contentLines = lines.dropWhile { it.trim().equals("mindmap", ignoreCase = true) }
    if (contentLines.isEmpty()) return

    val baseIndent = contentLines.minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length } ?: 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        var branchIdx = -1
        contentLines.forEachIndexed { idx, rawLine ->
            val indentSpaces = rawLine.takeWhile { it == ' ' || it == '\t' }.length - baseIndent
            val level = (indentSpaces / 2).coerceAtLeast(0)
            val cleanText = cleanMermaidNodeLabel(rawLine.trim())
            if (level <= 1 && idx > 0) branchIdx++
            val accent = branchColors[(branchIdx.coerceAtLeast(0)) % branchColors.size]

            if (idx == 0 || level == 0) {
                // 中心主题根节点
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = theme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Hub, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Text(
                            text = cleanText,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = ((level - 1) * 16).coerceAtMost(64).dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 树枝引导线
                    Box(
                        modifier = Modifier
                            .width(12.dp)
                            .height(2.dp)
                            .background(accent.copy(alpha = 0.7f))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(7.dp),
                        color = if (level == 1) accent.copy(alpha = 0.14f) else theme.surfaceContainer,
                        border = BorderStroke(1.dp, if (level == 1) accent.copy(alpha = 0.45f) else theme.outline)
                    ) {
                        Text(
                            text = cleanText,
                            fontSize = if (level == 1) 12.5.sp else 11.5.sp,
                            fontWeight = if (level == 1) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (level == 1) accent else theme.textPrimary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 2. 流程图 / 状态图 (Flowchart / Graph) 原生节点-有向边可视化组件
 */
private data class FlowEdgeItem(
    val fromLabel: String,
    val fromIsDecision: Boolean,
    val edgeLabel: String?,
    val toLabel: String,
    val toIsDecision: Boolean
)

private val MERMAID_EDGE_REGEX = Regex("""^(.+?)\s*(?:-->|==>|-\.->|--\s*([^>-]+?)\s*-->|-->\|([^|]+)\|)\s*(.+)$""")

@Composable
private fun RenderVisualFlowchart(lines: List<String>) {
    val theme = LocalAppTheme.current
    val nodeLabels = remember(lines) { mutableMapOf<String, Pair<String, Boolean>>() }
    val edges = remember(lines) {
        val list = mutableListOf<FlowEdgeItem>()
        val bodyLines = lines.filterNot {
            val t = it.trim().lowercase(Locale.ROOT)
            t.startsWith("graph ") || t.startsWith("flowchart ") || t.startsWith("statediagram") || t == "end"
        }
        for (line in bodyLines) {
            val trimmed = line.trim().removeSuffix(";")
            if (trimmed.startsWith("subgraph ")) continue
            val m = MERMAID_EDGE_REGEX.find(trimmed)
            if (m != null) {
                val rawFrom = m.groupValues[1].trim()
                val edgeText = m.groupValues[2].ifBlank { m.groupValues[3] }.trim().ifBlank { null }
                val rawTo = m.groupValues[4].trim()
                val (fromId, fromText, fromDec) = parseMermaidNodeToken(rawFrom, nodeLabels)
                val (toId, toText, toDec) = parseMermaidNodeToken(rawTo, nodeLabels)
                nodeLabels[fromId] = fromText to fromDec
                nodeLabels[toId] = toText to toDec
                list.add(FlowEdgeItem(fromText, fromDec, edgeText, toText, toDec))
            }
        }
        list
    }

    if (edges.isEmpty()) {
        // 回退展示结构化步骤卡片
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            lines.drop(1).forEach { step ->
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = theme.surfaceContainer,
                    border = BorderStroke(0.8.dp, theme.outline),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = cleanMermaidNodeLabel(step.trim()),
                        fontSize = 12.sp,
                        color = theme.textPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        edges.forEach { edge ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // 起点节点
                FlowNodeChip(
                    label = edge.fromLabel,
                    isDecision = edge.fromIsDecision,
                    modifier = Modifier.weight(1f)
                )

                // 有向边与条件标签
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.widthIn(min = 42.dp, max = 96.dp)
                ) {
                    if (!edge.edgeLabel.isNullOrBlank()) {
                        Surface(
                            color = theme.primary.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = edge.edgeLabel,
                                fontSize = 9.5.sp,
                                color = theme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                    Text(
                        text = "───▶",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = theme.primary
                    )
                }

                // 终点节点
                FlowNodeChip(
                    label = edge.toLabel,
                    isDecision = edge.toIsDecision,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun FlowNodeChip(
    label: String,
    isDecision: Boolean,
    modifier: Modifier = Modifier
) {
    val theme = LocalAppTheme.current
    val bgColor = if (isDecision) Color(0xFFF59E0B).copy(alpha = 0.14f) else theme.surfaceContainer
    val borderColor = if (isDecision) Color(0xFFF59E0B).copy(alpha = 0.55f) else theme.primary.copy(alpha = 0.4f)
    val textColor = if (isDecision) (if (theme.isDark) Color(0xFFFBBF24) else Color(0xFFB45309)) else theme.textPrimary

    Surface(
        shape = RoundedCornerShape(if (isDecision) 12.dp else 7.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (isDecision) {
                Text("◆ ", fontSize = 10.sp, color = textColor)
            }
            Text(
                text = label,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 3. 时序图 (SequenceDiagram) 原生可视化组件
 */
private val SEQ_ARROW_REGEX = Regex("""^([^-:]+?)\s*(->>|-->>|->|-->)\s*([^:]+?)\s*:\s*(.+)$""")

@Composable
private fun RenderVisualSequenceDiagram(lines: List<String>) {
    val theme = LocalAppTheme.current
    val steps = remember(lines) {
        lines.drop(1).mapNotNull { line ->
            val m = SEQ_ARROW_REGEX.find(line.trim())
            if (m != null) {
                Triple("${m.groupValues[1].trim()} ➔ ${m.groupValues[3].trim()}", m.groupValues[2].contains("--"), m.groupValues[4].trim())
            } else null
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        steps.forEachIndexed { idx, (actors, isReply, msg) ->
            Surface(
                shape = RoundedCornerShape(7.dp),
                color = if (isReply) theme.surfaceContainer else theme.primary.copy(alpha = 0.08f),
                border = BorderStroke(0.8.dp, if (isReply) theme.outline else theme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = theme.primary.copy(alpha = 0.18f),
                        modifier = Modifier.size(20.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("${idx + 1}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = theme.primary)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = actors,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isReply) theme.textSecondary else theme.primary
                        )
                        Text(
                            text = msg,
                            fontSize = 12.sp,
                            color = theme.textPrimary
                        )
                    }
                }
            }
        }
    }
}

/**
 * 4. 统计图表 (Pie / Bar Chart) 原生可视化组件
 */
private val PIE_ENTRY_REGEX = Regex("""^\s*"?([^":]+)"?\s*:\s*([0-9.]+)""")

@Composable
private fun RenderVisualChart(lines: List<String>) {
    val theme = LocalAppTheme.current
    val palette = listOf(
        Color(0xFF3B82F6),
        Color(0xFF10B981),
        Color(0xFFF59E0B),
        Color(0xFFEF4444),
        Color(0xFF8B5CF6),
        Color(0xFF06B6D4)
    )

    val title = lines.firstOrNull { it.trim().startsWith("title ", ignoreCase = true) }
        ?.trim()?.removePrefix("title ")?.removePrefix("Title ")?.trim()

    val entries = remember(lines) {
        lines.mapNotNull { line ->
            val m = PIE_ENTRY_REGEX.find(line)
            if (m != null) {
                val label = m.groupValues[1].trim()
                val value = m.groupValues[2].toDoubleOrNull() ?: 0.0
                if (value > 0) label to value else null
            } else null
        }
    }

    val total = entries.sumOf { it.second }.coerceAtLeast(0.0001)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (!title.isNullOrBlank()) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = theme.textPrimary)
        }

        if (entries.isNotEmpty()) {
            // 顶部堆叠占比条
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
            ) {
                entries.forEachIndexed { idx, (_, value) ->
                    val weight = (value / total).toFloat().coerceAtLeast(0.02f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .fillMaxHeight()
                            .background(palette[idx % palette.size])
                    )
                }
            }

            // 各项水平条形指标
            entries.forEachIndexed { idx, (label, value) ->
                val ratio = (value / total).toFloat().coerceIn(0f, 1f)
                val pctStr = String.format(Locale.US, "%.1f%%", ratio * 100f)
                val color = palette[idx % palette.size]
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.size(8.dp).background(color, CircleShape))
                            Text(label, fontSize = 12.sp, color = theme.textPrimary, fontWeight = FontWeight.Medium)
                        }
                        Text(
                            text = "$value ($pctStr)",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = theme.textSecondary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ratio },
                        color = color,
                        trackColor = theme.surfaceContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }
            }
        }
    }
}

private fun parseMermaidNodeToken(
    raw: String,
    cache: Map<String, Pair<String, Boolean>>
): Triple<String, String, Boolean> {
    val trimmed = raw.trim()
    val braceIdx = trimmed.indexOfFirst { it == '[' || it == '(' || it == '{' }
    if (braceIdx > 0) {
        val id = trimmed.substring(0, braceIdx).trim()
        val rest = trimmed.substring(braceIdx).trim()
        val isDecision = rest.startsWith("{")
        val label = cleanMermaidNodeLabel(rest)
        return Triple(id, label, isDecision)
    }
    val cached = cache[trimmed]
    if (cached != null) {
        return Triple(trimmed, cached.first, cached.second)
    }
    return Triple(trimmed, cleanMermaidNodeLabel(trimmed), false)
}

/** Mermaid 节点标签开头的 `A(` / `B[[` 形式的前缀。原在函数内每次重建。 */
private val MERMAID_LABEL_PREFIX_REGEX = Regex("""^[A-Za-z0-9_]+\s*(\(\(|\[\[|\[\(|\(\[|\[|\(|\{)""")

/** 与之对应的尾部闭合符号。 */
private val MERMAID_LABEL_SUFFIX_REGEX = Regex("""(\)\)|\]\]|\)\]|\]\)|\]|\)|\})$""")

private fun cleanMermaidNodeLabel(raw: String): String {
    return raw
        .replace(MERMAID_LABEL_PREFIX_REGEX, "")
        .replace(MERMAID_LABEL_SUFFIX_REGEX, "")
        .removeSurrounding("\"")
        .trim()
        .ifBlank { raw.trim() }
}

/**
 * Diff / Patch 语法着色卡片
 */
@Composable
private fun DiffCodeBlockCard(code: String, language: String) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181825)),
        border = BorderStroke(0.6.dp, Color(0xFF313244)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF11111B))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = language.uppercase(Locale.ROOT),
                    style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFA6ADC8))
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("diff", code))
                    },
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = Color(0xFFA6ADC8),
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                code.lines().forEach { line ->
                    val (bgColor, txtColor) = when {
                        line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF22C55E).copy(alpha = 0.16f) to Color(0xFF86EFAC)
                        line.startsWith("-") && !line.startsWith("---") -> Color(0xFFEF4444).copy(alpha = 0.16f) to Color(0xFFFCA5A5)
                        line.startsWith("@@") -> Color(0xFF3B82F6).copy(alpha = 0.15f) to Color(0xFF93C5FD)
                        else -> Color.Transparent to Color(0xFFCDD6F4)
                    }
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = txtColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(bgColor, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

/**
 * CommonMark GFM 结构化运维表格卡片
 */
@Composable
private fun AstTableCard(
    tableNode: TableBlock,
    isUser: Boolean,
    onOpenLink: ((String) -> Unit)? = null
) {
    val theme = LocalAppTheme.current

    val headerCells = mutableListOf<AnnotatedString>()
    val bodyRows = mutableListOf<List<AnnotatedString>>()

    var tablePart = tableNode.firstChild
    while (tablePart != null) {
        when (tablePart) {
            is TableHead -> {
                var rowNode = tablePart.firstChild
                while (rowNode != null) {
                    if (rowNode is TableRow) {
                        var cellNode = rowNode.firstChild
                        while (cellNode != null) {
                            if (cellNode is TableCell) {
                                headerCells.add(buildInlineAnnotatedString(cellNode, isUser = false, onOpenLink = onOpenLink))
                            }
                            cellNode = cellNode.next
                        }
                    }
                    rowNode = rowNode.next
                }
            }

            is TableBody -> {
                var rowNode = tablePart.firstChild
                while (rowNode != null) {
                    if (rowNode is TableRow) {
                        val rowCells = mutableListOf<AnnotatedString>()
                        var cellNode = rowNode.firstChild
                        while (cellNode != null) {
                            if (cellNode is TableCell) {
                                rowCells.add(buildInlineAnnotatedString(cellNode, isUser = false, onOpenLink = onOpenLink))
                            }
                            cellNode = cellNode.next
                        }
                        bodyRows.add(rowCells)
                    }
                    rowNode = rowNode.next
                }
            }
        }
        tablePart = tablePart.next
    }

    val totalCols = kotlin.math.max(headerCells.size, bodyRows.maxOfOrNull { it.size } ?: 0)
    if (totalCols == 0) return

    val colWidths = (0 until totalCols).map { colIdx ->
        val headerLen = headerCells.getOrNull(colIdx)?.text?.length ?: 0
        val maxBodyLen = bodyRows.maxOfOrNull { it.getOrNull(colIdx)?.text?.length ?: 0 } ?: 0
        val maxLen = kotlin.math.max(headerLen, maxBodyLen)
        ((maxLen * 9.5f) + 24f).coerceIn(85f, 220f).dp
    }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = theme.surfaceContainerLow),
        border = BorderStroke(0.7.dp, theme.outline.copy(alpha = 0.25f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            if (headerCells.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .background(theme.surfaceContainerHigh.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until totalCols) {
                        val cellText = headerCells.getOrNull(c) ?: AnnotatedString("")
                        val colW = colWidths[c]
                        Box(
                            modifier = Modifier
                                .width(colW)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = cellText,
                                style = TextStyle(
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = theme.textPrimary
                                )
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
            }

            bodyRows.forEachIndexed { rIdx, rowCells ->
                val rowBg = if (rIdx % 2 == 1) theme.surfaceContainer.copy(alpha = 0.35f) else Color.Transparent
                Row(
                    modifier = Modifier
                        .background(rowBg, RoundedCornerShape(3.dp))
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    for (c in 0 until totalCols) {
                        val cellText = rowCells.getOrNull(c) ?: AnnotatedString("")
                        val colW = colWidths[c]
                        Box(
                            modifier = Modifier
                                .width(colW)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = cellText,
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    color = theme.textPrimary,
                                    lineHeight = 15.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockCard(code: String, language: String) {
    val context = LocalContext.current
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF181825)),
        border = BorderStroke(0.6.dp, Color(0xFF313244)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            DisableSelection {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF11111B))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = language.ifBlank { "shell" },
                        style = TextStyle(fontSize = 10.5.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFA6ADC8))
                    )
                    IconButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("code", code))
                        },
                        modifier = Modifier.size(22.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "复制",
                            tint = Color(0xFFA6ADC8),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
            Text(
                text = code,
                style = TextStyle(
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFCDD6F4),
                    lineHeight = 16.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp)
            )
        }
    }
}
