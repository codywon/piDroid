package com.pi.app.ui.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码查看器分块的回归测试。
 *
 * 守护的现象：打开几千行的源文件时首帧排版明显变慢——整份文本被塞进单个 Text，
 * LazyColumn 无法按行懒加载，只能一次性排版整份文件。
 */
class SourceTextChunkerTest {

    @Test
    fun `空文本没有分块`() {
        assertTrue(SourceTextChunker.chunk("").isEmpty())
    }

    @Test
    fun `小文件保持单块原样`() {
        val text = "fun main() {\n    println(1)\n}\n"
        assertEquals(listOf(text), SourceTextChunker.chunk(text, linesPerChunk = 120))
    }

    @Test
    fun `大文件按行切块且不改变内容`() {
        val text = (1..500).joinToString("\n") { "line $it" }
        val chunks = SourceTextChunker.chunk(text, linesPerChunk = 120)

        assertEquals(5, chunks.size)
        // 边界用 item 之间的换行体现，所以用 \n 重新拼接必须和原文完全一致
        assertEquals(text, chunks.joinToString("\n"))
        chunks.forEach { chunk ->
            assertTrue(chunk.split('\n').size <= 120)
        }
    }

    @Test
    fun `保留结尾换行`() {
        val text = (1..130).joinToString("\n") { "line $it" } + "\n"
        val chunks = SourceTextChunker.chunk(text, linesPerChunk = 60)
        assertEquals(text, chunks.joinToString("\n"))
    }

    @Test
    fun `非法块大小退化为单块`() {
        val text = "a\nb\nc"
        assertEquals(listOf(text), SourceTextChunker.chunk(text, linesPerChunk = 0))
    }
}
