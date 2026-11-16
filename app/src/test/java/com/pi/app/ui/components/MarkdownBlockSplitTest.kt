package com.pi.app.ui.components

import org.junit.Assert
import org.junit.Test

/**
 * 流式 Markdown 的块级切分（`splitTopLevelMarkdownBlocks`）。
 *
 * 这套切分是长消息渲染 O(n) 的前提：切出来的块在重组时字节不变，才能命中缓存、
 * 只重解析末尾那一块。日志里每次失败的表现都是同一类——**该留在一起的内容被切开了**，
 * 于是代码块或引用在流式过程中被渲染成普通正文。
 *
 * 三条对齐 pi-web 的规则：
 *  - 围栏只有在「标记后面什么都没写」时才算闭合（#1654 / #1669：代码块里会展示围栏本身）；
 *  - 围栏外的链接引用定义行（`[guide]: https://…`、`[^1]: …`）让整篇不再切分（#1656）；
 *  - 围栏内的定义行不享受上面那条（它不属于任何块）。
 */
class MarkdownBlockSplitTest {

    @Test
    fun `two paragraphs split at the blank line`() {
        val blocks = splitTopLevelMarkdownBlocks("第一段\n\n第二段")

        Assert.assertEquals(2, blocks.size)
        // 空行属于前一块（块尾保留分隔符），拼接回去才能与原文逐字相等。
        Assert.assertEquals("第一段\n\n", blocks[0])
        Assert.assertEquals("第二段", blocks[1])
    }

    @Test
    fun `a fence-like line with text after it does not close the block`() {
        val markdown = "```text\n第一段\n\n第二段\n```js 示例\n\n第三段"

        val blocks = splitTopLevelMarkdownBlocks(markdown)

        // 整段仍是一个代码块：` ```js 示例 ` 只是块里的一行内容。
        Assert.assertEquals(1, blocks.size)
    }

    @Test
    fun `a bare closing fence closes the block and the next paragraph splits`() {
        val markdown = "```\ncode\n```\n\n后文"

        val blocks = splitTopLevelMarkdownBlocks(markdown)

        Assert.assertEquals(2, blocks.size)
        Assert.assertEquals("后文", blocks[1])
    }

    @Test
    fun `a closing fence with trailing spaces still closes`() {
        val blocks = splitTopLevelMarkdownBlocks("```\ncode\n```   \n\n后文")

        Assert.assertEquals(2, blocks.size)
    }

    @Test
    fun `a tilde fence inside a backtick block does not close it`() {
        val blocks = splitTopLevelMarkdownBlocks("```\na\n~~~\n\nb")

        Assert.assertEquals(1, blocks.size)
    }

    @Test
    fun `a reference definition outside a fence keeps the message whole`() {
        val markdown = "[guide]: https://example.com\n\n正文引用了 [guide]"

        val blocks = splitTopLevelMarkdownBlocks(markdown)

        Assert.assertEquals(1, blocks.size)
        Assert.assertEquals(markdown, blocks.single())
    }

    @Test
    fun `a footnote definition outside a fence keeps the message whole`() {
        val blocks = splitTopLevelMarkdownBlocks("正文[^1]\n\n[^1]: 脚注内容")

        Assert.assertEquals(1, blocks.size)
    }

    @Test
    fun `a definition line inside a fence does not disable splitting`() {
        val markdown = "```\n[1]: x\n```\n\n后文"

        val blocks = splitTopLevelMarkdownBlocks(markdown)

        Assert.assertEquals(2, blocks.size)
    }

    @Test
    fun `code containing a slice subscript is not a definition`() {
        // `items[1]:` 不以 `[` 开头：上游第一次修这里时 bail-out 写成了「任何 ]:」，
        // 结果普通代码回复全篇不切分（14k 的回复解析量从 39k 涨到 4.9M 字符）。
        val blocks = splitTopLevelMarkdownBlocks("for x in items[1:]:\n\n后文")

        Assert.assertEquals(2, blocks.size)
    }

    @Test
    fun `list items are not split points`() {
        val blocks = splitTopLevelMarkdownBlocks("- 第一项\n\n- 第二项")

        Assert.assertEquals(1, blocks.size)
    }
}
