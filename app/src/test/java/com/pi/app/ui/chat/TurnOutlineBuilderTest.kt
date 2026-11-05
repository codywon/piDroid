package com.pi.app.ui.chat

import com.pi.app.data.model.ChatItem
import com.pi.app.data.model.MessageRole
import com.pi.app.data.model.ToolCallInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合大纲构建的回归测试。
 *
 * 守的核心是一条**缓存陈旧**的故障：`ChatScreen` 原来把大纲包在
 * `remember(size, 首尾 id, 尾部 content/thinking 长度, isAgentBusy)` 里，而这套 key
 * 对「非尾部元素就地更新」完全无感。两个真实现象都出在这里：
 *
 * 1. 工具结果回来走 `messages[idx] = existing.copy(toolCall = …)` —— 长度不变、
 *    首尾 id 不变、TOOL 项 `content` 恒为 `""`（长度恒 0），于是大纲继续用旧快照，
 *    工具卡片永远转圈；
 * 2. 模型把正文回填给前面（非尾部）的 AGENT 气泡时，界面上的旧正文被缓存吃掉。
 *
 * 现在大纲改为每次基于传入列表重算（[buildTurnOutlines]）。本测试直接断言
 * 「喂进改过的列表就能看到改动」—— 只要有人再把 `remember` 那套 key 加回来，
 * 这两个用例就会红。
 */
class TurnOutlineBuilderTest {

    private var clock = 1_000L

    private fun user(text: String) = ChatItem(
        id = "u-$text",
        role = MessageRole.USER,
        content = text,
        timestamp = 10_000L
    )

    private fun agent(text: String) = ChatItem(
        id = "a-$text-${clock++}",
        role = MessageRole.AGENT,
        content = text,
        timestamp = 10_000L + clock
    )

    private fun toolCard(
        toolCallId: String,
        running: Boolean,
        output: String? = null
    ) = ChatItem(
        id = "tool-$toolCallId",
        role = MessageRole.TOOL,
        content = "",
        timestamp = 10_000L + clock++,
        toolCall = ToolCallInfo(
            toolCallId = toolCallId,
            toolName = "bash",
            command = "npm test",
            output = output,
            isRunning = running
        )
    )

    @Test
    fun `tool result applied in place reaches the outline`() {
        // 回合进行中：用户提问 → 工具卡（还在跑）
        val running = listOf(user("跑测试"), toolCard("t1", running = true))
        val before = buildTurnOutlines(running, isAgentBusy = true)
        assertEquals(1, before.size)
        val runningCard = before.single().processItems.single()
        assertTrue(runningCard.toolCall!!.isRunning)

        // 工具结果回来：同一下标就地替换，列表长度与首尾 id 都没变
        val finished = listOf(
            user("跑测试"),
            toolCard("t1", running = false, output = "ok")
        )
        assertEquals(running.size, finished.size)
        assertEquals(running.first().id, finished.first().id)
        assertEquals(running.last().id, finished.last().id)

        val after = buildTurnOutlines(finished, isAgentBusy = true)
        val finishedCard = after.single().processItems.single()
        assertFalse("工具跑完后大纲仍认为在运行 —— 缓存陈旧回归", finishedCard.toolCall!!.isRunning)
        assertEquals("ok", finishedCard.toolCall.output)
    }

    @Test
    fun `backfill into a non tail agent bubble is visible`() {
        // 模型先说一句、再跑工具、又补一句：正文落在**非尾部**项上
        val first = listOf(
            user("重构"),
            agent("我先看一下代码"),
            toolCard("t1", running = true)
        )
        val before = buildTurnOutlines(first, isAgentBusy = true)
        val midAgent = before.single().processItems.first { it.role == MessageRole.AGENT }
        assertEquals("我先看一下代码", midAgent.content)

        // 中间那条 AGENT 的正文被回填变长，尾部工具卡一个字段都没动
        val backfilled = listOf(
            user("重构"),
            agent("我先看一下代码，然后逐个文件改"),
            toolCard("t1", running = true)
        )
        val after = buildTurnOutlines(backfilled, isAgentBusy = true)
        val updated = after.single().processItems.first { it.role == MessageRole.AGENT }
        assertEquals("我先看一下代码，然后逐个文件改", updated.content)
    }

    @Test
    fun `finished turn promotes the trailing text to final answer`() {
        val conversation = listOf(
            user("问题"),
            toolCard("t1", running = false, output = "ok"),
            agent("答案")
        )
        val turn = buildTurnOutlines(conversation, isAgentBusy = false).single()

        assertNotNull(turn.finalAnswerItem)
        assertEquals("答案", turn.finalAnswerItem!!.content)
        // 处理项里不能把最终答复重复算一次，也不能把工具卡丢掉
        assertEquals(1, turn.processItems.size)
        assertEquals(MessageRole.TOOL, turn.processItems.single().role)
        assertFalse(turn.isRunning)
    }

    @Test
    fun `running turn keeps everything in process until answered`() {
        val conversation = listOf(
            user("问题"),
            toolCard("t1", running = true),
            agent("正在处理")
        )
        val turn = buildTurnOutlines(conversation, isAgentBusy = true).single()

        // pi-web：未答复回合里正文只是过程的一部分，不单独提成最终答复
        assertNull(turn.finalAnswerItem)
        assertEquals(2, turn.processItems.size)
        assertTrue(turn.isRunning)
    }

    @Test
    fun `tool count skips internal visualize tool and counts tool cards`() {
        val internal = ChatItem(
            id = "internal",
            role = MessageRole.TOOL,
            content = "",
            timestamp = 10_000L,
            toolCall = ToolCallInfo(toolName = "visualize_read_me", isRunning = false)
        )
        val conversation = listOf(
            user("问题"),
            toolCard("t1", running = false),
            internal,
            toolCard("t2", running = false),
            agent("答案")
        )
        val turn = buildTurnOutlines(conversation, isAgentBusy = false).single()

        // visualize_read_me 是插件内部步骤，不该被算进「M 次工具调用」
        assertEquals(2, turn.toolCount)
    }

    @Test
    fun `empty and user only conversations produce no turns`() {
        assertTrue(buildTurnOutlines(emptyList(), isAgentBusy = false).isEmpty())
        // 只有系统/助手消息、没有任何用户提问时也没有回合（首屏骨架靠 leadingRowCount）
        val noUser = listOf(agent("欢迎使用"))
        assertTrue(buildTurnOutlines(noUser, isAgentBusy = false).isEmpty())
    }

    @Test
    fun `turn numbering and user index follow user messages`() {
        val conversation = listOf(
            agent("开场白"),
            user("第一问"),
            agent("第一答"),
            user("第二问"),
            agent("第二答")
        )
        val turns = buildTurnOutlines(conversation, isAgentBusy = false)

        assertEquals(2, turns.size)
        assertEquals(1, turns[0].turnNumber)
        assertEquals(1, turns[0].userIndex)
        assertEquals(2, turns[1].turnNumber)
        assertEquals(3, turns[1].userIndex)
        assertEquals("第一问", turns[0].userMessage.content)
    }
}
