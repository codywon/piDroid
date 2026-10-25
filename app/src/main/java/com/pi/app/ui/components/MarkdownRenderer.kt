package com.pi.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * 大模型友好型 CJK Markdown 规整引擎 (参考 Termius 架构)
 */
object CjkMarkdownNormalizer {
    private val CODE_BLOCK_PATTERN = Regex("""(```[\s\S]*?```|`[^`\n]+`)""")
    private val PSEUDO_LIST_REGEX = Regex("""(?m)^([\t ]*)[•◦▪◆]\s+""")
    private val INNER_BOLD_WHITESPACE_ASTERISK = Regex("""\*\*[\t ]*([^*]+?)[\t ]*\*\*""")
    private val INNER_BOLD_WHITESPACE_UNDERSCORE = Regex("""__[\t ]*([^_]+?)[\t ]*__""")

    private const val OPEN_PUNC = """[“‘（【《〈〔\[\(\{]"""
    private const val CLOSE_PUNC = """[”’）】》〉〕\]\)\}。，！？；：]"""

    private val OPEN_BOLD_REGEX = Regex("""([\u4e00-\u9fa5\w])(\*\*)($OPEN_PUNC)""")
    private val CLOSE_BOLD_REGEX = Regex("""($CLOSE_PUNC)(\*\*)([\u4e00-\u9fa5\w])""")

    // 智能表格换行修复：
    // 大模型输出表格时常将多行粘连为单行，例如 "| 说明 | |:---|:---| | TCP | ... | | TCP | ..."
    // 将两行相接处的 "| |" 或 "||" 替换为 "|\n|"
    private val TABLE_STICKY_ROW_REGEX = Regex("""\|\s*\|(?=\s*[:\w\-\./*\$#@~`\u4e00-\u9fa5])""")
    private val TABLE_SEPARATOR_REGEX = Regex("""(?<!\n)(\|[^\n|]+?\|\s*)(\|(?:[\s:]*-+[\s:]*\|)+)""")

