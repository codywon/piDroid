package com.pi.app.ui.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme

/** @@ -a,b +c,d @@ 区块头正则（顶层常量，避免每行/每个 Hunk 重复编译）。 */
private val HUNK_HEADER_REGEX = Regex("""^(@@\s+-\d+(?:,\d+)?\s+\+\d+(?:,\d+)?\s+@@)(.*)$""")

/** 行号列最宽保留的位数上限，防御异常超长文件把正文挤成 0 宽。 */
private const val MAX_GUTTER_DIGITS = 6

/**
 * 结构化 Diff 视图组件（深度对标 GitHub Mobile / Desktop 差异审阅体验）。
 *
 * 核心特性：
 * 1. 彻底根治 Compose 0 宽度测量 Bug：在单行横向滚动模式下解绑 weight(1f)，让文字按自然宽度展开；
 * 2. 移动端轻量 Gutter 设计：行号列按最大行号位数自适应（窄文件约 33dp），腾出宝贵横向宽度；
 * 3. Hunk Header 通栏 Banner 化：@@ -a,b +c,d @@ 采用全宽独立分块展示，不再畸形挤在代码行号列中；
 * 4. 行内词级高亮（Intra-line Word Diff）：对 1:1 删改行进行公共前后缀剔除，精确高亮变化词汇；
 * 5. 满宽底色保证：保证短行在横向滚动时的绿/红底色依然撑满整个视口。
 */
