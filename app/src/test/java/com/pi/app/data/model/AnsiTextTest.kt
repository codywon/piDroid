package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `stripAnsi` 的口径对齐 pi-web `lib/ansi.ts`（#1753）。
 *
 * 用例守的是「正文/思考里不该出现裸转义」这件事，以及两条容易踩的边界：
 * 1. pi-tui 的零宽光标标记（`\x1b_pi:c\x07` / `\x1b_pi:fc\x07`）也必须一起去掉，
 *    否则模型回答里会露出 `_pi:c` 这种残渣；
 * 2. 没有 ESC 的普通文本必须原样返回（这是每条消息都会走的热路径）。
 */
class AnsiTextTest {

    @Test
    fun `strips SGR colour sequences`() {
        assertEquals("red plain", stripAnsi("\u001B[31mred\u001B[0m plain"))
        assertEquals(
            "Thinking: deeper",
            stripAnsi("\u001B[38;5;5mThinking:\u001B[39m \u001B[2mdeeper\u001B[0m")
        )
    }

    @Test
    fun `strips pi-tui cursor markers and keeps the text around them`() {
        assertEquals("answer", stripAnsi("answer\u001B_pi:c\u0007"))
        assertEquals("answer中", stripAnsi("answer\u001B_pi:fc\u0007中\u001B_pi:/fc\u0007"))
    }

    @Test
    fun `strips OSC titles`() {
        assertEquals("title body", stripAnsi("\u001B]0;title\u0007title body"))
        // OSC 8 超链接：只留链接文字
        assertEquals("链接", stripAnsi("\u001B]8;;http://x\u0007链接\u001B]8;;\u0007"))
    }

    @Test
    fun `strips cursor movement and single character escapes`() {
        assertEquals("xy", stripAnsi("x\u001B[1;2Hy"))
        assertEquals("ab", stripAnsi("a\u001BMb"))
    }

    @Test
    fun `returns plain text untouched`() {
        val text = "# 标题\n\n正文 with [brackets] and 中文"
        assertEquals(text, stripAnsi(text))
    }

    @Test
    fun `an unterminated marker does not swallow the following text`() {
        // 标记永远不跨行：没有 BEL/ST 结束符时后面的正文全部保留，ESC 本身仍按两字符转义剥掉
        // （与 pi-web 的 `stripAnsi` 逐字一致："before\u001B_pi:c" → "beforepi:c"）。
        assertEquals("beforepi:c", stripAnsi("before\u001B_pi:c"))
        assertEquals("line1pi:c\nline2", stripAnsi("line1\u001B_pi:c\nline2"))
    }
}