    fun normalize(rawMarkdown: String): String {
        if (rawMarkdown.isEmpty()) return rawMarkdown

        val matches = CODE_BLOCK_PATTERN.findAll(rawMarkdown).toList()
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

    private fun fixSegment(text: String): String {
        var s = text

        // 1. 自动修复大模型单行粘连表格（在表头、分隔线与数据行间安全插入换行）
        if (s.contains("|") && s.contains("-")) {
            s = TABLE_SEPARATOR_REGEX.replace(s) { "${it.groupValues[1]}\n${it.groupValues[2]}" }
            s = TABLE_STICKY_ROW_REGEX.replace(s, "|\n|")
        }

        // 2. 规范化伪列表符号
        if (s.contains("•") || s.contains("◦") || s.contains("▪")) {
            s = PSEUDO_LIST_REGEX.replace(s) { "${it.groupValues[1]}- " }
        }

        // 3. 粗体与标点归一化
        if (s.contains("*") || s.contains("_")) {
            s = INNER_BOLD_WHITESPACE_ASTERISK.replace(s) { "**${it.groupValues[1]}**" }
            s = INNER_BOLD_WHITESPACE_UNDERSCORE.replace(s) { "__${it.groupValues[1]}__" }
            s = OPEN_BOLD_REGEX.replace(s) { m -> "${m.groupValues[1]} ${m.groupValues[2]}${m.groupValues[3]}" }
            s = CLOSE_BOLD_REGEX.replace(s) { m -> "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}" }
        }

        return s
    }
}

/**
 * 现代标准 CommonMark AST 富文本与运维表格渲染器
 * 支持 GFM Tables 横向平滑滑动、代码高亮复制、CJK 标点粗体优化与 LaTeX
 */
@Composable
fun MarkdownRenderer(
    content: String,
    modifier: Modifier = Modifier,
    isUser: Boolean = false
) {
    if (content.isBlank()) return

    val document = remember(content) {
        val normalized = CjkMarkdownNormalizer.normalize(content)
        val extensions = listOf(
            TablesExtension.create(),
            StrikethroughExtension.create(),
            AutolinkExtension.create()
        )
        val parser = Parser.builder().extensions(extensions).build()
        parser.parse(normalized)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var childNode = document.firstChild
        while (childNode != null) {
            RenderAstBlockNode(node = childNode, isUser = isUser)
            childNode = childNode.next
        }
    }
}

@Composable
private fun RenderAstBlockNode(node: Node, isUser: Boolean) {
    val theme = LocalAppTheme.current

    when (node) {
        is Heading -> {
            val fontSize = when (node.level) {
                1 -> 16.sp
                2 -> 15.sp
                3 -> 14.sp
                else -> 13.sp
            }
            val annotated = buildInlineAnnotatedString(node, isUser)
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
                        color = if (isUser) Color.White else theme.textPrimary,
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
            Text(
                text = buildInlineAnnotatedString(node, isUser),
                style = TextStyle(
                    fontSize = 13.5.sp,
                    color = if (isUser) Color.White else theme.textPrimary,
                    lineHeight = 20.sp,
                    letterSpacing = 0.1.sp
                )
            )
        }

        is FencedCodeBlock -> {
            CodeBlockCard(code = node.literal.trimEnd(), language = node.info ?: "")
        }

        is IndentedCodeBlock -> {
            CodeBlockCard(code = node.literal.trimEnd(), language = "")
        }

        is TableBlock -> {
            AstTableCard(tableNode = node, isUser = isUser)
        }

        is ThematicBreak -> {
            HorizontalDivider(
                color = theme.outline.copy(alpha = 0.2f),
                thickness = 0.8.dp,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }

        is BlockQuote -> {
            Surface(
                shape = RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp),
                color = if (isUser) Color.White.copy(alpha = 0.1f) else theme.surfaceContainer.copy(alpha = 0.5f),
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
                            RenderAstBlockNode(node = quoteChild, isUser = isUser)
                            quoteChild = quoteChild.next
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
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 1.5.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(top = 7.5.dp, start = 4.dp, end = 9.dp)
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(if (isUser) Color.White else theme.primary)
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                var itemChild = item.firstChild
                                while (itemChild != null) {
                                    RenderAstBlockNode(node = itemChild, isUser = isUser)
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
                                    color = if (isUser) Color.White else theme.textSecondary
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
                                    RenderAstBlockNode(node = itemChild, isUser = isUser)
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

private fun buildInlineAnnotatedString(parentNode: Node, isUser: Boolean): AnnotatedString {
    val initial = buildAnnotatedString {
        appendInlineChildren(parentNode, this, isUser)
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

private fun appendInlineChildren(parent: Node, builder: AnnotatedString.Builder, isUser: Boolean) {
    var child = parent.firstChild
    while (child != null) {
        when (child) {
            is MdText -> {
                appendMdTextWithFallbacks(child.literal, builder, isUser)
            }
            is StrongEmphasis -> {
                builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                appendInlineChildren(child, builder, isUser)
                builder.pop()
            }
            is Emphasis -> {
                builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                appendInlineChildren(child, builder, isUser)
                builder.pop()
            }
            is Strikethrough -> {
                builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                appendInlineChildren(child, builder, isUser)
                builder.pop()
            }
            is Code -> {
                builder.pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        background = if (isUser) Color.White.copy(alpha = 0.2f) else Color(0x22888888),
                        color = if (isUser) Color.White else Color(0xFFF43F5E)
                    )
                )
                builder.append(" ${child.literal} ")
                builder.pop()
            }
            is Link -> {
                builder.pushStyle(
                    SpanStyle(
                        color = if (isUser) Color(0xFF93C5FD) else Color(0xFF3B82F6),
                        fontWeight = FontWeight.Medium,
                        textDecoration = TextDecoration.Underline
                    )
                )
                appendInlineChildren(child, builder, isUser)
                builder.pop()
            }
            is SoftLineBreak -> builder.append(" ")
            is HardLineBreak -> builder.append("\n")
            else -> appendInlineChildren(child, builder, isUser)
        }
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

/**
 * CommonMark GFM 结构化运维表格卡片：
 * 预计算每一列内容宽度，确保表头与各行对齐，支持手机横向平滑滑动
 */
@Composable
private fun AstTableCard(tableNode: TableBlock, isUser: Boolean) {
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
                                headerCells.add(buildInlineAnnotatedString(cellNode, isUser = false))
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
                                rowCells.add(buildInlineAnnotatedString(cellNode, isUser = false))
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
            // 表头行
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

            // 数据行
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
