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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pi.app.ui.theme.LocalAppTheme

/**
 * 结构化 Diff 视图组件（对标 pi-web DiffView / GitHub Diff 界面）。
 *
 * 特性：
 * 1. 结构化解析 @@ 块与逐行新旧行号；
 * 2. 浅绿/浅红柔和底色，左侧边缘带高对比度的绿色/红色指示条；
 * 3. 惰性按行加载（LazyColumn），支持极长 Diff；
 * 4. 支持由外部切换「横向自由滚动」或「软换行」，兼顾手机竖屏排版。
 */
@Composable
fun StructuredDiffView(
    patchText: String,
    modifier: Modifier = Modifier,
    softWrap: Boolean = false
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
        parsedFiles.forEachIndexed { index, file ->
            if (parsedFiles.size > 1 && file.newPath != null) {
                list.add(
                    UnifiedPatchLine(
                        type = PatchLineType.HUNK,
                        text = "--- 文件: ${file.newPath} ---"
                    )
                )
            }
            list.addAll(file.lines)
        }
        list
    }

    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = theme.surfaceContainer,
        border = BorderStroke(0.6.dp, theme.outline.copy(alpha = 0.5f)),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!softWrap) Modifier.horizontalScroll(scrollState) else Modifier)
            ) {
                items(allLines) { line ->
                    DiffLineItem(line = line, softWrap = softWrap)
                }
            }
        }
    }
}

@Composable
private fun DiffLineItem(
    line: UnifiedPatchLine,
    softWrap: Boolean
) {
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
        PatchLineType.HUNK -> Quadruple(
            if (isDark) Color(0xFF1B2234) else Color(0xFFEBF1FD),
            Color(0xFF388BFD),
            Color(0xFF388BFD),
            "@"
        )
        PatchLineType.GAP, PatchLineType.CONTEXT -> Quadruple(
            Color.Transparent,
            Color.Transparent,
            theme.textMuted,
            " "
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor),
        verticalAlignment = Alignment.Top
    ) {
        // 边缘指示线（3dp）
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(edgeColor)
        )

        // 行号区域（旧行号 / 新行号）
        if (line.type == PatchLineType.HUNK) {
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .padding(vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "⋯",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = prefixColor
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .width(72.dp)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = line.oldLineNo?.toString() ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = theme.textMuted.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = line.newLineNo?.toString() ?: "",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.5.sp,
                    color = theme.textMuted.copy(alpha = 0.7f),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // +/- 符号
        Text(
            text = prefixChar,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = prefixColor,
            modifier = Modifier
                .width(14.dp)
                .padding(vertical = 2.dp)
        )

        // 正文内容
        Text(
            text = if (line.text.isEmpty()) " " else line.text,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (line.type == PatchLineType.HUNK) prefixColor else theme.textPrimary,
            softWrap = softWrap,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(end = 12.dp, top = 2.dp, bottom = 2.dp)
        )
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