@Composable
fun StructuredDiffView(
    patchText: String,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true
) {
    val theme = LocalAppTheme.current
    val parsedFiles = remember(patchText) {
        UnifiedDiffParser.parse(patchText)
    }

    if (parsedFiles.isEmpty() || parsedFiles.all { it.lines.isEmpty() }) {
        Box(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "没有可显示的修改差异（或仅包含空变更）",
                fontSize = 12.sp,
                color = theme.textMuted
            )
        }
        return
    }

    // 扁平化展示所有文件与段落
    val allLines = remember(parsedFiles) {
        val list = mutableListOf<UnifiedPatchLine>()
        parsedFiles.forEach { file ->
            val displayPath = file.newPath?.takeIf { it != "/dev/null" } ?: file.oldPath ?: "未知文件"
            if (parsedFiles.size > 1) {
                list.add(
                    UnifiedPatchLine(
                        type = PatchLineType.HUNK,
                        text = "--- 文件: $displayPath ---"
                    )
                )
            }
            list.addAll(file.lines)
        }
        list
    }

    // 行号列宽度按实际最大行号位数自适应，保留足够内边距避免大字号或辅助功能缩放时裁切
    val gutterNumWidth = remember(allLines) {
        var maxLineNo = 0
        allLines.forEach { line ->
            line.oldLineNo?.let { if (it > maxLineNo) maxLineNo = it }
            line.newLineNo?.let { if (it > maxLineNo) maxLineNo = it }
        }
        val digits = maxLineNo.toString().length.coerceIn(1, MAX_GUTTER_DIGITS)
        (digits * 7 + 8).dp
    }

    val scrollState = rememberScrollState()

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // 单行滚动时父约束可能是 Infinity（或 Int.MAX_VALUE 折算的巨大值），
        // 必须收敛成有限的真实宽度，否则 defaultMinSize(Infinity) 会直接崩溃
        val minRowWidth = if (maxWidth.value.isFinite() && maxWidth < 20000.dp) maxWidth else 0.dp

        Surface(
            shape = RoundedCornerShape(8.dp),
            color = theme.surfaceContainer,
            border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (!softWrap) Modifier.horizontalScroll(scrollState) else Modifier)
                ) {
                    items(allLines) { line ->
                        DiffLineItem(
                            line = line,
                            softWrap = softWrap,
                            minRowWidth = minRowWidth,
                            gutterNumWidth = gutterNumWidth
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffLineItem(
    line: UnifiedPatchLine,
    softWrap: Boolean,
    minRowWidth: Dp,
    gutterNumWidth: Dp
) {
    if (line.type == PatchLineType.HUNK) {
        DiffSectionRow(text = line.text, minRowWidth = minRowWidth)
        return
    }

    val theme = LocalAppTheme.current
    val isDark = theme.isDark

    val (bgColor, edgeColor, prefixColor, prefixChar) = when (line.type) {
        PatchLineType.ADDED -> Quadruple(
            if (isDark) Color(0xFF132B1B) else Color(0xFFE6F7EA),
            Color(0xFF2EA043),
            Color(0xFF2EA043),
            "+"
        )
        PatchLineType.REMOVED -> Quadruple(
            if (isDark) Color(0xFF331618) else Color(0xFFFFECEE),
            Color(0xFFCF222E),
            Color(0xFFCF222E),
            "-"
        )
        // CONTEXT 与（理论上不可达的）HUNK 共用一个中性样式，避免用 error() 把渲染变成崩溃
        PatchLineType.CONTEXT, PatchLineType.HUNK -> Quadruple(
            Color.Transparent,
            Color.Transparent,
            theme.textMuted.copy(alpha = 0.5f),
            " "
        )
    }

    // 正文（含可选的行内词级高亮）：每个 item 只构建一次，滚动复用不重复分配
    val body = remember(line.text, line.highlightRange, isDark, line.type) {
        val source = if (line.text.isEmpty()) " " else line.text
        val range = line.highlightRange?.takeIf {
            it.first >= 0 && it.last < source.length && it.first <= it.last
        }
        val highlightBg = when (line.type) {
            PatchLineType.ADDED -> if (isDark) Color(0xFF1F5C2C) else Color(0xFFACECBC)
            PatchLineType.REMOVED -> if (isDark) Color(0xFF6B181E) else Color(0xFFFFBCC3)
            else -> Color.Transparent
        }
        if (range == null) {
            AnnotatedString(source)
        } else {
            buildAnnotatedString {
                append(source)
                addStyle(
                    style = SpanStyle(background = highlightBg, fontWeight = FontWeight.Bold),
                    start = range.first,
                    end = range.last + 1
                )
            }
        }
    }

    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = minRowWidth)
            .fillMaxWidth()
            .background(bgColor)
            .drawBehind {
                if (edgeColor != Color.Transparent) {
                    drawRect(color = edgeColor, size = Size(3.dp.toPx(), size.height))
                }
            },
        verticalAlignment = Alignment.Top
    ) {
        // 边缘指示条与行号之间的间隔
        Spacer(modifier = Modifier.width(4.dp))

        // 紧凑行号区（旧行号 + 新行号，均右对齐，宽度按位数自适应）
        Text(
            text = line.oldLineNo?.toString().orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            textAlign = TextAlign.End,
            color = theme.textMuted.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .width(gutterNumWidth)
                .padding(vertical = 2.dp)
        )

        Spacer(modifier = Modifier.width(3.dp))

        Text(
            text = line.newLineNo?.toString().orEmpty(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            textAlign = TextAlign.End,
            color = theme.textMuted.copy(alpha = 0.65f),
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .width(gutterNumWidth)
                .padding(vertical = 2.dp)
        )

        // +/- 符号（14dp 居中）
        Text(
            text = prefixChar,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = prefixColor,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .width(14.dp)
                .padding(vertical = 2.dp)
        )

        // 正文内容：
        // 核心修复：softWrap 为 false 时绝不挂载 weight(1f)，避免在无约束横向视口中被测量为 0 宽度！
        Text(
            text = body,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = theme.textPrimary,
            softWrap = softWrap,
            modifier = if (softWrap) {
                Modifier
                    .weight(1f)
                    .padding(end = 8.dp, top = 2.dp, bottom = 2.dp)
            } else {
                Modifier.padding(end = 16.dp, top = 2.dp, bottom = 2.dp)
            }
        )
    }
}

/**
 * Diff 段落分隔行：
 * - `@@ -a,b +c,d @@` 渲染成 GitHub Mobile 风格的全宽通栏 Banner（药丸标签 + 上下文）；
 * - `--- 文件: 路径 ---` 渲染成文件分组标题；
 * - 其余控制行（如 `\ No newline at end of file`）渲染成不抢眼的行内注记，不再占用正文行号列。
 */
@Composable
private fun DiffSectionRow(
    text: String,
    minRowWidth: Dp
) {
    val theme = LocalAppTheme.current
    val isDark = theme.isDark
    val trimmed = text.trim()

    val hunkMatch = remember(trimmed) { HUNK_HEADER_REGEX.find(trimmed) }

    if (hunkMatch == null && !trimmed.startsWith("--- 文件:")) {
        // 非 @@ 的控制行：低对比度注记，保持排版整洁
        Row(
            modifier = Modifier
                .defaultMinSize(minWidth = minRowWidth)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (trimmed.isEmpty()) "⋯" else trimmed,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontStyle = FontStyle.Italic,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }

    val (badgeText, contextText) = when {
        hunkMatch != null -> hunkMatch.groupValues[1].trim() to hunkMatch.groupValues[2].trim()
        else -> "文件" to trimmed.removePrefix("--- 文件:").removeSuffix("---").trim()
    }

    val bannerBg = if (isDark) Color(0xFF161F2E) else Color(0xFFF1F6FD)
    val bannerBorder = if (isDark) Color(0xFF263854) else Color(0xFFD4E3FA)
    val badgeBg = if (isDark) Color(0xFF233550) else Color(0xFFDCEAFE)
    val accentColor = if (isDark) Color(0xFF79B8FF) else Color(0xFF0969DA)

    Row(
        modifier = Modifier
            .defaultMinSize(minWidth = minRowWidth)
            .fillMaxWidth()
            .background(bannerBg)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = badgeBg,
            border = BorderStroke(0.5.dp, bannerBorder)
        ) {
            Text(
                text = badgeText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        if (contextText.isNotBlank()) {
            Text(
                text = contextText,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.5.sp,
                color = theme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
