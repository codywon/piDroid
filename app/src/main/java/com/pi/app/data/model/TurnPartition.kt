package com.pi.app.data.model

/**
 * 一个回合（两次用户提问之间）如何拆成「过程组 + 最终答复」（纯 JVM，可单测）。
 *
 * 对齐 pi-web：**只有「已答复」的回合**才有独立的最终答复；未答复（在跑）的回合里，
 * 正文只是过程的一部分，和思考/工具卡一起待在过程折叠组里。
 *
 * 这里唯一多出来的一条是「绝不丢项」：旧实现用 `turnSlice.subList(0, finalAnsIdx)` 取过程项，
 * 于是**最终答复之后的过程项会被整段丢弃** —— 而「先说一句、再继续跑工具」正是长任务的常态，
 * 表现就是「界面永远停在最后一句正文 + 正在等待模型…」，而 PC 上已经又跑了好几步。
 */
object TurnPartition {

    /**
     * @param finalAnswerIndex 被提为「最终答复」的条目下标（null = 本回合没有独立的最终答复）
     * @param processIndices 过程组要渲染的下标（保持原顺序，且**包含除最终答复外的全部条目**）
     */
    data class Split(val finalAnswerIndex: Int?, val processIndices: List<Int>)

    fun split(
        items: List<ChatItem>,
        /** 本回合是不是最后一轮且 Agent 仍在跑 */
        isCurrentRunning: Boolean,
        /** 本回合是否产生了生成式 UI 卡片（它必须紧跟在流式正文下方，不能被折叠组推着跳） */
        turnHasWidget: Boolean
    ): Split {
        val allProcess = Split(null, items.indices.toList())
        if (items.isEmpty()) return allProcess
        val lastTextIndex = items.indexOfLast { it.role == MessageRole.AGENT && it.content.isNotBlank() }
        if (lastTextIndex < 0) return allProcess
        val trailing = items.size - 1 - lastTextIndex
        val shouldSplit = if (isCurrentRunning) {
            // 在跑：只有存在生成式 UI 卡片时才把正文提出来，否则正文就是过程的一部分（pi-web answered=false）
            turnHasWidget
        } else {
            // 答完：正文落在最后一项才算最终答复；后面还有过程项说明这是被打断/旧数据，
            // 全部留在过程组里（渲染顺序略有不同，但不会丢内容）
            trailing == 0
        }
        if (!shouldSplit) return allProcess
        return Split(
            finalAnswerIndex = lastTextIndex,
            processIndices = items.indices.filter { it != lastTextIndex }
        )
    }
}
