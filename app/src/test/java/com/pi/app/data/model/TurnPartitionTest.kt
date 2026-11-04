package com.pi.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合拆分（过程组 / 最终答复）的回归测试。
 *
 * 守护的线上现象：长任务里模型「先说一句、再继续跑工具」，手机端界面永远停在
 * 那句正文下面只剩「正在等待模型…」，而 PC 上已经又跑了好几步 ——
 * 旧实现把最终答复之后的过程项整段丢掉了。
 */
class TurnPartitionTest {

    private fun agent(id: String, text: String = "") =
        ChatItem(id = id, role = MessageRole.AGENT, content = text)

    private fun tool(id: String) = ChatItem(
        id = id,
        role = MessageRole.TOOL,
        content = "",
        toolCall = ToolCallInfo(toolCallId = id, toolName = "bash")
    )

    @Test
    fun `在跑的回合里正文之后的过程项一个都不能丢`() {
        val items = listOf(tool("t1"), agent("a1", "82 张表已逐项完成"), tool("t2"), tool("t3"))

        val split = TurnPartition.split(items, isCurrentRunning = true, turnHasWidget = false)

        assertNull("在跑时正文只是过程的一部分（pi-web answered=false）", split.finalAnswerIndex)
        assertEquals("四项目录必须全部保留", listOf(0, 1, 2, 3), split.processIndices)
    }

    @Test
    fun `在跑且有生成式UI卡片时正文提为最终答复但尾部过程项仍保留`() {
        val items = listOf(agent("a1", "先说一句"), tool("t1"))

        val split = TurnPartition.split(items, isCurrentRunning = true, turnHasWidget = true)

        assertEquals("正文提出来，避免卡片被流式文字推着跳", 0, split.finalAnswerIndex)
        assertEquals("尾部过程项不能丢", listOf(1), split.processIndices)
    }

    @Test
    fun `答完的回合才把最后一句正文作为最终答复`() {
        val items = listOf(tool("t1"), agent("a1", "中间说明"), tool("t2"), agent("a2", "最终答复"))

        val split = TurnPartition.split(items, isCurrentRunning = false, turnHasWidget = false)

        assertEquals(3, split.finalAnswerIndex)
        assertEquals(listOf(0, 1, 2), split.processIndices)
    }

    @Test
    fun `答完但正文后面还有过程项时不做拆分也不丢项`() {
        val items = listOf(agent("a1", "最终说明"), tool("t1"))

        val split = TurnPartition.split(items, isCurrentRunning = false, turnHasWidget = false)

        assertNull(split.finalAnswerIndex)
        assertEquals(listOf(0, 1), split.processIndices)
    }

    @Test
    fun `没有正文的回合全部是过程项`() {
        val items = listOf(tool("t1"), tool("t2"))

        val split = TurnPartition.split(items, isCurrentRunning = false, turnHasWidget = false)

        assertNull(split.finalAnswerIndex)
        assertEquals(2, split.processIndices.size)
        assertTrue(TurnPartition.split(emptyList(), false, false).processIndices.isEmpty())
    }
}
