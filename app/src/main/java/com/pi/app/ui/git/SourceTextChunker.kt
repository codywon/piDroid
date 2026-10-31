package com.pi.app.ui.git

/**
 * 源码查看器的分块策略（纯 JVM，可单测）。
 *
 * 病根：`GitDetailMode.SOURCE` 把整份源码塞进**一个** `Text`，LazyColumn 只能把它
 * 当成一个不可拆分的 item，无法按行懒加载，首帧需要一次性排版整份文件。
 *
 * 这里按行切块，每块一个 item，让 LazyColumn 真正懒起来。切块点用换行代替（块内用
 * `\n` 连接、块与块之间由 item 纵向排列体现换行），所以视觉上与整段文本一致，
 * 不会在块边界多出空行。
 */
object SourceTextChunker {

    /** 每块行数：太小会让 item 数暴涨，太大又回到单块测量的老问题。 */
    const val DEFAULT_LINES_PER_CHUNK = 120

    fun chunk(text: String, linesPerChunk: Int = DEFAULT_LINES_PER_CHUNK): List<String> {
        if (text.isEmpty()) return emptyList()
        if (linesPerChunk <= 0) return listOf(text)
        val lines = text.split('\n')
        if (lines.size <= linesPerChunk) return listOf(text)
        return lines.chunked(linesPerChunk).map { it.joinToString("\n") }
    }
}
