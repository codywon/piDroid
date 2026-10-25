package com.pi.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * 统一委托至现代标准 CommonMark + GFM 渲染引擎的 MarkdownText 组件
 * 彻底消除标记符裸露，保证全应用 Markdown 渲染体验一致、专业、美观
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    isUser: Boolean = false
) {
    MarkdownRenderer(
        content = markdown,
        modifier = modifier,
        isUser = isUser
    )
}
